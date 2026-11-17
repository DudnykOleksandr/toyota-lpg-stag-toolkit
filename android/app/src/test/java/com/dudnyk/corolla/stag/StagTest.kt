package com.dudnyk.corolla.stag

import com.dudnyk.corolla.obd.ByteLink
import com.dudnyk.corolla.obd.LinkLost
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** Bytes from our capture logs/stag_sniff_20261117_121357.log (AC STAG 0.64.4 <-> QBOX BASIC 2.14.2). */
object Sniff {
    val HELLO_WIRE = "AC 00 00 04 00 00 14 C4"
    val MONITOR_WIRE = "14 00 00 13 14 10 78 CB 0F 0F 0F 00 20 04 C1 01 40 01 80 01 00 00 63"
    val ERRORS_WIRE = "14 00 00 03 40 00 57"
    val MONITOR_REPLY = "94 00 00 22 00 96 00 1B 00 3A 03 C8 36 9E 0A 02 3E 03 00 00 01 F2 00 00 01 F2 00 00 01 F2 00 00 01 F1 " +
        "00 00 01 F2 00 00 01 F2 00 00 01 F2 00 00 01 F1 00 00 02 DA 00 00 02 DF 00 00 02 DA 00 00 02 D8 00 00 00 00 02 00 " +
        "01 00 01 00 3B 80 00 00 00 7D B1 00 00"
    val AXIS_REPLY = "A1 00 00 00 00 08 00 02 02 00 00 0A 00 00 00 00 1E 08 00 00 01 EF 03 20 04 B0 06 40 09 60 0C 80 13 EC" +
        " 00".repeat(44)
    val VALUES_REPLY = "A1 00 00 01 00 08 00 02 00 A5 00 A1 00 9E 00 95 00 88 00 76 00 73 00 79"
    /** First step of the owner's test edit 12:31: point 4 ms 1.58 -> 1.59 (values frame), and the axis frame. */
    val WRITE_VALUES_159 = "22 00 00 01 00 08 00 A5 00 A1 00 9F 00 95 00 88 00 76 00 73 00 79"
    val WRITE_AXIS = "22 00 00 00 00 00 02 00 00 0A 00 00 00 00 1E 08 00 00 01 EF 03 20 04 B0 06 40 09 60 0C 80 13 EC" +
        " 00".repeat(44)
    /** Owner's 9th point 3.33 ms = 1.60 (12:32:22), when 4 ms was 1.60: both frames AC STAG sent. */
    val WRITE_AXIS_9 = "22 00 00 00 00 00 02 00 00 0A 00 00 00 00 1E 09 00 00 01 EF 02 9A 03 20 04 B0 06 40 09 60 0C 80 13 EC" +
        " 00".repeat(42)
    val WRITE_VALUES_9 = "22 00 00 01 00 09 00 A5 00 A1 00 A0 00 A0 00 95 00 88 00 76 00 73 00 79"
}

/**
 * Emulated controller: answers the greeting, generic start-up requests, the monitor, the error block and
 * the multiplier map (21 / 22 / 32), in arbitrary pieces. [dropAfter] = drop the link after that many frames.
 */
class FakeStag(var dropAfter: Int = Int.MAX_VALUE) : ByteLink {
    override val label = "fake-stag"
    private val out = java.util.concurrent.LinkedBlockingQueue<ByteArray>()
    private val parserHello = FrameParser(StagFrame.HELLO_START)
    private val parser = FrameParser(StagFrame.SESSION)
    var axisBlock = StagFrame.bytes(Sniff.AXIS_REPLY).copyOfRange(8, 78)
    var values = StagFrame.bytes(Sniff.VALUES_REPLY).copyOfRange(8, 24)
    private var pendingValues: ByteArray? = null
    val received = mutableListOf<ByteArray>()
    var open = false
    var silentMonitor = false

    override fun open() { open = true }
    override fun close() { open = false }

    override fun write(data: ByteArray) {
        if (!open) throw LinkLost("closed")
        for (f in parserHello.feed(data)) if (f[0].toInt() == 0) reply(StagFrame.bytes(
            "80 00 01 00 1E 01 03 02 0E 00 37 1D 07 07 E8 0D 06 01 02 11 01 16 06 30 08 06 68 FF FF FF FF FF FF FF FF 02 00 02 00"))
        for (f in parser.feed(data)) {
            received.add(f)
            if (received.size > dropAfter) { open = false; return }
            val cmd = f[0].toInt() and 0xFF
            when {
                f.contentEquals(StagMonitor.MONITOR_REQUEST) -> if (!silentMonitor) reply(StagFrame.bytes(Sniff.MONITOR_REPLY))
                cmd == 0x21 && f[3].toInt() == 0 -> reply(StagFrame.bytes("A1 00 00 00 00 08 00 02") + axisBlock)
                cmd == 0x21 && f[3].toInt() == 1 -> reply(StagFrame.bytes("A1 00 00 01 00") + byteArrayOf(axisBlock[9], 0, 2) + values)
                cmd == 0x22 && f[3].toInt() == 0 -> { axisBlock = f.copyOfRange(6, f.size); reply(StagFrame.bytes("A2 00")) }
                cmd == 0x22 && f[3].toInt() == 1 -> { pendingValues = f.copyOfRange(6, f.size); reply(StagFrame.bytes("A2 00")) }
                cmd == 0x32 -> { pendingValues?.let { values = it }; pendingValues = null; reply(StagFrame.bytes("B2 00")) }
                else -> reply(byteArrayOf((cmd or 0x80).toByte()) + f.copyOfRange(1, minOf(f.size, 3)))
            }
        }
    }

    private fun reply(body: ByteArray) {
        val w = StagFrame.build(StagFrame.SESSION, body)
        // deliver in two pieces with a garbage byte in between, like Bluetooth does
        val cut = w.size / 2
        out.add(byteArrayOf(0x2B) + w.copyOfRange(0, cut))
        out.add(w.copyOfRange(cut, w.size))
    }

    override fun readSome(timeoutMs: Long): ByteArray {
        if (!open) throw LinkLost("closed")
        val first = out.poll(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) ?: return ByteArray(0)
        return first
    }
}

class StagTest {
    private fun hex(b: ByteArray) = StagFrame.hex(b)

    @Test fun framesMatchTheCapture() {
        assertEquals(Sniff.HELLO_WIRE, hex(StagFrame.hello()))
        assertEquals(Sniff.MONITOR_WIRE, hex(StagFrame.build(StagFrame.SESSION, StagMonitor.MONITOR_REQUEST)))
        assertEquals(Sniff.ERRORS_WIRE, hex(StagFrame.build(StagFrame.SESSION, StagMonitor.ERRORS_REQUEST)))
    }

    @Test fun parserHandlesPiecesGarbageAndLongFrames() {
        val p = FrameParser(StagFrame.SESSION)
        val a = StagFrame.build(StagFrame.SESSION, StagFrame.bytes("52 00"))
        val long = StagFrame.build(StagFrame.SESSION, ByteArray(507) { (it % 7).toByte() }.also { it[0] = 0xA3.toByte() })
        assertEquals(0x01, long[2].toInt())                       // length 508 = 01 FC: the high byte is used
        val stream = StagFrame.bytes("0D 2B 2B 2B 0D 5E 5E 5E 14 00") + a + long
        val got = mutableListOf<ByteArray>()
        for (chunk in stream.toList().chunked(5)) got += p.feed(chunk.toByteArray())
        assertEquals(2, got.size)
        assertEquals("52 00", hex(got[0]))
        assertEquals(507, got[1].size)
    }

    @Test fun monitorFieldsMatchTheOscilloscope() {
        val m = StagMonitor.parse(StagFrame.bytes(Sniff.MONITOR_REPLY))!!
        assertEquals(0.34, m.mapBar, 1e-9)
        assertEquals(1.50, m.gasPBar, 1e-9)
        assertEquals(27, m.gasTempC)
        assertEquals(58, m.reducerTempC)
        assertEquals(968, m.rpm)
        assertEquals(13.982, m.supplyV, 1e-9)
        assertTrue(m.onGas)
        assertEquals(2.49, m.petrolMs[0], 1e-9)
        assertEquals(3.65, m.gasMs[0], 1e-9)
        assertEquals(59, m.engineTempC)
        assertEquals(1.468, m.ratio!!, 0.001)
        assertNull(StagMonitor.parse(StagFrame.bytes("94 00")))
    }

    @Test fun mapParsesAndWriteFramesMatchAcStag() {
        val map = MultiplierMap.parse(StagFrame.bytes(Sniff.AXIS_REPLY), StagFrame.bytes(Sniff.VALUES_REPLY))
        assertEquals(listOf(0.0, 2.475, 4.0, 6.0, 8.0, 12.0, 16.0, 25.5), map.axisMs.map { Math.round(it * 1000) / 1000.0 })
        assertEquals(listOf(1.65, 1.61, 1.58, 1.49, 1.36, 1.18, 1.15, 1.21), map.values)
        assertEquals(Sniff.WRITE_AXIS, hex(map.writeAxisBody()))
        val v159 = map.values.toMutableList().also { it[2] = 1.59 }
        assertEquals(Sniff.WRITE_VALUES_159, hex(map.withValues(v159).writeValuesBody()))
        // 1.36 * 100 is 135.99999…: must round, not truncate
        assertEquals(136, map.valuesRaw()[4])
    }

    @Test fun addingAPointMatchesAcStagFrames() {
        val base = MultiplierMap.parse(StagFrame.bytes(Sniff.AXIS_REPLY), StagFrame.bytes(Sniff.VALUES_REPLY))
            .withValues(listOf(1.65, 1.61, 1.60, 1.49, 1.36, 1.18, 1.15, 1.21))
        assertEquals(1.60, base.valueAt(3.33), 1e-9)            // a new point starts on the line
        val axis = base.axisMs.toMutableList().also { it.add(2, 3.33) }
        val values = base.values.toMutableList().also { it.add(2, 1.60) }
        assertNull(MultiplierMap.validate(base, axis, values))
        val nine = base.with(axis, values)
        assertEquals(Sniff.WRITE_AXIS_9, hex(nine.writeAxisBody()))
        assertEquals(Sniff.WRITE_VALUES_9, hex(nine.writeValuesBody()))
        // back to 8 points = the original frames
        assertEquals(Sniff.WRITE_AXIS, hex(nine.with(base.axisMs, base.values).writeAxisBody()))
        // refusals: edge points, spacing, too many, too big a step at the new point
        assertNotNull(MultiplierMap.validate(base, axis.drop(1), values.drop(1)))
        assertNotNull(MultiplierMap.validate(base, axis.toMutableList().also { it[2] = 2.6 }, values))
        assertNotNull(MultiplierMap.validate(base, axis, values.toMutableList().also { it[2] = 1.80 }))
        val many = (1..6).map { 8.0 + it * 0.5 }
        assertNotNull(MultiplierMap.validate(base, (axis + many).sorted(), values + many.map { 1.3 }))
    }

    @Test fun validationLimitsEachWrite() {
        val map = MultiplierMap.parse(StagFrame.bytes(Sniff.AXIS_REPLY), StagFrame.bytes(Sniff.VALUES_REPLY))
        val ok = map.values.toMutableList().also { it[1] = 1.68; it[0] = 1.72 }
        assertNull(MultiplierMap.validate(map, ok))
        assertNotNull(MultiplierMap.validate(map, map.values))                                     // nothing changed
        assertNotNull(MultiplierMap.validate(map, map.values.toMutableList().also { it[3] = 1.70 })) // +14 %
        assertNotNull(MultiplierMap.validate(map, map.values.dropLast(1)))
        assertNotNull(MultiplierMap.validate(map, map.values.toMutableList().also { it[0] = Double.NaN }))
    }

    @Test fun clientWritesReadsBackAndRefusesBigSteps() {
        val fake = FakeStag()
        val c = StagClient(fake)
        c.connect()
        assertEquals(StagClient.STARTUP.size, fake.received.size)
        assertEquals(1.49, c.readMap().values[3], 1e-9)
        var backup: MultiplierMap? = null
        val target = listOf(1.72, 1.68, 1.58, 1.49, 1.36, 1.18, 1.15, 1.21)
        val after = c.writeMap(target) { backup = it }
        assertEquals(target, after.values)
        assertEquals(1.65, backup!!.values[0], 1e-9)
        val writes = fake.received.filter { (it[0].toInt() and 0xFF) in setOf(0x22, 0x32) }.map { hex(it) }
        assertEquals(listOf(Sniff.WRITE_AXIS, "22 00 00 01 00 08 00 AC 00 A8 00 9E 00 95 00 88 00 76 00 73 00 79", "32 00"), writes)
        try {
            c.writeMap(target.toMutableList().also { it[5] = 1.40 })
            throw AssertionError("must refuse +19 %")
        } catch (e: StagError) {
            assertTrue(e.message!!.contains("10 %"))
        }
        assertEquals(target, c.readMap().values)          // refused write sent nothing
    }

    @Test fun demoControllerReadsWritesAndMonitors() {
        val c = StagClient(DemoStag())
        c.connect()
        val m = c.monitor()!!
        assertTrue(m.gasPBar in 1.0..1.5 && m.rpm in 600..2500)
        val map = c.readMap()
        val target = map.values.toMutableList().also { it[0] = 1.70; it[1] = 1.66 }
        assertEquals(target, c.writeMap(target).values)
        assertEquals(target, StagClient(DemoStag()).also { it.connect() }.readMap().values)   // survives a reconnect
        // add a point at 3.3 ms (+3 % over the line), then remove it again
        val axis9 = map.axisMs.toMutableList().also { it.add(2, 3.3) }
        val v9 = target.toMutableList().also { it.add(2, Math.round(c.readMap().valueAt(3.3) * 1.03 * 100) / 100.0) }
        val nine = c.writeMap(v9, axis9)
        assertEquals(9, nine.n)
        assertEquals(3.3, nine.axisMs[2], 1e-9)
        assertEquals(8, c.writeMap(map.values, map.axisMs).n)                                     // back, for other tests
    }

    @Test fun sessionReconnectsWritesCsvAndRunsMapCommands() {
        val dir = Files.createTempDirectory("stag").toFile()
        val links = mutableListOf<FakeStag>()
        val logs = mutableListOf<String>()
        var last: StagState? = null
        val session = StagSession(
            linkFactory = { FakeStag(dropAfter = if (links.isEmpty()) StagClient.STARTUP.size + 5 else Int.MAX_VALUE).also { links += it } },
            log = { synchronized(logs) { logs += it } },
            onState = { last = it },
            mapDir = { dir },
            pollPeriodMs = 10,
        )
        val t = Thread { session.run() }
        t.start()
        val deadline = System.currentTimeMillis() + 15000
        while (System.currentTimeMillis() < deadline && links.size < 2) Thread.sleep(20)
        while (System.currentTimeMillis() < deadline && session.fresh() == null) Thread.sleep(20)
        session.startCsv(File(dir, "elm_test.stag.csv"))
        session.writeMap(listOf(1.65, 1.61, 1.60, 1.55, 1.42, 1.18, 1.15, 1.21))
        while (System.currentTimeMillis() < deadline && (last?.mapBusy != false || last?.map == null)) Thread.sleep(20)
        Thread.sleep(200)
        session.stop()
        t.join(5000)
        assertTrue("reconnected", links.size >= 2)
        assertEquals(listOf(1.65, 1.61, 1.60, 1.55, 1.42, 1.18, 1.15, 1.21), last!!.map!!.values)
        assertTrue(last!!.mapMessage, last!!.mapMessage.startsWith("Записано"))
        val csv = File(dir, "elm_test.stag.csv").readLines()
        assertEquals(StagSession.HEADER.joinToString(","), csv[0])
        assertTrue(csv.size > 3)
        assertTrue(csv[1].contains(",1.50,27,58,968,"))
        val maps = dir.listFiles()!!.map { it.name }.filter { it.startsWith("stag_map_") }.sorted()
        assertEquals(2, maps.size)
        assertTrue(File(dir, maps.first { it.endsWith("_before.json") }).readText().contains("1.49"))
        assertTrue(logs.any { it.contains("reconnect") })
    }
}
