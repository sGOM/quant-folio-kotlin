package com.quantfolio.quant

import kotlin.math.sqrt

/** pandas rolling(window, min_periods) — 창 안 NaN 은 개수에서 빼고, 유효 개수 < minPeriods 면 NaN. */
class Rolling(private val s: Series, private val window: Int, private val minPeriods: Int) {
    private inline fun reduce(crossinline f: (DoubleArray, Int) -> Double): Series {
        val v = s.values
        val buf = DoubleArray(window)
        return Series(
            s.index,
            DoubleArray(v.size) { i ->
                var n = 0
                for (j in maxOf(0, i - window + 1)..i) if (!v[j].isNaN()) buf[n++] = v[j]
                if (n < minPeriods || n == 0) Double.NaN else f(buf, n)
            },
        )
    }

    fun sum() = reduce { b, n -> var t = 0.0; for (k in 0 until n) t += b[k]; t }

    fun mean() = reduce { b, n -> var t = 0.0; for (k in 0 until n) t += b[k]; t / n }

    fun max() = reduce { b, n -> var m = b[0]; for (k in 1 until n) if (b[k] > m) m = b[k]; m }

    fun min() = reduce { b, n -> var m = b[0]; for (k in 1 until n) if (b[k] < m) m = b[k]; m }

    fun std(ddof: Int = 1) = reduce { b, n ->
        if (n <= ddof) {
            Double.NaN
        } else {
            var m = 0.0
            for (k in 0 until n) m += b[k]
            m /= n
            var ss = 0.0
            for (k in 0 until n) ss += (b[k] - m) * (b[k] - m)
            sqrt(maxOf(0.0, ss / (n - ddof)))
        }
    }
}

/**
 * pandas ewm(...).mean() — adjust·ignore_na=False·min_periods(최소 1) 의 cython `ewma` 를 그대로 옮겼다.
 * 결측 구간에서도 기존 가중치는 감쇠하고, 다음 관측에서 재정규화된다.
 */
class Ewm(private val s: Series, private val alpha: Double, private val adjust: Boolean, minPeriods: Int = 0) {
    private val minp = maxOf(minPeriods, 1)

    fun mean(): Series {
        val v = s.values
        val out = DoubleArray(v.size)
        if (v.isEmpty()) return Series(s.index, out)
        val oldWtFactor = 1.0 - alpha
        val newWt = if (adjust) 1.0 else alpha
        var weighted = v[0]
        var nobs = if (weighted.isNaN()) 0 else 1
        out[0] = if (nobs >= minp) weighted else Double.NaN
        var oldWt = 1.0
        for (i in 1 until v.size) {
            val cur = v[i]
            val isObs = !cur.isNaN()
            if (isObs) nobs++
            if (!weighted.isNaN()) {
                oldWt *= oldWtFactor
                if (isObs) {
                    if (weighted != cur) weighted = (oldWt * weighted + newWt * cur) / (oldWt + newWt)
                    oldWt = if (adjust) oldWt + newWt else 1.0
                }
            } else if (isObs) {
                weighted = cur
            }
            out[i] = if (nobs >= minp) weighted else Double.NaN
        }
        return Series(s.index, out)
    }
}
