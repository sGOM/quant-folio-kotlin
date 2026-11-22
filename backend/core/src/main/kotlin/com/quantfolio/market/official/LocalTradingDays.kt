package com.quantfolio.market.official

import com.quantfolio.market.PastTradingDays
import com.quantfolio.market.store.FetchLedger
import org.springframework.stereotype.Component
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 로컬이 아는 거래일 — 진실은 KRX Open API 적재 원장(`krx_open | stk_bydd_trd|yyyyMMdd|KOSPI`) 하나다.
 * 행 > 0 이면 거래일, 0행·확정이면 휴장, 기록이 없거나 미확정 0행이면 **모른다**(결측).
 * `index_ohlcv` 는 UI(원격 KrxSnapshots)·구 MDC 도 쓰는 테이블이라 달력의 근거가 될 수 없다
 * (오래된 캐시 구간이 남으면 그 앞뒤 수년이 휴장으로 보인다). 직전 거래일 계산도 여기 하나다.
 */
@Component
class LocalTradingDays(private val ledger: FetchLedger) : PastTradingDays {
    /** 주말 false, 원장대로 true/false, 모르면 null. */
    override fun isTradingDay(day: LocalDate): Boolean? {
        if (isWeekend(day)) return false
        return state(ledger.get(SOURCE, key(day)))
    }

    /** [start, end] 의 기대 거래일(오름차순) = 평일 중 휴장 확정이 아닌 날. 아직 모르는 날은 결측 후보로 남는다. */
    fun expected(start: LocalDate, end: LocalDate): List<LocalDate> {
        val weekdays = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { !isWeekend(it) }.toList()
        val entries = ledger.getAll(SOURCE, weekdays.map(::key))
        return weekdays.filter { state(entries[key(it)]) != false }
    }

    /**
     * [day] 바로 앞 거래일. 휴장 확정·주말은 건너뛴다. 가다가 모르는 날을 만나거나 [MAX_BACK] 일 안에 없으면 null —
     * 호출자는 이를 추측으로 메우지 않고 미적재/공백으로 다룬다.
     */
    fun previous(day: LocalDate): LocalDate? {
        var d = day
        repeat(MAX_BACK) {
            d = d.minusDays(1)
            when (isTradingDay(d)) {
                true -> return d
                null -> return null
                false -> Unit
            }
        }
        return null
    }

    /** 원장에 휴장 확정(0행·final)이 있는 날. */
    fun isHoliday(day: LocalDate): Boolean = ledger.get(SOURCE, key(day))?.let { it.rowCount == 0 && it.final } == true

    private fun state(e: com.quantfolio.market.store.LedgerEntry?): Boolean? = when {
        e == null -> null
        e.rowCount > 0 -> true
        e.final -> false
        else -> null
    }

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun key(day: LocalDate) = "stk_bydd_trd|${day.format(YMD)}|KOSPI"

    companion object {
        private const val SOURCE = "krx_open"
        private const val MAX_BACK = 20
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
