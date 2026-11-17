package com.dudnyk.corolla.stag

import com.dudnyk.corolla.obd.ByteLink
import com.dudnyk.corolla.obd.LinkLost

/**
 * Request/response over the Stag link. One request at a time; replies are matched by cmd | 0x80,
 * stray frames (or bytes) in between are skipped.
 */
class StagClient(private val link: ByteLink, private val log: (String) -> Unit = {}, private val timeoutMs: Long = 1200) {
    private val parser = FrameParser(StagFrame.SESSION)
    val label get() = link.label

    /** Opens the link, greets the controller and repeats AC STAG's read-only start-up requests. */
    fun connect(): ByteArray {
        link.open()
        val hello = exchange(StagFrame.hello(), 0x00, timeoutMs * 2)
            ?: throw LinkLost("Stag did not answer the greeting")
        log("Stag hello: ${StagFrame.hex(hello)}")
        var missing = 0
        for (req in STARTUP) {
            if (request(StagFrame.bytes(req)) == null) missing++
        }
        if (missing > 0) log("Stag start-up: $missing of ${STARTUP.size} requests unanswered")
        return hello
    }

    /** Body of the reply to [body] (cmd | 0x80 first), or null on timeout. */
    fun request(body: ByteArray, timeout: Long = timeoutMs): ByteArray? =
        exchange(StagFrame.build(StagFrame.SESSION, body), body[0].toInt() and 0xFF, timeout)

    private fun exchange(frame: ByteArray, cmd: Int, timeout: Long): ByteArray? {
        link.write(frame)
        val want = cmd or 0x80
        val deadline = System.nanoTime() + timeout * 1_000_000
        while (true) {
            val left = (deadline - System.nanoTime()) / 1_000_000
            if (left <= 0) {
                parser.clear()
                return null
            }
            val got = link.readSome(left)
            for (f in parser.feed(got)) {
                if ((f[0].toInt() and 0xFF) == want) return f
            }
        }
    }

    fun monitor(): StagMonitor? = request(StagMonitor.MONITOR_REQUEST)?.let { StagMonitor.parse(it) }

    /** Raw error block (`40 00` -> `C0 …`); all zeros = no errors (meaning of the bytes not decoded yet). */
    fun errors(): ByteArray? = request(StagMonitor.ERRORS_REQUEST)

    fun readMap(): MultiplierMap {
        val axis = request(MultiplierMap.READ_AXIS) ?: throw StagError("map read: no answer (part 0)")
        val n = if (axis.size > 17) axis[17].toInt() and 0xFF else 0
        val values = request(MultiplierMap.readValuesBody(n)) ?: throw StagError("map read: no answer (part 1)")
        return MultiplierMap.parse(axis, values)
    }

    /**
     * Writes the map exactly as AC STAG does (axis block, values, `32 00`) and reads it back. [axis] = null keeps
     * the points; otherwise it may add or remove points (see [MultiplierMap.validate]). [before] gets the map
     * read right before writing (for a backup). Throws on any refusal or mismatch.
     */
    fun writeMap(newValues: List<Double>, axis: List<Double>? = null, before: (MultiplierMap) -> Unit = {}): MultiplierMap {
        val cur = readMap()
        val ax = axis ?: cur.axisMs
        MultiplierMap.validate(cur, ax, newValues)?.let { throw StagError(it) }
        val next = cur.with(ax, newValues)
        before(cur)
        expectAck(next.writeAxisBody(), "map write part 0")
        expectAck(next.writeValuesBody(), "map write part 1")
        expectAck(StagFrame.bytes("32 00"), "apply (32 00)")
        val after = readMap()
        if (after.valuesRaw() != next.valuesRaw() || after.axisRaw() != next.axisRaw())
            throw StagError("read back differs: wrote ${next.axisMs} / ${next.values}, controller has ${after.axisMs} / ${after.values}")
        return after
    }

    private fun expectAck(body: ByteArray, what: String) {
        val r = request(body) ?: throw StagError("$what: no answer")
        if (r.size < 2 || r[1].toInt() != body[1].toInt()) throw StagError("$what: unexpected answer ${StagFrame.hex(r)}")
    }

    fun close() = link.close()

    companion object {
        /**
         * AC STAG's start-up requests (capture of 2026-11-17), read-only ones only: left out are `6D 01`
         * (5 s pause, then the controller sent "+++"/"^^^" outside frames) and `4A 03 …` (writes a history
         * record: unix time + PC id). `34 05` and `1B 28 …` are kept: AC STAG sends them on every connect
         * right before polling; their meaning is not decoded.
         */
        val STARTUP = listOf(
            "52 00", "03 00", "6D 00", "6D 04", "4D 00", "16 00 01",
            "49 00", "49 01", "49 02", "49 03", "49 04", "49 05", "49 06", "49 07", "49 08", "49 09", "49 0A",
            "31 00 FF", "23 01 FF", "49 0E", "23 92 FF", "48 00", "1E 00", "16 01 FF", "10 00", "66 FF", "4B 00",
            "67 00", "21 00 00 00 00 00", "1F 01 FF", "03 00", "34 05", "1B 28 00 00 00 00", "51 00",
        )
    }
}
