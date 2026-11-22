package com.quantfolio.market.official

import com.quantfolio.domain.TestApp
import com.quantfolio.market.PastTradingDays
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.DividendEvent
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class DividendFeedTest(
    @Autowired private val dividends: DividendStore,
    @Autowired private val daily: StockDailyStore,
    @Autowired private val ticks: PriceTickStore,
) {
    private val id = UUID.randomUUID().toString().filter { it.isDigit() }.padEnd(6, '5')
    private var seq = 0
    private fun newSymbol() = id.take(5) + (seq++)

    // 기준일 2018-03-31(토) → 마지막 거래일 03-30 → 배당락 03-29.
    private val trading = setOf("2018-03-27", "2018-03-28", "2018-03-29", "2018-03-30", "2018-04-02").map(LocalDate::parse).toSet()
    private val calendar = PastTradingDays { d -> d in trading }
    private val full = InMemoryFetchLedger().also { it.put("datagokr", "dividends_full", 1, true) }
    private val feed = DividendFeed(dividends, daily, ticks, full, calendar)
    private val start = LocalDate.of(2018, 3, 1)
    private val end = LocalDate.of(2018, 12, 31)
    private val ex = LocalDate.of(2018, 3, 29)

    private fun tick(symbol: String, day: LocalDate, close: Double) =
        ticks.upsert(symbol, listOf(Bar(day.atStartOfDay().toInstant(ZoneOffset.UTC), close, close, close, close, 1L)))

    @Test
    fun `dps is converted to adjusted price units using the ex date raw and adjusted closes`() {
        val s = newSymbol()
        dividends.write(listOf(DividendEvent(s, LocalDate.of(2018, 3, 31), 17_700.0, null, "datagokr")))
        daily.write(ex, listOf(StockDaily(s, market = "KOSPI", close = 2_650_000.0))) // 50:1 액면분할 전 원시 종가
        tick(s, ex, 53_000.0) // 현재 기준 수정 종가
        val out = feed.forBacktest(listOf(s), start, end)
        val e = out.events.getValue(s).single()
        assertEquals(ex, e["ex_date"])
        assertEquals(17_700.0 * 53_000.0 / 2_650_000.0, e["dps"] as Double, 1e-9)
        assertTrue(out.warnings.isEmpty())
    }

    @Test
    fun `missing raw or adjusted close skips the event with a warning`() {
        val noRaw = newSymbol(); val noAdj = newSymbol()
        dividends.write(
            listOf(
                DividendEvent(noRaw, LocalDate.of(2018, 3, 31), 100.0, null, "datagokr"),
                DividendEvent(noAdj, LocalDate.of(2018, 3, 31), 100.0, null, "datagokr"),
            ),
        )
        tick(noRaw, ex, 53_000.0)
        daily.write(ex, listOf(StockDaily(noAdj, market = "KOSPI", close = 2_650_000.0)))
        val out = feed.forBacktest(listOf(noRaw, noAdj), start, end)
        assertNull(out.events[noRaw])
        assertNull(out.events[noAdj])
        assertEquals(2, out.warnings.size)
    }

    @Test
    fun `not ingested is reported and yields no events`() {
        val s = newSymbol()
        val bare = DividendFeed(dividends, daily, ticks, InMemoryFetchLedger(), calendar)
        assertFalse(bare.ingested())
        assertTrue(feed.ingested())
        assertTrue(bare.forBacktest(listOf(s), start, end).events.isEmpty())
    }
}
