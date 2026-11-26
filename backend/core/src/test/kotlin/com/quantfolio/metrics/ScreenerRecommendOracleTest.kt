package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.official.McapUniverse
import com.quantfolio.market.official.OfficialIngest
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.CapKind
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.test.InMemoryFetchLedger
import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import tools.jackson.databind.JsonNode
import tools.jackson.databind.PropertyNamingStrategies
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.node.ObjectNode
import tools.jackson.module.kotlin.convertValue
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

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

    /**
     * 시총 fake: 정답지 구성종목은 보통주, 나머지 종목은 우선주 → 당일 시총 상위 200 = 정답지 구성종목 그대로(구성종목 0개 케이스는 전부 우선주).
     * 구성종목 입력의 출처만 바뀌고 계산 정답 비교는 그대로다.
     */
    private fun mcap(members: List<String>) = McapUniverse(
        object : StockDailyStore(NamedParameterJdbcTemplate(JdbcTemplate())) {
            override fun capsWithKind(day: LocalDate, market: String): List<CapKind> {
                check(day == asOf && market == "KOSPI") { "당일 KOSPI 시총만 읽어야 한다: $day $market" }
                return (codes + members).distinct().mapIndexed { i, c -> CapKind(c, 1_000_000L - i, if (c in members) OfficialIngest.COMMON_STOCK else "우선주") }
            }
        },
        LocalTradingDays(InMemoryFetchLedger()),
    )

    @Test
    fun mcap200Recommend() {
        for (r in data.get("recs")) {
            val members = items(r.get("members")).map { it.asString() }
            val enabled = r.get("enabled").asBoolean()
            val svc = RecommendService(mcap(members), src, tech, inputs(enabled, if (enabled) qm else emptyMap()), catalog, common)
            val out = snake.convertValue<Map<String, Any?>>(svc.mcap200Scored(asOf))
            // 의도적 차이(허용 목록): 지수 라벨 - 구성종목 소스가 KRX KOSPI200 구성종목에서 당일 시총 상위 200 으로 바뀌었다(2단계 Task 6).
            assertEquals("MCAP200", out["index"])
            val expected = (r.get("out").deepCopy() as ObjectNode).also { it.remove("index") }
            Oracle.assertDeep(expected, out - "index", path = "rec(enabled=$enabled, n=${members.size})")
        }
    }
}
