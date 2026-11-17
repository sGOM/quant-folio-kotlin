package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext

/**
 * 체결 반영의 단일 진입점(원본 `engine/fills.py`) — executor(주문 직후)와 reconcile(주기 재조회)이 같은 로직을 쓴다.
 * 포지션은 (사용자, 증권사 계좌, 전략, 종목)으로 분리한다.
 */
@Component
class Fills(
    private val executions: ExecutionRepository,
    private val positions: PositionRepository,
    private val orders: OrderRepository,
    private val alerts: AlertPublisher,
    private val jdbc: NamedParameterJdbcTemplate,
) {
    private val log = LoggerFactory.getLogger("engine.fills")

    /** 이 주문에 이미 기록된 체결수량(executions 합). 정합·체결통보는 누적에서 이 값을 빼 증분만 기록한다. */
    fun recordedQty(orderId: Int): Int = jdbc.queryForObject(
        "select coalesce(sum(filled_qty), 0) from executions where order_id = :o", MapSqlParameterSource("o", orderId), BigDecimal::class.java,
    )!!.toInt()

    data class RecordedFill(val oversellWarning: String?)

    /** 체결 이벤트(WS 토스트). executor·정합·체결통보 세 경로가 같은 모양으로 낸다 — 키 순서도 계약. */
    fun publishExecution(order: Order, qty: Int, price: BigDecimal, recorded: RecordedFill, source: String? = null) {
        recorded.oversellWarning?.let { warning ->
            alerts.publish(order.userId, order.strategyId!!, AlertSeverity.warning, warning, "oversell_clamped")
        }
        val payload = linkedMapOf<String, Any?>(
            "type" to "execution", "user_id" to order.userId, "order_id" to order.id, "symbol" to order.symbol, "side" to order.side,
            "qty" to qty, "price" to price.toDouble(), "status" to order.status, "kis_order_id" to order.kisOrderId,
        )
        source?.let { payload["source"] = it }
        alerts.publishEvent(payload)
    }

    /** Execution 기록 + 포지션 평단 갱신 + 주문 상태. 호출자의 트랜잭션 안에서. */
    @Transactional(propagation = Propagation.MANDATORY)
    fun record(order: Order, qty: Int, price: BigDecimal, fullyFilled: Boolean, expectedRecordedQty: Int): RecordedFill? {
        val accountKey = requireNotNull(order.brokerAccountKey) { "체결 기록에 주문 계좌가 필요합니다." }
        // 주문이 달라도 같은 포지션을 갱신할 수 있다. 아직 행이 없을 때도 커밋까지 직렬화한다.
        jdbc.queryForList(
            "select pg_advisory_xact_lock(:userId, hashtext(:positionKey))",
            mapOf("userId" to order.userId, "positionKey" to "fill-position:$accountKey:${order.strategyId}:${order.symbol}"),
        )
        // DB 락 대기 중 Redis 주문 락이 만료됐으면 다른 경로의 최신 체결을 우선한다.
        val orderId = order.id!!
        if (recordedQty(orderId) != expectedRecordedQty) return null
        val pos = positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(order.userId, accountKey, order.strategyId, order.symbol)
        val fillQty = BigDecimal(qty)
        val positionQty = if (order.side == "buy") fillQty else fillQty.min(pos?.qty ?: BigDecimal.ZERO).max(BigDecimal.ZERO)
        executions.save(Execution(orderId, order.strategyId, fillQty, price, accountPositionQty = positionQty))
        order.status = if (fullyFilled) OrderStatus.filled else OrderStatus.partial
        orders.save(order)
        var oversellWarning: String? = null
        if (order.side == "buy") {
            if (pos == null) {
                positions.save(Position(order.userId, order.strategyId, order.symbol, fillQty, price, brokerAccountKey = accountKey))
            } else {
                val newQty = pos.qty + fillQty
                pos.avgPrice = (pos.qty * pos.avgPrice + fillQty * price).divide(newQty, MathContext.DECIMAL128)
                pos.qty = newQty
                positions.save(pos)
            }
        } else if (pos != null) {
            if (fillQty > pos.qty) {
                log.warn("오버셀 감지: 매도 체결 {}주가 보유수량 {}주를 초과 — 0으로 클램프 user={} strategy={} symbol={}(정합 경쟁·이중 체결 의심)", fillQty, pos.qty, order.userId, order.strategyId, order.symbol)
                if (order.strategyId != null) {
                    oversellWarning = "${order.symbol} 매도 체결 ${fillQty.toPlainString()}주가 보유수량 ${pos.qty.toPlainString()}주를 초과해 포지션을 0으로 클램프했습니다(정합 경쟁 의심 — 확인 필요)."
                }
            }
            pos.qty = maxOf(pos.qty - fillQty, BigDecimal.ZERO)
            if (pos.qty.signum() == 0) pos.avgPrice = BigDecimal.ZERO
            positions.save(pos)
        }
        return RecordedFill(oversellWarning)
    }
}
