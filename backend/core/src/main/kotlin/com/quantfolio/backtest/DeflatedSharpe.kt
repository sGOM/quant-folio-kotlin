package com.quantfolio.backtest

import com.quantfolio.quant.Stats
import org.apache.commons.math3.distribution.NormalDistribution
import kotlin.math.E
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Deflated Sharpe Ratio(원본 `backtest/deflated_sharpe.py`, Bailey & López de Prado 2014) — 같은 전략을 파라미터만 바꿔 여러 번
 * 돌렸을 때 우연히 부풀려진 샤프비를 걸러내는 사후 분석. 저장된 백테스트 결과만 읽는 순수 계산이다.
 *
 * ⚠ 단위 함정: 저장된 sharpe 는 연율화(√252) 값이다. DSR 은 반드시 일별 샤프비(÷√252)로 계산한다 —
 * 빠뜨리면 √(n−1) 항 때문에 DSR 이 거의 항상 1 이 된다.
 * N_eff = N / (1 + (N−1)·ρ̄), ρ̄ 는 [0, 0.9999] 로 클램프(음수는 N_eff>N 을 유발), 내림.
 */
object DeflatedSharpe {
    private const val EULER = 0.5772156649015329
    private const val MIN_OBS = 30
    private val NORMAL = NormalDistribution(0.0, 1.0)

    fun grade(dsr: Double?): String = when {
        dsr == null -> "insufficient_trials"
        dsr >= 0.95 -> "strong"
        dsr >= 0.9 -> "marginal"
        dsr >= 0.5 -> "inconclusive"
        else -> "overfit_suspected"
    }

    /** 자산곡선 v 배열 → 단순 일간수익률. */
    fun returnsFromCurve(curve: List<Map<String, Any?>>?): DoubleArray {
        val v = curve.orEmpty().mapNotNull { (it["v"] as Number?)?.toDouble() }
        if (v.size < 2) return DoubleArray(0)
        return DoubleArray(v.size - 1) { (v[it + 1] - v[it]) / v[it] }
    }

    @Suppress("UNCHECKED_CAST")
    fun analyze(target: Map<String, Any?>, homogeneous: List<Map<String, Any?>>, ann: Int = 252): Map<String, Any?> {
        val targetReturns = returnsFromCurve(target["equity_curve"] as List<Map<String, Any?>>?)
        val sharpeAnnual = (target["sharpe"] as Number?)?.toDouble()
            ?: return linkedMapOf("status" to "unavailable", "reason" to "대상 백테스트에 샤프비가 없습니다.", "grade" to "insufficient_trials")
        if (targetReturns.size < MIN_OBS || !targetReturns.all { it.isFinite() }) {
            return linkedMapOf(
                "status" to "unavailable",
                "reason" to "표본 부족 또는 비유한 수익률(관측치 ${targetReturns.size}, 최소 $MIN_OBS).",
                "grade" to "insufficient_trials",
                "n" to targetReturns.size,
            )
        }
        val sharpes = homogeneous.mapNotNull { (it["sharpe"] as Number?)?.toDouble() }
        val returnsList = homogeneous.map { returnsFromCurve(it["equity_curve"] as List<Map<String, Any?>>?) }
        val stats = trialStats(sharpes, returnsList, ann)
        val core = compute(targetReturns, sharpeAnnual, stats.nEff, stats.vDaily, ann)
        val dsr = core.dsr
        val resp = linkedMapOf<String, Any?>(
            "status" to if (dsr != null) "ok" else "insufficient_trials",
            "grade" to grade(dsr),
            "dsr" to dsr, "psr0" to core.psr0, "sharpe_annual" to sharpeAnnual, "sharpe_daily" to core.srDaily,
            "sr0_daily" to core.sr0Daily, "skew" to core.skew, "kurt" to core.kurt, "n_obs" to core.n,
            "N" to stats.n, "N_eff" to stats.nEff, "rho_bar" to stats.rhoBar, "V_daily" to stats.vDaily,
            "v_low_confidence" to (stats.n < 5), "ann" to ann,
        )
        if (dsr == null) resp["reason"] = "유효 시행 수 N_eff=${stats.nEff}<2 (동질 백테스트 이력 부족) — DSR 미정의. PSR0(다중검정 미보정 유의성)만 제공합니다."
        return resp
    }

    data class Core(val dsr: Double?, val psr0: Double, val srDaily: Double, val sr0Daily: Double?, val skew: Double, val kurt: Double, val n: Int)

    fun compute(r: DoubleArray, sharpeAnnual: Double, nTrials: Int, vDaily: Double, ann: Int = 252): Core {
        val n = r.size
        require(n >= MIN_OBS) { "표본 부족: 관측치 $n < 최소 $MIN_OBS" }
        require(r.all { it.isFinite() }) { "수익률에 비유한 값(NaN/inf)이 있어 계산할 수 없습니다." }
        val sr = sharpeAnnual / sqrt(ann.toDouble())
        val g3 = skewUnbiased(r)
        val g4 = kurtosisUnbiased(r)
        val seSq = 1.0 - g3 * sr + (g4 - 1.0) / 4.0 * sr * sr
        require(seSq > 0) { "PSR 표준오차 제곱이 비양수 — 극단적 왜도/첨도로 계산 불가." }
        val se = sqrt(seSq)
        val rootNm1 = sqrt(n - 1.0)
        val psr0 = NORMAL.cumulativeProbability(sr * rootNm1 / se)
        if (nTrials < 2) return Core(null, psr0, sr, null, g3, g4, n)
        val nd = nTrials.toDouble()
        val sr0 = sqrt(maxOf(vDaily, 0.0)) *
            ((1.0 - EULER) * NORMAL.inverseCumulativeProbability(1.0 - 1.0 / nd) + EULER * NORMAL.inverseCumulativeProbability(1.0 - 1.0 / (nd * E)))
        return Core(NORMAL.cumulativeProbability((sr - sr0) * rootNm1 / se), psr0, sr, sr0, g3, g4, n)
    }

    data class TrialStats(val n: Int, val vDaily: Double, val rhoBar: Double, val nEff: Int)

    fun trialStats(sharpesAnnual: List<Double>, returnsList: List<DoubleArray>, ann: Int = 252): TrialStats {
        val n = sharpesAnnual.size
        val daily = sharpesAnnual.map { it / sqrt(ann.toDouble()) }
        val v = if (daily.size >= 2) sampleVar(daily) else 0.0
        val rho = meanPairwiseCorr(returnsList)
        val rhoC = rho.coerceIn(0.0, 0.9999)
        val nEff = if (n >= 2) minOf(floor(n / (1.0 + (n - 1) * rhoC)).toInt(), n) else n
        return TrialStats(n, v, rho, nEff)
    }

    /** 시행 간 일별수익률의 평균 쌍상관(짧은 쪽 길이로 자름, 비유한·표준편차 0 쌍 제외). */
    private fun meanPairwiseCorr(list: List<DoubleArray>): Double {
        val arrs = list.filter { it.size >= 2 }
        val corrs = mutableListOf<Double>()
        for (i in arrs.indices) for (j in i + 1 until arrs.size) {
            val m = minOf(arrs[i].size, arrs[j].size)
            val a = arrs[i].copyOf(m)
            val b = arrs[j].copyOf(m)
            if (!a.all { it.isFinite() } || !b.all { it.isFinite() }) continue
            if (popStd(a) == 0.0 || popStd(b) == 0.0) continue
            val c = Stats.pearson(a, b).coerceIn(-1.0, 1.0)
            if (c.isFinite()) corrs += c
        }
        return if (corrs.isEmpty()) 0.0 else corrs.average()
    }

    private fun popStd(a: DoubleArray): Double { val m = a.average(); return sqrt(a.sumOf { (it - m).pow(2) } / a.size) }

    private fun sampleVar(a: List<Double>): Double { val m = a.average(); return a.sumOf { (it - m).pow(2) } / (a.size - 1) }

    private fun moments(r: DoubleArray): Triple<Double, Double, Double> {
        val m = r.average()
        val m2 = r.sumOf { (it - m).pow(2) } / r.size
        val m3 = r.sumOf { (it - m).pow(3) } / r.size
        val m4 = r.sumOf { (it - m).pow(4) } / r.size
        return Triple(m2, m3, m4)
    }

    /** scipy skew(bias=False) = √(n(n−1))/(n−2) · m3/m2^1.5. */
    private fun skewUnbiased(r: DoubleArray): Double {
        val n = r.size.toDouble()
        val (m2, m3, _) = moments(r)
        return sqrt(n * (n - 1)) / (n - 2) * (m3 / m2.pow(1.5))
    }

    /** scipy kurtosis(fisher=False, bias=False) = ((n+1)·g2 + 6)·(n−1)/((n−2)(n−3)) + 3, g2 = m4/m2² − 3. */
    private fun kurtosisUnbiased(r: DoubleArray): Double {
        val n = r.size.toDouble()
        val (m2, _, m4) = moments(r)
        val g2 = m4 / (m2 * m2) - 3.0
        return ((n + 1) * g2 + 6.0) * (n - 1) / ((n - 2) * (n - 3)) + 3.0
    }
}
