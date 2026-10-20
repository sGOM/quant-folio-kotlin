package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.Fill
import com.quantfolio.broker.OrderResult
import com.quantfolio.broker.Quote
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.trading.RiskLimit
import com.quantfolio.domain.trading.RiskLimitRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.Instant
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
    @MockitoBean lateinit var snapshots: SnapshotSource
    @MockitoBean lateinit var krx: com.quantfolio.market.krx.KrxIndexService

    /** 종목별 고정 시세, 주문은 즉시 전량 체결. */
    class Broker(val quotes: MutableMap<String, BigDecimal> = mutableMapOf(), var halted: Set<String> = emptySet()) : BrokerClient {
        val placed = mutableListOf<Triple<String, String, Int>>()
        override fun verifyConnection() = true
        override fun getQuote(symbol: String) = Quote(symbol, quotes[symbol] ?: BigDecimal("1000"), halted = symbol in halted)
        override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult {
            placed += Triple(symbol, side, qty)
            return OrderResult("ODNO-" + UUID.randomUUID())
        }
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
        return uid to strategies.save(Strategy(uid, "s", config = config, status = "live")).id!!
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
        positions.save(Position(uid, sid, code, BigDecimal("10"), BigDecimal("1000")))
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
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000")))
        broker.quotes[code] = BigDecimal("1200")
        broker.halted = setOf(code)
        single(sid).tickOnce()
        assertTrue(ordersOf(sid).isEmpty()) // 거래정지 종목은 주문하지 않는다
        broker.halted = emptySet()
        single(sid).tickOnce()
        val o = ordersOf(sid).single()
        assertTrue(o.idempotencyKey.endsWith(":tp"))
        assertEquals("익절: 현재가 1,200가 평균단가 1,000 대비 +20.00%로 익절 기준 +10.0% 도달", o.reason)
    }

    @Test
    fun prodLiveGateBlocksAndAlerts() {
        val code = sym()
        seed(code, 10) { 1000.0 }
        val (uid, sid) = strategy(mapOf("type" to "rsi", "symbol" to code, "take_profit_pct" to 0.1))
        positions.save(Position(uid, sid, code, BigDecimal("5"), BigDecimal("1000")))
        broker.quotes[code] = BigDecimal("1200")
        val prodProps = QuantfolioProperties(kis = QuantfolioProperties.Kis(env = "prod", prodApproved = true))
        val prod = ctx.copy(props = prodProps, liveGate = LiveGateService(reports, ctx.risk, ctx.jdbc, prodProps, ctx.clock))
        single(sid, prod).tickOnce()
        assertTrue(ordersOf(sid).isEmpty())
        val a = alerts.findAll().single { it.code == "live_gate_blocked" && it.strategyId == sid }
        assertTrue(a.message.startsWith("실전 게이트 차단 — $code sell 5주 주문 거부. 사유: 체결 정합 표본 부족(n=0 < 30)"), a.message)
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

    @Test
    fun regimeRiskOffLiquidatesAndPanicOverlayRefuses() {
        val codes = listOf(sym())
        seed(codes[0], 30) { 1000.0 }
        val idx = (0 until 60).map { IndexBar(LocalDate.now().minusDays(60L - it), close = if (it < 59) 3000.0 else 2000.0) }
        given(snapshots.indexOhlcv(anyDate(), anyDate(), anyString())).willReturn(idx)
        val (uid, sid) = strategy(rebalCfg(codes, mapOf("regime_filter" to mapOf("enabled" to true, "ma_period" to 20))))
        positions.save(Position(uid, sid, codes[0], BigDecimal("7"), BigDecimal("900")))
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
        val o = orders.save(Order(uid, sid, codes[0], "buy", BigDecimal("100"), BigDecimal("1000"), status = "filled", idempotencyKey = UUID.randomUUID().toString()))
        executions.save(Execution(o.id!!, sid, BigDecimal("100"), BigDecimal("1000"), BigDecimal.ZERO, Instant.now()))
        positions.save(Position(uid, sid, codes[0], BigDecimal("100"), BigDecimal("1000")))
        redis.opsForValue().set(RedisKeys.mddState(sid), """{"hwm": 100000.0, "killed": false, "kill_date": null, "rearm_pending": false}""")
        broker.quotes[codes[0]] = BigDecimal("800") // 자산가치 80,000 → 낙폭 −20%
        rebal(sid).tickOnce()
        val a = alerts.findAll().single { it.code == "mdd_kill" && it.strategyId == sid }
        assertEquals("MDD 킬스위치 발동 — 고점 100,000 대비 낙폭 -20.00%가 임계 −10.0%를 넘어 전량 청산·현금 대피", a.message)
        val sell = ordersOf(sid).single { it.side == "sell" }
        assertTrue(sell.idempotencyKey.endsWith(":mdd"))
        assertTrue(redis.opsForValue().get(RedisKeys.mddState(sid))!!.contains("\"killed\": true"))
    }

    @Test
    fun emptyTargetsRaiseFactorOutage() {
        val codes = listOf(sym()) // 시세 없음 → 모멘텀 선정 불가
        val (_, sid) = strategy(rebalCfg(codes))
        rebal(sid).tickOnce()
        assertEquals(1, alerts.findAll().count { it.code == "factor_outage" && it.strategyId == sid })
        assertTrue(ordersOf(sid).isEmpty())
    }

    /** 수용 기준 2: 지수 소스면 그 시점 구성종목(PIT)으로 선정, 조회 실패면 고정 universe 폴백 + 1회 경보. */
    @Test
    fun pitUniverseUsesIndexMembersAndAlertsOnceOnFallback() {
        val members = (1..2).map { sym() }
        val fixed = listOf(sym())
        (members + fixed).forEach { c -> seed(c, 30) { i -> 1000.0 + i } }
        val (_, sid) = strategy(rebalCfg(fixed, mapOf("selection" to mapOf("method" to "all", "universe_rule" to mapOf("source" to "KOSPI200")))))
        given(krx.indexMembers(anyDate(), anyString())).willReturn(members)
        rebal(sid).tickOnce()
        assertEquals(members.toSet(), ordersOf(sid).map { it.symbol }.toSet()) // 고정 universe 가 아니라 그 시점 구성종목

        val (_, sid2) = strategy(rebalCfg(fixed, mapOf("selection" to mapOf("method" to "all", "universe_rule" to mapOf("source" to "KOSPI200")))))
        given(krx.indexMembers(anyDate(), anyString())).willThrow(com.quantfolio.market.source.SourceAuthError("krx", "차단"))
        val r = rebal(sid2)
        r.tickOnce()
        r.rebalanceOnce(java.time.ZonedDateTime.now(), riskOff = false, barTag = "again")
        assertEquals(fixed.toSet(), ordersOf(sid2).map { it.symbol }.toSet())
        assertEquals(1, alerts.findAll().count { it.code == "pit_fallback" && it.strategyId == sid2 })
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
                place(code, "buy", 1, BigDecimal("1000"), "slow", "늦은 틱")
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
}
