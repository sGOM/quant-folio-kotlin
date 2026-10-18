package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 트래킹·체결 정합·캘리브레이션 정답지(tools/oracle/gen_tracking.py → tracking.json) 대조. */
class TrackingOracleTest {
    private val json = JsonMapper.builder().build()
    private val data = Oracle.load("tracking")
    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }
    private val dates: List<LocalDate> = items(data.get("dates")).map { LocalDate.parse(it.asString()) }
    private val symbols = data.get("panel").propertyNames().toList()
    private val panel = Panel(dates, symbols, symbols.associateWith { Oracle.doubles(data.get("panel").get(it)) })

    private fun execs(n: JsonNode) = items(n).map {
        Tracking.Execution(
            LocalDate.parse(it.get("date").asString()), it.get("symbol").asString(), it.get("side").asString(),
            it.get("qty").asDouble(), it.get("price").asDouble(), it.get("fee")?.asDouble(),
        )
    }

    private fun curve(n: JsonNode): List<Map<String, Any?>> = items(n).map { p ->
        mapOf("t" to p.get("t").asString(), "v" to p.get("v").let { if (it.isNull) null else Oracle.num(it) })
    }

    @Test
    fun reconstructAndReplay() {
        val all = execs(data.get("execs"))
        for (c in data.get("reconstruct")) {
            val disp = c.get("display_from").let { if (it.isNull) null else LocalDate.parse(it.asString()) }
            val (curve, notes) = Tracking.reconstructRealizedCurve(all, panel, 10_000_000.0, disp)
            Oracle.assertDeep(c.get("curve"), curve, path = "curve(display=$disp)")
            Oracle.assertDeep(c.get("notes"), notes)
        }
        val (curve, notes) = Tracking.reconstructRealizedCurve(all.take(3), Panel(emptyList(), emptyList(), emptyMap()), 1e6)
        Oracle.assertDeep(data.get("empty_panel").get("curve"), curve)
        Oracle.assertDeep(data.get("empty_panel").get("notes"), notes)
        Oracle.assertDeep(data.get("cash"), Tracking.replayCashBalance(all, 10_000_000.0))
    }

    @Test
    fun compareCurves() {
        val real = curve(data.get("reconstruct").get(0).get("curve"))
        val bt = curve(data.get("bt"))
        Oracle.assertDeep(data.get("compare"), Tracking.compare(real, bt))
        Oracle.assertDeep(data.get("compare_empty"), Tracking.compare(emptyList(), bt))
    }

    @Test
    fun fillQuality() {
        val orders = items(data.get("orders")).map {
            FillQuality.Order(
                it.get("order_id").asInt(), it.get("symbol").asString(), it.get("side").asString(), LocalDate.parse(it.get("fill_date").asString()),
                it.get("p_live").asDouble(), it.get("decision_price")?.asDouble(),
            )
        }
        val params = listOf(Triple(0.0, null, 30), Triple(0.7, 4.0, 30), Triple(0.5, 2.0, 100))
        params.forEachIndexed { i, (scale, turnover, minSample) ->
            val out = FillQuality.compute(orders, panel, 0.0005, scale, minSample = minSample, annualTurnover = turnover)
            Oracle.assertDeep(data.get("fill_quality").get(i), out, path = "fq[$i]")
        }
    }

    @Test
    fun calibration() {
        for (c in data.get("calibration")) {
            val p = SlippageCalibration.propose(json.convertValue(c.get("report")))
            val e = c.get("out")
            if (e.isNull) assertEquals(null, p, c.toString()) else Oracle.assertDeep(
                e, mapOf(
                    "current_bps" to p!!.currentBps, "proposed_bps" to p.proposedBps, "observed_median_bps" to p.observedMedianBps,
                    "sample_size" to p.sampleSize, "clamped" to p.clamped, "reason" to p.reason,
                ),
            )
        }
    }
}
