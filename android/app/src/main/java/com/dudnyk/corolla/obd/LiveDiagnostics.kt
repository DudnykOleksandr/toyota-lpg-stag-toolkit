package com.dudnyk.corolla.obd

import java.util.Locale

/**
 * Live hints for the dashboard from the values the recorder already polls. These are pointers for
 * where to look, not a diagnosis: the trip files go to `logger/analyze.py` for the real comparison.
 *
 * Total trim (STFT+LTFT) is averaged per fuel over the last [window] samples in three zones, only on
 * a warm engine in closed loop:
 *  - idle: standing, rpm < 1000
 *  - cruise: moving, 1200-2200 rpm, load < 50 %
 *  - load: load >= 60 % or rpm >= 2500
 * A vacuum leak adds unmetered air that matters most at idle (small airflow): trims high at idle and
 * falling with airflow. A dirty MAF (under-reads at high flow) or weak fuel pressure: trims rising
 * with load. Both high and flat: a common cause (MAF scaling, fuel pressure, ECU still adapting).
 */
class LiveDiagnostics(private val window: Int = 240, private val minSamples: Int = 30) {
    enum class Zone(val title: String) { IDLE("холості"), CRUISE("рівно"), LOAD("навантаження") }

    data class ZoneStat(val mean: Double, val n: Int)

    enum class Level { INFO, WARN, ALERT }

    data class Hint(val level: Level, val text: String)

    private val buf = HashMap<Pair<String, Zone>, ArrayDeque<Double>>()

    fun reset() = buf.clear()

    fun zoneOf(v: Map<String, Any?>): Zone? {
        val rpm = num(v["rpm"]) ?: return null
        val speed = num(v["speed"]) ?: return null
        val load = num(v["load"]) ?: return null
        return when {
            speed < 1 && rpm < 1000 -> Zone.IDLE
            load >= 60 || rpm >= 2500 -> Zone.LOAD
            speed >= 1 && rpm in 1200.0..2200.0 && load < 50 -> Zone.CRUISE
            else -> null
        }
    }

    /** Feed one snapshot of the recorder values and the marker state (fuel = gas / petrol / unknown). */
    fun feed(v: Map<String, Any?>, fuel: String) {
        val ect = num(v["ect"]) ?: return
        if (ect < WARM_ECT || v["fuel_status"] != "CL") return
        val stft = num(v["stft"]) ?: return
        val ltft = num(v["ltft"]) ?: return
        val zone = zoneOf(v) ?: return
        val q = buf.getOrPut(fuel to zone) { ArrayDeque() }
        q.addLast(stft + ltft)
        while (q.size > window) q.removeFirst()
    }

    fun stat(fuel: String, zone: Zone): ZoneStat? {
        val q = buf[fuel to zone] ?: return null
        return if (q.isEmpty()) null else ZoneStat(q.average(), q.size)
    }

    private fun ready(fuel: String, zone: Zone) = stat(fuel, zone)?.takeIf { it.n >= minSamples }

    /** Hints from the trim zones plus threshold warnings on the current values. */
    fun hints(v: Map<String, Any?>): List<Hint> {
        val out = mutableListOf<Hint>()
        // --- limits on the current values ---
        num(v["ect"])?.let { if (it > 105) out.add(Hint(Level.ALERT, "ОЖ ${fmt0(it)} °C — перегрів (> 105)")) }
        num(v["atf_c"])?.let { if (it > 110) out.add(Hint(Level.ALERT, "ATF ${fmt0(it)} °C — гаряче масло АКПП (> 110; значення експериментальне)")) }
        val rpm = num(v["rpm"])
        num(v["elm_v"])?.let {
            if (rpm != null && rpm > 500 && it < 13.2) out.add(Hint(Level.WARN, "Напруга ${fmt1(it)} В на працюючому двигуні — слабка зарядка (< 13.2)"))
            if (it > 15.0) out.add(Hint(Level.WARN, "Напруга ${fmt1(it)} В — перезаряд (> 15.0)"))
        }
        // --- trims by zone, petrol: the engine itself (Stag does not change petrol trims) ---
        val fuel = if (ready("petrol", Zone.IDLE) != null || ready("petrol", Zone.LOAD) != null) "petrol" else "unknown"
        val idle = ready(fuel, Zone.IDLE)
        val cruise = ready(fuel, Zone.CRUISE)
        val load = ready(fuel, Zone.LOAD)
        val src = if (fuel == "petrol") "бензин" else "паливо не позначене"
        if (idle != null && load != null) {
            when {
                idle.mean >= 8 && idle.mean - load.mean >= 5 -> out.add(Hint(Level.WARN,
                    "Трими $src: холості ${sgn(idle.mean)} %, навантаження ${sgn(load.mean)} % — спадають з потоком повітря, " +
                        "схоже на підсос повітря (вакуумні шланги, PCV, прокладка впуску)"))
                load.mean >= 8 && load.mean - idle.mean >= 5 -> out.add(Hint(Level.WARN,
                    "Трими $src: холості ${sgn(idle.mean)} %, навантаження ${sgn(load.mean)} % — ростуть з навантаженням, " +
                        "схоже на брудний MAF (занижує) або слабкий тиск палива"))
                idle.mean >= 8 && load.mean >= 8 -> out.add(Hint(Level.WARN,
                    "Трими $src високі всюди (${sgn(idle.mean)} / ${sgn(load.mean)} %) — спільна причина: MAF, тиск палива, або ECU ще довчається"))
                idle.mean <= -8 && load.mean <= -8 -> out.add(Hint(Level.WARN,
                    "Трими $src низькі всюди (${sgn(idle.mean)} / ${sgn(load.mean)} %) — суміш багата: форсунки, тиск палива, датчик кисню"))
                else -> out.add(Hint(Level.INFO, "Трими $src: холості ${sgn(idle.mean)} %, навантаження ${sgn(load.mean)} % — у межах ±8"))
            }
        } else if (cruise == null && idle == null && load == null) {
            out.add(Hint(Level.INFO, "Трими: мало даних (потрібен прогрітий двигун у closed loop, холості й навантаження)"))
        }
        // --- gas vs petrol in the same zone: Stag calibration ---
        for (z in Zone.values()) {
            val g = ready("gas", z) ?: continue
            val p = ready("petrol", z) ?: continue
            val d = g.mean - p.mean
            if (d <= -3) out.add(Hint(Level.WARN, "${z.title}: газ ${sgn(g.mean)} % проти бензину ${sgn(p.mean)} % — на газу багато (множник Stag у цій зоні)"))
            else if (d >= 3) out.add(Hint(Level.WARN, "${z.title}: газ ${sgn(g.mean)} % проти бензину ${sgn(p.mean)} % — на газу бідно (множник Stag у цій зоні)"))
        }
        return out
    }

    companion object {
        const val WARM_ECT = 75.0

        fun num(v: Any?): Double? = (v as? Number)?.toDouble()
        private fun fmt0(x: Double) = String.format(Locale.ROOT, "%.0f", x)
        private fun fmt1(x: Double) = String.format(Locale.ROOT, "%.1f", x)
        fun sgn(x: Double) = String.format(Locale.ROOT, "%+.1f", x)
    }
}
