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
            val name = run.get("name").asString()
            if (name !in DELIST_DIVERGES) return@dynamicTest Oracle.assertDeep(run.get("out"), out, rel = 1e-8)
            // 의도적 차이(migration-notes #10): 000110 의 중간 결측(22거래일)을 원본은 재개를 미리 알고 동결, Kotlin 은 ffill 한도를
            // 넘긴 날(DELIST_DAY) 상장폐지로 현금화한다. 그 전날까지는 원본 정답지와 같아야 한다.
            for (key in listOf("equity_curve", "trades", "markers")) {
                val expected = items(run.get("out").get(key)).filter { it.get("t").asString().take(10) < DELIST_DAY }
                @Suppress("UNCHECKED_CAST")
                val actual = (out[key] as List<Map<String, Any?>>).filter { (it["t"] as String).take(10) < DELIST_DAY }
                Oracle.assertDeep(json.valueToTree(expected), actual, rel = 1e-8, path = "$name.$key")
            }
            @Suppress("UNCHECKED_CAST")
            val delist = (out["markers"] as List<Map<String, Any?>>).filter { it["type"] == "delist" && it["symbol"] == "000110" }
            kotlin.test.assertEquals(listOf(DELIST_DAY), delist.map { (it["t"] as String).take(10) }, name)
        }
    }

    private companion object {
        /** 000110 결측(2024-05-06~06-04) 중 ffill 10일을 넘긴 첫날 — 이 종목을 그때 보유하는 시나리오. */
        const val DELIST_DAY = "2024-05-20"
        val DELIST_DIVERGES = setOf("all_inverse_vol", "score_vol_gate", "risk_caps", "limit_kill")
    }
}
