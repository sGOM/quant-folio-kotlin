package com.quantfolio.backtest

import com.quantfolio.market.official.DividendDates
import com.quantfolio.quant.Panel
import com.quantfolio.strategy.StrategyConfigParser
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 현금배당 반영 — 합성 값만 쓴다. 배당락일 종가에 (전일까지 들고 있던 수량) × 주당배당 × (1 - 배당세) 를 현금으로 적립한다. */
class PortfolioDividendTest {
    private val sym = "000010"
    private val dates = generateSequence(LocalDate.of(2023, 1, 2)) { it.plusDays(1) }
        .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.take(260).toList()
    private val prices = DoubleArray(dates.size) { 10_000.0 + 10.0 * it }

    private fun config(extra: Map<String, Any?> = emptyMap()) = mapOf(
        "universe" to listOf(sym), "selection" to mapOf("method" to "all", "top_n" to 1), "cadence" to "monthly", "weighting" to "equal",
        "fees" to 0.0, "tax" to 0.0, "slippage_bps" to 0.0, "integer_shares" to false, "fill_mode" to "same_close", "capital" to 10_000_000.0,
    ) + extra

    private fun run(config: Map<String, Any?>) =
        PortfolioBacktest.run(Panel(dates, listOf(sym), mapOf(sym to prices)), config, dates.first(), dates.last())

    private fun event(exIdx: Int, dps: Double) = mapOf<String, Any?>("ex_date" to dates[exIdx], "dps" to dps)

    @Test
    fun `hold one stock one year total minus price-only return equals dps times one minus tax over entry price`() {
        val price = run(config())
        val tax = 0.154
        val withDiv = run(config(mapOf("_dividends" to mapOf(sym to listOf(event(120, 300.0))), "dividend_tax" to tax)))
        val diff = (withDiv["total_return"] as Double) - (price["total_return"] as Double)
        assertEquals(300.0 * (1 - tax) / prices[0], diff, 1e-9)
        assertEquals(300.0 * (1 - tax) / prices[0] * 10_000_000.0, withDiv["dividend_income"] as Double, 1e-3)
    }

    @Test
    fun `dividend tax defaults to 0_154 in engine and in parsed config`() {
        val price = run(config())
        val withDiv = run(config(mapOf("_dividends" to mapOf(sym to listOf(event(120, 300.0))))))
        assertEquals(300.0 * (1 - 0.154) / prices[0], (withDiv["total_return"] as Double) - (price["total_return"] as Double), 1e-9)
        val parsed = StrategyConfigParser.parse(mapOf("type" to "rebalance", "universe" to listOf("000010", "000020"), "selection" to mapOf("top_n" to 1)))
        assertEquals(0.154, parsed["dividend_tax"])
    }

    @Test
    fun `no dividends gives identical result to before`() {
        val before = run(config())
        val emptyMap = run(config(mapOf("_dividends" to emptyMap<String, List<Map<String, Any?>>>())))
        assertEquals(before, emptyMap)
        assertFalse(before.containsKey("dividend_income"))
    }

    @Test
    fun `not entitled when position is only taken on the ex date`() {
        // 첫 거래일 결정 → 다음 거래일(1) 종가 체결. 배당락일이 체결일(1)이면 전일까지 무보유라 못 받는다.
        val cfg = config(mapOf("fill_mode" to "next_close"))
        val out = run(cfg + mapOf("_dividends" to mapOf(sym to listOf(event(1, 300.0)))))
        assertEquals(0.0, out["dividend_income"])
        assertEquals(run(cfg)["total_return"], out["total_return"])
    }

    @Test
    fun `ex date is the trading day before the last trading day on or before the record date`() {
        // 2017-12-31(일) 기준일 → 마지막 거래일 12-28 → 배당락 12-27.
        val tradingA = setOf(LocalDate.of(2017, 12, 26), LocalDate.of(2017, 12, 27), LocalDate.of(2017, 12, 28))
        assertEquals(LocalDate.of(2017, 12, 27), DividendDates.exDate(LocalDate.of(2017, 12, 31)) { it in tradingA })
        // 2018-03-31(토) 기준일 → 마지막 거래일 03-30 → 배당락 03-29.
        val tradingB = setOf(LocalDate.of(2018, 3, 28), LocalDate.of(2018, 3, 29), LocalDate.of(2018, 3, 30))
        assertEquals(LocalDate.of(2018, 3, 29), DividendDates.exDate(LocalDate.of(2018, 3, 31)) { it in tradingB })
        // 기준일이 거래일이면 그냥 직전 거래일.
        assertEquals(LocalDate.of(2018, 3, 29), DividendDates.exDate(LocalDate.of(2018, 3, 30)) { it in tradingB })
        // 모르는 날을 만나면 추측하지 않고 null.
        assertNull(DividendDates.exDate(LocalDate.of(2018, 3, 30)) { if (it == LocalDate.of(2018, 3, 29)) null else true })
        assertTrue(DividendDates.exDate(LocalDate.of(2018, 3, 30)) { false } == null)
    }
}
