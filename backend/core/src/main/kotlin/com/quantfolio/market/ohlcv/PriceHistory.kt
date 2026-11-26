package com.quantfolio.market.ohlcv

import com.quantfolio.market.store.Bar
import java.time.LocalDate

/** 종목 일봉 조회. 구현은 로컬 price_ticks 하나다(`LocalPriceHistory`). 테스트에서 교체 가능. */
fun interface PriceHistory {
    fun load(symbol: String, start: LocalDate, end: LocalDate): List<Bar>
}
