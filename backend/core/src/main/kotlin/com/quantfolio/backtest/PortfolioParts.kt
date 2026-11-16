package com.quantfolio.backtest

import com.quantfolio.quant.Num
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Stats
import java.time.LocalDate
import kotlin.math.pow
import kotlin.math.sqrt

// 리밸런싱 백테스트 부품: 리스크 캡(risk_caps.py), 변동성 슬리피지(slippage.py), 성과귀속·위험조정지표(attribution.py).

/** 비중·금액 비교 허용오차이자 잔량 기준 — 이하이면 0 으로 본다(원본 `1e-12`). */
internal const val EPS = 1e-12

/** 목표비중 리스크 레이어. 초과분은 한도 미만 종목에 비례 재분배, 여력이 없으면 현금. */
object RiskCaps {
    fun capPosition(targets: Map<String, Double>, cap: Double): Map<String, Double> {
        if (cap >= 1.0 || targets.isEmpty()) return targets
        val w = LinkedHashMap(targets)
        repeat(w.size + 1) {
            val excess = w.values.filter { it > cap + EPS }.sumOf { it - cap }
            if (excess <= EPS) return w
            for (s in w.keys) if (w.getValue(s) > cap) w[s] = cap
            val under = w.filterValues { it < cap - EPS }
            val pool = under.values.sum()
            if (pool <= EPS) return w
            for ((s, v) in under) w[s] = w.getValue(s) + excess * v / pool
        }
        return w
    }

    /** 업종 합계 한도. 업종 미분류 종목은 각자 별도 업종으로 본다(뭉쳐서 캡에 걸리지 않게). */
    fun capSector(targets: Map<String, Double>, sectorMap: Map<String, String?>, cap: Double): Map<String, Double> {
        if (cap >= 1.0 || targets.isEmpty()) return targets
        fun sec(code: String) = sectorMap[code]?.takeIf { it.isNotEmpty() } ?: "__unclassified__$code"
        val w = LinkedHashMap(targets)
        repeat(w.size + 1) {
            val tot = LinkedHashMap<String, Double>()
            for ((c, v) in w) tot[sec(c)] = (tot[sec(c)] ?: 0.0) + v
            val excess = tot.values.filter { it > cap + EPS }.sumOf { it - cap }
            if (excess <= EPS) return w
            for (c in w.keys) {
                val t = tot.getValue(sec(c))
                if (t > cap + EPS && t > 0) w[c] = w.getValue(c) * (cap / t)
            }
            val under = w.filter { (c, v) -> tot.getValue(sec(c)) < cap - EPS && v > 0 }
            val pool = under.values.sum()
            if (pool <= EPS) return w
            for ((c, cw) in under) w[c] = w.getValue(c) + excess * cw / pool
        }
        return w
    }

    /** 목표비중의 연율 포트 변동성(최근 lookback 일 수익률 공분산, 짝없는 결측은 0 공분산). 표본 부족이면 null. */
    fun portfolioVolAnn(hist: Panel, weights: Map<String, Double>, lookback: Int): Double? {
        val syms = weights.keys.filter { hist.hasSymbol(it) && weights.getValue(it) > 0 }
        if (syms.isEmpty()) return null
        val rets = syms.map { s -> hist.column(s).pctChange().values.let { it.copyOfRange(maxOf(0, it.size - lookback), it.size) } }
        if (rets[0].size < maxOf(10, lookback / 2)) return null
        val w = syms.map { weights.getValue(it) }
        var varDaily = 0.0
        for (i in syms.indices) for (j in syms.indices) {
            val c = pairCov(rets[i], rets[j])
            varDaily += w[i] * (if (c.isNaN()) 0.0 else c) * w[j]
        }
        if (!(varDaily > 0)) return null
        return sqrt(varDaily * 252.0)
    }

    /** pandas DataFrame.cov 의 쌍별 완전관측 공분산(ddof=1). */
    private fun pairCov(a: DoubleArray, b: DoubleArray): Double {
        val idx = a.indices.filter { !a[it].isNaN() && !b[it].isNaN() }
        if (idx.size < 2) return Double.NaN
        val ma = idx.sumOf { a[it] } / idx.size
        val mb = idx.sumOf { b[it] } / idx.size
        return idx.sumOf { (a[it] - ma) * (b[it] - mb) } / (idx.size - 1)
    }

    /** 원본 `_apply_risk_caps`: 종목 한도 → 업종 한도 → 변동성 타겟(축소만, max_leverage 캡). */
    fun apply(targets: Map<String, Double>, hist: Panel, risk: Map<String, Any?>, sectorMap: Map<String, String?>?): Map<String, Double> {
        if (targets.isEmpty() || risk.isEmpty()) return targets
        var t = targets
        risk["max_position_pct"]?.let { if (truthy(it)) t = capPosition(t, (it as Number).toDouble()) }
        risk["max_sector_pct"]?.let { if (truthy(it) && !sectorMap.isNullOrEmpty()) t = capSector(t, sectorMap, (it as Number).toDouble()) }
        val tv = risk["target_vol"]
        if (truthy(tv)) {
            val lb = (risk["vol_lookback"] as? Number)?.toInt()?.takeIf { it != 0 } ?: 20
            val vol = portfolioVolAnn(hist, t, lb)
            if (vol != null && vol > 0) {
                val maxLev = (risk["max_leverage"] as? Number)?.toDouble()?.takeIf { it != 0.0 } ?: 1.0
                val scale = minOf(maxLev, (tv as Number).toDouble() / vol)
                if (scale < 1.0 - 1e-9) t = t.mapValues { it.value * scale }
            }
        }
        return t
    }

    private fun truthy(v: Any?) = v != null && (v !is Number || v.toDouble() != 0.0)
}

object Slippage {
    /** 종목별 편도 슬리피지 = base × (1 + scale × (σ/중앙값σ − 1)), 하한 0. σ 는 최근 20일 일간수익률(10개 이상). */
    fun volMap(hist: Panel, symbols: Collection<String>, baseFrac: Double, volScale: Double): Map<String, Double> {
        if (baseFrac <= 0 || volScale <= 0) return emptyMap()
        val vols = LinkedHashMap<String, Double>()
        for (s in symbols) {
            if (!hist.hasSymbol(s)) continue
            val r = hist.column(s).dropNa().pctChange()
            val tail = r.tail(20)
            if (tail.size >= 10) {
                val sd = tail.std()
                if (!sd.isNaN() && sd > 0) vols[s] = sd
            }
        }
        if (vols.isEmpty()) return emptyMap()
        val sorted = vols.values.sorted()
        val med = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
        if (!(med > 0)) return emptyMap()
        return vols.mapValues { (_, v) -> maxOf(0.0, baseFrac * (1.0 + volScale * (v / med - 1.0))) }
    }
}

/** 리밸런싱일 d 의 팩터별 점수 스냅샷(후보풀 전체 크로스섹션). */
data class ScoreSnapshot(val date: LocalDate, val codes: List<String>, val scores: Map<String, DoubleArray>)

object Attribution {
    val FACTOR_SCORE_COLS = listOf(
        "score_momentum", "score_value", "score_lowvol", "score_quality", "score_growth", "score_flow",
        "score_residual_momentum", "score_pead", "score",
    )

    /**
     * 팩터별 IC(스피어만)·IR·적중률·상하위 1/3 롱숏 누적수익. 다음 스냅샷까지의 선행수익으로 잰다.
     * 스냅샷 3개 미만이거나 팩터별 유효 구간이 3개 미만이면 생략.
     */
    fun factorAttribution(snapshots: List<ScoreSnapshot>, panel: Panel): Map<String, Map<String, Any?>> {
        val out = LinkedHashMap<String, Map<String, Any?>>()
        if (snapshots.size < 3) return out
        val dates = panel.dates
        val pos = snapshots.map { s -> dates.binarySearch(s.date).let { if (it >= 0) it else -it - 1 } }
        val gaps = (0 until pos.size - 1).filter { pos[it + 1] > pos[it] }.map { (pos[it + 1] - pos[it]).toDouble() }
        val avgGap = if (gaps.isEmpty()) 21.0 else gaps.average()
        val ann = sqrt(if (avgGap > 0) 252.0 / avgGap else 12.0)
        val ics = FACTOR_SCORE_COLS.associateWith { mutableListOf<Double>() }
        val lss = FACTOR_SCORE_COLS.associateWith { mutableListOf<Double>() }
        for (i in 0 until snapshots.size - 1) {
            val snap = snapshots[i]
            val p0 = panel.row(snap.date) ?: continue
            val p1 = panel.row(snapshots[i + 1].date) ?: continue
            val syms = snap.codes.withIndex().filter { panel.hasSymbol(it.value) }
            if (syms.size < 5) continue
            val fwd = syms.mapNotNull { (k, s) ->
                val base = p0.getValue(s)
                val f = p1.getValue(s) / base - 1.0
                if (f.isNaN() || f.isInfinite() || !(base > 0)) null else k to f
            }
            if (fwd.size < 5) continue
            for (col in FACTOR_SCORE_COLS) {
                val sc = snap.scores[col] ?: continue
                val pair = fwd.filter { !sc[it.first].isNaN() }.map { sc[it.first] to it.second }
                if (pair.size < 5 || pair.map { it.first }.distinct().size < 3) continue
                val ic = spearman(pair.map { it.first }, pair.map { it.second })
                if (!ic.isNaN()) ics.getValue(col) += ic
                val k = pair.size / 3
                if (k >= 1) {
                    val ranked = pair.sortedByDescending { it.first }
                    val top = ranked.take(k).map { it.second }.average()
                    val bot = ranked.takeLast(k).map { it.second }.average()
                    if (!top.isNaN() && !bot.isNaN()) lss.getValue(col) += top - bot
                }
            }
        }
        for (col in FACTOR_SCORE_COLS) {
            val arr = ics.getValue(col)
            if (arr.size < 3) continue
            val mean = arr.average()
            val sd = Stats.std(arr, 1)
            val ls = lss.getValue(col)
            out[col] = linkedMapOf(
                "ic_mean" to Num.safe(mean),
                "ic_ir" to if (sd > 0) Num.safe(mean / sd * ann) else null,
                "ic_hit" to Num.safe(arr.count { it > 0 }.toDouble() / arr.size),
                "ls_return" to if (ls.isEmpty()) null else Num.safe(ls.fold(1.0) { acc, x -> acc * (1.0 + x) } - 1.0),
                "n" to arr.size,
            )
        }
        return out
    }

    private fun spearman(a: List<Double>, b: List<Double>): Double = Stats.pearson(avgRank(a), avgRank(b))

    private fun avgRank(v: List<Double>): DoubleArray {
        val order = v.indices.sortedBy { v[it] }
        val r = DoubleArray(v.size)
        var i = 0
        while (i < order.size) {
            var j = i
            while (j + 1 < order.size && v[order[j + 1]] == v[order[i]]) j++
            val rank = (i + j) / 2.0 + 1
            for (k in i..j) r[order[k]] = rank
            i = j + 1
        }
        return r
    }

    /** 샤프·소르티노(rf 초과) + 벤치마크 상대 지표(베타·알파·IR·TE·벤치 누적수익). 연율 √252. */
    fun riskAdjusted(rets: DoubleArray, bench: DoubleArray?, rfAnnual: Double): Map<String, Double?> {
        val out = linkedMapOf<String, Double?>(
            "sharpe" to null, "sortino" to null, "beta" to null, "alpha" to null,
            "information_ratio" to null, "tracking_error" to null, "benchmark_return" to null, "excess_return" to null,
        )
        val rfDaily = (1.0 + rfAnnual).pow(1.0 / 252.0) - 1.0
        val ann = sqrt(252.0)
        if (rets.size > 1) {
            val mean = rets.average()
            val std = Stats.std(rets, 1)
            if (std > 0) out["sharpe"] = Num.safe((mean - rfDaily) / std * ann)
            val dstd = sqrt(rets.sumOf { minOf(it - rfDaily, 0.0).let { d -> d * d } } / rets.size)
            if (dstd > 0) out["sortino"] = Num.safe((mean - rfDaily) / dstd * ann)
        }
        if (bench == null) return out
        val keep = bench.indices.filter { !bench[it].isNaN() }
        val p = DoubleArray(keep.size) { rets[keep[it]] }
        val b = DoubleArray(keep.size) { bench[keep[it]] }
        if (b.size > 1) {
            out["benchmark_return"] = Num.safe(b.fold(1.0) { acc, x -> acc * (1.0 + x) } - 1.0)
            val mb = b.average()
            val mp = p.average()
            val bvar = b.sumOf { (it - mb) * (it - mb) } / (b.size - 1)
            if (bvar > 0) {
                val cov = p.indices.sumOf { (p[it] - mp) * (b[it] - mb) } / (b.size - 1)
                val beta = cov / bvar
                out["beta"] = Num.safe(beta)
                out["alpha"] = Num.safe(((mp - rfDaily) - beta * (mb - rfDaily)) * 252.0)
            }
            val active = DoubleArray(p.size) { p[it] - b[it] }
            val astd = Stats.std(active, 1)
            if (astd > 0) {
                out["tracking_error"] = Num.safe(astd * ann)
                out["information_ratio"] = Num.safe(active.average() / astd * ann)
            }
        }
        return out
    }
}
