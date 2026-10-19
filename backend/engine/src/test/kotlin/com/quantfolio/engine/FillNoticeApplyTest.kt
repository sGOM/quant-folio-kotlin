package com.quantfolio.engine

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.math.BigDecimal
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
) {
    @Test
    fun appliesCumulativeDeltaOnceAndRespectsReconcileLock() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        val odno = "N" + UUID.randomUUID().toString().take(8)
        val o = orders.save(Order(uid, sid, "005930", "buy", BigDecimal("3"), BigDecimal("70000"), status = "submitted", kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString()))
        fun recorded() = executions.findAll().filter { it.orderId == o.id }.sumOf { it.filledQty.toInt() }

        assertFalse(notices.apply(uid, FillNotice("NOPE", "005930", 1, BigDecimal("1"), "buy"))) // 매칭 주문 없음
        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy")))
        assertEquals(1, recorded())
        assertFalse(notices.apply(uid, FillNotice(odno, "005930", 1, BigDecimal("70100"), "buy"))) // 같은 누적 → 중복 스킵
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        assertFalse(notices.apply(uid, FillNotice(odno, "005930", 3, BigDecimal("70100"), "buy"))) // reconcile 이 정합 중 — 스킵
        redis.delete(RedisKeys.reconcileLock(o.id!!))
        assertTrue(notices.apply(uid, FillNotice(odno, "005930", 3, BigDecimal("0"), "buy"))) // 단가 0 → 주문가 폴백
        assertEquals(3, recorded())
        assertEquals("filled", orders.findById(o.id!!).get().status)
        assertEquals(BigDecimal("70000.0000"), executions.findAll().filter { it.orderId == o.id }.maxBy { it.id!! }.filledPrice)
    }
}
