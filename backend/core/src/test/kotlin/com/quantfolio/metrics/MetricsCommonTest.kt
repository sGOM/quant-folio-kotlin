package com.quantfolio.metrics

import com.quantfolio.quant.Num
import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 원본 metrics/common.py 정답지(tools/oracle/gen_common.py) 대조. */
class MetricsCommonTest {
    private val oracle = Oracle.load("metrics_common")

    private fun input(name: String): Series {
        val node = oracle.get("inputs").get(name)
        return Series((0 until node.get("index").size()).map { LocalDate.parse(node.get("index").get(it).asString()) }, Oracle.doubles(node.get("values")))
    }

    private fun expected(section: String, key: String): Double? =
        oracle.get(section).get(key).let { if (it.isNull) null else Oracle.num(it) }

    @Test
    fun mddAndVolMatchPython() {
        for (name in listOf("normal", "gappy", "ties")) {
            val s = input(name)
            assertEquals(expected("mdd", name)!!, MetricsCommon.computeMdd(s)!!, 1e-12, "mdd $name")
            assertEquals(expected("vol", name)!!, MetricsCommon.computeVolAnn(s)!!, 1e-9, "vol $name")
            assertNull(MetricsCommon.computeMdd(Series(s.index.take(1), s.values.copyOf(1))))
            assertNull(MetricsCommon.computeVolAnn(Series(s.index.take(19), s.values.copyOf(19))))
        }
    }

    @Test
    fun rampWavgApproxStart() {
        val ramp = oracle.get("ramp")
        for (i in 0 until ramp.size()) {
            val r = ramp.get(i)
            val actual = MetricsCommon.ramp(Oracle.num(r.get(0)), r.get(1).asDouble(), r.get(2).asDouble())
            val exp = if (r.get(3).isNull) null else r.get(3).asDouble()
            assertEquals(exp, actual, "ramp $r")
        }
        assertEquals(30.0, MetricsCommon.wavg(listOf(1.0 to 10.0, 3.0 to null, 2.0 to 40.0)))
        assertNull(MetricsCommon.wavg(listOf(1.0 to null)))
        val starts = oracle.get("approx_start")
        listOf(0, 20, 252).forEachIndexed { i, b ->
            assertEquals(LocalDate.parse(starts.get(i).asString()), MetricsCommon.approxStart(LocalDate.of(2026, 10, 19), b))
        }
        assertEquals(listOf("KOSPI", "KOSDAQ"), MetricsCommon.mkts("all"))
        assertEquals(listOf("KOSDAQ"), MetricsCommon.mkts("kosdaq"))
        assertEquals("20261019", MetricsCommon.ymd(LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun numSafety() {
        assertNull(Num.safe(Double.NaN))
        assertNull(Num.safe(Double.POSITIVE_INFINITY))
        assertNull(Num.safe(null))
        assertEquals(1.5, Num.safe(1.5))
    }
}
