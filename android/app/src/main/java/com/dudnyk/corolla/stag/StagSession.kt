package com.dudnyk.corolla.stag

import com.dudnyk.corolla.obd.ByteLink
import com.dudnyk.corolla.obd.Json
import com.dudnyk.corolla.obd.LinkLost
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

enum class StagPhase { OFF, CONNECTING, LIVE, RECONNECTING, STOPPED }

/** Snapshot for the UI. [monitor] is null while no fresh answer (older than 3 s). */
data class StagState(
    val phase: StagPhase,
    val label: String,
    val monitor: StagMonitor?,
    val hz: Double,
    val errorsHex: String?,
    val map: MultiplierMap?,
    /** Result of the last map read/write, for the UI ("" = none yet). */
    val mapMessage: String,
    val mapBusy: Boolean,
    val reconnects: Int,
    val csvRows: Long,
)

/**
 * The Stag connection: keeps the link up (reconnects with back-off whenever it drops - StagMobile's weak
 * spot), polls the monitor ~10 times a second, the error block every 2 s, and runs map reads/writes queued
 * from the UI between polls. While a trip is recorded, every monitor answer goes to `<trip>.stag.csv`.
 * [run] blocks until [stop].
 */
class StagSession(
    private val linkFactory: () -> ByteLink,
    private val log: (String) -> Unit,
    private val onState: (StagState) -> Unit,
    /** Folder for map backups (`stag_map_*.json`), read when a map operation runs. */
    private val mapDir: () -> File,
    private val pollPeriodMs: Long = 100,
) {
    private sealed class Cmd {
        object ReadMap : Cmd()
        class WriteMap(val values: List<Double>, val axis: List<Double>?) : Cmd()
    }

    private val cmds = ConcurrentLinkedQueue<Cmd>()
    @Volatile private var stopRequested = false
    @Volatile var latest: StagMonitor? = null
        private set
    @Volatile private var latestAt = 0L
    private var phase = StagPhase.CONNECTING
    private var label = ""
    private var hz = 0.0
    private var errorsHex: String? = null
    private var map: MultiplierMap? = null
    private var mapMessage = ""
    @Volatile private var mapBusy = false
    private var reconnects = 0
    private var lastPublish = 0L

    // CSV of the trip being recorded (opened/closed from the recorder thread, written here)
    private val csvLock = Object()
    private var csv: Writer? = null
    private var csvStream: FileOutputStream? = null
    private var csvT0 = 0L
    private var csvRows = 0L
    private var csvSync = 0L

    fun stop() { stopRequested = true }

    fun readMap() { mapBusy = true; cmds.add(Cmd.ReadMap); publish(true) }

    fun writeMap(values: List<Double>, axis: List<Double>? = null) { mapBusy = true; cmds.add(Cmd.WriteMap(values, axis)); publish(true) }

    /** Fresh monitor answer (not older than [maxAgeMs]), for the trip recorder. */
    fun fresh(maxAgeMs: Long = 3000): StagMonitor? =
        latest?.takeIf { System.nanoTime() - latestAt < maxAgeMs * 1_000_000 }

    fun startCsv(file: File) = synchronized(csvLock) {
        stopCsvLocked()
        val s = FileOutputStream(file)
        csvStream = s
        csv = OutputStreamWriter(s, Charsets.UTF_8).also { it.write(HEADER.joinToString(",") + "\r\n") }
        csvT0 = System.nanoTime()
        csvRows = 0
        csvSync = System.nanoTime()
    }

    fun stopCsv() = synchronized(csvLock) { stopCsvLocked() }

    private fun stopCsvLocked() {
        try { csv?.close() } catch (_: Exception) {}
        csv = null
        csvStream = null
    }

    private fun writeCsv(m: StagMonitor) = synchronized(csvLock) {
        val w = csv ?: return
        fun f(x: Double, d: Int) = String.format(Locale.ROOT, "%.${d}f", x)
        val row = listOf(
            LocalDateTime.now().format(MILLIS), f((System.nanoTime() - csvT0) / 1e9, 3),
            f(m.mapBar, 2), f(m.gasPBar, 2), m.gasTempC.toString(), m.reducerTempC.toString(), m.rpm.toString(),
            f(m.supplyV, 3), m.status.toString(), if (m.onGas) "1" else "0",
        ) + m.petrolMs.map { f(it, 3) } + m.gasMs.map { f(it, 3) } +
            listOf(f(m.petrolAvg, 3), f(m.gasAvg, 3), m.engineTempC.toString(), errorsHex?.replace(" ", "") ?: "")
        w.write(row.joinToString(",") + "\r\n")
        w.flush()
        csvRows++
        if (System.nanoTime() - csvSync > 5_000_000_000L) {
            csvStream?.fd?.sync()
            csvSync = System.nanoTime()
        }
    }

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastPublish < 300_000_000L) return
        lastPublish = now
        onState(StagState(phase, label, fresh(), hz, errorsHex, map, mapMessage, mapBusy, reconnects,
            synchronized(csvLock) { csvRows }))
    }

    fun run() {
        var client: StagClient? = null
        var backoffS = 2.0
        var everConnected = false
        var timeouts = 0
        var lastErrors = 0L
        var rateN = 0
        var rateT = System.nanoTime()
        phase = StagPhase.CONNECTING
        publish(true)
        try {
            while (!stopRequested) {
                if (client == null) {
                    val c = StagClient(linkFactory(), log)
                    label = c.label
                    try {
                        val hello = c.connect()
                        client = c
                        timeouts = 0
                        backoffS = 2.0
                        if (everConnected) reconnects++
                        everConnected = true
                        phase = StagPhase.LIVE
                        log("STAG connected ${c.label}; hello ${StagFrame.hex(hello)}")
                        publish(true)
                    } catch (e: Exception) {
                        if (e !is LinkLost && e !is java.io.IOException) throw e
                        log("STAG connect failed: ${e.message}; retry in ${String.format(Locale.ROOT, "%.0f", backoffS)}s")
                        try { c.close() } catch (_: Exception) {}
                        phase = if (everConnected) StagPhase.RECONNECTING else StagPhase.CONNECTING
                        publish(true)
                        sleep((backoffS * 1000).toLong())
                        backoffS = minOf(backoffS * 1.5, 15.0)
                        continue
                    }
                }
                try {
                    val t0 = System.nanoTime()
                    cmds.poll()?.let { runCmd(client, it) }
                    val m = client.monitor()
                    if (m == null) {
                        timeouts++
                        if (timeouts >= 3) throw LinkLost("no monitor answer 3 times")
                    } else {
                        timeouts = 0
                        latest = m
                        latestAt = System.nanoTime()
                        writeCsv(m)
                        rateN++
                        val dt = (System.nanoTime() - rateT) / 1e9
                        if (dt >= 2.0) { hz = rateN / dt; rateN = 0; rateT = System.nanoTime() }
                    }
                    if (System.nanoTime() - lastErrors > 2_000_000_000L) {
                        lastErrors = System.nanoTime()
                        client.errors()?.let { e ->
                            val h = StagFrame.hex(e.copyOfRange(1, e.size))
                            if (h != errorsHex) {
                                if (errorsHex != null || e.drop(1).any { it.toInt() != 0 }) log("STAG error block: $h")
                                errorsHex = h
                            }
                        }
                    }
                    publish()
                    val left = pollPeriodMs - (System.nanoTime() - t0) / 1_000_000
                    if (left > 0) sleep(left)
                } catch (e: Exception) {
                    if (e !is LinkLost && e !is java.io.IOException) throw e
                    log("STAG link problem: ${e.message} -> reconnect")
                    try { client.close() } catch (_: Exception) {}
                    client = null
                    phase = StagPhase.RECONNECTING
                    publish(true)
                }
            }
        } finally {
            try { client?.close() } catch (_: Exception) {}
            stopCsv()
            phase = StagPhase.STOPPED
            log("STAG session stop")
            publish(true)
        }
    }

    private fun runCmd(c: StagClient, cmd: Cmd) {
        try {
            when (cmd) {
                is Cmd.ReadMap -> {
                    map = c.readMap()
                    mapMessage = "Прочитано ${LocalDateTime.now().format(HHMMSS)}"
                    log("STAG map read: ${map!!.values}")
                }
                is Cmd.WriteMap -> {
                    var backup: File? = null
                    val after = c.writeMap(cmd.values, cmd.axis) { before ->
                        map = before
                        backup = saveMap(before, "before")
                    }
                    map = after
                    val saved = saveMap(after, "after")
                    mapMessage = "Записано й перевірено ${LocalDateTime.now().format(HHMMSS)}; копії: ${backup?.name}, ${saved.name}"
                    log("STAG map WRITE ok: axis ${after.axisMs} values ${after.values} (before in ${backup?.name})")
                }
            }
        } catch (e: StagError) {
            mapMessage = "Помилка: ${e.message}"
            log("STAG map ${if (cmd is Cmd.WriteMap) "write" else "read"} failed: ${e.message}")
        } catch (e: Exception) {
            mapMessage = "Зв'язок обірвався під час операції з картою — після перепідключення прочитай карту ще раз"
            mapBusy = false
            throw e
        }
        mapBusy = false
        publish(true)
    }

    private fun saveMap(m: MultiplierMap, tag: String): File {
        val dir = mapDir().apply { mkdirs() }
        val stamp = LocalDateTime.now().format(STAMP)
        val f = File(dir, "stag_map_${stamp}_$tag.json")
        f.writeText(Json.dumps(m.toJson(mapOf("saved" to LocalDateTime.now().format(MILLIS), "what" to tag))), Charsets.UTF_8)
        return f
    }

    private fun sleep(ms: Long) {
        val end = System.nanoTime() + ms * 1_000_000
        while (!stopRequested && System.nanoTime() < end) {
            if (cmds.isNotEmpty() && phase == StagPhase.LIVE) return
            Thread.sleep(minOf(50L, (end - System.nanoTime()) / 1_000_000 + 1))
        }
    }

    companion object {
        val HEADER = listOf("time_iso", "t_s", "map_bar", "gas_p_bar", "gas_temp_c", "reducer_temp_c", "rpm", "supply_v",
            "status", "on_gas", "petrol_ms_1", "petrol_ms_2", "petrol_ms_3", "petrol_ms_4",
            "gas_ms_1", "gas_ms_2", "gas_ms_3", "gas_ms_4", "petrol_ms", "gas_ms", "engine_temp_emul_c", "errors")
        private val MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        private val HHMMSS = DateTimeFormatter.ofPattern("HH:mm:ss")
    }
}
