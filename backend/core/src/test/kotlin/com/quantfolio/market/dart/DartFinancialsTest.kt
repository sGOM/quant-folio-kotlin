package com.quantfolio.market.dart

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.HttpBytes
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.sqrt
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class DartFinancialsTest(@Autowired private val store: DartFinancialStore) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val cooldowns = Cooldowns(clock)
    private val ledger = InMemoryFetchLedger()
    private val client = OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K")), cooldowns, clock)
    private val fin = DartFinancials(client, store, ledger, cooldowns, clock)
    private val corp = "C" + UUID.randomUUID().toString().take(7)

    /** (사업연도, 보고서) → 당기순이익. 계정 응답에 접수번호를 넣어 확정일 계산이 되게 한다. */
    private fun serveAccounts(netIncome: Map<Pair<Int, String>, Long>, fsDiv: String = "CFS") {
        http.on({ it.url.endsWith("fnlttSinglAcntAll.json") && it.query["corp_code"] == corp }) { call ->
            val k = call.query["bsns_year"]!!.toInt() to call.query["reprt_code"]!!
            val ni = netIncome[k]
            val body = if (ni == null || call.query["fs_div"] != fsDiv) {
                """{"status":"013","message":"no data"}"""
            } else {
                """{"status":"000","list":[
                    {"sj_div":"IS","account_id":"ifrs-full_ProfitLoss","account_nm":"당기순이익","thstrm_amount":"$ni","rcept_no":"${k.first + 1}0301000001"},
                    {"sj_div":"IS","account_id":"dart_OperatingIncomeLoss","account_nm":"영업이익","thstrm_amount":"${ni * 2}"},
                    {"sj_div":"BS","account_id":"ifrs-full_Equity","account_nm":"자본총계","thstrm_amount":"1000"}]}"""
            }
            HttpBytes(200, body.toByteArray())
        }
    }

    private fun serveCorpMap(vararg pairs: Pair<String, String>) {
        val xml = "<result>" + pairs.joinToString("") { (stock, c) -> "<list><corp_code>$c</corp_code><stock_code>$stock</stock_code></list>" } + "</result>"
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("CORPCODE.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        http.on({ it.url.endsWith("corpCode.xml") }) { HttpBytes(200, out.toByteArray()) }
    }

    private fun accountCalls() = http.calls.count { it.url.endsWith("fnlttSinglAcntAll.json") }

    @Test
    fun dartLocalOnlyThrowsWhenLedgerHasNoFinalEntry() {
        assertThrows<MarketDataNotLoaded> { fin.cachedAccounts(corp, 2020, DartMetrics.REPORT_ANNUAL, "CFS", remote = false) }
        assertEquals(0, accountCalls())
        ledger.put("dart_accounts", com.quantfolio.market.store.CacheKeys.of(corp, 2020, DartMetrics.REPORT_ANNUAL, "CFS"), 0, true)
        assertNull(fin.cachedAccounts(corp, 2020, DartMetrics.REPORT_ANNUAL, "CFS", remote = false))
    }

    @Test
    fun cachedAccountsRemoteThenStoreThenMemory() {
        serveAccounts(mapOf((2020 to DartMetrics.REPORT_ANNUAL) to 100L))
        val acc = fin.cachedAccounts(corp, 2020, DartMetrics.REPORT_ANNUAL, "CFS")!!
        assertEquals("100", acc.first()["thstrm_amount"])
        assertEquals(true, ledger.get("dart_accounts", "$corp|2020|11011|CFS")!!.final)
        // 새 인스턴스: 저장소 확정본을 읽고 원격을 타지 않는다.
        DartFinancials(client, store, ledger, cooldowns, clock).cachedAccounts(corp, 2020, DartMetrics.REPORT_ANNUAL, "CFS")
        assertEquals(1, accountCalls())
    }

    @Test
    fun noDataRecordedAndFinalAfterConfirmWindow() {
        serveAccounts(emptyMap())
        assertNull(fin.cachedAccounts(corp, 2019, DartMetrics.REPORT_ANNUAL, "CFS"))
        // 2019 무자료: 확정일(2020-12-31) 지남 → final 0행 → 다음엔 원격 없이 null
        assertEquals(LedgerEntryView(0, true), ledger.get("dart_accounts", "$corp|2019|11011|CFS")!!.let { LedgerEntryView(it.rowCount, it.final) })
        DartFinancials(client, store, ledger, cooldowns, clock).cachedAccounts(corp, 2019, DartMetrics.REPORT_ANNUAL, "CFS")
        assertEquals(1, accountCalls())
    }

    private data class LedgerEntryView(val rows: Int, val final: Boolean)

    @Test
    fun annualMetricsFallsBackToSeparate() {
        serveAccounts(mapOf((2021 to DartMetrics.REPORT_ANNUAL) to 50L), fsDiv = "OFS")
        assertEquals(50.0, fin.annualMetrics(corp, 2021)["net_income"])
    }

    @Test
    fun ttmTelescopesQuarter() {
        serveAccounts(
            mapOf(
                (2024 to DartMetrics.REPORT_HALF) to 30L,
                (2023 to DartMetrics.REPORT_HALF) to 20L,
                (2023 to DartMetrics.REPORT_ANNUAL) to 100L,
            ),
        )
        assertEquals(110.0, fin.ttmMetrics(corp, 2024, DartMetrics.REPORT_HALF)["net_income"])
    }

    @Test
    fun ttmFallsBackToAnnualWhenQuarterMissing() {
        serveAccounts(mapOf((2023 to DartMetrics.REPORT_ANNUAL) to 100L))
        assertEquals(100.0, fin.ttmMetrics(corp, 2024, DartMetrics.REPORT_Q1)["net_income"])
    }

    @Test
    fun metricsBySymbolAddsDerivedKeys() {
        serveCorpMap("900001" to corp)
        serveAccounts(
            mapOf(
                (2025 to DartMetrics.REPORT_ANNUAL) to 150L,
                (2024 to DartMetrics.REPORT_ANNUAL) to 100L,
                (2023 to DartMetrics.REPORT_ANNUAL) to -10L,
            ),
        )
        // 2026-10-19 → 공시지연 반영 연도 2025
        val m = fin.metricsBySymbol(listOf("900001", "123456"), LocalDate.of(2026, 10, 19))["900001"]!!
        assertEquals(150.0, m["net_income"])
        assertEquals(0.5, m["net_growth"])
        assertEquals(0.0, m["turnaround"])
        assertEquals(1, m["loss_years_3"])
    }

    @Test
    fun quotaRaisesWithRetryAfterAndStopsAggregate() {
        serveCorpMap("900002" to "${corp}a", "900003" to "${corp}b")
        http.onText({ it.url.endsWith("fnlttSinglAcntAll.json") }, body = """{"status":"020","message":"한도 초과"}""")
        val e = assertThrows<SourceQuotaError> { fin.metricsBySymbol(listOf("900002", "900003"), LocalDate.of(2026, 10, 19)) }
        assertTrue(e.retryAfterSeconds!! > 0)
        assertEquals(1, accountCalls()) // 첫 실패의 쿨다운으로 두 번째 종목은 시도하지 않는다
    }

    @Test
    fun peadSue() {
        serveCorpMap("900004" to corp)
        http.onText({ it.url.endsWith("list.json") }, body = """{"status":"000","list":[
            {"report_nm":"분기보고서 (2023.03)","rcept_dt":"20230515"},
            {"report_nm":"[기재정정]분기보고서 (2023.03)","rcept_dt":"20230601"},
            {"report_nm":"분기보고서 (2024.03)","rcept_dt":"20240514"}]}""")
        serveAccounts(
            mapOf(
                (2022 to DartMetrics.REPORT_Q1) to 10L,
                (2023 to DartMetrics.REPORT_Q1) to 20L,
                (2024 to DartMetrics.REPORT_Q1) to 50L,
            ),
        )
        val sue = fin.peadSueBySymbol(listOf("900004"), LocalDate.of(2024, 6, 1), lookbackQ = 8, minObs = 2)["900004"]!!
        // 서프라이즈 [10, 30], 표본표준편차 = sqrt(200)
        assertEquals(30.0 / sqrt(200.0), sue, 1e-12)
    }
}
