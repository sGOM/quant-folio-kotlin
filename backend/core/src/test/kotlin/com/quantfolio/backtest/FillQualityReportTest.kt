package com.quantfolio.backtest

import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.store.Bar
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class FillQualityReportTest {
    @Test
    fun `fill window end is clipped to last loaded trading day`() {
        val last = LocalDate.of(2026, 10, 7) // 수 = 마지막 적재 거래일, 이후 평일은 미적재
        val loaded = (-5L..0L).map { last.plusDays(it) }.filter { it.dayOfWeek.value <= 5 }
        val ledger = InMemoryFetchLedger().also { it.trading(loaded) }
        var requestedEnd: LocalDate? = null
        val prices = com.quantfolio.market.ohlcv.PriceHistory { _, start, end ->
            requestedEnd = end
            // 미적재 구간(end 가 last 를 넘으면)을 요청하면 이전 구현처럼 적재 실패.
            check(!end.isAfter(last)) { "미적재 구간 요청 $end" }
            generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }
                .map { Bar(it.atStartOfDay().toInstant(ZoneOffset.UTC), 1.0, 1.0, 1.0, 100.0, 1) }.toList()
        }
        val clock = Clock.fixed(Instant.parse("2026-10-20T01:00:00Z"), ZoneOffset.UTC)
        val report = FillQualityReport(
            mock(org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate::class.java),
            mock(com.quantfolio.domain.strategy.StrategyRepository::class.java), prices, LocalTradingDays(ledger), clock,
        )
        val panel = report.closePanel(listOf(FillQuality.Order(1, "005930", "buy", last, 100.0)))
        assertEquals(last, requestedEnd)
        assertEquals(listOf("005930"), panel.symbols)
    }
}
