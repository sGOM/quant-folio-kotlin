package com.quantfolio.backtest

import com.quantfolio.common.MarketClock
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.store.Bar
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/** 체결된(부분·전체) 주문 1건과 그 체결들. */
data class FilledOrder(val id: Int, val symbol: String, val side: String, val price: Double?, val fills: List<Fill>) {
    data class Fill(val qty: Double, val price: Double, val fee: Double, val executedAt: Instant)
}

/**
 * 실거래 체결 데이터 조립(원본 `routes/fill_quality.compute_fill_quality_report`·`tracking` 의 DB 부분).
 * 라우트·실전 게이트(엔진)·주간 배치가 함께 쓴다. 소유권 확인은 호출자 몫(주문은 user_id 로만 조회된다).
 */
@Component
class FillQualityReport(
    private val jdbc: NamedParameterJdbcTemplate,
    private val strategies: StrategyRepository,
    private val prices: PriceHistory,
    private val days: LocalTradingDays,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 사용자의 partial·filled 주문(전략 주문만) — 생성 시각 순, 체결은 id 순. */
    fun filledOrders(userId: Int, strategyId: Int?, createdFrom: Instant? = null, createdTo: Instant? = null): List<FilledOrder> {
        val p = MapSqlParameterSource("u", userId)
        var where = "o.user_id = :u and o.strategy_id is not null and o.status in ('partial', 'filled')"
        strategyId?.let { where += " and o.strategy_id = :s"; p.addValue("s", it) }
        createdFrom?.let { where += " and o.created_at >= :f"; p.addValue("f", java.sql.Timestamp.from(it)) }
        createdTo?.let { where += " and o.created_at <= :t"; p.addValue("t", java.sql.Timestamp.from(it)) }
        val rows = jdbc.queryForList(
            "select o.id, o.symbol, o.side, o.price, e.filled_qty, e.filled_price, e.fee, e.executed_at from orders o " +
                "left join executions e on e.order_id = o.id where $where order by o.created_at, o.id, e.id",
            p,
        )
        val out = LinkedHashMap<Int, FilledOrder>()
        for (r in rows) {
            val id = r["id"] as Int
            val o = out.getOrPut(id) { FilledOrder(id, r["symbol"] as String, r["side"] as String, (r["price"] as BigDecimal?)?.toDouble(), mutableListOf()) }
            val qty = r["filled_qty"] as BigDecimal? ?: continue
            (o.fills as MutableList) += FilledOrder.Fill(
                qty.toDouble(), (r["filled_price"] as BigDecimal).toDouble(), (r["fee"] as BigDecimal?)?.toDouble() ?: 0.0,
                (r["executed_at"] as java.sql.Timestamp).toInstant(),
            )
        }
        return out.values.toList()
    }

    /** 정합 실측 리포트 + window. detail=false 면 주문별 상세·스킵 목록을 뺀다. */
    fun report(
        userId: Int,
        dFrom: LocalDate,
        dTo: LocalDate,
        strategyId: Int? = null,
        annualTurnover: Double? = null,
        capital: Double? = null,
        minSample: Int = FillQuality.DEFAULT_MIN_SAMPLE,
        detail: Boolean = false,
    ): MutableMap<String, Any?> {
        var slipBps = FillQuality.DEFAULT_SLIP_BPS
        var volScale = 0.0
        strategyId?.let { sid ->
            strategies.findById(sid).filter { it.userId == userId }.ifPresent { s ->
                slipBps = Cfg.or(s.config, "slippage_bps", FillQuality.DEFAULT_SLIP_BPS).let { Cfg.num(it) }
                volScale = Cfg.or(s.config, "slippage_vol_scale", 0.0).let { Cfg.num(it) }
            }
        }
        val orders = filledOrders(userId, strategyId, MarketClock.kstStart(dFrom), MarketClock.kstEnd(dTo))
        val norm = orders.mapNotNull { o ->
            if (o.fills.isEmpty()) return@mapNotNull null
            val tot = o.fills.sumOf { it.qty }
            if (tot <= 0) return@mapNotNull null
            val pLive = o.fills.sumOf { it.price * it.qty } / tot
            FillQuality.Order(o.id, o.symbol, o.side, o.fills.maxOf { MarketClock.kstDate(it.executedAt) }, pLive, o.price) to tot
        }
        var turnover = annualTurnover
        if (turnover == null && capital != null) turnover = estimateTurnover(norm, capital, (dTo.toEpochDay() - dFrom.toEpochDay()).toInt())
        val result = LinkedHashMap(
            FillQuality.compute(norm.map { it.first }, closePanel(norm.map { it.first }), maxOf(0.0, slipBps / 1e4), volScale, slipBps, minSample, turnover),
        )
        result["window"] = linkedMapOf(
            "date_from" to dFrom.toString(), "date_to" to dTo.toString(), "strategy_id" to strategyId,
            "turnover_estimated" to (annualTurnover == null && turnover != null),
        )
        if (!detail) {
            result.remove("per_order")
            result.remove("skipped")
        }
        return result
    }

    /** 편도 연 회전율 ≈ 매수 체결금액 / 자본 / 연수(거친 추정). */
    private fun estimateTurnover(orders: List<Pair<FillQuality.Order, Double>>, capital: Double, spanDays: Int): Double? {
        if (capital <= 0 || spanDays <= 0) return null
        val buy = orders.filter { it.first.side == "buy" }.sumOf { it.first.pLive * it.second }
        if (buy <= 0) return null
        return buy / capital / (spanDays / 365.25)
    }

    /**
     * 백테스트와 같은 소스(로컬 일봉 수정종가)로 [최소 체결일−45일, 최대 체결일+10일]. 끝은 마지막 적재 거래일로 자른다 —
     * 아직 안 온 날까지 요구하면 미적재로 전 종목이 빠진다. 구간 안쪽 결손·종목별 실패는 그 종목만 건너뛴다(표본 부족은 게이트가 막는다).
     */
    internal fun closePanel(orders: List<FillQuality.Order>): Panel {
        if (orders.isEmpty()) return Panel(emptyList(), emptyList(), emptyMap())
        val start = orders.minOf { it.fillDate }.minusDays(45)
        val maxFill = orders.maxOf { it.fillDate }.plusDays(10)
        val end = minOf(maxFill, days.lastTradingDayUpTo(MarketClock.todayKst(clock)) ?: maxFill)
        val bars = linkedMapOf<String, List<Bar>>()
        for (sym in orders.map { it.symbol }.toSortedSet()) {
            bars[sym] = try {
                prices.load(sym, start, end)
            } catch (e: Exception) {
                log.warn("정합 실측 종가 적재 실패 {}: {}", sym, e.message)
                continue
            }
        }
        return dailyClosePanel(bars)
    }

    companion object {
        /** 종목별 일봉 → 종가 패널(같은 날 여러 봉이면 마지막 값). 봉이 없는 종목은 열에서 빠진다. */
        fun dailyClosePanel(barsBySymbol: Map<String, List<Bar>>): Panel {
            val cols = linkedMapOf<String, Series>()
            for ((sym, bars) in barsBySymbol) {
                if (bars.isEmpty()) continue
                val byDate = java.util.TreeMap<LocalDate, Double>()
                for (b in bars) byDate[b.date] = b.close
                cols[sym] = Series(byDate.keys.toList(), byDate.values.toDoubleArray())
            }
            return if (cols.isEmpty()) Panel(emptyList(), emptyList(), emptyMap()) else Panel.fromSeries(cols)
        }
    }
}
