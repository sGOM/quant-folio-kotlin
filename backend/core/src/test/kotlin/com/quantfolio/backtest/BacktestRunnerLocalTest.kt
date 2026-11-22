package com.quantfolio.backtest

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.domain.market.SectorMapSnapshotRepository
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.OpenDartClient
import com.quantfolio.market.krx.KrxIndexService
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
    @Autowired private val dartStore: DartFinancialStore,
    @Autowired private val dividends: DividendStore,
    @Autowired private val constituents: IndexConstituentStore,
    @Autowired private val sectors: SectorMapSnapshotRepository,
    @Autowired private val panic: PanicService,
    @Autowired private val dividendFeed: DividendFeed,
) {
    private val clock = Clock.fixed(Instant.parse("2330-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val ledger = InMemoryFetchLedger()
    private val http = FakeExternalHttp() // DART·Naver 원격 — 호출되면 실패
    private val krxTransport = FakeKrxTransport() // KRX MDC — 호출되면 실패
    private val cooldowns = Cooldowns(clock)
    private val days = LocalTradingDays(indexes, ledger)
    private val dart = DartFinancials(
        OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = "K")), cooldowns, clock),
        dartStore, ledger, cooldowns, clock,
    )
    private val krx: KrxIndexService = run {
        val props = QuantfolioProperties(krx = QuantfolioProperties.Krx("id", "pw"))
        val session = KrxSession(krxTransport, props, cooldowns, clock)
        KrxIndexService(KrxMdc(krxTransport, session, cooldowns) {}, session, cooldowns, constituents, ledger, StoreCache(ledger, clock), sectors, clock)
    }
    private val snaps = LocalSnapshots(daily, indexes, days, Valuation(daily, dart, dartStore, dividends, ledger))
    private val runner = BacktestRunner(
        LocalOhlcvCoverage(ticks, daily, days), ticks, snaps, dart, krx, McapUniverse(daily, days), constituents, daily, panic, dividendFeed, clock,
    )

    private val symbols = listOf("L10001", "L10002", "L10003", "L10004", "L10005")
    private val start = LocalDate.of(2330, 6, 3)
    private val end = LocalDate.of(2330, 9, 30)
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
    fun `flow weight rejected in phase1`() {
        val e = assertThrows<BacktestInputError> { runner.run(config(mapOf("momentum" to 0.5, "flow" to 0.5)), start, end) }
        assertTrue("flow" in e.message!!, e.message)
        assertNoRemoteCalls()
    }

    @Test
    fun `missing benchmark index propagates as not loaded not as backtest failure`() {
        indexes.delete("1028")
        val e = assertThrows<MarketDataNotLoaded> { runner.run(config(mapOf("momentum" to 1.0, "value" to 0.0, "lowvol" to 0.0)), start, end) }
        assertEquals("index_ohlcv", e.kind)
        assertNoRemoteCalls()
    }
}
