package com.quantfolio.market.official

import com.quantfolio.market.store.StockDaily
import org.junit.jupiter.api.Test
import java.time.Instant
import java.time.LocalDate
import kotlin.test.assertEquals

class AdjustedBarsTest {
    private val d1 = LocalDate.of(2026, 1, 5)
    private val d2 = LocalDate.of(2026, 1, 6)
    private val d3 = LocalDate.of(2026, 1, 7)

    private fun row(close: Double?, prevDiff: Double?, o: Double? = close, h: Double? = close, l: Double? = close, v: Long = 10) =
        StockDaily("005930", open = o, high = h, low = l, close = close, volume = v, prevDiff = prevDiff)

    @Test
    fun `split day uses prevDiff`() {
        val bars = AdjustedBars.build(listOf(row(2_650_000.0, null), row(53_000.0, 0.0)), listOf(d1, d2))
        assertEquals(53_000.0, bars[0].close, 1e-6)
        assertEquals(53_000.0, bars[1].close, 1e-6)
    }

    @Test
    fun `last bar equals raw close and time is utc midnight`() {
        val bars = AdjustedBars.build(listOf(row(100.0, null), row(110.0, 10.0)), listOf(d1, d2))
        assertEquals(110.0, bars.last().close, 1e-9)
        assertEquals(Instant.parse("2026-01-06T00:00:00Z"), bars.last().time)
        assertEquals(100.0, bars[0].close, 1e-9)
    }

    @Test
    fun `ohlc scaled by same factor, volume raw`() {
        val a = row(2_650_000.0, null, o = 2_600_000.0, h = 2_700_000.0, l = 2_500_000.0, v = 7)
        val bars = AdjustedBars.build(listOf(a, row(53_000.0, 0.0)), listOf(d1, d2))
        assertEquals(52_000.0, bars[0].open, 1e-6)
        assertEquals(54_000.0, bars[0].high, 1e-6)
        assertEquals(50_000.0, bars[0].low, 1e-6)
        assertEquals(7L, bars[0].volume)
    }

    @Test
    fun `halted day with zero ohl and zero volume is flattened to close, not dropped`() {
        val halted = row(100.0, 0.0, o = 0.0, h = 0.0, l = 0.0, v = 0)
        val bars = AdjustedBars.build(listOf(row(100.0, null), halted), listOf(d1, d2))
        assertEquals(listOf(100.0, 100.0, 100.0, 100.0), bars[1].let { listOf(it.open, it.high, it.low, it.close) })
    }

    @Test
    fun `null prevDiff starts new chain without NaN`() {
        val bars = AdjustedBars.build(
            listOf(row(100.0, null), row(50.0, 0.0), row(55.0, null), row(60.0, 5.0)), listOf(d1, d2, d3, d3.plusDays(1)),
        )
        assertEquals(listOf(50.0, 50.0, 55.0, 60.0), bars.map { it.close })
    }

    @Test
    fun `close minus prevDiff zero or negative is treated as null prevDiff`() {
        val bars = AdjustedBars.build(listOf(row(100.0, null), row(50.0, 50.0), row(60.0, 70.0)), listOf(d1, d2, d3))
        assertEquals(listOf(100.0, 50.0, 60.0), bars.map { it.close })
    }

    @Test
    fun `null close rows are skipped not zeroed`() {
        val bars = AdjustedBars.build(listOf(row(100.0, null), row(null, null), row(110.0, 10.0)), listOf(d1, d2, d3))
        assertEquals(listOf(d1, d3), bars.map { it.date })
    }
}
