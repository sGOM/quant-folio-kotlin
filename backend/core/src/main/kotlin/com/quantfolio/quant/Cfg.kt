package com.quantfolio.quant

/**
 * 전략 config(dict) 읽기 — 원본 Python 의 `cfg.get(k, d)` 와 `cfg.get(k) or d` 를 구분해 그대로 옮긴다.
 * `or` 는 0·0.0·""·False·None 을 모두 기본값으로 바꾼다("0 으로 끄기"가 되살아나는 원본 동작 보존).
 */
object Cfg {
    fun truthy(v: Any?): Boolean = when (v) {
        null -> false
        is Boolean -> v
        is Number -> v.toDouble() != 0.0
        is String -> v.isNotEmpty()
        is Collection<*> -> v.isNotEmpty()
        is Map<*, *> -> v.isNotEmpty()
        else -> true
    }

    /** `cfg.get(k, d)` — 키가 없을 때만 기본값. */
    fun get(m: Map<String, Any?>?, k: String, d: Any?): Any? = if (m != null && m.containsKey(k)) m[k] else d

    /** `cfg.get(k) or d`. */
    fun or(m: Map<String, Any?>?, k: String, d: Any?): Any? = m?.get(k).takeIf { truthy(it) } ?: d

    fun num(v: Any?): Double = (v as Number).toDouble()
    fun int(v: Any?): Int = (v as Number).toInt()

    @Suppress("UNCHECKED_CAST")
    fun map(m: Map<String, Any?>?, k: String): Map<String, Any?> = (m?.get(k) as? Map<String, Any?>) ?: emptyMap()
}
