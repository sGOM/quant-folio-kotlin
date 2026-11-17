package com.quantfolio.quant

/** 범용 기술지표(원본 `backtest/signals.py` 의 지표 함수). 전략 신호(`backtest.Signals`)와 지표 화면이 함께 쓴다. */
object Indicators {
    fun sma(close: Series, n: Int): Series = close.rolling(n, n).mean()

    fun ema(close: Series, n: Int): Series = close.ewm(span = n.toDouble(), adjust = false, minPeriods = n).mean()

    fun rsi(close: Series, period: Int): Series {
        val delta = close.diff()
        val gain = delta.clip(lo = 0.0)
        val loss = -delta.clip(hi = 0.0)
        val avgGain = gain.ewm(alpha = 1.0 / period, adjust = false, minPeriods = period).mean()
        val avgLoss = loss.ewm(alpha = 1.0 / period, adjust = false, minPeriods = period).mean()
        val rs = avgGain / avgLoss
        return Series(
            close.index,
            DoubleArray(close.size) { i ->
                val g = avgGain[i]
                val l = avgLoss[i]
                when {
                    g == 0.0 && l == 0.0 -> 50.0 // 무변동
                    l != 0.0 -> 100 - 100 / (1 + rs[i]) // NaN 이면 NaN
                    else -> 100.0 // 손실 0 (NaN != 0 은 참이라 위 분기)
                }
            },
        )
    }

    fun macd(close: Series, fast: Int, slow: Int, signal: Int): Pair<Series, Series> {
        val line = ema(close, fast) - ema(close, slow)
        return line to line.ewm(span = signal.toDouble(), adjust = false, minPeriods = signal).mean()
    }

    fun atr(high: Series, low: Series, close: Series, n: Int): Series {
        val prev = close.shift(1)
        val tr = rowMax(high - low, (high - prev).abs(), (low - prev).abs())
        return tr.ewm(alpha = 1.0 / n, adjust = false, minPeriods = n).mean()
    }

    /** a 가 b 를 위로 교차(오늘 a>b, 어제 a<=b). NaN 비교는 거짓. */
    fun crossUp(a: Series, b: Series) = (a gt b) and (a.shift(1) le b.shift(1))

    fun crossDown(a: Series, b: Series) = (a lt b) and (a.shift(1) ge b.shift(1))
}
