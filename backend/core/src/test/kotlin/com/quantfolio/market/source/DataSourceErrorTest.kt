package com.quantfolio.market.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DataSourceErrorTest {
    @Test
    fun causeProperties() {
        // 원본 errors.py 의 원인별 속성표
        assertEquals(false to 300.0, SourceAuthError("krx", "x").let { it.retryable to it.cooldownSeconds })
        assertEquals(false to 300.0, SourceQuotaError("dart", "x").let { it.retryable to it.cooldownSeconds })
        assertEquals(true to 60.0, SourceUnavailableError("krx", "x").let { it.retryable to it.cooldownSeconds })
        assertEquals(false to null, SourceSchemaError("krx", "x").let { it.retryable to it.cooldownSeconds })
        assertEquals(false to null, SourceRequestError("krx", "x").let { it.retryable to it.cooldownSeconds })
        assertEquals("[krx] 메시지", SourceAuthError("krx", "메시지").message)
        assertEquals("SourceUnavailableError", SourceUnavailableError("krx", "x").causeName)
    }

    @Test
    fun representativePicksPriorityAndCounts() {
        val errors = listOf(
            SourceUnavailableError("krx", "u1"),
            SourceSchemaError("krx", "s1"),
            SourceUnavailableError("krx", "u2"),
        )
        val rep = DataSourceErrors.representative(errors)
        assertIs<SourceSchemaError>(rep)
        assertTrue(rep.detail.startsWith("s1 — 총 3건 실패"))
        assertTrue("SourceUnavailableError" in rep.detail && "SourceSchemaError" in rep.detail)
    }

    @Test
    fun representativeKeepsRetryAfter() {
        val rep = DataSourceErrors.representative(listOf(SourceQuotaError("dart", "q", retryAfterSeconds = 99.0)))
        assertEquals(99.0, rep.retryAfterSeconds)
    }

    @Test
    fun representativeEmptyThrows() {
        assertThrows<IllegalArgumentException> { DataSourceErrors.representative(emptyList()) }
    }

    @Test
    fun copyWithDetailKeepsClass() {
        val e = SourceAuthError("krx", "a", retryAfterSeconds = 5.0).copyWithDetail("b")
        assertIs<SourceAuthError>(e)
        assertEquals("b", e.detail)
        assertEquals(5.0, e.retryAfterSeconds)
    }

    @Test
    fun secondsUntilMidnight() {
        // DART 한도는 KST 자정에 리셋된다 — 프로세스 시간대(UTC)와 무관하게 KST 23:00 이면 1시간(migration-notes #2)
        val clock = Clock.fixed(Instant.parse("2026-10-19T14:00:00Z"), ZoneId.of("UTC"))
        assertEquals(3600.0, DataSourceErrors.secondsUntilMidnight(clock))
        assertNull(SourceSchemaError("x", "y").retryAfterSeconds)
    }
}
