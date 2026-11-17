package com.quantfolio.quant

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 원본 pandas 2.2.3 결과(tools/oracle/gen_series.py)와 대조. */
class SeriesOracleTest {
    private val oracle = Oracle.load("series_ops")
    private val inputs = oracle.get("inputs").properties().associate { (name, node) ->
        val index = (0 until node.get("index").size()).map { LocalDate.parse(node.get("index").get(it).asString()) }
        name to Series(index, Oracle.doubles(node.get("values")))
    }

    @TestFactory
    fun matchesPandas(): List<DynamicTest> = (0 until oracle.get("cases").size()).map { i ->
        val c = oracle.get("cases").get(i)
        val s = inputs.getValue(c.get("series").asString())
        val p = c.get("params")
        val op = c.get("op").asString()
        DynamicTest.dynamicTest("$i ${c.get("series").asString()} $op $p") {
            fun mp() = if (p.get("min_periods").isNull) p.get("window").asInt() else p.get("min_periods").asInt()
            val actual: DoubleArray = when (op) {
                "rolling_mean" -> s.rolling(p.get("window").asInt(), mp()).mean().values
                "rolling_std" -> s.rolling(p.get("window").asInt(), mp()).std().values
                "rolling_max" -> s.rolling(p.get("window").asInt(), mp()).max().values
                "rolling_min" -> s.rolling(p.get("window").asInt(), mp()).min().values
                "rolling_sum" -> s.rolling(p.get("window").asInt(), mp()).sum().values
                "ewm_mean" -> s.ewm(span = p.get("span").asDouble(), adjust = p.get("adjust").asBoolean()).mean().values
                "ewm_mean_alpha" -> s.ewm(alpha = p.get("alpha").asDouble(), adjust = p.get("adjust").asBoolean()).mean().values
                "shift" -> s.shift(p.get("n").asInt()).values
                "diff" -> s.diff(p.get("n").asInt()).values
                "pct_change" -> s.pctChange(p.get("n").asInt()).values
                "ffill" -> s.ffill(if (p.get("limit").isNull) null else p.get("limit").asInt()).values
                "cumsum" -> s.cumSum().values
                "expanding_max" -> s.expandingMax().values
                "rank" -> s.rank(RankMethod.valueOf(p.get("method").asString().uppercase()), p.get("pct").asBoolean()).values
                "quantile" -> doubleArrayOf(s.quantile(p.get("q").asDouble()))
                "mean" -> doubleArrayOf(s.mean())
                "std" -> doubleArrayOf(s.std())
                "sum" -> doubleArrayOf(s.sum())
                else -> error("unknown op $op")
            }
            // 누적 연산(cumsum·ewm)은 합산 순서 차이로 상대 1e-9 를 넘을 수 있어 1e-6 까지 허용(설계 §8.1).
            val rel = if (op in setOf("cumsum", "ewm_mean", "ewm_mean_alpha", "rolling_std", "rolling_sum", "rolling_mean")) 1e-6 else 1e-9
            Oracle.assertClose(Oracle.doubles(c.get("out")), actual, rel, "$op $p")
        }
    }

    @Test
    fun comparisonsAndBool() {
        val idx = (1..4).map { LocalDate.of(2026, 1, it) }
        val a = Series(idx, doubleArrayOf(1.0, Double.NaN, 3.0, 4.0))
        val gt = a gt 2.0
        assertEquals(listOf(false, false, true, true), gt.values.toList()) // NaN 비교는 false
        assertEquals(listOf(false, false, false, true), gt.shift(1).values.toList())
        assertTrue((gt and gt.not()).values.none { it })
    }
}
