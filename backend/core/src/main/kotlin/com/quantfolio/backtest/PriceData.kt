package com.quantfolio.backtest

import com.quantfolio.quant.Series

/**
 * 시그널 입력. 원본은 종가 Series 또는 OHLCV DataFrame 을 받는다 — [frame] 이 그 구분이다.
 * OHLC 가 필요한 전략에 종가만 주면 원본처럼 거부한다.
 */
class PriceData private constructor(
    val close: Series,
    val open: Series?,
    val high: Series?,
    val low: Series?,
    val volume: Series?,
    val frame: Boolean,
) {
    fun column(name: String): Series? = when (name) {
        "close" -> close
        "open" -> open
        "high" -> high
        "low" -> low
        "volume" -> volume
        else -> null
    }

    /** 원본 `_require_ohlc`. */
    fun require(vararg cols: String) {
        if (!frame) throw SignalConfigError("이 전략은 OHLC DataFrame 입력이 필요합니다(종가 단일 시계열 불가).")
        val missing = cols.filter { column(it) == null }
        if (missing.isNotEmpty()) throw SignalConfigError("OHLC 컬럼 누락: ${missing.joinToString(", ", "[", "]") { "'$it'" }}")
    }

    companion object {
        fun close(close: Series) = PriceData(close, null, null, null, null, frame = false)

        fun ohlcv(open: Series?, high: Series?, low: Series?, close: Series, volume: Series?) =
            PriceData(close, open, high, low, volume, frame = true)
    }
}

/** 전략 설정·입력이 시그널 계산에 맞지 않음(원본 ValueError). */
class SignalConfigError(message: String) : IllegalArgumentException(message)
