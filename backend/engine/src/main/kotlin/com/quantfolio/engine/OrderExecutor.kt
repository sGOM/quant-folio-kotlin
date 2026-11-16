package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.OrderNotSent
import com.quantfolio.broker.OrderOutcomeUnknown
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.common.withLockOrElse
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

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
    private val brokers: BrokerCredentials,
    private val fills: Fills,
    private val redis: StringRedisTemplate,
    private val alerts: AlertPublisher,
    private val tx: TransactionTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("engine.executor")
    // 외부 호출 전에 예약·접수 정보를 확정한다. 호출자의 롤백이 주문 증거를 지우면 안 된다.
    private val orderTx = TransactionTemplate(tx.transactionManager!!).apply {
        propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
    }

    /** [heldQty] 는 이 주문을 판단할 때 읽은 이 전략·종목의 보유수량(포지션 없으면 0). */
    fun execute(
        broker: BrokerClient, userId: Int, strategyId: Int, symbol: String, side: String, qty: Int, price: BigDecimal,
        idempotencyKey: String, heldQty: BigDecimal, reason: String? = null,
    ): Order? {
        if (!brokers.isOwner(userId)) {
            log.warn("지정된 거래 소유자가 아니어서 주문 차단 user={} strategy={}", userId, strategyId)
            return null
        }
        val accountKey = broker.orderAccountKey ?: throw BrokerError("주문 계좌를 확인할 수 없습니다.")
        val lockKey = RedisKeys.ORDER_LOCK_PREFIX + idempotencyKey
        return redis.withLockOrElse(lockKey, Duration.ofSeconds(LOCK_TTL), busy = {
            log.info("중복 주문 차단(락 보유 중): {}", idempotencyKey)
            null
        }) {
            if (orders.existsByIdempotencyKey(idempotencyKey)) {
                log.info("중복 주문 차단(DB 존재): {}", idempotencyKey)
                return null
            }
            val order = try {
                orderTx.execute {
                    val p = MapSqlParameterSource("u", userId).addValue("s", symbol).addValue("st", strategyId)
                    // 신호 키가 달라도 같은 계좌의 접수불명 주문을 넘지 못하게 예약을 직렬화한다.
                    val ownsStrategy = jdbc.queryForList(
                        "select id from users where id = :u and exists (select 1 from strategies where id = :st and user_id = :u) for update",
                        p, Int::class.java,
                    ).isNotEmpty()
                    if (!ownsStrategy) {
                        log.warn("사용자의 전략이 아니어서 주문 차단 user={} strategy={}", userId, strategyId)
                        return@execute null
                    }
                    val unknown = jdbc.queryForObject(
                        "select count(*) from orders where user_id = :u and symbol = :s and closed_at is null and (status = 'pending' or (status in ('submitted', 'partial') and coalesce(trim(kis_order_id), '') = ''))",
                        p, Int::class.java,
                    )!!
                    // 같은 포지션에 오늘 낸 주문이 아직 비종결이면 그 체결이 장부에 다 오지 않았다 — 그 장부로 판단한 주문을 막는다.
                    // 주문일(KST)로 한정: 장 마감으로 잔량이 소멸한 주문은 주문일이 지나고 근거가 있을 때만 종결(closed_at)되므로 비종결로 남을 수 있다.
                    val live = jdbc.queryForObject(
                        "select count(*) from orders where user_id = :u and symbol = :s and strategy_id = :st and broker_account_key = :a and status in ('submitted', 'partial') and coalesce(trim(kis_order_id), '') <> '' and closed_at is null and created_at >= :day",
                        p.addValue("a", accountKey)
                            .addValue("day", Timestamp.from(MarketClock.todayKst(clock).atStartOfDay(MarketClock.KST).toInstant())),
                        Int::class.java,
                    )!!
                    // 판단 뒤 기록된 체결로 장부가 바뀌었으면 낡은 수량으로 정한 주문을 막는다(위 검사를 지난 뒤 종결된 주문 포함).
                    // 위 검사 뒤에 읽는다: 종결 상태와 포지션 반영은 Fills.record 한 트랜잭션이라, 위에서 종결로 본 주문의 체결은 여기 보인다.
                    val ledger = jdbc.queryForObject(
                        "select coalesce(sum(qty), 0) from positions where user_id = :u and broker_account_key = :a and strategy_id = :st and symbol = :s",
                        p, BigDecimal::class.java,
                    )!!
                    if (unknown > 0) {
                        log.warn("접수불명 주문이 있어 새 주문 보류 user={} symbol={}", userId, symbol)
                        null
                    } else if (live > 0) {
                        log.warn("같은 포지션의 미완료 주문이 있어 새 주문 보류 user={} strategy={} symbol={}", userId, strategyId, symbol)
                        null
                    } else if (ledger.compareTo(heldQty) != 0) {
                        log.warn("판단 뒤 보유수량이 바뀌어 새 주문 보류 user={} strategy={} symbol={} 판단={} 장부={}", userId, strategyId, symbol, heldQty, ledger)
                        null
                    } else {
                        orders.saveAndFlush(Order(userId, strategyId, symbol, side, BigDecimal(qty), price, "market", status = OrderStatus.pending, reason = reason, idempotencyKey = idempotencyKey, brokerAccountKey = accountKey))
                    }
                } ?: return null
            } catch (e: DataIntegrityViolationException) {
                log.info("중복 주문 차단(UNIQUE 충돌): {}", idempotencyKey)
                return null
            }

            try {
                val res = broker.placeOrder(symbol, side, qty, 0, "market")
                val orderId = res.orderId?.takeIf { it.isNotBlank() }
                    ?: throw OrderOutcomeUnknown("주문 성공 응답에 주문번호 없음")
                order.kisOrderId = orderId
                order.kisOrderOrgNo = res.orderOrgNo
                order.status = OrderStatus.submitted
                orderTx.executeWithoutResult { orders.saveAndFlush(order) }
            } catch (e: BrokerError) {
                order.status = OrderStatus.rejected
                orderTx.executeWithoutResult { orders.saveAndFlush(order) }
                log.warn("주문 거부됨 {}: {}", idempotencyKey, e.message)
                if (e is OrderNotSent) {
                    try {
                        alerts.publish(
                            userId, strategyId, AlertSeverity.critical,
                            "주문 미전송 — $symbol $side ${grouped(qty)}주(주문 #${order.id}). 증권사로 주문이 전송되지 않았고 이 신호의 주문은 재시도되지 않습니다. 원인: ${e.message}",
                            "order_not_sent",
                        )
                    } catch (alertError: Exception) {
                        log.warn("주문 미전송 알림 발행 실패(무시) {}: {}", idempotencyKey, alertError.message)
                    }
                }
                alerts.publishEvent(linkedMapOf("type" to "order", "user_id" to userId, "order_id" to order.id, "status" to order.status, "symbol" to symbol))
                return order
            } catch (e: Exception) {
                log.error("주문 접수 결과 불명 {} ({} {} {}주)", idempotencyKey, side, symbol, qty, e)
                try {
                    alerts.publish(
                        userId, strategyId, AlertSeverity.critical,
                        "주문 접수 결과 불명 — $symbol $side ${grouped(qty)}주(주문 #${order.id}). " +
                            "증권사 응답을 받지 못해 접수·체결 여부를 알 수 없습니다. 실제 체결됐을 수 있어 재주문하지 않고 대기시킵니다" +
                            "(정합 루프가 잔고로 교차확인). 원인: $e",
                        "order_unconfirmed",
                    )
                } catch (alertError: Exception) {
                    log.warn("접수 불명 알림 발행 실패(무시) {}: {}", idempotencyKey, alertError.message)
                }
                throw e
            }

            // 네트워크 대기를 정합 락 안에 두면 만료 뒤 체결통보와 중복 반영할 수 있다.
            val (fillQty, fillPrice) = resolveFill(broker, order, price)
            val (current, recorded) = redis.withLockOrElse(RedisKeys.reconcileLock(order.id!!), Duration.ofSeconds(RedisKeys.RECONCILE_LOCK_TTL_SECONDS), busy = { return order }) {
                val latest = orders.findById(order.id!!).orElseThrow()
                // 조회 중 다른 경로가 먼저 반영했으면 추가 반영도 그 정합 경로에 맡긴다.
                if (fills.recordedQty(latest.id!!) > 0) return latest
                val recorded = if (fillQty > 0) {
                    tx.execute { fills.record(latest, fillQty, fillPrice, fillQty >= qty, expectedRecordedQty = 0) }
                        ?: return orders.findById(latest.id!!).orElseThrow()
                } else null
                latest to recorded
            }
            if (recorded != null) {
                fills.publishExecution(current, fillQty, fillPrice, recorded)
                log.info("주문 체결 기록: {} {} {}주 @ {} (실제 체결가)", side, symbol, fillQty, fillPrice)
            } else {
                alerts.publishEvent(linkedMapOf("type" to "order", "user_id" to userId, "order_id" to current.id, "symbol" to symbol, "side" to side, "status" to current.status, "kis_order_id" to current.kisOrderId))
                log.info("주문 접수(미체결): {} {} {}주", side, symbol, qty)
            }
            current
        }
    }

    /**
     * 실제 체결수량·평균체결가. 조회 실패는 미체결(0)로 보고 submitted 로 남긴다 — 전량 체결로 간주하면 filled 로 확정돼
     * reconcile 대상에서 빠지고, 실제 미체결이었을 때 장부가 영구 오염된다.
     */
    private fun resolveFill(broker: BrokerClient, order: Order, signalPrice: BigDecimal): Pair<Int, BigDecimal> {
        val id = order.kisOrderId?.takeIf { it.isNotEmpty() } ?: return 0 to signalPrice // 원본 `if not` — 빈 주문번호로 조회하지 않는다
        val info = try {
            broker.getOrderExecution(id, order.symbol, MarketClock.kstDate(order.createdAt))
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
        private const val LOCK_TTL = RedisKeys.ORDER_LOCK_TTL_SECONDS

        /** 결정적 멱등 키 — 같은 신호봉의 같은 주문은 항상 같은 키. */
        fun idempotencyKey(strategyId: Int, symbol: String, side: String, barTs: String) = "s$strategyId:$symbol:$side:$barTs"
    }
}
