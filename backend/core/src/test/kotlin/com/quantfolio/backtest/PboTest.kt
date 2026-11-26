package com.quantfolio.backtest

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** algorithm/screen_stats.py `pbo_cscv` 정답지(합성 입력, 원본 Python 아님) 대조. */
class PboTest {
    private val oracle = Oracle.load("screen_pbo")

    @TestFactory
    fun matchesPython(): List<DynamicTest> = (0 until oracle.get("cases").size()).map { i ->
        val c = oracle.get("cases").get(i)
        DynamicTest.dynamicTest(c.get("name").asString()) {
            val rows = c.get("m")
            val m = Array(rows.size()) { r -> Oracle.doubles(rows.get(r)) }
            assertEquals(Oracle.num(c.get("pbo")), Pbo.cscv(m, c.get("s").asInt())!!, 1e-12)
        }
    }

    @Test
    fun `single trial or too few months is null`() {
        assertNull(Pbo.cscv(Array(50) { doubleArrayOf(0.01 * it) }))
        assertNull(Pbo.cscv(Array(7) { doubleArrayOf(0.01, 0.02) }))
    }

    private fun result(vararg pts: Pair<String, Double>) = mapOf<String, Any?>("equity_curve" to pts.map { mapOf("t" to it.first, "v" to it.second) })

    @Test
    fun `monthly matrix keeps only months every trial has`() {
        val a = result("2020-01-02T00:00:00" to 100.0, "2020-01-31T00:00:00" to 110.0, "2020-02-28T00:00:00" to 99.0, "2020-03-31T00:00:00" to 99.0)
        val b = result("2020-02-03T00:00:00" to 50.0, "2020-02-28T00:00:00" to 55.0, "2020-03-31T00:00:00" to 66.0)
        val m = Pbo.monthlyMatrix(listOf(a, b))
        assertEquals(2, m.size) // 2월·3월만 공통
        assertEquals(-0.1, m[0][0], 1e-12)
        assertEquals(0.1, m[0][1], 1e-12)
        assertEquals(0.2, m[1][1], 1e-12)
    }
}
