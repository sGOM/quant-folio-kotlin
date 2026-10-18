package com.quantfolio.metrics

import com.quantfolio.market.store.IndexBar
import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 원본 metrics 순수 계산부 정답지(tools/oracle/gen_metrics_pure.py) 대조. */
class MetricsPureOracleTest {
    private val json = JsonMapper.builder().build()
    private val oracle = Oracle.load("metrics_pure")
    private val snake = JsonMapper.builder().propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE).build()

    private fun days(n: Int) = generateSequence(LocalDate.of(2023, 1, 2)) { d -> d.plusDays(if (d.dayOfWeek.value == 5) 3 else 1) }.take(n).toList()

    /** 기대값 맵(JSON)과 실제 객체(snake_case 직렬화)를 키별로 비교한다. */
    private fun assertSame(expected: JsonNode, actual: Any?, label: String) {
        val e = json.convertValue<Map<String, Any?>>(expected)
        val a = snake.convertValue<Map<String, Any?>>(actual!!)
        for ((k, v) in e) {
            val av = a[k]
            if (v is Number && av is Number) Oracle.assertClose(doubleArrayOf(v.toDouble()), doubleArrayOf(av.toDouble()), 1e-9, "$label.$k")
            else assertEquals(v, av, "$label.$k")
        }
    }

    @Test
    fun techIndicators() {
        val cases = oracle.get("tech")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val close = Oracle.doubles(c.get("close"))
            assertSame(c.get("out"), TechIndicators.of(Series(days(close.size), close)), c.get("name").asString())
        }
    }

    @Test
    fun sectorOne() {
        val cases = oracle.get("sector")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val close = Oracle.doubles(c.get("close"))
            val tv = Oracle.doubles(c.get("trading_value"))
            val bars = days(close.size).mapIndexed { k, d -> IndexBar(d, close = close[k], tradingValue = tv[k].toLong()) }
            val ref = if (c.get("ref").isNull) null else c.get("ref").asDouble()
            val m = SectorMetric.of("전기전자", "KOSPI", bars, ref)
            if (c.get("out").isNull) assertEquals(null, m) else assertSame(c.get("out"), m, c.get("name").asString())
        }
    }

    @Test
    fun panicScoring() {
        val cases = oracle.get("panic")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val ix = c.get("index")
            val close = Oracle.doubles(ix.get("close"))
            val high = Oracle.doubles(ix.get("high"))
            val low = Oracle.doubles(ix.get("low"))
            val tv = Oracle.doubles(ix.get("trading_value"))
            val bars = days(close.size).mapIndexed { k, d -> IndexBar(d, high = high[k], low = low[k], close = close[k], tradingValue = tv[k].toLong()) }
            val sig = PanicScoring.indexSignals(bars)
            val chg = Oracle.doubles(c.get("breadth").get("chg"))
            val vol = Oracle.doubles(c.get("breadth").get("vol"))
            sig.putAll(PanicScoring.breadthSignals(chg.indices.map { chg[it] to vol[it] }))
            sig["nlr"] = if (c.get("nlr").isNull) null else c.get("nlr").asDouble()
            val es = json.convertValue<Map<String, Any?>>(c.get("signals"))
            for ((k, v) in es) {
                val a = sig[k]
                if (v == null) assertEquals(null, a, "sig $i.$k") else Oracle.assertClose(doubleArrayOf((v as Number).toDouble()), doubleArrayOf(a!!), 1e-6, "sig $i.$k")
            }
            val r = PanicScoring.scoreAndLabel(c.get("market").asString(), sig)
            val er = c.get("result")
            assertEquals(er.get("level").asString(), r.level, "level $i")
            assertEquals(er.get("gated").asBoolean(), r.gated, "gated $i")
            assertEquals(er.get("hard_trigger").asBoolean(), r.hardTrigger, "hard $i")
            Oracle.assertClose(doubleArrayOf(er.get("score").asDouble()), doubleArrayOf(r.score), 1e-6, "score $i")
            for (k in r.sub.keys) {
                val e = er.get(k)
                if (e.isNull) assertEquals(null, r.sub[k], "$i.$k") else Oracle.assertClose(doubleArrayOf(e.asDouble()), doubleArrayOf(r.sub[k]!!), 1e-6, "$i.$k")
            }
        }
    }

    @Test
    fun vulnerability() {
        val cases = oracle.get("vuln")
        for (i in 0 until cases.size()) {
            val c = cases.get(i)
            val credit = json.convertValue<List<Double?>>(c.get("credit"))
            val forced = json.convertValue<List<Double?>>(c.get("forced"))
            val r = VulnerabilityScoring.score(credit, forced)
            val e = c.get("out")
            assertEquals(e.get("exposure_scale").asDouble(), r.exposureScale, "exposure $i")
            if (e.get("score").isNull) assertEquals(null, r.score) else Oracle.assertClose(doubleArrayOf(e.get("score").asDouble()), doubleArrayOf(r.score!!), 1e-9, "score $i")
            for (k in listOf("credit_z", "credit_growth", "forced_sale")) {
                val ev = e.get("signals").get(k)
                if (ev.isNull) assertEquals(null, r.signals[k], "$i.$k") else Oracle.assertClose(doubleArrayOf(ev.asDouble()), doubleArrayOf(r.signals[k]!!), 1e-9, "$i.$k")
            }
        }
    }
}
