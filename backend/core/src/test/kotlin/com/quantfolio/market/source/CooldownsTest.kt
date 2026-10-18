package com.quantfolio.market.source

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CooldownsTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
    private val cooldowns = Cooldowns(clock)

    @Test
    fun noteFailureUsesDefaultCooldown() {
        cooldowns.noteFailure(SourceAuthError("krx", "x"))
        assertEquals(300.0, cooldowns.remaining("krx"))
        clock.now = clock.now.plus(Duration.ofSeconds(301))
        assertEquals(0.0, cooldowns.remaining("krx"))
    }

    @Test
    fun noteFailureUsesRetryAfterOverDefault() {
        cooldowns.noteFailure(SourceQuotaError("dart", "x", retryAfterSeconds = 10.0))
        assertEquals(10.0, cooldowns.remaining("dart"))
    }

    @Test
    fun schemaAndRequestNoCooldown() {
        cooldowns.noteFailure(SourceSchemaError("krx", "x"))
        cooldowns.noteFailure(SourceRequestError("krx", "x"))
        assertEquals(0.0, cooldowns.remaining("krx"))
    }

    @Test
    fun clearRemoves() {
        cooldowns.noteFailure(SourceUnavailableError("krx", "x"))
        cooldowns.clear("krx")
        assertEquals(0.0, cooldowns.remaining("krx"))
    }

    @Test
    fun stopAggregateDuringCooldown() {
        val err = SourceUnavailableError("krx", "x")
        cooldowns.noteFailure(err)
        assertTrue(cooldowns.stopAggregate("krx", listOf(err), ok = 5))
    }

    @Test
    fun noStopWithoutErrors() {
        assertFalse(cooldowns.stopAggregate("krx", emptyList(), ok = 0))
    }

    @Test
    fun stopAggregateOnThreeSchemaWithNoSuccess() {
        val errors = List(3) { SourceSchemaError("dart", "s$it") }
        assertFalse(cooldowns.stopAggregate("dart", errors.take(2), ok = 0))
        assertTrue(cooldowns.stopAggregate("dart", errors, ok = 0))
    }

    @Test
    fun noStopWhenAnySuccess() {
        assertFalse(cooldowns.stopAggregate("dart", List(5) { SourceSchemaError("dart", "s") }, ok = 1))
    }

    @Test
    fun noStopOnRequestErrors() {
        // Request 오류는 종목별 인자 문제일 수 있어 부분 결과를 지키려 계속한다.
        assertFalse(cooldowns.stopAggregate("dart", List(5) { SourceRequestError("dart", "r") }, ok = 0))
    }
}
