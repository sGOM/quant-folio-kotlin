package com.quantfolio.market.store

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class CacheKeysTest {
    @Test
    fun keyIsDeterministic() {
        assertEquals("20260102|KOSDAQ,KOSPI|x", CacheKeys.of(LocalDate.of(2026, 1, 2), listOf("KOSPI", "KOSDAQ"), "x"))
        assertEquals(CacheKeys.of(listOf("b", "a")), CacheKeys.of(setOf("a", "b")))
    }
}
