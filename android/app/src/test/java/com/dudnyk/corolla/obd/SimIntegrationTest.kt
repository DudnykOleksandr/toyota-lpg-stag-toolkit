package com.dudnyk.corolla.obd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.concurrent.thread

/**
 * The recorder against `python logger/sim.py --tcp PORT`. Skipped unless SIM_TCP=host:port is set.
 * SIM_OUT = folder for the trip files (then run `python logger/analyze.py <csv>` on them),
 * SIM_SECONDS = trip length, SIM_FUEL_PERIOD = the simulator's --fuel-period (markers follow it).
 *
 * Scenario: connect with recording off (dashboard only, no files), turn recording on, set
 * city + gas + idle at once, alternate fuel on the simulator's schedule; then a second short trip in
 * the same connection.
 */
class SimIntegrationTest {
    @Test fun dashboardThenTripsFromSimulator() {
        val target = System.getenv("SIM_TCP").orEmpty()
        assumeTrue("SIM_TCP not set", target.isNotEmpty())
        val (host, port) = target.split(":").let { it[0] to it[1].toInt() }
        val out = File(System.getenv("SIM_OUT").orEmpty().ifEmpty { "build/sim-trip" })
        val seconds = System.getenv("SIM_SECONDS").orEmpty().ifEmpty { "60" }.toDouble()
        val fuelPeriod = System.getenv("SIM_FUEL_PERIOD").orEmpty().ifEmpty { "240" }.toDouble()
        val before = out.listFiles()?.map { it.name }?.toSet().orEmpty()

        var last: RecorderStatus? = null
        val rec = TripRecorder(out, { TcpTransport(host, port) },
            TripRecorder.Options(sim = true, silentStopMin = 0.0), onStatus = { last = it })
        val t = thread { rec.run() }
        val start = System.nanoTime()
        fun el() = (System.nanoTime() - start) / 1e9

        // 1. dashboard only: values arrive, no files
        while (el() < 6) Thread.sleep(100)
        assertEquals(Phase.LIVE, last?.phase)
        assertTrue("values on the dashboard", last?.values?.containsKey("rpm") == true)
        assertEquals("no trip files without recording", before, out.listFiles()?.map { it.name }?.toSet().orEmpty())

        // 2. record; several tags at once
        rec.mark(Mark.GAS)
        rec.mark(Mark.CITY)
        rec.mark(Mark.IDLE_ON)
        rec.setRecording(true)
        val tripStart = el()
        // the simulator's fuel schedule runs from the connection, not from the trip start
        var switches = (el() / fuelPeriod).toInt()
        while (el() - tripStart < seconds) {
            if (el() >= (switches + 1) * fuelPeriod) {
                switches += 1
                rec.mark(if (switches % 2 == 1) Mark.PETROL else Mark.GAS)
                if (switches == 1) rec.mark(Mark.IDLE_OFF)
            }
            Thread.sleep(100)
        }
        rec.setRecording(false)
        while (last?.recording == true) Thread.sleep(100)
        val first = rec.files()
        val firstBase = rec.baseName
        val firstRows = last?.rows ?: 0

        // 3. a second trip in the same connection
        rec.setRecording(true)
        while (last?.recording != true || (last?.rows ?: 0) < 20) Thread.sleep(100)
        rec.stop()
        t.join(30_000)
        println("trip 1: $firstBase files=${first.map { it.name }} rows=$firstRows; trip 2: ${rec.baseName} rows=${last?.rows}")

        val names = first.map { it.name }
        assertTrue(names.toString(), listOf(".csv", ".log", ".meta.json", ".health.json").all { s -> names.any { it.endsWith(s) } })
        assertTrue("rows: $firstRows", firstRows > 20)
        assertTrue("second trip has its own files", rec.baseName != firstBase && rec.files().any { it.name.endsWith(".csv") })
        val csv = first.first { it.name.endsWith(".csv") }.readLines()
        val header = csv[0].split(",")
        val row = csv[1].split(",")
        fun col(n: String) = row[header.indexOf(n)]
        assertEquals("gas", col("fuel"))
        assertEquals("city", col("road"))
        assertEquals("on", col("idle"))
    }
}
