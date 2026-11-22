package com.quantfolio.market.official

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.OpenDartClient
import com.quantfolio.market.krxopen.KrxOpenApi
import com.quantfolio.market.krxopen.OpenBaseRow
import com.quantfolio.market.krxopen.OpenDailyRow
import com.quantfolio.market.krxopen.OpenIndexRow
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.LedgerEntry
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class OfficialIngestTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
    @Autowired private val ticks: PriceTickStore,
    @Autowired private val dartStore: DartFinancialStore,
    @Autowired private val jdbc: NamedParameterJdbcTemplate,
) {
    private val clock = Clock.fixed(Instant.parse("2320-05-20T01:00:00Z"), ZoneOffset.UTC) // KST 10:00
    private val today = LocalDate.of(2320, 5, 20)
    private val ledger = InMemoryFetchLedger()
    private val props = QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K"))
    private val cooldowns = Cooldowns(clock)
    private val dartClient = OpenDartClient(FakeExternalHttp(), props, cooldowns, clock)

    /** 날짜별 (종가, 전일대비). prices 에 있는 날만 거래일. 000001=KOSPI, 000002=KOSDAQ. */
    class FakeOpen(cooldowns: Cooldowns) : KrxOpenApi(FakeExternalHttp(), QuantfolioProperties(), cooldowns) {
        val prices = sortedMapOf<LocalDate, Pair<Double, Double>>()
        val failDays = mutableMapOf<LocalDate, Exception>()
        var indexNames: Map<String, List<String>> = mapOf("KRX" to listOf("KRX 300"), "KOSPI" to listOf("코스피", "코스피 200"), "KOSDAQ" to listOf("코스닥"))
        var dailyCalls = 0
        var indexCalls = 0
        var baseCalls = 0
        val calls get() = dailyCalls + indexCalls + baseCalls

        override fun stockDaily(day: LocalDate, market: String): List<OpenDailyRow> {
            dailyCalls++
            failDays[day]?.let { throw it }
            val (c, d) = prices[day] ?: return emptyList()
            val sym = if (market == "KOSPI") "000001" else "000002"
            return listOf(OpenDailyRow(day, sym, market, c, c, c, c, d, 10, 10, 1000, 100))
        }

        override fun stockBase(day: LocalDate, market: String): List<OpenBaseRow> {
            baseCalls++
            if (prices[day] == null) return emptyList()
            return listOf(OpenBaseRow(if (market == "KOSPI") "000001" else "000002", "n", market, "보통주"))
        }

        override fun indexDaily(day: LocalDate, family: String): List<OpenIndexRow> {
            indexCalls++
            failDays[day]?.let { throw it }
            if (prices[day] == null) return emptyList()
            return indexNames.getValue(family).map { OpenIndexRow(day, it, 1.0, 2.0, 0.5, 1.5) }
        }
    }

    private val open = FakeOpen(cooldowns)

    private fun ingest(dart: DartFinancials = FakeDart()) = OfficialIngest(open, daily, indexes, ticks, ledger, dart, clock)

    /** DART 는 호출되면 안 되는 테스트용 기본값(서비스 호출은 dartBackfill 에서만). */
    private fun FakeDart(corpMap: Map<String, String>? = emptyMap(), failAt: Int = 0) = object : DartFinancials(dartClient, dartStore, ledger, cooldowns, clock) {
        var n = 0
        val called = mutableListOf<String>()
        override fun corpCodeMap() = corpMap
        override fun cachedAccounts(corp: String, year: Int, reprt: String, fsDiv: String, remote: Boolean): List<Map<String, Any?>>? {
            if (!remote) return listOf(mapOf("x" to 1))
            n++
            if (failAt in 1..n) throw SourceQuotaError("opendart", "020 한도")
            called += CacheKeys.of(corp, year, reprt, fsDiv)
            ledger.put("dart_accounts", CacheKeys.of(corp, year, reprt, fsDiv), 1, true)
            return listOf(mapOf("x" to 1))
        }
    }

    private val weekdays = generateSequence(LocalDate.of(2320, 4, 1)) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(10).toList()

    private fun key(svc: String, day: LocalDate, mkt: String) = "$svc|${day.toString().replace("-", "")}|$mkt"

    @BeforeEach
    fun clean() {
        val range = mapOf("a" to LocalDate.of(2320, 1, 1), "b" to LocalDate.of(2320, 12, 31))
        jdbc.update("DELETE FROM stock_daily_snapshots WHERE trade_date BETWEEN :a AND :b", range)
        jdbc.update("DELETE FROM index_ohlcv WHERE trade_date BETWEEN :a AND :b", range)
        jdbc.update("DELETE FROM price_ticks WHERE time >= :t", mapOf("t" to java.sql.Timestamp.from(Instant.parse("2320-01-01T00:00:00Z"))))
    }

    private fun trade(vararg days: LocalDate, closes: List<Pair<Double, Double>>? = null) {
        days.forEachIndexed { i, d -> open.prices[d] = closes?.get(i) ?: ((1000.0 + i) to 1.0) }
    }

    @Test
    fun `backfill resumes from ledger`() {
        trade(weekdays[0], weekdays[1], weekdays[2])
        val first = ingest().backfill(weekdays[0], weekdays[2], 1000)
        assertEquals(3, first.daysDone)
        assertEquals(2, open.baseCalls) // 월 1회 — 첫 거래일에만
        assertEquals(listOf("보통주"), daily.read(weekdays[0], listOf("KOSPI")).map { it.stockKind })
        assertNull(daily.read(weekdays[1], listOf("KOSPI")).single().stockKind)
        assertEquals(3, indexes.read("1001", weekdays[0], weekdays[2]).size)
        assertEquals(3, indexes.read("1028", weekdays[0], weekdays[2]).size)
        assertEquals(3, indexes.read("2001", weekdays[0], weekdays[2]).size)
        assertEquals(15 + 2, open.calls)

        val before = open.calls
        val second = ingest().backfill(weekdays[0], weekdays[2], 1000)
        assertEquals(0, second.daysDone)
        assertEquals(before, open.calls)
    }

    @Test
    fun `budget stops and reports`() {
        trade(weekdays[0], weekdays[1], weekdays[2])
        val r = ingest().backfill(weekdays[0], weekdays[2], 11) // 첫날 7(기본정보 포함) + 둘째날 5 = 12 > 11
        assertEquals(1, r.daysDone)
        assertTrue(r.stoppedByBudget)
        assertNull(ledger.get("krx_open", key("stk_bydd_trd", weekdays[1], "KOSPI")))
        val resumed = ingest().backfill(weekdays[0], weekdays[2], 1000)
        assertEquals(2, resumed.daysDone)
        assertFalse(resumed.stoppedByBudget)
    }

    @Test
    fun `holiday older than 7 days is finalized, recent zero rows are not`() {
        val oldDay = generateSequence(today.minusDays(10)) { it.minusDays(1) }.first { it.dayOfWeek.value <= 5 }
        val recent = generateSequence(today.minusDays(3)) { it.minusDays(1) }.first { it.dayOfWeek.value <= 5 }
        ingest().backfill(oldDay, oldDay, 100)
        ingest().backfill(recent, recent, 100)
        assertEquals(LedgerEntry(0, true), ledger.get("krx_open", key("stk_bydd_trd", oldDay, "KOSPI")))
        assertEquals(LedgerEntry(0, false), ledger.get("krx_open", key("stk_bydd_trd", recent, "KOSPI")))
        assertEquals(LedgerEntry(0, true), ledger.get("krx_open", key("kospi_dd_trd", oldDay, "KOSPI")))
        assertTrue(LocalTradingDays(indexes, ledger).isHoliday(oldDay))
        assertFalse(LocalTradingDays(indexes, ledger).isHoliday(recent))
    }

    @Test
    fun `api failure on a day records nothing and continues next day, report lists it`() {
        trade(weekdays[0], weekdays[1], weekdays[2])
        open.failDays[weekdays[1]] = SourceUnavailableError("krx_open", "503")
        val r = ingest().backfill(weekdays[0], weekdays[2], 1000)
        assertEquals(2, r.daysDone)
        assertEquals(1, r.failed.size)
        assertTrue(r.failed.single().contains(weekdays[1].toString()))
        assertNull(ledger.get("krx_open", key("stk_bydd_trd", weekdays[1], "KOSPI")))
        assertTrue(daily.read(weekdays[1]).isEmpty())
        assertNotNull(ledger.get("krx_open", key("stk_bydd_trd", weekdays[2], "KOSPI")))
    }

    @Test
    fun `index family with rows but none of the expected names is a schema failure, not a holiday`() {
        trade(weekdays[0])
        open.indexNames = open.indexNames + ("KOSPI" to listOf("이름이 바뀐 지수"))
        val r = ingest().backfill(weekdays[0], weekdays[0], 1000)
        assertEquals(0, r.daysDone)
        assertEquals(1, r.failed.size)
        assertNull(ledger.get("krx_open", key("stk_bydd_trd", weekdays[0], "KOSPI")))
    }

    @Test
    fun `normal day appends one adjusted bar`() {
        trade(weekdays[0], weekdays[1], closes = listOf(1000.0 to 0.0, 1100.0 to 100.0))
        ingest().backfill(weekdays[0], weekdays[0], 1000)
        assertEquals(1, ticks.bars("000001", Instant.parse("2320-01-01T00:00:00Z"), Instant.parse("2320-12-31T00:00:00Z")).size)
        ingest().backfill(weekdays[1], weekdays[1], 1000)
        val bars = ticks.bars("000001", Instant.parse("2320-01-01T00:00:00Z"), Instant.parse("2320-12-31T00:00:00Z"))
        assertEquals(listOf(1000.0, 1100.0), bars.map { it.close })
    }

    @Test
    fun `corporate action day triggers full rebuild of that symbol`() {
        // 1000, 1000, 그리고 1:2 분할로 500(기준가 500 → 전일대비 0)
        trade(weekdays[0], weekdays[1], weekdays[2], closes = listOf(1000.0 to 0.0, 1000.0 to 0.0, 500.0 to 0.0))
        ingest().backfill(weekdays[0], weekdays[2], 1000)
        val bars = ticks.bars("000001", Instant.parse("2320-01-01T00:00:00Z"), Instant.parse("2320-12-31T00:00:00Z"))
        assertEquals(listOf(500.0, 500.0, 500.0), bars.map { it.close })
    }

    @Test
    fun `dart backfill stops on quota error and resumes next run`() {
        val symbols = listOf("000011", "000012", "000013")
        daily.write(LocalDate.of(2320, 4, 1), symbols.map { StockDaily(it, "KOSPI", close = 1.0, stockKind = "보통주") } +
            StockDaily("000014", "KOSPI", close = 1.0, stockKind = "우선주") + StockDaily("000015", "KOSDAQ", close = 1.0, stockKind = "보통주"))
        val corp = (symbols + "000014" + "000015").associateWith { "C$it" }.filterKeys { it in symbols }
        val failing = FakeDart(corp, failAt = 5)
        val r1 = ingest(failing).dartBackfill(2319, 1000)
        assertTrue(r1.stoppedByBudget)
        assertEquals(4, failing.called.size)
        val ok = FakeDart(corp)
        val r2 = ingest(ok).dartBackfill(2319, 1000)
        assertFalse(r2.stoppedByBudget)
        // 2319: 4종, 2320: 11011 제외 3종 → 종목당 7, 3종목 21. 첫 실행 4건은 원장에 있어 다시 부르지 않는다.
        assertEquals(21 - 4, ok.called.size)
        assertEquals(0, ok.called.intersect(failing.called.toSet()).size)
    }
}
