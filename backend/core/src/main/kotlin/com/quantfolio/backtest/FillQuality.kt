package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import com.quantfolio.quant.Stats
import com.quantfolio.quant.pyFixed
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.abs

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
