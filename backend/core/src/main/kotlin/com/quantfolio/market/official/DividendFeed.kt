package com.quantfolio.market.official

import com.quantfolio.common.MarketClock
import com.quantfolio.market.PastTradingDays
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDailyStore
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate

/** [events] 는 종목 → `ex_date`(LocalDate)·`dps`(백테스트 수정주가 단위) 목록, 종목·배당락일 오름차순. [warnings] 는 건너뛴 이벤트 사유. */
class DividendInputs(val events: Map<String, List<Map<String, Any?>>>, val warnings: List<String>)

/**
 * 백테스트용 `_dividends` 구성. 공시 배당금은 기준일 당시 주식 수 기준이라(분할 전 17,700원 → 분할 후 354원)
 * 배당락일의 (수정 종가 / 원시 종가) 비율로 백테스트 가격 단위에 맞춘다. 어느 쪽 종가든 없으면 추측하지 않고 건너뛰며 경고를 남긴다.
 */
@Component
class DividendFeed(
    private val dividends: DividendStore,
    private val daily: StockDailyStore,
    private val ticks: PriceTickStore,
    private val ledger: FetchLedger,
    private val days: PastTradingDays,
) {
    /** 전체 적재를 마친 적이 있는가 — 없으면 배당 이력이 비어 있는 것과 '배당 없음'을 구분할 수 없다. */
    fun ingested(): Boolean = ledger.get(DividendIngest.SOURCE, DividendIngest.LEDGER_KEY)?.final == true

    fun forBacktest(symbols: List<String>, start: LocalDate, end: LocalDate): DividendInputs {
        if (!ingested()) return DividendInputs(emptyMap(), emptyList())
        val warnings = mutableListOf<String>()
        val out = linkedMapOf<String, List<Map<String, Any?>>>()
        val calendar = { d: LocalDate -> if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) false else days.isTradingDay(d) }
        // 기준일이 끝 날짜 뒤여도 배당락일은 그 앞일 수 있다(연말 기준일 → 직전 영업일들).
        val rows = dividends.read(symbols, start, end.plusDays(LOOKAHEAD_DAYS)).groupBy { it.symbol }
        for (symbol in symbols.sorted()) {
            val dated = rows[symbol.padStart(6, '0')].orEmpty().mapNotNull { e ->
                val ex = DividendDates.exDate(e.recordDate, calendar)
                if (ex == null) warnings += "배당 ${symbol} 기준일 ${e.recordDate}: 배당락일을 확정하지 못해 건너뜀(달력 미적재)"
                ex?.takeIf { !it.isBefore(start) && !it.isAfter(end) }?.let { it to e.dps }
            }
            if (dated.isEmpty()) continue
            val adjusted = ticks.bars(symbol, MarketClock.kstStart(dated.minOf { it.first }), MarketClock.kstEnd(dated.maxOf { it.first }))
                .associate { it.date to it.close }
            val raw = daily.readSymbol(symbol, dated.minOf { it.first }, dated.maxOf { it.first }).associate { it.first to it.second.close }
            val list = dated.sortedBy { it.first }.mapNotNull { (ex, dps) ->
                val a = adjusted[ex]
                val r = raw[ex]
                if (a == null || r == null || !(a > 0) || !(r > 0)) {
                    warnings += "배당 $symbol 배당락일 $ex: ${if (a == null || !(a > 0)) "수정" else "원시"} 종가가 없어 건너뜀(금액 환산 불가)"
                    null
                } else {
                    mapOf<String, Any?>("ex_date" to ex, "dps" to dps * a / r)
                }
            }
            if (list.isNotEmpty()) out[symbol] = list
        }
        return DividendInputs(out, warnings)
    }

    private companion object {
        const val LOOKAHEAD_DAYS = 14L
    }
}
