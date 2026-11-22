package com.quantfolio.market.official

import com.quantfolio.market.PastTradingDays
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.IndexOhlcvStore
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 로컬이 아는 거래일. 기준은 `index_ohlcv` 의 KOSPI(1001) 행이다.
 * 적재된 마지막 지수일보다 뒤로 요청 구간이 뻗으면 그 뒤 평일은 아직 모르는 날이므로 결측으로 센다 —
 * 단 원장에 휴장 확정(0행·final)이 있는 날은 제외.
 */
@Component
class LocalTradingDays(private val indexes: IndexOhlcvStore, private val ledger: FetchLedger) : PastTradingDays {
    override fun isTradingDay(day: LocalDate): Boolean = day in expected(day, day)

    /** [start, end] 의 기대 거래일(오름차순). 적재 구간 앞·뒤의 평일(휴장 확정 제외)은 아직 모르는 날이라 결측 후보다. */
    fun expected(start: LocalDate, end: LocalDate): List<LocalDate> {
        val span = indexes.span(KOSPI_INDEX) ?: return unknownWeekdays(start, end)
        val head = unknownWeekdays(start, minOf(end, span.first.minusDays(1)))
        val inRange = indexes.read(KOSPI_INDEX, start, end).map { it.date }
        val tail = unknownWeekdays(maxOf(start, span.second.plusDays(1)), end)
        return head + inRange + tail
    }

    private fun unknownWeekdays(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }
            .takeWhile { !it.isAfter(to) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY && !isHoliday(it) }
            .toList()

    /** 원장에 휴장 확정(`krx_open`, `stk_bydd_trd|yyyyMMdd|KOSPI`, 0행·final)이 있는 날. */
    fun isHoliday(day: LocalDate): Boolean =
        ledger.get(LEDGER_SOURCE, "stk_bydd_trd|${day.format(YMD)}|KOSPI")?.let { it.rowCount == 0 && it.final } == true

    companion object {
        const val KOSPI_INDEX = "1001"
        private const val LEDGER_SOURCE = "krx_open"
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
