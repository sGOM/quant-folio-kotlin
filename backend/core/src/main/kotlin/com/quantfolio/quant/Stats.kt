package com.quantfolio.quant

import kotlin.math.sqrt

/**
 * 기초 통계 — 여러 계산부가 같은 식을 각자 들고 있던 것을 모았다. NaN 은 거르지 않는다(호출자 책임, [Series] 는 걸러서 부른다).
 * 연산 순서(평균 = Σx/n, 편차제곱은 `d * d`, 앞에서부터 누적)가 정답지 일치의 일부라 바꾸지 말 것.
 */
object Stats {
    /** 표준편차 √(Σ(x−m)²/(n−ddof)). ddof=1 표본(pandas 기본), 0 모집단(numpy 기본). */
    fun std(xs: DoubleArray, ddof: Int): Double {
        val m = xs.average()
        return sqrt(xs.sumOf { (it - m) * (it - m) } / (xs.size - ddof))
    }

    fun std(xs: List<Double>, ddof: Int): Double = std(xs.toDoubleArray(), ddof)

    /** 피어슨 상관 Σdadb/√(Σda²·Σdb²). 분산 0 이면 NaN. */
    fun pearson(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average()
        val mb = b.average()
        var sab = 0.0
        var saa = 0.0
        var sbb = 0.0
        for (i in a.indices) {
            val da = a[i] - ma
            val db = b[i] - mb
            sab += da * db
            saa += da * da
            sbb += db * db
        }
        return sab / sqrt(saa * sbb)
    }

    /** 정렬된 값의 선형 보간 분위수(numpy/pandas `linear`). q ∈ [0, 1], 비어 있으면 안 된다. */
    fun quantileSorted(sorted: List<Double>, q: Double): Double {
        val pos = q * (sorted.size - 1)
        val lo = pos.toInt()
        val hi = minOf(lo + 1, sorted.size - 1)
        return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
    }
}
