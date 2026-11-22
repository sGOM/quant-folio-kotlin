package com.quantfolio.market.official

import com.quantfolio.domain.TestApp
import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.PriceTickStore
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
import java.time.ZoneOffset
import kotlin.test.assertEquals

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class OhlcvLoaderLocalTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
    @Autowired private val ticks: PriceTickStore,
) {
    private val local = LocalOhlcvCoverage(ticks, daily, LocalTradingDays(indexes, InMemoryFetchLedger()))
    private val week = (1..5).map { LocalDate.of(2320, 3, it) }
    private fun bar(day: LocalDate) = Bar(day.atStartOfDay().toInstant(ZoneOffset.UTC), 1.0, 1.0, 1.0, 1.0, 1)

    @BeforeEach
    fun clean() {
        indexes.delete("1001")
        week.forEach { daily.delete(it) }
        indexes.write("1001", week.map { IndexBar(it, close = 1.0) }, null)
    }

    @Test
    fun `missing trading days throw with exact gaps`() {
        ticks.upsert("L00001", listOf(bar(week[0]), bar(week[1]), bar(week[4])))
        val e = assertThrows<MarketDataNotLoaded> { local.require("L00001", week[0], week[4]) }
        assertEquals("price_ticks", e.kind)
        assertEquals(listOf(week[2] to week[3]), e.missing)
    }

    @Test
    fun `delisted symbol after last row is not missing`() {
        ticks.upsert("L00002", listOf(bar(week[0]), bar(week[1])))
        daily.write(week[0], listOf(StockDaily("L00002", market = "KOSPI"), StockDaily("005930", market = "KOSPI")))
        daily.write(week[1], listOf(StockDaily("L00002", market = "KOSPI"), StockDaily("005930", market = "KOSPI")))
        for (i in 2..4) daily.write(week[i], listOf(StockDaily("005930", market = "KOSPI"))) // 시장 전체는 계속, 종목은 없음
        local.require("L00002", week[0], week[4]) // 예외 없음
    }
}
