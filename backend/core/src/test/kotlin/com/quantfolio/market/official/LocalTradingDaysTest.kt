package com.quantfolio.market.official

import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalTradingDaysTest {
    private val ledger = InMemoryFetchLedger()
    private val days = LocalTradingDays(ledger)

    @Test
    fun `dates before the official ledger starts are unknown and expected, never holidays`() {
        // index_ohlcv 에 2015 년 캐시가 남아 있어도 달력은 원장만 본다 — 원장은 2020 부터.
        ledger.trading(generateSequence(LocalDate.of(2020, 1, 2)) { it.plusDays(1) }.take(10).filter { it.dayOfWeek.value <= 5 }.toList())
        val d = LocalDate.of(2016, 3, 2) // 수
        assertNull(days.isTradingDay(d))
        assertEquals(listOf(d), days.expected(d, d))
        assertNull(days.previous(d.plusDays(1)))
    }

    @Test
    fun `final zero-row holiday is skipped by previous, non-final zero row is unknown`() {
        val mon = LocalDate.of(2026, 10, 5); val tue = mon.plusDays(1); val wed = mon.plusDays(2)
        ledger.trading(listOf(mon)); ledger.holiday(listOf(tue))
        assertEquals(mon, days.previous(wed)) // 화 휴장 건너뜀
        assertFalse(days.isTradingDay(tue)!!)
        assertEquals(listOf(mon, wed), days.expected(mon, wed))
        assertEquals(listOf(mon), days.expected(mon, tue)) // 휴장은 기대 거래일이 아니다
        ledger.put("krx_open", "stk_bydd_trd|20261006|KOSPI", 0, false) // 아직 확정 아님
        assertNull(days.isTradingDay(tue))
        assertNull(days.previous(wed))
        assertTrue(days.isTradingDay(mon)!!)
        assertFalse(days.isTradingDay(mon.plusDays(5))!!) // 일요일
    }

    // I-5b: 휴장 의심(0행·미확정) 날은 뒷날 공개 후에 받은 0행일 때만 잠정 휴장.
    private val d = LocalDate.of(2026, 10, 6) // 화
    private val next = d.plusDays(1)
    private val t0 = java.time.Instant.parse("2026-10-07T00:00:00Z")
    private val services = listOf("stk_bydd_trd|KOSPI", "ksq_bydd_trd|KOSDAQ", "krx_dd_trd|KRX", "kospi_dd_trd|KOSPI", "kosdaq_dd_trd|KOSDAQ")

    private fun zeroRows(day: LocalDate, at: java.time.Instant, only: Int = services.size) =
        services.take(only).forEach { s ->
            val (svc, mkt) = s.split("|")
            ledger.putAt("krx_open", "$svc|${day.toString().replace("-", "")}|$mkt", 0, false, at)
        }

    private fun nextPublished(at: java.time.Instant) =
        ledger.putAt("krx_open", "stk_bydd_trd|${next.toString().replace("-", "")}|KOSPI", 1, true, at)

    @Test
    fun `zero rows fetched after the next day was published is a provisional holiday`() {
        nextPublished(t0)
        zeroRows(d, t0.plusSeconds(60))
        assertFalse(days.isTradingDay(d)!!)
        assertEquals(listOf(next), days.expected(d, next))
    }

    @Test
    fun `zero rows fetched before the next day was published stay unknown`() {
        nextPublished(t0)
        zeroRows(d, t0.minusSeconds(60))
        assertNull(days.isTradingDay(d))
        assertEquals(listOf(d, next), days.expected(d, next))
    }

    @Test
    fun `only some of the five calls zero stays unknown`() {
        nextPublished(t0)
        zeroRows(d, t0.plusSeconds(60), only = 4)
        assertNull(days.isTradingDay(d))
    }

    @Test
    fun `listed holiday with a zero-row record is a holiday the morning after, rows or no record still win`() {
        val listed = LocalTradingDays(ledger, com.quantfolio.market.KrxHolidays("2026-10-06"))
        ledger.trading(listOf(d.minusDays(1)))
        zeroRows(d, t0, only = 1) // 공개 전 조회와 구분할 뒷날 기록이 아직 없다
        assertNull(days.isTradingDay(d)) // 목록 없으면 모름
        assertNull(days.previous(next))
        assertFalse(listed.isTradingDay(d)!!)
        assertEquals(d.minusDays(1), listed.previous(next)) // 연휴 다음 날 직전 거래일
        ledger.put("krx_open", "stk_bydd_trd|20261006|KOSPI", 5, false)
        assertTrue(listed.isTradingDay(d)!!) // 행이 있으면 목록보다 원장
    }

    @Test
    fun `listed holiday without any ledger record stays unknown`() {
        val listed = LocalTradingDays(ledger, com.quantfolio.market.KrxHolidays("2026-10-06"))
        assertNull(listed.isTradingDay(d))
    }

    @Test
    fun `malformed holiday list fails at construction`() {
        org.junit.jupiter.api.assertThrows<java.time.format.DateTimeParseException> { com.quantfolio.market.KrxHolidays("2026-13-01") }
        assertEquals(setOf(LocalDate.of(2026, 12, 25), LocalDate.of(2026, 12, 31)), com.quantfolio.market.KrxHolidays(" 2026-12-25, 2026-12-31 ,").days)
    }
}
