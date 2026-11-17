package com.dudnyk.corolla.obd

/**
 * Read-only whitelist: every line sent to the ELM must match one of these. Service 04 (clear
 * codes) and anything that writes to an ECU cannot be sent - [Elm327.cmd] throws instead.
 */
object ReadOnly {
    private val ALLOWED = listOf(
        Regex("AT(Z|E0|L0|S1|H0|H1|AT1|SP0|SPA6|DP|DPN|RV|SH7E0|CRA7E8)"),
        Regex("01(?:[0-9A-F]{2}){1,6}( 1)?"),   // current data, up to 6 PIDs per request
        Regex("0[37A]"),                         // stored / pending / permanent DTCs
        Regex("06[0-9A-F]{2}"),                  // on-board test results (mode 06)
        Regex("09(02|04)"),                      // VIN, calibration ids
        Regex("21D9"),                           // Toyota ECM block D9: ATF temperature candidate
    )

    fun allowed(line: String) = ALLOWED.any { it.matches(line) }

    fun check(line: String) {
        require(allowed(line)) { "command not in the read-only whitelist: '$line'" }
    }
}

/**
 * Minimal ELM327 driver tuned for fast mode 01 polling over Bluetooth SPP. Port of `logger/elm327.py`:
 * on CAN one request carries several PIDs so that the answer fits one CAN frame, and requests go to
 * the engine ECU only (7E0 -> 7E8).
 */
class Elm327(private val transport: Transport, private val log: (String) -> Unit = {}) {
    var version = ""
        private set
    var protocol = ""
        private set
    var isCan = false
        private set
    var headers = false
        private set
    var countSuffix = false    // "010C 1": ELM returns after the first response
        private set
    var multiPidOk = true

    fun close() {
        try {
            transport.close()
        } catch (_: Exception) {
        }
    }

    fun cmd(line: String, timeoutMs: Long = 2000): List<String> {
        ReadOnly.check(line)
        transport.clearInput()
        transport.write((line + "\r").toByteArray(Charsets.US_ASCII))
        val raw = transport.readUntilPrompt(timeoutMs)
        if (raw.isEmpty() || raw.last() != '>'.code.toByte()) {
            throw ElmError("timeout on '$line' (got '${String(raw, Charsets.US_ASCII)}')")
        }
        val text = String(raw, 0, raw.size - 1, Charsets.US_ASCII).replace('\n', '\r')
        var lines = text.split('\r').map { it.trim() }.filter { it.isNotEmpty() }
        // drop command echo if ATE0 has not taken effect yet
        if (lines.isNotEmpty() && lines[0].replace(" ", "") == line.replace(" ", "")) lines = lines.drop(1)
        return lines.filterNot { it.startsWith("SEARCHING") }
    }

    fun init() {
        val t0 = System.nanoTime()
        fun secs() = String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - t0) / 1e9)
        transport.open()
        val tOpen = secs()
        // wake up / flush: a lone CR aborts anything pending
        transport.write("\r".toByteArray(Charsets.US_ASCII))
        Thread.sleep(300)
        transport.clearInput()
        version = cmd("ATZ", 5000).joinToString(" ").ifEmpty { "?" }
        for (c in listOf("ATE0", "ATL0", "ATS1", "ATH0", "ATAT1")) cmd(c)
        // Auto protocol, but try ISO 15765-4 CAN 11/500 (this car) first instead of a full search
        if ("?" in cmd("ATSPA6")) cmd("ATSP0")
        // force protocol detection; ignition must be on
        val tProto = System.nanoTime()
        val resp = cmd("0100", 10000)
        val protoSecs = String.format(java.util.Locale.ROOT, "%.1f", (System.nanoTime() - tProto) / 1e9)
        if (resp.isEmpty() || ELM_ERRORS.any { resp.joinToString(" ").contains(it) }) {
            throw ElmError("ECU not answering on 0100: ${Py.str(resp)} (ignition on?)")
        }
        protocol = cmd("ATDP").joinToString(" ")
        val dpn = cmd("ATDPN").joinToString(" ").replace("A", "")
        isCan = dpn.isNotEmpty() && dpn[0] in "6789"
        if (isCan) {
            // Headers on + physical addressing to the engine ECU only (7E0 -> 7E8).
            // The automatic gearbox ECU would otherwise also answer some PIDs.
            cmd("ATH1")
            headers = true
            for (c in listOf("ATSH7E0", "ATCRA7E8")) {
                if ("?" in cmd(c)) log("ELM ignored $c (clone?) - filtering by header in software")
            }
            countSuffix = probeCountSuffix()
        }
        log("ELM: $version | protocol: $protocol | CAN=${Py.bool(isCan)} | count-suffix=${Py.bool(countSuffix)}")
        log("init ${secs()} s (link $tOpen s, first 0100 $protoSecs s)")
    }

    private fun probeCountSuffix(): Boolean = try {
        parse(cmd("010D 1", 2000))[0x41]?.isNotEmpty() == true
    } catch (_: ElmError) {
        false
    }

    /**
     * {service response byte: payload bytes} for the engine ECU. Handles single frames, ISO-TP
     * multi-frame (headers on) and headerless output.
     */
    fun parse(lines: List<String>): Map<Int, IntArray> {
        val joined = lines.joinToString(" ")
        if ("?" in lines || ELM_ERRORS.any { joined.contains(it) }) return emptyMap()
        if (!headers) {
            val data = mutableListOf<Int>()
            for (ln0 in lines) {
                var ln = ln0
                if (':' in ln) {
                    ln = ln.substringAfter(':')          // "0: 41 00 ..." multi-line without headers
                } else if (ln.replace(" ", "").length <= 3) {
                    continue                             // byte-count line of a multi-frame message
                }
                data += hexBytes(ln)
            }
            return if (data.isEmpty()) emptyMap() else mapOf(data[0] to data.drop(1).toIntArray())
        }
        val frames = mutableListOf<List<Int>>()
        for (ln in lines) {
            val tok = ln.split(' ').filter { it.isNotEmpty() }
            if (tok.size < 2 || tok[0].uppercase() != "7E8") continue
            frames.add(tok.drop(1).filter { isHex(it) }.map { it.toInt(16) })
        }
        var payload = mutableListOf<Int>()
        var expected = 0
        for (f in frames) {
            if (f.isEmpty()) continue
            val kind = f[0] shr 4
            if (kind == 0) {
                payload = f.drop(1).take(f[0] and 0x0F).toMutableList()
                expected = payload.size
                break
            }
            if (kind == 1) {
                expected = ((f[0] and 0x0F) shl 8) or f[1]
                payload = f.drop(2).toMutableList()
            } else if (kind == 2) {
                payload.addAll(f.drop(1))
            }
        }
        val p = if (expected != 0) payload.take(expected) else payload
        return if (p.isEmpty()) emptyMap() else mapOf(p[0] to p.drop(1).toIntArray())
    }

    /** Mode 01 request for up to 6 PIDs; returns {pid: data bytes}. */
    fun queryRaw(pids: List<Int>): Map<Int, IntArray> {
        var req = "01" + pids.joinToString("") { Pids.hex(it) }
        if (countSuffix) req += " 1"
        val data = parse(cmd(req))[0x41] ?: return emptyMap()
        val out = LinkedHashMap<Int, IntArray>()
        var i = 0
        while (i < data.size) {
            val pid = data[i]
            val def = Pids.ALL[pid] ?: break
            if (i + 1 + def.nbytes > data.size) break
            out[pid] = data.copyOfRange(i + 1, i + 1 + def.nbytes)
            i += 1 + def.nbytes
        }
        return out
    }

    fun query(pids: List<Int>): Map<String, Any?> {
        val values = LinkedHashMap<String, Any?>()
        for ((pid, d) in queryRaw(pids)) {
            try {
                values.putAll(Pids.ALL.getValue(pid).decode(d))
            } catch (_: Exception) {
            }
        }
        return values
    }

    /** Read the 0100/0120/0140 support bitmaps. */
    fun supportedPids(): List<Int> {
        val supported = mutableListOf<Int>()
        var base = 0x00
        while (base <= 0x40) {
            val data = parse(cmd("01" + Pids.hex(base)))[0x41] ?: break
            if (data.size < 5 || data[0] != base) break
            val bits = (data[1].toLong() shl 24) or (data[2].toLong() shl 16) or (data[3].toLong() shl 8) or data[4].toLong()
            for (i in 0 until 32) {
                if (bits and (1L shl (31 - i)) != 0L) supported.add(base + i + 1)
            }
            if (bits and 1L == 0L) break          // PID base+0x20 not supported -> stop
            base += 0x20
        }
        return supported
    }

    fun voltage(): Double? = try {
        cmd("ATRV").firstOrNull()?.trimEnd('V', 'v')?.toDouble()
    } catch (_: ElmError) {
        null
    } catch (_: NumberFormatException) {
        null
    }

    /**
     * Gearbox (U340E) fluid temperature. The 1ZR ECU runs the gearbox itself; service 21 block D9,
     * byte 4 - 40 degC read 79-80 degC after a 40 min drive (candidate, see logs/ecm_mode21_scan_*.txt).
     */
    fun readAtf(): Double? {
        val d = try {
            parse(cmd("21D9", 2000))[0x61]
        } catch (_: ElmError) {
            return null
        }
        return if (d != null && d.size > 4 && d[0] == 0xD9) (d[4] - 40).toDouble() else null
    }

    companion object {
        val ELM_ERRORS = listOf(
            "NO DATA", "CAN ERROR", "UNABLE TO CONNECT", "BUS INIT", "BUS ERROR", "BUS BUSY",
            "STOPPED", "BUFFER FULL", "FB ERROR", "DATA ERROR", "RX ERROR", "LV RESET", "ACT ALERT", "ERR",
        )

        fun isHex(t: String) = t.length == 2 && t.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' }

        fun hexBytes(s0: String): List<Int> {
            val s = s0.replace(" ", "")
            return (0 until s.length - 1 step 2).map { s.substring(it, it + 2) }.filter { isHex(it) }.map { it.toInt(16) }
        }
    }
}
