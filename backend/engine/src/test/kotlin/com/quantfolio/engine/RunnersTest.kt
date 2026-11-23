package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.Fill
import com.quantfolio.broker.OrderResult
import com.quantfolio.broker.Quote
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.trading.RiskLimit
import com.quantfolio.domain.trading.RiskLimitRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.store.Bar
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.McapUniverse
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 러너 안전 경로(원본 `base_runner`·`runner`·`rebalance_runner`) — 가짜 증권사·고정 시세로 틱 1회씩. */
@SpringBootTest
@Import(ContainersConfig::class)
class RunnersTest(
    @Autowired private val ctx: RunnerContext,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val limits: RiskLimitRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val ticks: PriceTickStore,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val reports: com.quantfolio.backtest.FillQualityReport,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials
    @MockitoBean lateinit var calendar: MarketCalendar
    @MockitoBean @Qualifier("local") lateinit var snapshots: SnapshotSource // 엔진이 쓰는 로컬 소스
    @MockitoBean lateinit var coverage: com.quantfolio.market.ohlcv.LocalOhlcvCoverage
    @MockitoBean lateinit var krx: com.quantfolio.market.krx.KrxIndexService
    @MockitoBean lateinit var sectorMaps: com.quantfolio.market.official.LocalSectorMap
    @MockitoBean lateinit var mcap: McapUniverse
    @MockitoBean lateinit var dart: com.quantfolio.market.dart.DartFinancials

    /** 종목별 고정 시세, 주문은 즉시 전량 체결. */
    class Broker(val quotes: MutableMap<String, BigDecimal> = mutableMapOf(), var halted: Set<String> = emptySet()) : BrokerClient {
        val placed = mutableListOf<Triple<String, String, Int>>()
        var quoteDelayMs = 0L
        var onQuote: (String) -> Unit = {}
        override fun verifyConnection() = true
        override fun getQuote(symbol: String): Quote {
            if (quoteDelayMs > 0) Thread.sleep(quoteDelayMs)
            onQuote(symbol)
            return Quote(symbol, quotes[symbol] ?: BigDecimal("1000"), halted = symbol in halted)
        }
        override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult {
            placed += Triple(symbol, side, qty)
            return OrderResult("ODNO-" + UUID.randomUUID())
        }
        override val orderAccountKey = TEST_KIS_ACCOUNT_KEY
        override fun getOrderExecution(orderId: String, symbol: String?) = Fill(placed.last().third, quotes[symbol] ?: BigDecimal("1000"), true)
        override fun getBalance() = Balance()
    }

    private val broker = Broker()
    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")
    private fun anyDate(): LocalDate = any(LocalDate::class.java) ?: LocalDate.MIN
    private fun anyNow(): ZonedDateTime = any(ZonedDateTime::class.java) ?: ZonedDateTime.now()
    private fun sym() = "9" + Random.nextInt(10_000, 99_999)

    @BeforeEach
    fun stubs() {
        willReturn(true).given(brokers).has(anyUser())
        willReturn(broker).given(brokers).forUser(anyUser())
        willReturn(true).given(calendar).isMarketOpen(anyNow())
        given(calendar.isBusinessDay(anyDate())).willAnswer { (it.arguments[0] as LocalDate).dayOfWeek.value <= 5 }
    }

    private fun strategy(config: Map<String, Any?>): Pair<Int, Int> {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        willReturn(true).given(brokers).isOwner(uid)
        return uid to strategies.save(Strategy(uid, "s", config = config, status = StrategyStatus.live)).id!!
    }

    private fun seed(symbol: String, n: Int, f: (Int) -> Double) {
        val days = generateSequence(LocalDate.now(ZoneOffset.UTC).minusDays(1)) { it.minusDays(1) }.take(n).toList().reversed()
        ticks.upsert(symbol, days.mapIndexed { i, d -> Bar(d.atStartOfDay().toInstant(ZoneOffset.UTC), f(i), f(i), f(i), f(i), 10) })
    }

    private fun ordersOf(sid: Int) = orders.findAll().filter { it.strategyId == sid }

    private fun single(sid: Int, c: RunnerContext = ctx) = StrategyRunner(sid, c).also { assertTrue(it.load()) }

    @Test
    fun singleRunnerBuysOnEntryEdgeWithAuditReason() {
        val code = sym()
        seed(code, 10) { 900.0 }
        val (_, sid) = strategy(
            mapOf(
                "type" to "custom", "symbol" to code, "cash" to 100_000,
                "entry" to listOf(mapOf("left" to mapOf("kind" to "price", "source" to "close"), "op" to ">", "right" to mapOf("kind" to "const", "value" to 1000))),
                "exit" to listOf(mapOf("left" to mapOf("kind" to "price", "source" to "close"), "op" to "<", "right" to mapOf("kind" to "const", "value" to 500))),
            ),
        )
        redis.opsForValue().set(RedisKeys.price(code), "1100")
        single(sid).tickOnce()
        val o = ordersOf(sid).single()
        assertEquals("buy", o.side)
        assertEquals("s$sid:$code:buy:${LocalDate.now(ZoneOffset.UTC)}", o.idempotencyKey)
        assertTrue(o.reason!!.endsWith(" · 진입 90주 @ 1,100(약 99,000원)"), o.reason)
    }

    @Test
    fun missingPriceSkipsAndPositionLockSkips() {
        val code = sym()
        seed(code, 10) { 900.0 }
        // config 손절(stop_loss_pct)은 리스크 게이트와 달리 0 가격 방어가 없다 — 0 이 흘러들면 −100% 로 전량 손절된다
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "stop_loss_pct" to 0.5))
        positions.save(Position(uid, sid, code, BigDecimal("10"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        limits.save(RiskLimit(uid, sid, stopLossPct = BigDecimal("0.1")))
        redis.opsForValue().set(RedisKeys.price(code), "0") // 비정상 캐시 → REST 폴백
        broker.quotes[code] = BigDecimal.ZERO // REST 도 0 → 이번 틱 건너뜀(손절 오발동 금지)
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())

        broker.quotes[code] = BigDecimal("800")
        redis.delete(RedisKeys.price(code))
        redis.opsForValue().set(RedisKeys.positionLock(uid, code), "1")
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty()) // 다른 경로가 같은 종목을 처리 중
        redis.delete(RedisKeys.positionLock(uid, code))

        single(sid).tickOnce()
        val sell = ordersOf(sid).single()
        assertEquals("s$sid:$code:sell:${LocalDate.now(ZoneOffset.UTC)}:stop", sell.idempotencyKey)
        assertEquals("리스크 손절 한도 도달: 현재가 800가 평균단가 1,000 대비 −20.00% 하락(RiskLimit 손절선 이하)", sell.reason)
    }

    @Test
    fun configTakeProfitAndHaltGate() {
        val code = sym()
        seed(code, 10) { 1000.0 }
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "take_profit_pct" to 0.1))
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        positions.save(Position(uid, sid, code, BigDecimal("10"), BigDecimal("2000"), brokerAccountKey = "different-account"))
        positions.save(Position(uid, sid, code, BigDecimal("20"), BigDecimal("3000")))
        broker.quotes[code] = BigDecimal("1200")
        broker.halted = setOf(code)
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty()) // 거래정지 종목은 주문하지 않는다
        broker.halted = emptySet()
        single(sid).tickOnce()
        val o = ordersOf(sid).single()
        assertTrue(o.idempotencyKey.endsWith(":tp"))
        assertEquals(BigDecimal("5.0000"), o.qty)
        assertEquals("익절: 현재가 1,200가 평균단가 1,000 대비 +20.00%로 익절 기준 +10.0% 도달", o.reason)
    }

    /** 판단 뒤 정지 게이트 시세 조회 중에 다른 경로가 체결을 기록하면 그 주문은 보류되고, 다음 틱이 새 장부로 다시 판단한다. */
    @Test
    fun ledgerChangeAfterDecisionHoldsOrderAndNextTickUsesNewQty() {
        val code = sym()
        seed(code, 10) { 1000.0 }
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "take_profit_pct" to 0.1))
        val pos = positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        redis.opsForValue().set(RedisKeys.price(code), "1200") // 현재가는 캐시 — 시세 조회는 정지 게이트에서만
        broker.onQuote = {
            broker.onQuote = {}
            ctx.jdbc.update("update positions set qty = 3 where id = :id", mapOf("id" to pos.id))
        }
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertTrue(broker.placed.isEmpty())
        single(sid).tickOnce()
        val o = ordersOf(sid).single()
        assertTrue(o.idempotencyKey.endsWith(":tp"))
        assertEquals(BigDecimal("3.0000"), o.qty)
    }

    @Test
    fun prodLiveGateBlocksAndAlerts() {
        val code = sym()
        seed(code, 10) { 1000.0 }
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "take_profit_pct" to 0.1))
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        broker.quotes[code] = BigDecimal("1200")
        val prodProps = QuantfolioProperties(kis = QuantfolioProperties.Kis(env = "prod", prodApproved = true))
        val prod = ctx.copy(liveGate = LiveGateService(reports, ctx.risk, ctx.jdbc, prodProps, ctx.clock, users))
        single(sid, prod).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        val a = alerts.findAll().single { it.code == "live_gate_blocked" && it.strategyId == sid }
        assertTrue(a.message.startsWith("실전 게이트 차단 — $code sell 5주 주문 거부. 사유: 체결 정합 표본 부족(n=0 < 30)"), a.message)
    }

    @Test
    fun tossLiveAccountIsBlockedEvenWhenKisIsPaper() {
        val code = sym()
        seed(code, 10) { 1000.0 }
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "take_profit_pct" to 0.1))
        val user = users.findById(uid).get()
        user.broker = "toss"
        users.saveAndFlush(user)
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        broker.quotes[code] = BigDecimal("1200")
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertTrue(broker.placed.isEmpty())
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "live_gate_blocked" })
        assertEquals(false, ctx.liveGate.evaluate(uid, sid).passed)
    }

    @Test
    fun failureAlertFiresOnceAtThresholdAndHealthIsRecorded() {
        val (_, sid) = strategy(mapOf("type" to "rsi", "symbol" to "005930"))
        val r = single(sid)
        repeat(4) { r.recordFailure("점검 오류: boom") }
        assertEquals(1, alerts.findAll().count { it.code == "runner_failures" && it.strategyId == sid })
        assertTrue(redis.opsForValue().get(RedisKeys.engineHealth(sid))!!.contains("\"consecutive_failures\": 4"))
        r.recordSuccess()
        assertTrue(redis.opsForValue().get(RedisKeys.engineHealth(sid))!!.contains("\"consecutive_failures\": 0"))
    }

    private fun rebal(sid: Int) = RebalanceRunner(sid, ctx).also { assertTrue(it.load()) }

    private fun rebalCfg(codes: List<String>, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to "rebalance", "universe" to codes, "selection" to mapOf("method" to "momentum", "lookback" to 5, "top_n" to 2),
        "cadence" to "daily", "rebalance_time" to "00:00", "capital" to 1_000_000, "drift_band_pct" to 0.0,
    ) + extra

    @Test
    fun rebalanceBuysTopMomentumAndRecordsLastRun() {
        val codes = (1..3).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, 30) { i -> 1000.0 + (k + 1) * 10.0 * i } }
        val (_, sid) = strategy(rebalCfg(codes))
        codes.forEach { broker.quotes[it] = BigDecimal("1000") }
        rebal(sid).tickOnce()
        val buys = ordersOf(sid).sortedBy { it.id }
        assertEquals(listOf(codes[2], codes[1]).toSet(), buys.map { it.symbol }.toSet())
        assertTrue(buys.all { it.reason!!.contains("목표비중 50.0% 구성 · 500주 @ 1,000 매수") }, buys.map { it.reason }.toString())
        assertTrue(redis.hasKey(RedisKeys.rebalanceLast(sid)))
        rebal(sid).tickOnce() // 같은 날 다시 와도 이미 실행 — 추가 주문 없음
        assertEquals(2, ordersOf(sid).size)
    }

    /** 락 밖에서 읽은 보유로 정한 매수는 그 뒤 체결이 기록되면 보류된다 — 이미 산 수량을 또 사지 않는다. */
    @Test
    fun rebalanceBuyDecidedOnStaleHoldingsIsHeld() {
        val codes = (1..3).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, 30) { i -> 1000.0 + (k + 1) * 10.0 * i } }
        val (uid, sid) = strategy(rebalCfg(codes))
        codes.forEach { broker.quotes[it] = BigDecimal("1000") }
        broker.onQuote = { // 보유 조회 뒤 첫 시세 조회
            broker.onQuote = {}
            positions.save(Position(uid, sid, codes[2], BigDecimal("500"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        }
        val r = rebal(sid)
        r.tickOnce()
        assertEquals(listOf(codes[1]), ordersOf(sid).map { it.symbol })
        r.rebalanceOnce(ZonedDateTime.now(), riskOff = false) // 다음 발화는 새 장부로 판단 — 이미 목표 수량
        assertEquals(listOf(codes[1]), ordersOf(sid).map { it.symbol })
    }

    @Test
    fun regimeRiskOffLiquidatesAndPanicOverlayRefuses() {
        val codes = listOf(sym())
        seed(codes[0], 30) { 1000.0 }
        val idx = (0 until 60).map { IndexBar(LocalDate.now().minusDays(60L - it), close = if (it < 59) 3000.0 else 2000.0) }
        given(snapshots.indexOhlcv(anyDate(), anyDate(), anyString())).willReturn(idx)
        val (uid, sid) = strategy(rebalCfg(codes, mapOf("regime_filter" to mapOf("enabled" to true, "ma_period" to 20))))
        positions.save(Position(uid, sid, codes[0], BigDecimal("7"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        rebal(sid).tickOnce()
        val sell = ordersOf(sid).single()
        assertTrue(sell.idempotencyKey.endsWith(":regime"))
        assertEquals("레짐 위험회피(현금화): 7주 전량 청산 · 평단 900 대비 손익 +11.11%", sell.reason)

        val (_, sid2) = strategy(rebalCfg(codes, mapOf("panic_overlay" to mapOf("enabled" to true))))
        val r = rebal(sid2)
        repeat(2) { r.tickOnce() }
        assertEquals(1, alerts.findAll().count { it.code == "panic_overlay_unsupported" && it.strategyId == sid2 })
        assertTrue(ordersOf(sid2).isEmpty())
    }

    @Test
    fun mddKillSwitchLiquidatesAndAlerts() {
        val codes = listOf(sym())
        seed(codes[0], 30) { 1000.0 }
        val (uid, sid) = strategy(rebalCfg(codes, mapOf("capital" to 100_000, "risk_layer" to mapOf("mdd_kill_pct" to 0.1, "mdd_rearm_days" to 5))))
        val o = orders.save(Order(uid, sid, codes[0], "buy", BigDecimal("100"), BigDecimal("1000"), status = OrderStatus.filled,
            idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        executions.save(Execution(o.id!!, sid, BigDecimal("100"), BigDecimal("1000"), accountPositionQty = BigDecimal("100")))
        positions.save(Position(uid, sid, codes[0], BigDecimal("100"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        redis.opsForValue().set(RedisKeys.mddState(sid, TEST_KIS_ACCOUNT_KEY), """{"hwm": 100000.0, "killed": false, "kill_date": null, "rearm_pending": false}""")
        broker.quotes[codes[0]] = BigDecimal("800") // 자산가치 80,000 → 낙폭 −20%
        rebal(sid).tickOnce()
        val a = alerts.findAll().single { it.code == "mdd_kill" && it.strategyId == sid }
        assertEquals("MDD 킬스위치 발동 — 고점 100,000 대비 낙폭 -20.00%가 임계 −10.0%를 넘어 전량 청산·현금 대피", a.message)
        val sell = ordersOf(sid).single { it.side == "sell" }
        assertTrue(sell.idempotencyKey.endsWith(":mdd"))
        assertTrue(redis.opsForValue().get(RedisKeys.mddState(sid, TEST_KIS_ACCOUNT_KEY))!!.contains("\"killed\": true"))
    }

    @Test
    fun mddIgnoresExecutionThatOnlyContributedToLegacyPosition() {
        val code = sym()
        val (uid, sid) = strategy(rebalCfg(listOf(code), mapOf("capital" to 100_000, "risk_layer" to mapOf("mdd_kill_pct" to 0.1))))
        val old = orders.save(Order(uid, sid, code, "buy", BigDecimal("100"), BigDecimal("1000"), status = OrderStatus.filled,
            idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        executions.save(Execution(old.id!!, sid, BigDecimal("100"), BigDecimal("1000")))
        positions.save(Position(uid, sid, code, BigDecimal("100"), BigDecimal("1000")))
        val mddKey = RedisKeys.mddState(sid, TEST_KIS_ACCOUNT_KEY)
        redis.opsForValue().set(mddKey, """{"hwm":100000.0,"killed":false}""")

        rebal(sid).tickOnce()

        assertTrue(alerts.findAll().none { it.code == "mdd_kill" && it.strategyId == sid })
        assertTrue(redis.opsForValue().get(mddKey)!!.contains("\"killed\": false"))
    }

    @Test
    fun emptyTargetsRaiseFactorOutage() {
        val codes = listOf(sym()) // 시세 없음 → 모멘텀 선정 불가
        val (_, sid) = strategy(rebalCfg(codes))
        rebal(sid).tickOnce()
        assertEquals(1, alerts.findAll().count { it.code == "factor_outage" && it.strategyId == sid })
        assertTrue(ordersOf(sid).isEmpty())
    }

    private fun mcapCfg(fixed: List<String>, extra: Map<String, Any?> = emptyMap()) =
        rebalCfg(fixed, mapOf("selection" to mapOf("method" to "all", "universe_rule" to mapOf("source" to "KOSPI_MCAP", "pick" to 2))) + extra)

    @Test
    fun `KOSPI_MCAP universe resolves from local`() {
        val members = (1..2).map { sym() }
        val fixed = listOf(sym())
        (members + fixed).forEach { c -> seed(c, 30) { i -> 1000.0 + i } }
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willReturn(members)
        val (_, sid) = strategy(mcapCfg(fixed))
        rebal(sid).tickOnce()
        assertEquals(members.toSet(), ordersOf(sid).map { it.symbol }.toSet())
        org.mockito.Mockito.verify(krx, org.mockito.Mockito.never()).indexMembers(anyDate(), anyString())
        org.mockito.Mockito.verify(mcap).members(anyDate(), org.mockito.ArgumentMatchers.eq(2))
    }

    @Test
    fun `missing previous day data skips rebalance and publishes critical once`() {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { i -> 1000.0 + i }
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI"))
        val (_, sid) = strategy(mcapCfg(fixed))
        val r = rebal(sid)
        r.tickOnce()
        r.tickOnce()
        val crit = alerts.findAll().filter { it.code == "market_data_not_loaded" && it.strategyId == sid }
        assertEquals(1, crit.size)
        assertEquals(AlertSeverity.critical, crit.single().severity)
        assertEquals(false, redis.hasKey(RedisKeys.rebalanceLast(sid))) // 실행된 것으로 기록하지 않는다 — 데이터가 들어오면 다음 점검에서 재시도
    }

    @Test
    fun `no orders placed when universe not loaded`() {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { i -> 1000.0 + i }
        broker.quotes[fixed[0]] = BigDecimal("1000")
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI"))
        val (_, sid) = strategy(mcapCfg(fixed)) // config.universe 폴백이 있어도 KOSPI_MCAP 에는 적용하지 않는다
        val before = broker.placed.size
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(before, broker.placed.size)
    }

    @Test
    fun `missing sector snapshot skips rebalance and publishes critical`() {
        val codes = (1..3).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, 30) { i -> 1000.0 + (k + 1) * 10.0 * i } }
        given(sectorMaps.sectorMap(anyDate())).willThrow(MarketDataNotLoaded("sector_map", "x"))
        val (_, sid) = strategy(rebalCfg(codes, mapOf("risk_layer" to mapOf("max_sector_pct" to 0.3))))
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, alerts.findAll().count { it.code == "market_data_not_loaded" && it.strategyId == sid && it.severity == AlertSeverity.critical })
    }

    private fun heldKilled(uid: Int, sid: Int, held: String) {
        val o = orders.save(Order(uid, sid, held, "buy", BigDecimal("100"), BigDecimal("1000"), status = OrderStatus.filled,
            idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        executions.save(Execution(o.id!!, sid, BigDecimal("100"), BigDecimal("1000"), accountPositionQty = BigDecimal("100")))
        positions.save(Position(uid, sid, held, BigDecimal("100"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        redis.opsForValue().set(RedisKeys.mddState(sid, TEST_KIS_ACCOUNT_KEY), """{"hwm": 100000.0, "killed": false, "kill_date": null, "rearm_pending": false}""")
        broker.quotes[held] = BigDecimal("800") // 자산가치 80,000 → 낙폭 -20%
    }

    private val mdd = mapOf("capital" to 100_000, "risk_layer" to mapOf("mdd_kill_pct" to 0.1, "mdd_rearm_days" to 5))

    @Test
    fun `mdd kill switch liquidates holdings even when universe not loaded`() {
        val fixed = listOf(sym())
        val held = fixed[0]
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI"))
        val (uid, sid) = strategy(mcapCfg(fixed, mdd))
        heldKilled(uid, sid, held)
        rebal(sid).tickOnce()
        assertEquals(listOf(held), ordersOf(sid).filter { it.side == "sell" }.map { it.symbol })
        assertTrue(alerts.findAll().none { it.code == "market_data_not_loaded" && it.strategyId == sid })
    }

    @Test
    fun `risk off sells holdings outside the candidate pool`() {
        val inPool = sym()
        val outside = sym()
        val (uid, sid) = strategy(rebalCfg(listOf(inPool), mdd))
        heldKilled(uid, sid, outside) // universe 에 없는 보유
        rebal(sid).tickOnce()
        assertEquals(listOf(outside), ordersOf(sid).filter { it.side == "sell" }.map { it.symbol })
    }

    @Test
    fun `KOSPI_MCAP decision date is today so previous trading day must be loaded`() {
        val members = (1..2).map { sym() }
        val fixed = listOf(sym())
        (members + fixed).forEach { c -> seed(c, 30) { i -> 1000.0 + i } }
        val today = com.quantfolio.common.MarketClock.todayKst(ctx.clock)
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willReturn(members) // 그 이전 날짜는 적재된 상태
        org.mockito.BDDMockito.willThrow(MarketDataNotLoaded("stock_daily", "KOSPI")).given(mcap).members(org.mockito.ArgumentMatchers.eq(today) ?: today, org.mockito.ArgumentMatchers.anyInt())
        val (_, sid) = strategy(mcapCfg(fixed))
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, alerts.findAll().count { it.code == "market_data_not_loaded" && it.strategyId == sid })
    }

    @Test
    fun `unknown regime stops buying and alerts without liquidating`() {
        val codes = listOf(sym())
        seed(codes[0], 30) { 1000.0 }
        given(snapshots.indexOhlcv(anyDate(), anyDate(), anyString())).willThrow(MarketDataNotLoaded("index_ohlcv", "1001"))
        val (uid, sid) = strategy(rebalCfg(codes, mapOf("regime_filter" to mapOf("enabled" to true, "ma_period" to 20))))
        positions.save(Position(uid, sid, codes[0], BigDecimal("7"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty()) // 국면을 모르면 매수도, 청산도 하지 않는다
        val a = alerts.findAll().single { it.code == "market_data_not_loaded" && it.strategyId == sid }
        assertEquals(AlertSeverity.critical, a.severity)
    }

    @Test
    fun `live tick completes without touching remote sources`() {
        val codes = (1..3).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, 30) { i -> 1000.0 + (k + 1) * 10.0 * i } }
        codes.forEach { broker.quotes[it] = BigDecimal("1000") }
        val idx = (0 until 60).map { IndexBar(LocalDate.now().minusDays(60L - it), close = 3000.0) }
        given(snapshots.indexOhlcv(anyDate(), anyDate(), anyString())).willReturn(idx)
        given(snapshots.priceChange(anyDate(), anyDate(), anyList())).willReturn(codes.mapIndexed { k, c -> StockPeriod(c, changePct = 5.0 + k) })
        given(sectorMaps.sectorMap(anyDate())).willReturn(codes.associateWith { "S" })
        val (_, sid) = strategy(
            rebalCfg(codes, mapOf("selection" to mapOf("method" to "score", "top_n" to 2, "factor_weights" to mapOf("momentum" to 1.0)),
                "regime_filter" to mapOf("enabled" to true, "ma_period" to 20), "risk_layer" to mapOf("max_sector_pct" to 0.6))),
        )
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isNotEmpty(), alerts.findAll().filter { it.strategyId == sid }.map { it.message }.toString())
        org.mockito.Mockito.verify(krx, org.mockito.Mockito.never()).marketCaps(anyDate())
        org.mockito.Mockito.verify(krx, org.mockito.Mockito.never()).indexMembers(anyDate(), anyString())
    }

    @Test
    fun `pead weight is rejected on live with critical and no orders`() {
        val codes = (1..2).map { sym() }
        codes.forEach { seed(it, 30) { i -> 1000.0 + i } }
        val (_, sid) = strategy(rebalCfg(codes, mapOf("selection" to mapOf("method" to "score", "top_n" to 2, "factor_weights" to mapOf("momentum" to 1.0, "pead" to 0.5)))))
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, alerts.findAll().count { it.code == "market_data_not_loaded" && it.strategyId == sid && it.severity == AlertSeverity.critical })
    }

    @Test
    fun `missing local price history stops seeding with critical`() {
        val codes = (1..2).map { sym() }
        codes.forEach { seed(it, 30) { i -> 1000.0 + i } }
        org.mockito.Mockito.doThrow(MarketDataNotLoaded("price_ticks", codes[0])).`when`(coverage).require(org.mockito.ArgumentMatchers.eq(codes[0]) ?: "", anyDate(), anyDate())
        val (_, sid) = strategy(rebalCfg(codes))
        rebal(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, alerts.findAll().count { it.code == "market_data_not_loaded" && it.strategyId == sid })
    }

    @Test
    fun `regime gap recovery does not suppress a later plan gap alert`() {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { 1000.0 }
        val (_, sid) = strategy(mcapCfg(fixed, mapOf("regime_filter" to mapOf("enabled" to true, "ma_period" to 20))))
        org.mockito.BDDMockito.willThrow(MarketDataNotLoaded("index_ohlcv", "1001")).given(snapshots).indexOhlcv(anyDate(), anyDate(), anyString())
        org.mockito.BDDMockito.willThrow(MarketDataNotLoaded("stock_daily", "KOSPI")).given(mcap).members(anyDate(), org.mockito.ArgumentMatchers.anyInt())
        val r = rebal(sid)
        r.tickOnce() // 국면 결손
        val idx = (0 until 60).map { IndexBar(LocalDate.now().minusDays(60L - it), close = 3000.0) }
        org.mockito.BDDMockito.willReturn(idx).given(snapshots).indexOhlcv(anyDate(), anyDate(), anyString())
        r.tickOnce() // 국면 회복, 계획 결손
        assertEquals(2, alerts.findAll().count { it.code == "market_data_not_loaded" && it.strategyId == sid })
    }

    @Test
    fun `critical alert is raised once per episode even after it is read and again after recovery`() {
        val members = (1..2).map { sym() }
        val fixed = listOf(sym())
        (members + fixed).forEach { c -> seed(c, 30) { i -> 1000.0 + i } }
        given(mcap.members(anyDate(), org.mockito.ArgumentMatchers.anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI"))
        val (_, sid) = strategy(mcapCfg(fixed))
        val r = rebal(sid)
        fun mine() = alerts.findAll().filter { it.code == "market_data_not_loaded" && it.strategyId == sid }
        fun readAll() = alerts.saveAll(mine().onEach { it.isRead = true })
        r.tickOnce()
        readAll()
        r.tickOnce() // 읽었어도 같은 에피소드면 다시 쌓지 않는다
        r.tickOnce()
        assertEquals(1, mine().size)
        org.mockito.BDDMockito.willReturn(members).given(mcap).members(anyDate(), org.mockito.ArgumentMatchers.anyInt())
        r.rebalanceOnce(ZonedDateTime.now(), riskOff = false, barTag = "ok") // 해소
        org.mockito.BDDMockito.willThrow(MarketDataNotLoaded("stock_daily", "KOSPI")).given(mcap).members(anyDate(), org.mockito.ArgumentMatchers.anyInt())
        readAll()
        r.rebalanceOnce(ZonedDateTime.now(), riskOff = false, barTag = "again2") // 새 에피소드
        assertEquals(2, mine().size)
    }

    /** 타임아웃으로 끊긴 틱은 주문하지 않는다. 틱 안의 catch(Exception) 가 인터럽트를 삼켜도(시세 조회 실패 처리 등) 마찬가지. */
    @Test
    fun timedOutTickNeverPlacesOrder() {
        val code = sym()
        val (_, sid) = strategy(mapOf("type" to "rsi", "symbol" to code))
        val r = object : BaseRunner(sid, ctx) {
            override val log = org.slf4j.LoggerFactory.getLogger("test.slow")!!
            override val label = "테스트"
            override val tickWord = "tick"
            override val pollIntervalSec = 1L
            override val tickTimeoutSec = 1L
            override fun logStart() = Unit
            override fun tickOnce() {
                try {
                    Thread.sleep(3_000) // 외부 I/O 무응답
                } catch (e: Exception) {
                    // 원래 코드의 조회 실패 처리처럼 인터럽트를 삼키고 계속
                }
                place(code, "buy", 1, BigDecimal("1000"), "slow", "늦은 틱", heldQty = BigDecimal.ZERO)
            }
        }
        val stop = java.util.concurrent.CountDownLatch(1)
        val t = Thread.ofVirtual().start { r.run(stop) }
        Thread.sleep(1_800) // 1초 타임아웃 지나고 다음 틱 전
        stop.countDown()
        t.join(java.time.Duration.ofSeconds(10))
        Thread.sleep(500)
        assertTrue(ordersOf(sid).isEmpty(), ordersOf(sid).map { it.reason }.toString())
    }

    /** 버려진 리밸런싱 틱은 이번 주기를 "실행됨"으로 기록하지 않는다 — 기록하면 그 달 리밸런싱이 조용히 건너뛰어진다(원본은 취소가 _set_last 전에 끊는다). */
    @Test
    fun abandonedRebalanceTickDoesNotConsumeCadence() {
        val codes = (1..2).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, 30) { i -> 1000.0 + (k + 1) * 10.0 * i } }
        val (_, sid) = strategy(rebalCfg(codes))
        broker.quoteDelayMs = 2_000 // 시세 조회 무응답 — 인터럽트는 quotes() 의 catch 가 삼킨다
        val r = RebalanceRunner(sid, ctx, tickTimeoutSec = 1)
        val stop = java.util.concurrent.CountDownLatch(1)
        val t = Thread.ofVirtual().start { r.run(stop) }
        Thread.sleep(1_500)
        stop.countDown()
        t.join(java.time.Duration.ofSeconds(15))
        Thread.sleep(5_000) // 버려진 틱이 끝까지 돌 시간
        broker.quoteDelayMs = 0
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(false, redis.hasKey(RedisKeys.rebalanceLast(sid)))
    }

    // ── 적재 대기 시각(dataReadyBy)·커버리지 관문·전략 시드 검사 ──

    private val today get() = MarketClock.todayKst(ctx.clock)
    private fun kstClock(h: Int, m: Int) = java.time.Clock.fixed(ZonedDateTime.of(today, java.time.LocalTime.of(h, m), MarketClock.KST).toInstant(), ZoneOffset.UTC)
    /** T-1 앞 평일은 원장에 모두 적재된 상태(T-1 만 모름) - 대기 판정의 "앞쪽 결손 없음" 조건을 만족시킨다. */
    private fun at(h: Int, m: Int) = ctx.copy(clock = kstClock(h, m), days = com.quantfolio.market.official.LocalTradingDays(com.quantfolio.test.InMemoryFetchLedger().also { it.trading(weekdaysBack(12).drop(1)) }))
    private fun lastExpected() = ctx.days.expected(today.minusDays(10), today.minusDays(1)).last() // 직전 기대 거래일 T-1
    private fun gapAlerts(sid: Int) = alerts.findAll().filter { it.code == "market_data_not_loaded" && it.strategyId == sid && it.severity == AlertSeverity.critical }
    private fun anyInt() = org.mockito.ArgumentMatchers.anyInt()

    @Test
    fun `previous day not yet loaded before ready time stops quietly then alerts after`() {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { i -> 1000.0 + i }
        val t1 = lastExpected()
        given(mcap.members(anyDate(), anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI", listOf(t1 to t1)))
        val (_, sid) = strategy(mcapCfg(fixed))
        RebalanceRunner(sid, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertTrue(gapAlerts(sid).isEmpty(), "적재 대기 시각 전 T-1 미적재는 경보가 아니다")
        assertEquals(false, redis.hasKey(RedisKeys.rebalanceLast(sid))) // 주기를 소비하지 않는다
        val late = RebalanceRunner(sid, at(10, 31)).also { assertTrue(it.load()) }
        late.tickOnce()
        late.tickOnce()
        assertEquals(1, gapAlerts(sid).size)
        assertTrue(ordersOf(sid).isEmpty())
    }

    @Test
    fun `older gap before ready time still alerts immediately`() {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { i -> 1000.0 + i }
        val t1 = lastExpected()
        given(mcap.members(anyDate(), anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI", listOf(t1.minusDays(3) to t1)))
        val (_, sid) = strategy(mcapCfg(fixed))
        RebalanceRunner(sid, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertEquals(1, gapAlerts(sid).size)
        // 날짜 없는 미적재(업종·커버리지 등)도 대기 대상이 아니다
        val (_, sid2) = strategy(rebalCfg(listOf(fixed[0]), mapOf("risk_layer" to mapOf("max_sector_pct" to 0.3))))
        given(sectorMaps.sectorMap(anyDate())).willThrow(MarketDataNotLoaded("sector_map", "x"))
        RebalanceRunner(sid2, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertEquals(1, gapAlerts(sid2).size)
    }

    @Test
    fun `regime index gap of previous day only waits quietly before ready time`() {
        val codes = listOf(sym())
        seed(codes[0], 30) { 1000.0 }
        val t1 = lastExpected()
        given(snapshots.indexOhlcv(anyDate(), anyDate(), anyString())).willThrow(MarketDataNotLoaded("index_ohlcv", "1001", listOf(t1 to t1)))
        val (uid, sid) = strategy(rebalCfg(codes, mapOf("regime_filter" to mapOf("enabled" to true, "ma_period" to 20))))
        positions.save(Position(uid, sid, codes[0], BigDecimal("7"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        RebalanceRunner(sid, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).isEmpty() && gapAlerts(sid).isEmpty())
        RebalanceRunner(sid, at(10, 31)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, gapAlerts(sid).size)
    }

    @Test
    fun `mdd kill liquidation is unaffected by waiting for previous day data`() {
        val codes = listOf(sym())
        val t1 = lastExpected()
        given(mcap.members(anyDate(), anyInt())).willThrow(MarketDataNotLoaded("stock_daily", "KOSPI", listOf(t1 to t1)))
        val (uid, sid) = strategy(mcapCfg(codes, mdd))
        heldKilled(uid, sid, codes[0])
        RebalanceRunner(sid, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).single { it.side == "sell" }.idempotencyKey.endsWith(":mdd"))
    }

    @Test
    fun `quality weight with local dart coverage below half stops with critical once and no orders`() {
        val codes = (1..4).map { sym() }
        codes.forEach { seed(it, 30) { i -> 1000.0 + i } }
        given(snapshots.priceChange(anyDate(), anyDate(), anyList())).willReturn(codes.map { StockPeriod(it, changePct = 5.0) })
        // dart 는 모의(미적재) -> 모든 종목 지표가 비어 커버리지 0%
        val (_, sid) = strategy(rebalCfg(codes, mapOf("selection" to mapOf("method" to "score", "top_n" to 2, "factor_weights" to mapOf("momentum" to 1.0, "quality" to 1.0)))))
        val r = rebal(sid)
        r.tickOnce()
        r.tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, gapAlerts(sid).size)
        assertTrue(gapAlerts(sid).single().message.contains("factor_coverage quality"), gapAlerts(sid).single().message)
        assertEquals(false, redis.hasKey(RedisKeys.rebalanceLast(sid)))
    }

    private fun entryCfg(code: String, extra: Map<String, Any?> = emptyMap()) = mapOf(
        "type" to "custom", "symbol" to code, "cash" to 100_000,
        "entry" to listOf(mapOf("left" to mapOf("kind" to "price", "source" to "close"), "op" to ">", "right" to mapOf("kind" to "const", "value" to 1000))),
        "exit" to listOf(mapOf("left" to mapOf("kind" to "price", "source" to "close"), "op" to "<", "right" to mapOf("kind" to "const", "value" to 500))),
    ) + extra

    private fun coverageGap(code: String, vararg ranges: Pair<LocalDate, LocalDate>) =
        org.mockito.Mockito.doThrow(MarketDataNotLoaded("price_ticks", code, ranges.toList())).`when`(coverage).require(anyString(), anyDate(), anyDate())

    @Test
    fun `strategy seeding gap stops entries with critical once and later ticks retry seeding`() {
        val code = sym()
        seed(code, 10) { 900.0 }
        val (_, sid) = strategy(entryCfg(code))
        redis.opsForValue().set(RedisKeys.price(code), "1100")
        coverageGap(code, lastExpected().minusDays(3) to lastExpected())
        val r = StrategyRunner(sid, at(10, 31)).also { assertTrue(it.load()) }
        r.tickOnce()
        r.tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, gapAlerts(sid).size)
        org.mockito.Mockito.doNothing().`when`(coverage).require(anyString(), anyDate(), anyDate()) // 데이터 도착
        r.tickOnce() // onLoad 1회 시드가 아니라 틱마다 재시도
        assertEquals("buy", ordersOf(sid).single().side)
        // 회복했으니 다음 결손은 새 에피소드
        coverageGap(code, lastExpected().minusDays(3) to lastExpected())
        val r2 = StrategyRunner(sid, at(10, 31)).also { assertTrue(it.load()) }
        r2.tickOnce()
        assertEquals(2, gapAlerts(sid).size)
    }

    @Test
    fun `strategy waits quietly for previous day before ready time only`() {
        val code = sym()
        seed(code, 10) { 900.0 }
        val (_, sid) = strategy(entryCfg(code))
        redis.opsForValue().set(RedisKeys.price(code), "1100")
        val t1 = lastExpected()
        coverageGap(code, t1 to t1)
        StrategyRunner(sid, at(9, 30)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).isEmpty() && gapAlerts(sid).isEmpty())
        StrategyRunner(sid, at(10, 31)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        assertEquals(1, gapAlerts(sid).size)
    }

    @Test
    fun `stop loss on a held position still works while history is missing`() {
        val code = sym()
        seed(code, 10) { 900.0 }
        val (uid, sid) = strategy(entryCfg(code, mapOf("stop_loss_pct" to 0.1)))
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        broker.quotes[code] = BigDecimal("800")
        coverageGap(code, lastExpected().minusDays(3) to lastExpected())
        StrategyRunner(sid, at(10, 31)).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(ordersOf(sid).single().idempotencyKey.endsWith(":sl"), ordersOf(sid).toString())
        assertEquals(1, gapAlerts(sid).size) // 청산은 하되 이력 결손은 여전히 알린다
    }

    // ── 실제 McapUniverse + 가짜 원장 - 오래된 결손·마스터 미적재가 "T-1 대기" 로 오분류되지 않는다 ──

    private fun weekdaysBack(n: Int) = generateSequence(today.minusDays(1)) { it.minusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(n).toList() // [T-1, T-2, ...]

    /** 실제 [McapUniverse] 를 쓰는 컨텍스트. [loaded] 만 원장에 거래일로 적재돼 있다. */
    private fun realMcapCtx(h: Int, m: Int, loaded: List<LocalDate>): RunnerContext {
        val ledger = com.quantfolio.test.InMemoryFetchLedger().also { it.trading(loaded) }
        val days = com.quantfolio.market.official.LocalTradingDays(ledger)
        return at(h, m).copy(days = days, mcap = McapUniverse(ctx.daily, days))
    }

    private fun mcapGapSid(): Int {
        val fixed = listOf(sym())
        seed(fixed[0], 30) { i -> 1000.0 + i }
        return strategy(mcapCfg(fixed)).second
    }

    @Test
    fun `real mcap path three missing days before ready time alerts immediately`() {
        val wd = weekdaysBack(12)
        val sid = mcapGapSid()
        RebalanceRunner(sid, realMcapCtx(9, 30, wd.drop(3))).also { assertTrue(it.load()) }.tickOnce() // T-3~T-1 미적재
        assertEquals(1, gapAlerts(sid).size)
        assertTrue(ordersOf(sid).isEmpty())
    }

    @Test
    fun `real mcap path only previous day missing before ready time waits quietly`() {
        val wd = weekdaysBack(12)
        val sid = mcapGapSid()
        RebalanceRunner(sid, realMcapCtx(9, 30, wd.drop(1))).also { assertTrue(it.load()) }.tickOnce()
        assertTrue(gapAlerts(sid).isEmpty() && ordersOf(sid).isEmpty())
    }

    @Test
    fun `real mcap path missing stock kind master before ready time alerts immediately`() {
        val wd = weekdaysBack(12)
        val sid = mcapGapSid()
        val t1 = wd[0]
        ctx.daily.write(t1, listOf(StockDaily("999991", market = "KOSPI", marketCap = 1000L, close = 1000.0))) // 주권 종류 없음
        try {
            RebalanceRunner(sid, realMcapCtx(9, 30, wd)).also { assertTrue(it.load()) }.tickOnce()
        } finally {
            ctx.daily.delete(t1)
        }
        assertEquals(1, gapAlerts(sid).size)
    }

    @Test
    fun `min market cap gap is not waited for`() {
        val e = MarketDataNotLoaded("min_market_cap", "x", listOf(lastExpected() to lastExpected()))
        val probe = object : BaseRunner(0, at(9, 30)) {
            override val log = org.slf4j.LoggerFactory.getLogger("test.probe")!!
            override val label = "t"
            override val tickWord = "t"
            override val pollIntervalSec = 1L
            override val tickTimeoutSec = 1L
            override fun logStart() = Unit
            override fun tickOnce() = Unit
            fun waits(x: MarketDataNotLoaded) = awaitingPreviousDay(x)
        }
        assertEquals(false, probe.waits(e))
        assertEquals(true, probe.waits(MarketDataNotLoaded("stock_daily", "KOSPI", listOf(lastExpected() to lastExpected()))))
    }

    @Test
    fun `invalid dataReadyBy fails at construction not in the middle of a tick`() {
        org.junit.jupiter.api.assertThrows<java.time.format.DateTimeParseException> { ctx.copy(dataReadyBy = "25:99") }
    }
}
