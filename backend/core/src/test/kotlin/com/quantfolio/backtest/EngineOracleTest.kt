package com.quantfolio.backtest

import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestFactory
import org.junit.jupiter.api.assertThrows
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.LocalDate
import kotlin.test.assertEquals

/** 원본 vectorbt from_signals·run_backtest 정답지(tools/oracle/gen_engine.py) 대조. */
class EngineOracleTest {
    private val json = JsonMapper.builder().build()
    private val oracle = Oracle.load("engine")

    private fun bools(n: JsonNode) = BooleanArray(n.size()) { n.get(it).asBoolean() }
    private fun opt(n: JsonNode?) = if (n == null || n.isNull) null else n.asDouble()
    private fun assertNum(expected: JsonNode, actual: Double?, label: String, rel: Double = 1e-9) =
        Oracle.assertClose(doubleArrayOf(Oracle.num(expected)), doubleArrayOf(actual ?: Double.NaN), rel, label)

    @TestFactory
    fun simMatchesVectorbt(): List<DynamicTest> = (0 until oracle.get("sim").size()).map { i ->
        val c = oracle.get("sim").get(i)
        DynamicTest.dynamicTest("sim $i ${c.get("params")}") {
            val p = c.get("params")
            val close = Oracle.doubles(c.get("close"))
            val slippage = if (c.get("slippage_series").isNull) DoubleArray(close.size) { p.get("slippage").asDouble() } else Oracle.doubles(c.get("slippage_series"))
            val r = FromSignals.simulate(
                close, bools(c.get("entries")), bools(c.get("exits")),
                FromSignals.Params(
                    initCash = p.get("init_cash").asDouble(), fees = p.get("fees").asDouble(), slippage = slippage,
                    slStop = opt(p.get("sl_stop")), slTrail = p.get("sl_trail").asBoolean(), tpStop = opt(p.get("tp_stop")),
                ),
            )
            Oracle.assertClose(Oracle.doubles(c.get("value")), r.value, 1e-9, "value")
            val orders = c.get("orders")
            assertEquals(orders.size(), r.orders.size, "orders 수")
            for (k in 0 until orders.size()) {
                val o = orders.get(k)
                val a = r.orders[k]
                assertEquals(o.get(0).asInt(), a.idx, "order $k idx")
                assertEquals(o.get(4).asInt(), if (a.buy) 0 else 1, "order $k side")
                Oracle.assertClose(doubleArrayOf(o.get(1).asDouble(), o.get(2).asDouble(), o.get(3).asDouble()), doubleArrayOf(a.size, a.price, a.fees), 1e-9, "order $k")
            }
            assertNum(c.get("total_return"), r.totalReturn(), "total_return")
            assertNum(c.get("max_drawdown"), r.maxDrawdown(), "mdd")
            assertNum(c.get("sharpe"), r.sharpe(0.0), "sharpe", 1e-7)
            assertNum(c.get("sharpe_rf"), r.sharpe(0.0001), "sharpe_rf", 1e-7)
            assertNum(c.get("sortino"), r.sortino(0.0001), "sortino", 1e-7)
            assertEquals(c.get("num_trades").asInt(), r.trades.size, "num_trades")
            assertNum(c.get("win_rate"), r.winRate(), "win_rate")
        }
    }

    private fun frame(node: JsonNode): PriceData {
        val idx = (0 until node.get("index").size()).map { LocalDate.parse(node.get("index").get(it).asString()) }
        fun col(c: String) = Series(idx, Oracle.doubles(node.get(c)))
        return PriceData.ohlcv(col("open"), col("high"), col("low"), col("close"), col("volume"))
    }

    @TestFactory
    fun runMatchesPython(): List<DynamicTest> = (0 until oracle.get("run").size()).map { i ->
        val c = oracle.get("run").get(i)
        val name = c.get("config").asString()
        DynamicTest.dynamicTest("run ${c.get("frame").asString()} $name") {
            val cfg = json.convertValue<Map<String, Any?>>(oracle.get("configs").get(name))
            val full = frame(oracle.get("frames").get(c.get("frame").asString()))
            val data = if (Signals.requiresOhlc(cfg)) full else PriceData.close(full.close)
            val expected = c.get("result")
            val actual = SingleBacktest.run(data, cfg)
            for (k in listOf("total_return", "mdd", "sharpe", "sortino", "win_rate")) {
                val e = expected.get(k)
                val a = actual[k] as Double?
                if (e.isNull) assertEquals(null, a, k) else assertNum(e, a, k, 1e-7)
            }
            assertEquals(expected.get("num_trades").asInt(), actual["num_trades"])
            @Suppress("UNCHECKED_CAST")
            val curve = actual["equity_curve"] as List<Map<String, Any?>>
            val eCurve = expected.get("equity_curve")
            assertEquals(eCurve.size(), curve.size, "equity_curve 길이")
            for (k in 0 until eCurve.size()) {
                assertEquals(eCurve.get(k).get("t").asString(), curve[k]["t"], "t[$k]")
                assertNum(eCurve.get(k).get("v"), curve[k]["v"] as Double, "v[$k]")
            }
            @Suppress("UNCHECKED_CAST")
            val markers = actual["markers"] as List<Map<String, Any?>>
            val eMarkers = expected.get("markers")
            assertEquals(eMarkers.size(), markers.size, "markers 수")
            for (k in 0 until eMarkers.size()) {
                assertEquals(eMarkers.get(k).get("t").asString(), markers[k]["t"])
                assertEquals(eMarkers.get(k).get("type").asString(), markers[k]["type"])
                assertNum(eMarkers.get(k).get("price"), markers[k]["price"] as Double, "marker price")
            }
        }
    }

    @Test
    fun shortDataRejected() {
        val full = frame(oracle.get("frames").get("a"))
        val short = PriceData.close(full.close.slice(to = full.close.index[9]))
        val e = assertThrows<SignalConfigError> { SingleBacktest.run(short, mapOf("type" to "sma_crossover")) }
        assertEquals(oracle.get("short_error").asString(), e.message)
    }
}
