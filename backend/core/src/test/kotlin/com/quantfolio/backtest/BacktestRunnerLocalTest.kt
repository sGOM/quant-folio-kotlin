package com.quantfolio.backtest

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.domain.market.SectorMapSnapshotRepository
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.OpenDartClient
import com.quantfolio.market.krx.KrxIndexService
import com.quantfolio.market.official.LocalSectorMap
import com.quantfolio.market.krx.KrxMdc
import com.quantfolio.market.krx.KrxSession
import com.quantfolio.market.official.DividendFeed
import com.quantfolio.market.official.LocalSnapshots
import com.quantfolio.market.official.LocalTradingDays
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.McapUniverse
import com.quantfolio.market.official.OfficialIngest
import com.quantfolio.market.official.Valuation
import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexConstituentStore
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.market.store.StoreCache
import com.quantfolio.metrics.PanicService
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.FakeKrxTransport
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.math.sin
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * 백테스트는 로컬 데이터만 읽는다 — KRX·Naver·DART 원격 fake 가 한 번이라도 불리면 실패한다.
 * 합성 데이터(2330년대, 다른 테스트와 겹치지 않는 구간)를 Testcontainers DB 에 적재해 돌린다.
 */
@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class BacktestRunnerLocalTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
    @Autowired private val ticks: PriceTickStore,
    @Autowired jdbc: NamedParameterJdbcTemplate,
    @Autowired private val dividends: DividendStore,
    @Autowired private val constituents: IndexConstituentStore,
    @Autowired private val sectors: SectorMapSnapshotRepository,
    @Autowired private val panic: PanicService,
    @Autowired private val dividendFeed: DividendFeed,
) {
    private val clock = Clock.fixed(Instant.parse("2330-10-19T01:00:00Z"), ZoneOffset.UTC)
    // 재무 확정 판정이 이 시계(2330년)를 쓰도록 직접 만든다(운영 시계면 2328년 보고서가 미확정이다).
    private val dartStore = DartFinancialStore(jdbc, clock)
    private val ledger = InMemoryFetchLedger()
    private val http = FakeExternalHttp() // DART·Naver 원격 — 호출되면 실패
    private val krxTransport = FakeKrxTransport() // KRX MDC — 호출되면 실패
    private val cooldowns = Cooldowns(clock)
    private val days = LocalTradingDays(ledger)
    private val dart = DartFinancials(
        OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K")), cooldowns, clock),
        dartStore, ledger, cooldowns, clock,
    )
    private val krx: KrxIndexService = run {
        val props = QuantfolioProperties(krx = QuantfolioProperties.Krx("id", "pw"))
        val session = KrxSession(krxTransport, props, cooldowns, clock)
        KrxIndexService(KrxMdc(krxTransport, session, cooldowns) {}, session, cooldowns, constituents, ledger, StoreCache(ledger, clock), LocalSectorMap(sectors), clock)
    }
    private val snaps = LocalSnapshots(daily, indexes, days, Valuation(daily, dart, dartStore, dividends, ledger, ticks, days))
    private val runner = BacktestRunner(
        LocalOhlcvCoverage(ticks, daily, days), ticks, snaps, dart, LocalSectorMap(sectors), McapUniverse(daily, days), daily, panic, dividendFeed, clock,
    )

    private val symbols = listOf("L10001", "L10002", "L10003", "L10004", "L10005")
    private val start = LocalDate.of(2330, 2, 3)
    private val end = LocalDate.of(2330, 6, 30)
    private val loadFrom = start.minusDays(500)
    private val tradingDays = generateSequence(loadFrom) { it.plusDays(1) }.takeWhile { !it.isAfter(end) }.filter { it.dayOfWeek.value <= 5 }.toList()

    private fun price(s: Int, i: Int) = 10_000.0 + 40.0 * (s + 1) * i / 10 + 300.0 * sin(i * 0.1 * (s + 1))
    private fun bar(day: LocalDate, close: Double) = Bar(day.atStartOfDay().toInstant(ZoneOffset.UTC), close, close, close, close, 1_000)

    @BeforeEach
    fun load() {
        indexes.delete("1001")
        indexes.delete("1028")
        tradingDays.forEach { daily.delete(it) }
        indexes.write("1001", tradingDays.map { IndexBar(it, close = 100.0) }, null)
        ledger.trading((1L..7L).map { loadFrom.minusDays(it) } + tradingDays) // 첫 적재일 앞 며칠(워밍업 직전 거래일 조회)
        indexes.write("1028", tradingDays.mapIndexed { i, d -> IndexBar(d, close = 100.0 + i) }, null)
        symbols.forEachIndexed { s, sym -> ticks.upsert(sym, tradingDays.mapIndexed { i, d -> bar(d, price(s, i)) }) }
        tradingDays.forEachIndexed { i, d ->
            daily.write(
                d,
                symbols.mapIndexed { s, sym ->
                    StockDaily(sym, market = "KOSPI", close = price(s, i), marketCap = 1_000_000_000L * (s + 1), stockKind = OfficialIngest.COMMON_STOCK)
                },
            )
        }
        dartStore.writeCorpCodes(symbols.associateWith { "C$it" })
        for (y in 2327..2329) symbols.forEach { dartStore.delete("C$it", y, "11011", "CFS") }
        http.calls.clear()
        krxTransport.calls.clear()
    }

    private fun config(weights: Map<String, Any?>, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to "rebalance",
        "selection" to mapOf(
            "method" to "score", "top_n" to 2, "universe_rule" to mapOf("source" to "KOSPI_MCAP", "pick" to 4), "factor_weights" to weights,
        ),
        "cadence" to "monthly", "weighting" to "equal", "fees" to 0.0, "tax" to 0.0, "slippage_bps" to 0.0, "capital" to 10_000_000.0,
    ) + extra

    private fun assertNoRemoteCalls() {
        assertEquals(0, http.calls.size, "DART·Naver 원격이 불렸다: ${http.calls}")
        assertEquals(0, krxTransport.calls.size, "KRX 원격이 불렸다: ${krxTransport.calls}")
    }

    @Test
    fun `rebalance backtest with KOSPI_MCAP runs on local data only`() {
        val result = runner.run(config(mapOf("momentum" to 1.0, "value" to 0.0, "lowvol" to 0.0)), start, end)
        assertNotNull(result["total_return"])
        assertNoRemoteCalls()
    }

    /** 시총 순위를 모멘텀 순위와 반대로 둔다(L10001 이 시총 1위, L10005 가 모멘텀 1위) — 풀 크기가 매수 종목을 바꾸게. */
    private fun capsInverse() = tradingDays.forEachIndexed { i, d ->
        daily.write(d, symbols.mapIndexed { s, sym -> StockDaily(sym, market = "KOSPI", close = price(s, i), marketCap = 1_000_000_000L * (5 - s), stockKind = OfficialIngest.COMMON_STOCK) })
    }

    private fun boughtSymbols(rule: Map<String, Any?>): Set<Any?> {
        val cfg = config(mapOf("momentum" to 1.0, "value" to 0.0, "lowvol" to 0.0)).let { it + ("selection" to (it["selection"] as Map<*, *> + ("universe_rule" to rule))) }
        @Suppress("UNCHECKED_CAST")
        return (runner.run(cfg, start, end)["trades"] as List<Map<String, Any?>>).filter { it["side"] == "buy" }.map { it["symbol"] }.toSet()
    }

    @Test
    fun `pool size 4 with pick 2 narrows the mcap pool by momentum`() {
        capsInverse()
        // 시총 상위 4(L10001~L10004) 중 모멘텀 상위만 산다: 모멘텀 1위 L10004 는 있고, 시총 5위(풀 밖)인 L10005 는 없다.
        // pool_size 를 무시하면 풀이 시총 상위 pick(2)=L10001·L10002 뿐이라 L10004 가 없다.
        val bought = boughtSymbols(mapOf("source" to "KOSPI_MCAP", "pool_size" to 4, "pick" to 2))
        assertTrue("L10004" in bought && "L10005" !in bought, bought.toString())
    }

    @Test
    fun `pool size falls back to pick`() {
        capsInverse()
        // pool_size 없음 -> 풀 = 시총 상위 pick(3) = L10001~L10003. 그 밖(L10004·L10005)은 사지 않는다.
        val bought = boughtSymbols(mapOf("source" to "KOSPI_MCAP", "pick" to 3))
        assertTrue(bought.isNotEmpty() && bought.all { it in setOf("L10001", "L10002", "L10003") }, bought.toString())
    }

    @Test
    fun `coverage below half at any rebalance throws`() {
        val e = assertThrows<MarketDataNotLoaded> {
            runner.run(config(mapOf("momentum" to 0.5, "quality" to 0.5, "value" to 0.0, "lowvol" to 0.0)), start, end)
        }
        assertEquals("factor_coverage", e.kind)
        assertTrue("quality" in e.key, e.key)
        assertNoRemoteCalls()
    }

    @Test
    fun `pead weight rejected in phase1`() {
        val e = assertThrows<BacktestInputError> { runner.run(config(mapOf("momentum" to 0.5, "pead" to 0.5)), start, end) }
        assertTrue("pead" in e.message!!, e.message)
        assertNoRemoteCalls()
    }

    @Test
    fun `missing benchmark index propagates as not loaded not as backtest failure`() {
        indexes.delete("1028")
        val e = assertThrows<MarketDataNotLoaded> { runner.run(config(mapOf("momentum" to 1.0, "value" to 0.0, "lowvol" to 0.0)), start, end) }
        assertEquals("index_ohlcv", e.kind)
        assertNoRemoteCalls()
    }

    private fun row(sj: String, id: String, amount: Int) = mapOf<String, Any?>("sj_div" to sj, "account_id" to id, "thstrm_amount" to amount.toString())

    /** 사업보고서(연결)를 [symbols] 전부에 적재. 접수번호가 없어 확정일 = 사업연도 다음 해 말(테스트 시계 이전). */
    private fun loadAnnual(year: Int, accounts: List<Map<String, Any?>>) = symbols.forEach { dartStore.write("C$it", year, "11011", "CFS", accounts) }

    private val qualityAccounts = listOf(
        row("BS", "ifrs-full_Equity", 1_000), row("BS", "ifrs-full_Liabilities", 500), row("IS", "ifrs-full_ProfitLoss", 100),
    )
    private val qualityWeights = mapOf("momentum" to 0.5, "quality" to 0.5, "value" to 0.0, "lowvol" to 0.0)

    @Test
    fun `coverage is checked at every rebalance not only the first`() {
        // 4월 이전 결정일은 2328 사업보고서(적재), 4월부터는 2329(미적재) — 첫 리밸런싱은 통과하고 나중에 50% 아래로 떨어진다.
        loadAnnual(2328, qualityAccounts)
        val e = assertThrows<MarketDataNotLoaded> { runner.run(config(qualityWeights), start, end) }
        assertEquals("factor_coverage", e.kind)
        assertTrue(Regex("quality 2330-0[4-6]-").containsMatchIn(e.key), e.key)
    }

    @Test
    fun `quality coverage counts only inputs the quality score uses`() {
        // 점수에 안 쓰는 op_growth 만 차 있고 roe·debt_ratio·fcf·f_score 는 비어 있다.
        val opIncome = listOf(row("IS", "dart_OperatingIncomeLoss", 100))
        for (y in 2327..2328) loadAnnual(y, opIncome) // 2~3월 결정일(2328 사업연도)에서 op_growth 는 계산된다. 2329 는 시계상 미확정이라 4월 이후는 제외.
        val e = assertThrows<MarketDataNotLoaded> { runner.run(config(qualityWeights), start, LocalDate.of(2330, 3, 31)) }
        assertEquals("factor_coverage", e.kind)
        assertTrue("quality" in e.key, e.key)
    }

    @Test
    fun `empty universe day skips the coverage check`() {
        val rule = mapOf("source" to "KOSPI_MCAP", "pick" to 4, "lookback" to 100_000) // 이력 부족으로 전 종목 제외 -> 후보 0
        val cfg = config(qualityWeights).let { it + ("selection" to (it["selection"] as Map<*, *> + ("universe_rule" to rule))) }
        // 후보가 없는 날에도 provider 는 불린다(quality 가중치) — 그때 0/0 을 미적재로 보면 여기서 factor_coverage 로 던진다.
        // 정상 종료하면 빈 후보 경로를 지났다는 뜻이다: 거래·리밸런싱 0건, 수익률 0.
        val r = runner.run(cfg, start, end)
        assertEquals(0, r["num_rebalances"])
        assertEquals(0, r["num_trades"])
        assertEquals(0.0, r["total_return"])
    }

    @Test
    fun `string factor weights are rejected not silently skipped`() {
        val e = assertThrows<BacktestInputError> { runner.run(config(mapOf("momentum" to 0.5, "pead" to "0.5")), start, end) }
        assertTrue("pead" in e.message!!, e.message)
        assertThrows<BacktestInputError> { runner.run(config(mapOf("momentum" to 0.5, "quality" to "0.5")), start, end) }
        assertNoRemoteCalls()
    }
}
