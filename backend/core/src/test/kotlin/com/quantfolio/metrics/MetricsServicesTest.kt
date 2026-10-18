package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.krx.KrxMdc
import com.quantfolio.market.krx.KrxSession
import com.quantfolio.market.krx.KrxStockApi
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.FakeKrxTransport
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
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    // 거래소 응답이 없으므로 달력은 주말만 휴장으로 본다.
    private val common = MetricsCommon(
        MarketCalendar(KrxStockApi(KrxMdc(transport, KrxSession(transport, QuantfolioProperties(), cooldowns, clock), cooldowns) {}), clock),
        clock,
    )
    private val asOf = LocalDate.of(2026, 10, 16)

    private open class FakeSource : SnapshotSource {
        var caps = listOf<StockDaily>()
        var periods = listOf<StockPeriod>()
        var index: List<IndexBar>? = null
        var snapshot: List<StockDaily>? = null
        var tickers: () -> List<String> = { emptyList() }
        override fun fundamentals(asOf: LocalDate, markets: List<String>) = emptyList<StockDaily>()
        override fun marketCap(asOf: LocalDate, markets: List<String>) = caps
        override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>) = periods
        override fun netPurchases(start: LocalDate, end: LocalDate, markets: List<String>, investors: List<String>) = emptyList<StockPeriod>()
        override fun marketOhlcvSnapshot(day: LocalDate, market: String) = snapshot
        override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String) = index
        override fun indexTickers(day: LocalDate, market: String) = tickers()
        override fun indexName(day: LocalDate, ticker: String) = "업종$ticker"
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
        val catalog = SymbolCatalog({ throw SourceAuthError("krx", "x") }, FakeExternalHttp().apply { failWith { true } })
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

    @Test
    fun panicMarksMarketUnavailableWhenIndexMissing(@TempDir dir: Path) {
        val out = PanicService(FakeSource(), common, QuantfolioProperties(cacheDir = dir.toString())).compute("ALL", asOf)
        assertEquals(listOf("KOSPI", "KOSDAQ"), out.unavailable)
        assertEquals(PanicService.CAVEATS, out.caveats)
    }

    @Test
    fun sectorsRaiseWhenEveryTickerListFails() {
        val src = FakeSource().apply { tickers = { throw SourceAuthError("krx", "차단") } }
        assertThrows<SourceAuthError> { SectorMetricsService(src).compute("ALL", asOf) }
    }
}
