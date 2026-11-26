package com.quantfolio.backtest

import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import org.junit.jupiter.api.Test
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

    private fun run(config: Map<String, Any?>, future: Int?, capPool: Boolean = false): Map<String, Any?> {
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
            pool = if (capPool) mcapPool(future) else null,
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

    /**
     * 시총 상위 후보풀(McapUniverse 와 같은 규칙: 결정일 d 는 직전 거래일의 시총만 본다)의 fake.
     * cut 이후 시총만 교란한다 — d 가 cut 이하면 직전일도 cut 이하라 풀이 같아야 한다.
     */
    private fun mcapPool(future: Int?): PoolProvider {
        fun cap(k: Int, i: Int): Double {
            val base = ((k * 7 + i / 5) % 10 + 1).toDouble()
            return if (future != null && dates[i].isAfter(cut)) base * (0.1 + Random(future * 1000 + k * n + i).nextDouble() * 10) else base
        }
        return { d ->
            val i = dates.indexOf(d)
            if (i <= 0) symbols.take(4) else symbols.indices.sortedWith(compareByDescending<Int> { cap(it, i - 1) }.thenBy { symbols[it] }).take(4).map { symbols[it] }
        }
    }

    @Test
    fun capUniversePoolFutureCapsDoNotChangePast() {
        val config = mapOf(
            "universe" to symbols, "selection" to mapOf("method" to "momentum", "lookback" to 40, "top_n" to 3),
            "cadence" to "weekly", "weighting" to "equal",
        )
        val base = run(config, null, capPool = true)
        for (seed in 1..3) {
            val alt = run(config, seed, capPool = true)
            for (key in listOf("equity_curve", "trades", "markers")) assertEquals(upToCut(base, key), upToCut(alt, key), "cap-pool/$key seed=$seed")
        }
        assertTrue(upToCut(base, "trades").isNotEmpty())
    }

    /**
     * 결측 패턴 교란(migration-notes #10): cut 전에 시작해 ffill 한도를 넘긴 결측이 cut 뒤 재개되든 끝까지 이어지든
     * cut 까지의 결과는 같아야 한다. 원본은 패널 전체의 마지막 유효일(lastValid)로 재개 여부를 미리 알고 동결/상장폐지를 갈랐다.
     */
    @Test
    fun missingRunResumingAfterCutDoesNotChangePast() {
        val config = mapOf("universe" to symbols, "selection" to mapOf("method" to "all"), "cadence" to "monthly", "weighting" to "equal")
        fun go(resumes: Boolean): Map<String, Any?> {
            val cols = symbols.withIndex().associate { (k, s) -> s to walk(k, 5000.0 + 3000 * k) }.toMutableMap()
            val gapStart = 320 // cut(340) 보다 20거래일 앞 — 기본 delisting_gap_days 10 을 cut 전에 넘긴다
            cols[symbols[0]] = cols.getValue(symbols[0]).mapIndexed { i, v -> if (i >= gapStart && !(resumes && dates[i].isAfter(cut))) Double.NaN else v }.toDoubleArray()
            return PortfolioBacktest.run(Panel(dates, symbols, cols), config, dates[260], dates.last())
        }
        val ends = go(resumes = false)
        val resumes = go(resumes = true)
        for (key in listOf("equity_curve", "trades", "markers")) assertEquals(upToCut(ends, key), upToCut(resumes, key), "missing-run/$key")
        assertTrue(upToCut(ends, "markers").any { it["type"] == "delist" && it["symbol"] == symbols[0] })
    }

    /** 배당 이벤트: cut 이하 배당락은 고정, cut 이후 이벤트(금액·추가 종목)만 시드마다 바꾼다 — cut 까지의 결과는 같아야 한다. */
    private fun dividends(future: Int?): Map<String, List<Map<String, Any?>>> = symbols.withIndex().associate { (k, s) ->
        val past = listOf(mapOf<String, Any?>("ex_date" to dates[270 + k], "dps" to 100.0 + k), mapOf("ex_date" to dates[330], "dps" to 50.0))
        val later = if (future == null) listOf(mapOf("ex_date" to dates[360 + k], "dps" to 80.0))
        else listOf(mapOf("ex_date" to dates[350 + k], "dps" to 1000.0 * future), mapOf("ex_date" to dates[380 + k], "dps" to 7.0 * future))
        s to past + later
    }

    @Test
    fun dividendEventsAfterCutDoNotChangePast() {
        val config = mapOf(
            "universe" to symbols, "selection" to mapOf("method" to "momentum", "lookback" to 40, "top_n" to 4),
            "cadence" to "weekly", "weighting" to "equal",
        )
        fun go(future: Int?): Map<String, Any?> {
            val panel = Panel(dates, symbols, symbols.withIndex().associate { (k, s) -> s to walk(k, 5000.0 + 3000 * k) })
            return PortfolioBacktest.run(panel, config + ("_dividends" to dividends(future)), dates[260], dates.last())
        }
        val base = go(null)
        for (seed in 1..3) {
            val alt = go(seed)
            for (key in listOf("equity_curve", "trades", "markers")) assertEquals(upToCut(base, key), upToCut(alt, key), "dividends/$key seed=$seed")
        }
        assertTrue((base["dividend_income"] as Double) > 0.0)
        assertTrue(upToCut(base, "trades").isNotEmpty())
    }
}
