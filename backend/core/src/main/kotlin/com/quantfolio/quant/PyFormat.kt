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
