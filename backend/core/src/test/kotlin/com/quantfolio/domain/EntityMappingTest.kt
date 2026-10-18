package com.quantfolio.domain

import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import jakarta.persistence.EntityManager
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.assertEquals

@SpringBootApplication(scanBasePackages = ["com.quantfolio"])
class TestApp

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
@Transactional
class EntityMappingTest(
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val em: EntityManager,
) {
    @Test
    fun schemaValidates() {
        // ddl-auto=validate 로 컨텍스트가 뜨면 엔티티가 V1 스키마와 일치한다.
    }

    @Test
    fun roundTripsTypes() {
        val user = users.save(User(email = "rt@test.dev", passwordHash = "h"))
        val config = mapOf("a" to 1, "b" to listOf(1.5, null), "c" to mapOf("d" to "한글"))
        val strategy = strategies.save(Strategy(userId = user.id!!, name = "s", config = config))
        val createdAt = Instant.parse("2026-10-19T01:02:03.456789Z")
        val order = orders.save(
            Order(userId = user.id!!, symbol = "005930", side = "buy", qty = BigDecimal("10.0000"),
                idempotencyKey = "k1", createdAt = createdAt),
        )
        val position = positions.save(
            Position(userId = user.id!!, symbol = "005930", qty = BigDecimal("10.0000"), avgPrice = BigDecimal("12345.6789")),
        )
        em.flush()
        em.clear()

        assertEquals(config, strategies.findById(strategy.id!!).get().config)
        assertEquals(BigDecimal("12345.6789"), positions.findById(position.id!!).get().avgPrice)
        assertEquals(createdAt.truncatedTo(ChronoUnit.MICROS), orders.findById(order.id!!).get().createdAt)
    }
}
