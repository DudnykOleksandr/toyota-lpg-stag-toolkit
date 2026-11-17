package com.dudnyk.corolla.stag

import com.dudnyk.corolla.obd.ByteLink
import com.dudnyk.corolla.obd.LinkLost
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Built-in emulated Stag controller for trying the app without the car (Settings -> Stag -> «Демо»).
 * Speaks the same frames as the real QBOX: greeting, start-up requests, monitor (idle and gentle drives
 * on gas, switching to petrol now and then), error block, multiplier map read / write / apply.
 * The map lives in memory for as long as the app runs; it starts as the car's map after the write of 2026-11-17 16:37.
 */
class DemoStag : ByteLink {
    override val label = "демо Stag (без машини)"
    private val out = LinkedBlockingQueue<ByteArray>()
    private val hello = FrameParser(StagFrame.HELLO_START)
    private val parser = FrameParser(StagFrame.SESSION)
    private var open = false
    private val t0 = System.nanoTime()

    override fun open() {
        Thread.sleep(400)            // a real Bluetooth connect takes a moment
        open = true
    }

    override fun close() { open = false }

    override fun write(data: ByteArray) {
        if (!open) throw LinkLost("demo closed")
        for (f in hello.feed(data)) if (f[0].toInt() == 0) reply(StagFrame.bytes("80 00 01 00 1E 01 03 02 0E 00 37 1D 07 07 E8"))
        for (f in parser.feed(data)) {
            val cmd = f[0].toInt() and 0xFF
            when {
                f.contentEquals(StagMonitor.MONITOR_REQUEST) -> reply(monitor())
                cmd == 0x21 && f.size >= 4 && f[3].toInt() == 0 -> reply(StagFrame.bytes("A1 00 00 00 00 08 00 02") + axisBlock())
                cmd == 0x21 && f.size >= 4 && f[3].toInt() == 1 ->
                    reply(StagFrame.bytes("A1 00 00 01 00") + byteArrayOf(synchronized(Companion) { axis.size }.toByte(), 0, 2) + valuesRaw())
                cmd == 0x22 && f.size >= 4 && f[3].toInt() == 0 -> {
                    val b = f.copyOfRange(6, f.size)
                    val n = b[9].toInt() and 0xFF
                    pendingAxis = (0 until n).map { ((b[10 + 2 * it].toInt() and 0xFF) shl 8) or (b[11 + 2 * it].toInt() and 0xFF) }
                    reply(StagFrame.bytes("A2 00"))
                }
                cmd == 0x22 && f.size >= 4 && f[3].toInt() == 1 -> { pending = f.copyOfRange(6, f.size); reply(StagFrame.bytes("A2 00")) }
                cmd == 0x32 -> {
                    pending?.let { p -> synchronized(Companion) {
                        val v = (0 until p.size / 2).map { (((p[2 * it].toInt() and 0xFF) shl 8) or (p[2 * it + 1].toInt() and 0xFF)) / 100.0 }
                        val a = pendingAxis ?: axis
                        if (a.size == v.size) { axis = a; values = v }
                    } }
                    pending = null
                    pendingAxis = null
                    reply(StagFrame.bytes("B2 00"))
                }
                cmd == 0x40 -> reply(StagFrame.bytes("C0 00 00 00 00 00 00"))
                else -> reply(byteArrayOf((cmd or 0x80).toByte()) + f.copyOfRange(1, minOf(f.size, 3)))
            }
        }
    }

    private var pending: ByteArray? = null
    private var pendingAxis: List<Int>? = null

    override fun readSome(timeoutMs: Long): ByteArray {
        if (!open) throw LinkLost("demo closed")
        return out.poll(timeoutMs, TimeUnit.MILLISECONDS) ?: ByteArray(0)
    }

    private fun reply(body: ByteArray) {
        Thread.sleep(8)
        out.add(StagFrame.build(StagFrame.SESSION, body))
    }

    private fun axisBlock(): ByteArray {
        val b = ByteArray(70)
        val ax = synchronized(Companion) { axis }
        StagFrame.bytes("02 00 00 0A 00 00 00 00 1E").copyInto(b)
        b[9] = ax.size.toByte()
        ax.forEachIndexed { i, v -> b[10 + 2 * i] = (v shr 8).toByte(); b[11 + 2 * i] = v.toByte() }
        return b
    }

    private fun valuesRaw(): ByteArray {
        val v = synchronized(Companion) { values }
        val b = ByteArray(2 * v.size)
        v.forEachIndexed { i, x -> val r = (x * 100).roundToInt(); b[2 * i] = (r shr 8).toByte(); b[2 * i + 1] = r.toByte() }
        return b
    }

    /** A 90 s loop: idle, pull-away, cruise, back to idle; petrol for 15 s every 3 min. */
    private fun monitor(): ByteArray {
        val t = (System.nanoTime() - t0) / 1e9
        val ph = t % 90
        val drive = when {
            ph < 25 -> 0.0
            ph < 35 -> (ph - 25) / 10
            ph < 70 -> 0.45 + 0.1 * sin(ph)
            ph < 78 -> (78 - ph) / 8 * 0.45
            else -> 0.0
        }
        val petrol = (t % 180) > 165
        val rpm = (720 + drive * 1500 + 15 * sin(t * 3)).roundToInt()
        val map = 0.35 + drive * 0.5
        val b = 2.45 + drive * 6.5 + 0.03 * sin(t * 5)
        val v = synchronized(Companion) { values }
        val mult = interpolate(b, v, synchronized(Companion) { axis })
        val g = if (petrol) 0.0 else b * mult * 0.98
        val p = 1.37 - drive * 0.12 + 0.01 * sin(t * 2)
        val r = ByteArray(85)
        fun u16(i: Int, x: Int) { r[i] = (x shr 8).toByte(); r[i + 1] = x.toByte() }
        r[0] = 0x94.toByte()
        r[3] = (map * 100).roundToInt().toByte()
        u16(4, (p * 100).roundToInt())
        u16(6, 43)
        u16(8, 82)
        u16(10, rpm)
        u16(12, 13900)
        r[14] = if (petrol) 4 else 10
        for (i in 0 until 4) {
            u16(20 + 4 * i, max(0, (b * 200).roundToInt() + i))
            u16(52 + 4 * i, max(0, (g * 200).roundToInt() + i))
        }
        u16(75, 90)
        return r
    }

    private fun interpolate(b: Double, v: List<Double>, raw: List<Int>): Double {
        val axis = raw.map { it * 0.005 }
        if (b <= axis.first()) return v.first()
        for (i in 1 until axis.size) if (b <= axis[i]) {
            val k = (b - axis[i - 1]) / (axis[i] - axis[i - 1])
            return v[i - 1] + k * (v[i] - v[i - 1])
        }
        return v.last()
    }

    companion object {
        const val ADDRESS = "demo"
        /** Axis (raw, × 0.005 ms) and values, shared across reconnects while the app runs. */
        private var axis = listOf(0x0000, 0x01EF, 0x0323, 0x04B0, 0x0640, 0x0960, 0x0C80, 0x13EC)
        private var values = listOf(1.72, 1.68, 1.60, 1.55, 1.42, 1.19, 1.17, 1.23)
    }
}
