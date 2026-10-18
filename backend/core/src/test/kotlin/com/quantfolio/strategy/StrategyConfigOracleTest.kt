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
        DynamicTest.dynamicTest(c.get("name").asString()) {
            val input = json.convertValue<Map<String, Any?>>(c.get("input"))
            val result = runCatching { StrategyConfigParser.parse(input) }
            if (c.get("ok").asBoolean()) {
                val expected = json.convertValue<Map<String, Any?>>(c.get("dump"))
                val actual = result.getOrElse { fail("검증 실패: ${it.message}") }
                assertEquals(normalize(expected), normalize(actual))
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
