package com.quantfolio.market.official

import com.quantfolio.common.MarketClock
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDailyStore
import java.time.LocalDate

/**
 * 날짜별 수정 종가 / 원시 종가 — 배당(기준일 당시 주식 단위)을 가격 단위로 옮기는 데 쓴다.
 * 수정주가는 최신 기준으로 다시 만들어지지만 두 날짜의 비의 비(= 그 사이 권리락 곱)는 이후 권리락이 와도 변하지 않는다
 * (같은 인자가 양쪽에 곱해진다). 그래서 asOf 이하 두 날짜만으로 미래참조 없이 환산할 수 있다.
 */
class PriceUnits(private val ticks: PriceTickStore, private val daily: StockDailyStore) {
    /** [from]~[to] 중 수정·원시 종가가 모두 있고 양수인 날만. */
    fun ratios(symbol: String, from: LocalDate, to: LocalDate): Map<LocalDate, Double> {
        val adjusted = ticks.bars(symbol, MarketClock.kstStart(from), MarketClock.kstEnd(to)).associate { it.date to it.close }
        val raw = daily.readSymbol(symbol, from, to).associate { it.first to it.second.close }
        return adjusted.mapNotNull { (d, a) ->
            val r = raw[d]
            if (r != null && a > 0 && r > 0) d to a / r else null
        }.toMap()
    }
}
