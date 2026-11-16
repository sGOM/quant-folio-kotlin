package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import com.quantfolio.quant.Stats
import com.quantfolio.quant.pyFixed
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * 모의투자 실측 NAV ↔ 백테스트 기대곡선 괴리(원본 `backtest/tracking.py`).
 * 실측 NAV 는 첫 체결일에 initialCapital 현금만 있다고 보고 체결을 시간순 재생, 거래일 종가(앞·뒤 채움)로 시가평가한다.
 * displayFrom 은 **반환 곡선만** 자른다 — 재생은 언제나 첫 체결부터(기간 필터로 NAV 레벨이 어긋나지 않게).
 */
object Tracking {
    private const val QTY_EPS = 1e-9

    data class Execution(val date: LocalDate, val symbol: String, val side: String, val qty: Double, val price: Double, val fee: Double? = null)

    fun reconstructRealizedCurve(
        executions: List<Execution>,
        closePanel: Panel,
        initialCapital: Double,
        displayFrom: LocalDate? = null,
    ): Pair<List<Map<String, Any?>>, List<String>> {
        val notes = mutableListOf<String>()
        if (executions.isEmpty()) return emptyList<Map<String, Any?>>() to notes
        val execs = executions.sortedBy { it.date }
        val first = execs.first().date
        val last = execs.last().date
        val hasPanel = closePanel.dates.isNotEmpty()
        val missing = execs.map { it.symbol }.toSortedSet().filter { !hasPanel || !closePanel.hasSymbol(it) }
        if (missing.isNotEmpty()) notes += "종가 데이터 없음(0 으로 평가): ${missing.joinToString(", ")} — NAV 가 과소평가될 수 있습니다."
        val priceDates = if (hasPanel) closePanel.dates else emptyList()
        val end = (priceDates + last).max()
        val days = (priceDates + execs.map { it.date }).toSortedSet().filter { !it.isBefore(first) && !it.isAfter(end) }.ifEmpty { listOf(first) }
        // panel.reindex(days).ffill().bfill()
        val px: Map<String, DoubleArray> = if (!hasPanel) emptyMap() else closePanel.symbols.associateWith { s ->
            val filled = closePanel.column(s).reindex(days).ffill().values
            val firstValid = filled.indexOfFirst { !it.isNaN() }
            if (firstValid > 0) for (i in 0 until firstValid) filled[i] = filled[firstValid]
            filled
        }
        val qty = LinkedHashMap<String, Double>()
        var cash = initialCapital
        val curve = mutableListOf<Map<String, Any?>>()
        var i = 0
        for ((k, d) in days.withIndex()) {
            while (i < execs.size && !execs[i].date.isAfter(d)) {
                val e = execs[i++]
                val notional = e.qty * e.price
                val fee = e.fee ?: 0.0
                if (e.side == "buy") {
                    cash -= notional + fee
                    qty[e.symbol] = (qty[e.symbol] ?: 0.0) + e.qty
                } else {
                    cash += notional - fee
                    qty[e.symbol] = (qty[e.symbol] ?: 0.0) - e.qty
                }
            }
            var holdings = 0.0
            for ((sym, q) in qty) {
                if (abs(q) < QTY_EPS) continue
                val p = px[sym]?.get(k) ?: continue
                if (!p.isNaN()) holdings += q * p
            }
            if (displayFrom == null || !d.isBefore(displayFrom)) curve += linkedMapOf("t" to d.toString(), "v" to cash + holdings)
        }
        return curve to notes
    }

    /** 체결 로그만으로 현재 현금 잔고 재생. */
    fun replayCashBalance(executions: List<Execution>, initialCapital: Double): Double =
        executions.fold(initialCapital) { cash, e -> if (e.side == "buy") cash - (e.qty * e.price + (e.fee ?: 0.0)) else cash + e.qty * e.price - (e.fee ?: 0.0) }

    private fun toIndex100(curve: List<Map<String, Any?>>): List<Map<String, Any?>> {
        val base = curve.firstNotNullOfOrNull { (it["v"] as? Number)?.toDouble()?.takeIf { v -> v > 0 } } ?: return emptyList()
        return curve.filter { it["v"] != null }.map { linkedMapOf("t" to it["t"], "v" to (it["v"] as Number).toDouble() / base * 100.0) }
    }

    /** 날짜(정규화)→값. 비유한 값 제외, 같은 날짜는 마지막 값. */
    private fun toSeries(curve: List<Map<String, Any?>>): Map<LocalDate, Double> {
        val m = java.util.TreeMap<LocalDate, Double>()
        for (p in curve) {
            val t = p["t"] as? String ?: continue
            val v = (p["v"] as? Number)?.toDouble() ?: continue
            if (!v.isFinite()) continue
            m[LocalDate.parse(t.take(10))] = v
        }
        return m
    }

    /** 정규화 인덱스(첫 양수 값=100) + 공통 거래일 기준 트래킹에러(연율)·누적괴리(%)·상관. */
    fun compare(realized: List<Map<String, Any?>>, backtest: List<Map<String, Any?>>): Map<String, Any?> {
        val metrics = linkedMapOf<String, Any?>(
            "tracking_error" to null, "cumulative_divergence_pct" to null, "realized_return_pct" to null,
            "backtest_return_pct" to null, "correlation" to null, "n_common_days" to 0,
        )
        val out = linkedMapOf<String, Any?>("realized_index" to toIndex100(realized), "backtest_index" to toIndex100(backtest), "metrics" to metrics)
        val r = toSeries(realized)
        val b = toSeries(backtest)
        if (r.isEmpty() || b.isEmpty()) return out
        val common = r.keys.filter { it in b }
        metrics["n_common_days"] = common.size
        if (common.size < 2) return out
        val rc = common.map { r.getValue(it) }
        val bc = common.map { b.getValue(it) }
        if (rc.first() > 0 && bc.first() > 0) {
            val rr = rc.last() / rc.first() - 1.0
            val br = bc.last() / bc.first() - 1.0
            metrics["realized_return_pct"] = rr * 100.0
            metrics["backtest_return_pct"] = br * 100.0
            metrics["cumulative_divergence_pct"] = ((1.0 + rr) / (1.0 + br) - 1.0) * 100.0
        }
        val rRet = (1 until rc.size).map { rc[it] / rc[it - 1] - 1.0 }
        val bRet = (1 until bc.size).map { bc[it] / bc[it - 1] - 1.0 }
        val pairs = rRet.indices.filter { !rRet[it].isNaN() && !bRet[it].isNaN() }
        val diff = pairs.map { rRet[it] - bRet[it] }
        if (diff.size >= 2) metrics["tracking_error"] = Stats.std(diff, 1) * sqrt(252.0)
        if (pairs.size >= 2) {
            val a = pairs.map { rRet[it] }
            val c = pairs.map { bRet[it] }
            if (Stats.std(a, 0) > 0 && Stats.std(c, 0) > 0) metrics["correlation"] = Stats.pearson(a.toDoubleArray(), c.toDoubleArray())
        }
        return out
    }
}

/**
 * 실거래 체결 ↔ 백테스트 체결모형 정합(원본 `backtest/fill_quality.py`). 부호 규약 g(P,R)=d·(P−R)/R·1e4 bp, 비용=양수.
 * M1 실행 슬리피지(체결가 vs 결정가), M2 시점 표류(당일 종가 vs 익일 종가), M3 총 괴리(체결가 vs 백테스트 가정 체결가).
 */
object FillQuality {
    const val DEFAULT_SLIP_BPS = 5.0
    const val DEFAULT_MIN_SAMPLE = 30

    data class Order(val orderId: Any?, val symbol: String, val side: String, val fillDate: LocalDate, val pLive: Double, val decisionPrice: Double? = null)

    private fun g(p: Double, r: Double, d: Int) = d * (p - r) / r * 1e4

    private fun stats(v: List<Double>): Map<String, Any?> {
        if (v.isEmpty()) return linkedMapOf("n" to 0, "mean" to null, "std" to null, "median" to null, "p90" to null, "p95" to null)
        val sorted = v.sorted()
        return linkedMapOf(
            "n" to v.size, "mean" to v.average(), "std" to if (v.size >= 2) Stats.std(v, 1) else 0.0,
            "median" to percentile(sorted, 50.0), "p90" to percentile(sorted, 90.0), "p95" to percentile(sorted, 95.0),
        )
    }

    /** numpy percentile(linear). 정렬된 값을 받는다. */
    private fun percentile(sorted: List<Double>, q: Double): Double = Stats.quantileSorted(sorted, q / 100.0)

    fun gradeM1(mean: Double?, std: Double?, n: Int, assumption: Double, minSample: Int): String {
        if (n < minSample || mean == null) return "INSUFFICIENT"
        val sd = std ?: 0.0
        if (mean > 15.0 || sd > 3.0 * assumption) return "RED"
        if (mean <= 5.0 && sd <= 2.0 * assumption) return "GREEN"
        return "AMBER"
    }

    fun gradeM3(dragDiffPct: Double?, n: Int, minSample: Int): String = when {
        dragDiffPct == null || n < minSample -> "INSUFFICIENT"
        dragDiffPct <= 0.5 -> "GREEN"
        dragDiffPct <= 1.5 -> "AMBER"
        else -> "RED"
    }

    fun compute(
        orders: List<Order>,
        closePanel: Panel,
        slipBase: Double,
        slipVolScale: Double,
        backtestSlipBps: Double = DEFAULT_SLIP_BPS,
        minSample: Int = DEFAULT_MIN_SAMPLE,
        annualTurnover: Double? = null,
    ): Map<String, Any?> {
        val dates = closePanel.dates
        val perOrder = mutableListOf<Map<String, Any?>>()
        val skipped = mutableListOf<Map<String, Any?>>()
        val m1 = mutableListOf<Double>()
        val m2 = mutableListOf<Double>()
        val m3 = mutableListOf<Double>()
        val by = listOf("m1", "m2", "m3").associateWith { mapOf("buy" to mutableListOf<Double>(), "sell" to mutableListOf()) }
        val residuals = mutableListOf<Double>()
        val fillDates = HashSet<LocalDate>()
        var fallbacks = 0
        for ((t, dayOrders) in orders.groupBy { it.fillDate }.toSortedMap()) {
            val found = dates.binarySearch(t)
            val pos = if (found >= 0) found else (-found - 1) - 1
            if (pos < 0 || pos + 1 >= dates.size) {
                dayOrders.forEach { skipped += linkedMapOf("order_id" to it.orderId, "reason" to "no_next_close") }
                continue
            }
            val t1 = dates[pos + 1]
            val smap = Slippage.volMap(closePanel.upTo(t1), dayOrders.map { it.symbol }.filter { closePanel.hasSymbol(it) }, slipBase, slipVolScale)
            for (o in dayOrders) {
                val d = if (o.side == "buy") 1 else -1
                if (!closePanel.hasSymbol(o.symbol)) {
                    skipped += linkedMapOf("order_id" to o.orderId, "reason" to "no_price_symbol")
                    continue
                }
                val col = closePanel.column(o.symbol)
                val ct = col[pos]
                val ct1 = col[pos + 1]
                if (!(!ct.isNaN() && !ct1.isNaN() && ct > 0 && ct1 > 0 && o.pLive > 0)) {
                    skipped += linkedMapOf("order_id" to o.orderId, "reason" to "invalid_price")
                    continue
                }
                val fallback = !(o.decisionPrice != null && o.decisionPrice > 0)
                val arrival = if (fallback) ct.also { fallbacks++ } else o.decisionPrice!!
                val s = smap[o.symbol] ?: slipBase
                val sBp = s * 1e4
                val pBt = ct1 * (1.0 + d * s)
                val gExec = g(o.pLive, arrival, d)
                val gTime = g(ct, ct1, d)
                val gTotal = g(o.pLive, pBt, d)
                val residual = gTotal - (gExec - sBp + gTime)
                m1 += gExec
                m2 += gTime
                m3 += gTotal
                by.getValue("m1").getValue(o.side) += gExec
                by.getValue("m2").getValue(o.side) += gTime
                by.getValue("m3").getValue(o.side) += gTotal
                residuals += residual
                fillDates += t
                perOrder += linkedMapOf(
                    "order_id" to o.orderId, "symbol" to o.symbol, "side" to o.side, "fill_date" to t.toString(), "next_close_date" to t1.toString(),
                    "p_live" to o.pLive, "r_arrival" to arrival, "arrival_fallback" to fallback, "c_t" to ct, "c_t1" to ct1, "s_bp" to sBp, "p_bt" to pBt,
                    "m1_exec_bp" to gExec, "m2_time_bp" to gTime, "m3_total_bp" to gTotal, "checksum_residual_bp" to residual,
                )
            }
        }
        val m1All = stats(m1)
        val mean1 = m1All["mean"] as Double?
        val dragAbs = if (annualTurnover != null && mean1 != null) annualTurnover * mean1 / 1e4 * 100.0 else null
        val dragDiff = if (annualTurnover != null && mean1 != null) annualTurnover * (mean1 - backtestSlipBps) / 1e4 * 100.0 else null
        fun block(k: String, all: List<Double>) = linkedMapOf("all" to stats(all), "buy" to stats(by.getValue(k).getValue("buy")), "sell" to stats(by.getValue(k).getValue("sell")))
        return linkedMapOf(
            "sample" to linkedMapOf(
                "n_orders" to m1.size, "n_skipped" to skipped.size, "n_clusters" to fillDates.size, "n_arrival_fallback" to fallbacks,
                "min_sample" to minSample, "note" to "클러스터(동일 체결일) 상관 미보정 — 표준 std 근사.",
            ),
            "assumptions" to linkedMapOf("backtest_slip_bps" to backtestSlipBps, "slip_base_bps" to slipBase * 1e4, "slip_vol_scale" to slipVolScale, "annual_turnover" to annualTurnover),
            "m1_exec" to block("m1", m1),
            "m2_time" to block("m2", m2),
            "m3_total" to block("m3", m3),
            "checksum" to linkedMapOf(
                "mean_abs_residual_bp" to if (residuals.isEmpty()) null else residuals.map { abs(it) }.average(),
                "note" to "g_total ≈ g_exec − s_bp + g_time (1차 근사 항등식)",
            ),
            "annualized_drag" to linkedMapOf("drag_abs_pct_per_yr" to dragAbs, "drag_diff_pct_per_yr" to dragDiff),
            "grades" to linkedMapOf(
                "m1_exec" to gradeM1(mean1, m1All["std"] as Double?, m1.size, backtestSlipBps, minSample),
                "m3_total" to gradeM3(dragDiff, m1.size, minSample),
            ),
            "per_order" to perOrder,
            "skipped" to skipped,
        )
    }
}

/**
 * 슬리피지 가정 되먹임 제안(원본 `slippage_calibration.py`). **제안만** 한다 — config 반영은 사람 승인 경로뿐.
 * 표본 부족·미미한 변화면 null, 1회 조정폭 ±maxStep 클램프, 대표값은 M1 중앙값.
 */
object SlippageCalibration {
    data class Proposal(val currentBps: Double, val proposedBps: Double, val observedMedianBps: Double, val sampleSize: Int, val clamped: Boolean, val reason: String)

    @Suppress("UNCHECKED_CAST")
    fun propose(report: Map<String, Any?>, minSample: Int = FillQuality.DEFAULT_MIN_SAMPLE, maxStepBps: Double = 3.0, minChangeBps: Double = 1.0): Proposal? {
        val m1All = ((report["m1_exec"] as? Map<String, Any?>)?.get("all") as? Map<String, Any?>) ?: emptyMap()
        val n = (m1All["n"] as? Number)?.toInt() ?: 0
        val median = (m1All["median"] as? Number)?.toDouble()
        val current = ((report["assumptions"] as? Map<String, Any?>)?.get("backtest_slip_bps") as? Number)?.toDouble() ?: FillQuality.DEFAULT_SLIP_BPS
        if (n < minSample || median == null) return null
        val low = current - maxStepBps
        val high = current + maxStepBps
        val proposed = BigDecimal(maxOf(0.0, median.coerceIn(low, high))).setScale(2, RoundingMode.HALF_EVEN).toDouble()
        val clamped = median < low || median > high
        if (abs(proposed - current) < minChangeBps) return null
        val direction = if (proposed > current) "상향" else "하향"
        val reason = "실측 실행 슬리피지 중앙값 ${pyFixed(median, 2)}bp(표본 $n) 기준 ${pyFixed(current, 2)}bp → ${pyFixed(proposed, 2)}bp $direction" +
            if (clamped) " (±${pyFixed(maxStepBps, 0)}bp 클램프 적용)" else ""
        return Proposal(current, proposed, median, n, clamped, reason)
    }
}
