package com.quantfolio.market.ohlcv

import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.contiguousRanges
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDailyStore
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 로컬 전용 일봉 커버리지 점검 — [OhlcvLoader.ensureCoverage] 와 달리 외부(Naver)를 절대 부르지 않고
 * 빠진 거래일을 [MarketDataNotLoaded] 로 알린다. 거래일은 [LocalTradingDays].
 *
 * 상장 전·상장폐지 후의 날은 결측이 아니다: 빠진 날에 시장 전체 행(stock_daily_snapshots)은 있는데 그 종목 행이 없으면 상장 상태가 아니었던 날이다.
 * 시장 전체 행도 없으면 판정 불가이므로 결측으로 둔다.
 */
@Component
class LocalOhlcvCoverage(private val ticks: PriceTickStore, private val daily: StockDailyStore, private val days: LocalTradingDays) {
    fun require(symbol: String, start: LocalDate, end: LocalDate) {
        val expected = days.expected(start, end)
        if (expected.isEmpty()) return
        val have = ticks.bars(symbol, expected.first().atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
            .map { it.date }.toSet()
        val missing = expected.filter { it !in have }.filter { d ->
            val market = daily.read(d)
            market.isEmpty() || market.any { it.symbol == symbol }
        }
        if (missing.isNotEmpty()) throw MarketDataNotLoaded("price_ticks", symbol, contiguousRanges(missing, expected))
    }
}
