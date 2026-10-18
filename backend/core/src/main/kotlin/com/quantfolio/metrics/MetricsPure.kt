package com.quantfolio.metrics

import com.fasterxml.jackson.annotation.JsonProperty
import com.quantfolio.backtest.Signals
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.store.IndexBar
import com.quantfolio.quant.Num
import com.quantfolio.quant.Series
import java.math.BigDecimal
import java.math.RoundingMode

// 지표 화면의 순수 계산부(원본 metrics/stocks·sectors·panic·vulnerability). 조회는 각 서비스가 하고 여기는 계산만.

/** 종목 기술지표(원본 `_compute_tech_indicators`). 값이 없으면 null, valid_bdays 는 받은 봉 수. */
data class TechIndicators(
    @JsonProperty("high_52w_ratio") val high52wRatio: Double? = null,
    @JsonProperty("rsi14") val rsi14: Double? = null,
    val volAnn: Double? = null,
    @JsonProperty("mdd_252") val mdd252: Double? = null,
    val trendAligned: Boolean? = null,
    val aboveSma200: Boolean? = null,
    val validBdays: Int = 0,
) {
    companion object {
        fun of(close: Series): TechIndicators {
            val n = close.size
            if (n < 20) return TechIndicators(validBdays = n)
            val last = close.last()
            var high52: Double? = null
            if (n >= 252) {
                val hi = close.values.takeLast(252).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
                if (hi > 0) high52 = Num.safe(last / hi)
            }
            val rsi = if (n >= 28) Num.safe(Signals.rsi(close, 14).last()) else null
            val vol = MetricsCommon.computeVolAnn(tail(close, 253))
            val mdd = MetricsCommon.computeMdd(tail(close, 252))
            var aligned: Boolean? = null
            if (n >= 120) {
                val s = listOf(5, 20, 60, 120).map { Signals.sma(close, it).last() }
                if (s.all { it.isFinite() }) aligned = s[0] > s[1] && s[1] > s[2] && s[2] > s[3]
            }
            var above: Boolean? = null
            if (n >= 200) {
                val sma200 = Signals.sma(close, 200).last()
                if (sma200.isFinite()) above = last > sma200
            }
            return TechIndicators(high52, rsi, vol, mdd, aligned, above, n)
        }
    }
}

internal fun tail(s: Series, n: Int): Series {
    val from = maxOf(0, s.size - n)
    return Series(s.index.subList(from, s.size), s.values.copyOfRange(from, s.size))
}

/** 업종지수 지표 한 건(원본 `SectorMetric`). */
data class SectorMetric(
    val name: String,
    val market: String,
    @JsonProperty("mom_1m") val mom1m: Double? = null,
    @JsonProperty("mom_3m") val mom3m: Double? = null,
    @JsonProperty("mom_6m") val mom6m: Double? = null,
    @JsonProperty("rs_126") val rs126: Double? = null,
    val volAnn: Double? = null,
    val riskAdjMom: Double? = null,
    val valueTrend: Double? = null,
    @JsonProperty("high_52w_ratio") val high52wRatio: Double? = null,
    @JsonProperty("mdd_252") val mdd252: Double? = null,
) {
    companion object {
        /** 원본 `_compute_one_sector` 의 계산부. 봉이 21개 미만이면 null. */
        fun of(name: String, market: String, bars: List<IndexBar>, refReturn: Double?): SectorMetric? {
            if (bars.size < 21) return null
            val idx = bars.map { it.date }
            val c = Series(idx, DoubleArray(bars.size) { bars[it].close ?: Double.NaN })
            fun mom(n: Int) = if (c.size < n) null else Num.safe(c.last() / c[c.size - n] - 1)
            val mom6 = mom(126)
            val rs = if (mom6 != null && refReturn != null && kotlin.math.abs(refReturn) > 1e-9) Num.safe(mom6 / refReturn) else null
            val vol = MetricsCommon.computeVolAnn(tail(c, 253))
            val riskAdj = if (mom6 != null && vol != null && vol != 0.0 && vol > 1e-9) Num.safe(mom6 / vol) else null
            var valueTrend: Double? = null
            if (bars.size >= 60) {
                val tv = Series(idx, DoubleArray(bars.size) { (bars[it].tradingValue ?: Long.MIN_VALUE).let { v -> if (v == Long.MIN_VALUE) Double.NaN else v.toDouble() } })
                val ma20 = tv.rolling(20).mean().last()
                val ma60 = tv.rolling(60).mean().last()
                if (ma60 > 0) valueTrend = Num.safe(ma20 / ma60)
            }
            var high52: Double? = null
            if (c.size >= 252) {
                val hi = c.values.takeLast(252).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
                if (hi > 0) high52 = Num.safe(c.last() / hi)
            }
            val mdd = if (c.size >= 2) MetricsCommon.computeMdd(tail(c, 252)) else null
            return SectorMetric(name, market, mom(21), mom(63), mom6, rs, vol, riskAdj, valueTrend, high52, mdd)
        }
    }
}

/** 패닉 지표의 계산부(원본 `metrics/panic.py`). 동시지표다 — 사전 경보로 쓰지 말 것(원본 caveats). */
object PanicScoring {
    data class Thresholds(val r1: Pair<Double, Double>, val r5: Pair<Double, Double>, val z: Pair<Double, Double>, val hardR1: Double)

    val TH = mapOf(
        "KOSPI" to Thresholds(-0.025 to -0.045, -0.06 to -0.11, -2.5 to -3.5, -0.06),
        "KOSDAQ" to Thresholds(-0.032 to -0.06, -0.08 to -0.14, -2.5 to -3.5, -0.075),
    )
    val TH_COMMON = mapOf("vr" to (1.5 to 2.0), "bdr" to (0.72 to 0.85), "cr5" to (0.15 to 0.3), "cr10" to (0.05 to 0.1), "clv" to (0.4 to 0.15), "nlr" to (0.1 to 0.25))
    val W = mapOf("s1" to 20.0, "s2" to 15.0, "s4" to 20.0, "s5" to 10.0, "s6" to 10.0, "s9" to 10.0, "s7" to 10.0, "s8" to 5.0)
    const val SCORE_CAUTION = 35.0
    const val SCORE_WARNING = 60.0
    private const val GATE_SUB = 60.0

    /** 지수 신호. 봉이 6개 미만이면 빈 맵. */
    fun indexSignals(bars: List<IndexBar>): MutableMap<String, Double?> {
        if (bars.size < 6) return mutableMapOf()
        val idx = bars.map { it.date }
        val c = Series(idx, DoubleArray(bars.size) { bars[it].close ?: Double.NaN })
        val n = c.size
        val r1 = Num.safe(c[n - 1] / c[n - 2] - 1)
        val r5 = Num.safe(c[n - 1] / c[n - 6] - 1)
        val hi = c.values.takeLast(60).filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
        val dd60 = if (hi > 0) Num.safe(c[n - 1] / hi - 1) else null
        var vr: Double? = null
        if (n >= 22) {
            val tv = DoubleArray(n) { bars[it].tradingValue?.toDouble() ?: Double.NaN }
            val base = tv.copyOfRange(n - 21, n - 1).filter { !it.isNaN() }.let { if (it.isEmpty()) Double.NaN else it.average() }
            if (base > 0) vr = Num.safe(tv[n - 1] / base)
        }
        var z: Double? = null
        if (n >= 22) {
            val ret = c.pctChange()
            val std20 = Series(idx.subList(n - 21, n - 1), ret.values.copyOfRange(n - 21, n - 1)).std()
            if (std20 > 1e-9 && r1 != null) z = Num.safe(r1 / std20)
        }
        val hiD = bars.last().high ?: Double.NaN
        val loD = bars.last().low ?: Double.NaN
        val rng = hiD - loD
        val clv = if (rng > 0) Num.safe((c[n - 1] - loD) / rng) else null
        val close = c[n - 1]
        return mutableMapOf("r1" to r1, "r5" to r5, "dd60" to dd60, "vr" to vr, "z" to z, "clv" to clv, "close" to close, "low" to loD)
    }

    /** 하락·급락(−5%·−10%) 종목 비율. 거래량 0(거래정지)은 제외. changes 는 퍼센트 단위. */
    fun breadthSignals(rows: List<Pair<Double?, Double?>>?): Map<String, Double?> {
        if (rows.isNullOrEmpty()) return mapOf("bdr" to null, "cr5" to null, "cr10" to null, "universe" to 0.0)
        val hasVolume = rows.any { it.second != null }
        val valid = rows.filter { (chg, vol) -> chg != null && !chg.isNaN() && (!hasVolume || (vol != null && vol > 0)) }.map { it.first!! }
        val u = valid.size
        if (u == 0) return mapOf("bdr" to null, "cr5" to null, "cr10" to null, "universe" to 0.0)
        return mapOf(
            "bdr" to Num.safe(valid.count { it < 0 }.toDouble() / u),
            "cr5" to Num.safe(valid.count { it <= -5.0 }.toDouble() / u),
            "cr10" to Num.safe(valid.count { it <= -10.0 }.toDouble() / u),
            "universe" to u.toDouble(),
        )
    }

    data class Scored(
        val score: Double, val level: String, val gated: Boolean, val hardTrigger: Boolean,
        val priceSub: Double?, val breadthSub: Double?, val sub: Map<String, Double?>,
    )

    fun scoreAndLabel(market: String, sig: Map<String, Double?>): Scored {
        val th = TH.getValue(market)
        fun ramp(v: Double?, t: Pair<Double, Double>) = MetricsCommon.ramp(v, t.first, t.second)
        val s1 = ramp(sig["r1"], th.r1)
        val s2 = ramp(sig["r5"], th.r5)
        val s4 = ramp(sig["vr"], TH_COMMON.getValue("vr"))
        val s5 = ramp(sig["bdr"], TH_COMMON.getValue("bdr"))
        val s6cr5 = ramp(sig["cr5"], TH_COMMON.getValue("cr5"))
        val s6cr10 = ramp(sig["cr10"], TH_COMMON.getValue("cr10"))
        val s6 = when { s6cr5 == null -> s6cr10; s6cr10 == null -> s6cr5; else -> maxOf(s6cr5, s6cr10) }
        val s7 = ramp(sig["z"], th.z)
        val s8 = ramp(sig["clv"], TH_COMMON.getValue("clv"))
        val s9 = ramp(sig["nlr"], TH_COMMON.getValue("nlr"))
        val weighted = listOf(W["s1"]!! to s1, W["s2"]!! to s2, W["s4"]!! to s4, W["s5"]!! to s5, W["s6"]!! to s6, W["s9"]!! to s9, W["s7"]!! to s7, W["s8"]!! to s8)
        val availW = weighted.sumOf { (w, s) -> if (s != null) w else 0.0 }
        val raw = if (availW > 0) weighted.sumOf { (w, s) -> if (s != null) w * s else 0.0 } / availW else 0.0
        val dd60 = sig["dd60"]
        val mult = when { dd60 == null -> 1.0; dd60 <= -0.1 -> 1.1; dd60 > -0.03 -> 0.9; else -> 1.0 }
        var score = minOf(100.0, maxOf(0.0, raw * mult))
        val priceSub = MetricsCommon.wavg(listOf(W["s1"]!! to s1, W["s2"]!! to s2))
        val breadthSub = MetricsCommon.wavg(listOf(W["s5"]!! to s5, W["s6"]!! to s6, W["s9"]!! to s9))
        val vr = sig["vr"]
        val gated = priceSub != null && priceSub >= GATE_SUB &&
            ((vr != null && vr >= TH_COMMON.getValue("vr").first) || (breadthSub != null && breadthSub >= GATE_SUB))
        val r1 = sig["r1"]
        val cr10 = sig["cr10"]
        val bdr = sig["bdr"]
        val hard = (r1 != null && r1 <= th.hardR1) || (cr10 != null && cr10 >= 0.1) || (bdr != null && vr != null && bdr >= 0.92 && vr >= 2.0)
        if (hard) score = maxOf(score, SCORE_WARNING)
        val level = when {
            hard || (score >= SCORE_WARNING && gated) -> "panic"
            score >= SCORE_WARNING -> "warning"
            score >= SCORE_CAUTION -> "caution"
            else -> "normal"
        }
        val sub = mapOf("s1" to s1, "s2" to s2, "s4" to s4, "s5" to s5, "s6" to s6, "s6_cr5" to s6cr5, "s6_cr10" to s6cr10, "s7" to s7, "s8" to s8, "s9" to s9)
        return Scored(score, level, gated, hard, priceSub, breadthSub, sub)
    }
}

/** 시장 취약성 점수(원본 `metrics/vulnerability.score_vulnerability`) → 노출 축소 비율(0.6~1.0). */
object VulnerabilityScoring {
    private val TH_CREDIT_Z = 1.0 to 2.0
    private val TH_CREDIT_GROWTH = 0.1 to 0.2
    private val TH_FORCED_SALE = 5.0 to 8.0
    private const val MIN_EXPOSURE = 0.6

    data class Result(val score: Double?, val exposureScale: Double, val signals: Map<String, Double?>, val subscores: Map<String, Double?>)

    fun score(creditTotals: List<Double?>, forcedSaleRatios: List<Double?>): Result {
        val z = zscore(creditTotals, 250)
        val growth = growth(creditTotals, 60)
        val forcedTail = forcedSaleRatios.takeLast(20).filterNotNull()
        val forced = if (forcedTail.isEmpty()) null else forcedTail.average()
        val subZ = MetricsCommon.ramp(z, TH_CREDIT_Z.first, TH_CREDIT_Z.second)
        val subG = MetricsCommon.ramp(growth, TH_CREDIT_GROWTH.first, TH_CREDIT_GROWTH.second)
        val subF = MetricsCommon.ramp(forced, TH_FORCED_SALE.first, TH_FORCED_SALE.second)
        val score = MetricsCommon.wavg(listOf(0.4 to subZ, 0.35 to subG, 0.25 to subF))
        val exposure = if (score == null) 1.0 else 1.0 - (1.0 - MIN_EXPOSURE) * (score / 100.0)
        return Result(
            score, BigDecimal(exposure).setScale(4, RoundingMode.HALF_EVEN).toDouble(),
            mapOf("credit_z" to z, "credit_growth" to growth, "forced_sale" to forced),
            mapOf("credit_z" to subZ, "credit_growth" to subG, "forced_sale" to subF),
        )
    }

    private fun zscore(series: List<Double?>, window: Int): Double? {
        val tail = series.takeLast(window).filterNotNull()
        if (tail.size < 120) return null
        val mean = tail.average()
        val sd = kotlin.math.sqrt(tail.sumOf { (it - mean) * (it - mean) } / (tail.size - 1))
        if (sd <= 0 || sd.isNaN()) return null
        return (tail.last() - mean) / sd
    }

    private fun growth(series: List<Double?>, window: Int): Double? {
        val vals = series.filterNotNull()
        if (vals.size <= window) return null
        val base = vals[vals.size - 1 - window]
        if (base == 0.0) return null
        return vals.last() / base - 1.0
    }
}
