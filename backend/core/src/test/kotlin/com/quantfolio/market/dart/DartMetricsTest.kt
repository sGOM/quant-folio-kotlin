package com.quantfolio.market.dart

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 원본 tests/test_opendart.py 의 파생지표 시나리오. */
class DartMetricsTest {
    private fun row(sj: String, id: String, nm: String, amt: String) =
        mapOf<String, Any?>("sj_div" to sj, "account_id" to id, "account_nm" to nm, "thstrm_amount" to amt)

    private val samsung2023 = listOf(
        row("BS", "ifrs-full_Assets", "자산총계", "455,905,980,000,000"),
        row("BS", "ifrs-full_Liabilities", "부채총계", "92,228,115,000,000"),
        row("BS", "ifrs-full_Equity", "자본총계", "363,677,865,000,000"),
        row("IS", "dart_OperatingIncomeLoss", "영업이익", "6,566,976,000,000"),
        row("IS", "ifrs-full_ProfitLoss", "당기순이익(손실)", "15,487,100,000,000"),
        row("IS", "ifrs-full_ProfitLossAttributableToOwnersOfParent", "지배기업의 소유주에게 귀속되는 당기순이익(손실)", "14,473,401,000,000"),
        row("CF", "ifrs-full_CashFlowsFromUsedInOperatingActivities", "영업활동현금흐름", "44,137,427,000,000"),
        row("CF", "ifrs-full_PurchaseOfPropertyPlantAndEquipmentClassifiedAsInvestingActivities", "유형자산의 취득", "57,611,292,000,000"),
        row("CF", "ifrs-full_PurchaseOfIntangibleAssetsClassifiedAsInvestingActivities", "무형자산의 취득", "2,922,875,000,000"),
    )

    @Test
    fun coreValues() {
        val m = DartMetrics.deriveMetrics(samsung2023)
        assertEquals(15_487_100_000_000.0, m["net_income"])
        assertEquals(6_566_976_000_000.0, m["op_income"])
        assertEquals(363_677_865_000_000.0, m["equity"])
        assertEquals(15_487_100_000_000.0 / 363_677_865_000_000.0, m["roe"]!!, 1e-12)
        assertEquals(92_228_115_000_000.0 / 363_677_865_000_000.0, m["debt_ratio"]!!, 1e-12)
        assertEquals(15_487_100_000_000.0 / 455_905_980_000_000.0, m["roa"]!!, 1e-12)
        assertEquals(44_137_427_000_000.0 - (57_611_292_000_000.0 + 2_922_875_000_000.0), m["fcf"])
    }

    @Test
    fun cisFallback() {
        val m = DartMetrics.deriveMetrics(
            listOf(
                row("BS", "ifrs-full_Equity", "자본총계", "24,000,000,000,000"),
                row("CIS", "dart_OperatingIncomeLoss", "영업이익", "1,488,820,269,608"),
                row("CIS", "ifrs-full_ProfitLoss", "당기순이익", "985,017,762,493"),
            ),
        )
        assertEquals(1_488_820_269_608.0, m["op_income"])
        assertEquals(985_017_762_493.0, m["net_income"])
    }

    @Test
    fun nameFallbackWhenIdMissing() {
        val m = DartMetrics.deriveMetrics(
            listOf(
                row("BS", "", "자본총계", "1,000"), row("BS", "", "부채총계", "500"), row("BS", "", "자산총계", "1,500"),
                row("IS", "", "영업이익", "200"), row("IS", "", "당기순이익(손실)", "150"),
            ),
        )
        assertEquals(200.0, m["op_income"])
        assertEquals(150.0, m["net_income"])
        assertEquals(0.5, m["debt_ratio"])
        assertEquals(0.15, m["roe"])
    }

    @Test
    fun negativeEquityRatiosNull() {
        val m = DartMetrics.deriveMetrics(
            listOf(
                row("BS", "ifrs-full_Equity", "자본총계", "-1,000"),
                row("BS", "ifrs-full_Liabilities", "부채총계", "5,000"),
                row("IS", "ifrs-full_ProfitLoss", "당기순이익", "100"),
            ),
        )
        assertNull(m["roe"])
        assertNull(m["debt_ratio"])
    }

    @Test
    fun capexRules() {
        val ocf = row("CF", "ifrs-full_CashFlowsFromUsedInOperatingActivities", "영업활동현금흐름", "1,000")
        assertNull(DartMetrics.deriveMetrics(listOf(ocf))["fcf"])
        val partial = DartMetrics.deriveMetrics(listOf(ocf, row("CF", "ifrs-full_PurchaseOfPropertyPlantAndEquipment", "유형자산의 취득", "300")))
        assertEquals(700.0, partial["fcf"])
    }

    @Test
    fun emptyAllNull() {
        val m = DartMetrics.deriveMetrics(emptyList())
        assertEquals(14, m.size)
        assertTrue(m.values.all { it == null })
    }

    @Test
    fun toNumber() {
        assertEquals(1234567.0, DartMetrics.toNumber("1,234,567"))
        assertEquals(-500.0, DartMetrics.toNumber("-500"))
        assertNull(DartMetrics.toNumber(""))
        assertNull(DartMetrics.toNumber("-"))
        assertNull(DartMetrics.toNumber(null))
    }

    @Test
    fun reportPeriods() {
        assertEquals(2023, DartMetrics.announcementLaggedYear(LocalDate.of(2025, 3, 31)))
        assertEquals(2024, DartMetrics.announcementLaggedYear(LocalDate.of(2025, 4, 1)))
        assertEquals(2023 to DartMetrics.REPORT_ANNUAL, DartMetrics.latestReportPeriod(LocalDate.of(2025, 3, 10)))
        assertEquals(2024 to DartMetrics.REPORT_ANNUAL, DartMetrics.latestReportPeriod(LocalDate.of(2025, 4, 10)))
        assertEquals(2024 to DartMetrics.REPORT_ANNUAL, DartMetrics.latestReportPeriod(LocalDate.of(2025, 5, 15)))
        assertEquals(2025 to DartMetrics.REPORT_Q1, DartMetrics.latestReportPeriod(LocalDate.of(2025, 5, 16)))
        assertEquals(2025 to DartMetrics.REPORT_HALF, DartMetrics.latestReportPeriod(LocalDate.of(2025, 8, 16)))
        assertEquals(2025 to DartMetrics.REPORT_Q3, DartMetrics.latestReportPeriod(LocalDate.of(2025, 11, 16)))
    }

    @Test
    fun countLosses() {
        assertEquals(2, DartMetrics.countLosses(listOf(10.0, -5.0, -1.0)))
        assertEquals(1, DartMetrics.countLosses(listOf(null, -5.0)))
        assertNull(DartMetrics.countLosses(listOf(null, null)))
    }

    private fun fin(vararg kv: Pair<String, Double>) = mapOf(
        "roa" to null, "cfo" to null, "assets" to null, "liabilities" to null, "current_assets" to null,
        "current_liabilities" to null, "revenue" to null, "gross_profit" to null,
    ) + kv.toMap()

    @Test
    fun piotroski() {
        val prev = fin("roa" to 0.05, "cfo" to 50.0, "assets" to 1000.0, "liabilities" to 600.0, "current_assets" to 300.0,
            "current_liabilities" to 200.0, "revenue" to 800.0, "gross_profit" to 200.0)
        val cur = fin("roa" to 0.10, "cfo" to 150.0, "assets" to 1000.0, "liabilities" to 500.0, "current_assets" to 400.0,
            "current_liabilities" to 200.0, "revenue" to 1000.0, "gross_profit" to 300.0)
        assertEquals(8, DartMetrics.piotroskiFScore(cur, prev))
        val weakPrev = fin("roa" to 0.10, "cfo" to 100.0, "assets" to 1000.0, "liabilities" to 400.0, "current_assets" to 400.0,
            "current_liabilities" to 200.0, "revenue" to 1000.0, "gross_profit" to 300.0)
        val weakCur = fin("roa" to -0.05, "cfo" to -60.0, "assets" to 1000.0, "liabilities" to 600.0, "current_assets" to 300.0,
            "current_liabilities" to 250.0, "revenue" to 800.0, "gross_profit" to 180.0)
        assertEquals(0, DartMetrics.piotroskiFScore(weakCur, weakPrev))
        assertNull(DartMetrics.piotroskiFScore(fin("roa" to 0.1), fin()))
    }

    private fun accounts(revenue: Int, op: Int, net: Int, assets: Int, liabilities: Int, equity: Int) = listOf(
        row("BS", "ifrs-full_Assets", "자산총계", "$assets"), row("BS", "ifrs-full_Liabilities", "부채총계", "$liabilities"),
        row("BS", "ifrs-full_Equity", "자본총계", "$equity"), row("IS", "ifrs-full_Revenue", "매출액", "$revenue"),
        row("IS", "dart_OperatingIncomeLoss", "영업이익", "$op"), row("IS", "ifrs-full_ProfitLoss", "당기순이익", "$net"),
    )

    @Test
    fun combineTtm() {
        val cur = DartMetrics.deriveMetrics(accounts(100, 20, 10, 2000, 800, 1200))
        val prevSame = DartMetrics.deriveMetrics(accounts(80, 15, 8, 1800, 750, 1050))
        val prevAnnual = DartMetrics.deriveMetrics(accounts(400, 70, 40, 1900, 770, 1130))
        val c = DartMetrics.combineTtm(cur, prevSame, prevAnnual)
        assertEquals(420.0, c["revenue"])
        assertEquals(42.0, c["net_income"])
        assertEquals(2000.0, c["assets"])
        assertEquals(42.0 / 1200, c["roe"]!!, 1e-12)
        val empty = DartMetrics.deriveMetrics(emptyList())
        val partial = DartMetrics.combineTtm(cur, empty, empty)
        assertNull(partial["revenue"])
        assertEquals(2000.0, partial["assets"])
    }

    @Test
    fun parsePeriodicReport() {
        assertEquals(Triple(LocalDate.of(2024, 5, 14), 2024, DartMetrics.REPORT_Q1), DartMetrics.parsePeriodicReport("분기보고서 (2024.03)", "20240514"))
        assertEquals(Triple(LocalDate.of(2024, 3, 12), 2023, DartMetrics.REPORT_ANNUAL), DartMetrics.parsePeriodicReport("[기재정정]사업보고서 (2023.12)", "20240312"))
        assertNull(DartMetrics.parsePeriodicReport("주요사항보고서", "20240312"))
        assertNull(DartMetrics.parsePeriodicReport("분기보고서 (2024.03)", "2024"))
    }

    @Test
    fun growthAndTurnaround() {
        assertEquals(0.5, DartMetrics.yoyGrowth(150.0, 100.0))
        assertNull(DartMetrics.yoyGrowth(150.0, -100.0))
        assertEquals(1.0, DartMetrics.turnaroundFlag(10.0, -5.0))
        assertEquals(0.0, DartMetrics.turnaroundFlag(10.0, 5.0))
        assertNull(DartMetrics.turnaroundFlag(null, 5.0))
    }
}
