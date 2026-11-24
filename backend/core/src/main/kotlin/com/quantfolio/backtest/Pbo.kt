package com.quantfolio.backtest

import java.util.TreeMap
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 과최적화 확률 PBO(CSCV, Bailey·Borwein·López de Prado·Zhu 2017). 원본에 없는 기능 — 정답지는 algorithm/screen_stats.py `pbo_cscv`.
 * 행(월)을 [s] 블록으로 나눠 C(s, s/2) 조합마다 표본 내 Sharpe 1위 시행의 표본 밖 상대순위 ω → λ = ln(ω/(1−ω)), PBO = P(λ ≤ 0).
 */
object Pbo {
    /** [m] 행 = 월, 열 = 시행. 시행 < 2 이거나 월 < [s] 면 null. */
    fun cscv(m: Array<DoubleArray>, s: Int = 8): Double? {
        val t = m.size
        val n = m.firstOrNull()?.size ?: 0
        if (n < 2 || t < s) return null
        // numpy.array_split: 앞의 t % s 블록이 한 행씩 더 갖는다.
        val blocks = (0 until s).map { b ->
            val start = b * (t / s) + minOf(b, t % s)
            start until start + t / s + (if (b < t % s) 1 else 0)
        }
        val lambdas = combinations(s, s / 2).map { combo ->
            val isRows = combo.flatMap { blocks[it] }
            val oosRows = (0 until s).filter { it !in combo }.flatMap { blocks[it] }
            val isSharpe = colSharpe(m, isRows)
            val best = isSharpe.indices.maxBy { isSharpe[it] } // 동점이면 첫 열(numpy argmax)
            val w = averageRank(colSharpe(m, oosRows), best) / (n + 1)
            ln(w / (1 - w))
        }
        return lambdas.count { it <= 0 }.toDouble() / lambdas.size
    }

    /** 같은 DSR 동질 시행들의 자산곡선 → 월 수익률 행렬(모든 시행에 있는 달만, 월 오름차순). */
    fun monthlyMatrix(results: List<Map<String, Any?>>): Array<DoubleArray> {
        val perTrial = results.map { monthly(it) }
        val common = perTrial.map { it.keys }.reduceOrNull { a, b -> a intersect b }.orEmpty().sorted()
        return Array(common.size) { r -> DoubleArray(perTrial.size) { c -> perTrial[c].getValue(common[r]) } }
    }

    private fun monthly(result: Map<String, Any?>): Map<String, Double> {
        val curve = (result["equity_curve"] as? List<*>).orEmpty().mapNotNull { p ->
            val pt = p as? Map<*, *> ?: return@mapNotNull null
            val t = pt["t"]?.toString()?.takeIf { it.length >= 7 } ?: return@mapNotNull null
            val v = (pt["v"] as? Number)?.toDouble() ?: return@mapNotNull null
            t to v
        }.sortedBy { it.first }
        if (curve.isEmpty()) return emptyMap()
        val monthEnd = TreeMap<String, Double>()
        for ((t, v) in curve) monthEnd[t.take(7)] = v
        var prev = curve.first().second
        val out = LinkedHashMap<String, Double>()
        for ((month, v) in monthEnd) {
            if (prev > 0) out[month] = v / prev - 1
            prev = v
        }
        return out
    }

    private fun colSharpe(m: Array<DoubleArray>, rows: List<Int>): DoubleArray = DoubleArray(m[0].size) { c ->
        val x = rows.map { m[it][c] }
        val mean = x.average()
        val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / (x.size - 1))
        if (sd > 0) mean / sd else Double.NEGATIVE_INFINITY
    }

    /** pandas rank(method="average") 의 [i] 번째 값(1부터). */
    private fun averageRank(v: DoubleArray, i: Int): Double {
        val below = v.count { it < v[i] }
        val equal = v.count { it == v[i] }
        return below + (equal + 1) / 2.0
    }

    private fun combinations(n: Int, k: Int): List<List<Int>> {
        val out = mutableListOf<List<Int>>()
        fun go(start: Int, acc: List<Int>) {
            if (acc.size == k) { out += acc; return }
            for (i in start until n) go(i + 1, acc + i)
        }
        go(0, emptyList())
        return out
    }
}
