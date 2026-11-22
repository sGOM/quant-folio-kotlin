package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.domain.market.SectorMapSnapshot
import com.quantfolio.domain.market.SectorMapSnapshotRepository
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.market.store.IndexConstituentStore
import com.quantfolio.market.store.StoreCache
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeKrxTransport
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
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
class KrxIndexServiceTest(
    @Autowired private val constituents: IndexConstituentStore,
    @Autowired private val sectors: SectorMapSnapshotRepository,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val ledger = InMemoryFetchLedger()
    private lateinit var service: KrxIndexService

    private fun build(withCreds: Boolean = true): KrxIndexService {
        val props = if (withCreds) QuantfolioProperties(krx = QuantfolioProperties.Krx("id", "pw")) else QuantfolioProperties()
        val session = KrxSession(transport, props, cooldowns, clock)
        val mdc = KrxMdc(transport, session, cooldowns) {}
        return KrxIndexService(mdc, session, cooldowns, constituents, ledger, StoreCache(ledger, clock), sectors, clock)
    }

    @BeforeEach
    fun setUp() {
        transport.on({ it.url == KrxSession.LOGIN_URL }) { HttpResult(200, """{"_error_code":"CD001"}""") }
        service = build()
    }

    private fun onConstituents(respond: (String) -> String) =
        transport.on({ it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT00601" }) { HttpResult(200, respond(it.form["trdDd"]!!)) }

    private fun constituentCalls() = transport.calls.filter { it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT00601" }

    @Test
    fun indexMembersParsesAndZfills() {
        onConstituents { """{"output":[{"ISU_SRT_CD":"5930"},{"ISU_SRT_CD":"000660"},{"ISU_SRT_CD":""}]}""" }
        assertEquals(listOf("005930", "000660"), service.indexMembers(LocalDate.of(2001, 3, 2), "KOSPI200"))
        val form = constituentCalls().single().form
        assertEquals(listOf("1", "028", "ko_KR", "1", "false"), listOf(form["indIdx"], form["indIdx2"], form["locale"], form["money"], form["csvxls_isNo"]))
    }

    @Test
    fun indexMembersSnapsBackOverHoliday() {
        onConstituents { dd -> if (dd == "20010302") """{"output":[{"ISU_SRT_CD":"000001"}]}""" else """{"output":[]}""" }
        assertEquals(listOf("000001"), service.indexMembers(LocalDate.of(2001, 3, 4), "KOSPI200"))
        assertEquals(listOf("20010304", "20010303", "20010302"), constituentCalls().map { it.form["trdDd"] })
    }

    @Test
    fun indexMembersEmptyWhenUnauthenticated() {
        assertEquals(emptyList(), build(withCreds = false).indexMembers(LocalDate.of(2001, 3, 5), "KOSPI200"))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun indexMembersPropagatesSessionAuthError() {
        transport.on({ it.url == KrxSession.LOGIN_URL }) { HttpResult(200, """{"_error_code":"CD010"}""") }
        assertThrows<SourceAuthError> { build().indexMembers(LocalDate.of(2001, 3, 6), "KOSPI200") }
    }

    @Test
    fun indexMembersAllFailuresRaiseRepresentative() {
        transport.on({ it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT00601" }) { HttpResult(503, "") }
        assertThrows<SourceUnavailableError> { service.indexMembers(LocalDate.of(2001, 3, 7), "KOSPI200") }
        assertEquals(1, constituentCalls().size) // 실패 후 쿨다운이 걸려 더 거슬러 가지 않는다
    }

    @Test
    fun indexMembersCachedAndFinalizedInLedger() {
        onConstituents { """{"output":[{"ISU_SRT_CD":"000001"}]}""" }
        val day = LocalDate.of(2001, 3, 8)
        service.indexMembers(day, "KOSPI200")
        service.indexMembers(day, "KOSPI200")
        assertEquals(1, constituentCalls().size)
        assertEquals(true, ledger.get("index_members", "KOSPI200|20010308")!!.final)
        assertEquals(listOf("000001"), constituents.read("KOSPI200", day))
        // 새 인스턴스(메모리 캐시 없음)도 원장 확정이면 저장소에서 읽는다.
        build().indexMembers(day, "KOSPI200")
        assertEquals(1, constituentCalls().size)
    }

    @Test
    fun indexMembersEmptyNotFinalizedAndRetried() {
        onConstituents { """{"output":[]}""" }
        val day = LocalDate.of(2001, 3, 9)
        assertEquals(emptyList(), service.indexMembers(day, "KOSPI200"))
        assertNull(ledger.get("index_members", "KOSPI200|20010309"))
        service.indexMembers(day, "KOSPI200")
        assertEquals(14, constituentCalls().size)
    }

    @Test
    fun unknownIndexRaises() {
        assertThrows<IllegalArgumentException> { service.indexMembers(LocalDate.of(2001, 3, 9), "NOPE") }
    }

    @Test
    fun allListedStocksParsesMapsAndCaches() {
        transport.onBld(
            "dbms/comm/finder/finder_stkisu",
            """{"block1":[{"short_code":"5930","codeName":"삼성전자","marketCode":"STK"},{"short_code":"","codeName":"x","marketCode":"KSQ"}]}""",
        )
        assertEquals(listOf(ListedStock("005930", "삼성전자", "KOSPI")), service.allListedStocks())
        service.allListedStocks()
        assertEquals(1, transport.calls.count { it.form["bld"] == "dbms/comm/finder/finder_stkisu" })
    }

    @Test
    fun allListedStocksFailureNotCached() {
        transport.on({ it.form["bld"] == "dbms/comm/finder/finder_stkisu" }) { HttpResult(404, "") }
        assertThrows<com.quantfolio.market.source.SourceRequestError> { service.allListedStocks() }
    }

    @Test
    fun marketCapsMergesMarkets() {
        transport.on({ it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT01501" }) {
            val body = if (it.form["mktId"] == "STK") """[{"ISU_SRT_CD":"005930","MKTCAP":"1,000"}]""" else """[{"ISU_SRT_CD":"035720","MKTCAP":"-"}]"""
            HttpResult(200, """{"OutBlock_1":$body}""")
        }
        assertEquals(mapOf("005930" to 1000L), service.marketCaps(LocalDate.of(2001, 4, 2)))
    }

    @Test
    fun sectorMapUsesPitSnapshot() {
        sectors.saveAll(
            listOf(
                SectorMapSnapshot("900001", "옛분류", LocalDate.of(1999, 1, 1)),
                SectorMapSnapshot("900001", "새분류", LocalDate.of(1999, 7, 1)),
            ),
        )
        assertEquals("옛분류", service.sectorMap(LocalDate.of(1999, 6, 30))["900001"])
        assertEquals("새분류", service.sectorMap(LocalDate.of(1999, 7, 1))["900001"])
        assertTrue(transport.calls.none { it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT03901" })
    }

    @Test
    fun sectorMapWithoutSnapshotIsNotLoaded() {
        val e = assertThrows<MarketDataNotLoaded> { service.sectorMap(LocalDate.of(1990, 1, 1)) }
        assertEquals("sector_map", e.kind)
        assertTrue(transport.calls.none { it.form["bld"] == "dbms/MDC/STAT/standard/MDCSTAT03901" })
    }
}
