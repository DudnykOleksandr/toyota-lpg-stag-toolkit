package com.dudnyk.corolla.stag

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * STAG-4 QBOX BASIC (fw 2.14.2) serial protocol as AC STAG 0.64.4 speaks it over the Bluetooth interface.
 * Decoded from our own capture `logs/stag_sniff_20261117_121357.log`, see `docs/05_stag_protocol.md`.
 *
 * Frame: `[start] 00 [len hi] [len lo] [cmd] [args…] [cks]`, len = bytes after it including cks,
 * cks = sum of all previous bytes & 0xFF. The PC greets with start byte 0xAC and names the session byte
 * (`AC 00 00 04 00 00 S C`); every later frame in both directions starts with S. A reply's cmd is the
 * request's cmd | 0x80. Multi-byte values are big-endian.
 */
object StagFrame {
    const val HELLO_START = 0xAC
    const val SESSION = 0x14            // AC STAG uses 0x14; the forum example used 0x91

    /** `body` = cmd + args (no start/len/cks). */
    fun build(start: Int, body: ByteArray): ByteArray {
        val out = ByteArray(body.size + 5)
        val len = body.size + 1
        out[0] = start.toByte()
        out[1] = 0
        out[2] = (len shr 8).toByte()
        out[3] = len.toByte()
        body.copyInto(out, 4)
        out[out.size - 1] = checksum(out, out.size - 1).toByte()
        return out
    }

    fun checksum(b: ByteArray, n: Int): Int {
        var s = 0
        for (i in 0 until n) s += b[i].toInt() and 0xFF
        return s and 0xFF
    }

    fun hello(session: Int = SESSION) = build(HELLO_START, byteArrayOf(0, 0, session.toByte()))

    fun hex(b: ByteArray) = b.joinToString(" ") { String.format(Locale.ROOT, "%02X", it.toInt() and 0xFF) }

    fun bytes(hex: String): ByteArray = hex.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        .map { it.toInt(16).toByte() }.toByteArray()
}

/**
 * Assembles frames from a byte stream (Bluetooth delivers them in arbitrary pieces). Bytes that do not
 * start a frame with a valid checksum are skipped one at a time, so it resynchronises after garbage
 * (the controller once sent `0D 2B 2B 2B 0D 5E 5E 5E` outside any frame).
 */
class FrameParser(private val start: Int) {
    private val buf = java.io.ByteArrayOutputStream()
    var skipped = 0L
        private set

    /** Feeds bytes; returns complete frame bodies (cmd + args). */
    fun feed(data: ByteArray): List<ByteArray> {
        buf.write(data)
        val out = mutableListOf<ByteArray>()
        var b = buf.toByteArray()
        var i = 0
        while (true) {
            while (i < b.size && (b[i].toInt() and 0xFF) != start) { i++; skipped++ }
            if (b.size - i < 5) break
            // bytes 1-2 are the high bytes of the length: the 23 01 FF reply is 507 bytes (00 01 FB)
            val len = ((b[i + 2].toInt() and 0xFF) shl 8) or (b[i + 3].toInt() and 0xFF)
            if (b[i + 1].toInt() != 0 || len < 2 || len > MAX_LEN) { i++; skipped++; continue }
            val total = 4 + len
            if (b.size - i < total) break
            val frame = b.copyOfRange(i, i + total)
            if (StagFrame.checksum(frame, total - 1) != (frame[total - 1].toInt() and 0xFF)) { i++; skipped++; continue }
            out.add(frame.copyOfRange(4, total - 1))
            i += total
        }
        buf.reset()
        buf.write(b, i, b.size - i)
        return out
    }

    fun clear() = buf.reset()

    companion object {
        /** Longest frame seen is 507 bytes; a false start byte with a huge "length" must not stall the parser. */
        const val MAX_LEN = 2048
    }
}

/** One answer of the monitor request (`14 10 78 …` -> `94`, 86 bytes). Indices count from the `94` byte. */
data class StagMonitor(
    val mapBar: Double,
    val gasPBar: Double,
    val gasTempC: Int,
    val reducerTempC: Int,
    val rpm: Int,
    val supplyV: Double,
    /** 4 petrol, 5 / 8 switching, 10 gas */
    val status: Int,
    val petrolMs: List<Double>,
    val gasMs: List<Double>,
    val engineTempC: Int,
) {
    val onGas get() = status == 10
    val petrolAvg get() = petrolMs.average()
    val gasAvg get() = gasMs.average()
    /** Actual gas / petrol injection time; null when not on gas or no petrol pulse. */
    val ratio get() = if (onGas && petrolAvg > 0.3) gasAvg / petrolAvg else null

    companion object {
        val MONITOR_REQUEST = StagFrame.bytes("14 10 78 CB 0F 0F 0F 00 20 04 C1 01 40 01 80 01 00 00")
        val ERRORS_REQUEST = StagFrame.bytes("40 00")

        fun parse(b: ByteArray): StagMonitor? {
            if (b.size < 77 || (b[0].toInt() and 0xFF) != 0x94) return null
            fun u8(i: Int) = b[i].toInt() and 0xFF
            fun u16(i: Int) = (u8(i) shl 8) or u8(i + 1)
            return StagMonitor(
                mapBar = u8(3) * 0.01,
                gasPBar = u16(4) * 0.01,
                gasTempC = u16(6),
                reducerTempC = u16(8),
                rpm = u16(10),
                supplyV = u16(12) / 1000.0,
                status = u8(14),
                petrolMs = listOf(20, 24, 28, 32).map { u16(it) * 0.005 },
                gasMs = listOf(52, 56, 60, 64).map { u16(it) * 0.005 },
                engineTempC = u16(75),
            )
        }
    }
}

/**
 * Petrol-injection-time multiplier «Множитель (Б-1)», page 00. Read in two parts:
 * part 0 (`21 00 00 00 00 00`) = a 70-byte block: `02 00 00 0A 00 00 00 00 1E`, the point count N, then the
 * axis (Б, N × u16 × 0.005 ms), zeros; part 1 (`21 00 00 01 00 N`) = N multiplier values (u16 × 0.01).
 * AC STAG writes the whole map on every change: `22 00 00 00 00 00` + block, `22 00 00 01 00 N` + values,
 * then `32 00`. Adding a point = the same frames with N + 1 and the new axis (owner's 3.33 ms point, 17.11).
 */
data class MultiplierMap(val block: ByteArray, val axisMs: List<Double>, val values: List<Double>) {
    val n get() = axisMs.size

    fun valuesRaw() = values.map { (it * 100).roundToInt() }
    fun axisRaw() = axisMs.map { msToRaw(it) }

    fun writeAxisBody(): ByteArray = StagFrame.bytes("22 00 00 00 00 00") + block

    fun writeValuesBody(): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(StagFrame.bytes("22 00 00 01 00"))
        out.write(n)
        for (r in valuesRaw()) {
            out.write(r shr 8)
            out.write(r and 0xFF)
        }
        return out.toByteArray()
    }

    /** The same map with another axis and values: the block keeps its header, N and axis are replaced. */
    fun with(axis: List<Double>, newValues: List<Double>): MultiplierMap {
        require(axis.size == newValues.size && axis.size in 2..MAX_POINTS)
        val b = block.copyOf()
        b[9] = axis.size.toByte()
        for (i in 10 until b.size) b[i] = 0
        axis.forEachIndexed { i, ms -> val r = msToRaw(ms); b[10 + 2 * i] = (r shr 8).toByte(); b[11 + 2 * i] = r.toByte() }
        return MultiplierMap(b, axis.map { msToRaw(it) * 0.005 }, newValues.map { Math.round(it * 100) / 100.0 })
    }

    fun withValues(newValues: List<Double>) = with(axisMs, newValues)

    /** Multiplier on the current line at [ms] (linear between points), rounded to 0.01. */
    fun valueAt(ms: Double): Double {
        if (ms <= axisMs.first()) return values.first()
        for (i in 1 until n) if (ms <= axisMs[i]) {
            val k = (ms - axisMs[i - 1]) / (axisMs[i] - axisMs[i - 1])
            return Math.round((values[i - 1] + k * (values[i] - values[i - 1])) * 100) / 100.0
        }
        return values.last()
    }

    fun sameAs(other: MultiplierMap) = block.contentEquals(other.block) && valuesRaw() == other.valuesRaw()

    fun toJson(extra: Map<String, Any?> = emptyMap()): Map<String, Any?> = linkedMapOf<String, Any?>(
        "axis_ms" to axisMs, "multiplier" to values, "block_hex" to StagFrame.hex(block)) + extra

    override fun equals(other: Any?) = other is MultiplierMap && sameAs(other)
    override fun hashCode() = valuesRaw().hashCode()

    companion object {
        val READ_AXIS = StagFrame.bytes("21 00 00 00 00 00")
        fun readValuesBody(n: Int) = StagFrame.bytes("21 00 00 01 00") + byteArrayOf(n.toByte())

        /** Hard limits for any value we write, and the largest step per write vs the line read from the controller. */
        const val MIN_VALUE = 0.50
        const val MAX_VALUE = 3.00
        const val MAX_STEP = 0.10
        /** The controller took 9 points (17.11); the block has room for 30. We stay well below. */
        const val MAX_POINTS = 12
        /** Closest allowed spacing of two points on the axis, ms. */
        const val MIN_GAP_MS = 0.3

        fun msToRaw(ms: Double) = (ms / 0.005).roundToInt()

        /** `axisReply` = `A1 …` for part 0, `valuesReply` = `A1 …` for part 1 (bodies without start/len/cks). */
        fun parse(axisReply: ByteArray, valuesReply: ByteArray): MultiplierMap {
            fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
            fun u16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
            if (axisReply.size < 8 + 10 || u8(axisReply, 0) != 0xA1 || u8(axisReply, 1) != 0 || u8(axisReply, 2) != 0 || u8(axisReply, 3) != 0)
                throw StagError("map part 0: unexpected reply ${StagFrame.hex(axisReply.take(12).toByteArray())}")
            val block = axisReply.copyOfRange(8, axisReply.size)
            val n = u8(block, 9)
            if (n !in 2..30 || block.size < 10 + 2 * n) throw StagError("map part 0: bad point count $n")
            val axis = (0 until n).map { u16(block, 10 + 2 * it) * 0.005 }
            if (axis.zipWithNext().any { (a, b) -> b <= a }) throw StagError("map axis not increasing: $axis")
            if (valuesReply.size < 8 + 2 * n || u8(valuesReply, 0) != 0xA1 || u8(valuesReply, 3) != 1 || u8(valuesReply, 5) != n)
                throw StagError("map part 1: unexpected reply ${StagFrame.hex(valuesReply.take(12).toByteArray())}")
            val values = (0 until n).map { u16(valuesReply, 8 + 2 * it) / 100.0 }
            if (values.any { it < 0.3 || it > 4.0 }) throw StagError("map values out of range: $values")
            return MultiplierMap(block, axis, values)
        }

        private fun ms(x: Double) = String.format(Locale.ROOT, "%.3f мс", x)

        /**
         * Null if [axis] / [newValues] may be written over [current]; otherwise the reason (Ukrainian, for the UI).
         * Each value may move at most ±10 % from the current line at its point (for a new point - from the line
         * between its neighbours, so adding a point by itself changes nothing). The first and last points stay.
         */
        fun validate(current: MultiplierMap, axis: List<Double>, newValues: List<Double>): String? {
            if (axis.size != newValues.size) return "кількість точок і значень різна"
            if (axis.size > MAX_POINTS) return "не більше $MAX_POINTS точок"
            if (axis.size < 4) return "не менше 4 точок"
            val raw = axis.map { msToRaw(it) }
            if (raw.first() != current.axisRaw().first() || raw.last() != current.axisRaw().last())
                return "першу (${ms(current.axisMs.first())}) і останню (${ms(current.axisMs.last())}) точки не можна прибрати чи посунути"
            for (i in 1 until axis.size) {
                if (raw[i] <= raw[i - 1]) return "точки мають іти за зростанням (${ms(axis[i - 1])}, ${ms(axis[i])})"
                if (axis[i] - axis[i - 1] < MIN_GAP_MS - 1e-9) return "точки ${ms(axis[i - 1])} і ${ms(axis[i])} ближче ${MIN_GAP_MS} мс"
            }
            for (i in axis.indices) {
                val v = newValues[i]
                if (v.isNaN() || v < MIN_VALUE || v > MAX_VALUE) return "точка ${ms(axis[i])}: $v поза межами $MIN_VALUE…$MAX_VALUE"
                val ref = current.valueAt(axis[i])
                if (abs(v / ref - 1) > MAX_STEP + 1e-9)
                    return "точка ${ms(axis[i])}: зміна ${String.format(Locale.ROOT, "%+.1f", (v / ref - 1) * 100)} % від лінії — за раз не більше ±${(MAX_STEP * 100).toInt()} %"
            }
            val next = current.with(axis, newValues)
            return if (next.axisRaw() == current.axisRaw() && next.valuesRaw() == current.valuesRaw()) "нічого не змінено" else null
        }

        fun validate(current: MultiplierMap, newValues: List<Double>): String? =
            if (newValues.size != current.n) "кількість точок не збігається (${newValues.size} ≠ ${current.n})"
            else validate(current, current.axisMs, newValues)
    }
}

class StagError(msg: String) : Exception(msg)
