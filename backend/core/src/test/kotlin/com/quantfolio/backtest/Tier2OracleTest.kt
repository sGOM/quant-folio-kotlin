package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.TestFactory
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate

/**
 * 2층(실데이터) 정답지 — `tools/oracle/tier2.sh` 가 로컬 DB price_ticks 로 만든 산출물을 재생한다.
 * 실데이터라 저장소에 없다(gitignore). 산출물이 없으면 건너뛴다(CI).
 */
class Tier2OracleTest {
    private val dir = Path.of("../../tools/oracle/tier2")
    private val json = JsonMapper.builder().build()

    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }

    @TestFactory
    fun realData(): List<DynamicTest> {
        assumeTrue(Files.exists(dir.resolve("tier2.json")) && Files.exists(dir.resolve("prices.csv")), "2층 정답지 없음 — tools/oracle/tier2.sh 로 생성")
        val rows = Files.readAllLines(dir.resolve("prices.csv")).map { it.split(",") }
        val frames = rows.groupBy { it[0] }.mapValues { (_, r) ->
            val idx = r.map { LocalDate.parse(it[1]) }
            fun col(k: Int) = Series(idx, DoubleArray(r.size) { r[it][k].toDouble() })
            listOf(col(2), col(3), col(4), col(5), col(6))
        }
        val oracle = json.readTree(dir.resolve("tier2.json").toFile())
        val single = items(oracle.get("single")).map { c ->
            DynamicTest.dynamicTest("single ${c.get("symbol").asString()} ${c.get("config").asString()}") {
                val cfg = json.convertValue<Map<String, Any?>>(oracle.get("single_configs").get(c.get("config").asString()))
                val (o, h, l, cl, v) = frames.getValue(c.get("symbol").asString()).map { it.slice(LocalDate.of(2021, 1, 1)) }
                val data = if (Signals.requiresOhlc(cfg)) PriceData.ohlcv(o, h, l, cl, v) else PriceData.close(cl)
                Oracle.assertDeep(c.get("result"), SingleBacktest.run(data, cfg), rel = 1e-7)
            }
        }
        val panel = Panel.fromSeries(frames.mapValues { it.value[3] }.toSortedMap())
        val volume = Panel.fromSeries(frames.mapValues { it.value[4] }.toSortedMap())
        val proxyDates = items(oracle.get("proxy_dates")).map { LocalDate.parse(it.asString()) }
        val proxy = Series(proxyDates, Oracle.doubles(oracle.get("proxy")))
        val port = items(oracle.get("port")).map { c ->
            DynamicTest.dynamicTest("portfolio ${c.get("config").asString()}") {
                val cfg = json.convertValue<Map<String, Any?>>(oracle.get("port_configs").get(c.get("config").asString()))
                val out = PortfolioBacktest.run(panel, cfg, LocalDate.of(2021, 1, 4), panel.dates.last(), regime = proxy, benchmark = proxy, volume = volume)
                // ls_return 은 원본 numpy quicksort 의 동점 순서에 좌우된다(migration-notes 의도적 차이) — 그 항목만 뺀다.
                val expected = c.get("out").deepCopy() as tools.jackson.databind.node.ObjectNode
                expected.get("factor_ic").forEach { (it as tools.jackson.databind.node.ObjectNode).remove("ls_return") }
                @Suppress("UNCHECKED_CAST")
                val ic = (out["factor_ic"] as Map<String, Map<String, Any?>>).mapValues { it.value - "ls_return" }
                Oracle.assertDeep(expected, out + ("factor_ic" to ic), rel = 1e-7)
            }
        }
        return single + port
    }
}
