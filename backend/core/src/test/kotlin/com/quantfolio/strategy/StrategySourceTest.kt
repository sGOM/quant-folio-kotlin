package com.quantfolio.strategy

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StrategySourceTest {
    @Test
    fun `schema accepts KOSPI_MCAP and pick as N`() {
        val cfg = mapOf(
            "type" to "rebalance",
            "selection" to mapOf(
                "method" to "momentum", "top_n" to 5,
                "universe_rule" to mapOf("source" to "KOSPI_MCAP", "pick" to 50),
            ),
        )
        @Suppress("UNCHECKED_CAST")
        val rule = ((StrategyConfigParser.parse(cfg)["selection"] as Map<String, Any?>)["universe_rule"]) as Map<String, Any?>
        assertEquals("KOSPI_MCAP", rule["source"])
        assertEquals(50, rule["pick"])
    }

    private fun rebalance(rule: Map<String, Any?>) = mapOf(
        "type" to "rebalance",
        "selection" to mapOf("method" to "momentum", "top_n" to 5, "universe_rule" to rule),
    )

    @Test
    fun `removed index sources are rejected`() {
        for (src in listOf("KOSPI200", "KOSPI100", "KRX300")) {
            assertFailsWith<StrategyConfigException>(src) { StrategyConfigParser.parse(rebalance(mapOf("source" to src, "pick" to 50))) }
        }
    }

    @Test
    fun `pick above pool size is rejected`() {
        val e = assertFailsWith<StrategyConfigException> {
            StrategyConfigParser.parse(rebalance(mapOf("source" to "KOSPI_MCAP", "pick" to 50, "pool_size" to 40)))
        }
        assertTrue(e.errors.any { "universe_rule.pick 은 pool_size 이하여야 합니다." in it.msg }, e.errors.toString())
    }

    @Test
    fun `pool size defaults to null and accepts up to 300`() {
        @Suppress("UNCHECKED_CAST")
        fun rule(r: Map<String, Any?>) = ((StrategyConfigParser.parse(rebalance(r))["selection"] as Map<String, Any?>)["universe_rule"]) as Map<String, Any?>
        assertEquals(null, rule(mapOf("source" to "KOSPI_MCAP", "pick" to 5))["pool_size"])
        assertEquals(300, rule(mapOf("source" to "KOSPI_MCAP", "pick" to 5, "pool_size" to 300))["pool_size"])
        assertFailsWith<StrategyConfigException> { StrategyConfigParser.parse(rebalance(mapOf("source" to "KOSPI_MCAP", "pool_size" to 301))) }
    }
}
