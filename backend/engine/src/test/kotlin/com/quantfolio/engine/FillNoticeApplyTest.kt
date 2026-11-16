package com.quantfolio.engine

import com.quantfolio.broker.BrokerAccountKey
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.Fill
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 실시간 체결통보 → 그 주문 하나의 정합(원본 `fill_notice.apply_fill_notice` 와 다르다 — docs/migration-notes.md).
 * 통보의 CNTG_QTY 는 체결 건별(2026-11-16 모의 실측)이고 체결 고유번호가 없어, 통보 수량은 장부에 쓰지 않는다 —
 * 수량·단가는 가짜 증권사의 단건 체결조회 누적에서 온다.
 */
@SpringBootTest
@Import(ContainersConfig::class)
class FillNoticeApplyTest(
    @Autowired private val notices: FillNoticeApplier,
    @Autowired private val reconciler: Reconciler,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val redis: StringRedisTemplate,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials

    private val broker = OrderExecutorTest.FakeBroker()

    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")

    private fun user(): Pair<Int, Int> {
        willReturn(broker).given(brokers).forUser(anyUser())
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        return uid to strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
    }

    private fun submitted(qty: Int, side: String = "buy", price: BigDecimal? = BigDecimal("70000")): Triple<Int, Order, String> {
        val (uid, sid) = user()
        val odno = "N" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", side, BigDecimal(qty), price, status = OrderStatus.submitted, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        return Triple(uid, o, odno)
    }

    private fun recorded(o: Order) = executions.findAll().filter { it.orderId == o.id }.sumOf { it.filledQty.toInt() }

    private fun notice(odno: String, qty: Int, side: String = "buy", price: String = "4265") = FillNotice(odno, "005930", qty, BigDecimal(price), side, TEST_KIS_ACCOUNT)

    @Test
    fun noticeMatchesItsAccountEvenWithReusedOrderNumber() {
        val (uid, sid) = user()
        fun submitted(key: String?) = orders.save(Order(uid, sid, "005930", "buy", BigDecimal.ONE, BigDecimal("1000"),
            status = OrderStatus.submitted, kisOrderId = "123", idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = key))
        val legacy = submitted(null)
        val old = submitted(BrokerAccountKey.kis("87654321-01", paper = true))
        val current = submitted(TEST_KIS_ACCOUNT_KEY)
        broker.execution = { Fill(1, BigDecimal("1000"), true) }

        assertFalse(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy")))
        assertFalse(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy", TEST_KIS_ACCOUNT + "bad")))
        assertTrue(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy", TEST_KIS_ACCOUNT)))
        assertEquals(listOf(current.id), executions.findAll().filter { it.orderId in setOf(legacy.id, old.id, current.id) }.map { it.orderId })
    }

    /** 실측 시퀀스: 한 주문(300주)의 건별 통보 1,1,2,7 — 그 사이 증권사 누적은 1,2,4,11. 통보 수량을 더하지도, 누적으로 오독하지도 않는다. */
    @Test
    fun perFillNoticesRecordBrokerCumulativeIncrementsOnly() {
        val (uid, o, odno) = submitted(300)
        var cumulative = 0
        broker.execution = { Fill(cumulative, BigDecimal("4265"), false) }

        for ((perFill, brokerTotal) in listOf(1 to 1, 1 to 2, 2 to 4, 7 to 11)) {
            cumulative = brokerTotal
            assertTrue(notices.apply(uid, notice(odno, perFill)))
            assertEquals(brokerTotal, recorded(o))
        }

        assertEquals(11, recorded(o))
        assertEquals(listOf(1, 1, 2, 7), executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }.map { it.filledQty.toInt() })
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
    }

    /** 실측: 같은 초에 수량·단가가 같은 통보 3건(별개 체결). 통보로는 중복과 구분할 수 없다 — 기록은 증권사 누적을 넘지 않는다. */
    @Test
    fun identicalNoticesNeverRecordBeyondBrokerCumulative() {
        val (uid, o, odno) = submitted(300)
        broker.execution = { Fill(6, BigDecimal("4265"), false) }

        repeat(3) { notices.apply(uid, notice(odno, 2)) }
        assertEquals(6, recorded(o))
        repeat(3) { notices.apply(uid, notice(odno, 2)) } // 재전송·중복이어도
        assertEquals(6, recorded(o))
        assertEquals(1, executions.findAll().count { it.orderId == o.id })
    }

    /** 통보가 REST 누적보다 먼저 왔다(누적이 아직 0) — 통보 수량으로 기록하지 않고, 뒤의 주기 정합이 누적으로 잡는다. */
    @Test
    fun noticeAheadOfBrokerCumulativeIsLeftToLaterReconcile() {
        val (uid, o, odno) = submitted(10, side = "sell")
        var cumulative = 0
        broker.execution = { Fill(cumulative, if (cumulative > 0) BigDecimal("4205") else null, cumulative >= 10) }

        assertTrue(notices.apply(uid, notice(odno, 2, side = "sell")))
        assertEquals(0, recorded(o))
        assertEquals(OrderStatus.submitted, orders.findById(o.id!!).get().status)

        cumulative = 10
        reconciler.reconcileOpenOrders(uid)
        assertEquals(10, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    /** 정합이 먼저 누적을 다 반영한 뒤 늦게 온 통보는 아무것도 더하지 않는다. */
    @Test
    fun lateNoticeAfterReconcileAddsNothing() {
        val (uid, o, odno) = submitted(10)
        broker.execution = { Fill(4, BigDecimal("4265"), false) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(4, recorded(o))

        for (perFill in listOf(2, 2)) notices.apply(uid, notice(odno, perFill))

        assertEquals(4, recorded(o))
        assertEquals(1, executions.findAll().count { it.orderId == o.id })
    }

    /** 실측: 부분 취소 뒤의 체결 통보는 ODER_QTY 가 300 → 50 으로 줄어 온다. 그 수량으로 "전량 체결"을 판정하지 않는다. */
    @Test
    fun noticeAfterPartialCancelKeepsOrderPartial() {
        val (uid, o, odno) = submitted(300)
        broker.execution = { Fill(50, BigDecimal("4265"), false) }
        val frame = MutableList(23) { "" }.apply {
            this[1] = TEST_KIS_ACCOUNT; this[2] = odno; this[4] = "02"; this[8] = "005930"; this[9] = "0000000002"; this[10] = "4265"
            this[12] = "0"; this[13] = "2"; this[14] = "2"; this[16] = "0000000050"
        }.joinToString("^")
        val n = KisFrames.parseFillNotice(frame)!!
        assertEquals(2, n.qty)

        assertTrue(notices.apply(uid, n))

        assertEquals(50, recorded(o))
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
    }

    /** 통보 단가는 쓰지 않는다 — 단가는 증권사 누적 평균가에서, 그것도 없으면 주문가(정합과 같은 규칙), 둘 다 없으면 기록하지 않는다. */
    @Test
    fun priceComesFromBrokerCumulativeNotFromNotice() {
        val (uid, o, odno) = submitted(1)
        broker.execution = { Fill(1, BigDecimal("70100"), true) }
        notices.apply(uid, notice(odno, 1, price = "1"))
        assertEquals(BigDecimal("70100.0000"), executions.findAll().single { it.orderId == o.id }.filledPrice)

        val (uid2, unpriced, odno2) = submitted(1, price = null)
        broker.execution = { Fill(1, null, true) }
        notices.apply(uid2, notice(odno2, 1, price = "70100"))
        assertEquals(0, recorded(unpriced))
        assertEquals(OrderStatus.submitted, orders.findById(unpriced.id!!).get().status)
    }

    /** 다른 경로가 같은 주문 락으로 반영 중이면 기다리지 않고 넘긴다 — 그 체결은 주기 정합이 잡는다. */
    @Test
    fun busyOrderLockSkipsWithoutWaitingAndReconcileCatchesUp() {
        val (uid, o, odno) = submitted(3)
        broker.execution = { Fill(3, BigDecimal("70100"), true) }
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        val started = System.nanoTime()
        notices.apply(uid, notice(odno, 3))
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "락을 기다리지 않아야 한다")
        assertEquals(0, recorded(o))
        redis.delete(RedisKeys.reconcileLock(o.id!!))

        reconciler.reconcileOpenOrders(uid)
        assertEquals(3, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    /** 전량 기록된 주문(filled)은 정합 대상이 아니다 — 같은 주문의 늦은 통보마다 증권사를 다시 조회하지 않는다. */
    @Test
    fun noticeForFullyRecordedOrderDoesNotQueryBroker() {
        val (uid, o, odno) = submitted(2)
        var lookups = 0
        broker.execution = { lookups++; Fill(2, BigDecimal("70100"), true) }
        notices.apply(uid, notice(odno, 1))
        assertEquals(2, recorded(o))
        assertEquals(1, lookups)

        notices.apply(uid, notice(odno, 1))

        assertEquals(1, lookups)
        assertEquals(2, recorded(o))
    }

    private fun pendingOrder(side: String, createdAt: Instant = Instant.now()): Pair<Int, Order> {
        val (uid, sid) = user()
        return uid to orders.save(Order(uid, sid, "005930", side, BigDecimal.ONE, BigDecimal("70000"), "market", status = OrderStatus.pending, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY, createdAt = createdAt))
    }

    /** 시장가는 POST 응답(주문번호 커밋)보다 통보가 먼저 올 수 있다 — 전송 중 주문이 있으면 커밋을 잠깐 기다린다. */
    @Test
    fun noticeArrivingBeforeOrderNumberCommitWaitsForIt() {
        val (uid, o) = pendingOrder("sell")
        val odno = "E" + UUID.randomUUID().toString().take(8)
        broker.execution = { Fill(1, BigDecimal("70100"), true) }
        Thread.startVirtualThread {
            Thread.sleep(400)
            o.kisOrderId = odno
            o.status = OrderStatus.submitted
            orders.save(o)
        }

        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "sell", TEST_KIS_ACCOUNT)))

        assertEquals(1, executions.findAll().count { it.orderId == o.id })
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    /** 전송 중 주문이 없으면(HTS 직접 주문 등) 기다리지 않는다 — 통보 스레드가 무관한 통보마다 멈추면 안 된다. */
    @Test
    fun unmatchedNoticeWithoutInFlightOrderIsDroppedImmediately() {
        val (uid, _) = pendingOrder("buy") // 방향이 다른 전송 중 주문은 기다릴 이유가 아니다
        val started = System.nanoTime()
        assertFalse(notices.apply(uid, FillNotice("H" + UUID.randomUUID().toString().take(8), "005930", 1, BigDecimal("70100"), "sell", TEST_KIS_ACCOUNT)))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2000, "기다리지 않아야 한다: ${ms}ms")
    }

    /** 접수불명으로 남은 오래된 pending 주문 때문에 무관한 통보가 매번 기다리면 안 된다. */
    @Test
    fun unmatchedNoticeWithStaleInFlightOrderIsDroppedImmediately() {
        // TTL 바로 너머로 잡아 "오늘" 범위가 아니라 최근 조건만 시험한다.
        val ago = RedisKeys.ORDER_LOCK_TTL_SECONDS + 5
        val (uid, _) = pendingOrder("buy", todayOrderTime(ago, ago)) // 어제 주문이면 "오늘 범위" 때문에 버려져 최근 조건을 못 시험한다
        val started = System.nanoTime()
        assertFalse(notices.apply(uid, FillNotice("S" + UUID.randomUUID().toString().take(8), "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT)))
        val ms = (System.nanoTime() - started) / 1_000_000
        assertTrue(ms < 2000, "기다리지 않아야 한다: ${ms}ms")
    }

    /** 전송 중 주문에 끝내 주문번호가 붙지 않으면 상한 뒤 버린다(번호 없는 주문은 정합의 접수불명 경로로만 — 재주문 없음). */
    @Test
    fun noticeGivesUpWhenInFlightOrderNeverGetsItsNumber() {
        val (uid, o) = pendingOrder("buy")
        val started = System.nanoTime()
        assertFalse(notices.apply(uid, FillNotice("G" + UUID.randomUUID().toString().take(8), "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT)))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1000, "상한까지 재시도해야 한다")
        assertEquals(0, executions.findAll().count { it.orderId == o.id })
        assertEquals(0, broker.placed)
    }
}
