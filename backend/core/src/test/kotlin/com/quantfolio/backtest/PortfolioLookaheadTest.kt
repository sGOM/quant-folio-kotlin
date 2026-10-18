package com.quantfolio.backtest

import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.LocalDate
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * 미래참조 금지 인수 테스트(CLAUDE.md 절대 규칙 1): cut 이후의 가격·지수·패닉·거래량을 모두 바꿔도
 * cut 까지의 자산곡선·거래·마커가 한 글자도 달라지면 안 된다.
 */
class PortfolioLookaheadTest {
    private val n = 420
    private val dates = generateSequence(LocalDate.of(2023, 1, 2)) { d -> d.plusDays(if (d.dayOfWeek.value == 5) 3 else 1) }.take(n).toList()
    private val symbols = (1..10).map { "%06d".format(it * 10) }
    private val cut = dates[340]

    private fun walk(seed: Int, start: Double): DoubleArray {
        val r = Random(seed)
        var p = start
        return DoubleArray(n) { p *= 1 + (r.nextDouble() - 0.5) * 0.04; p }
    }

    /** cut 이후만 다른 값으로 바꾼 복사본. */
    private fun perturb(a: DoubleArray, seed: Int): DoubleArray {
        val r = Random(seed)
        return DoubleArray(n) { if (dates[it].isAfter(cut)) a[it] * (0.5 + r.nextDouble()) else a[it] }
    }

    private fun run(config: Map<String, Any?>, future: Int?): Map<String, Any?> {
        fun f(a: DoubleArray, s: Int) = if (future == null) a else perturb(a, future * 100 + s)
        val panel = Panel(dates, symbols, symbols.withIndex().associate { (k, s) -> s to f(walk(k, 5000.0 + 3000 * k), k) })
        val volume = Panel(dates, symbols, symbols.withIndex().associate { (k, s) -> s to f(walk(50 + k, 10_000.0), 50 + k) })
        val index = f(walk(99, 2500.0), 99)
        val panic = dates.indices.map { i ->
            val crash = if (future != null && dates[i].isAfter(cut)) i % 7 == 0 else i == 300
            PanicDay(dates[i], 0.0, if (crash) "panic" else "normal", false, crash, index[i], index[i] * 0.99, -0.05)
        }
        return PortfolioBacktest.run(
            panel, config, dates[260], dates.last(),
            fundamentals = { _, codes -> codes.withIndex().associate { (k, c) -> c to mapOf("PER" to 5.0 + k, "PBR" to 1.0, "DIV" to 2.0) } },
            regime = Series(dates, index), benchmark = Series(dates, index), panic = panic, volume = volume,
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun upToCut(out: Map<String, Any?>, key: String) =
        (out[key] as List<Map<String, Any?>>).filter { !LocalDate.parse((it["t"] as String).take(10)).isAfter(cut) }

    @ParameterizedTest
    @ValueSource(strings = ["momentum", "score", "all"])
    fun futureDataDoesNotChangePast(method: String) {
        val config = mapOf(
            "universe" to symbols, "selection" to mapOf("method" to method, "lookback" to 40, "top_n" to 4),
            "cadence" to "weekly", "weighting" to "inverse_vol", "slippage_vol_scale" to 0.5, "adv_participation_cap" to 0.01,
            "regime_filter" to mapOf("enabled" to true, "ma_period" to 30),
            "panic_overlay" to mapOf("enabled" to true, "arm_window" to 5, "hold_days" to 10),
            "risk_layer" to mapOf("max_position_pct" to 0.3, "target_vol" to 0.15, "mdd_kill_pct" to 0.1),
        )
        val base = run(config, null)
        for (seed in 1..3) {
            val alt = run(config, seed)
            for (key in listOf("equity_curve", "trades", "markers")) assertEquals(upToCut(base, key), upToCut(alt, key), "$method/$key seed=$seed")
            assertTrue(upToCut(base, "trades").isNotEmpty())
        }
    }
}
