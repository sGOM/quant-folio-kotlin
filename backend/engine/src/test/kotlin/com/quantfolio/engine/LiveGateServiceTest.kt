package com.quantfolio.engine

import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals

@SpringBootTest
@Import(ContainersConfig::class)
class LiveGateServiceTest(
    @Autowired private val gate: LiveGateService,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
) {
    @MockitoBean lateinit var master: KisStockMaster

    private fun d(s: String) = BigDecimal(s)

    /** 원본은 당일 체결 명목만 더해 미체결 주문만큼 일일 상한을 넘겨 체결될 수 있었다(migration-notes #16③). */
    @Test
    fun dailyUsedAddsOutstandingNotionalOfTodaysOpenOrders() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        fun order(qty: String, price: String, status: OrderStatus, filled: String? = null, createdAt: Instant = Instant.now()) {
            val o = orders.save(Order(uid, sid, "005930", "buy", d(qty), d(price), status = status, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY, createdAt = createdAt))
            if (filled != null) executions.save(Execution(o.id!!, sid, d(filled), d(price), accountPositionQty = d(filled)))
        }
        order("10", "1000", OrderStatus.filled, filled = "10") // 체결 10,000
        order("10", "1000", OrderStatus.partial, filled = "4") // 체결 4,000 + 잔량 6,000
        order("5", "2000", OrderStatus.submitted) // 잔량 10,000
        order("7", "1000", OrderStatus.cancelled) // 제외
        order("9", "1000", OrderStatus.submitted, createdAt = Instant.now().minusSeconds(3 * 86400)) // 지난 주문 — 당일 상한 밖
        val details = gate.evaluate(uid, sid, d("1"), paper = true).details
        @Suppress("UNCHECKED_CAST")
        assertEquals(30_000.0, (details["order_notional"] as Map<String, Any?>)["daily_used"])
    }
}
