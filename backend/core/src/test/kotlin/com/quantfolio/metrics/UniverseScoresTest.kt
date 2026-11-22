package com.quantfolio.metrics

import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockPeriod
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 리밸런싱 점수 조립부: 전량 실패 가드, flow_norm 분모 규칙, 모멘텀 결측 종목 제외. */
class UniverseScoresTest {
    private val asOf = LocalDate.of(2026, 10, 16)

    private class Src(var pc: List<StockPeriod>, var net: List<StockPeriod> = emptyList(), var caps: List<StockDaily> = emptyList()) : SnapshotSource {
        override fun fundamentals(asOf: LocalDate, markets: List<String>) = emptyList<StockDaily>()
        override fun marketCap(asOf: LocalDate, markets: List<String>) = caps
        override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>) = pc
        override fun netPurchases(start: LocalDate, end: LocalDate, markets: List<String>, investors: List<String>) = net
        override fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>? = null
        override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? = null
        override fun indexTickers(day: LocalDate, market: String) = emptyList<String>()
        override fun indexName(day: LocalDate, ticker: String) = ""
    }

    private val noInputs = object : FactorInputs {
        override fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean) = emptyMap<String, Map<String, Any?>>()
        override fun peadSue(codes: List<String>, asOf: LocalDate, lookbackQ: Int) = emptyMap<String, Double>()
        override fun marketCaps(asOf: LocalDate) = emptyMap<String, Long>()
        override fun sectorMap(asOf: LocalDate) = emptyMap<String, String>()
        override fun dartEnabled() = false
    }
    private val noTech = TechIndicatorLoader { _, _, _ -> emptyList() }

    private fun scores(src: Src) = UniverseScores(src, noTech, noInputs) { _, _, _ -> emptyList() }

    @Test
    fun momentumAllEmptyAborts() {
        val e = assertThrows<IllegalStateException> { scores(Src(emptyList())).compute(listOf("5930"), asOf) }
        assertTrue(e.message!!.startsWith("모멘텀 팩터 데이터 전량 조회 실패"))
    }

    @Test
    fun weightedFlowWithNoNetPurchasesAborts() {
        val src = Src(listOf(StockPeriod("005930", changePct = 1.0)))
        val w = mapOf("momentum" to 0.5, "value" to 0.0, "lowvol" to 0.0, "flow" to 0.5)
        assertThrows<IllegalStateException> { scores(src).compute(listOf("005930"), asOf, UniverseScoreParams(factorWeights = w)) }
    }

    @Test
    fun flowNormSkipsMissingAndNonPositiveDenominators() {
        val src = Src(
            emptyList(),
            net = listOf(StockPeriod("000010", netBuyValue = 50.0), StockPeriod("000020", netBuyValue = 5.0), StockPeriod("000030", netBuyValue = 1.0)),
            caps = listOf(StockDaily("000010", marketCap = 1000), StockDaily("000020", marketCap = 0)),
        )
        assertEquals(mapOf("000010" to 0.05), scores(src).flowNorm(listOf("10", "20", "30", "40"), asOf))
    }

    @Test
    fun codesWithoutSixMonthReturnAreDropped() {
        val pc = listOf(StockPeriod("000010", changePct = 10.0), StockPeriod("000020", changePct = -5.0), StockPeriod("000030", changePct = 2.0))
        val out = scores(Src(pc)).compute(listOf("10", "20", "30", "40"), asOf)
        assertEquals(setOf("000010", "000020", "000030"), out.keys)
        assertTrue(out.getValue("000010") > out.getValue("000020"))
    }

    private fun gated(inputs: FactorInputs, src: Src) = UniverseScores(src, noTech, inputs) { _, _, _ -> emptyList() }
    private fun inputsWith(q: Map<String, Map<String, Any?>>) = object : FactorInputs by noInputs {
        override fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean) = q
    }
    private val qualityWeights = mapOf("momentum" to 1.0, "value" to 0.0, "quality" to 1.0)

    @Test
    fun `coverage gate rejects quality below half of the universe`() {
        val src = Src(listOf("1", "2", "3", "4").map { StockPeriod(it.padStart(6, '0'), changePct = 1.0) })
        val q = mapOf("000001" to mapOf<String, Any?>("roe" to 10.0)) // 1/4 = 25%
        val e = assertThrows<com.quantfolio.market.official.MarketDataNotLoaded> {
            gated(inputsWith(q), src).compute(listOf("1", "2", "3", "4"), asOf, UniverseScoreParams(factorWeights = qualityWeights, requireCoverage = true))
        }
        assertEquals("factor_coverage", e.kind)
        // 관문이 꺼져 있으면(웹·기존 호출자) 기존 동작 — 점수가 나온다
        gated(inputsWith(q), src).compute(listOf("1", "2", "3", "4"), asOf, UniverseScoreParams(factorWeights = qualityWeights))
    }

    @Test
    fun `coverage gate passes at half and skips weightless factors and empty universe`() {
        val src = Src(listOf("1", "2", "3", "4").map { StockPeriod(it.padStart(6, '0'), changePct = 1.0) })
        val q = mapOf("000001" to mapOf<String, Any?>("roe" to 10.0), "000002" to mapOf<String, Any?>("roe" to 5.0)) // 2/4 = 50%
        val p = UniverseScoreParams(factorWeights = qualityWeights, requireCoverage = true)
        gated(inputsWith(q), src).compute(listOf("1", "2", "3", "4"), asOf, p)
        gated(inputsWith(emptyMap()), src).compute(listOf("1", "2", "3", "4"), asOf, UniverseScoreParams(factorWeights = mapOf("momentum" to 1.0), requireCoverage = true))
        assertEquals(emptyMap(), gated(inputsWith(emptyMap()), src).compute(emptyList(), asOf, p))
    }
}
