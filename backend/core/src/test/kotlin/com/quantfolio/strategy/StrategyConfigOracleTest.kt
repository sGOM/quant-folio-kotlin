package com.quantfolio.strategy

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import kotlin.test.assertEquals
import kotlin.test.fail

/** 원본 pydantic 스키마 정답지(tools/oracle/gen_strategy_config.py) 대조. */
class StrategyConfigOracleTest {
    private val json = JsonMapper.builder().build()
    /** 원본 스키마에 없는 Kotlin 전용 필드(원본 정답지엔 없다). 대조에서만 뺀다 — 값은 PortfolioDividendTest 가 검증. */
    private val KOTLIN_ADDED = setOf("dividend_tax")
    /** 정답지(원본)가 쓰는 지수 소스(KOSPI200 등)는 2단계에서 폐지했다 — 원본과 달라지는 의도적 차이. 정답지 JSON 은 고치지 않는다. */
    private val REMOVED_INDEX_SOURCE_CASES = setOf("rebalance_pit_empty_universe") // 2단계: 지수 소스 폐지(migration-notes)
    /** 정답지(원본)는 flow 가중치·창·분모를 스키마 기본값/오류 메시지에 담는다 — 2단계에서 flow 팩터를 삭제했으므로 달라지는 의도적 차이. */
    private val REMOVED_FLOW_CASES = setOf("rebalance_min", "rebalance_full", "rebalance_custom", "weights_sum") // 2단계: flow 팩터 삭제(migration-notes)
    private val cases = Oracle.load("strategy_config").get("cases")

    /** 숫자는 값으로 비교(pydantic 기본값 int 10000000 == 1.0E7), 나머지는 구조 그대로. */
    private fun normalize(v: Any?): Any? = when (v) {
        is Number -> v.toDouble()
        is Map<*, *> -> v.entries.associate { it.key.toString() to normalize(it.value) }
        is List<*> -> v.map { normalize(it) }
        else -> v
    }

    @TestFactory
    fun matchesPydantic(): List<DynamicTest> = (0 until cases.size()).map { i ->
        val c = cases.get(i)
        if (c.get("name").asString() in REMOVED_INDEX_SOURCE_CASES + REMOVED_FLOW_CASES) return@map DynamicTest.dynamicTest("${c.get("name").asString()} (skipped)") {}
        DynamicTest.dynamicTest(c.get("name").asString()) {
            val input = json.convertValue<Map<String, Any?>>(c.get("input"))
            val result = runCatching { StrategyConfigParser.parse(input) }
            if (c.get("ok").asBoolean()) {
                val expected = json.convertValue<Map<String, Any?>>(c.get("dump"))
                val actual = result.getOrElse { fail("검증 실패: ${it.message}") }
                assertEquals(normalize(expected), normalize(actual - KOTLIN_ADDED))
            } else {
                val err = result.exceptionOrNull() as? StrategyConfigException ?: fail("오류를 기대했으나 통과: ${result.getOrNull()}")
                val expected = (0 until c.get("errors").size()).map { k ->
                    val e = c.get("errors").get(k)
                    json.convertValue<List<Any>>(e.get("loc")).map { it.toString() } to e.get("msg").asString()
                }.toSet()
                assertEquals(expected, err.errors.map { e -> e.loc.map { it.toString() } to e.msg }.toSet())
            }
        }
    }
}
