package com.quantfolio.quant

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PanelTest {
    private val d = (1..5).map { LocalDate.of(2026, 1, it) }
    private val panel = Panel(
        d, listOf("A", "B"),
        mapOf("A" to doubleArrayOf(1.0, 2.0, 3.0, 4.0, 5.0), "B" to doubleArrayOf(10.0, Double.NaN, 30.0, 40.0, 50.0)),
    )

    @Test
    fun upToHidesFuture() {
        val past = panel.upTo(d[2])
        assertEquals(d.take(3), past.dates)
        assertEquals(3, past.column("A").size)
        assertEquals(3.0, past.column("A").last())
        assertEquals(d[2], past.lastDate)
        assertNull(past.row(d[3])) // 이후 날짜는 존재하지 않는다
        assertEquals(mapOf("A" to 3.0, "B" to 30.0), past.row(d[2]))
    }

    @Test
    fun upToBeforeStartIsEmpty() {
        val none = panel.upTo(LocalDate.of(2025, 12, 31))
        assertTrue(none.dates.isEmpty())
        assertEquals(0, none.column("A").size)
    }

    @Test
    fun upToBetweenDatesUsesLastAvailable() {
        val weekendLike = Panel(listOf(d[0], d[2]), listOf("A"), mapOf("A" to doubleArrayOf(1.0, 3.0)))
        assertEquals(listOf(d[0]), weekendLike.upTo(d[1]).dates)
    }

    @Test
    fun missingIsNaN() {
        assertTrue(panel.column("B")[1].isNaN())
        assertTrue(panel.row(d[1])!!.getValue("B").isNaN())
    }

    @Test
    fun betweenInclusive() {
        assertEquals(d.subList(1, 4), panel.between(d[1], d[3]).dates)
    }

    @Test
    fun rejectsUnsortedOrDuplicateDates() {
        assertThrows<IllegalArgumentException> { Panel(listOf(d[1], d[0]), listOf("A"), mapOf("A" to doubleArrayOf(1.0, 2.0))) }
        assertThrows<IllegalArgumentException> { Panel(listOf(d[0], d[0]), listOf("A"), mapOf("A" to doubleArrayOf(1.0, 2.0))) }
    }

    @Test
    fun fromSeriesAlignsOnUnionOfDates() {
        val a = Series(listOf(d[0], d[1]), doubleArrayOf(1.0, 2.0))
        val b = Series(listOf(d[1], d[2]), doubleArrayOf(20.0, 30.0))
        val p = Panel.fromSeries(mapOf("A" to a, "B" to b))
        assertEquals(d.take(3), p.dates)
        assertTrue(p.column("A")[2].isNaN())
        assertTrue(p.column("B")[0].isNaN())
        assertTrue(p.hasSymbol("B"))
    }
}
