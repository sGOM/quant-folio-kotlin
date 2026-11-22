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
}
