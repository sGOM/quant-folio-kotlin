package com.quantfolio.quant

import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.sqrt

enum class RankMethod { AVERAGE, MIN, MAX, FIRST, DENSE }

/**
 * 날짜 인덱스 실수 시계열 — pandas Series 의 필요한 부분을 **같은 의미로** 재현한다(정답지: tools/oracle/gen_series.py).
 * 결측은 NaN. 불변이며 모든 연산은 새 Series 를 돌려준다.
 *
 * pandas 의미 요약: 집계는 NaN 을 건너뛰고(sum 전부 NaN → 0, mean → NaN), rolling 은 창 안 NaN 을 개수에서 빼고
 * min_periods 로 판정, std 는 ddof=1, ewm 은 adjust=True·ignore_na=False, pctChange 는 기본으로 NaN 을 앞값으로 채운 뒤 계산.
 */
class Series(val index: List<LocalDate>, val values: DoubleArray) {
    init {
        require(index.size == values.size) { "index(${index.size}) 와 values(${values.size}) 길이가 다르다" }
    }

    val size: Int get() = values.size

    operator fun get(i: Int): Double = values[i]

    fun isNaN(i: Int): Boolean = values[i].isNaN()

    fun last(): Double = values.last()

    private fun with(newValues: DoubleArray) = Series(index, newValues)

    private fun zip(other: Series, f: (Double, Double) -> Double): Series {
        require(other.index == index) { "인덱스가 다른 Series 간 연산" }
        return with(DoubleArray(size) { f(values[it], other.values[it]) })
    }

    operator fun plus(o: Series) = zip(o) { a, b -> a + b }
    operator fun minus(o: Series) = zip(o) { a, b -> a - b }
    operator fun times(o: Series) = zip(o) { a, b -> a * b }
    operator fun div(o: Series) = zip(o) { a, b -> a / b }
    operator fun plus(v: Double) = map { it + v }
    operator fun minus(v: Double) = map { it - v }
    operator fun times(v: Double) = map { it * v }
    operator fun div(v: Double) = map { it / v }
    operator fun unaryMinus() = map { -it }

    fun map(f: (Double) -> Double): Series = with(DoubleArray(size) { f(values[it]) })

    infix fun gt(v: Double) = BoolSeries(index, BooleanArray(size) { values[it] > v })
    infix fun lt(v: Double) = BoolSeries(index, BooleanArray(size) { values[it] < v })
    infix fun ge(v: Double) = BoolSeries(index, BooleanArray(size) { values[it] >= v })
    infix fun le(v: Double) = BoolSeries(index, BooleanArray(size) { values[it] <= v })
    infix fun gt(o: Series) = BoolSeries(index, BooleanArray(size) { values[it] > o.values[it] })
    infix fun lt(o: Series) = BoolSeries(index, BooleanArray(size) { values[it] < o.values[it] })
    infix fun ge(o: Series) = BoolSeries(index, BooleanArray(size) { values[it] >= o.values[it] })
    infix fun le(o: Series) = BoolSeries(index, BooleanArray(size) { values[it] <= o.values[it] })

    /** n>0 이면 뒤로 밀고(앞이 NaN), n<0 이면 앞으로 당긴다. */
    fun shift(n: Int): Series = with(DoubleArray(size) { i -> val j = i - n; if (j in 0 until size) values[j] else Double.NaN })

    fun diff(n: Int = 1): Series = this - shift(n)

    /** pandas 2.2.3 기본(fill_method='pad'): NaN 을 앞값으로 채운 뒤 data / data.shift(n) − 1. */
    fun pctChange(n: Int = 1, fillMethodPad: Boolean = true): Series {
        val data = if (fillMethodPad) ffill() else this
        return data / data.shift(n) - 1.0
    }

    fun ffill(limit: Int? = null): Series {
        val out = values.copyOf()
        var last = Double.NaN
        var run = 0
        for (i in out.indices) {
            if (!out[i].isNaN()) {
                last = out[i]
                run = 0
            } else if (!last.isNaN() && (limit == null || run < limit)) {
                out[i] = last
                run++
            } else {
                run++
            }
        }
        return with(out)
    }

    fun fillNa(v: Double): Series = map { if (it.isNaN()) v else it }

    fun clip(lo: Double? = null, hi: Double? = null): Series = map { x ->
        if (x.isNaN()) x else x.coerceAtLeast(lo ?: Double.NEGATIVE_INFINITY).coerceAtMost(hi ?: Double.POSITIVE_INFINITY)
    }

    /** NaN 위치는 NaN 으로 두고 합은 이어간다(pandas skipna). */
    fun cumSum(): Series {
        var acc = 0.0
        return with(DoubleArray(size) { i -> if (values[i].isNaN()) Double.NaN else { acc += values[i]; acc } })
    }

    fun cumProd(): Series {
        var acc = 1.0
        return with(DoubleArray(size) { i -> if (values[i].isNaN()) Double.NaN else { acc *= values[i]; acc } })
    }

    /** expanding().max() — 첫 유효값 이후로는 NaN 위치도 그때까지의 최대. */
    fun expandingMax(): Series {
        var best = Double.NaN
        return with(DoubleArray(size) { i -> if (!values[i].isNaN() && (best.isNaN() || values[i] > best)) best = values[i]; best })
    }

    fun rolling(window: Int, minPeriods: Int = window) = Rolling(this, window, minPeriods)

    /** span 또는 alpha 중 하나. span → alpha = 2/(span+1). */
    fun ewm(span: Double? = null, alpha: Double? = null, adjust: Boolean = true): Ewm {
        val a = alpha ?: span?.let { 2.0 / (it + 1.0) } ?: error("span 또는 alpha 가 필요하다")
        return Ewm(this, a, adjust)
    }

    /** pandas rank(na_option='keep'). pct 는 rank / 유효 개수(dense 는 최대 dense rank). */
    fun rank(method: RankMethod = RankMethod.AVERAGE, pct: Boolean = false): Series {
        val valid = values.indices.filter { !values[it].isNaN() }.sortedWith(compareBy({ values[it] }, { it }))
        val out = DoubleArray(size) { Double.NaN }
        var i = 0
        var dense = 0
        while (i < valid.size) {
            var j = i
            while (j + 1 < valid.size && values[valid[j + 1]] == values[valid[i]]) j++
            dense++
            for (k in i..j) {
                out[valid[k]] = when (method) {
                    RankMethod.AVERAGE -> (i + 1 + j + 1) / 2.0
                    RankMethod.MIN -> (i + 1).toDouble()
                    RankMethod.MAX -> (j + 1).toDouble()
                    RankMethod.FIRST -> (k + 1).toDouble()
                    RankMethod.DENSE -> dense.toDouble()
                }
            }
            i = j + 1
        }
        if (pct) {
            val denom = if (method == RankMethod.DENSE) dense.toDouble() else valid.size.toDouble()
            for (k in out.indices) out[k] = out[k] / denom
        }
        return with(out)
    }

    /** 선형 보간 분위수(pandas 기본). 유효값이 없으면 NaN. */
    fun quantile(q: Double): Double {
        val v = values.filter { !it.isNaN() }.sorted()
        if (v.isEmpty()) return Double.NaN
        val pos = q * (v.size - 1)
        val lo = floor(pos).toInt()
        val hi = minOf(lo + 1, v.size - 1)
        return v[lo] + (v[hi] - v[lo]) * (pos - lo)
    }

    fun sum(): Double = values.filter { !it.isNaN() }.sum()

    fun mean(): Double = values.filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.sum() / it.size }

    fun std(ddof: Int = 1): Double {
        val v = values.filter { !it.isNaN() }
        if (v.size <= ddof) return Double.NaN
        val m = v.sum() / v.size
        return sqrt(v.sumOf { (it - m) * (it - m) } / (v.size - ddof))
    }

    fun dropNa(): Series {
        val keep = values.indices.filter { !values[it].isNaN() }
        return Series(keep.map { index[it] }, DoubleArray(keep.size) { values[keep[it]] })
    }

    /** [from, to] 포함 구간. */
    fun slice(from: LocalDate? = null, to: LocalDate? = null): Series {
        val keep = index.indices.filter { (from == null || !index[it].isBefore(from)) && (to == null || !index[it].isAfter(to)) }
        return Series(keep.map { index[it] }, DoubleArray(keep.size) { values[keep[it]] })
    }

    override fun toString() = "Series(n=$size, last=${values.lastOrNull()})"
}

class BoolSeries(val index: List<LocalDate>, val values: BooleanArray) {
    infix fun and(o: BoolSeries) = BoolSeries(index, BooleanArray(values.size) { values[it] && o.values[it] })
    infix fun or(o: BoolSeries) = BoolSeries(index, BooleanArray(values.size) { values[it] || o.values[it] })
    fun not() = BoolSeries(index, BooleanArray(values.size) { !values[it] })
    fun shift(n: Int, fill: Boolean = false) =
        BoolSeries(index, BooleanArray(values.size) { i -> val j = i - n; if (j in values.indices) values[j] else fill })
}
