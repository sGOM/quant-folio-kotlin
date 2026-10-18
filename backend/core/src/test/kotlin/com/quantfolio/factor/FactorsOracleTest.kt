package com.quantfolio.factor

import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 원본 metrics/factors.py 순수 계산부 정답지(tools/oracle/gen_factors.py) 대조. */
class FactorsOracleTest {
    private val json = JsonMapper.builder().build()
    private val oracle = Oracle.load("factors")
    private val f = oracle.get("frame")
    private val codes = json.convertValue<List<String>>(f.get("index"))
    private val frame = FactorFrame(
        codes,
        f.properties().filter { (k, _) -> k != "index" && k != "sector" }.associate { (k, v) -> k to Oracle.doubles(v) },
        json.convertValue<List<String?>>(f.get("sector")),
    )

    @TestFactory
    fun stockScores(): List<DynamicTest> = (0 until oracle.get("cases").size()).map { i ->
        val c = oracle.get("cases").get(i)
        DynamicTest.dynamicTest("${c.get("weights").asString()} ${c.get("neutralize").asString()}") {
            val weights = if (c.get("weights").asString() == "all") {
                mapOf("momentum" to 0.2, "value" to 0.1, "lowvol" to 0.1, "quality" to 0.15, "growth" to 0.1, "flow" to 0.1, "residual_momentum" to 0.15, "pead" to 0.1)
            } else {
                null
            }
            val out = FactorScores.compute(frame, weights, c.get("neutralize").asString())
            c.get("out").properties().forEach { (col, values) ->
                Oracle.assertClose(Oracle.doubles(values), out.getValue(col), 1e-9, col)
            }
        }
    }

    @Test
    fun winsorize() {
        val w = oracle.get("winsor")
        for (col in listOf("PBR", "mom_6m", "pead_sue")) {
            Oracle.assertClose(Oracle.doubles(w.get(col).get("plain")), FactorScores.winsorizeZ(frame.col(col)), 1e-12, col)
            Oracle.assertClose(Oracle.doubles(w.get(col).get("invert")), FactorScores.winsorizeZ(frame.col(col), invert = true), 1e-12, col)
        }
        Oracle.assertClose(Oracle.doubles(w.get("few")), FactorScores.winsorizeZ(doubleArrayOf(1.0, Double.NaN, 2.0)))
        Oracle.assertClose(Oracle.doubles(w.get("const")), FactorScores.winsorizeZ(doubleArrayOf(5.0, 5.0, 5.0, Double.NaN)))
    }

    @Test
    fun residualMomentum() {
        val p = oracle.get("panel")
        val idx = (0 until p.get("index").size()).map { LocalDate.parse(p.get("index").get(it).asString()) }
        val market = Series(idx, Oracle.doubles(p.get("market")))
        val closes = p.properties().filter { (k, _) -> k != "index" && k != "market" }.associate { (k, v) -> k to Series(idx, Oracle.doubles(v)) }
        val cases = oracle.get("resid_mom")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val out = ResidualMomentum.compute(
                closes, market, LocalDate.parse(c.get("as_of").asString()),
                regWindow = c.get("reg_window").asInt(), momWindow = c.get("mom_window").asInt(), skip = c.get("skip").asInt(),
            )
            val expected = json.convertValue<Map<String, Double>>(c.get("out"))
            assertEquals(expected.keys, out.keys, "codes ${c.get("as_of")}")
            for ((k, v) in expected) Oracle.assertClose(doubleArrayOf(v), doubleArrayOf(out.getValue(k)), 1e-9, k)
        }
    }
}
