package com.quantfolio.quant

import java.math.BigDecimal
import java.math.RoundingMode

// Python 문자열 포맷 재현 — 원본이 만든 메시지·설명 문구를 글자 그대로 맞추기 위해.

/** Python repr 와 같은 숫자 표기(Int 는 그대로, 실수는 최단 표현·지수는 1e-05 형식). */
fun pyNum(n: Number): String = when (n) {
    is Int, is Long -> n.toString()
    else -> pyFloat(n.toDouble())
}

fun pyFloat(d: Double): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    val s = d.toString()
    val e = s.indexOf('E')
    if (e < 0) return s
    val mantissa = s.substring(0, e).removeSuffix(".0")
    val exp = s.substring(e + 1).toInt()
    if (exp in -4..15) return BigDecimal(s).toPlainString().let { if ('.' in it) it else "$it.0" }
    return mantissa + "e" + (if (exp < 0) "-" else "+") + kotlin.math.abs(exp).toString().padStart(2, '0')
}

/** Python `format(x, '.Nf')` — 정확한 이진값 기준 half-even(Java %f 의 half-up 과 다르다). NaN·inf 는 Python 표기. */
fun pyFixed(d: Double, digits: Int, grouping: Boolean = false): String {
    if (d.isNaN()) return "nan"
    if (d.isInfinite()) return if (d > 0) "inf" else "-inf"
    val plain = BigDecimal(d).setScale(digits, RoundingMode.HALF_EVEN).toPlainString()
    if (!grouping) return plain
    val neg = plain.startsWith("-")
    val body = plain.removePrefix("-")
    val intPart = body.substringBefore('.')
    val frac = body.substringAfter('.', "")
    val grouped = intPart.reversed().chunked(3).joinToString(",").reversed()
    return (if (neg) "-" else "") + grouped + (if (frac.isNotEmpty()) ".$frac" else "")
}

/** Python `repr(str)`. */
fun pyRepr(s: String): String = if ('\'' in s && '"' !in s) "\"$s\"" else "'" + s.replace("\\", "\\\\").replace("'", "\\'") + "'"

/**
 * Python `json.dumps(value, sort_keys=?, default=str)` 와 바이트 동일한 직렬화(구분자 `", "`·`": "`, ensure_ascii).
 * 해시·지문처럼 원본이 만든 문자열과 그대로 비교해야 하는 곳에만 쓴다(응답 JSON 은 Jackson).
 */
fun pyJsonDumps(value: Any?, sortKeys: Boolean = false): String = StringBuilder().also { pyJson(value, sortKeys, it) }.toString()

private fun pyJson(v: Any?, sortKeys: Boolean, out: StringBuilder) {
    when (v) {
        null -> out.append("null")
        is Boolean -> out.append(if (v) "true" else "false")
        is Int, is Long, is Short, is Byte, is java.math.BigInteger -> out.append(v.toString())
        is Double, is Float -> {
            val d = (v as Number).toDouble()
            out.append(
                when {
                    d.isNaN() -> "NaN"
                    d.isInfinite() -> if (d > 0) "Infinity" else "-Infinity"
                    else -> pyFloat(d)
                },
            )
        }
        is BigDecimal -> out.append(pyFloat(v.toDouble()))
        is Map<*, *> -> {
            val entries = v.entries.map { it.key.toString() to it.value }.let { e -> if (sortKeys) e.sortedBy { it.first } else e }
            out.append('{')
            entries.forEachIndexed { i, (k, x) ->
                if (i > 0) out.append(", ")
                pyJsonString(k, out)
                out.append(": ")
                pyJson(x, sortKeys, out)
            }
            out.append('}')
        }
        is Iterable<*> -> {
            out.append('[')
            v.forEachIndexed { i, x ->
                if (i > 0) out.append(", ")
                pyJson(x, sortKeys, out)
            }
            out.append(']')
        }
        else -> pyJsonString(v.toString(), out) // default=str
    }
}

private fun pyJsonString(s: String, out: StringBuilder) {
    out.append('"')
    for (c in s) {
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            c == '\n' -> out.append("\\n")
            c == '\r' -> out.append("\\r")
            c == '\t' -> out.append("\\t")
            c == '\b' -> out.append("\\b")
            c == '\u000c' -> out.append("\\f")
            c.code < 0x20 || c.code > 0x7e -> out.append("\\u").append(c.code.toString(16).padStart(4, '0'))
            else -> out.append(c)
        }
    }
    out.append('"')
}
