package com.quantfolio.backtest

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import kotlin.test.assertEquals

/** 원본 backtest/deflated_sharpe.py 정답지(tools/oracle/gen_dsr.py) 대조. */
class DeflatedSharpeOracleTest {
    private val json = JsonMapper.builder().build()
    private val oracle = Oracle.load("dsr")

    @TestFactory
    fun matchesScipy(): List<DynamicTest> = (0 until oracle.get("cases").size()).map { i ->
        val c = oracle.get("cases").get(i)
        DynamicTest.dynamicTest(c.get("name").asString()) {
            val target = json.convertValue<Map<String, Any?>>(c.get("target"))
            val homo = json.convertValue<List<Map<String, Any?>>>(c.get("homogeneous"))
            val actual = DeflatedSharpe.analyze(target, homo)
            val expected = json.convertValue<Map<String, Any?>>(c.get("out"))
            assertEquals(expected.keys, actual.keys, "키")
            for ((k, e) in expected) {
                val a = actual[k]
                if (e is Number && a is Number && e !is Int) {
                    Oracle.assertClose(doubleArrayOf(e.toDouble()), doubleArrayOf(a.toDouble()), 1e-9, k)
                } else if (e is Number && a is Number) {
                    assertEquals(e.toDouble(), a.toDouble(), k)
                } else {
                    assertEquals(e, a, k)
                }
            }
        }
    }

    @Test
    fun grades() {
        val g = oracle.get("grades")
        for (i in 0 until g.size()) {
            val x = g.get(i).get(0)
            assertEquals(g.get(i).get(1).asString(), DeflatedSharpe.grade(if (x.isNull) null else x.asDouble()))
        }
    }
}
