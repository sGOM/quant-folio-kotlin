package com.quantfolio.market.official

import com.quantfolio.market.store.CapKind
import com.quantfolio.market.store.StockDailyStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 시총 상위 N 유니버스(소스 `KOSPI_MCAP`). 후보 = asOf **직전 거래일** KOSPI 보통주를 시가총액 내림차순(동률은 종목코드 오름차순)으로 N개.
 * 직전 거래일까지의 데이터만 쓴다(미래참조 금지). 주권 종류는 기본정보 적재일에만 채워지므로 종목별로 직전 거래일 이하의 최신 값을 쓴다.
 */
@Component
class McapUniverse(private val daily: StockDailyStore, private val days: LocalTradingDays) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun members(asOf: LocalDate, n: Int): List<String> {
        val prev = previousTradingDay(asOf)
        val rows = daily.capsWithKind(prev, "KOSPI")
        if (rows.isEmpty()) throw MarketDataNotLoaded("stock_daily", "KOSPI", listOf(prev to prev))
        if (rows.all { it.stockKind == null }) throw MarketDataNotLoaded("stock_kind", "KOSPI", listOf(prev to prev))
        val unknown = rows.count { it.stockKind == null }
        if (unknown > 0) log.warn("주권 종류를 모르는 KOSPI 종목 {}개를 시총 유니버스에서 제외한다(기준일 {}).", unknown, prev)
        return rows.filter { it.stockKind == OfficialIngest.COMMON_STOCK }
            .sortedWith(compareByDescending<CapKind> { it.marketCap }.thenBy { it.symbol })
            .take(n).map { it.symbol }
    }

    private fun previousTradingDay(asOf: LocalDate): LocalDate {
        var d = asOf.minusDays(1)
        repeat(MAX_BACK) {
            when (days.isTradingDay(d)) {
                true -> return d
                null -> throw MarketDataNotLoaded("index_ohlcv", "1001", listOf(d to d))
                false -> d = d.minusDays(1)
            }
        }
        throw MarketDataNotLoaded("index_ohlcv", "1001", listOf(d to asOf.minusDays(1)))
    }

    private companion object {
        const val MAX_BACK = 20
    }
}
