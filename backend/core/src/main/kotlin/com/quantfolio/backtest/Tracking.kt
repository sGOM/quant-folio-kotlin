package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import com.quantfolio.quant.Stats
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
