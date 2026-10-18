package com.quantfolio.backtest

import com.quantfolio.quant.pyJsonDumps
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import kotlin.test.assertEquals

/** universe_fingerprint 정답지(tools/oracle/gen_fingerprint.py) — Python json.dumps(sort_keys) 바이트 동일. */
class FingerprintOracleTest {
    private val json = JsonMapper.builder().build()

    @Test
    fun matchesPython() {
        val cases = Oracle.load("fingerprint")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val universe = json.convertValue<List<String>>(c.get("universe"))
            val rule = if (c.get("rule").isNull) null else json.convertValue<Map<String, Any?>>(c.get("rule"))
            assertEquals(c.get("dumps").asString(), pyJsonDumps(mapOf("universe" to universe.sorted(), "universe_rule" to (rule ?: emptyMap<String, Any?>())), sortKeys = true), "dumps[$i]")
            assertEquals(c.get("fp").asString(), BacktestRunner.universeFingerprint(universe, rule), "fp[$i]")
        }
    }
}
