package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.market.store.StockPeriodStore
import com.quantfolio.market.store.StoreCache
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeKrxTransport
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class KrxSnapshotsTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val periods: StockPeriodStore,
    @Autowired private val indexes: IndexOhlcvStore,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val ledger = InMemoryFetchLedger()
    private val api = KrxStockApi(KrxMdc(transport, KrxSession(transport, QuantfolioProperties(), cooldowns, clock), cooldowns) {})
    private val snapshots = KrxSnapshots(api, StoreCache(ledger, clock), daily, periods, indexes, cooldowns, MarketCalendar(api, clock))

    private fun bld(code: String) = "dbms/MDC/STAT/standard/$code"
    private fun calls(code: String) = transport.calls.count { it.form["bld"] == bld(code) }

    private fun fundamentals(mkt: String, vararg rows: Pair<String, String>) =
        transport.on({ it.form["bld"] == bld("MDCSTAT03501") && it.form["mktId"] == mkt }) {
            HttpResult(200, """{"output":[${rows.joinToString(",") { (code, per) ->
                """{"ISU_SRT_CD":"$code","BPS":"1","PER":"$per","PBR":"1.5","EPS":"1","DVD_YLD":"2.0","DPS":"1"}"""
            }}]}""")
        }

    @Test
    fun fundamentalsPerNonPositiveIsNullAndSecondCallReadsLocal() {
        fundamentals("STK", "100001" to "12.5", "100002" to "-")
        fundamentals("KSQ", "200001" to "-3.1")
        val day = LocalDate.of(2003, 5, 2)
        val first = snapshots.fundamentals(day, listOf("KOSPI", "KOSDAQ")).associateBy { it.symbol }
        assertEquals(12.5, first["100001"]!!.per)
        assertNull(first["100002"]!!.per) // "-" → 0 → 적자 취급
        assertNull(first["200001"]!!.per)
        assertEquals("KOSDAQ", first["200001"]!!.market)
        assertEquals(true, ledger.get("fundamentals", "20030502|KOSDAQ,KOSPI")!!.final)

        val again = KrxSnapshots(api, StoreCache(ledger, clock), daily, periods, indexes, cooldowns, MarketCalendar(api, clock))
            .fundamentals(day, listOf("KOSPI", "KOSDAQ"))
        assertEquals(3, again.size)
        assertEquals(2, calls("MDCSTAT03501"))
    }

    @Test
    fun partialFailureNotFinal() {
        fundamentals("STK", "300001" to "10")
        transport.on({ it.form["bld"] == bld("MDCSTAT03501") && it.form["mktId"] == "KSQ" }) { HttpResult(200, "<html>") }
        val rows = snapshots.fundamentals(LocalDate.of(2003, 5, 6), listOf("KOSPI", "KOSDAQ"))
        assertEquals(listOf("300001"), rows.map { it.symbol })
        assertEquals(false, ledger.get("fundamentals", "20030506|KOSDAQ,KOSPI")!!.final)
    }

    @Test
    fun allFailRaisesRepresentative() {
        transport.on({ it.form["bld"] == bld("MDCSTAT03501") }) { HttpResult(200, "<html>") }
        assertThrows<SourceUnavailableError> { snapshots.fundamentals(LocalDate.of(2003, 5, 7), listOf("KOSPI", "KOSDAQ")) }
        assertEquals(1, calls("MDCSTAT03501")) // 첫 실패의 쿨다운으로 나머지 시장 단락
    }

    @Test
    fun marketCapMapsColumns() {
        transport.onBld(
            bld("MDCSTAT01501"),
            """{"OutBlock_1":[{"ISU_SRT_CD":"400001","TDD_CLSPRC":"100","MKTCAP":"5,000","ACC_TRDVOL":"7","ACC_TRDVAL":"700","LIST_SHRS":"50"}]}""",
        )
        val row = snapshots.marketCap(LocalDate.of(2003, 6, 2), listOf("KOSPI")).single()
        assertEquals(listOf(5000L, 7L, 700L, 50L), listOf(row.marketCap, row.volume, row.tradingValue, row.shares))
        assertEquals("KOSPI", row.market)
    }

    @Test
    fun netPurchasesSumsInvestors() {
        transport.on({ it.form["bld"] == bld("MDCSTAT02401") }) {
            val v = if (it.form["invstTpCd"] == "9000") "100" else "-30"
            HttpResult(200, """{"output":[{"a":"500001","b":"x","c":"0","d":"0","e":"0","f":"0","g":"0","h":"$v"}]}""")
        }
        val start = LocalDate.of(2003, 7, 1)
        val end = LocalDate.of(2003, 7, 31)
        val row = snapshots.netPurchases(start, end, listOf("KOSPI"), listOf("외국인", "기관합계")).single()
        assertEquals(70.0, row.netBuyValue)
        assertEquals("KOSPI", row.market)
        assertEquals(70.0, periods.read(start, end, "기관합계,외국인").single().netBuyValue)
    }

    @Test
    fun sparseIndexBarsNotCovered() {
        transport.onBld(
            bld("MDCSTAT00301"),
            """{"output":[{"TRD_DD":"2004/01/05","OPNPRC_IDX":"1","HGPRC_IDX":"1","LWPRC_IDX":"1","CLSPRC_IDX":"700","ACC_TRDVOL":"1","ACC_TRDVAL":"1","MKTCAP":"1"}]}""",
        )
        val bars = snapshots.indexOhlcv(LocalDate.of(2004, 1, 1), LocalDate.of(2004, 3, 31), "1999")!!
        assertEquals(700.0, bars.single().close)
        assertTrue(indexes.readCoverage("1999").isEmpty()) // 예상 ~60거래일인데 1행 → 부분 응답 의심
    }

    @Test
    fun denseIndexBarsCovered() {
        val dates = generateSequence(LocalDate.of(2004, 2, 2)) { it.plusDays(1) }.take(20).toList()
        transport.onBld(
            bld("MDCSTAT00301"),
            """{"output":[${dates.joinToString(",") { d ->
                """{"TRD_DD":"${d.toString().replace("-", "/")}","OPNPRC_IDX":"1","HGPRC_IDX":"1","LWPRC_IDX":"1","CLSPRC_IDX":"1","ACC_TRDVOL":"1","ACC_TRDVAL":"1","MKTCAP":"1"}"""
            }}]}""",
        )
        snapshots.indexOhlcv(LocalDate.of(2004, 2, 1), LocalDate.of(2004, 2, 29), "1998")
        assertEquals(listOf(LocalDate.of(2004, 2, 1) to LocalDate.of(2004, 2, 29)), indexes.readCoverage("1998"))
    }

    @Test
    fun indexNameFallsBackToTicker() {
        transport.on({ it.form["bld"] == bld("MDCSTAT00401") }) { HttpResult(500, "") }
        assertEquals("1028", snapshots.indexName(LocalDate.of(2026, 10, 16), "1028"))
    }
}
