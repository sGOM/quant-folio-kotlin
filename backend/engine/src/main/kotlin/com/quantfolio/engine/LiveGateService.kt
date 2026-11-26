package com.quantfolio.engine

import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.live.LiveGate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Clock

/**
 * 실전 게이트 입력 조립(원본 `live_gate.evaluate_live_gate` 의 DB 부분) → 판정은 [LiveGate.decide].
 * 체결 정합 리포트는 최근 90일(KST), 금액 상한은 전략별→공통 리스크 한도와 KST 당일 체결 명목 + 당일 미체결 잔량 명목 합.
 */
@Component
class LiveGateService(
    private val reports: FillQualityReport,
    private val risk: RiskManager,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: QuantfolioProperties,
    private val clock: Clock,
    private val users: UserRepository,
) {
    /** 모의투자는 KIS 에만 있다 — 토스 사용자는 KIS 환경 설정과 무관하게 실전이다. */
    fun isPaperTrading(user: User): Boolean = user.broker.ifEmpty { "kis" } == "kis" && props.kis.isPaperTrading

    fun isPaperTrading(userId: Int): Boolean = isPaperTrading(users.findById(userId).orElseThrow())

    fun evaluate(userId: Int, strategyId: Int, orderNotional: BigDecimal? = null, paper: Boolean = isPaperTrading(userId)): LiveGate.Result {
        val today = MarketClock.todayKst(clock)
        val report = reports.report(userId, today.minusDays(90), today, strategyId)
        val order = orderNotional?.let {
            // 당일 체결 명목 + 당일 미완료 주문의 미체결 잔량 × 주문가(신호가). 원본은 체결만 더해 미체결분만큼 상한을 넘겨 체결될 수 있었다
            // (migration-notes #16③). 지난 날 주문은 당일 상한 밖(KRX 주문은 당일 소멸), 종결(closed_at)·가격 없는 주문은 잔량에서 빠진다.
            val used = jdbc.queryForObject(
                """select coalesce((select sum(e.filled_qty * e.filled_price) from executions e join orders o on e.order_id = o.id
                                     where o.user_id = :u and e.executed_at >= :t), 0)
                        + coalesce((select sum(greatest(o.qty - coalesce((select sum(e.filled_qty) from executions e where e.order_id = o.id), 0), 0) * o.price)
                                      from orders o where o.user_id = :u and o.created_at >= :t and o.closed_at is null
                                       and o.status in ('pending', 'submitted', 'partial')), 0)""",
                MapSqlParameterSource("u", userId).addValue("t", Timestamp.from(MarketClock.kstStart(today))), BigDecimal::class.java,
            )!!
            LiveGate.OrderCheck(it, risk.limit(userId, strategyId)?.maxPositionSize, used, props.kis.dailyOrderNotionalCap?.toDouble())
        }
        return LiveGate.decide(paper, props.kis.env, props.kis.prodApproved, report, order, strategyId)
    }
}
