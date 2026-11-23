package com.quantfolio.market.official

import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/** 로컬 일봉(유일한 [PriceHistory]). 구간의 거래일이 빠졌으면 [MarketDataNotLoaded] — 외부 보충은 없다. */
@Component
class LocalPriceHistory(private val ticks: PriceTickStore, private val coverage: LocalOhlcvCoverage) : PriceHistory {
    override fun load(symbol: String, start: LocalDate, end: LocalDate): List<Bar> {
        coverage.require(symbol, start, end)
        return ticks.bars(symbol, start.atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
    }
}
