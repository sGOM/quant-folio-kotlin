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
 * 체결 정합 리포트는 최근 90일(KST), 금액 상한은 전략별→공통 리스크 한도와 KST 당일 체결 명목 합.
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
            val used = jdbc.queryForObject(
                "select coalesce(sum(e.filled_qty * e.filled_price), 0) from executions e join orders o on e.order_id = o.id where o.user_id = :u and e.executed_at >= :t",
                MapSqlParameterSource("u", userId).addValue("t", Timestamp.from(MarketClock.kstStart(today))), BigDecimal::class.java,
            )!!
            LiveGate.OrderCheck(it, risk.limit(userId, strategyId)?.maxPositionSize, used, props.kis.dailyOrderNotionalCap?.toDouble())
        }
        return LiveGate.decide(paper, props.kis.env, props.kis.prodApproved, report, order, strategyId)
    }
}
