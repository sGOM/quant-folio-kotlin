package com.quantfolio.common

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals

class MarketClockTest {
    @Test
    fun todayKstAfterMidnight() {
        // 00:30 KST = 전날 15:30 UTC. UTC 컨테이너에서도 KST 날짜가 나와야 한다.
        val clock = Clock.fixed(Instant.parse("2026-10-18T15:30:00Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 19), MarketClock.todayKst(clock))
    }

    @Test
    fun todayKstBeforeMidnight() {
        val clock = Clock.fixed(Instant.parse("2026-10-18T14:59:59Z"), ZoneOffset.UTC)
        assertEquals(LocalDate.of(2026, 10, 18), MarketClock.todayKst(clock))
    }
}
