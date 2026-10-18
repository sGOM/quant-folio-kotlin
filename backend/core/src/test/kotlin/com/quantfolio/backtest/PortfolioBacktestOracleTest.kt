package com.quantfolio.backtest

import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate

/** 리밸런싱 백테스트 시나리오 정답지(tools/oracle/gen_portfolio.py → portfolio.json) 대조. */
class PortfolioBacktestOracleTest {
    private val json = JsonMapper.builder().build()
    private val data = Oracle.load("portfolio")

    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }
    private val dates = items(data.get("dates")).map { LocalDate.parse(it.asString()) }
    private val symbols = data.get("panel").propertyNames().toList()
    private fun panelOf(n: JsonNode) = Panel(dates, symbols, symbols.associateWith { Oracle.doubles(n.get(it)) })
    private val panel = panelOf(data.get("panel"))
    private val volume = panelOf(data.get("volume"))
    private val regime = Series(dates, Oracle.doubles(data.get("regime")))
    private val bench = Series(dates, Oracle.doubles(data.get("bench")))
    private val panic = data.get("panic").let { p ->
        dates.indices.map { i ->
            PanicDay(dates[i], 0.0, p.get("level").get(i).asString(), false, p.get("hard_trigger").get(i).asBoolean(),
                Oracle.num(p.get("close").get(i)), Oracle.num(p.get("low").get(i)), -0.05)
        }
    }
    private val fund: Map<String, Map<String, Any?>> = json.convertValue(data.get("fund"))
    private val poolSwitch = LocalDate.parse(data.get("pool_switch").asString())
    private val sim = items(data.get("sim")).map { LocalDate.parse(it.asString()) }

    @TestFactory
    fun scenarios() = items(data.get("runs")).map { run ->
        DynamicTest.dynamicTest(run.get("name").asString()) {
            val opt = run.get("opt")
            fun on(k: String) = opt.has(k) && opt.get(k).asBoolean()
            val out = PortfolioBacktest.run(
                panel, json.convertValue(run.get("config")), sim[0], sim[1],
                fundamentals = if (on("fund")) { _, codes -> codes.filter { it in fund }.associateWith { fund.getValue(it) } } else null,
                regime = if (on("regime")) regime else null,
                pool = if (on("pool")) { d -> if (d.isBefore(poolSwitch)) symbols.take(9) else symbols.drop(4) } else null,
                benchmark = if (on("bench")) bench else null,
                panic = if (on("panic")) panic else null,
                volume = if (on("volume")) volume else null,
            )
            Oracle.assertDeep(run.get("out"), out, rel = 1e-8)
        }
    }
}
