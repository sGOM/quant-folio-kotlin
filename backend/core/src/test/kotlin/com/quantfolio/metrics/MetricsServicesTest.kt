package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 지표 서비스 조립부: 필터·이름·라벨, 패닉 S9 캐시, 섹터 전량 실패. 계산 자체는 MetricsPureOracleTest 가 맡는다. */
class MetricsServicesTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    // 거래소 응답이 없으므로 달력은 주말만 휴장으로 본다.
    private val common = MetricsCommon(
        MarketCalendar({ true }, { emptyMap() }, clock),
        clock,
    )
    private val asOf = LocalDate.of(2026, 10, 16)

    private open class FakeSource : SnapshotSource {
        var caps = listOf<StockDaily>()
        var periods = listOf<StockPeriod>()
        var index: List<IndexBar>? = null
        var indexError: Exception? = null
        var snapshot: List<StockDaily>? = null
        var sectors = mapOf<String, List<Pair<String, String>>>()
        var periodError: Exception? = null
        var periodErrorMarkets: Set<String>? = null // null 이면 전 시장
        override fun fundamentals(asOf: LocalDate, markets: List<String>) = emptyList<StockDaily>()
        override fun marketCap(asOf: LocalDate, markets: List<String>) = caps
        override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>) = periodError?.takeIf { periodErrorMarkets?.let { m -> markets.any { it in m } } ?: true }?.let { throw it } ?: periods
        override fun marketOhlcvSnapshot(day: LocalDate, market: String) = snapshot
        override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? = indexError?.let { throw it } ?: index
        override fun sectorIndexes(market: String, day: LocalDate) = sectors[market].orEmpty()
    }

    private fun bars(n: Int) = (0 until n).map {
        Bar(Instant.parse("2025-01-01T00:00:00Z").plusSeconds(86_400L * it), 100.0, 101.0, 99.0, 100.0 + it, 1000)
    }

    @Test
    fun stocksFilterLiquidityAndHistoryThenLabel() {
        val src = FakeSource().apply {
            caps = listOf(
                StockDaily("000010", market = "KOSPI", marketCap = 200_000_000_000, shares = 1000),
                StockDaily("000020", market = null, marketCap = 300_000_000_000, shares = 1000),
                StockDaily("000030", market = "KOSPI", marketCap = 50_000_000_000), // 시총 미달
                StockDaily("000040", market = "KOSPI", marketCap = 400_000_000_000), // 거래대금 미달
                StockDaily("000050", market = "  ", marketCap = 500_000_000_000), // 봉 부족
            )
            periods = listOf(
                StockPeriod("000010", name = " 가나 ", changePct = 5.0, close = 1234.0, tradingValue = 20_000_000_000),
                StockPeriod("000020", name = null, changePct = -2.0, close = 50.0, tradingValue = 20_000_000_000),
                StockPeriod("000040", name = "다라", changePct = 1.0, tradingValue = 1_000_000_000),
                StockPeriod("000050", name = "마바", changePct = 1.0, tradingValue = 20_000_000_000),
            )
        }
        val tech = TechIndicatorLoader { code, _, _ -> if (code == "000050") bars(100) else bars(300) }
        val catalog = SymbolCatalog { throw SourceAuthError("kis_master", "x") }
        val out = StockMetricsService(src, common, tech, catalog).compute("ALL", asOf)

        assertEquals(listOf("000010", "000020"), out.items.map { it.code })
        val a = out.items[0]
        assertEquals("가나", a.name)
        assertEquals("KOSPI", a.market)
        assertEquals(1234L, a.price)
        assertEquals(0.05, a.changeRate)
        assertEquals(1e9, a.avgValue20)
        val b = out.items[1]
        assertEquals("000020", b.name) // 이름 결측 → 코드
        assertEquals("KOSPI", b.market) // 시장 결측 → ALL 의 폴백
    }

    @Test
    fun panicNewLowRatioAccumulatesFileCacheAndNeedsSixtyDays(@TempDir dir: Path) {
        val src = FakeSource().apply {
            index = (0 until 30).map { IndexBar(asOf.minusDays(30L - it), high = 101.0, low = 99.0, close = 100.0, tradingValue = 1_000) }
            periods = listOf(StockPeriod("000010", changePct = -1.0, volume = 10))
            snapshot = listOf(StockDaily("000010", close = 50.0), StockDaily("000020", close = 200.0))
        }
        val service = PanicService(src, common, QuantfolioProperties(cacheDir = dir.toString()))
        val first = service.compute("KOSPI", asOf).items.single()
        assertNull(first.signals.single { it.key == "nlr" }.value) // 캐시 1일치 → 판정 제외
        assertEquals(1, first.universe)
        val file = dir.resolve("panic_breadth_KOSPI_CLOSES.json")
        assertTrue(Files.readString(file).contains("20261016"))

        // 과거 70일을 채워 두면 오늘 종가가 기간 최저인 종목 비율이 나온다.
        val hist = (1..70).associate { asOf.minusDays(it.toLong()).toString().replace("-", "") to mapOf("000010" to 60.0, "000020" to 100.0) }
        Files.writeString(file, tools.jackson.databind.json.JsonMapper.builder().build().writeValueAsString(hist))
        val second = service.compute("KOSPI", asOf).items.single()
        assertEquals(0.5, second.signals.single { it.key == "nlr" }.value)
    }

    /** 로컬 데이터 미적재는 브레드스 결측(null)으로 뭉개지 않고 호출자(백테스트 409·UI)에게 올라간다. */
    @Test
    fun panicPropagatesMarketDataNotLoadedFromBreadth(@TempDir dir: Path) {
        val src = FakeSource().apply {
            index = (0 until 30).map { IndexBar(asOf.minusDays(30L - it), high = 101.0, low = 99.0, close = 100.0, tradingValue = 1_000) }
            periodError = MarketDataNotLoaded("stock_daily", "KOSPI")
        }
        val service = PanicService(src, common, QuantfolioProperties(cacheDir = dir.toString()))
        assertThrows<MarketDataNotLoaded> { service.series("KOSPI", asOf.minusDays(10), asOf) }
    }

    /** UI 화면은 시장 하나의 미적재로 통째로 내려가지 않는다 — 그 시장만 unavailable. */
    @Test
    fun panicScreenMarksOnlyTheUnloadedMarketUnavailable(@TempDir dir: Path) {
        val src = FakeSource().apply {
            index = (0 until 30).map { IndexBar(asOf.minusDays(30L - it), high = 101.0, low = 99.0, close = 100.0, tradingValue = 1_000) }
            periods = listOf(StockPeriod("000010", changePct = -1.0, volume = 10))
            periodError = MarketDataNotLoaded("stock_daily", "KOSDAQ")
            periodErrorMarkets = setOf("KOSDAQ")
        }
        val out = PanicService(src, common, QuantfolioProperties(cacheDir = dir.toString())).compute("ALL", asOf)
        assertEquals(listOf("KOSPI"), out.items.map { it.market })
        assertEquals(listOf("KOSDAQ"), out.unavailable)
    }

    @Test
    fun panicMarksMarketUnavailableWhenIndexMissing(@TempDir dir: Path) {
        val out = PanicService(FakeSource(), common, QuantfolioProperties(cacheDir = dir.toString())).compute("ALL", asOf)
        assertEquals(listOf("KOSPI", "KOSDAQ"), out.unavailable)
        assertEquals(PanicService.CAVEATS, out.caveats)
    }

    /** 업종지수 하나라도 첫 행 이후 미적재면 빈 목록·일부 목록이 아니라 화면 전체 409(종목 화면과 같은 동작). */
    @Test
    fun `sector metrics propagate MarketDataNotLoaded instead of returning an empty list`() {
        val ref = (0 until 130).map { IndexBar(asOf.minusDays(130L - it), close = 100.0 + it) }
        val src = object : FakeSource() {
            override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? =
                if (ticker == "KOSPI:전기전자") throw MarketDataNotLoaded("index_ohlcv", ticker) else ref
        }.apply { sectors = mapOf("KOSPI" to listOf("KOSPI:음식료" to "음식료", "KOSPI:전기전자" to "전기전자")) }
        assertThrows<MarketDataNotLoaded> { SectorMetricsService(src).compute("KOSPI", asOf) }
    }

    /** 업종지수 키가 아직 없는 시장(백필 전)은 빈 목록이 아니라 미적재. */
    @Test
    fun `sector metrics throw MarketDataNotLoaded when a market has no sector indexes`() {
        val src = FakeSource().apply {
            index = (0 until 130).map { IndexBar(asOf.minusDays(130L - it), close = 100.0 + it) }
            sectors = mapOf("KOSPI" to listOf("KOSPI:음식료" to "음식료"))
        }
        val e = assertThrows<MarketDataNotLoaded> { SectorMetricsService(src).compute("ALL", asOf) }
        assertEquals("index_ohlcv", e.kind)
        assertEquals("sector_indexes|KOSDAQ", e.key)
    }

    @Test
    fun sectorsListLocalKeysWithTheirNames() {
        val ref = (0 until 130).map { IndexBar(asOf.minusDays(130L - it), close = 100.0 + it) }
        val src = FakeSource().apply {
            index = ref
            sectors = mapOf("KOSPI" to listOf("KOSPI:음식료" to "음식료"), "KOSDAQ" to listOf("KOSDAQ:제약" to "제약"))
        }
        assertEquals(listOf("음식료" to "KOSPI", "제약" to "KOSDAQ"), SectorMetricsService(src).compute("ALL", asOf).items.map { it.name to it.market })
    }

    /** 기준지수(1001·2001) 조회 실패는 "rs 결측"이 아니라 실패 — 원본은 감싸지 않아 503, 캐시 안 됨. 결측으로 굳혀 6시간 캐시하면 안 된다. */
    @Test
    fun sectorsRaiseWhenReferenceIndexFails() {
        val src = FakeSource().apply { indexError = SourceAuthError("krx", "차단") }
        assertThrows<SourceAuthError> { SectorMetricsService(src).compute("ALL", asOf) }
    }
}
