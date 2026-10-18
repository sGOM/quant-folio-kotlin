package com.quantfolio.market.ohlcv

import com.quantfolio.domain.TestApp
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class UniverseBuilderTest(
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val users: UserRepository,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)

    @Test
    fun unionOfMembersAndStrategiesSorted() {
        val user = users.save(User(email = "${UUID.randomUUID()}@u.dev", passwordHash = "h"))
        strategies.save(Strategy(userId = user.id!!, name = "r", config = mapOf("type" to "rebalance", "universe" to listOf("900002", "900001"))))
        strategies.save(Strategy(userId = user.id!!, name = "s", config = mapOf("type" to "sma_crossover", "symbol" to "900003")))
        val builder = UniverseBuilder({ asOf, idx ->
            check(asOf == LocalDate.of(2026, 10, 19) && idx == "KOSPI200")
            listOf("900004", "900001")
        }, strategies, clock)
        val codes = builder.build()
        assertTrue(codes.containsAll(listOf("900001", "900002", "900003", "900004")))
        assertTrue(codes == codes.sorted())
        assertTrue(codes.count { it == "900001" } == 1)
    }

    @Test
    fun memberLookupFailureIsExcludedNotFatal() {
        val user = users.save(User(email = "${UUID.randomUUID()}@u.dev", passwordHash = "h"))
        strategies.save(Strategy(userId = user.id!!, name = "s", config = mapOf("symbol" to "900005")))
        val codes = UniverseBuilder({ _, _ -> throw SourceAuthError("krx", "차단") }, strategies, clock).build()
        assertTrue("900005" in codes)
    }
}
