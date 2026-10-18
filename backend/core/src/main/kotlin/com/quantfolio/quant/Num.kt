package com.quantfolio.quant

/** JSON 안전 숫자(원본 `services/_num.py`). NaN/±Inf/변환 불가 → null. */
object Num {
    fun safe(v: Double?): Double? = v?.takeIf { it.isFinite() }

    /** 원본 `safe_float(v)` — 숫자로 바꿀 수 없으면 null. **문자열은 숫자 문자열이 아닌 한 null** 이다. */
    fun safeAny(v: Any?): Double? = when (v) {
        null -> null
        is Number -> v.toDouble().takeIf { it.isFinite() }
        is Boolean -> if (v) 1.0 else 0.0
        is String -> v.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        else -> null
    }

    /** 원본 `is_nan` = `safe_float(v) is None`. 주의: 숫자가 아닌 문자열이면 무조건 true. */
    fun isNan(v: Any?): Boolean = safeAny(v) == null

    fun safeBool(v: Boolean?): Boolean? = v
}
