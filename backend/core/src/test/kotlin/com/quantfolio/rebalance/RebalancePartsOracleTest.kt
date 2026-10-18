package com.quantfolio.rebalance

import com.quantfolio.backtest.Attribution
import com.quantfolio.backtest.RiskCaps
import com.quantfolio.backtest.ScoreSnapshot
import com.quantfolio.backtest.Slippage
import com.quantfolio.quant.Panel
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 리밸런싱 부품 정답지(tools/oracle/gen_portfolio.py → rebalance_parts.json) 대조. */
class RebalancePartsOracleTest {
    private val json = JsonMapper.builder().build()
    private val parts = Oracle.load("rebalance_parts")
    private val data = Oracle.load("portfolio")
    private val dates = items(data.get("dates")).map { LocalDate.parse(it.asString()) }
    private val symbols = data.get("panel").propertyNames().toList()
    private val panel = Panel(dates, symbols, symbols.associateWith { Oracle.doubles(data.get("panel").get(it)) })

    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }

    private fun doubleMap(n: JsonNode): Map<String, Double> = n.propertyNames().associateWith { Oracle.num(n.get(it)) }

    @Test
    fun capNormalize() {
        for (c in parts.get("cap_normalize")) {
            val out = Rebalance.capNormalize(doubleMap(c.get("raw")), c.get("lo").asDouble(), c.get("hi").asDouble())
            Oracle.assertDeep(c.get("out"), out)
        }
    }

    @Test
    fun orders() {
        for (c in parts.get("orders")) {
            val positions = c.get("positions").propertyNames().associateWith { c.get("positions").get(it).asInt() }
            val prices = c.get("prices").propertyNames().associateWith { c.get("prices").get(it).let { p -> if (p.isNull) null else p.asDouble() } }
            val out = Rebalance.orders(doubleMap(c.get("targets")), positions, prices, c.get("capital").asDouble(), c.get("band").asDouble())
            Oracle.assertDeep(c.get("out"), out.map { listOf(it.first, it.second, it.third) })
        }
    }

    @Test
    fun periodKey() {
        for (c in parts.get("period_key")) {
            val key = Rebalance.periodKey(LocalDate.parse(c.get("d").asString()), c.get("cadence").asString())
            Oracle.assertDeep(c.get("out"), if (key is LocalDate) key.toString() else key)
        }
    }

    @Test
    fun volGate() {
        for (c in parts.get("vol_gate")) {
            val s = panel.column(c.get("symbol").asString()).dropNa()
            assertEquals(c.get("out").asBoolean(), Rebalance.passesVolGate(s, json.convertValue(c.get("gate"))), c.toString())
        }
    }

    @Test
    fun riskCaps() {
        val hist = panel.upTo(dates.get(299))
        val sector = data.get("fund").propertyNames().associateWith { data.get("fund").get(it).get("sector").asString() }
        for (c in parts.get("risk")) {
            val tg = doubleMap(c.get("targets"))
            Oracle.assertDeep(c.get("pos"), RiskCaps.capPosition(tg, 0.15), path = "pos")
            Oracle.assertDeep(c.get("sector"), RiskCaps.capSector(tg, sector - symbols[7], 0.45), path = "sector")
            Oracle.assertDeep(c.get("vol"), RiskCaps.portfolioVolAnn(hist, tg, 20), path = "vol")
            val risk = mapOf("max_position_pct" to 0.2, "max_sector_pct" to 0.5, "target_vol" to 0.1, "max_leverage" to 1.5)
            Oracle.assertDeep(c.get("all"), RiskCaps.apply(tg, hist, risk, sector), path = "all")
        }
    }

    @Test
    fun slippage() {
        Oracle.assertDeep(parts.get("slip"), Slippage.volMap(panel.upTo(dates.get(299)), symbols, 0.0005, 0.7))
    }

    @Test
    fun attribution() {
        val a = parts.get("attribution")
        val snaps = items(a.get("snaps")).map { s ->
            val f = s.get("frame")
            ScoreSnapshot(LocalDate.parse(s.get("d").asString()), symbols, f.propertyNames().associateWith { Oracle.doubles(f.get(it)) })
        }
        Oracle.assertDeep(a.get("out"), Attribution.factorAttribution(snaps, panel))
    }

    @Test
    fun riskAdjusted() {
        val r = parts.get("radj")
        val rets = Oracle.doubles(r.get("rets"))
        Oracle.assertDeep(r.get("out"), Attribution.riskAdjusted(rets, Oracle.doubles(r.get("bench")), 0.03))
        Oracle.assertDeep(r.get("out_nobench"), Attribution.riskAdjusted(rets, null, 0.0))
    }
}
