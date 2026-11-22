package com.quantfolio.market.official

import com.quantfolio.market.PastTradingDays
import java.time.DayOfWeek
import java.time.LocalDate

object DividendDates {
    private const val MAX_BACK = 20

    /** [exDate] 를 [days] 로. 주말은 달력에 묻지 않고 휴일로 본다. */
    fun exDateOn(record: LocalDate, days: PastTradingDays): LocalDate? =
        exDate(record) { d -> if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) false else days.isTradingDay(d) }

    /**
     * 배당락일 = 기준일 이하 마지막 거래일의 직전 거래일. 기준일이 거래일이면 단순히 직전 거래일이고,
     * 휴일(연말·토요일 등)이면 마지막 거래일이 실질 기준일이라 한 거래일 더 앞이다
     * (실측: 기준일 2017-12-31 → 배당락 12-27, 2018-03-31(토) → 03-29).
     * [isTradingDay] 가 null(모르는 날)이면 추측하지 않고 null.
     */
    fun exDate(record: LocalDate, isTradingDay: (LocalDate) -> Boolean?): LocalDate? {
        var d = record
        var seenLast = false
        repeat(MAX_BACK) {
            when (isTradingDay(d)) {
                null -> return null
                true -> if (seenLast) return d else seenLast = true
                false -> Unit
            }
            d = d.minusDays(1)
        }
        return null
    }
}
