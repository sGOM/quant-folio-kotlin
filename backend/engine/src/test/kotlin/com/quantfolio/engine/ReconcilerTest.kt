package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerAccountKey
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.BrokerHttp
import com.quantfolio.broker.BrokerResponse
import com.quantfolio.broker.BrokerSupport
import com.quantfolio.broker.KisClient
import com.quantfolio.broker.BrokerOrderRow
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.broker.Fill
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.CredentialCipher
import com.quantfolio.test.ContainersConfig
import com.quantfolio.alert.AlertPublisher
import org.junit.jupiter.api.Assertions.assertTimeoutPreemptively
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationContext
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.ZoneId
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.io.IOException
import java.math.BigDecimal
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * "지금보다 [agoSeconds] 초 전, 단 오늘(KST)" 주문 시각. now−N초가 어제로 넘어가는 KST 자정 직후에는 오늘 0시로 당기되,
 * 그래도 [minAgoSeconds] 초 전이 못 되면(유예 5분·락 TTL 을 못 넘김) 테스트 전제를 만들 수 없으므로 사유를 남기고 건너뛴다.
 * 기본값 330 = 정합 유예 300초 + 테스트 실행 여유.
 */
internal fun todayOrderTime(agoSeconds: Long = 600, minAgoSeconds: Long = 330): Instant {
    val now = Instant.now()
    val t = maxOf(now.minusSeconds(agoSeconds), MarketClock.kstDate(now).atStartOfDay(MarketClock.KST).toInstant())
    assumeTrue(Duration.between(t, now).seconds >= minAgoSeconds, "KST 자정 직후라 오늘(KST) 주문을 ${minAgoSeconds}초 전으로 만들 수 없어 건너뜀")
    return t
}

/** 미체결·접수불명 주문 정합(원본 `engine/reconcile.py`) — 증분만 기록, 접수불명은 잔고로만 회수·못 밝히면 알림. */
@SpringBootTest
@Import(ContainersConfig::class)
class ReconcilerTest(
    @Autowired private val reconciler: Reconciler,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val positions: com.quantfolio.domain.trading.PositionRepository,
    @Autowired private val notices: FillNoticeApplier,
    @Autowired private val executor: OrderExecutor,
    @Autowired private val cipher: CredentialCipher,
    @Autowired private val ctx: ApplicationContext,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials

    private val broker = OrderExecutorTest.FakeBroker()
    private var balance = Balance()
    private var onBalance: () -> Unit = { }
    private val wrapped = object : com.quantfolio.broker.BrokerClient by broker {
        override fun getBalance() = balance.also { onBalance() }
    }

    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")

    private fun setup(kisAccountNo: String? = null): Pair<Int, Int> {
        willReturn(wrapped).given(brokers).forUser(anyUser())
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x", kisAccountNo = kisAccountNo))
        val s = strategies.save(Strategy(u.id!!, "s", config = mapOf("type" to "rsi")))
        return u.id!! to s.id!!
    }

    private fun order(uid: Int, sid: Int, symbol: String, qty: Int, status: OrderStatus, odno: String?, createdAt: Instant = Instant.now(), side: String = "buy", accountKey: String? = TEST_KIS_ACCOUNT_KEY) =
        orders.save(Order(uid, sid, symbol, side, BigDecimal(qty), BigDecimal("1000"), status = status, kisOrderId = odno, idempotencyKey = UUID.randomUUID().toString(), createdAt = createdAt, brokerAccountKey = accountKey))

    private fun recorded(o: Order) = executions.findAll().filter { it.orderId == o.id }.sumOf { it.filledQty.toInt() }

    private fun acctPosition(uid: Int, sid: Int) = positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(uid, TEST_KIS_ACCOUNT_KEY, sid, "005930")!!

    @Test
    fun recordsOnlyTheIncrementAndIsIdempotent() {
        val (uid, sid) = setup()
        val sym = "9" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val o = order(uid, sid, sym, 3, OrderStatus.submitted, "ODNO-A")
        broker.execution = { Fill(1, BigDecimal("1010"), false) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, recorded(o))
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
        broker.execution = { Fill(3, BigDecimal("1020"), true) }
        repeat(2) { reconciler.reconcileOpenOrders(uid) }
        assertEquals(3, recorded(o)) // 반복해도 중복 가산 없음
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
        val fills = executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("1010.0000"), BigDecimal("1025.0000")), fills.map { it.filledPrice })
        assertEquals(BigDecimal("1020.0000"), positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(uid, TEST_KIS_ACCOUNT_KEY, sid, sym)!!.avgPrice)
    }

    @Test
    fun kisReconcilesPreviousTradingDayAfterKstMidnightWithReusedOrderNumber() {
        val (uid, sid) = setup()
        val dates = mutableListOf<Pair<String, String>>()
        val http = object : BrokerHttp {
            override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
                if (url.endsWith("/oauth2/tokenP")) return BrokerResponse(200, """{"access_token":"test","expires_in":3600}""")
                check(method == "GET" && url.endsWith("/inquire-daily-ccld"))
                val day = query.getValue("INQR_STRT_DT")
                dates += day to query.getValue("INQR_END_DT")
                val today = """{"odno":"123","ord_dt":"20261107","tot_ccld_qty":"1","ord_qty":"1","avg_prvs":"1200"}"""
                val yesterday = """{"odno":"123","ord_dt":"20261106","tot_ccld_qty":"2","ord_qty":"2","avg_prvs":"1000"}"""
                // A wrong-day row with the same order number must not win even if it arrives first.
                val rows = if (day == "20261106") "$today,$yesterday" else today
                return BrokerResponse(200, """{"rt_cd":"0","output1":[$rows]}""")
            }
        }
        val afterMidnight = Clock.fixed(Instant.parse("2026-11-06T15:01:00Z"), ZoneOffset.UTC)
        val support = BrokerSupport(http, redis, QuantfolioProperties(), afterMidnight, JsonMapper.builder().build()) { }
        val kis = KisClient("test-${UUID.randomUUID()}", "test", "50012345-01", support)
        willReturn(kis).given(brokers).forUser(anyUser())
        val yesterday = order(uid, sid, "005930", 2, OrderStatus.submitted, "123", Instant.parse("2026-11-06T06:00:00Z"))
        val today = order(uid, sid, "005930", 1, OrderStatus.submitted, "123", Instant.parse("2026-11-06T15:00:00Z"))

        repeat(2) { reconciler.reconcileOpenOrders(uid) }

        assertEquals(listOf("20261106" to "20261106", "20261107" to "20261107"), dates)
        val byOrder = executions.findAll().filter { it.orderId == yesterday.id || it.orderId == today.id }.associateBy { it.orderId }
        assertEquals(2, recorded(yesterday))
        assertEquals(1, recorded(today))
        assertEquals(BigDecimal("1000.0000"), byOrder.getValue(yesterday.id!!).filledPrice)
        assertEquals(BigDecimal("1200.0000"), byOrder.getValue(today.id!!).filledPrice)
        assertEquals(OrderStatus.filled, orders.findById(yesterday.id!!).orElseThrow().status)
        assertEquals(OrderStatus.filled, orders.findById(today.id!!).orElseThrow().status)
    }

    @Test
    fun legacyOrdersAreNotMatchedToCurrentAccount() {
        val (uid, sid) = setup()
        val submitted = order(uid, sid, "005930", 2, OrderStatus.submitted, "LEGACY", accountKey = null)
        val pending = order(uid, sid, "000660", 2, OrderStatus.pending, null, todayOrderTime(), accountKey = null)
        var lookups = 0
        broker.execution = { lookups++; Fill(2, BigDecimal("1000"), true) }
        var balanceReads = 0
        onBalance = { balanceReads++ }
        balance = Balance(listOf(mapOf("pdno" to "000660", "hldg_qty" to "2")))

        reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(submitted))
        assertEquals(0, recorded(pending))
        assertEquals(OrderStatus.submitted, orders.findById(submitted.id!!).orElseThrow().status)
        assertEquals(OrderStatus.pending, orders.findById(pending.id!!).orElseThrow().status)
        assertEquals(0, lookups)
        assertEquals(0, balanceReads)
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_account_unverified" })
    }

    @Test
    fun environmentAccountChangeKeepsReusedOrderNumbersSeparate() {
        val (uid, sid) = setup()
        val user = users.findById(uid).orElseThrow()
        val queriedAccounts = mutableListOf<String>()
        var executionQty = 0
        var executionPrice = 1000
        val http = object : BrokerHttp {
            override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse = when {
                url.endsWith("/oauth2/tokenP") -> BrokerResponse(200, """{"access_token":"test","expires_in":3600}""")
                url.endsWith("/uapi/hashkey") -> BrokerResponse(200, """{"HASH":"hash"}""")
                url.endsWith("/order-cash") -> BrokerResponse(200, """{"rt_cd":"0","output":{"ODNO":"REUSED"}}""")
                url.endsWith("/inquire-daily-ccld") -> {
                    queriedAccounts += query.getValue("CANO")
                    BrokerResponse(200, """{"rt_cd":"0","output1":[{"odno":"REUSED","tot_ccld_qty":"$executionQty","ord_qty":"2","avg_prvs":"$executionPrice"}]}""")
                }
                url.endsWith("/inquire-balance") -> BrokerResponse(200, """{"rt_cd":"0","output1":[{"pdno":"005930","hldg_qty":"2"}]}""")
                else -> error("Unexpected test broker request: $method $url")
            }
        }
        fun configured(account: String): BrokerClient = BrokerCredentials(
            cipher, QuantfolioProperties(kis = QuantfolioProperties.Kis(appKey = UUID.randomUUID().toString(), appSecret = "test", accountNo = account)),
            redis, http, Clock.systemUTC(),
        ).forUser(user)

        val first = configured("50012345-01")
        val second = configured("87654321-01")
        val old = executor.execute(first, uid, sid, "005930", "buy", 2, BigDecimal("1000"), "old-${UUID.randomUUID()}", BigDecimal.ZERO)!!
        val current = executor.execute(second, uid, sid, "005930", "buy", 2, BigDecimal("1000"), "new-${UUID.randomUUID()}", BigDecimal.ZERO)!!
        assertEquals(first.orderAccountKey, orders.findById(old.id!!).orElseThrow().brokerAccountKey)
        assertEquals(second.orderAccountKey, orders.findById(current.id!!).orElseThrow().brokerAccountKey)
        assertNotEquals(old.brokerAccountKey, current.brokerAccountKey)

        willReturn(second).given(brokers).forUser(anyUser())
        queriedAccounts.clear()
        reconciler.reconcileOpenOrders(uid)
        assertEquals(listOf("87654321"), queriedAccounts)
        assertEquals(0, recorded(old))
        assertEquals(0, recorded(current)) // The new account already holds two shares, with no recorded history yet.

        executionQty = 1
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, recorded(current))

        executionQty = 0
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, recorded(current)) // A verified partial does not establish what B owned before the order.

        executionQty = 2
        reconciler.reconcileOpenOrders(uid)
        assertEquals(0, recorded(old))
        assertEquals(2, recorded(current))
        assertEquals(OrderStatus.submitted, orders.findById(old.id!!).orElseThrow().status)

        executionPrice = 1200
        willReturn(first).given(brokers).forUser(anyUser())
        reconciler.reconcileOpenOrders(uid)
        assertEquals(2, recorded(old))
        assertEquals(2, recorded(current))
        val holdings = positions.findAll().filter { it.userId == uid && it.strategyId == sid && it.symbol == "005930" }
            .associateBy { it.brokerAccountKey }
        assertEquals(setOf(first.orderAccountKey, second.orderAccountKey), holdings.keys)
        assertEquals(BigDecimal("1200.0000"), holdings.getValue(first.orderAccountKey).avgPrice)
        assertEquals(BigDecimal("1000.0000"), holdings.getValue(second.orderAccountKey).avgPrice)
        assertTrue(holdings.values.all { it.qty == BigDecimal("2.0000") })
    }

    @Test
    fun differentAccountHistoryRequiresExactLookupInsteadOfBalanceInference() {
        val (uid, sid) = setup()
        val foreignKey = BrokerAccountKey.kis("87654321-01", paper = true)
        val prior = order(uid, sid, "005930", 4, OrderStatus.filled, "CURRENT-HISTORY")
        val other = order(uid, sid, "000660", 10, OrderStatus.filled, "FOREIGN-HISTORY", accountKey = foreignKey)
        executions.save(Execution(prior.id!!, sid, BigDecimal("4"), BigDecimal("1000")))
        executions.save(Execution(other.id!!, sid, BigDecimal.TEN, BigDecimal("1000")))
        val current = order(uid, sid, "005930", 5, OrderStatus.submitted, "NEW-BUY")
        broker.execution = { Fill(0, null, false) }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "7")))

        repeat(2) { reconciler.reconcileOpenOrders(uid) }

        assertEquals(0, recorded(current))
        assertEquals(OrderStatus.submitted, orders.findById(current.id!!).orElseThrow().status)
        broker.execution = { Fill(3, BigDecimal("1000"), false) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(3, recorded(current))
        assertEquals(4, recorded(prior))
        assertEquals(10, recorded(other))
    }

    @Test
    fun unknownAccountHistoryPreventsBalanceOnlyInferenceButNotExactLookup() {
        val (uid, sid) = setup()
        val legacy = order(uid, sid, "000660", 2, OrderStatus.filled, "LEGACY-HISTORY", accountKey = null)
        executions.save(Execution(legacy.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        val current = order(uid, sid, "005930", 2, OrderStatus.submitted, "SCOPED-BUY")
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))
        broker.execution = { Fill(0, null, false) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(0, recorded(current))

        broker.execution = { Fill(2, BigDecimal("1000"), true) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(2, recorded(current))
        assertEquals(2, recorded(legacy))
    }

    @Test
    fun statusOnlyDriftIsCorrected() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 2, OrderStatus.submitted, "ODNO-B")
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        broker.execution = { Fill(2, BigDecimal("1000"), true) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(2, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun paperBalanceFallbackAssignsOnlyUnexplainedHoldings() {
        val (uid, sid) = setup()
        val sym = "8" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val earlier = order(uid, sid, sym, 2, OrderStatus.filled, "ODNO-OLD")
        executions.save(Execution(earlier.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        val o = order(uid, sid, sym, 5, OrderStatus.submitted, "ODNO-C")
        broker.execution = { Fill(0, null, false) } // 체결조회가 0(체결이 아직 안 보임) — 잔고 교차확인 경로
        balance = Balance(listOf(mapOf("pdno" to sym, "hldg_qty" to "5", "pchs_avg_pric" to "1005")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(3, recorded(o)) // 보유 5 − 다른 주문이 설명한 2
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
    }

    @Test
    fun paperBalanceFallbackSubtractsRecordedSalesAcrossStrategies() {
        val (uid, sid) = setup()
        val priorStrategy = strategies.save(Strategy(uid, "prior", config = mapOf("type" to "rsi"))).id!!
        val buy = order(uid, priorStrategy, "005930", 10, OrderStatus.filled, "BOUGHT")
        val sell = order(uid, priorStrategy, "005930", 8, OrderStatus.partial, "SOLD", side = "sell")
        executions.save(Execution(buy.id!!, priorStrategy, BigDecimal.TEN, BigDecimal("1000")))
        executions.save(Execution(sell.id!!, priorStrategy, BigDecimal("6"), BigDecimal("1000")))
        val current = order(uid, sid, "005930", 5, OrderStatus.submitted, "NEW-BUY")
        broker.execution = { Fill(0, null, false) }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "7", "pchs_avg_pric" to "1010")))

        repeat(2) { reconciler.reconcileOpenOrders(uid) }

        // 7 held − (10 bought − 6 sold) leaves 3. The 2 unfilled sell shares do not count.
        assertEquals(3, recorded(current))
        assertEquals(OrderStatus.partial, orders.findById(current.id!!).orElseThrow().status)
        assertEquals(BigDecimal("3.0000"), acctPosition(uid, sid).qty)
        assertEquals(6, recorded(sell))
    }

    @Test
    fun paperBalanceFallbackDoesNotInferFillsFromNegativeRecordedHoldings() {
        val (uid, sid) = setup()
        val sell = order(uid, sid, "005930", 3, OrderStatus.filled, "SOLD", side = "sell")
        executions.save(Execution(sell.id!!, sid, BigDecimal("3"), BigDecimal("1000")))
        val pending = order(uid, sid, "005930", 5, OrderStatus.pending, null, todayOrderTime())
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))

        reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(pending))
        assertEquals(OrderStatus.pending, orders.findById(pending.id!!).orElseThrow().status)
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_unconfirmed" })
    }

    @Test
    fun paperBalanceFallbackDoesNotUseSnapshotOlderThanRecordedSale() {
        val (uid, sid) = setup()
        val buy = order(uid, sid, "005930", 10, OrderStatus.filled, "BOUGHT")
        executions.save(Execution(buy.id!!, sid, BigDecimal.TEN, BigDecimal("1000")))
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal.TEN, BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val otherSymbol = order(uid, sid, "000660", 1, OrderStatus.submitted, "OTHER")
        val sell = order(uid, sid, "005930", 6, OrderStatus.submitted, "SELL", side = "sell")
        val current = order(uid, sid, "005930", 6, OrderStatus.submitted, "NEW-BUY")
        balance = Balance(listOf(mapOf("pdno" to "000660", "hldg_qty" to "1"), mapOf("pdno" to "005930", "hldg_qty" to "10")))
        broker.execution = { id ->
            if (id == "SELL") {
                balance = Balance(listOf(mapOf("pdno" to "000660", "hldg_qty" to "1"), mapOf("pdno" to "005930", "hldg_qty" to "4")))
                Fill(6, BigDecimal("1000"), true)
            } else Fill(0, null, false)
        }

        repeat(2) { reconciler.reconcileOpenOrders(uid) }

        assertEquals(1, recorded(otherSymbol)) // The first order caused the old snapshot to be read.
        assertEquals(6, recorded(sell))
        assertEquals(0, recorded(current))
        assertEquals(OrderStatus.submitted, orders.findById(current.id!!).orElseThrow().status)
        assertEquals(BigDecimal("4.0000"), acctPosition(uid, sid).qty)
    }

    @Test
    fun stalePendingIsRecoveredFromBalanceOrEscalatedNeverReordered() {
        val (uid, sid) = setup()
        val old = todayOrderTime()
        val visible = "7" + UUID.randomUUID().toString().filter { it.isDigit() }.take(5).padEnd(5, '0')
        val recoverable = order(uid, sid, visible, 4, OrderStatus.pending, null, old)
        val lost = order(uid, sid, "000001", 3, OrderStatus.pending, null, old)
        val sid2 = strategies.save(Strategy(uid, "fresh", config = mapOf("type" to "rsi"))).id!!
        val fresh = order(uid, sid2, "000002", 1, OrderStatus.pending, null) // 아직 전송 중일 수 있음 — 유예 안
        balance = Balance(listOf(mapOf("pdno" to visible, "hldg_qty" to "4")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(4, recorded(recoverable))
        assertEquals(OrderStatus.filled, orders.findById(recoverable.id!!).get().status)
        assertEquals(OrderStatus.pending, orders.findById(lost.id!!).get().status) // 확정하지 않는다
        val alert = alerts.findAll().single { it.code == "order_unconfirmed" && it.strategyId == sid }
        assertEquals(
            "주문 접수 불명 상태 지속 — 000001 buy 3주(주문 #${lost.id}). 증권사 주문번호가 없어 체결조회로 확인할 수 없습니다. 증권사 계좌에서 실제 체결 여부를 직접 확인하세요(이중 주문 위험 때문에 엔진은 재주문하지 않습니다).",
            alert.message,
        )
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, alerts.findAll().count { it.code == "order_unconfirmed" && it.strategyId == sid }) // 6h dedup
        assertEquals(0, broker.placed) // 정합은 어떤 경우에도 주문을 내지 않는다
        assertEquals(0, recorded(fresh))
        assertTrue(alerts.findAll().none { it.strategyId == sid2 }) // 유예 중인 주문은 건드리지도 알리지도 않는다
    }

    @Test
    fun partiallyRecoveredUnknownOrderStaysUnderReconciliation() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 5, OrderStatus.pending, null, todayOrderTime())
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(2, recorded(o))
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status) // 주문번호 없는 partial

        reconciler.reconcileOpenOrders(uid) // 잔고가 그대로면 더 밝힐 수 없다 → 사람에게
        assertEquals(2, recorded(o))
        assertEquals(1, alerts.findAll().count { it.strategyId == sid && it.code == "order_unconfirmed" })

        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "5")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(5, recorded(o)) // 자기 기록분 2를 뺀 나머지 3만
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
        assertEquals(BigDecimal("5.0000"), acctPosition(uid, sid).qty)
        assertEquals(0, broker.placed)
    }

    @Test
    fun submittedWithoutOrderNumberIsTreatedAsUnknown() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "", todayOrderTime())
        reconciler.reconcileOpenOrders(uid)
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_unconfirmed" })

        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "3")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(3, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    private fun changeKisAccount(uid: Int, account: String) {
        val user = users.findById(uid).orElseThrow()
        user.kisAccountNo = account
        users.saveAndFlush(user)
    }

    @Test
    fun changedKisAccountCannotInferFillsFromOldAccountHistory() {
        val (uid, sid) = setup(kisAccountNo = "12345678-01")
        val buy = order(uid, sid, "005930", 10, OrderStatus.filled, "OLD-BUY")
        val sell = order(uid, sid, "005930", 6, OrderStatus.filled, "OLD-SELL", side = "sell")
        executions.save(Execution(buy.id!!, sid, BigDecimal.TEN, BigDecimal("1000")))
        executions.save(Execution(sell.id!!, sid, BigDecimal("6"), BigDecimal("1000")))
        changeKisAccount(uid, "87654321-01")
        val current = order(uid, sid, "005930", 6, OrderStatus.submitted, "NEW-BUY")
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "10")))
        broker.execution = { Fill(0, null, false) }
        var balanceReads = 0
        onBalance = { balanceReads++ }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(current))
        assertEquals(OrderStatus.submitted, orders.findById(current.id!!).orElseThrow().status)
        assertEquals(0, balanceReads)
    }

    @Test
    fun accountChangeDuringBalanceReadCannotWriteInferredFill() {
        val (uid, sid) = setup(kisAccountNo = "12345678-01")
        val pending = order(uid, sid, "005930", 2, OrderStatus.pending, null, todayOrderTime())
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))
        var balanceReads = 0
        onBalance = {
            balanceReads++
            changeKisAccount(uid, "87654321-01")
        }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(1, balanceReads)
        assertEquals(0, recorded(pending))
        assertEquals(OrderStatus.pending, orders.findById(pending.id!!).orElseThrow().status)
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_unconfirmed" })
    }

    @Test
    fun tossUnknownOrderIsNotResolvedFromHoldingsWhenKisIsPaper() {
        val (uid, sid) = setup()
        val user = users.findById(uid).get()
        user.broker = "toss"
        users.saveAndFlush(user)
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, todayOrderTime())
        balance = Balance(listOf(mapOf("symbol" to "005930", "qty" to "10", "avg_price" to "1000")))
        reconciler.reconcileOpenOrders(uid)
        assertEquals(0, recorded(o))
        assertEquals(OrderStatus.pending, orders.findById(o.id!!).get().status)
        assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_unconfirmed" })
        assertEquals(0, broker.placed)
    }

    @Test
    fun cumulativeSellPricePreservesProceedsAndRemainingCostBasis() {
        val (uid, sid) = setup()
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "SELL", side = "sell")
        broker.execution = { Fill(1, BigDecimal("1100"), false) }
        reconciler.reconcileOpenOrders(uid)
        broker.execution = { Fill(3, BigDecimal("1000"), true) }
        repeat(2) { reconciler.reconcileOpenOrders(uid) }
        val fills = executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("1100.0000"), BigDecimal("950.0000")), fills.map { it.filledPrice })
        assertEquals(0, BigDecimal("3000").compareTo(fills.sumOf { it.filledQty * it.filledPrice }))
        val position = acctPosition(uid, sid)
        assertEquals(BigDecimal("2.0000"), position.qty)
        assertEquals(BigDecimal("900.0000"), position.avgPrice)
    }

    @Test
    fun restCumulativePriceSubtractsNoticeFillButNotOtherOrders() {
        val (uid, sid) = setup()
        val prior = order(uid, sid, "005930", 1, OrderStatus.filled, "PRIOR")
        executions.save(Execution(prior.id!!, sid, BigDecimal.ONE, BigDecimal("500")))
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal.ONE, BigDecimal("500"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "MIXED")
        broker.execution = { Fill(1, BigDecimal("100"), false) } // 체결통보가 트리거한 정합이 그때의 누적(1주 @100)을 기록
        assertTrue(notices.apply(uid, FillNotice("MIXED", "005930", 1, BigDecimal("100"), "buy", TEST_KIS_ACCOUNT)))
        assertEquals(1, recorded(o))
        broker.execution = { Fill(3, BigDecimal("110"), true) }
        repeat(2) { reconciler.reconcileOpenOrders(uid) }
        val fills = executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("100.0000"), BigDecimal("115.0000")), fills.map { it.filledPrice })
        assertEquals(BigDecimal("207.5000"), acctPosition(uid, sid).avgPrice)
        assertEquals(3, recorded(o))
    }

    @Test
    fun inconsistentCumulativeValueDoesNotCorruptLedgerAndCanRecover() {
        for (badAverage in listOf("100", "500")) {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 2, OrderStatus.submitted, "BAD")
            broker.execution = { Fill(1, BigDecimal("1000"), false) }
            reconciler.reconcileOpenOrders(uid)
            broker.execution = { Fill(2, BigDecimal(badAverage), true) }
            assertEquals(1, reconciler.reconcileOpenOrders(uid).getValue("errors"))
            assertEquals(1, recorded(o))
            assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
            assertEquals(BigDecimal("1000.0000"), acctPosition(uid, sid).avgPrice)
            broker.execution = { Fill(2, BigDecimal("1100"), true) }
            reconciler.reconcileOpenOrders(uid)
            assertEquals(2, recorded(o))
            assertEquals(BigDecimal("1100.0000"), acctPosition(uid, sid).avgPrice)
        }
    }

    @Test
    fun incrementalPriceThatRoundsToZeroDoesNotCompleteOrder() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 1001, OrderStatus.submitted, "PRECISION")
        broker.execution = { Fill(800, BigDecimal("488"), false) }
        reconciler.reconcileOpenOrders(uid)
        // Only 0.01 remains to allocate to 201 shares, below the stored price precision.
        broker.execution = { Fill(1001, BigDecimal("390.01"), true) }
        assertEquals(1, reconciler.reconcileOpenOrders(uid).getValue("errors"))
        assertEquals(800, recorded(o))
        assertEquals(OrderStatus.partial, orders.findById(o.id!!).get().status)
    }

    @Test
    fun balanceLookupFailureIsUndeterminedNotAbsent() {
        val (uid, sid) = setup()
        val old = todayOrderTime()
        val first = order(uid, sid, "005930", 2, OrderStatus.pending, null, old)
        val second = order(uid, sid, "000660", 2, OrderStatus.pending, null, old)
        var balanceReads = 0
        onBalance = { balanceReads++; throw BrokerError("일시 장애") }

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(1, balanceReads) // 실패도 같은 pass 안에서는 캐시한다
        assertEquals(1, stats.getValue("errors"))
        assertTrue(alerts.findAll().none { it.strategyId == sid && it.code == "order_unconfirmed" })
        assertEquals(0, recorded(first) + recorded(second))

        onBalance = { }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))
        assertEquals(0, reconciler.reconcileOpenOrders(uid).getValue("errors"))
        assertEquals(2, recorded(first)) // 정상 응답이면 기존대로 회수
        assertEquals(1, alerts.findAll().count { it.strategyId == sid && it.code == "order_unconfirmed" }) // 정말 없는 종목(second)만 알림
    }

    @Test
    fun staleOrderSnapshotDoesNotRevertNewerStatus() {
        val (uid, sid) = setup()
        // 2/3 이 기록됐는데 상태가 submitted 로 남은 행 — 정합이 상태만 보정하는 분기.
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "STALE")
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        // 정합이 증권사를 기다리는 동안(락 만료 포함) 체결통보가 트리거한 정합이 새 누적(3주)으로 마지막 1주를 반영한 상황 — 바깥 정합의 응답은 낡은 2주.
        broker.execution = lookups(Fill(2, BigDecimal("1000"), false), Fill(3, BigDecimal("1100"), true)) {
            redis.delete(RedisKeys.reconcileLock(o.id!!))
            assertTrue(notices.apply(uid, FillNotice("STALE", "005930", 1, BigDecimal("1100"), "buy", TEST_KIS_ACCOUNT)))
        }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(3, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    /**
     * 정합이 증권사 응답을 기다리는 중에 도착한 체결통보. 통보는 그 주문의 정합을 트리거하고, 그 정합은 락이 바쁘면 기다리지 않고 넘긴다 —
     * 정합이 주문 락을 쥔 채 증권사를 기다리면 통보가 트리거한 정합이 반영하지 못하므로 락이 비었는지 바로 판정한다.
     * [qty] 는 건별 통보 수량(장부에 쓰이지 않는다).
     */
    private fun noticeWhileReconcileWaits(uid: Int, o: Order, qty: Int, price: String): Boolean {
        assertNull(redis.opsForValue().get(RedisKeys.reconcileLock(o.id!!)), "정합이 증권사 조회 중 주문 락을 쥐고 있다 — 체결통보가 트리거한 정합이 반영될 수 없다")
        return notices.apply(uid, FillNotice(o.kisOrderId!!, o.symbol, qty, BigDecimal(price), o.side, TEST_KIS_ACCOUNT))
    }

    /**
     * 단건 체결조회 응답을 호출 순서대로 준다(마지막 응답은 이후 반복). 첫 호출(바깥 정합의 조회) 중에 [during] 을 실행한다 —
     * 그 안의 체결통보가 트리거한 정합은 두 번째 응답을 받는다.
     */
    private fun lookups(vararg responses: Fill, during: () -> Unit = {}): (String) -> Fill {
        var calls = 0
        return {
            val n = calls++
            if (n == 0) during()
            responses[minOf(n, responses.size - 1)]
        }
    }

    @Test
    fun sellNoticeArrivingWhileReconcileWaitsOnBrokerIsApplied() {
        val (uid, sid) = setup()
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        // 매도는 잔고로 체결을 추정하지 않는다 — 바깥 정합의 조회가 아직 0 이어도 통보가 트리거한 정합이 새 누적(2주)으로 반영한다.
        val sell = order(uid, sid, "005930", 2, OrderStatus.submitted, "PAPER-SELL", side = "sell")
        var applied = false
        broker.execution = lookups(Fill(0, null, false), Fill(2, BigDecimal("1100"), true)) { applied = noticeWhileReconcileWaits(uid, sell, 2, "1100") }

        reconciler.reconcileOpenOrders(uid)

        assertTrue(applied)
        assertEquals(2, recorded(sell))
        assertEquals(OrderStatus.filled, orders.findById(sell.id!!).get().status)
        assertEquals(BigDecimal("3.0000"), acctPosition(uid, sid).qty)
    }

    @Test
    fun noticeDuringLookupAndRestCumulativeAreNotRecordedTwice() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 2, OrderStatus.submitted, "BOTH")
        var applied = false
        broker.execution = lookups(Fill(2, BigDecimal("1000"), true)) { applied = noticeWhileReconcileWaits(uid, o, 2, "1000") }

        repeat(2) { reconciler.reconcileOpenOrders(uid) }

        assertTrue(applied)
        assertEquals(1, executions.findAll().count { it.orderId == o.id })
        assertEquals(2, recorded(o))
        assertEquals(BigDecimal("2.0000"), acctPosition(uid, sid).qty)
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun partialNoticeDuringLookupLeavesOnlyTheRemainderToReconcile() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "PART")
        // 통보가 트리거한 정합은 그때의 누적 1주 @1000 을, 바깥 정합은 3주 @1100 을 받는다.
        broker.execution = lookups(Fill(3, BigDecimal("1100"), true), Fill(1, BigDecimal("1000"), false)) { assertTrue(noticeWhileReconcileWaits(uid, o, 1, "1000")) }

        reconciler.reconcileOpenOrders(uid)

        val fills = executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("1000.0000"), BigDecimal("1150.0000")), fills.map { it.filledPrice }) // (1100×3 − 1000) / 2
        assertEquals(BigDecimal("3.0000"), acctPosition(uid, sid).qty)
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun fullNoticeDuringLookupIsNotRevertedByStaleRestCumulative() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 2, OrderStatus.submitted, "FULL")
        // 바깥 정합의 조회 응답은 통보가 트리거한 정합의 조회(2주)보다 먼저 만들어진 누적(1주)이다.
        broker.execution = lookups(Fill(1, BigDecimal("1000"), false), Fill(2, BigDecimal("1000"), true)) { assertTrue(noticeWhileReconcileWaits(uid, o, 1, "1000")) }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(1, executions.findAll().count { it.orderId == o.id })
        assertEquals(2, recorded(o))
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun noticeDuringBalanceReadIsSubtractedFromBalanceFallback() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "BAL")
        // 바깥 정합의 체결조회는 0(체결이 아직 안 보임) → 잔고 폴백. 잔고를 읽는 중 통보가 트리거한 정합은 누적 2주를 받는다.
        broker.execution = lookups(Fill(0, null, false), Fill(2, BigDecimal("1000"), false))
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "3", "pchs_avg_pric" to "1000")))
        onBalance = { assertTrue(noticeWhileReconcileWaits(uid, o, 1, "1000")) }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(3, recorded(o)) // 통보 2 + 잔고가 설명하는 나머지 1
        assertEquals(BigDecimal("3.0000"), acctPosition(uid, sid).qty)
        assertEquals(OrderStatus.filled, orders.findById(o.id!!).get().status)
    }

    @Test
    fun unexpectedFailureOnOneOrderDoesNotStopThePass() {
        val (uid, sid) = setup()
        val boom = order(uid, sid, "005930", 1, OrderStatus.submitted, "BOOM")
        val next = order(uid, sid, "000660", 1, OrderStatus.submitted, "NEXT")
        broker.execution = { id -> if (id == "BOOM") throw IOException("연결 끊김") else Fill(1, BigDecimal("1000"), true) }

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(boom))
        assertEquals(OrderStatus.submitted, orders.findById(boom.id!!).get().status)
        assertEquals(1, recorded(next))
        assertEquals(1, stats.getValue("errors"))
    }

    @Test
    fun unexpectedBalanceFailureIsUndeterminedOncePerUser() {
        val (uid, sid) = setup()
        val old = todayOrderTime()
        val first = order(uid, sid, "005930", 2, OrderStatus.pending, null, old)
        val second = order(uid, sid, "000660", 2, OrderStatus.pending, null, old)
        var balanceReads = 0
        onBalance = { balanceReads++; throw IOException("연결 끊김") }

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(1, balanceReads)
        assertEquals(1, stats.getValue("errors"))
        assertEquals(0, recorded(first) + recorded(second))
        assertTrue(alerts.findAll().none { it.strategyId == sid && it.code == "order_unconfirmed" })
    }

    @Test
    fun unknownOrderPriceSkipsOnlyThatOrderAndCountsAnError() {
        val (uid, sid) = setup()
        val noPrice = orders.save(Order(uid, sid, "005930", "buy", BigDecimal("2"), null, status = OrderStatus.submitted, kisOrderId = "NOPRICE", idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val other = order(uid, sid, "000660", 1, OrderStatus.submitted, "OTHER")
        broker.execution = { id -> if (id == "NOPRICE") Fill(2, null, true) else Fill(1, BigDecimal("1000"), true) } // 평균가 없음 → 주문가 폴백이 필요한데 주문가도 없다

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(noPrice))
        assertEquals(1, recorded(other)) // 한 주문 때문에 같은 pass 의 나머지가 멈추지 않는다
        assertEquals(1, stats.getValue("errors"))
        assertEquals(OrderStatus.submitted, orders.findById(noPrice.id!!).get().status)
    }

    @Test
    fun unknownOrderPriceInBalanceFallbackIsNotRecordedNorAlerted() {
        val (uid, sid) = setup()
        val o = orders.save(Order(uid, sid, "005930", "buy", BigDecimal("2"), null, status = OrderStatus.pending, idempotencyKey = UUID.randomUUID().toString(), createdAt = todayOrderTime(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2"))) // 매입평균가 없음

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(o))
        assertEquals(1, stats.getValue("errors"))
        assertTrue(alerts.findAll().none { it.strategyId == sid && it.code == "order_unconfirmed" }) // 단가만 모르는 것이지 접수불명 확정이 아니다
    }

    @Test
    fun lockedOrdersAreSkippedAndBrokerFailuresCount() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 1, OrderStatus.submitted, "ODNO-D")
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        broker.execution = { Fill(1, BigDecimal("1000"), true) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(0, recorded(o))
        redis.delete(RedisKeys.reconcileLock(o.id!!))

        order(uid, sid, "000660", 1, OrderStatus.submitted, "ODNO-E")
        order(uid, sid, "035420", 1, OrderStatus.submitted, "ODNO-F")
        given(brokers.forUser(anyUser())).willThrow(BrokerError("자격증명 없음"))
        val stats = reconciler.reconcileOpenOrders(uid)
        assertEquals(1, stats.getValue("errors")) // 주문 3건이어도 실패한 사용자 1명 = 1건(원본과 같은 의미)
    }

    // ── 주문번호 회수 · 주문일이 지난 주문의 종결 ──

    private fun dayAgo(): Instant = Instant.now().minus(Duration.ofDays(1))

    private fun row(no: String, symbol: String, side: String, qty: Int, filled: Int, at: Instant?, avg: String? = null, orig: String? = null) =
        BrokerOrderRow(no, symbol, side, qty, filled, avg?.let(::BigDecimal), at?.atZone(MarketClock.KST)?.toLocalTime()?.withNano(0), orig)

    private fun reload(o: Order) = orders.findById(o.id!!).orElseThrow()

    private fun alertsOf(sid: Int, code: String) = alerts.findAll().filter { it.strategyId == sid && it.code == code }

    @Test
    fun uniqueListCandidateAttachesOrderNumberThenNumberPathReconciles() {
        val (uid, sid) = setup()
        val created = todayOrderTime()
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
        broker.orderList = { day ->
            assertEquals(MarketClock.kstDate(created), day)
            listOf(row("RECOVERED", "005930", "buy", 3, 0, created.plusSeconds(1)), row("OTHER", "005930", "sell", 3, 0, created.plusSeconds(1)))
        }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "3", "pchs_avg_pric" to "1000")))
        var balanceReads = 0
        onBalance = { balanceReads++ }
        val lookups = mutableListOf<String>()
        broker.execution = { id -> lookups += id; Fill(3, BigDecimal("1010"), true) }

        reconciler.reconcileOpenOrders(uid)

        val attached = reload(o)
        assertEquals("RECOVERED", attached.kisOrderId)
        assertEquals(OrderStatus.submitted, attached.status)
        assertNull(attached.closedAt)
        assertEquals(0, recorded(o)) // 붙인 pass 에서는 잔고로 배정하지 않는다
        assertEquals(0, balanceReads)
        assertEquals(emptyList(), lookups)
        assertEquals(AlertSeverity.warning, alertsOf(sid, "order_number_recovered").single().severity)
        assertTrue(alertsOf(sid, "order_unconfirmed").isEmpty())

        reconciler.reconcileOpenOrders(uid) // 다음 pass 는 주문번호 경로
        assertEquals(listOf("RECOVERED"), lookups)
        assertEquals(3, recorded(o))
        assertEquals(BigDecimal("1010.0000"), executions.findAll().single { it.orderId == o.id }.filledPrice)
        assertEquals(OrderStatus.filled, reload(o).status)
        assertEquals(0, broker.placed)
    }

    @Test
    fun orderNumberIsNotAttachedUnlessExactlyOneCandidate() {
        val cases = linkedMapOf<String, (Instant) -> List<BrokerOrderRow>>(
            "후보 둘" to { t -> listOf(row("A", "005930", "buy", 3, 0, t.plusSeconds(1)), row("B", "005930", "buy", 3, 0, t.plusSeconds(5))) },
            "창 뒤" to { t -> listOf(row("A", "005930", "buy", 3, 0, t.plusSeconds(121))) },
            "창 앞" to { t -> listOf(row("A", "005930", "buy", 3, 0, t.minusSeconds(3))) },
            "시각 없음" to { _ -> listOf(row("A", "005930", "buy", 3, 0, null)) },
            "수량 다름" to { t -> listOf(row("A", "005930", "buy", 4, 0, t.plusSeconds(1))) },
            "방향 다름" to { t -> listOf(row("A", "005930", "sell", 3, 0, t.plusSeconds(1))) },
            "종목 다름" to { t -> listOf(row("A", "000660", "buy", 3, 0, t.plusSeconds(1))) },
            "다른 주문에 붙은 번호" to { t -> listOf(row("TAKEN", "005930", "buy", 3, 0, t.plusSeconds(1))) },
        )
        for ((name, rows) in cases) {
            val (uid, sid) = setup()
            val created = todayOrderTime()
            order(uid, sid, "000660", 1, OrderStatus.filled, "TAKEN", created) // 같은 주문일이어야 "붙은 번호" 다
            val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
            broker.orderList = { rows(created) }

            reconciler.reconcileOpenOrders(uid)

            assertNull(reload(o).kisOrderId, name)
            assertEquals(OrderStatus.pending, reload(o).status, name)
            assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty(), name) // 기존대로 잔고 교차확인 → 사람에게
            assertTrue(alertsOf(sid, "order_number_recovered").isEmpty(), name)
        }
        assertEquals(0, broker.placed)
    }

    @Test
    fun sameNumberOnAnotherDaysOrderDoesNotBlockRecovery() {
        val (uid, sid) = setup()
        val created = todayOrderTime()
        order(uid, sid, "000660", 1, OrderStatus.filled, "RECOVERED", created.minus(Duration.ofDays(1))) // 전날 주문이 같은 번호를 썼다
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
        broker.orderList = { listOf(row("RECOVERED", "005930", "buy", 3, 0, created.plusSeconds(1))) }

        reconciler.reconcileOpenOrders(uid)

        assertEquals("RECOVERED", reload(o).kisOrderId)
        assertTrue(alertsOf(sid, "order_number_recovered").isNotEmpty())
    }

    @Test
    fun orderListFailureOrUnsupportedKeepsBalanceCrossCheck() {
        val (uid, sid) = setup()
        val created = todayOrderTime()
        val held = order(uid, sid, "005930", 2, OrderStatus.pending, null, created)
        val lost = order(uid, sid, "000660", 2, OrderStatus.pending, null, created)
        broker.orderList = { throw BrokerError("주문 목록 조회 실패") }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2", "pchs_avg_pric" to "1000")))

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(1, broker.orderListCalls) // 실패도 사용자·주문일 단위로 캐시
        assertEquals(1, stats.getValue("errors"))
        assertEquals(2, recorded(held)) // 기존대로 잔고로 회수
        assertNull(reload(lost).kisOrderId)
        assertEquals(1, alertsOf(sid, "order_unconfirmed").size)

        val (uid2, sid2) = setup()
        val unsupported = order(uid2, sid2, "005930", 2, OrderStatus.pending, null, created)
        broker.orderList = { null } // 토스 등 미지원
        balance = Balance()
        assertEquals(0, reconciler.reconcileOpenOrders(uid2).getValue("errors"))
        assertNull(reload(unsupported).kisOrderId)
        assertEquals(1, alertsOf(sid2, "order_unconfirmed").size)
        assertEquals(0, broker.placed)
    }

    @Test
    fun priorDayOrderWithZeroFillRowIsCancelledAndLeavesReconciliation() {
        val (uid, sid) = setup()
        val created = dayAgo()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "GONE", created, side = "sell")
        var lookups = 0
        broker.execution = { lookups++; Fill(0, null, false) }
        broker.orderList = { day ->
            assertEquals(MarketClock.kstDate(created), day)
            listOf(row("GONE", "005930", "sell", 3, 0, created))
        }

        reconciler.reconcileOpenOrders(uid)

        val closed = reload(o)
        assertEquals(OrderStatus.cancelled, closed.status)
        assertNotNull(closed.closedAt)
        assertEquals(0, BigDecimal("3").compareTo(closed.qty))
        assertEquals(0, recorded(o))
        assertTrue(alerts.findAll().none { it.strategyId == sid }) // 주문번호 있는 주문의 종결은 로그만
        val calls = lookups to broker.orderListCalls
        assertEquals(0, reconciler.reconcileOpenOrders(uid).getValue("checked"))
        assertEquals(calls, lookups to broker.orderListCalls) // 종결된 주문은 증권사 조회 대상이 아니다
    }

    @Test
    fun priorDayPartialMatchingRowKeepsPartialAndLedgerUntouched() {
        val (uid, sid) = setup()
        val created = dayAgo()
        val o = order(uid, sid, "005930", 5, OrderStatus.partial, "HALF", created)
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal("2"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        broker.execution = { Fill(0, null, false) }
        broker.orderList = { listOf(row("HALF", "005930", "buy", 5, 2, created, "1000")) }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "9", "pchs_avg_pric" to "1000"))) // 행 근거가 있으면 잔고로 배정하지 않는다

        reconciler.reconcileOpenOrders(uid)

        val closed = reload(o)
        assertEquals(OrderStatus.partial, closed.status)
        assertNotNull(closed.closedAt)
        assertEquals(0, BigDecimal("5").compareTo(closed.qty))
        assertEquals(1, executions.findAll().count { it.orderId == o.id })
        assertEquals(BigDecimal("2.0000"), acctPosition(uid, sid).qty)
    }

    @Test
    fun priorDayZeroFillRowOverridesBalanceInference() {
        val (uid, sid) = setup()
        val created = dayAgo()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "NOFILL", created)
        broker.execution = { Fill(0, null, false) }
        broker.orderList = { listOf(row("NOFILL", "005930", "buy", 3, 0, created)) }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "3", "pchs_avg_pric" to "1000"))) // 설명되지 않는 보유(HTS 매수 등)

        reconciler.reconcileOpenOrders(uid)

        assertEquals(0, recorded(o)) // 증권사가 이 주문의 체결 0 을 직접 말하면 잔고로 체결을 지어내지 않는다
        assertEquals(OrderStatus.cancelled, reload(o).status)
        assertNotNull(reload(o).closedAt)
    }

    @Test
    fun priorDayRowAboveRecordIsAppliedIncrementallyThenClosed() {
        val (uid, sid) = setup()
        val created = dayAgo()
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal("4"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val o = order(uid, sid, "005930", 5, OrderStatus.partial, "MORE", created, side = "sell")
        executions.save(Execution(o.id!!, sid, BigDecimal.ONE, BigDecimal("1100")))
        broker.execution = { Fill(1, BigDecimal("1100"), false) } // 체결조회는 낡은 누적 — 목록 행의 더 큰 누적을 쓴다
        broker.orderList = { listOf(row("MORE", "005930", "sell", 5, 3, created, "1000")) }

        reconciler.reconcileOpenOrders(uid)

        val fills = executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("1100.0000"), BigDecimal("950.0000")), fills.map { it.filledPrice }) // (1000×3 − 1100) / 2
        assertEquals(3, recorded(o))
        assertEquals(BigDecimal("2.0000"), acctPosition(uid, sid).qty)
        val closed = reload(o)
        assertEquals(OrderStatus.partial, closed.status)
        assertNotNull(closed.closedAt)
    }

    @Test
    fun priorDayRowBelowRecordIsNotClosedAndEscalates() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 5, OrderStatus.partial, "LESS", dayAgo(), side = "sell")
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        broker.orderList = { listOf(row("LESS", "005930", "sell", 5, 1, null)) }

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(1, stats.getValue("errors"))
        assertNull(reload(o).closedAt)
        assertEquals(OrderStatus.partial, reload(o).status)
        assertEquals(2, recorded(o)) // 기록을 줄이지 않는다
        val alert = alertsOf(sid, "order_fill_mismatch").single()
        assertEquals(AlertSeverity.critical, alert.severity)
        assertTrue(alert.message.contains("#${o.id}") && alert.message.contains("005930") && alert.message.contains("장부를 직접 확인"), alert.message)
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, alertsOf(sid, "order_fill_mismatch").size) // 접수불명 알림과 같은 dedup
    }

    @Test
    fun priorDayOrderWithoutCertainRowEvidenceIsNotClosed() {
        val cases = listOf<Triple<String, (Instant) -> List<BrokerOrderRow>, Int>>(
            Triple("행 없음", { t -> listOf(row("ELSE", "005930", "sell", 3, 0, t)) }, 0),
            Triple("목록 조회 실패", { _ -> throw BrokerError("목록 미완결") }, 1),
            Triple("같은 번호 행 둘", { t -> listOf(row("NUM", "005930", "sell", 3, 0, t), row("NUM", "005930", "sell", 3, 0, t)) }, 1),
            Triple("종목 다름", { t -> listOf(row("NUM", "000660", "sell", 3, 0, t)) }, 1),
            Triple("방향 다름", { t -> listOf(row("NUM", "005930", "buy", 3, 0, t)) }, 1),
        )
        for ((name, rows, errors) in cases) {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "NUM", dayAgo(), side = "sell")
            broker.execution = { Fill(0, null, false) }
            broker.orderList = { rows(o.createdAt) }
            balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "3"))) // 잔고 3 ≠ 장부 0 — 모의 매도 잔고 근거(R3)도 없게
            assertEquals(errors, reconciler.reconcileOpenOrders(uid).getValue("errors"), name)
            assertNull(reload(o).closedAt, name)
            assertEquals(OrderStatus.submitted, reload(o).status, name)
        }
    }

    @Test
    fun sameDayOrdersAreNeverClosed() {
        val (uid, sid) = setup()
        val numbered = order(uid, sid, "005930", 3, OrderStatus.submitted, "TODAY", side = "sell")
        val unknown = order(uid, sid, "000660", 3, OrderStatus.pending, null, todayOrderTime()) // 잔고에 없는 매수(E2 조건)
        broker.execution = { Fill(0, null, false) }
        broker.orderList = { listOf(row("TODAY", "005930", "sell", 3, 0, numbered.createdAt)) }

        reconciler.reconcileOpenOrders(uid)

        assertNull(reload(numbered).closedAt)
        assertEquals(OrderStatus.submitted, reload(numbered).status)
        assertNull(reload(unknown).closedAt)
        assertEquals(OrderStatus.pending, reload(unknown).status)
        assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty())
        assertTrue(alertsOf(sid, "order_closed_unconfirmed").isEmpty())
    }

    @Test
    fun priorDayExecutionLookupTotalClosesWhenFullyRecorded() {
        val (uid, sid) = setup()
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("900"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val partial = order(uid, sid, "005930", 5, OrderStatus.submitted, "E1P", dayAgo(), side = "sell")
        val zero = order(uid, sid, "000660", 5, OrderStatus.submitted, "E1Z", dayAgo(), side = "sell")
        broker.execution = { id -> if (id == "E1P") Fill(2, BigDecimal("1000"), false) else Fill(0, null, false) } // 목록 미지원(null)
        balance = Balance(listOf(mapOf("pdno" to "000660", "hldg_qty" to "5"))) // 잔고 5 ≠ 장부 0 — 모의 매도 잔고 근거(R3)도 없게

        reconciler.reconcileOpenOrders(uid)

        assertEquals(2, recorded(partial))
        assertEquals(OrderStatus.partial, reload(partial).status)
        assertNotNull(reload(partial).closedAt)
        assertNull(reload(zero).closedAt) // 체결 0 은 행 없음과 구분되지 않는다
        assertEquals(OrderStatus.submitted, reload(zero).status)
    }

    @Test
    fun priorDayPaperBuyWithNothingLeftInBalanceIsClosed() {
        val (uid, sid) = setup()
        val prior = order(uid, sid, "005930", 2, OrderStatus.filled, "PRIOR", dayAgo())
        executions.save(Execution(prior.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        val numbered = order(uid, sid, "005930", 3, OrderStatus.submitted, "E2N", dayAgo())
        val unknown = order(uid, sid, "000660", 4, OrderStatus.pending, null, dayAgo())
        broker.execution = { Fill(0, null, false) }
        balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2", "pchs_avg_pric" to "1000"))) // 기존 체결이 다 설명, 000660 없음
        var balanceReads = 0
        onBalance = { balanceReads++ }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(OrderStatus.cancelled, reload(numbered).status)
        assertNotNull(reload(numbered).closedAt)
        assertEquals(OrderStatus.cancelled, reload(unknown).status)
        assertNotNull(reload(unknown).closedAt)
        assertEquals(0, recorded(numbered) + recorded(unknown))
        val closedAlert = alertsOf(sid, "order_closed_unconfirmed").single() // 접수불명 주문만 알린다
        assertEquals(AlertSeverity.warning, closedAlert.severity)
        assertTrue(closedAlert.message.contains("#${unknown.id}"), closedAlert.message)
        assertTrue(alertsOf(sid, "order_unconfirmed").isEmpty())
        val reads = balanceReads
        assertEquals(0, reconciler.reconcileOpenOrders(uid).getValue("checked"))
        assertEquals(reads, balanceReads)
        assertEquals(0, broker.placed)
    }

    @Test
    fun priorDayBalanceThatCannotConfirmDoesNotClose() {
        // 잔고 조회 실패
        run {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 2, OrderStatus.pending, null, dayAgo())
            onBalance = { throw BrokerError("일시 장애") }
            reconciler.reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt)
            onBalance = { }
        }
        // 기록 순보유 음수(보류) — 기존대로 알림만
        run {
            val (uid, sid) = setup()
            val sell = order(uid, sid, "005930", 3, OrderStatus.filled, "SOLD", dayAgo(), side = "sell")
            executions.save(Execution(sell.id!!, sid, BigDecimal("3"), BigDecimal("1000")))
            val o = order(uid, sid, "005930", 5, OrderStatus.pending, null, dayAgo())
            balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2")))
            reconciler.reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt)
            assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty())
        }
        // 모의 매도: 잔고가 장부 순보유와 다르면 밝힐 수 없다(같으면 R3 종결 — priorDayUnknownPaperSellIsClosedWhenBalanceEqualsLedger)
        run {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 2, OrderStatus.pending, null, dayAgo(), side = "sell")
            balance = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "2"))) // 잔고 2 ≠ 장부 0
            reconciler.reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt)
            assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty())
        }
        // 토스 사용자
        run {
            val (uid, sid) = setup()
            users.saveAndFlush(users.findById(uid).orElseThrow().also { it.broker = "toss" })
            val o = order(uid, sid, "005930", 2, OrderStatus.pending, null, dayAgo())
            balance = Balance()
            reconciler.reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt)
        }
        // 목록에 같은 조건의 주문이 둘 — 접수됐을 수 있어 잔고만으로 종결하지 않는다
        run {
            val (uid, sid) = setup()
            val created = dayAgo()
            val o = order(uid, sid, "005930", 2, OrderStatus.pending, null, created)
            broker.orderList = { listOf(row("X1", "005930", "buy", 2, 2, created.plusSeconds(1)), row("X2", "005930", "buy", 2, 0, created.plusSeconds(2))) }
            balance = Balance()
            reconciler.reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt)
            assertNull(reload(o).kisOrderId)
            assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty())
            broker.orderList = { null }
        }
        assertEquals(0, broker.placed)
    }

    // ── 정정·취소 행 · 목록 근거 종결 · 모의 매도 잔고 근거 · 종료 상태 근거 · 재확인 주기 ──

    /** 테스트가 옮기는 시계 — 재확인 주기를 Thread.sleep 없이 넘긴다. */
    private class MovableClock(var now: Instant = Instant.now()) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }

    /** 스프링 빈과 같은 의존성으로 시계·KIS 환경만 바꾼 정합기. 재확인 맵도 따로라 공유 빈과 섞이지 않는다. */
    private fun reconcilerWith(clock: Clock = Clock.systemUTC(), kisEnv: String = "vts") = Reconciler(
        orders, users, brokers, ctx.getBean(Fills::class.java), ctx.getBean(AlertPublisher::class.java), redis,
        ctx.getBean(NamedParameterJdbcTemplate::class.java), ctx.getBean(TransactionTemplate::class.java),
        QuantfolioProperties(kis = QuantfolioProperties.Kis(env = kisEnv, prodApproved = kisEnv == "prod")), clock, // 정합기는 isPaperTrading 만 본다 — 증권사 호출은 가짜
    )

    private fun tossUser(): Pair<Int, Int> = setup().also { (uid, _) -> users.saveAndFlush(users.findById(uid).orElseThrow().also { it.broker = "toss" }) }

    private fun hold(vararg symbolQty: Pair<String, Int>) =
        Balance(symbolQty.map { (s, q) -> mapOf("pdno" to s, "hldg_qty" to q.toString(), "pchs_avg_pric" to "1000") })

    @Test
    fun modifyOrCancelRowsAreNeitherAttachCandidatesNorTheOrderRow() {
        // 접수불명: 창 안의 같은 조건 행이 정정 행뿐이면 회수하지 않는다
        val (uid, sid) = setup()
        val created = todayOrderTime()
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
        broker.orderList = { listOf(row("MOD1", "005930", "buy", 3, 0, created.plusSeconds(1), orig = "ORIG")) }
        reconciler.reconcileOpenOrders(uid)
        assertNull(reload(o).kisOrderId)
        assertTrue(alertsOf(sid, "order_number_recovered").isEmpty())
        assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty())

        // 주문번호 있음: 같은 번호라도 원주문번호가 붙은 행은 이 주문의 행이 아니다(실전 — 잔고 근거 없음)
        val (uid2, _) = setup()
        val numbered = order(uid2, sid, "005930", 3, OrderStatus.submitted, "SAME", dayAgo())
        broker.orderList = { listOf(row("SAME", "005930", "buy", 3, 0, numbered.createdAt, orig = "OTHER")) }
        reconcilerWith(kisEnv = "prod").reconcileOpenOrders(uid2)
        assertNull(reload(numbered).closedAt)
        assertEquals(OrderStatus.submitted, reload(numbered).status)
        assertEquals(0, broker.placed)
    }

    @Test
    fun fillsOnModifiedOrderAreEscalatedNotRecordedNorClosed() {
        val (uid, sid) = setup()
        val created = dayAgo()
        val o = order(uid, sid, "005930", 5, OrderStatus.submitted, "ORIG", created)
        broker.execution = { Fill(0, null, false) }
        broker.orderList = { listOf(row("ORIG", "005930", "buy", 5, 2, created, "1000"), row("MOD1", "005930", "buy", 3, 3, created.plusSeconds(60), "1010", orig = "ORIG")) }

        val stats = reconciler.reconcileOpenOrders(uid)

        assertEquals(1, stats.getValue("errors"))
        assertEquals(0, recorded(o))
        assertNull(reload(o).closedAt)
        assertEquals(OrderStatus.submitted, reload(o).status)
        val alert = alertsOf(sid, "order_modified_externally").single()
        assertEquals(AlertSeverity.critical, alert.severity)
        listOf("#${o.id}", "005930", "buy", "MOD1", "3주", "자동 반영하지 않았").forEach { assertTrue(alert.message.contains(it), alert.message) }
        reconciler.reconcileOpenOrders(uid)
        assertEquals(1, alertsOf(sid, "order_modified_externally").size)
        assertEquals(0, broker.placed)
    }

    @Test
    fun zeroFillCancelRowLeavesOriginalRowDecisive() {
        val (uid, sid) = setup()
        val created = dayAgo()
        val o = order(uid, sid, "005930", 5, OrderStatus.submitted, "ORIG", created)
        broker.execution = { Fill(0, null, false) }
        broker.orderList = { listOf(row("ORIG", "005930", "buy", 5, 2, created, "1000"), row("CXL1", "005930", "buy", 3, 0, created.plusSeconds(60), orig = "ORIG")) }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(2, recorded(o))
        assertEquals(OrderStatus.partial, reload(o).status)
        assertNotNull(reload(o).closedAt)
        assertTrue(alertsOf(sid, "order_modified_externally").isEmpty())
    }

    @Test
    fun modifyChainIsFollowedAndCyclesTerminate() {
        val cases = linkedMapOf<String, (Instant) -> List<BrokerOrderRow>>(
            "정정의 정정" to { t -> listOf(row("ORIG", "005930", "buy", 5, 0, t), row("M1", "005930", "buy", 5, 0, t, orig = "ORIG"), row("M2", "005930", "buy", 5, 2, t, orig = "M1")) },
            "순환" to { t ->
                listOf(row("ORIG", "005930", "buy", 5, 0, t), row("M1", "005930", "buy", 5, 0, t, orig = "ORIG"), row("M2", "005930", "buy", 5, 2, t, orig = "M1"), row("M1", "005930", "buy", 5, 0, t, orig = "M2"))
            },
        )
        for ((name, rows) in cases) {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 5, OrderStatus.submitted, "ORIG", dayAgo())
            broker.execution = { Fill(0, null, false) }
            broker.orderList = { rows(o.createdAt) }
            assertTimeoutPreemptively(Duration.ofSeconds(60), { reconciler.reconcileOpenOrders(uid) }, name)
            assertNull(reload(o).closedAt, name)
            assertEquals(0, recorded(o), name)
            assertTrue(alertsOf(sid, "order_modified_externally").single().message.contains("M2"), name)
        }
        // 이 주문에 닿지 않는 순환은 후손이 아니다 — 원주문 행(체결 0)으로 종결
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 5, OrderStatus.submitted, "ORIG", dayAgo())
        broker.orderList = { _ -> listOf(row("ORIG", "005930", "buy", 5, 0, null), row("X1", "005930", "buy", 5, 1, null, orig = "X2"), row("X2", "005930", "buy", 5, 0, null, orig = "X1")) }
        assertTimeoutPreemptively(Duration.ofSeconds(60)) { reconciler.reconcileOpenOrders(uid) }
        assertEquals(OrderStatus.cancelled, reload(o).status)
        assertTrue(alertsOf(sid, "order_modified_externally").isEmpty())
    }

    @Test
    fun priorDayUnknownOrderAbsentFromWitnessedListIsClosed() {
        val (uid, sid) = setup()
        val created = dayAgo()
        order(uid, sid, "000660", 1, OrderStatus.filled, "W1", created) // 증인: 같은 계좌·주문일의 다른 주문 번호가 목록에 있다
        order(uid, sid, "005930", 3, OrderStatus.filled, "W2", created) // 같은 종목·방향이지만 주인 있는 행
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
        val half = order(uid, sid, "035420", 4, OrderStatus.partial, null, created) // 잔고로 일부 회수된 접수불명
        executions.save(Execution(half.id!!, sid, BigDecimal.ONE, BigDecimal("1000")))
        broker.orderList = {
            listOf(row("W1", "000660", "buy", 1, 1, created), row("W2", "005930", "buy", 3, 3, created.plusSeconds(1)), row("W2C", "005930", "buy", 3, 0, created, orig = "W2"))
        }

        reconcilerWith(kisEnv = "prod").reconcileOpenOrders(uid)

        assertEquals(OrderStatus.cancelled, reload(o).status)
        assertNotNull(reload(o).closedAt)
        assertEquals(0, recorded(o))
        assertEquals(OrderStatus.partial, reload(half).status)
        assertNotNull(reload(half).closedAt)
        assertEquals(1, recorded(half))
        val closed = alertsOf(sid, "order_closed_unconfirmed")
        assertEquals(2, closed.size) // dedup 없는 warning — 주문마다 하나
        closed.forEach {
            assertEquals(AlertSeverity.warning, it.severity)
            assertTrue(it.message.contains("증권사 주문 목록에 이 주문이 없습니다"), it.message)
        }
        assertTrue(closed.any { it.message.contains("#${o.id}") } && closed.any { it.message.contains("#${half.id}") })
        assertTrue(alertsOf(sid, "order_unconfirmed").isEmpty())
        assertEquals(0, broker.placed)
    }

    @Test
    fun priorDayUnknownOrderIsNotClosedWithoutWitnessedCompleteList() {
        val cases = listOf<Triple<String, () -> Instant, (Instant) -> List<BrokerOrderRow>?>>(
            Triple("빈 목록", { dayAgo() }, { _ -> emptyList() }),
            Triple("증인 없음", { dayAgo() }, { t -> listOf(row("ELSE", "000660", "sell", 1, 0, t)) }),
            Triple("같은 종목·방향의 주인 없는 행(수량·시각 다름)", { dayAgo() }, { t -> listOf(row("W1", "000660", "buy", 1, 1, t), row("HTS", "005930", "buy", 7, 0, t.plusSeconds(3600))) }),
            Triple("주인 없는 원주문의 정정 행", { dayAgo() }, { t -> listOf(row("W1", "000660", "buy", 1, 1, t), row("M9", "005930", "buy", 3, 0, t, orig = "NOBODY")) }),
            Triple("목록 조회 실패", { dayAgo() }, { _ -> throw BrokerError("목록 미완결") }),
            Triple("목록 미지원", { dayAgo() }, { _ -> null }),
            Triple("오늘 주문", { todayOrderTime() }, { t -> listOf(row("W1", "000660", "buy", 1, 1, t)) }),
        )
        for ((name, at, rows) in cases) {
            val (uid, sid) = setup()
            val created = at()
            order(uid, sid, "000660", 1, OrderStatus.filled, "W1", created)
            val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
            broker.orderList = { rows(created) }
            reconcilerWith(kisEnv = "prod").reconcileOpenOrders(uid)
            assertNull(reload(o).closedAt, name)
            assertEquals(OrderStatus.pending, reload(o).status, name)
            assertTrue(alertsOf(sid, "order_unconfirmed").isNotEmpty(), name)
            assertTrue(alertsOf(sid, "order_closed_unconfirmed").isEmpty(), name)
        }
        assertEquals(0, broker.placed)
    }

    @Test
    fun listEvidenceDoesNotCloseInPassWhereBalanceAssignedFills() {
        val (uid, sid) = setup()
        val created = dayAgo()
        order(uid, sid, "000660", 1, OrderStatus.filled, "W1", created)
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, created)
        broker.orderList = { listOf(row("W1", "000660", "buy", 1, 1, created)) }
        balance = hold("005930" to 2)

        reconciler.reconcileOpenOrders(uid)

        assertEquals(2, recorded(o))
        assertEquals(OrderStatus.partial, reload(o).status)
        assertNull(reload(o).closedAt)
    }

    @Test
    fun priorDayPaperSellIsClosedWhenBalanceEqualsLedgerWithoutRecordingFills() {
        val (uid, sid) = setup()
        val buy = order(uid, sid, "005930", 5, OrderStatus.filled, "BUY", dayAgo())
        executions.save(Execution(buy.id!!, sid, BigDecimal("5"), BigDecimal("1000")))
        val zero = order(uid, sid, "005930", 3, OrderStatus.submitted, "SELL0", dayAgo(), side = "sell")
        val buy2 = order(uid, sid, "000660", 4, OrderStatus.filled, "BUY2", dayAgo())
        executions.save(Execution(buy2.id!!, sid, BigDecimal("4"), BigDecimal("1000")))
        val half = order(uid, sid, "000660", 3, OrderStatus.partial, "SELL1", dayAgo(), side = "sell")
        executions.save(Execution(half.id!!, sid, BigDecimal.ONE, BigDecimal("1000")))
        broker.execution = { Fill(0, null, false) } // 체결조회 0, 목록 미지원
        balance = hold("005930" to 5, "000660" to 3)

        reconciler.reconcileOpenOrders(uid)

        assertEquals(OrderStatus.cancelled, reload(zero).status)
        assertNotNull(reload(zero).closedAt)
        assertEquals(OrderStatus.partial, reload(half).status)
        assertNotNull(reload(half).closedAt)
        assertEquals(1, executions.findAll().count { it.orderId == zero.id || it.orderId == half.id }) // 잔고로 매도 체결을 지어내지 않는다
        assertEquals(0, recorded(zero))
        assertTrue(alerts.findAll().none { it.strategyId == sid })
    }

    @Test
    fun priorDayUnknownPaperSellIsClosedWhenBalanceEqualsLedger() {
        val (uid, sid) = setup()
        val buy = order(uid, sid, "005930", 5, OrderStatus.filled, "BUY", dayAgo())
        executions.save(Execution(buy.id!!, sid, BigDecimal("5"), BigDecimal("1000")))
        val o = order(uid, sid, "005930", 2, OrderStatus.pending, null, dayAgo(), side = "sell")
        balance = hold("005930" to 5)

        reconciler.reconcileOpenOrders(uid)

        assertEquals(OrderStatus.cancelled, reload(o).status)
        assertNotNull(reload(o).closedAt)
        assertEquals(0, recorded(o))
        val alert = alertsOf(sid, "order_closed_unconfirmed").single()
        assertTrue(alert.message.contains("#${o.id}") && alert.message.contains("잔고 보유수량이 장부 순보유와 같습니다"), alert.message)
        assertTrue(alertsOf(sid, "order_unconfirmed").isEmpty())
    }

    @Test
    fun paperSellBalanceEvidenceIsNotUsedUnlessCertain() {
        fun sellCase(name: String, sellAt: Instant, held: Int, configure: (Int) -> Unit = { }, reconcile: (Int) -> Unit = { reconciler.reconcileOpenOrders(it) }): Int {
            val (uid, sid) = setup()
            configure(uid)
            val buy = order(uid, sid, "005930", 5, OrderStatus.filled, "BUY", dayAgo())
            executions.save(Execution(buy.id!!, sid, BigDecimal("5"), BigDecimal("1000")))
            val sell = order(uid, sid, "005930", 3, OrderStatus.submitted, "SELL", sellAt, side = "sell")
            balance = hold("005930" to held)
            var reads = 0
            val prior = onBalance
            onBalance = { reads++; prior() }
            reconcile(uid)
            onBalance = { }
            assertNull(reload(sell).closedAt, name)
            assertEquals(OrderStatus.submitted, reload(sell).status, name)
            assertEquals(0, recorded(sell), name)
            assertEquals(5, recorded(buy), name)
            return reads
        }
        sellCase("잔고 < 장부(미기록 매도 체결 가능)", dayAgo(), 3)
        sellCase("잔고 > 장부", dayAgo(), 7)
        onBalance = { throw BrokerError("일시 장애") }
        sellCase("잔고 조회 실패", dayAgo(), 5)
        assertEquals(0, sellCase("실전(비모의)", dayAgo(), 5, reconcile = { reconcilerWith(kisEnv = "prod").reconcileOpenOrders(it) }))
        assertEquals(0, sellCase("토스", dayAgo(), 5, configure = { uid -> users.saveAndFlush(users.findById(uid).orElseThrow().also { it.broker = "toss" }) }))
        assertEquals(0, sellCase("오늘 매도", Instant.now(), 5))
        assertEquals(0, broker.placed)
    }

    @Test
    fun priorDayTerminalStatusClosesWhenFullyRecorded() {
        val (uid, sid) = tossUser()
        val zero = order(uid, sid, "005930", 3, OrderStatus.submitted, "T0", dayAgo())
        val more = order(uid, sid, "000660", 3, OrderStatus.submitted, "T2", dayAgo())
        broker.execution = { id -> if (id == "T0") Fill(0, null, false, terminal = true) else Fill(2, BigDecimal("1100"), false, terminal = true) }

        reconciler.reconcileOpenOrders(uid)

        assertEquals(OrderStatus.cancelled, reload(zero).status)
        assertNotNull(reload(zero).closedAt)
        assertEquals(2, recorded(more)) // 누적 > 기록 → 증분 반영 뒤 종결
        assertEquals(OrderStatus.partial, reload(more).status)
        assertNotNull(reload(more).closedAt)
    }

    @Test
    fun terminalStatusDoesNotCloseTodayOrWhenNotTerminalOrBelowRecord() {
        val (uid, sid) = tossUser()
        val today = order(uid, sid, "005930", 3, OrderStatus.submitted, "TODAY")
        val notTerminal = order(uid, sid, "000660", 3, OrderStatus.submitted, "OPEN", dayAgo())
        val below = order(uid, sid, "035420", 3, OrderStatus.partial, "BELOW", dayAgo())
        executions.save(Execution(below.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        broker.execution = { id ->
            when (id) {
                "TODAY" -> Fill(0, null, false, terminal = true)
                "OPEN" -> Fill(0, null, false)
                else -> Fill(1, BigDecimal("1000"), false, terminal = true)
            }
        }

        val stats = reconciler.reconcileOpenOrders(uid)

        listOf(today, notTerminal, below).forEach { assertNull(reload(it).closedAt, it.kisOrderId) }
        assertEquals(2, recorded(below)) // 기록을 줄이지 않는다
        assertEquals(1, stats.getValue("errors"))
        val alert = alertsOf(sid, "order_fill_mismatch").single()
        assertTrue(alert.message.contains("#${below.id}"), alert.message)
    }

    @Test
    fun priorDayOrderWithoutProgressIsRecheckedHourlyThenEscalated() {
        val clock = MovableClock()
        val r = reconcilerWith(clock)
        val (uid, sid) = setup()
        val buy = order(uid, sid, "005930", 5, OrderStatus.filled, "BUY", dayAgo())
        executions.save(Execution(buy.id!!, sid, BigDecimal("5"), BigDecimal("1000")))
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "STUCK", dayAgo(), side = "sell")
        var lookups = 0
        broker.execution = { lookups++; Fill(0, null, false) }
        broker.orderList = { throw BrokerError("목록 미완결") }
        balance = hold("005930" to 3) // 잔고 3 < 장부 5
        var balanceReads = 0
        onBalance = { balanceReads++ }

        assertEquals(1, r.reconcileOpenOrders(uid).getValue("checked"))
        val calls = Triple(lookups, broker.orderListCalls, balanceReads)
        assertEquals(Triple(1, 1, 1), calls)
        assertTrue(alertsOf(sid, "order_unresolved").isEmpty()) // 첫 무진전은 미루기만

        clock.now = clock.now.plusSeconds(3599)
        assertEquals(0, r.reconcileOpenOrders(uid).getValue("checked"))
        assertEquals(calls, Triple(lookups, broker.orderListCalls, balanceReads)) // 증권사 조회 없음
        assertTrue(alertsOf(sid, "order_unresolved").isEmpty())

        clock.now = clock.now.plusSeconds(2)
        assertEquals(1, r.reconcileOpenOrders(uid).getValue("checked"))
        assertEquals(2, lookups)
        val alert = alertsOf(sid, "order_unresolved").single()
        assertEquals(AlertSeverity.critical, alert.severity)
        listOf("#${o.id}", "005930", "sell", "주문 목록 조회가 실패했습니다", "잔고 3주가 장부 5주보다 적습니다 — 기록되지 않은 매도 체결일 수 있습니다", "수동 종결")
            .forEach { assertTrue(alert.message.contains(it), alert.message) }

        clock.now = clock.now.plusSeconds(3601)
        r.reconcileOpenOrders(uid)
        assertEquals(3, lookups)
        assertEquals(1, alertsOf(sid, "order_unresolved").size) // 24시간 dedup
        assertNull(reload(o).closedAt)
        assertEquals(0, broker.placed)
    }

    @Test
    fun specificCriticalAlertIsNotDuplicatedByUnresolved() {
        val clock = MovableClock()
        val r = reconcilerWith(clock)
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 5, OrderStatus.partial, "LESS", dayAgo(), side = "sell")
        executions.save(Execution(o.id!!, sid, BigDecimal("2"), BigDecimal("1000")))
        broker.orderList = { listOf(row("LESS", "005930", "sell", 5, 1, null)) }
        repeat(2) { r.reconcileOpenOrders(uid); clock.now = clock.now.plusSeconds(3601) }
        assertEquals(1, alertsOf(sid, "order_fill_mismatch").size)
        assertTrue(alertsOf(sid, "order_unresolved").isEmpty())
        assertEquals(2, broker.orderListCalls)
    }

    @Test
    fun todayOrdersAndBusyLocksDoNotBackOff() {
        val r = reconcilerWith(MovableClock())
        val (uid, sid) = setup()
        val today = order(uid, sid, "005930", 3, OrderStatus.submitted, "TODAY-R5", todayOrderTime(), side = "sell")
        val past = order(uid, sid, "000660", 3, OrderStatus.submitted, "PAST-R5", dayAgo(), side = "sell")
        val lookups = mutableListOf<String>()
        broker.execution = { id -> lookups += id; Fill(0, null, false) }
        balance = hold("000660" to 9) // past: 잔고 9 ≠ 장부 0 — 근거 없음
        redis.opsForValue().set(RedisKeys.reconcileLock(past.id!!), "1")
        r.reconcileOpenOrders(uid) // past: 락 바쁨 — 미루지 않는다
        redis.delete(RedisKeys.reconcileLock(past.id!!))
        r.reconcileOpenOrders(uid) // past: 바로 다시 확인, 무진전 → 미룸
        r.reconcileOpenOrders(uid) // past: 건너뜀, today: 매 pass
        assertEquals(listOf("TODAY-R5", "PAST-R5", "TODAY-R5", "PAST-R5", "TODAY-R5"), lookups)
        assertTrue(alertsOf(sid, "order_unresolved").isEmpty()) // 락 바쁨 뒤 첫 확인은 재확인이 아니다
        assertNull(reload(today).closedAt)
    }

    // ── 사람이 확인한 최종 체결로 수동 정리(resolve_order) ──

    /** 주문 행·이 주문의 executions·이 사용자의 포지션 — 거부 시 하나도 바뀌지 않아야 한다. */
    private fun ledger(o: Order) = Triple(
        reload(o).let { listOf(it.status, it.closedAt, it.kisOrderId, it.qty) },
        executions.findAll().filter { it.orderId == o.id }.map { it.filledQty to it.filledPrice },
        positions.findAll().filter { it.userId == o.userId }.map { Triple(it.symbol, it.qty, it.avgPrice) },
    )

    @Test
    fun manualResolveWithZeroFillCancelsAndIsIdempotent() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "MANUAL-0", dayAgo())
        assertTrue(reconciler.resolveManually(o.id!!, "005930", 0, null))
        val closed = reload(o)
        assertEquals(OrderStatus.cancelled, closed.status)
        assertNotNull(closed.closedAt)
        assertEquals(0, recorded(o))
        val done = alertsOf(sid, "order_resolved_manually").single()
        assertEquals(AlertSeverity.warning, done.severity)
        listOf("#${o.id}", "005930", "buy", "최종 누적 0주").forEach { assertTrue(done.message.contains(it), done.message) }

        val before = ledger(o)
        assertEquals(false, reconciler.resolveManually(o.id!!, "005930", 0, null)) // 같은 요청 두 번째 — 이미 종결
        assertEquals(before, ledger(o))
        val rejected = alertsOf(sid, "order_resolve_rejected").single()
        assertEquals(AlertSeverity.warning, rejected.severity)
        listOf("#${o.id}", "이미 종결").forEach { assertTrue(rejected.message.contains(it), rejected.message) }
        assertEquals(1, alertsOf(sid, "order_resolved_manually").size)
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveRecordsIncrementWithSameIncrementalPriceThenClosesPartial() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.partial, "MANUAL-P", dayAgo())
        executions.save(Execution(o.id!!, sid, BigDecimal.ONE, BigDecimal("1000")))
        positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal.ONE, BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))

        assertTrue(reconciler.resolveManually(o.id!!, "005930", 2, BigDecimal("1100")))

        // 증분 단가 = (1100 × 2 − 1000) ÷ 1 — 자동 정합과 같은 산식
        assertEquals(listOf(BigDecimal("1000.0000"), BigDecimal("1200.0000")), executions.findAll().filter { it.orderId == o.id }.sortedBy { it.id }.map { it.filledPrice })
        assertEquals(2, recorded(o))
        val closed = reload(o)
        assertEquals(OrderStatus.partial, closed.status)
        assertNotNull(closed.closedAt)
        val pos = acctPosition(uid, sid)
        assertEquals(0, BigDecimal("2").compareTo(pos.qty))
        assertEquals(0, BigDecimal("1100").compareTo(pos.avgPrice))
        val done = alertsOf(sid, "order_resolved_manually").single()
        listOf("#${o.id}", "1주", "1200", "최종 누적 2주").forEach { assertTrue(done.message.contains(it), done.message) }
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveOfFullFillMarksFilled() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 2, OrderStatus.submitted, "MANUAL-F", dayAgo())
        assertTrue(reconciler.resolveManually(o.id!!, "005930", 2, BigDecimal("1050")))
        assertEquals(OrderStatus.filled, reload(o).status)
        assertNull(reload(o).closedAt) // filled 는 상태만으로 정합 대상에서 빠진다 — 종결 update 를 하지 않는다
        assertEquals(BigDecimal("1050.0000"), executions.findAll().single { it.orderId == o.id }.filledPrice)
        assertEquals(0, BigDecimal("2").compareTo(acctPosition(uid, sid).qty))
        assertTrue(alertsOf(sid, "order_resolved_manually").single().message.contains("전량"))
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveClosesUnconfirmedOrderWithoutOrderNumber() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.pending, null, dayAgo())
        assertTrue(reconciler.resolveManually(o.id!!, "005930", 0, null))
        assertEquals(OrderStatus.cancelled, reload(o).status)
        assertNotNull(reload(o).closedAt)
        assertNull(reload(o).kisOrderId)

        val p = order(uid, sid, "000660", 3, OrderStatus.pending, null, dayAgo())
        assertTrue(reconciler.resolveManually(p.id!!, "000660", 1, BigDecimal("900")))
        assertEquals(OrderStatus.partial, reload(p).status)
        assertNotNull(reload(p).closedAt)
        assertEquals(1, recorded(p))
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveRejectsWithoutChangingAnything() {
        class Case(val name: String, val reason: String, val filled: Int, val avg: String?, val make: (Int, Int) -> Order)
        val cases = listOf(
            Case("오늘 주문", "주문일", 0, null) { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-TODAY", todayOrderTime()) },
            Case("filled", "filled", 0, null) { u, s -> order(u, s, "005930", 3, OrderStatus.filled, "R-FILLED", dayAgo()) },
            Case("rejected", "rejected", 0, null) { u, s -> order(u, s, "005930", 3, OrderStatus.rejected, "R-REJ", dayAgo()) },
            Case("기록보다 적음", "기록된 체결 2주보다 적습니다", 1, "1000") { u, s ->
                order(u, s, "005930", 3, OrderStatus.partial, "R-LESS", dayAgo()).also { executions.save(Execution(it.id!!, s, BigDecimal("2"), BigDecimal("1000"))) }
            },
            Case("주문수량 초과", "주문수량 3주", 4, "1000") { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-OVER", dayAgo()) },
            Case("평균가 없음", "avg_price", 1, null) { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-NOAVG", dayAgo()) },
            Case("평균가 0", "avg_price", 1, "0") { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-ZERO", dayAgo()) },
            Case("평균가 음수", "avg_price", 1, "-5") { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-NEG", dayAgo()) },
            Case("증분 단가 0 이하", "증분 단가", 2, "500") { u, s ->
                order(u, s, "005930", 3, OrderStatus.partial, "R-PRICE", dayAgo()).also { executions.save(Execution(it.id!!, s, BigDecimal.ONE, BigDecimal("1000"))) }
            },
            Case("계좌 키 없음", "계좌 키", 1, "1000") { u, s -> order(u, s, "005930", 3, OrderStatus.submitted, "R-NOACCT", dayAgo(), accountKey = null) },
            Case("이미 전량 기록", "전량", 2, "1000") { u, s ->
                order(u, s, "005930", 2, OrderStatus.partial, "R-ALL", dayAgo()).also { executions.save(Execution(it.id!!, s, BigDecimal("2"), BigDecimal("1000"))) }
            },
        )
        for (c in cases) {
            val (uid, sid) = setup()
            val o = c.make(uid, sid)
            val before = ledger(o)
            assertEquals(false, reconciler.resolveManually(o.id!!, "005930", c.filled, c.avg?.let(::BigDecimal)), c.name)
            assertEquals(before, ledger(o), c.name)
            val alert = alertsOf(sid, "order_resolve_rejected").single()
            assertEquals(AlertSeverity.warning, alert.severity, c.name)
            listOf("#${o.id}", c.reason).forEach { assertTrue(alert.message.contains(it), "${c.name}: ${alert.message}") }
            assertTrue(alertsOf(sid, "order_resolved_manually").isEmpty(), c.name)
        }
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveRejectsSymbolMismatchWithoutChangingAnything() {
        // 입력이 다른 값이면 그 밖에는 성공했을 요청이다(증분 1주 @ 1100) — 종목 대조만이 막는다. 정규화(0 채움·대소문자) 없이 정확 일치.
        for (wrong in listOf("000660", "5930", "05930", "0059300", "005930A")) {
            val (uid, sid) = setup()
            val o = order(uid, sid, "005930", 3, OrderStatus.partial, "SYM-$wrong", dayAgo())
            executions.save(Execution(o.id!!, sid, BigDecimal.ONE, BigDecimal("1000")))
            positions.save(com.quantfolio.domain.trading.Position(uid, sid, "005930", BigDecimal.ONE, BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
            val before = ledger(o)
            assertEquals(false, reconciler.resolveManually(o.id!!, wrong, 2, BigDecimal("1100")), wrong)
            assertEquals(before, ledger(o), wrong)
            val alert = alertsOf(sid, "order_resolve_rejected").single()
            assertEquals(AlertSeverity.warning, alert.severity, wrong)
            listOf("#${o.id}", "입력한 종목: $wrong", "주문의 종목: 005930", "아무것도 바꾸지 않았습니다").forEach { assertTrue(alert.message.contains(it), "$wrong: ${alert.message}") }
            assertTrue(alertsOf(sid, "order_resolved_manually").isEmpty(), wrong)
        }
        // 소문자·앞자리 0 이 사라진 입력은 같은 종목으로 보지 않는다 — 알파뉴메릭 종목코드 대소문자도 그대로 비교
        val (uid, sid) = setup()
        val alpha = order(uid, sid, "00593K", 3, OrderStatus.submitted, "SYM-K", dayAgo())
        assertEquals(false, reconciler.resolveManually(alpha.id!!, "00593k", 0, null))
        assertEquals(OrderStatus.submitted, reload(alpha).status)
        assertNull(reload(alpha).closedAt)
        assertEquals(0, broker.placed)
    }

    @Test
    fun manualResolveReportsSymbolMismatchBeforeOtherRejectionReasons() {
        val (uid, sid) = setup()
        // 이미 종결 + 종목 불일치 — 종목이 다르면 애초에 다른 주문을 가리킨 것이라 종결 사유가 아니라 종목 사유가 나온다
        val closed = order(uid, sid, "005930", 3, OrderStatus.submitted, "SYM-CLOSED", dayAgo())
        assertTrue(reconciler.resolveManually(closed.id!!, "005930", 0, null))
        val before = ledger(closed)
        assertEquals(false, reconciler.resolveManually(closed.id!!, "000660", 0, null))
        assertEquals(before, ledger(closed))
        val alert = alertsOf(sid, "order_resolve_rejected").single()
        listOf("입력한 종목: 000660", "주문의 종목: 005930").forEach { assertTrue(alert.message.contains(it), alert.message) }
        assertFalse(alert.message.contains("이미 종결"), alert.message)

        // 오늘 주문(주문일 미경과) + 수량 초과 + 종목 불일치
        val (uid2, sid2) = setup()
        val today = order(uid2, sid2, "005930", 3, OrderStatus.submitted, "SYM-TODAY", todayOrderTime())
        assertEquals(false, reconciler.resolveManually(today.id!!, "000660", 9, null))
        val alert2 = alertsOf(sid2, "order_resolve_rejected").single()
        assertTrue(alert2.message.contains("입력한 종목: 000660"), alert2.message)
        assertFalse(alert2.message.contains("주문일"), alert2.message)
        assertFalse(alert2.message.contains("주문수량"), alert2.message)
    }

    @Test
    fun manualResolveTrimsSymbolInput() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "SYM-TRIM", dayAgo())
        assertTrue(reconciler.resolveManually(o.id!!, " 005930\t", 0, null))
        assertEquals(OrderStatus.cancelled, reload(o).status)
    }

    @Test
    fun manualResolveRejectsWhenLockIsBusyAndIgnoresMissingOrder() {
        val (uid, sid) = setup()
        val o = order(uid, sid, "005930", 3, OrderStatus.submitted, "R-BUSY", dayAgo())
        val before = ledger(o)
        redis.opsForValue().set(RedisKeys.reconcileLock(o.id!!), "1")
        try {
            assertEquals(false, reconciler.resolveManually(o.id!!, "005930", 0, null))
        } finally {
            redis.delete(RedisKeys.reconcileLock(o.id!!))
        }
        assertEquals(before, ledger(o))
        assertTrue(alertsOf(sid, "order_resolve_rejected").single().message.contains("잠시 뒤"))

        val alertCount = alerts.count()
        assertEquals(false, reconciler.resolveManually(Int.MAX_VALUE, "005930", 0, null)) // 알릴 사용자를 모른다 — 로그만
        assertEquals(alertCount, alerts.count())
        assertEquals(0, broker.placed)
    }
}
