package com.dudnyk.corolla.obd

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Number and JSON formatting that matches what the Python logger writes, so the files from the
 * phone are the same as `logs/` from the laptop and `logger/analyze.py` reads them unchanged.
 */
object Py {
    /** Python `round(x, n)`: exact binary value, half-even. */
    fun round(x: Double, n: Int): Double =
        if (x.isNaN() || x.isInfinite()) x else BigDecimal(x).setScale(n, RoundingMode.HALF_EVEN).toDouble()

    /** Python `round(x)` -> int (banker's rounding). */
    fun roundInt(x: Double): Int = Math.rint(x).toInt()

    /** Python `repr(float)`: shortest round-trip digits, scientific below 1e-4 and from 1e16. */
    fun float(d: Double): String {
        if (d.isNaN()) return "NaN"
        if (d.isInfinite()) return if (d > 0) "Infinity" else "-Infinity"
        if (d == 0.0) return if (1.0 / d < 0) "-0.0" else "0.0"
        val a = Math.abs(d)
        if (a >= 1e-4 && a < 1e16) {
            val s = BigDecimal(d.toString()).stripTrailingZeros().toPlainString()
            return if ('.' in s) s else "$s.0"
        }
        // Java: "1.5E-7" -> Python: "1.5e-07"
        val (mant, exp) = d.toString().split('E')
        val m = mant.removeSuffix(".0")
        val e = exp.toInt()
        return m + "e" + (if (e < 0) "-" else "+") + Math.abs(e).toString().padStart(2, '0')
    }

    /** `str(value)` for the value types the logger produces. */
    fun str(v: Any?): String = when (v) {
        null -> "None"
        is Double -> float(v)
        is Float -> float(v.toDouble())
        is Boolean -> if (v) "True" else "False"
        is List<*> -> v.joinToString(", ", "[", "]") { repr(it) }
        else -> v.toString()
    }

    fun repr(v: Any?): String = if (v is String) "'" + v.replace("\\", "\\\\").replace("'", "\\'") + "'" else str(v)

    /** Python `f"{x:g}"`. */
    fun g(x: Number): String {
        if (x is Int || x is Long) return x.toString().let { if (Math.abs(x.toLong()) < 1_000_000) it else g(x.toDouble()) }
        val d = x.toDouble()
        if (d == 0.0) return if (1.0 / d < 0) "-0" else "0"
        if (d.isNaN()) return "nan"
        if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
        val bd = BigDecimal(d).round(MathContext(6, RoundingMode.HALF_EVEN))
        val exp = bd.precision() - bd.scale() - 1
        return if (exp < -4 || exp >= 6) {
            val digits = bd.unscaledValue().abs().toString().trimEnd('0').ifEmpty { "0" }
            val mant = if (digits.length > 1) digits[0] + "." + digits.substring(1) else digits
            (if (bd.signum() < 0) "-" else "") + mant + "e" + (if (exp < 0) "-" else "+") +
                Math.abs(exp).toString().padStart(2, '0')
        } else {
            bd.stripTrailingZeros().toPlainString()
        }
    }

    fun bool(b: Boolean) = if (b) "True" else "False"
}

/** `json.dumps(obj, indent=2, ensure_ascii=False)` for maps, lists, strings, numbers, booleans and null. */
object Json {
    fun dumps(v: Any?, indent: Int = 2): String = StringBuilder().also { write(it, v, indent, 0) }.toString()

    private fun write(sb: StringBuilder, v: Any?, step: Int, level: Int) {
        when (v) {
            null -> sb.append("null")
            is Boolean -> sb.append(if (v) "true" else "false")
            is Double -> sb.append(Py.float(v))
            is Float -> sb.append(Py.float(v.toDouble()))
            is Number -> sb.append(v.toString())
            is String -> string(sb, v)
            is Map<*, *> -> {
                if (v.isEmpty()) { sb.append("{}"); return }
                sb.append("{\n")
                var first = true
                for ((k, value) in v) {
                    if (!first) sb.append(",\n")
                    first = false
                    pad(sb, step * (level + 1))
                    string(sb, k.toString())
                    sb.append(": ")
                    write(sb, value, step, level + 1)
                }
                sb.append('\n')
                pad(sb, step * level)
                sb.append('}')
            }
            is Iterable<*> -> {
                val items = v.toList()
                if (items.isEmpty()) { sb.append("[]"); return }
                sb.append("[\n")
                items.forEachIndexed { i, item ->
                    if (i > 0) sb.append(",\n")
                    pad(sb, step * (level + 1))
                    write(sb, item, step, level + 1)
                }
                sb.append('\n')
                pad(sb, step * level)
                sb.append(']')
            }
            else -> string(sb, v.toString())
        }
    }

    private fun pad(sb: StringBuilder, n: Int) { repeat(n) { sb.append(' ') } }

    private fun string(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c == '\b' -> sb.append("\\b")
                c == '\u000C' -> sb.append("\\f")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        sb.append('"')
    }
}
