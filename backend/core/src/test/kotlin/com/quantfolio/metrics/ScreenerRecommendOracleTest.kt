package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 스크리너·추천 조립부 정답지(tools/oracle/gen_screener.py → screener.json) 대조. 외부 조회는 정답지와 같은 고정 데이터. */
class ScreenerRecommendOracleTest {
    private val json = JsonMapper.builder().build()
    private val snake = JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build()
    private val data = Oracle.load("screener")
    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }
    private val asOf = LocalDate.parse(data.get("as_of").asString())
    private val codes = items(data.get("codes")).map { it.asString() }

    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val common = MetricsCommon(MarketCalendar({ true }, { emptyMap() }, clock), clock)
    private val catalog = SymbolCatalog { throw SourceAuthError("kis_master", "x") }

    private fun num(n: JsonNode) = Oracle.num(n).takeIf { !it.isNaN() }

    private val caps = data.get("cap").let { c ->
        codes.indices.map { i ->
            StockDaily(codes[i], market = c.get("market").get(i).asString(), marketCap = num(c.get("market_cap").get(i))?.toLong(), shares = num(c.get("shares").get(i))?.toLong())
        }
    }
    private val fund = data.get("fund").let { f -> codes.indices.map { i -> StockDaily(codes[i], per = num(f.get("PER").get(i)), pbr = num(f.get("PBR").get(i)), div = num(f.get("DIV").get(i))) } }
    private val pcByKey = data.get("pc").propertyNames().associateWith { k ->
        val p = data.get("pc").get(k)
        items(p.get("codes")).mapIndexed { i, c ->
            StockPeriod(
                c.asString(), name = p.get("name").get(i).let { if (it.isNull) null else it.asString() }, changePct = num(p.get("chg").get(i)),
                close = num(p.get("close").get(i)), tradingValue = num(p.get("value").get(i))?.toLong(), volume = num(p.get("volume").get(i))?.toLong(),
            )
        }
    }
    private val startKey = mapOf(
        MetricsCommon.approxStart(asOf, 5, buffer = 4) to "5", MetricsCommon.approxStart(asOf, 60) to "60", asOf.minusDays(1) to "1",
        MetricsCommon.approxStart(asOf, 21) to "21", MetricsCommon.approxStart(asOf, 63) to "63", MetricsCommon.approxStart(asOf, 126) to "126",
    )
    private val src = object : SnapshotSource {
        override fun fundamentals(asOf: LocalDate, markets: List<String>) = fund
        override fun marketCap(asOf: LocalDate, markets: List<String>) = caps
        override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>) = pcByKey.getValue(startKey.getValue(start))
        override fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>? = null
        override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? = null
        override fun sectorIndexes(market: String, day: LocalDate) = emptyList<Pair<String, String>>()
    }
    private val days = items(data.get("days")).map { LocalDate.parse(it.asString()) }
    private val tech = TechIndicatorLoader { code, _, _ ->
        val closes = data.get("closes").get(code)
        if (closes == null || code.endsWith("3")) throw IllegalStateException("no data")
        days.indices.map { i -> Bar(days[i].atStartOfDay().toInstant(ZoneOffset.UTC), 0.0, 0.0, 0.0, closes.get(i).asDouble(), 0) }
    }
    private val qm: Map<String, Map<String, Any?>> = json.convertValue(data.get("qm"))

    private fun inputs(enabled: Boolean, metrics: Map<String, Map<String, Any?>>?) = object : FactorInputs {
        override fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean) = metrics ?: throw SourceAuthError("opendart", "x")
        override fun peadSue(codes: List<String>, asOf: LocalDate, lookbackQ: Int) = emptyMap<String, Double>()
        override fun marketCaps(asOf: LocalDate) = emptyMap<String, Long>()
        override fun sectorMap(asOf: LocalDate) = emptyMap<String, String>()
        override fun dartEnabled() = enabled
    }

    @Test
    fun turnaroundScreener() {
        for (s in data.get("screens")) {
            val kw = s.get("kw")
            fun d(k: String, def: Double) = if (kw.has(k)) kw.get(k).asDouble() else def
            val svc = ScreenerService(src, inputs(s.get("enabled").asBoolean(), if (s.get("qm_fail").asBoolean()) null else if (s.get("enabled").asBoolean()) qm else emptyMap()), catalog, common)
            val out = svc.screenTurnaround(
                asOf, "ALL", topN = if (kw.has("top_n")) kw.get("top_n").asInt() else 20, smallcapPct = d("smallcap_pct", 0.2), minValue = d("min_value", 3e8),
                surge = d("surge", 1.5), maxDebt = d("max_debt", 1.5), maxCandidates = if (kw.has("max_candidates")) kw.get("max_candidates").asInt() else 60,
            )
            Oracle.assertDeep(s.get("out"), snake.convertValue<Map<String, Any?>>(out), path = "screen$kw")
        }
    }

    @Test
    fun kospi200Recommend() {
        for (r in data.get("recs")) {
            val members = items(r.get("members")).map { it.asString() }
            val enabled = r.get("enabled").asBoolean()
            val svc = RecommendService({ _, _ -> members }, src, tech, inputs(enabled, if (enabled) qm else emptyMap()), catalog, common)
            Oracle.assertDeep(r.get("out"), snake.convertValue<Map<String, Any?>>(svc.kospi200Scored(asOf)), path = "rec(enabled=$enabled, n=${members.size})")
        }
    }
}
