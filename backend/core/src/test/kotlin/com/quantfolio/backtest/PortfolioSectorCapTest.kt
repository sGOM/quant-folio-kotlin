package com.quantfolio.backtest

import com.quantfolio.quant.Panel
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** 섹터 한도는 결정일 이하의 업종 스냅샷으로만 건다 — 스냅샷이 없는 결정일(null)은 캡 없이 진행(migration-notes #12). */
class PortfolioSectorCapTest {
    private val symbols = (1..6).map { "%06d".format(it * 10) }
    private val dates = generateSequence(LocalDate.of(2023, 1, 2)) { it.plusDays(1) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.take(120).toList()
    private val panel = Panel(dates, symbols, symbols.withIndex().associate { (k, s) -> s to DoubleArray(dates.size) { 1000.0 * (k + 1) + it } })
    private val firstSnapshot = dates[60]
    private val config = mapOf(
        "universe" to symbols, "selection" to mapOf("method" to "all"), "cadence" to "monthly", "weighting" to "equal",
        "fees" to 0.0, "tax" to 0.0, "slippage_bps" to 0.0, "integer_shares" to false, "fill_mode" to "same_close",
        "risk_layer" to mapOf("max_sector_pct" to 0.3),
    )

    @Suppress("UNCHECKED_CAST")
    private fun trades(out: Map<String, Any?>, before: Boolean) =
        (out["trades"] as List<Map<String, Any?>>).filter { LocalDate.parse((it["t"] as String).take(10)).isBefore(firstSnapshot) == before }

    @Test
    fun capStartsOnFirstDecisionWithASnapshot() {
        val uncapped = PortfolioBacktest.run(panel, config, dates.first(), dates.last())
        val out = PortfolioBacktest.run(panel, config, dates.first(), dates.last(), sectors = { d ->
            if (d.isBefore(firstSnapshot)) null else symbols.associateWith { "전기전자" }
        })
        assertEquals(trades(uncapped, before = true), trades(out, before = true)) // 스냅샷 전: 캡 없음
        assertNotEquals(trades(uncapped, before = false), trades(out, before = false)) // 스냅샷 뒤: 한 업종 30% 상한
    }
}
