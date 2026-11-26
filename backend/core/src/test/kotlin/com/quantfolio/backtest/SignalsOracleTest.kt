package com.quantfolio.backtest

import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 원본 backtest/signals.py 정답지(tools/oracle/gen_signals.py) 대조. */
class SignalsOracleTest {
    private val json = JsonMapper.builder().build()
    private val oracle = Oracle.load("signals")

    private fun frame(node: JsonNode): PriceData {
        val idx = (0 until node.get("index").size()).map { LocalDate.parse(node.get("index").get(it).asString()) }
        fun col(c: String) = Series(idx, Oracle.doubles(node.get(c)))
        return PriceData.ohlcv(open = col("open"), high = col("high"), low = col("low"), close = col("close"), volume = col("volume"))
    }

    private val datasets = oracle.get("datasets").properties().associate { (k, v) -> k to frame(v) }

    @TestFactory
    fun matchesPython(): List<DynamicTest> = (0 until oracle.get("cases").size()).map { i ->
        val c = oracle.get("cases").get(i)
        val cfgName = c.get("config").asString()
        DynamicTest.dynamicTest("${c.get("data").asString()} $cfgName") {
            val cfg = json.convertValue<Map<String, Any?>>(oracle.get("configs").get(cfgName))
            val full = datasets.getValue(c.get("data").asString())
            val data = if (Signals.requiresOhlc(cfg)) full else PriceData.close(full.close)
            assertEquals(c.get("requires_ohlc").asBoolean(), Signals.requiresOhlc(cfg))
            // 의도적 차이(migration-notes #17): slow 미지정 ema_crossover 는 신호 계산과 같은 기본 26 — 원본 min_periods 는 20 이었다
            val minPeriods = if (cfg == mapOf("type" to "ema_crossover")) 26 else c.get("min_periods").asInt()
            assertEquals(minPeriods, Signals.minPeriods(cfg))
            val (entries, exits) = Signals.generate(data, cfg)
            assertEquals(json.convertValue<List<Boolean>>(c.get("entries")), entries.values.toList(), "entries")
            assertEquals(json.convertValue<List<Boolean>>(c.get("exits")), exits.values.toList(), "exits")
            assertEquals(c.get("latest").let { if (it.isNull) null else it.asString() }, Signals.latestSignal(data, cfg))
            assertEquals(c.get("explain_buy").asString(), Signals.explain(data, cfg, "buy"))
            assertEquals(c.get("explain_sell").asString(), Signals.explain(data, cfg, "sell"))
            if (c.has("position_state")) {
                Oracle.assertClose(Oracle.doubles(c.get("position_state")), Signals.customPositionState(data, cfg).values)
            }
        }
    }
}
