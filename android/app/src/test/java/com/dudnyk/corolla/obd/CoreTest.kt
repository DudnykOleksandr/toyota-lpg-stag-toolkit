package com.dudnyk.corolla.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Replays scripted ELM answers; records what was sent. */
class ScriptTransport(private val answers: Map<String, String>) : Transport {
    val sent = mutableListOf<String>()
    private var pending = ""
    override val label = "script"
    override fun open() {}
    override fun write(data: ByteArray) {
        val line = String(data).trim()
        if (line.isEmpty()) return
        sent.add(line)
        pending = (answers[line] ?: "NO DATA") + "\r\r>"
    }
    override fun readUntilPrompt(timeoutMs: Long): ByteArray = pending.toByteArray().also { pending = "" }
    override fun clearInput() {}
    override fun close() {}
}

class CoreTest {
    private fun canElm(answers: Map<String, String>): Pair<Elm327, ScriptTransport> {
        val t = ScriptTransport(mapOf(
            "ATZ" to "ELM327 v1.5", "ATE0" to "OK", "ATL0" to "OK", "ATS1" to "OK", "ATH0" to "OK", "ATAT1" to "OK",
            "ATSP0" to "OK", "0100" to "41 00 BE 3F A8 13", "ATDP" to "AUTO, ISO 15765-4 (CAN 11/500)", "ATDPN" to "A6",
            "ATH1" to "OK", "ATSH7E0" to "OK", "ATCRA7E8" to "OK", "010D 1" to "7E8 03 41 0D 00",
        ) + answers)
        val e = Elm327(t)
        e.init()
        return e to t
    }

    @Test fun packGroupsMatchesTheLaptopLogger() {
        // logs/elm_20260928_130033.meta.json: supported fast PIDs of this car (no MAP 0B)
        val sup = listOf(0x06, 0x07, 0x0C, 0x04, 0x10, 0x11, 0x0D, 0x14, 0x15)
        val fast = Pids.FAST.filter { it in sup }
        assertEquals(listOf(listOf(0x0C, 0x10), listOf(0x14, 0x15), listOf(0x06, 0x07, 0x04), listOf(0x11, 0x0D)),
            Pids.packGroups(fast))
        val slow = Pids.SLOW.filter { it in listOf(0x05, 0x0F, 0x0E, 0x03, 0x3C, 0x44, 0x43, 0x42, 0x33, 0x1F) }
        assertEquals(listOf(listOf(0x03, 0x3C), listOf(0x44, 0x43), listOf(0x42, 0x1F), listOf(0x05, 0x0F, 0x0E), listOf(0x33)),
            Pids.packGroups(slow))
    }

    @Test fun initDetectsCanAndCountSuffix() {
        val (e, _) = canElm(emptyMap())
        assertTrue(e.isCan)
        assertTrue(e.headers)
        assertTrue(e.countSuffix)
        assertEquals("ELM327 v1.5", e.version)
    }

    @Test fun multiPidSingleFrame() {
        val (e, t) = canElm(mapOf("01060704 1" to "7E8 07 41 06 7E 07 90 04 57"))
        val v = e.query(listOf(0x06, 0x07, 0x04))
        assertEquals("01060704 1", t.sent.last())
        assertEquals(-1.56, v["stft"])
        assertEquals(12.5, v["ltft"])
        assertEquals(34.1, v["load"])
    }

    @Test fun isoTpMultiFrameAndOtherEcuIgnored() {
        val (e, _) = canElm(mapOf("0902" to listOf(
            "7E9 03 49 02 01",                                   // gearbox ECU: must be ignored
            "7E8 10 14 49 02 01 4A 54 44",
            "7E8 21 42 55 34 30 45 30 30",
            "7E8 22 31 32 33 34 35 36 37",
        ).joinToString("\r")))
        val d = e.parse(e.cmd("0902"))[0x49]!!
        assertEquals(0x14 - 1, d.size)
        assertEquals("JTDBU40E001234567", d.drop(2).map { it.toChar() }.joinToString(""))
    }

    @Test fun errorsGiveNothing() {
        val (e, _) = canElm(mapOf("010C 1" to "NO DATA"))
        assertTrue(e.query(listOf(0x0C)).isEmpty())
    }

    @Test fun headerlessParse() {
        val e = Elm327(ScriptTransport(emptyMap()))
        assertEquals(listOf(0x0C, 0x12, 0x80), e.parse(listOf("41 0C 12 80"))[0x41]!!.toList())
        // multi-line without headers: byte count, then "0:", "1:" lines
        val m = e.parse(listOf("00A", "0: 49 02 01 41 42 43", "1: 44 45 46 47 00 00 00"))[0x49]!!
        assertEquals(listOf(0x02, 0x01, 0x41, 0x42, 0x43, 0x44, 0x45, 0x46, 0x47, 0, 0, 0), m.toList())
    }

    @Test fun readOnlyWhitelist() {
        for (ok in listOf("ATZ", "0100", "010C0D 1", "03", "07", "0A", "0600", "06A2", "0902", "21D9", "ATRV", "ATCRA7E8")) {
            assertTrue(ok, ReadOnly.allowed(ok))
        }
        for (bad in listOf("04", "0400", "14", "2E0101", "3101", "ATPP", "ATMA", "1001", "27", "ATSH7E1")) {
            assertFalse(bad, ReadOnly.allowed(bad))
        }
        val e = Elm327(ScriptTransport(emptyMap()))
        try {
            e.cmd("04")
            throw AssertionError("service 04 must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test fun pidDecodersMatchPython() {
        val d = Pids.ALL
        assertEquals(mapOf("rpm" to 1184), d.getValue(0x0C).decode(intArrayOf(0x12, 0x80)))
        assertEquals(mapOf("maf" to 5.5), d.getValue(0x10).decode(intArrayOf(0x02, 0x26)))
        assertEquals(mapOf("o2s1_v" to 0.78, "o2s1_trim" to -1.56), d.getValue(0x14).decode(intArrayOf(156, 126)))
        assertEquals(mapOf("o2s2_v" to 0.055, "o2s2_trim" to null), d.getValue(0x15).decode(intArrayOf(11, 0xFF)))
        assertEquals(mapOf("fuel_status" to "CL"), d.getValue(0x03).decode(intArrayOf(2, 0)))
        assertEquals(mapOf("timing" to 10.0), d.getValue(0x0E).decode(intArrayOf(148)))
        assertEquals(mapOf("cat_temp" to 327.1), d.getValue(0x3C).decode(intArrayOf(0x0E, 0x57)))
        assertEquals(mapOf("ect" to 90), d.getValue(0x05).decode(intArrayOf(130)))
    }

    @Test fun pythonNumberFormatting() {
        assertEquals("12.5", Py.float(12.5))
        assertEquals("10.0", Py.float(10.0))
        assertEquals("0.055", Py.float(0.055))
        assertEquals("1e-05", Py.float(0.00001))
        assertEquals("0.0001", Py.float(0.0001))
        assertEquals("12345678.0", Py.float(12345678.0))
        assertEquals("0.35", Py.g(0.35))
        assertEquals("40", Py.g(40))
        assertEquals("1104", Py.g(1104.0))
        assertEquals("1.23457e+06", Py.g(1234567.0))
        assertEquals("0.000305", Py.g(0.000305))
        assertEquals("['302G4200', 'A0C01000']", Py.str(listOf("302G4200", "A0C01000")))
        assertEquals(2.67, Py.round(2.675, 2), 0.0)       // 2.675 is 2.67499999... in binary, like Python
        assertEquals("-0.0", Py.float(-0.0))
        assertEquals(2, Py.roundInt(2.5))
    }

    @Test fun jsonLikePython() {
        val j = Json.dumps(linkedMapOf("a" to listOf("01", "03"), "b" to emptyList<Int>(), "c" to null, "d" to true,
            "e" to linkedMapOf<String, Any>(), "f" to 0.351, "g" to "°C"))
        assertEquals("{\n  \"a\": [\n    \"01\",\n    \"03\"\n  ],\n  \"b\": [],\n  \"c\": null,\n  \"d\": true,\n" +
            "  \"e\": {},\n  \"f\": 0.351,\n  \"g\": \"°C\"\n}", j)
    }

    @Test fun healthDecoding() {
        assertEquals("P0302", Health.decodeDtc(0x03, 0x02))
        assertEquals("C1234", Health.decodeDtc(0x52, 0x34))
        assertEquals(0.351 to "V", Health.scaled(0x0B, 351))
        assertEquals(40L to "ms", Health.scaled(0x10, 40))
        val (v, u) = Health.scaled(0x16, 1000)            // 0.1 °C with -40 offset
        assertEquals(60.0, v.toDouble(), 1e-9)
        assertEquals("°C", u)
        assertEquals(-1L to "", Health.scaled(0x81, 0xFFFF))  // signed
    }

    @Test fun healthSnapshotOverCan() {
        val (e, _) = canElm(mapOf(
            "0101" to "7E8 06 41 01 00 07 65 21",
            "03" to "7E8 02 43 00",
            "07" to "7E8 04 47 01 03 02",
            "0A" to "NO DATA",
            "0600" to "7E8 06 46 00 00 00 00 01",   // only A1..? next page flag
            "0920" to "NO DATA",
        ))
        val h = Health.read(e, listOf(0x01))
        @Suppress("UNCHECKED_CAST")
        val rd = h["readiness"] as Map<String, Any?>
        assertEquals(false, rd["mil_on"])
        assertEquals(listOf<String>(), h["dtc_stored"])
        assertEquals(listOf("P0302"), h["dtc_pending"])
        assertNull(h["dtc_permanent"])
        val lines = Health.format(h)
        assertTrue(lines.any { it == "pending DTC: P0302" })
        assertTrue(lines.any { it == "permanent DTC: n/a" })
        assertTrue(lines.any { it.startsWith("readiness: misfire READY, fuel system READY, components READY, catalyst not ready") })
    }

    @Test fun diagnosticsVacuumLeakPattern() {
        val d = LiveDiagnostics(minSamples = 5)
        fun feed(rpm: Int, speed: Int, load: Double, stft: Double, ltft: Double) =
            d.feed(mapOf("rpm" to rpm, "speed" to speed, "load" to load, "ect" to 90, "fuel_status" to "CL",
                "stft" to stft, "ltft" to ltft), "petrol")
        repeat(10) { feed(700, 0, 22.0, 4.0, 8.0) }       // idle: +12
        repeat(10) { feed(3000, 90, 70.0, 0.0, 3.0) }     // load: +3
        val h = d.hints(emptyMap())
        assertTrue(h.joinToString { it.text }, h.any { "підсос" in it.text })
    }

    @Test fun diagnosticsDirtyMafPattern() {
        val d = LiveDiagnostics(minSamples = 5)
        fun feed(rpm: Int, speed: Int, load: Double, sum: Double) =
            d.feed(mapOf("rpm" to rpm, "speed" to speed, "load" to load, "ect" to 90, "fuel_status" to "CL",
                "stft" to sum, "ltft" to 0.0), "petrol")
        repeat(10) { feed(700, 0, 22.0, 1.0) }
        repeat(10) { feed(3000, 90, 75.0, 11.0) }
        assertTrue(d.hints(emptyMap()).any { "MAF" in it.text })
    }

    @Test fun diagnosticsIgnoresColdAndOpenLoop() {
        val d = LiveDiagnostics(minSamples = 1)
        d.feed(mapOf("rpm" to 700, "speed" to 0, "load" to 22.0, "ect" to 40, "fuel_status" to "CL", "stft" to 20.0, "ltft" to 0.0), "petrol")
        d.feed(mapOf("rpm" to 700, "speed" to 0, "load" to 22.0, "ect" to 90, "fuel_status" to "OL_load", "stft" to 20.0, "ltft" to 0.0), "petrol")
        assertNull(d.stat("petrol", LiveDiagnostics.Zone.IDLE))
    }

    @Test fun warnings() {
        val h = LiveDiagnostics().hints(mapOf("ect" to 108, "rpm" to 800, "elm_v" to 12.6))
        assertTrue(h.any { it.level == LiveDiagnostics.Level.ALERT && "ОЖ" in it.text })
        assertTrue(h.any { "зарядка" in it.text })
    }
}
