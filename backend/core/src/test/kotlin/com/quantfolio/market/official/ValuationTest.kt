package com.quantfolio.market.official

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.OpenDartClient
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.DividendEvent
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
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
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class ValuationTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val dartStore: DartFinancialStore,
    @Autowired private val dividends: DividendStore,
    @Autowired private val ticks: PriceTickStore,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val cooldowns = Cooldowns(clock)
    private val client = OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K")), cooldowns, clock)
    private val ledger = InMemoryFetchLedger()
    private val dart = DartFinancials(client, dartStore, ledger, cooldowns, clock)
    private val valuation = Valuation(daily, dart, dartStore, dividends, ledger, ticks) { d -> d.dayOfWeek.value <= 5 }

    private val asOf = LocalDate.of(2021, 6, 15)
    private val id = UUID.randomUUID().toString().filter { it.isDigit() }.padEnd(6, '7')

    private var seq = 0
    private val corps = mutableMapOf<String, String>()

    /** 이 테스트 전용 종목코드(6자리 숫자)와 corp. */
    private fun newSymbol(): String = (id.take(5) + (seq++)).also { corps[it] = "C${it}0" }

    private fun serveCorpMap() = dartStore.writeCorpCodes(corps)

    private fun snapshot(symbol: String, day: LocalDate = asOf, cap: Long = 100_000L, close: Double = 1000.0) =
        daily.write(day, listOf(StockDaily(symbol, market = "KOSPI", marketCap = cap, close = close)))

    /** 지배주주 순이익·자본(원)과 접수일. 비지배 포함 총계는 일부러 다르게 둔다. */
    private fun report(symbol: String, year: Int, rcept: LocalDate, netIncome: Long, equity: Long, fs: String = "CFS") {
        val no = rcept.toString().replace("-", "") + "000001"
        dartStore.write(
            corps.getValue(symbol), year, "11011", fs,
            listOf(
                mapOf("sj_div" to "IS", "account_id" to "ifrs-full_ProfitLoss", "account_nm" to "당기순이익", "thstrm_amount" to "${netIncome * 2}", "rcept_no" to no),
                mapOf("sj_div" to "IS", "account_id" to "ifrs-full_ProfitLossAttributableToOwnersOfParent", "account_nm" to "지배기업의 소유주에게 귀속되는 당기순이익", "thstrm_amount" to "$netIncome"),
                mapOf("sj_div" to "BS", "account_id" to "ifrs-full_Equity", "account_nm" to "자본총계", "thstrm_amount" to "${equity * 2}"),
                mapOf("sj_div" to "BS", "account_id" to "ifrs-full_EquityAttributableToOwnersOfParent", "account_nm" to "지배기업 소유주지분", "thstrm_amount" to "$equity"),
            ),
        )
    }

    private fun one(symbol: String): StockDaily = valuation.compute(asOf, listOf(symbol)).single()

    @Test
    fun `per and pbr are market cap over controlling net income and equity`() {
        val s = newSymbol(); serveCorpMap()
        snapshot(s, cap = 100_000L)
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        val v = one(s)
        assertEquals(10.0, v.per)
        assertEquals(2.0, v.pbr)
    }

    @Test
    fun `loss gives null per`() {
        val s = newSymbol(); serveCorpMap()
        snapshot(s)
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = -5_000L, equity = 50_000L)
        val v = one(s)
        assertNull(v.per)
        assertEquals(2.0, v.pbr)
    }

    @Test
    fun `non positive equity gives null pbr`() {
        val s = newSymbol(); serveCorpMap()
        snapshot(s)
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = -1L)
        val v = one(s)
        assertEquals(10.0, v.per)
        assertNull(v.pbr)
    }

    @Test
    fun `report received after asOf is ignored`() {
        val s = newSymbol(); serveCorpMap()
        snapshot(s)
        report(s, 2020, LocalDate.of(2021, 7, 1), netIncome = 1L, equity = 1L) // 접수일 > asOf
        report(s, 2019, LocalDate.of(2020, 3, 10), netIncome = 20_000L, equity = 25_000L)
        val v = one(s)
        assertEquals(5.0, v.per)
        assertEquals(4.0, v.pbr)
    }

    @Test
    fun `separate statement is the fallback when consolidated is absent`() {
        val s = newSymbol(); serveCorpMap()
        snapshot(s)
        // 별도: 비지배가 없어 총계가 곧 지배주주 몫. report() 의 총계는 2배이므로 별도 값은 직접 넣는다.
        dartStore.write(
            corps.getValue(s), 2020, "11011", "OFS",
            listOf(
                mapOf("sj_div" to "IS", "account_id" to "ifrs-full_ProfitLoss", "account_nm" to "당기순이익", "thstrm_amount" to "25000", "rcept_no" to "20210310000001"),
                mapOf("sj_div" to "BS", "account_id" to "ifrs-full_Equity", "account_nm" to "자본총계", "thstrm_amount" to "50000"),
            ),
        )
        ledger.put("dart_accounts", CacheKeys.of(corps.getValue(s), 2020, "11011", "CFS"), 0, true) // 연결은 확정 무자료
        val v = one(s)
        assertEquals(4.0, v.per)
        assertEquals(2.0, v.pbr)
    }

    @Test
    fun `dividend yield is null when unknown and zero only when recorded as zero`() {
        val unknown = newSymbol(); val zero = newSymbol(); val paid = newSymbol(); serveCorpMap()
        for (s in listOf(unknown, zero, paid)) {
            snapshot(s, close = 1000.0)
            report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        }
        // unknown: 공시일 불명 + 기준일 + 90일 > asOf → 아직 알려지지 않은 것으로 본다.
        dividends.write(listOf(DividendEvent(unknown, asOf.minusDays(10), 50.0, null, "t")))
        // zero: 공시된 0원 배당.
        dividends.write(listOf(DividendEvent(zero, asOf.minusDays(100), 0.0, asOf.minusDays(120), "t")))
        // paid: 두 건 합계 30 + 20 = 50원 / 종가 1000 = 5%. 366일 전 기준일은 창 밖.
        dividends.write(
            listOf(
                DividendEvent(paid, asOf.minusDays(30), 30.0, asOf.minusDays(60), "t"),
                DividendEvent(paid, asOf.minusDays(200), 20.0, null, "t"), // 공시일 불명이지만 기준일+90 <= asOf
                DividendEvent(paid, asOf.minusDays(366), 999.0, asOf.minusDays(380), "t"),
                DividendEvent(paid, asOf.minusDays(5), 777.0, asOf.plusDays(1), "t"), // 공시가 asOf 이후
            ),
        )
        for (r in listOf(30L, 200L)) flatPrices(paid, asOf.minusDays(r), asOf)
        val out = valuation.compute(asOf, listOf(unknown, zero, paid)).associateBy { it.symbol }
        assertNull(out.getValue(unknown).div)
        assertEquals(0.0, out.getValue(zero).div)
        assertEquals(5.0, out.getValue(paid).div)
    }

    @Test
    fun `uses latest snapshot on or before asOf and nulls symbols without one`() {
        val s = newSymbol(); val none = newSymbol(); serveCorpMap()
        snapshot(s, day = asOf.minusDays(3), cap = 100_000L)
        snapshot(s, day = asOf.plusDays(1), cap = 1L) // 미래 행은 쓰지 않는다
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        report(none, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        val out = valuation.compute(asOf, listOf(s, none)).associateBy { it.symbol }
        assertEquals(10.0, out.getValue(s).per)
        assertNull(out.getValue(none).per)
        assertNull(out.getValue(none).pbr)
        assertNull(out.getValue(none).div)
    }

    @Test
    fun `symbol without loaded dart is null but all unloaded propagates`() {
        val ok = newSymbol(); val missing = newSymbol(); serveCorpMap()
        snapshot(ok); snapshot(missing)
        report(ok, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        val out = valuation.compute(asOf, listOf(ok, missing)).associateBy { it.symbol }
        assertEquals(10.0, out.getValue(ok).per)
        assertNull(out.getValue(missing).per)
        assertNull(out.getValue(missing).pbr)
        assertThrows<MarketDataNotLoaded> { valuation.compute(asOf, listOf(missing)) }
    }

    @Test
    fun `symbol missing from corp map is null for that symbol only`() {
        val ok = newSymbol(); val unmapped = newSymbol()
        corps.remove(unmapped) // 매핑 테이블에 없는 종목
        serveCorpMap()
        snapshot(ok); snapshot(unmapped)
        report(ok, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        val out = valuation.compute(asOf, listOf(ok, unmapped)).associateBy { it.symbol }
        assertEquals(10.0, out.getValue(ok).per)
        assertNull(out.getValue(unmapped).per)
    }

    @Test
    fun `no dividend rows is zero only after the full dividend ingest ran`() {
        val s = newSymbol(); val other = newSymbol(); serveCorpMap()
        dividends.write(listOf(DividendEvent(other, LocalDate.of(2015, 6, 30), 10.0, null, "t")))
        snapshot(s, close = 1000.0)
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        assertNull(one(s).div) // 전체 적재 전: 모른다
        ledger.put("datagokr", "dividends_full", 100, true)
        assertEquals(0.0, one(s).div) // 적재 후 기록 없음 = 무배당
    }

    @Test
    fun `annual december dividend is still counted in march and not mistaken for no dividend`() {
        val s = newSymbol(); val other = newSymbol(); serveCorpMap()
        val day = LocalDate.of(2021, 3, 15)
        snapshot(s, day = day, close = 1000.0)
        report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        // 공시일 불명: 알려진 날 = 기준일 + 90일. 2020-12-31 건은 3-15 에 아직 모르고, 2019-12-31 건(알려진 날 2020-03-30)이 직전 1년 창 안이다.
        dividends.write(listOf(DividendEvent(s, LocalDate.of(2019, 12, 31), 100.0, null, "t"), DividendEvent(s, LocalDate.of(2020, 12, 31), 120.0, null, "t")))
        dividends.write(listOf(DividendEvent(other, LocalDate.of(2015, 6, 30), 10.0, null, "t"))) // 이력 시작을 충분히 앞으로
        ledger.put("datagokr", "dividends_full", 100, true)
        flatPrices(s, LocalDate.of(2019, 12, 31), day)
        assertEquals(10.0, valuation.compute(day, listOf(s)).single().div)
    }

    @Test
    fun `zero is asserted only once a year of dividend history exists before asOf`() {
        val s = newSymbol(); val other = newSymbol(); serveCorpMap()
        dividends.write(listOf(DividendEvent(other, LocalDate.of(2015, 6, 30), 10.0, null, "t")))
        ledger.put("datagokr", "dividends_full", 100, true)
        for ((day, expected) in listOf(LocalDate.of(2015, 12, 1) to null, LocalDate.of(2016, 7, 1) to 0.0)) {
            snapshot(s, day = day, close = 1000.0)
            report(s, day.year - 1, day.minusDays(120), netIncome = 10_000L, equity = 50_000L)
            assertEquals(expected, valuation.compute(day, listOf(s)).single().div, "asOf $day")
        }
    }

    /** 기준일 [record] 의 배당락일과 [asOf] 에 분할 없는 가격(1000)을 심는다. */
    private fun flatPrices(symbol: String, record: LocalDate, asOf: LocalDate) {
        val ex = DividendDates.exDateOn(record) { d -> d.dayOfWeek.value <= 5 }!!
        prices(symbol, ex, raw = 1000.0)
        prices(symbol, asOf, raw = 1000.0)
    }

    /** [day] 의 원시 종가(stock_daily)와 수정 종가(price_ticks) 를 함께 심는다. */
    private fun prices(symbol: String, day: LocalDate, raw: Double, adj: Double = raw) {
        daily.write(day, listOf(StockDaily(symbol, market = "KOSPI", marketCap = 100_000L, close = raw)))
        ticks.upsert(symbol, listOf(Bar(day.atStartOfDay().toInstant(ZoneOffset.UTC), adj, adj, adj, adj, 1L)))
    }

    @Test
    fun `dividend yield converts pre-split dps to asOf units, equal to the no-split case`() {
        val split = newSymbol(); val plain = newSymbol(); val other = newSymbol(); serveCorpMap()
        dividends.write(listOf(DividendEvent(other, LocalDate.of(2015, 6, 30), 10.0, null, "t")))
        ledger.put("datagokr", "dividends_full", 100, true)
        val record = asOf.minusDays(100) // 2021-03-07(일) -> 마지막 거래일 03-05(금) -> 배당락 03-04(목)
        val ex = LocalDate.of(2021, 3, 4)
        for (s in listOf(split, plain)) {
            report(s, 2020, LocalDate.of(2021, 3, 10), netIncome = 10_000L, equity = 50_000L)
        }
        dividends.write(listOf(DividendEvent(split, record, 5000.0, asOf.minusDays(90), "t")))
        // 50:1 분할이 배당락일과 asOf 사이에 있었다: 배당락일 원시 종가 1,000,000 → asOf 20,000. 수정 종가는 둘 다 20,000 이하 단위.
        // 수정/원시 비는 분할 때만 바뀌고, 이후(asOf 뒤) 다른 권리락이 와도 두 날짜가 같은 인자로 곱해져 비의 비는 변하지 않는다 — 미래참조 없음.
        prices(split, ex, raw = 1_000_000.0, adj = 20_000.0)
        prices(split, asOf, raw = 20_000.0, adj = 20_000.0)
        // 분할이 없었던 동등 종목: 가격 20,000 그대로, 주당 배당 100.
        dividends.write(listOf(DividendEvent(plain, record, 100.0, asOf.minusDays(90), "t")))
        prices(plain, ex, raw = 20_000.0); prices(plain, asOf, raw = 20_000.0)
        val out = valuation.compute(asOf, listOf(split, plain)).associateBy { it.symbol }
        assertEquals(0.5, out.getValue(plain).div!!, 1e-9)
        assertEquals(0.5, out.getValue(split).div!!, 1e-9)
    }
}
