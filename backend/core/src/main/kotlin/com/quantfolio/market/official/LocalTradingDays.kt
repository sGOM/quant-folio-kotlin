package com.quantfolio.market.official

import com.quantfolio.market.PastTradingDays
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.LedgerEntry
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
        return state(day, ledger.get(SOURCE, key(day)))
    }

    /** [start, end] 의 기대 거래일(오름차순) = 평일 중 휴장 확정이 아닌 날. 아직 모르는 날은 결측 후보로 남는다. */
    fun expected(start: LocalDate, end: LocalDate): List<LocalDate> {
        val weekdays = generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { !isWeekend(it) }.toList()
        val entries = ledger.getAll(SOURCE, weekdays.map(::key))
        return weekdays.filter { state(it, entries[key(it)]) != false }
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

    private fun state(day: LocalDate, e: LedgerEntry?): Boolean? = when {
        e == null -> null
        e.rowCount > 0 -> true
        e.final -> false
        provisionalHoliday(day, e) -> false
        else -> null
    }

    /**
     * 0행·미확정 날의 잠정 휴장: 5개 호출이 모두 0행이고, 그 기록이 뒷날(첫 rows>0 거래일) 기록보다 늦거나 같을 때 —
     * 뒷날이 공개된 뒤에 받은 0행은 '미공개'가 아니라 휴장이다. 원장 `final`·7일 확정 규칙은 그대로다.
     * 시각을 모르거나 뒷날 기록이 없으면(공개 전 조회 가능성) 모른다.
     */
    private fun provisionalHoliday(day: LocalDate, e: LedgerEntry): Boolean {
        val at = e.fetchedAt ?: return false
        val all = CALLS.map { (svc, market) -> ledger.get(SOURCE, "$svc|${day.format(YMD)}|$market") }
        if (all.any { it == null || it.rowCount != 0 }) return false
        val later = generateSequence(day.plusDays(1)) { it.plusDays(1) }.take(MAX_FORWARD).filter { !isWeekend(it) }
            .firstNotNullOfOrNull { d -> ledger.get(SOURCE, key(d))?.takeIf { it.rowCount > 0 } } ?: return false
        return later.fetchedAt?.let { !at.isBefore(it) } == true
    }

    private fun isWeekend(d: LocalDate) = d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY

    private fun key(day: LocalDate) = "stk_bydd_trd|${day.format(YMD)}|KOSPI"

    companion object {
        private const val SOURCE = "krx_open"
        private const val MAX_BACK = 20
        private const val MAX_FORWARD = 10

        /** 하루의 5개 호출(서비스, 시장) — OfficialIngest 원장 키와 같다. */
        private val CALLS = listOf("stk_bydd_trd" to "KOSPI", "ksq_bydd_trd" to "KOSDAQ", "krx_dd_trd" to "KRX", "kospi_dd_trd" to "KOSPI", "kosdaq_dd_trd" to "KOSDAQ")
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
