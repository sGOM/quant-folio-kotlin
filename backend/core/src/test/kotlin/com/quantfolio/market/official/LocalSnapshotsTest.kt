package com.quantfolio.market.official

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.OpenDartClient
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.DividendStore
import com.quantfolio.test.FakeExternalHttp
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class LocalSnapshotsTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
    @Autowired dartStore: DartFinancialStore,
    @Autowired dividends: DividendStore,
    @Autowired ticks: com.quantfolio.market.store.PriceTickStore,
) {
    private val ledger = InMemoryFetchLedger()
    private val days = LocalTradingDays(ledger)
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp() // 호출되면 안 된다: 로컬 경로
    private val cooldowns = Cooldowns(clock)
    private val dart = DartFinancials(
        OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K")), cooldowns, clock),
        dartStore, ledger, cooldowns, clock,
    )
    init {
        dartStore.writeCorpCodes(mapOf("999999" to "00000001"))
    }

    private val snaps = LocalSnapshots(daily, indexes, days, Valuation(daily, dart, dartStore, dividends, ledger, ticks, days))

    // 2310-03-01 ~ 03-05 (공유 DB 의 다른 테스트와 겹치지 않는 먼 미래). 요일은 무관하게 평일 가정이 필요하므로 아래에서 검증한다.
    private val week = generateSequence(LocalDate.of(2310, 3, 1)) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(8).toList()

    @BeforeEach
    fun clean() {
        listOf("1001", "1028", "2001", "KOSPI:테스트업종가", "KOSPI:테스트업종나", "KOSDAQ:테스트업종다").forEach { indexes.delete(it) }
        week.forEach { daily.delete(it) }
    }

    private fun loadIndex(vararg days: LocalDate) = ledger.trading(days.toList()).also { indexes.write("1001", days.map { IndexBar(it, close = 1.0) }, null) }
    private fun row(close: Double? = null, volume: Long? = null, cap: Long? = null) = listOf(StockDaily("005930", market = "KOSPI", close = close, volume = volume, marketCap = cap))

    @Test
    fun `missing trading days inside range throw with exact gaps`() {
        loadIndex(*week.toTypedArray())
        daily.write(week[0], row(close = 1.0))
        daily.write(week[3], row(close = 1.0))
        daily.write(week[4], row(close = 1.0))
        val e = assertThrows<MarketDataNotLoaded> { snaps.priceChange(week[0], week[4], listOf("KOSPI")) }
        assertEquals("stock_daily", e.kind)
        assertEquals(listOf(week[1] to week[2]), e.missing)
    }

    @Test
    fun `holiday is not missing and range past loaded index is`() {
        loadIndex(week[0], week[1])
        // 2310-03-05 이후 평일을 쓰려면 요일을 알아야 한다 — 꼬리 구간은 다음 두 평일로 잡는다.
        val tail = generateSequence(week[1].plusDays(1)) { it.plusDays(1) }
            .filter { it.dayOfWeek.value <= 5 }.take(3).toList()
        ledger.put("krx_open", "stk_bydd_trd|${tail[0].toString().replace("-", "")}|KOSPI", 0, true) // 첫 평일 휴장 확정
        val e = assertThrows<MarketDataNotLoaded> { snaps.indexOhlcv(week[0], tail[2], "1001") }
        assertEquals(listOf(tail[1] to tail[2]), e.missing)
        assertNull(snaps.marketOhlcvSnapshot(tail[0], "KOSPI")) // 휴장일은 null
    }

    @Test
    fun `index history before its first row is not missing`() {
        ledger.trading(week.take(5))
        indexes.write("1001", listOf(week[2], week[3], week[4]).map { IndexBar(it, close = 1.0) }, null)
        assertEquals(listOf(week[2], week[3], week[4]), snaps.indexOhlcv(week[0], week[4], "1001")!!.map { it.date }) // 첫 행 이전은 그때 없던 지수
        assertNull(snaps.indexOhlcv(week[0], week[1], "1001")) // 구간 전체가 첫 행 이전: 예외 아님
        indexes.delete("1001")
        indexes.write("1001", listOf(week[2], week[4]).map { IndexBar(it, close = 1.0) }, null)
        assertEquals(listOf(week[3] to week[3]), assertThrows<MarketDataNotLoaded> { snaps.indexOhlcv(week[0], week[4], "1001") }.missing)
        // 행이 하나도 없는 키는 전 구간 미적재
        assertEquals(listOf(week[0] to week[4]), assertThrows<MarketDataNotLoaded> { snaps.indexOhlcv(week[0], week[4], "KOSPI:없는업종") }.missing)
        assertThrows<MarketDataNotLoaded> { snaps.priceChange(week[0], week[1], listOf("KOSPI")) }
    }

    @Test
    fun `sectorIndexes lists stored sector keys without market reference`() {
        for (code in listOf("1001", "1028", "2001")) indexes.write(code, listOf(IndexBar(week[0], close = 1.0)), null)
        indexes.write("KOSPI:테스트업종나", listOf(IndexBar(week[0], close = 1.0)), "테스트업종나")
        indexes.write("KOSPI:테스트업종가", listOf(IndexBar(week[0], close = 1.0), IndexBar(week[1], close = 1.0)), "테스트업종가")
        indexes.write("KOSDAQ:테스트업종다", listOf(IndexBar(week[0], close = 1.0)), "테스트업종다")
        val kospi = snaps.sectorIndexes("KOSPI")
        assertEquals(listOf("KOSPI:테스트업종가" to "테스트업종가", "KOSPI:테스트업종나" to "테스트업종나"), kospi.filter { "테스트업종" in it.first })
        assertTrue(kospi.all { it.first.startsWith("KOSPI:") }) // 1001·1028·2001·KOSDAQ 키 없음
        assertEquals(kospi.map { it.first }.sorted(), kospi.map { it.first })
        assertEquals(listOf("KOSDAQ:테스트업종다" to "테스트업종다"), snaps.sectorIndexes("KOSDAQ").filter { "테스트업종" in it.first })
    }

    @Test
    fun `price change uses prev diff returns so a split is not a crash`() {
        loadIndex(week[0], week[1], week[2])
        daily.write(week[0], listOf(StockDaily("005930", market = "KOSPI", close = 50000.0)))
        daily.write(week[1], listOf(StockDaily("005930", market = "KOSPI", close = 1000.0, prevDiff = 0.0))) // 1/50 분할, 기준가 조정
        daily.write(week[2], listOf(StockDaily("005930", market = "KOSPI", close = 1010.0, prevDiff = 10.0)))
        val p = snaps.priceChange(week[0], week[2], listOf("KOSPI")).single()
        assertEquals(1.0, p.changePct!!, 1e-9)
    }

    @Test
    fun `price change falls back to raw ratio when prev diff is null`() {
        loadIndex(week[0], week[1])
        daily.write(week[0], listOf(StockDaily("005930", market = "KOSPI", close = 100.0)))
        daily.write(week[1], listOf(StockDaily("005930", market = "KOSPI", close = 105.0)))
        assertEquals(5.0, snaps.priceChange(week[0], week[1], listOf("KOSPI")).single().changePct!!, 1e-9)
    }

    @Test
    fun `symbol delisted during period gets close 0 and minus 100 percent`() {
        loadIndex(week[0], week[1])
        daily.write(week[0], listOf(StockDaily("111111", market = "KOSPI", close = 100.0), StockDaily("222222", market = "KOSPI", close = 10.0)))
        daily.write(week[1], listOf(StockDaily("222222", market = "KOSPI", close = 11.0, prevDiff = 1.0))) // 시장은 계속, 111111 만 끝
        val gone = snaps.priceChange(week[0], week[1], listOf("KOSPI")).single { it.symbol == "111111" }
        assertEquals(0.0, gone.close)
        assertEquals(-100.0, gone.changePct)
        assertEquals(0L, gone.volume)
        assertEquals(0L, gone.tradingValue)
    }

    @Test
    fun `price change and market cap read local rows`() {
        loadIndex(week[0], week[1])
        daily.write(week[0], row(close = 100.0, volume = 10, cap = 5))
        daily.write(week[1], row(close = 110.0, volume = 20))
        val p = snaps.priceChange(week[0], week[1], listOf("KOSPI")).single()
        assertEquals(10.0, p.changePct!!, 1e-9)
        assertEquals(30L, p.volume)
        assertEquals(5L, snaps.marketCap(week[0], listOf("KOSPI")).single().marketCap)
    }

    @Test
    fun `fundamentals delegates to valuation and keeps market`() {
        loadIndex(week[0])
        daily.write(week[0], row(close = 1000.0, cap = 5_000L))
        val v = snaps.fundamentals(week[0], listOf("KOSPI")).single()
        assertEquals("005930", v.symbol)
        assertEquals("KOSPI", v.market)
        assertNull(v.per) // corp 매핑에 없는 종목 → 지표 null (0 아님)
        assertNull(v.pbr)
        assertNull(v.div)
        assertThrows<MarketDataNotLoaded> { snaps.fundamentals(week[0], listOf("KOSDAQ")) } // 시장 적재 공백은 그대로 미적재
    }
}
