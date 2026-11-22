package com.quantfolio.strategy

import kotlin.test.Test
import kotlin.test.assertEquals

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
}
