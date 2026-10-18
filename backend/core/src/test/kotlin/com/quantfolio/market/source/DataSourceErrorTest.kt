package com.quantfolio.market.source

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.HttpServerErrorException
import org.springframework.web.client.ResourceAccessException
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
    fun classifyHttp() {
        fun client(code: HttpStatus) = HttpClientErrorException.create(code, "", HttpHeaders(), ByteArray(0), null)
        assertIs<SourceQuotaError>(DataSourceErrors.classifyHttp("s", client(HttpStatus.TOO_MANY_REQUESTS)))
        assertIs<SourceAuthError>(DataSourceErrors.classifyHttp("s", client(HttpStatus.UNAUTHORIZED)))
        assertIs<SourceAuthError>(DataSourceErrors.classifyHttp("s", client(HttpStatus.FORBIDDEN)))
        assertIs<SourceRequestError>(DataSourceErrors.classifyHttp("s", client(HttpStatus.BAD_REQUEST)))
        assertIs<SourceUnavailableError>(
            DataSourceErrors.classifyHttp("s", HttpServerErrorException.create(HttpStatus.BAD_GATEWAY, "", HttpHeaders(), ByteArray(0), null)),
        )
        assertIs<SourceUnavailableError>(DataSourceErrors.classifyHttp("s", ResourceAccessException("timeout")))
        assertIs<SourceUnavailableError>(DataSourceErrors.classifyHttp("s", IllegalStateException("?")))
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
        val clock = Clock.fixed(Instant.parse("2026-10-19T23:00:00Z"), ZoneId.of("UTC"))
        assertEquals(3600.0, DataSourceErrors.secondsUntilMidnight(clock))
        assertNull(SourceSchemaError("x", "y").retryAfterSeconds)
    }
}
