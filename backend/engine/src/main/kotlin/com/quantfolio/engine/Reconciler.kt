package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.sql.Timestamp
import java.text.DecimalFormat
import java.time.Clock
import java.time.Duration

/**
 * 미체결(submitted/partial)·접수불명(pending, 주문번호 없음) 주문을 실제 체결로 정합(원본 `engine/reconcile.py`).
 *
 * - 이미 기록된 체결(executions 합)을 빼고 **증분만** 기록 — 몇 번을 돌려도 중복 가산이 없다.
 * - 체결 소스: ① 일별주문체결조회(실전 정확) ② 잔고 폴백(모의투자는 ①이 늘 빈 응답 — 매수만 판정 가능).
 * - 접수불명은 주문번호가 없어 **잔고 교차확인으로만** 회수, 못 밝히면 critical 알림(자동 재주문은 이중 매수라 절대 안 함).
 *   전송 중인 주문을 오판하지 않게 5분 유예 후에만 대상.
 * - 주문 단위 분산 락으로 다른 경로(수동 스크립트·실시간 체결통보)와 직렬화.
 */
@Component
class Reconciler(
    private val orders: OrderRepository,
    private val users: UserRepository,
    private val brokers: BrokerCredentials,
    private val fills: Fills,
    private val alerts: AlertPublisher,
    private val redis: StringRedisTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val props: QuantfolioProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("engine.reconcile")

    fun reconcileOpenOrders(userId: Int? = null): Map<String, Int> {
        val p = MapSqlParameterSource("grace", Timestamp.from(clock.instant().minus(Duration.ofSeconds(PENDING_GRACE_SECONDS)))).addValue("u", userId)
        val userFilter = if (userId != null) " and user_id = :u" else ""
        val open = jdbc.queryForList("select id from orders where status in ('submitted', 'partial') and kis_order_id is not null and kis_order_id <> ''$userFilter order by id", p, Int::class.java)
        val stale = jdbc.queryForList("select id from orders where status = 'pending' and (kis_order_id is null or kis_order_id = '') and created_at < :grace$userFilter order by id", p, Int::class.java)
        val ids = LinkedHashSet<Int>().apply { addAll(open.filterNotNull()); addAll(stale.filterNotNull()) }
        val stats = linkedMapOf("checked" to 0, "filled" to 0, "partial" to 0, "errors" to 0)
        if (ids.isEmpty()) return stats

        val brokerCache = HashMap<Int, BrokerClient?>()
        val balanceCache = HashMap<Int, Map<String, Pair<Int, BigDecimal?>>>()

        fun broker(uid: Int): BrokerClient? = brokerCache.getOrPut(uid) {
            val user = users.findById(uid).orElse(null) ?: return@getOrPut null
            try {
                brokers.forUser(user)
            } catch (e: BrokerError) {
                log.warn("브로커 생성 실패 user={}: {}", uid, e.message)
                stats["errors"] = stats.getValue("errors") + 1 // 자격증명 만료가 조용히 넘어가지 않게
                null
            }
        }

        fun balance(uid: Int, b: BrokerClient) = balanceCache.getOrPut(uid) {
            try {
                b.getBalance().positionsNormalized().associate { it.symbol to (it.qty to it.avgPrice) }
            } catch (e: BrokerError) {
                log.warn("잔고 조회 실패 user={}: {}", uid, e.message)
                emptyMap()
            }
        }

        fun apply(order: Order, delta: Int, price: BigDecimal, fully: Boolean) {
            tx.executeWithoutResult { fills.record(order, delta, price, fully) }
            stats[if (fully) "filled" else "partial"] = stats.getValue(if (fully) "filled" else "partial") + 1
            alerts.publishEvent(
                linkedMapOf(
                    "type" to "execution", "user_id" to order.userId, "order_id" to order.id, "symbol" to order.symbol, "side" to order.side,
                    "qty" to delta, "price" to price.toDouble(), "status" to order.status, "kis_order_id" to order.kisOrderId,
                ),
            )
            log.info("체결 정합: {} {} +{}주 @ {} → {}", order.side, order.symbol, delta, price, order.status)
        }

        /** 잔고 보유수량으로 이 주문의 체결분 배정 — 같은 종목 다른 주문이 이미 설명한 수량은 뺀다. 반영했으면 true. */
        fun balanceCrossCheck(order: Order, b: BrokerClient, target: Int, already: Int): Boolean {
            if (!props.kis.isPaperTrading || order.side != "buy") return false
            val (held, avg) = balance(order.userId, b)[order.symbol] ?: return false
            if (held <= 0) return false
            val available = held - symbolRecorded(order.userId, order.symbol)
            if (available <= 0) return false
            val delta = minOf(target - already, available)
            if (delta <= 0) return false
            apply(order, delta, avg?.takeIf { it.signum() > 0 } ?: order.price!!, already + delta >= target)
            return true
        }

        fun alertUnconfirmed(order: Order) {
            log.warn("접수 불명 주문 미해소 — order={} {} {} {}주(주문번호 없음)", order.id, order.symbol, order.side, order.qty)
            alerts.publish(
                order.userId, order.strategyId ?: 0, "critical",
                "주문 접수 불명 상태 지속 — ${order.symbol} ${order.side} ${DecimalFormat("#,##0").format(order.qty.toInt())}주(주문 #${order.id}). " +
                    "증권사 주문번호가 없어 체결조회로 확인할 수 없습니다. 증권사 계좌에서 실제 체결 여부를 직접 확인하세요(이중 주문 위험 때문에 엔진은 재주문하지 않습니다).",
                "order_unconfirmed", dedupWindowHours = UNCONFIRMED_ALERT_DEDUP_HOURS,
            )
        }

        fun processOne(order: Order) {
            val b = broker(order.userId) ?: return
            stats["checked"] = stats.getValue("checked") + 1
            val target = order.qty.toInt()
            val already = orderRecorded(order.id!!)
            val odno = order.kisOrderId
            if (odno.isNullOrEmpty()) {
                if (!balanceCrossCheck(order, b, target, already)) alertUnconfirmed(order)
                return
            }
            var kisFilled = -1
            var avg: BigDecimal? = null
            try {
                val info = b.getOrderExecution(odno, order.symbol)
                kisFilled = info.filledQty
                avg = info.avgPrice
            } catch (e: BrokerError) {
                log.debug("체결 재조회 실패 order={}: {}", order.id, e.message)
                stats["errors"] = stats.getValue("errors") + 1
            }
            if (kisFilled > 0) {
                val delta = kisFilled - already
                val fully = kisFilled >= target
                if (delta > 0) {
                    apply(order, delta, avg?.takeIf { it.signum() > 0 } ?: order.price!!, fully)
                } else {
                    val status = if (fully) "filled" else "partial"
                    if (order.status != status) {
                        order.status = status
                        orders.save(order)
                    }
                }
                return
            }
            balanceCrossCheck(order, b, target, already)
        }

        for (id in ids) {
            val lock = RedisKeys.reconcileLock(id)
            if (redis.opsForValue().setIfAbsent(lock, "1", Duration.ofSeconds(RedisKeys.RECONCILE_LOCK_TTL_SECONDS)) != true) continue
            try {
                orders.findById(id).ifPresent { processOne(it) }
            } finally {
                redis.delete(lock)
            }
        }
        return stats
    }

    private fun symbolRecorded(userId: Int, symbol: String): Int = jdbc.queryForObject(
        "select coalesce(sum(e.filled_qty), 0) from executions e join orders o on e.order_id = o.id where o.user_id = :u and o.symbol = :s",
        MapSqlParameterSource("u", userId).addValue("s", symbol), BigDecimal::class.java,
    )!!.toInt()

    private fun orderRecorded(orderId: Int): Int = jdbc.queryForObject(
        "select coalesce(sum(filled_qty), 0) from executions where order_id = :o", MapSqlParameterSource("o", orderId), BigDecimal::class.java,
    )!!.toInt()

    private companion object {
        const val PENDING_GRACE_SECONDS = 300L
        const val UNCONFIRMED_ALERT_DEDUP_HOURS = 6.0
    }
}
