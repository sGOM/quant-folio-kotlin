package com.quantfolio.engine

import com.quantfolio.broker.BrokerAccountKey
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실시간 체결통보 반영(원본 `fill_notice.apply_fill_notice`) — 누적 대비 델타만, reconcile 과 같은 주문 락. */
@SpringBootTest
@Import(ContainersConfig::class)
class FillNoticeApplyTest(
    @Autowired private val notices: FillNoticeApplier,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @Test
    fun noticeMatchesItsAccountEvenWithReusedOrderNumber() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        fun submitted(key: String?) = orders.save(Order(uid, sid, "005930", "buy", BigDecimal.ONE, BigDecimal("1000"),
            status = OrderStatus.submitted, kisOrderId = "123", idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = key))
        val legacy = submitted(null)
        val old = submitted(BrokerAccountKey.kis("87654321-01", paper = true))
        val current = submitted(TEST_KIS_ACCOUNT_KEY)

        assertFalse(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy")))
        assertFalse(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy", TEST_KIS_ACCOUNT + "bad")))
        assertTrue(notices.apply(uid, FillNotice("123", "005930", 1, BigDecimal("1000"), "buy", TEST_KIS_ACCOUNT)))
        assertEquals(listOf(current.id), executions.findAll().filter { it.orderId in setOf(legacy.id, old.id, current.id) }.map { it.orderId })
    }

    @Test
    fun appliesCumulativeDeltaOnceAndRespectsReconcileLock() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        val odno = "N" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", "buy", BigDecimal("3"), BigDecimal("70000"), status = OrderStatus.submitted, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        fun recorded() = executions.findAll().filter { it.orderId == o.id }.sumOf { it.filledQty.toInt() }

        assertFalse(notices.apply(uid, FillNotice("NOPE", "005930", 1, BigDecimal("1"), "buy", TEST_KIS_ACCOUNT))) // 매칭 주문 없음
        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT)))
        assertEquals(1, recorded())
        assertFalse(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT))) // 같은 누적 → 중복 스킵
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        assertFalse(notices.apply(uid, FillNotice(odno, "005930", 3, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT))) // reconcile 이 정합 중 — 스킵
        redis.delete(RedisKeys.reconcileLock(o.id!!))
        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 3, BigDecimal("0"), "buy", TEST_KIS_ACCOUNT))) // 단가 0 → 주문가 폴백
        assertEquals(3, recorded())
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
        assertEquals(BigDecimal("70000.0000"), executions.findAll().filter { it.orderId == o.id }.maxBy { it.id!! }.filledPrice)
    }

    @Test
    fun noticeWaitsBrieflyForBusyReconcileLockInsteadOfDroppingTheFill() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        val odno = "W" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", "sell", BigDecimal.ONE, BigDecimal("70000"), status = OrderStatus.submitted, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        Thread.startVirtualThread { Thread.sleep(400); redis.delete(RedisKeys.reconcileLock(o.id!!)) }

        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "sell", TEST_KIS_ACCOUNT)))

        assertEquals(1, executions.findAll().count { it.orderId == o.id })
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun noticeWithoutAnyKnownPriceIsNotRecorded() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        val odno = "P" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", "buy", BigDecimal.ONE, null, status = OrderStatus.submitted, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))

        assertFalse(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal.ZERO, "buy", TEST_KIS_ACCOUNT))) // 통보 단가 0 + 주문가 없음 → 단가를 모른다
        assertEquals(0, executions.findAll().count { it.orderId == o.id })
        assertEquals(OrderStatus.submitted, orders.findById(o.id!!).get().status)
        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT))) // 통보 단가가 있으면 주문가는 필요 없다
    }

    private fun pendingOrder(side: String, createdAt: Instant = Instant.now()): Pair<Int, Order> {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        return uid to orders.save(Order(uid, sid, "005930", side, BigDecimal.ONE, BigDecimal("70000"), "market", status = OrderStatus.pending, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY, createdAt = createdAt))
    }

    /** 시장가는 POST 응답(주문번호 커밋)보다 통보가 먼저 올 수 있다 — 전송 중 주문이 있으면 커밋을 잠깐 기다린다. */
    @Test
    fun noticeArrivingBeforeOrderNumberCommitWaitsForIt() {
        val (uid, o) = pendingOrder("sell")
        val odno = "E" + UUID.randomUUID().toString().take(8)
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

    /** 전송 중 주문에 끝내 주문번호가 붙지 않으면 상한 뒤 버린다. */
    @Test
    fun noticeGivesUpWhenInFlightOrderNeverGetsItsNumber() {
        val (uid, o) = pendingOrder("buy")
        val started = System.nanoTime()
        assertFalse(notices.apply(uid, FillNotice("G" + UUID.randomUUID().toString().take(8), "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT)))
        assertTrue((System.nanoTime() - started) / 1_000_000 >= 1000, "상한까지 재시도해야 한다")
        assertEquals(0, executions.findAll().count { it.orderId == o.id })
    }

    /** 락을 기다리는 동안 다른 경로가 바꾼 주문 컬럼을 통보 반영이 낡은 엔티티로 되돌리면 안 된다. */
    @Test
    fun noticeDoesNotRevertColumnsChangedWhileWaitingForLock() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        val odno = "R" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", "buy", BigDecimal.ONE, BigDecimal("70000"), status = OrderStatus.submitted, reason = "before", kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        Thread.startVirtualThread {
            Thread.sleep(400) // 통보가 주문을 읽고 락 재시도 중일 때
            jdbc.update("update orders set reason = 'after' where id = ?", o.id)
            redis.delete(RedisKeys.reconcileLock(o.id!!))
        }

        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy", TEST_KIS_ACCOUNT)))

        assertEquals("after", orders.findById(o.id!!).get().reason)
    }
}
