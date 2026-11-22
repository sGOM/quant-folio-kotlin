package com.quantfolio.market

import com.quantfolio.common.MarketClock
import com.quantfolio.domain.TestApp
import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class MarketCalendarTest(@Autowired private val indexes: IndexOhlcvStore) {
    // 2026-10-19 10:00 KST = 오늘
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-19T01:00:00Z"))
    private val kisCalls = mutableListOf<LocalDate>()
    private var kis: (LocalDate) -> Map<LocalDate, Boolean> = { emptyMap() }
    private val calendar by lazy {
        MarketCalendar(LocalTradingDays(indexes, InMemoryFetchLedger()), { from -> kisCalls += from; kis(from) }, clock)
    }

    @BeforeEach
    fun loadIndex() {
        indexes.delete("1001")
        // 10-09(금)은 휴장, 10-16(금)까지 적재
        val days = listOf(5, 6, 7, 8, 12, 13, 14, 15, 16).map { LocalDate.of(2026, 10, it) }
        indexes.write("1001", days.map { IndexBar(it, close = 1.0) }, null)
    }

    @Test
    fun weekendIsClosedWithoutRequest() {
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 17)))
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 25)))
        assertTrue(kisCalls.isEmpty())
    }

    @Test
    fun `past business day from local index dates`() {
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 16)))
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 9))) // 지수 행 없는 평일 = 휴장
        assertTrue(kisCalls.isEmpty())
    }

    @Test
    fun `today holiday from KIS`() {
        kis = { mapOf(LocalDate.of(2026, 10, 19) to false, LocalDate.of(2026, 10, 20) to true) }
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 19)))
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 20))) // 미래도 KIS
        assertEquals(1, kisCalls.size) // 하루 캐시
    }

    @Test
    fun `KIS failure for today falls back to weekday rule and logs`() {
        kis = { error("vts 미지원") }
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 19)))
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 20)))
        assertEquals(1, kisCalls.size) // 실패 직후 재호출 폭주 없음
    }

    @Test
    fun `past holiday after last loaded index day comes from KIS`() {
        indexes.delete("1001")
        indexes.write("1001", listOf(12, 13, 14).map { IndexBar(LocalDate.of(2026, 10, it), close = 1.0) }, null)
        kis = { mapOf(LocalDate.of(2026, 10, 15) to false, LocalDate.of(2026, 10, 16) to true, LocalDate.of(2026, 10, 19) to true) }
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 15))) // 적재 범위 밖 과거 휴장일
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 16)))
        assertEquals(listOf(LocalDate.of(2026, 10, 15)), kisCalls) // 기준일 = 지수 최대일+1, 한 번
    }

    @Test
    fun `unloaded past day falls back to weekday rule when KIS is unavailable`() {
        indexes.delete("1001")
        indexes.write("1001", listOf(12, 13, 14).map { IndexBar(LocalDate.of(2026, 10, it), close = 1.0) }, null)
        kis = { error("vts 미지원") }
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 15)))
    }

    @Test
    fun `failed KIS lookup is retried after ten minutes`() {
        kis = { error("down") }
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 19)))
        clock.now = clock.now.plus(Duration.ofMinutes(9))
        calendar.isBusinessDay(LocalDate.of(2026, 10, 19))
        assertEquals(1, kisCalls.size)
        clock.now = clock.now.plus(Duration.ofMinutes(2))
        kis = { mapOf(LocalDate.of(2026, 10, 19) to false) }
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 19)))
        assertEquals(2, kisCalls.size)
    }

    @Test
    fun `KIS cache is refreshed when the day changes`() {
        kis = { from -> mapOf(from to true) }
        calendar.isBusinessDay(LocalDate.of(2026, 10, 19))
        calendar.isBusinessDay(LocalDate.of(2026, 10, 19))
        assertEquals(1, kisCalls.size)
        clock.now = clock.now.plus(Duration.ofDays(1))
        calendar.isBusinessDay(LocalDate.of(2026, 10, 20))
        assertEquals(listOf(LocalDate.of(2026, 10, 19), LocalDate.of(2026, 10, 20)), kisCalls)
    }

    @Test
    fun marketHoursInclusive() {
        kis = { mapOf(LocalDate.of(2026, 10, 19) to true) }
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 19, h, m, 0, 0, MarketClock.KST)
        assertFalse(calendar.isMarketOpen(at(8, 59)))
        assertTrue(calendar.isMarketOpen(at(9, 0)))
        assertTrue(calendar.isMarketOpen(at(15, 30)))
        assertFalse(calendar.isMarketOpen(at(15, 31)))
    }

    @Test
    fun estimatedTradingDays() {
        val days = MarketCalendar.estimatedTradingDays(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(365 * 5.0 / 7 - 365 * (15.0 / 365), days, 1e-9)
        assertEquals(0.0, MarketCalendar.estimatedTradingDays(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
    }
}
