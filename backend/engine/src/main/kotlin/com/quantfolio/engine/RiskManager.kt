package com.quantfolio.engine

import com.quantfolio.common.MarketClock
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.trading.RiskLimit
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.market.kis.ManagementBlock
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock

/** 주문 전 리스크 검증(원본 `engine/risk.py`). 통과 못 하면 주문을 만들지 않는다(안전 우선). */
@Component
class RiskManager(
    private val jdbc: NamedParameterJdbcTemplate,
    private val positions: PositionRepository,
    private val master: KisStockMaster,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("engine.risk")

    /** approved 면 qty 는 매수 산정 수량 또는 매도 청산 수량. */
    data class Decision(val approved: Boolean, val qty: Int, val reason: String = "")

    private data class Aggregate(val qty: BigDecimal, val avgPrice: BigDecimal)

    /** 매수: 관리종목·정리매매 차단 → max_position_size(계좌 합산 보유가치 대비) 안에서 수량 산정. */
    fun evaluateBuy(userId: Int, strategyId: Int, symbol: String, price: BigDecimal, desiredCash: BigDecimal, accountKey: String): Decision {
        if (price.signum() <= 0) return Decision(false, 0, "유효하지 않은 가격")
        ManagementBlock.reason(master.latest(symbol), MarketClock.todayKst(clock))?.let { return Decision(false, 0, it) }
        var cash = desiredCash
        limit(userId, strategyId)?.maxPositionSize?.let { max ->
            val held = aggregate(userId, symbol, accountKey)
            val remaining = max - (held?.let { it.qty * it.avgPrice } ?: BigDecimal.ZERO)
            if (remaining.signum() <= 0) return Decision(false, 0, "최대 포지션 한도 도달")
            cash = cash.min(remaining)
        }
        val qty = cash.divide(price, 0, RoundingMode.DOWN).toInt()
        if (qty <= 0) return Decision(false, 0, "주문 가능 수량 부족")
        return Decision(true, qty)
    }

    /**
     * 매도: 보유 전량 청산. strategyId 를 주면 그 전략 귀속 수량만 — 같은 종목을 다른 전략이 보유해도 함께 팔지 않는다.
     * null 이면 계좌 합산.
     */
    fun evaluateSell(userId: Int, symbol: String, strategyId: Int?, accountKey: String): Decision {
        if (strategyId != null) {
            val qty = positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(userId, accountKey, strategyId, symbol)?.qty
            if (qty == null || qty.signum() <= 0) return Decision(false, 0, "보유 수량 없음")
            return Decision(true, qty.toInt())
        }
        val held = aggregate(userId, symbol, accountKey)
        if (held == null || held.qty.signum() <= 0) return Decision(false, 0, "보유 수량 없음")
        return Decision(true, held.qty.toInt())
    }

    /**
     * 일일 손실 한도(손익 ≤ −한도면 신규 진입 차단). 두 하드 게이트를 순서대로:
     * ① 계좌 — 공통(strategy_id null) 한도를 계좌 전체 손익에 ② 전략 — 이 전략에 명시 설정된 한도를 전략 귀속 손익에.
     * 회귀 보존: 전략 행이 있는데 daily_loss_limit 이 null 이면 계좌 게이트도 생략(원본 `_get_limit` 이 그 행을 골라 검사를 건너뛰던 동작).
     */
    fun checkDailyLoss(userId: Int, strategyId: Int, prices: Map<String, BigDecimal>, accountKey: String): Decision {
        val common = limits(userId, "strategy_id is null").firstOrNull()
        val strat = limits(userId, "strategy_id = :s", strategyId).firstOrNull()
        val suppress = strat != null && strat.dailyLossLimit == null
        val commonLimit = common?.dailyLossLimit
        if (!suppress && commonLimit != null) {
            val pnl = dailyPnl(userId, prices, null, accountKey)
            if (pnl <= commonLimit.negate()) {
                log.warn("일일 손실 한도 초과(계좌): 손익 {} <= -{} — 신규 진입 차단", pnl, commonLimit)
                return Decision(false, 0, "일일 손실 한도 초과")
            }
        }
        val stratLimit = strat?.dailyLossLimit
        if (stratLimit != null) {
            val pnl = dailyPnl(userId, prices, strategyId, accountKey)
            if (pnl <= stratLimit.negate()) {
                log.warn("일일 손실 한도 초과(전략 {}): 손익 {} <= -{} — 신규 진입 차단", strategyId, pnl, stratLimit)
                return Decision(false, 0, "전략별 일일 손실 한도 초과")
            }
        }
        return Decision(true, 0)
    }

    /** 당일(KST) 손익 = 당일 체결의 실현 현금흐름(매도 − 매수 − 수수료) + 보유 평가손익(현재가 없는 종목은 제외). */
    private fun dailyPnl(userId: Int, prices: Map<String, BigDecimal>, strategyId: Int?, accountKey: String): BigDecimal {
        val start = MarketClock.todayKst(clock).atStartOfDay(MarketClock.KST).toInstant()
        val p = MapSqlParameterSource("u", userId).addValue("t", Timestamp.from(start)).addValue("s", strategyId).addValue("a", accountKey)
        val strat = if (strategyId != null) " and e.strategy_id = :s" else ""
        var realized = BigDecimal.ZERO
        jdbc.query(
            "select e.filled_qty, e.filled_price, e.fee, o.side from executions e join orders o on e.order_id = o.id where o.user_id = :u and o.broker_account_key = :a and e.executed_at >= :t$strat",
            p,
        ) { rs ->
            val gross = rs.getBigDecimal(1) * rs.getBigDecimal(2)
            realized = if (rs.getString(4) == "sell") realized + gross - rs.getBigDecimal(3) else realized - gross - rs.getBigDecimal(3)
        }
        var unrealized = BigDecimal.ZERO
        jdbc.query("select symbol, qty, avg_price from positions where user_id = :u and broker_account_key = :a${if (strategyId != null) " and strategy_id = :s" else ""}", p) { rs ->
            val cur = prices[rs.getString(1)]
            val qty = rs.getBigDecimal(2)
            if (cur != null && qty.signum() > 0) unrealized += (cur - rs.getBigDecimal(3)) * qty
        }
        return realized + unrealized
    }

    /**
     * 손절 도달(평단 대비 stop_loss_pct 이상 하락, 계좌 합산 가중평균). 현재가 0 이하는 시세 결측이 흘러든 값이라 판정하지 않는다
     * — 그대로 나누면 하락률 100% 로 어떤 설정이든 전량 손절이 발동한다.
     */
    fun checkStopLoss(userId: Int, strategyId: Int, symbol: String, currentPrice: BigDecimal, accountKey: String): Boolean {
        val pct = limit(userId, strategyId)?.stopLossPct ?: return false
        if (currentPrice.signum() <= 0) {
            log.warn("{} 현재가가 0 이하({}) — 유효 시세 없음으로 보고 손절 판정 건너뜀", symbol, currentPrice)
            return false
        }
        val pos = aggregate(userId, symbol, accountKey) ?: return false
        if (pos.qty.signum() <= 0 || pos.avgPrice.signum() <= 0) return false
        val drop = (pos.avgPrice - currentPrice).divide(pos.avgPrice, MathContext.DECIMAL128)
        if (drop >= pct) {
            log.info("손절 조건 도달: {} 하락률 {} >= {}", symbol, drop, pct)
            return true
        }
        return false
    }

    /** 전략 한도 우선, 없으면 사용자 공통(원본 `_get_limit`). */
    fun limit(userId: Int, strategyId: Int): RiskLimit? =
        limits(userId, "(strategy_id = :s or strategy_id is null) order by strategy_id desc nulls last", strategyId).firstOrNull()

    private fun limits(userId: Int, where: String, strategyId: Int? = null): List<RiskLimit> = jdbc.query(
        "select id, user_id, strategy_id, max_position_size, daily_loss_limit, stop_loss_pct from risk_limits where user_id = :u and $where",
        MapSqlParameterSource("u", userId).addValue("s", strategyId),
    ) { rs, _ ->
        RiskLimit(rs.getInt(2), rs.getObject(3) as Int?, rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6), rs.getInt(1))
    }

    /** 계좌 단위 종목 익스포저(전략 행 합산·수량가중 평단) — 단일 행 조회는 어느 전략 행이 잡힐지 비결정적이다. */
    private fun aggregate(userId: Int, symbol: String, accountKey: String): Aggregate? {
        val rows = jdbc.query("select qty, avg_price from positions where user_id = :u and broker_account_key = :a and symbol = :s", MapSqlParameterSource("u", userId).addValue("a", accountKey).addValue("s", symbol)) { rs, _ ->
            rs.getBigDecimal(1) to rs.getBigDecimal(2)
        }
        val total = rows.fold(BigDecimal.ZERO) { a, r -> a + r.first }
        if (rows.isEmpty() || total.signum() <= 0) return null
        val weighted = rows.fold(BigDecimal.ZERO) { a, r -> a + r.first * r.second }
        return Aggregate(total, weighted.divide(total, MathContext.DECIMAL128))
    }
}
