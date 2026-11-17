package com.dudnyk.corolla.obd

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Engine health snapshot, read-only: DTCs, readiness monitors, counters, VIN/CALID and mode 06
 * on-board test results (Toyota CAN ECUs report misfire counts per cylinder there).
 * Port of `logger/obd_health.py`; the map it builds is written as `<trip>.health.json`.
 * Nothing here clears codes: service 04 is not in [ReadOnly] and cannot be sent.
 */
object Health {
    // Readiness monitors, PID 01 byte C (supported) / byte D (incomplete), spark ignition
    private val SPARK_MONITORS = listOf("catalyst", "heated catalyst", "EVAP", "secondary air", "A/C", "O2 sensor",
        "O2 heater", "EGR/VVT")
    // byte B bits 0..2 (supported), 4..6 (incomplete)
    private val CONT_MONITORS = listOf("misfire", "fuel system", "components")

    // Mode 06 OBDMIDs (SAE J1979), the ones a 4-cylinder petrol engine is likely to report
    private val MID_NAMES: Map<Int, String> = mapOf(
        0x01 to "O2 B1S1", 0x02 to "O2 B1S2", 0x03 to "O2 B1S3", 0x21 to "catalyst B1", 0x31 to "EGR", 0x35 to "VVT B1",
        0x39 to "EVAP 0.150\"", 0x3A to "EVAP 0.090\"", 0x3B to "EVAP 0.040\"", 0x3C to "EVAP 0.020\"", 0x3D to "purge flow",
        0x41 to "O2 heater B1S1", 0x42 to "O2 heater B1S2", 0x61 to "heated cat B1", 0x71 to "secondary air",
        0x81 to "fuel system B1", 0xA1 to "misfire general",
    ) + (0 until 8).associate { (0xA2 + it) to "misfire cyl ${it + 1}" }

    // Misfire test ids (Toyota): 0B = EWMA over the last 10 driving cycles, 0C = last/current cycle
    private val MISFIRE_TIDS = mapOf(0x0B to "avg10", 0x0C to "cycle")

    // Unit and scaling ids (SAE J1979 appendix E): uasid -> (scale, unit). Int scale = Python int.
    private val UAS: Map<Int, Pair<Number, String>> = mapOf(
        0x01 to (1 to ""), 0x02 to (0.1 to ""), 0x03 to (0.01 to ""), 0x04 to (0.001 to ""), 0x05 to (0.0000305 to ""),
        0x06 to (0.000305 to ""), 0x07 to (0.25 to "rpm"), 0x08 to (0.01 to "km/h"), 0x09 to (1 to "km/h"),
        0x0A to (0.122 to "mV"), 0x0B to (0.001 to "V"), 0x0C to (0.01 to "V"), 0x0D to (0.00390625 to "mA"),
        0x0E to (0.001 to "A"), 0x0F to (0.01 to "A"), 0x10 to (1 to "ms"), 0x11 to (100 to "ms"), 0x12 to (1 to "s"),
        0x13 to (1 to "mOhm"), 0x14 to (1 to "Ohm"), 0x15 to (1 to "kOhm"), 0x16 to (0.1 to "°C"), 0x17 to (0.01 to "kPa"),
        0x1A to (1 to "kPa"), 0x1C to (0.01 to "°"), 0x1D to (0.5 to "°"), 0x1E to (0.0000305 to "λ"), 0x20 to (0.0039062 to ""),
        0x21 to (0.001 to "Hz"), 0x22 to (1 to "Hz"), 0x24 to (1 to "counts"), 0x25 to (1 to "km"), 0x26 to (0.1 to "mV/ms"),
        0x27 to (0.01 to "g/s"), 0x28 to (1 to "g/s"), 0x2B to (1 to "switches"), 0x2F to (0.01 to "%"), 0x30 to (0.001526 to "%"),
        0x34 to (1 to "min"), 0x35 to (10 to "ms"), 0x3C to (0.1 to "µs"), 0x3D to (0.01 to "mA"),
    )

    fun decodeDtc(a: Int, b: Int): String = "PCBU"[a shr 6] + "${(a shr 4) and 3}" + String.format("%X%02X", a and 0x0F, b)

    private fun u16(d: IntArray, i: Int) = d[i] * 256 + d[i + 1]

    fun scaled(uasid: Int, raw0: Int): Pair<Number, String> {
        var raw = raw0
        if (uasid and 0x80 != 0 && raw >= 0x8000) raw -= 0x10000
        val (scale, unit) = UAS[uasid and 0x7F] ?: (1 to "raw")
        var v: Number = if (scale is Int) raw.toLong() * scale else raw * scale.toDouble()
        if (uasid == 0x16) v = v.toDouble() - 40     // unsigned temperature has a -40 °C offset
        return (if (v is Double) Py.round(v, 6) else v) to unit
    }

    private class Reader(val elm: Elm327, supported: List<Int>) {
        val supported = supported.toSet()

        /** One request; payload after the positive response byte, or null. */
        fun req(service: Int, rest: String = "", timeoutMs: Long = 4000): IntArray? {
            check(service != 0x04) { "never clear codes" }
            return elm.parse(elm.cmd(String.format("%02X", service) + rest, timeoutMs))[service + 0x40]
        }

        fun dtcs(service: Int): List<String>? {
            var d = req(service) ?: return null
            if (elm.isCan && d.isNotEmpty()) d = d.copyOfRange(1, minOf(d.size, 1 + 2 * d[0]))  // CAN: leading byte = count
            return (0 until d.size - 1 step 2).filter { d[it] != 0 || d[it + 1] != 0 }.map { decodeDtc(d[it], d[it + 1]) }
        }

        fun pid(p: Int): IntArray? {
            if (p !in supported) return null
            val d = req(0x01, Pids.hex(p))
            return if (d != null && d.isNotEmpty() && d[0] == p) d.copyOfRange(1, d.size) else null
        }

        fun readiness(): Map<String, Any?>? {
            val d = pid(0x01) ?: return null
            if (d.size < 4) return null
            val (a, b, c, dd) = listOf(d[0], d[1], d[2], d[3])
            val mons = LinkedHashMap<String, String>()
            CONT_MONITORS.forEachIndexed { i, name ->
                if (b and (1 shl i) != 0) mons[name] = if (b and (1 shl (i + 4)) != 0) "incomplete" else "ready"
            }
            if (b and 0x08 == 0) {                       // spark ignition
                SPARK_MONITORS.forEachIndexed { i, name ->
                    if (c and (1 shl i) != 0) mons[name] = if (dd and (1 shl i) != 0) "incomplete" else "ready"
                }
            }
            return linkedMapOf("mil_on" to (a and 0x80 != 0), "dtc_count" to (a and 0x7F), "monitors" to mons)
        }

        fun counters(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            for ((p, name) in listOf(0x21 to "km_with_mil", 0x31 to "km_since_clear", 0x4D to "min_with_mil",
                    0x4E to "min_since_clear")) {
                val d = pid(p)
                if (d != null && d.size >= 2) out[name] = u16(d, 0)
            }
            val d = pid(0x30)
            if (d != null && d.isNotEmpty()) out["warmups_since_clear"] = d[0]
            return out
        }

        fun vehicleInfo(): Map<String, Any?> {
            val out = LinkedHashMap<String, Any?>()
            var d = req(0x09, "02", 5000)
            if (d != null && d.size > 2) {
                out["vin"] = d.drop(2).filter { it in 33..126 }.map { it.toChar() }.joinToString("")
            }
            d = req(0x09, "04", 5000)
            if (d != null && d.size > 2) {
                val raw = d.drop(2)
                out["calid"] = raw.chunked(16)
                    .filter { chunk -> chunk.any { it != 0 } }
                    .map { chunk -> chunk.dropLastWhile { it == 0 }.map { if (it < 128) it.toChar() else '�' }.joinToString("") }
            }
            return out
        }

        fun mode06Mids(): List<Int> {
            val mids = mutableListOf<Int>()
            var base = 0x00
            while (base <= 0xE0) {
                val d = req(0x06, Pids.hex(base))
                if (d == null || d.size < 5 || d[0] != base) break
                val bits = (d[1].toLong() shl 24) or (d[2].toLong() shl 16) or (d[3].toLong() shl 8) or d[4].toLong()
                for (i in 0 until 32) {
                    if (bits and (1L shl (31 - i)) != 0L && (i + 1) % 0x20 != 0) mids.add(base + i + 1)
                }
                if (bits and 1L == 0L) break
                base += 0x20
            }
            return mids
        }

        fun mode06(): List<Map<String, Any?>> {
            val tests = mutableListOf<Map<String, Any?>>()
            for (mid in mode06Mids()) {
                val d = req(0x06, Pids.hex(mid)) ?: continue
                var i = 0
                while (i < d.size - 8) {                 // records: MID TID UASID value min max (u16 each)
                    val m = d[i]
                    val tid = d[i + 1]
                    val uas = d[i + 2]
                    val (v, unit) = scaled(uas, u16(d, i + 3))
                    val (vlo, _) = scaled(uas, u16(d, i + 5))
                    val (vhi, _) = scaled(uas, u16(d, i + 7))
                    tests.add(linkedMapOf(
                        "mid" to Pids.hex(m), "name" to (MID_NAMES[m] ?: "MID ${Pids.hex(m)}"), "tid" to Pids.hex(tid),
                        "uasid" to Pids.hex(uas), "value" to v, "min" to vlo, "max" to vhi, "unit" to unit,
                        "pass" to (vlo.toDouble() <= v.toDouble() && v.toDouble() <= vhi.toDouble()),
                    ))
                    i += 9
                }
            }
            return tests
        }
    }

    /** Read everything; each part is optional (clone ELMs / ECUs answer NO DATA to some). */
    fun read(elm: Elm327, supported: List<Int>, mode06: Boolean = true, log: (String) -> Unit = {}): Map<String, Any?> {
        val r = Reader(elm, supported)
        val h = LinkedHashMap<String, Any?>()
        h["time"] = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
        val parts: List<Pair<String, () -> Any?>> = listOf(
            "readiness" to r::readiness, "dtc_stored" to { r.dtcs(0x03) },
            "dtc_pending" to { r.dtcs(0x07) }, "dtc_permanent" to { r.dtcs(0x0A) },
            "counters" to r::counters, "vehicle" to r::vehicleInfo,
            "mode06" to (if (mode06) r::mode06 else ({ null })),
        )
        for ((key, fn) in parts) {
            h[key] = try {
                fn()
            } catch (e: ElmError) {
                log("  health: $key failed: ${e.message}")
                null
            }
        }
        return h
    }

    /** {"cyl 1": {"avg10": x, "cycle": y}, ...} from mode 06 MIDs A2.. */
    @Suppress("UNCHECKED_CAST")
    fun misfires(h: Map<String, Any?>): Map<String, Map<String, Number>> {
        val out = sortedMapOf<String, MutableMap<String, Number>>()
        for (t in (h["mode06"] as? List<Map<String, Any?>>).orEmpty()) {
            val m = (t["mid"] as String).toInt(16)
            val tid = (t["tid"] as String).toInt(16)
            if (m in 0xA2..0xA9 && tid in MISFIRE_TIDS) {
                out.getOrPut("cyl ${m - 0xA1}") { LinkedHashMap() }[MISFIRE_TIDS.getValue(tid)] = t["value"] as Number
            }
        }
        return out
    }

    /** Human-readable lines, same text as `format_health` in Python (they go into the `.log`). */
    @Suppress("UNCHECKED_CAST")
    fun format(h: Map<String, Any?>): List<String> {
        val out = mutableListOf<String>()
        val rd = h["readiness"] as? Map<String, Any?>
        if (rd != null) {
            out.add("MIL (Check Engine): ${if (rd["mil_on"] == true) "ON" else "off"}, stored codes: ${rd["dtc_count"]}")
            val mons = rd["monitors"] as Map<String, String>
            out.add("readiness: " + mons.entries.joinToString(", ") { "${it.key} ${if (it.value == "ready") "READY" else "not ready"}" })
            val inc = mons.filterValues { it == "incomplete" }.keys
            if (inc.isNotEmpty()) {
                out.add("  not ready: ${inc.joinToString(", ")} (normal for a while after codes were cleared / battery off)")
            }
        }
        for ((key, title) in listOf("dtc_stored" to "stored DTC", "dtc_pending" to "pending DTC", "dtc_permanent" to "permanent DTC")) {
            val v = h[key] as? List<String>
            out.add("$title: " + when {
                v == null -> "n/a"
                v.isEmpty() -> "none"
                else -> v.joinToString(", ")
            })
        }
        val c = h["counters"] as? Map<String, Any?>
        if (!c.isNullOrEmpty()) out.add("counters: " + c.entries.joinToString(", ") { "${it.key}=${Py.str(it.value)}" })
        val veh = h["vehicle"] as? Map<String, Any?>
        if (!veh.isNullOrEmpty()) out.add("vehicle: " + veh.entries.joinToString(", ") { "${it.key}=${Py.str(it.value)}" })
        val mis = misfires(h)
        if (mis.isNotEmpty()) {
            out.add("misfires (mode 06; avg10 = average of last 10 driving cycles, cycle = current/last):")
            for ((cyl, v) in mis) {
                out.add("  $cyl: " + listOf("avg10", "cycle").filter { it in v }.joinToString(", ") { "$it ${Py.g(v.getValue(it))}" })
            }
        }
        val all = h["mode06"] as? List<Map<String, Any?>>
        val tests = all.orEmpty().filter { t -> val n = t["name"] as String; !("misfire" in n && n != "misfire general") }
        if (tests.isNotEmpty()) {
            out.add("mode 06 tests (${tests.count { it["pass"] != true }} out of limits):")
            for (t in tests) {
                val flag = if (t["pass"] == true) "OK  " else "FAIL"
                out.add("  $flag ${(t["name"] as String).padEnd(16)} TID ${t["tid"]}: ${Py.g(t["value"] as Number)} ${t["unit"]} " +
                    "[${Py.g(t["min"] as Number)} .. ${Py.g(t["max"] as Number)}]")
            }
        } else if (all != null) {
            out.add("mode 06: no results (monitors have not run since codes were cleared)")
        }
        return out
    }
}
