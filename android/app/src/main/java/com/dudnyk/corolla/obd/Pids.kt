package com.dudnyk.corolla.obd

import com.dudnyk.corolla.obd.Py.round
import com.dudnyk.corolla.obd.Py.roundInt

/** Mode 01 PID: CSV column name, number of data bytes, decoder (raw bytes -> columns). Port of `elm327.py`. */
class Pid(val pid: Int, val name: String, val nbytes: Int, val decode: (IntArray) -> Map<String, Any?>)

object Pids {
    private fun trim(a: Int): Double = round(a * 100.0 / 128.0 - 100.0, 2)

    private fun u16(d: IntArray, i: Int = 0) = d[i] * 256 + d[i + 1]

    private fun o2Narrow(prefix: String): (IntArray) -> Map<String, Any?> = { d ->
        // second byte = per-sensor trim; 0xFF means "not used for trim"
        mapOf("${prefix}_v" to round(d[0] / 200.0, 3), "${prefix}_trim" to if (d[1] == 0xFF) null else trim(d[1]))
    }

    val FUEL_STATUS = mapOf(1 to "OL_cold", 2 to "CL", 4 to "OL_load", 8 to "OL_fault", 16 to "CL_fault")

    val ALL: Map<Int, Pid> = listOf(
        Pid(0x03, "fuel_status", 2) { d -> mapOf("fuel_status" to (FUEL_STATUS[d[0]] ?: String.format("0x%02X", d[0]))) },
        Pid(0x04, "load", 1) { d -> mapOf("load" to round(d[0] * 100 / 255.0, 1)) },
        Pid(0x05, "ect", 1) { d -> mapOf("ect" to d[0] - 40) },
        Pid(0x06, "stft", 1) { d -> mapOf("stft" to trim(d[0])) },
        Pid(0x07, "ltft", 1) { d -> mapOf("ltft" to trim(d[0])) },
        Pid(0x0B, "map", 1) { d -> mapOf("map" to d[0]) },
        Pid(0x0C, "rpm", 2) { d -> mapOf("rpm" to roundInt(u16(d) / 4.0)) },
        Pid(0x0D, "speed", 1) { d -> mapOf("speed" to d[0]) },
        Pid(0x0E, "timing", 1) { d -> mapOf("timing" to d[0] / 2.0 - 64) },
        Pid(0x0F, "iat", 1) { d -> mapOf("iat" to d[0] - 40) },
        Pid(0x10, "maf", 2) { d -> mapOf("maf" to round(u16(d) / 100.0, 2)) },
        Pid(0x11, "tps", 1) { d -> mapOf("tps" to round(d[0] * 100 / 255.0, 1)) },
        Pid(0x14, "o2s1", 2, o2Narrow("o2s1")),
        Pid(0x15, "o2s2", 2, o2Narrow("o2s2")),
        Pid(0x1F, "runtime", 2) { d -> mapOf("runtime" to u16(d)) },
        Pid(0x24, "wr1", 4) { d ->
            mapOf("wr1_lambda" to round(u16(d) * 2 / 65536.0, 4), "wr1_v" to round(u16(d, 2) * 8 / 65536.0, 3))
        },
        Pid(0x33, "baro", 1) { d -> mapOf("baro" to d[0]) },
        Pid(0x34, "wr1c", 4) { d ->
            mapOf("wr1_lambda" to round(u16(d) * 2 / 65536.0, 4), "wr1_ma" to round(u16(d, 2) / 256.0 - 128, 3))
        },
        Pid(0x3C, "cat_temp", 2) { d -> mapOf("cat_temp" to round(u16(d) / 10.0 - 40, 1)) },
        Pid(0x42, "ecu_v", 2) { d -> mapOf("ecu_v" to round(u16(d) / 1000.0, 2)) },
        Pid(0x43, "abs_load", 2) { d -> mapOf("abs_load" to round(u16(d) * 100 / 255.0, 1)) },
        Pid(0x44, "cmd_lambda", 2) { d -> mapOf("cmd_lambda" to round(u16(d) * 2 / 65536.0, 4)) },
        Pid(0x46, "ambient", 1) { d -> mapOf("ambient" to d[0] - 40) },
    ).associateBy { it.pid }

    /** Columns each PID produces (for the CSV header). */
    private val COLUMNS = mapOf(
        0x14 to listOf("o2s1_v", "o2s1_trim"),
        0x15 to listOf("o2s2_v", "o2s2_trim"),
        0x24 to listOf("wr1_lambda", "wr1_v"),
        0x34 to listOf("wr1_lambda", "wr1_ma"),
    )

    fun columnsFor(pid: Int): List<String> = COLUMNS[pid] ?: listOf(ALL.getValue(pid).name)

    // Candidate PIDs, same as elm_logger.py. Only those the ECU reports as supported are polled.
    val FAST = listOf(0x06, 0x07, 0x0C, 0x04, 0x10, 0x0B, 0x11, 0x0D, 0x14, 0x15)
    val FAST_WIDEBAND = listOf(0x34, 0x24)          // used only if 0x14 (narrow-band O2) is absent
    val SLOW = listOf(0x05, 0x0F, 0x0E, 0x03, 0x3C, 0x44, 0x43, 0x42, 0x33, 0x46, 0x1F)

    /**
     * First-fit-decreasing packing so every response fits one CAN frame: 7 data bytes = 1 byte for
     * the 0x41 mode echo, then (1 PID byte + N data bytes) per PID -> capacity 6 for the payload.
     * Python's `sorted` is stable, so equal sizes keep their order - `sortedBy` is stable too.
     */
    fun packGroups(pids: List<Int>, capacity: Int = 6): List<List<Int>> {
        val used = mutableListOf<Int>()
        val groups = mutableListOf<MutableList<Int>>()
        for (pid in pids.sortedBy { -(1 + ALL.getValue(it).nbytes) }) {
            val cost = 1 + ALL.getValue(pid).nbytes
            val i = used.indices.firstOrNull { used[it] + cost <= capacity }
            if (i != null) {
                used[i] += cost
                groups[i].add(pid)
            } else {
                used.add(cost)
                groups.add(mutableListOf(pid))
            }
        }
        return groups
    }

    fun hex(p: Int) = String.format("%02X", p)
}
