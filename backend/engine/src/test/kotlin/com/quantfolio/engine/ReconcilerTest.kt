package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.Fill
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 미체결·접수불명 주문 정합(원본 `engine/reconcile.py`) — 증분만 기록, 접수불명은 잔고로만 회수·못 밝히면 알림. */
@SpringBootTest
@Import(ContainersConfig::class)
class ReconcilerTest(
    @Autowired private val reconciler: Reconciler,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val redis: StringRedisTemplate,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials

    private val broker = OrderExecutorTest.FakeBroker()
    private var balance = Balance()
    private val wrapped = object : com.quantfolio.broker.BrokerClient by broker {
        override fun getBalance() = balance
    }

    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")

    private fun setup(): Pair<Int, Int> {
        willReturn(wrapped).given(brokers).forUser(anyUser())
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x"))
        val s = strategies.save(Strategy(u.id!!, "s", config = mapOf("type" to "rsi")))
        return u.id!! to s.id!!
    }

    private fun order(uid: Int, sid: Int, symbol: String, qty: Int, status: String, odno: String?, createdAt: Instant = Instant.now(), side: String = "buy") =
        orders.save(Order(uid, sid, symbol, side, BigDecimal(qty), BigDecimal("1000"), status = status, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), createdAt = createdAt))

    private fun recorded(o: Order) = executions.findAll().filter { it.orderId == o.id }.sumOf { it.filledQty.toInt() }

    @Test
    fun recordsOnlyTheIncrementAndIsIdempotent() {
        val (uid, sid) = setup()
        val sym = "9" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val o = order(uid, sid, sym, 3, "submitted", "ODNO-A")
        broker.execution = { Fill(1, BigDecimal("1010"), false) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, recorded(o))
        assertEquals("partial", orders.findById(o.id!!).get().status)
        broker.execution = { Fill(3, BigDecimal("1020"), true) }
        repeat(2) { reconciler.reconcileOpenOrders(uid) }
        assertEquals(3, recorded(o)) // 반복해도 중복 가산 없음
        assertEquals("filled", orders.findById(o.id!!).get().status)
    }

    @Test
    fun statusOnlyDriftIsCorrected() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 2, "submitted", "ODNO-B")
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        broker.execution = { Fill(2, BigDecimal("1000"), true) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(2, recorded(o))
        assertEquals("filled", orders.findById(o.id!!).get().status)
    }

    @Test
    fun paperBalanceFallbackAssignsOnlyUnexplainedHoldings() {
        val (uid, sid) = setup()
        val sym = "8" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val earlier = order(uid, sid, sym, 2, "filled", "ODNO-OLD")
        executions.save(Execution(earlier.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        val o = order(uid, sid, sym, 5, "submitted", "ODNO-C")
        broker.execution = { Fill(0, null, false) } // 모의투자 일별체결조회는 늘 빈 응답
        balance = Balance(listOf(mapOf("pdno" to sym, "hldg_qty" to "5", "pchs_avg_pric" to "1005")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(3, recorded(o)) // 보유 5 − 다른 주문이 설명한 2
        assertEquals("partial", orders.findById(o.id!!).get().status)
    }

    @Test
    fun stalePendingIsRecoveredFromBalanceOrEscalatedNeverReordered() {
        val (uid, sid) = setup()
        val old = Instant.now().minusSeconds(600)
        val visible = "7" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val recoverable = order(uid, sid, visible, 4, "pending", null, old)
        val lost = order(uid, sid, "000001", 3, "pending", null, old)
        val sid2 = strategies.save(Strategy(uid, "fresh", config = mapOf("type" to "rsi"))).id!!
        val fresh = order(uid, sid2, "000002", 1, "pending", null) // 아직 전송 중일 수 있음 — 유예 안
        balance = Balance(listOf(mapOf("pdno" to visible, "hldg_qty" to "4")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(4, recorded(recoverable))
        assertEquals("filled", orders.findById(recoverable.id!!).get().status)
        assertEquals("pending", orders.findById(lost.id!!).get().status) // 확정하지 않는다
        val alert = alerts.findAll().single { it.code == "order_unconfirmed" && it.strategyId == sid }
        assertEquals(
            "주문 접수 불명 상태 지속 — 000001 buy 3주(주문 #${lost.id}). 증권사 주문번호가 없어 체결조회로 확인할 수 없습니다. 증권사 계좌에서 실제 체결 여부를 직접 확인하세요(이중 주문 위험 때문에 엔진은 재주문하지 않습니다).",
            alert.message,
        )
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, alerts.findAll().count { it.code == "order_unconfirmed" && it.strategyId == sid }) // 6h dedup
        assertEquals(0, broker.placed) // 정합은 어떤 경우에도 주문을 내지 않는다
        assertEquals(0, recorded(fresh))
        assertTrue(alerts.findAll().none { it.strategyId == sid2 }) // 유예 중인 주문은 건드리지도 알리지도 않는다
    }

    @Test
    fun lockedOrdersAreSkippedAndBrokerFailuresCount() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 1, "submitted", "ODNO-D")
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        broker.execution = { Fill(1, BigDecimal("1000"), true) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(0, recorded(o))
        redis.delete(RedisKeys.reconcileLock(o.id!!))

        given(brokers.forUser(anyUser())).willThrow(BrokerError("자격증명 없음"))
        val stats = reconciler.reconcileOpenOrders(uid)
        assertTrue(stats.getValue("errors") >= 1)
    }
}
