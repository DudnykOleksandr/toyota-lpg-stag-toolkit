package com.dudnyk.corolla.obd

import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Marker buttons. Fuel (gas/petrol) and road (city/highway) are exclusive within their group;
 * A/C and idle are separate on/off tags, so "city + petrol + idle" can all be set at once.
 * Keys as in the laptop logger where they exist: `g b a s h c i m`.
 */
enum class Mark(val label: String, val stateKey: String?, val value: String?) {
    GAS("gas", "fuel", "gas"),
    PETROL("petrol", "fuel", "petrol"),
    AC_ON("ac_on", "ac", "on"),
    AC_OFF("ac_off", "ac", "off"),
    HIGHWAY("highway", "road", "highway"),
    CITY("city", "road", "city"),
    IDLE_ON("idle", "idle", "on"),
    IDLE_OFF("idle_off", "idle", "off"),
    GENERIC("m", null, null),
}

/** CONNECTING / RECONNECTING: no data; LIVE: dashboard only; RECORDING: dashboard + trip files. */
enum class Phase { CONNECTING, LIVE, RECORDING, RECONNECTING, STOPPED }

/** Snapshot for the UI and the notification. */
data class RecorderStatus(
    val phase: Phase,
    val connected: Boolean,
    val recording: Boolean,
    val sessionS: Double,
    val hz: Double,
    /** Current (or last) trip: base name, seconds, rows. Empty name = nothing recorded yet. */
    val baseName: String,
    val tripS: Double,
    val rows: Long,
    val state: Map<String, String>,
    val values: Map<String, Any?>,
    val elm: String,
    val protocol: String,
    val lastEvents: List<String>,
)

/**
 * One connection session to the ELM that always polls (the dashboard), and trips inside it that are
 * written only while recording is on. A trip = `elm_YYYYMMDD_HHMMSS.csv` + `.log` + `.meta.json` +
 * `.health.json` in [dir], the same files and columns as `logger/elm_logger.py` writes into `logs/`
 * (plus the `idle` tag column). Port of its `Logger` class.
 * [run] blocks until [stop] is called (or the ECU has been silent for [Options.silentStopMin]).
 */
class TripRecorder(
    private val dir: File,
    private val transportFactory: () -> Transport,
    private val options: Options = Options(),
    private val onStatus: (RecorderStatus) -> Unit = {},
    /** Called with the trip's base path (`dir/elm_…`, no suffix) when a trip starts, null when it ends. */
    private val tripListener: (File?) -> Unit = {},
    /** "gas" / "petrol" from the Stag controller while it is connected, else null: sets the fuel tag by itself. */
    private val stagFuel: () -> String? = { null },
) {
    data class Options(
        val sim: Boolean = false,
        val single: Boolean = false,
        val slowPeriodS: Double = 4.0,
        val noHealth: Boolean = false,
        /** Stop by itself after the ECU was answering and then stayed silent this long (engine off). 0 = never. */
        val silentStopMin: Double = 5.0,
        val readAtf: Boolean = true,
        val source: String = "android",
        /** Start a trip as soon as the ELM answers (otherwise only the dashboard until [setRecording]). */
        val recordFromStart: Boolean = false,
    )

    /** Open files of the trip being recorded. */
    private inner class Trip(val baseName: String) {
        val t0 = System.nanoTime()
        val eventLog: Writer = OutputStreamWriter(FileOutputStream(file(".log"), true), Charsets.UTF_8)
        var csvStream: FileOutputStream? = null
        var csv: Writer? = null
        var header = listOf<String>()
        val health = LinkedHashMap<String, Any?>()
        var rows = 0L
        var lastFsync = System.nanoTime()
        val pendingMarker = mutableListOf<String>()

        fun file(suffix: String) = File(dir, baseName + suffix)
        fun elapsed() = (System.nanoTime() - t0) / 1e9
    }

    private val state = linkedMapOf("fuel" to "unknown", "ac" to "unknown", "road" to "unknown", "idle" to "unknown")
    private var markNo = 0
    private val values = LinkedHashMap<String, Any?>()
    private val t0 = System.nanoTime()
    private var columns = listOf<String>()
    private var fastGroups = listOf<List<Int>>()
    private var slowGroups = listOf<List<Any>>()     // PID lists, or ["RV"] / ["ATF"] pseudo groups
    private var supported = listOf<Int>()
    private var rateN = 0
    private var rateT = System.nanoTime()
    private var hz = 0.0
    private val marks = ConcurrentLinkedQueue<Mark>()
    @Volatile private var stopRequested = false
    @Volatile private var wantRecording = options.recordFromStart
    private var phase = Phase.CONNECTING
    private var elmVersion = ""
    private var elmProtocol = ""
    private var connected = false
    private val events = ArrayDeque<String>()
    /** Lines about the current connection (ELM, PIDs, groups): copied into each new trip's `.log`. */
    private val setupLines = mutableListOf<String>()
    private var trip: Trip? = null
    private var lastTrip: Trip? = null
    private var lastStatus = 0L
    private val label: String by lazy { transportFactory().label }

    // ---- controls (any thread) ------------------------------------------
    fun mark(m: Mark) { marks.add(m) }

    fun stop() { stopRequested = true }

    /** Start or stop writing trip files; the connection and the dashboard keep running. */
    fun setRecording(on: Boolean) { wantRecording = on }

    val baseName: String get() = (trip ?: lastTrip)?.baseName ?: ""

    // ---- helpers ---------------------------------------------------------
    fun log(msg: String, setup: Boolean = false) {
        val line = LocalDateTime.now().format(SECONDS) + " " + msg
        synchronized(events) {
            events.addLast(line)
            while (events.size > 12) events.removeFirst()
        }
        if (setup) setupLines.add(line)
        trip?.let {
            it.eventLog.write(line + "\n")
            it.eventLog.flush()
        }
    }

    private fun elapsed() = (System.nanoTime() - t0) / 1e9

    private fun publish(force: Boolean = false) {
        val now = System.nanoTime()
        if (!force && now - lastStatus < 500_000_000L) return
        lastStatus = now
        val t = trip ?: lastTrip
        onStatus(RecorderStatus(phase, connected, trip != null, elapsed(), hz, t?.baseName ?: "", t?.elapsed() ?: 0.0,
            t?.rows ?: 0, LinkedHashMap(state), LinkedHashMap(values), elmVersion, elmProtocol,
            synchronized(events) { events.toList() }))
    }

    // ---- setup -----------------------------------------------------------
    private fun setupPids(elm: Elm327) {
        val sup = elm.supportedPids()
        val s = { m: String -> log(m, setup = true) }
        s("supported PIDs: " + sup.joinToString(" ") { Pids.hex(it) })
        val known = sup.filter { it in Pids.ALL }
        s("   of them known to logger: " + known.joinToString(", ") { Pids.ALL.getValue(it).name })
        val fast = Pids.FAST.filter { it in sup }.toMutableList()
        if (0x14 !in sup) {
            val wb = Pids.FAST_WIDEBAND.filter { it in sup }
            if (wb.isNotEmpty()) {
                fast.add(wb[0])
                s("no narrow-band O2 B1S1 - using wide-band PID ${Pids.hex(wb[0])}")
            }
        }
        val slow = Pids.SLOW.filter { it in sup }
        val missing = Pids.FAST.filter { it !in sup }.map { Pids.ALL.getValue(it).name }
        if (missing.isNotEmpty()) s("WARNING fast PIDs not supported: " + missing.joinToString(", "))

        var fg: List<List<Int>> = emptyList()
        var sg: MutableList<List<Any>> = mutableListOf()
        if (elm.isCan && !options.single) {
            fg = Pids.packGroups(fast)
            sg = Pids.packGroups(slow).toMutableList()
            if (!verifyGroups(elm, fg)) {
                s("multi-PID requests not answered correctly - falling back to 1 PID/request")
                elm.multiPidOk = false
            }
        }
        if (!elm.isCan || options.single || !elm.multiPidOk) {
            fg = fast.map { listOf(it) }
            sg = slow.map { listOf<Any>(it) }.toMutableList()
        }
        sg.add(listOf("RV"))
        if (options.readAtf && !options.sim && elm.isCan && elm.readAtf() != null) sg.add(listOf("ATF"))
        fastGroups = fg
        slowGroups = sg
        s("fast groups: " + fg.joinToString(" | ") { g -> g.joinToString(",") { Pids.ALL.getValue(it).name } })
        s("slow groups: " + sg.joinToString(" | ") { g -> g.joinToString(",") { pseudoOrName(it) } })

        val cols = mutableListOf<String>()
        for (g in fg + sg) {
            for (p in g) {
                for (c in if (p is String) listOf(PSEUDO.getValue(p)) else Pids.columnsFor(p as Int)) {
                    if (c !in cols) cols.add(c)
                }
            }
        }
        if (trip != null && cols != columns) log("PID set changed after reconnect - keeping original columns")
        columns = cols
        supported = sup
    }

    private fun pseudoOrName(p: Any) = if (p is String) PSEUDO.getValue(p) else Pids.ALL.getValue(p as Int).name

    private fun verifyGroups(elm: Elm327, groups: List<List<Int>>): Boolean {
        for (g in groups) {
            var got = elm.queryRaw(g)
            if (got.keys != g.toSet()) {
                // try once more: the first request after init is sometimes slow
                got = elm.queryRaw(g)
                if (got.keys != g.toSet()) {
                    log("group ${Py.str(g.map { Pids.hex(it) })} -> got ${Py.str(got.keys.map { Pids.hex(it) })}", setup = true)
                    return false
                }
            }
        }
        return true
    }

    // ---- trips -----------------------------------------------------------------
    private fun startTrip(elm: Elm327) {
        var stamp = LocalDateTime.now().format(STAMP)
        // two trips in the same second would share files
        if (lastTrip?.baseName?.startsWith("elm_$stamp") == true) { Thread.sleep(1000); stamp = LocalDateTime.now().format(STAMP) }
        dir.mkdirs()
        val t = Trip("elm_$stamp" + if (options.sim) "_sim" else "")
        trip = t
        log("logger start; sim=${Py.bool(options.sim)} port=$label")
        for (line in setupLines) t.eventLog.write(line + "\n")
        log("state at start: ${stateRepr()}")
        val path = t.file(".csv")
        val stream = FileOutputStream(path)
        t.csvStream = stream
        t.csv = OutputStreamWriter(stream, Charsets.UTF_8)
        t.header = listOf("time_iso", "t_s", "marker", "fuel", "ac", "road", "idle", "hz") + columns
        csvLine(t, t.header)
        val meta = linkedMapOf<String, Any?>(
            "started" to LocalDateTime.now().format(MICROS),
            "elm" to elm.version,
            "protocol" to elm.protocol,
            "supported_pids" to supported.map { Pids.hex(it) },
            "fast_groups" to fastGroups.map { g -> g.map { Pids.hex(it) } },
            "slow_groups" to slowGroups.map { g -> g.map { if (it is String) it else Pids.hex(it as Int) } },
            "sim" to options.sim,
            "source" to options.source,
        )
        t.file(".meta.json").writeText(Json.dumps(meta), Charsets.UTF_8)
        log("writing ${path.path}")
        try { tripListener(File(dir, t.baseName)) } catch (e: Exception) { log("trip listener: $e") }
        snapshotHealth(elm, "start")
    }

    private fun endTrip(elm: Elm327?) {
        val t = trip ?: return
        if (elm != null) snapshotHealth(elm, "end")
        log("logger stop")
        try { tripListener(null) } catch (_: Exception) {}
        try { t.csv?.close() } catch (_: Exception) {}
        try { t.eventLog.close() } catch (_: Exception) {}
        lastTrip = t
        trip = null
    }

    // ---- engine health snapshot (read-only) --------------------------------
    /** Taken at the start and at the end of a trip (current-cycle misfire counts are most useful then). */
    private fun snapshotHealth(elm: Elm327, whenTag: String) {
        val t = trip ?: return
        if (options.noHealth || supported.isEmpty()) return
        val h = try {
            Health.read(elm, supported, log = { log(it) })
        } catch (e: LinkLost) {
            log("health snapshot ($whenTag) lost link: ${e.message}")
            return
        }
        t.health[whenTag] = h
        t.file(".health.json").writeText(Json.dumps(t.health), Charsets.UTF_8)
        log("engine health ($whenTag):")
        for (line in Health.format(h)) log("  $line")
    }

    // ---- markers -----------------------------------------------------------
    private fun handleMark(m: Mark) {
        var label = m.label
        if (m == Mark.GENERIC) {
            markNo += 1
            label = "m$markNo"
        } else {
            state[m.stateKey!!] = m.value!!
        }
        trip?.pendingMarker?.add(label)
        val t = String.format(Locale.ROOT, "%.1f", trip?.elapsed() ?: elapsed())
        log("MARK $label at t=${t}s  state=${stateRepr()}")
        publish(force = true)
    }

    private fun stateRepr() = state.entries.joinToString(", ", "{", "}") { "'${it.key}': '${it.value}'" }

    private fun drainMarks() {
        while (true) handleMark(marks.poll() ?: break)
        val f = try { stagFuel() } catch (_: Exception) { null } ?: return
        if (f != state["fuel"]) {
            state["fuel"] = f
            trip?.pendingMarker?.add(f)
            val t = String.format(Locale.ROOT, "%.1f", trip?.elapsed() ?: elapsed())
            log("MARK $f at t=${t}s (Stag status)  state=${stateRepr()}")
            publish(force = true)
        }
    }

    // ---- CSV -----------------------------------------------------------------
    private fun csvLine(t: Trip, fields: List<String>) {
        val w = t.csv ?: return
        w.write(fields.joinToString(",") { csvField(it) })
        w.write("\r\n")                        // Python csv module default line terminator
        w.flush()
    }

    private fun writeRow(t: Trip) {
        val row = HashMap<String, String>()
        row["time_iso"] = LocalDateTime.now().format(MILLIS)
        row["t_s"] = String.format(Locale.ROOT, "%.3f", t.elapsed())
        row["marker"] = t.pendingMarker.joinToString(";")
        row["hz"] = String.format(Locale.ROOT, "%.2f", hz)
        row.putAll(state)
        for ((k, v) in values) row[k] = cell(v)
        t.pendingMarker.clear()
        csvLine(t, t.header.map { row[it] ?: "" })
        t.rows += 1
        if (System.nanoTime() - t.lastFsync > 5_000_000_000L) {
            t.csvStream?.fd?.sync()
            t.lastFsync = System.nanoTime()
        }
    }

    private fun tickRate() {
        rateN += 1
        val dt = (System.nanoTime() - rateT) / 1e9
        if (dt >= 2.0) {
            hz = rateN / dt
            rateN = 0
            rateT = System.nanoTime()
        }
    }

    // ---- main loop -----------------------------------------------------------
    fun run() {
        log("session start; sim=${Py.bool(options.sim)} port=$label")
        var elm: Elm327? = null
        var slowI = 0
        var lastSlow = 0L
        var fails = 0
        var backoffS = 2.0
        var lastData = 0L                      // nanoTime of the last answer; 0 = never had data
        phase = Phase.CONNECTING
        publish(force = true)
        try {
            while (true) {
                drainMarks()
                if (stopRequested) break
                if (lastData != 0L && options.silentStopMin > 0 &&
                    System.nanoTime() - lastData > (options.silentStopMin * 60e9).toLong()) {
                    log("ECU silent for ${Py.g(options.silentStopMin)} min - stopping")
                    break
                }
                // --- recording on/off (files are opened and closed on this thread only) ---
                if (!wantRecording && trip != null) endTrip(elm)
                if (wantRecording && trip == null && elm != null) startTrip(elm)
                // --- connect ---
                if (elm == null) {
                    val e = Elm327(transportFactory(), { log(it, setup = true) })
                    try {
                        setupLines.clear()
                        e.init()
                        elmVersion = e.version
                        elmProtocol = e.protocol
                        setupPids(e)
                        elm = e
                        connected = true
                        fails = 0
                        backoffS = 2.0
                        phase = if (trip != null) Phase.RECORDING else Phase.LIVE
                        if (wantRecording && trip == null) startTrip(e)
                        phase = if (trip != null) Phase.RECORDING else Phase.LIVE
                    } catch (ex: Exception) {
                        if (ex !is ElmError && ex !is LinkLost) throw ex
                        log("connect failed: ${ex.message}; retry in ${String.format(Locale.ROOT, "%.0f", backoffS)}s")
                        e.close()
                        connected = false
                        phase = if (lastData != 0L) Phase.RECONNECTING else Phase.CONNECTING
                        publish(force = true)
                        sleepWithMarks(backoffS)
                        backoffS = minOf(backoffS * 1.5, 15.0)
                        continue
                    }
                }
                // --- poll one cycle ---
                try {
                    var gotAny = false
                    for (g in fastGroups) {
                        val v = elm.query(g)
                        gotAny = gotAny || v.isNotEmpty()
                        values.putAll(v)
                    }
                    val periodNs = (options.slowPeriodS / maxOf(1, slowGroups.size) * 1e9).toLong()
                    if (slowGroups.isNotEmpty() && System.nanoTime() - lastSlow >= periodNs) {
                        val g = slowGroups[slowI % slowGroups.size]
                        slowI += 1
                        lastSlow = System.nanoTime()
                        when (g) {
                            listOf("RV") -> values["elm_v"] = elm.voltage()
                            listOf("ATF") -> values["atf_c"] = elm.readAtf()
                            else -> values.putAll(elm.query(g.map { it as Int }))
                        }
                    }
                    if (gotAny) {
                        fails = 0
                        tickRate()
                        trip?.let { writeRow(it) }
                        lastData = System.nanoTime()
                        phase = if (trip != null) Phase.RECORDING else Phase.LIVE
                    } else {
                        fails += 1
                        if (fails in setOf(3, 10) || fails % 50 == 0) log("no data from ECU ($fails cycles) - engine/ignition off?")
                        if (fails >= 10) throw ElmError("ECU silent for 10 cycles, reinitialising")
                        Thread.sleep(200)
                    }
                } catch (ex: Exception) {
                    if (ex !is ElmError && ex !is LinkLost) throw ex
                    log("link problem: ${ex.message} -> reconnect")
                    elm.close()
                    elm = null
                    connected = false
                    phase = Phase.RECONNECTING
                    publish(force = true)
                }
                publish()
            }
            endTrip(elm)
        } finally {
            elm?.close()
            if (trip != null) endTrip(null)
            connected = false
            log("session stop")
            phase = Phase.STOPPED
            publish(force = true)
        }
    }

    private fun sleepWithMarks(seconds: Double) {
        val end = System.nanoTime() + (seconds * 1e9).toLong()
        while (System.nanoTime() < end && !stopRequested) {
            drainMarks()
            if (!wantRecording && trip != null) endTrip(null)
            Thread.sleep(100)
        }
    }

    /** All files of the current (or last) trip that exist. */
    fun files(): List<File> {
        val t = trip ?: lastTrip ?: return emptyList()
        return listOf(".csv", ".log", ".meta.json", ".health.json", ".stag.csv").map { t.file(it) }.filter { it.exists() }
    }

    companion object {
        // Pseudo-PIDs in the slow groups: name -> CSV column
        val PSEUDO = mapOf("RV" to "elm_v", "ATF" to "atf_c")

        private val SECONDS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        private val MILLIS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS")
        private val MICROS = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSSSS")
        private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")

        /** Python `csv` writes None as empty and quotes only fields with a comma, quote or newline. */
        fun csvField(s: String): String =
            if (s.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

        fun cell(v: Any?): String = when (v) {
            null -> ""
            is Double -> Py.float(v)
            is Boolean -> Py.bool(v)
            else -> v.toString()
        }
    }
}
