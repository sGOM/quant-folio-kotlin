package com.quantfolio.metrics

import com.quantfolio.market.MarketCalendar
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class MetricsCommonTest {
    /** KST 화요일 01:00(UTC 로는 월요일) 의 전 영업일은 월요일 — 원본은 UTC 날짜라 금요일이었다(migration-notes #8). */
    @Test
    fun lastBusinessDayUsesKstToday() {
        val clock = Clock.fixed(Instant.parse("2026-10-19T16:00:00Z"), ZoneOffset.UTC)
        val common = MetricsCommon(MarketCalendar({ true }, { emptyMap() }, clock), clock)
        assertEquals(LocalDate.of(2026, 10, 19), common.lastBusinessDay())
    }
}
