package com.quantfolio.market.official

import com.quantfolio.domain.TestApp
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

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class LocalSnapshotsTest(@Autowired private val daily: StockDailyStore, @Autowired private val indexes: IndexOhlcvStore) {
    private val ledger = InMemoryFetchLedger()
    private val snaps = LocalSnapshots(daily, indexes, LocalTradingDays(indexes, ledger))

    // 2310-03-01 ~ 03-05 (공유 DB 의 다른 테스트와 겹치지 않는 먼 미래). 요일은 무관하게 평일 가정이 필요하므로 아래에서 검증한다.
    private val week = (1..5).map { LocalDate.of(2310, 3, it) }

    @BeforeEach
    fun clean() {
        indexes.delete("1001")
        week.forEach { daily.delete(it) }
    }

    private fun loadIndex(vararg days: LocalDate) = indexes.write("1001", days.map { IndexBar(it, close = 1.0) }, null)
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
    fun `fundamentals and unsupported methods throw not loaded`() {
        assertEquals("valuation_pending", assertThrows<MarketDataNotLoaded> { snaps.fundamentals(week[0], listOf("KOSPI")) }.kind)
        assertEquals("unsupported_in_phase1", assertThrows<MarketDataNotLoaded> { snaps.indexTickers(week[0], "KOSPI") }.kind)
        assertEquals("unsupported_in_phase1", assertThrows<MarketDataNotLoaded> { snaps.indexName(week[0], "1001") }.kind)
        assertEquals("unsupported_in_phase1", assertThrows<MarketDataNotLoaded> { snaps.netPurchases(week[0], week[1], listOf("KOSPI")) }.kind)
    }
}
