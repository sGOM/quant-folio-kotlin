package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.MathContext
import java.text.DecimalFormat
import java.time.Duration

/**
 * 체결 반영의 단일 진입점(원본 `engine/fills.py`) — executor(주문 직후)와 reconcile(주기 재조회)이 같은 로직을 쓴다.
 * 포지션은 (사용자, 전략, 종목)으로 분리(같은 종목을 여러 전략이 보유해도 섞이지 않게).
 */
@Component
class Fills(
    private val executions: ExecutionRepository,
    private val positions: PositionRepository,
    private val orders: OrderRepository,
    private val alerts: AlertPublisher,
) {
    private val log = LoggerFactory.getLogger("engine.fills")

    /** Execution 기록 + 포지션 평단 갱신 + 주문 상태. 호출자의 트랜잭션 안에서. */
    fun record(order: Order, qty: Int, price: BigDecimal, fullyFilled: Boolean, alertOnOversell: Boolean = true) {
        executions.save(Execution(order.id!!, order.strategyId, BigDecimal(qty), price, BigDecimal.ZERO))
        order.status = if (fullyFilled) "filled" else "partial"
        orders.save(order)
        val pos = positions.findByUserIdAndStrategyIdAndSymbol(order.userId, order.strategyId, order.symbol)
        val fillQty = BigDecimal(qty)
        if (order.side == "buy") {
            if (pos == null) {
                positions.save(Position(order.userId, order.strategyId, order.symbol, fillQty, price))
            } else {
                val newQty = pos.qty + fillQty
                pos.avgPrice = (pos.qty * pos.avgPrice + fillQty * price).divide(newQty, MathContext.DECIMAL128)
                pos.qty = newQty
                positions.save(pos)
            }
        } else if (pos != null) {
            if (fillQty > pos.qty) {
                log.warn("오버셀 감지: 매도 체결 {}주가 보유수량 {}주를 초과 — 0으로 클램프 user={} strategy={} symbol={}(정합 경쟁·이중 체결 의심)", fillQty, pos.qty, order.userId, order.strategyId, order.symbol)
                if (alertOnOversell && order.strategyId != null) {
                    alerts.publish(
                        order.userId, order.strategyId!!, "warning",
                        "${order.symbol} 매도 체결 ${fillQty.toPlainString()}주가 보유수량 ${pos.qty.toPlainString()}주를 초과해 포지션을 0으로 클램프했습니다(정합 경쟁 의심 — 확인 필요).",
                        "oversell_clamped",
                    )
                }
            }
            pos.qty = maxOf(pos.qty - fillQty, BigDecimal.ZERO)
            if (pos.qty.signum() == 0) pos.avgPrice = BigDecimal.ZERO
            positions.save(pos)
        }
    }
}

/**
 * 주문 실행기(원본 `engine/executor.py`). 멱등 3중 방어: 결정적 키 + Redis 락(SET NX) + orders.idempotency_key UNIQUE.
 *
 * 주문 전송 결과는 세 갈래다.
 * 1. 성공 → submitted, 체결 조회.
 * 2. 증권사가 명시적으로 거부([BrokerError]) → rejected(접수되지 않음이 확인됐으므로 확정 안전).
 * 3. 그 밖의 예외(타임아웃·연결 끊김·파싱 실패) → **접수 여부 불명**. 체결됐을 수 있어 pending(주문번호 없음) 그대로 두고
 *    critical 알림 후 재전파한다. 멱등 키가 이미 이 행에 걸려 있어 같은 신호로 재주문되지 않고, 회수는 reconcile 이 잔고로 한다.
 */
@Component
class OrderExecutor(
    private val orders: OrderRepository,
    private val fills: Fills,
    private val redis: StringRedisTemplate,
    private val alerts: AlertPublisher,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger("engine.executor")

    fun execute(
        broker: BrokerClient, userId: Int, strategyId: Int, symbol: String, side: String, qty: Int, price: BigDecimal,
        idempotencyKey: String, reason: String? = null,
    ): Order? {
        val lockKey = RedisKeys.ORDER_LOCK_PREFIX + idempotencyKey
        if (redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(LOCK_TTL)) != true) {
            log.info("중복 주문 차단(락 보유 중): {}", idempotencyKey)
            return null
        }
        try {
            if (orders.existsByIdempotencyKey(idempotencyKey)) {
                log.info("중복 주문 차단(DB 존재): {}", idempotencyKey)
                return null
            }
            val order = try {
                orders.saveAndFlush(Order(userId, strategyId, symbol, side, BigDecimal(qty), price, "market", status = "pending", reason = reason, idempotencyKey = idempotencyKey))
            } catch (e: DataIntegrityViolationException) {
                log.info("중복 주문 차단(UNIQUE 충돌): {}", idempotencyKey)
                return null
            }

            try {
                val res = broker.placeOrder(symbol, side, qty, 0, "market")
                order.kisOrderId = res.orderId
                order.kisOrderOrgNo = res.orderOrgNo
                order.status = "submitted"
            } catch (e: BrokerError) {
                order.status = "rejected"
                orders.save(order)
                log.warn("주문 거부됨 {}: {}", idempotencyKey, e.message)
                alerts.publishEvent(linkedMapOf("type" to "order", "user_id" to userId, "order_id" to order.id, "status" to "rejected", "symbol" to symbol))
                return order
            } catch (e: Exception) {
                log.error("주문 접수 결과 불명 {} ({} {} {}주)", idempotencyKey, side, symbol, qty, e)
                try {
                    alerts.publish(
                        userId, strategyId, "critical",
                        "주문 접수 결과 불명 — $symbol $side ${DecimalFormat("#,##0").format(qty)}주(주문 #${order.id}). " +
                            "증권사 응답을 받지 못해 접수·체결 여부를 알 수 없습니다. 실제 체결됐을 수 있어 재주문하지 않고 대기시킵니다" +
                            "(정합 루프가 잔고로 교차확인). 원인: $e",
                        "order_unconfirmed",
                    )
                } catch (alertError: Exception) {
                    log.warn("접수 불명 알림 발행 실패(무시) {}: {}", idempotencyKey, alertError.message)
                }
                throw e
            }

            val (fillQty, fillPrice) = resolveFill(broker, order, price)
            if (fillQty <= 0) {
                orders.save(order) // 접수만 됨 — submitted 유지, reconcile 이 수렴
                alerts.publishEvent(linkedMapOf("type" to "order", "user_id" to userId, "order_id" to order.id, "symbol" to symbol, "side" to side, "status" to "submitted", "kis_order_id" to order.kisOrderId))
                log.info("주문 접수(미체결): {} {} {}주", side, symbol, qty)
                return order
            }
            tx.executeWithoutResult { fills.record(order, fillQty, fillPrice, fillQty >= qty) }
            alerts.publishEvent(
                linkedMapOf(
                    "type" to "execution", "user_id" to userId, "order_id" to order.id, "symbol" to symbol, "side" to side, "qty" to fillQty,
                    "price" to fillPrice.toDouble(), "status" to order.status, "kis_order_id" to order.kisOrderId,
                ),
            )
            log.info("주문 체결 기록: {} {} {}주 @ {} (실제 체결가)", side, symbol, fillQty, fillPrice)
            return order
        } finally {
            redis.delete(lockKey)
        }
    }

    /**
     * 실제 체결수량·평균체결가. 조회 실패는 미체결(0)로 보고 submitted 로 남긴다 — 전량 체결로 간주하면 filled 로 확정돼
     * reconcile 대상에서 빠지고, 실제 미체결이었을 때 장부가 영구 오염된다.
     */
    private fun resolveFill(broker: BrokerClient, order: Order, signalPrice: BigDecimal): Pair<Int, BigDecimal> {
        val id = order.kisOrderId?.takeIf { it.isNotEmpty() } ?: return 0 to signalPrice // 원본 `if not` — 빈 주문번호로 조회하지 않는다
        val info = try {
            broker.getOrderExecution(id, order.symbol)
        } catch (e: BrokerError) {
            log.warn("체결 조회 실패 — SUBMITTED 유지, reconcile 로 수렴 위임 {}: {}", order.idempotencyKey, e.message)
            return 0 to signalPrice
        }
        if (info.filledQty <= 0) return 0 to signalPrice
        val avg = info.avgPrice
        if (avg == null || avg.signum() <= 0) {
            log.warn("체결 평균가 없음 — 신호가로 폴백 {}", order.idempotencyKey)
            return info.filledQty to signalPrice
        }
        return info.filledQty to avg
    }

    companion object {
        private const val LOCK_TTL = 30L

        /** 결정적 멱등 키 — 같은 신호봉의 같은 주문은 항상 같은 키. */
        fun idempotencyKey(strategyId: Int, symbol: String, side: String, barTs: String) = "s$strategyId:$symbol:$side:$barTs"
    }
}
