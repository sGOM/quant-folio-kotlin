package com.quantfolio.factor

import com.quantfolio.quant.Series
import com.quantfolio.quant.Stats
import java.time.LocalDate
import java.time.YearMonth

/**
 * 잔차 모멘텀(원본 `factors.compute_residual_momentum_panel`, Blitz 류): 월간 수익률을 시장(KOSPI200)에 회귀하고,
 * 최근 mom_window 개월(직전 skip 개월 제외) 잔차의 평균/표준편차. 시장 베타에 실린 모멘텀을 걷어낸다.
 * as_of 이후 데이터는 쓰지 않는다. 회귀 관측이 24개월 미만이면 제외.
 */
object ResidualMomentum {
    const val MIN_OBS = 24

    fun compute(
        closes: Map<String, Series>,
        market: Series,
        asOf: LocalDate,
        codes: List<String>? = null,
        regWindow: Int = 36,
        momWindow: Int = 11,
        skip: Int = 1,
        minObs: Int = MIN_OBS,
    ): Map<String, Double> {
        if (closes.isEmpty() || market.size == 0) return emptyMap()
        val mktRet = monthlyReturns(market, asOf)
        if (mktRet.isEmpty()) return emptyMap()
        val window = maxOf(regWindow, momWindow + skip)
        val mkt = mktRet.filterValues { !it.isNaN() }.entries.toList().takeLast(window)
        if (mkt.size < minObs) return emptyMap()
        val norm = closes.keys.associateBy { it.trim().padStart(6, '0') }
        val out = LinkedHashMap<String, Double>()
        for (code in codes ?: closes.keys.toList()) {
            val key = if (code in closes) code else norm[code.trim().padStart(6, '0')] ?: continue
            val stock = monthlyReturns(closes.getValue(key), asOf)
            val pairs = mkt.mapNotNull { (m, x) -> stock[m]?.takeIf { !it.isNaN() }?.let { x to it } }
            if (pairs.size < minObs) continue
            val xs = pairs.map { it.first }
            val ys = pairs.map { it.second }
            val mx = xs.average()
            val my = ys.average()
            val xvar = xs.sumOf { (it - mx) * (it - mx) }
            if (!(xvar > 0)) continue
            val beta = xs.indices.sumOf { (xs[it] - mx) * (ys[it] - my) } / xvar
            val alpha = my - beta * mx
            val resid = xs.indices.map { ys[it] - alpha - beta * xs[it] }
            val tail = resid.takeLast(momWindow + skip)
            val formation = if (skip > 0) tail.dropLast(skip) else tail
            if (formation.size < 2) continue
            val mu = formation.average()
            val sd = Stats.std(formation, 1)
            out[code.trim().padStart(6, '0')] = if (sd > 0) mu / sd else mu
        }
        return out
    }

    /** 월말(그 달 마지막 유효값) 기준 월간 수익률. 첫 달·결측 달은 NaN(fill 없음). */
    private fun monthlyReturns(s: Series, asOf: LocalDate): LinkedHashMap<YearMonth, Double> {
        val last = LinkedHashMap<YearMonth, Double>()
        for (i in 0 until s.size) {
            val d = s.index[i]
            if (d.isAfter(asOf)) break
            val ym = YearMonth.from(d)
            if (!last.containsKey(ym)) last[ym] = Double.NaN
            if (!s[i].isNaN()) last[ym] = s[i]
        }
        val out = LinkedHashMap<YearMonth, Double>()
        var prev = Double.NaN
        for ((ym, v) in last) {
            out[ym] = v / prev - 1.0
            prev = v
        }
        return out
    }
}
