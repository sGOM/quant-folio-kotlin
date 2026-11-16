package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerAccountKey
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.Fill
import com.quantfolio.broker.OrderNotSent
import com.quantfolio.broker.OrderResult
import com.quantfolio.broker.Quote
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit.SECONDS
import org.springframework.jdbc.core.JdbcTemplate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val TEST_KIS_ACCOUNT = "50012345-01"
internal val TEST_KIS_ACCOUNT_KEY = BrokerAccountKey.kis(TEST_KIS_ACCOUNT, paper = true)!!

/** 주문 실행 안전 불변식(원본 `engine/executor.py`·`fills.py`) — 멱등 3중 방어, 접수불명 시 재주문 금지. */
@SpringBootTest
@Import(ContainersConfig::class)
class OrderExecutorTest(
    @Autowired private val executor: OrderExecutor,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val tx: org.springframework.transaction.support.TransactionTemplate,
    @Autowired private val fills: Fills,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @MockitoSpyBean lateinit var props: QuantfolioProperties

    /** 가짜 증권사 — 주문·체결 응답을 시나리오별로 바꾼다. */
    class FakeBroker(
        var place: () -> OrderResult = { OrderResult("ODNO1", "ORG1") },
        var execution: (String) -> Fill = { Fill(0, null, false) },
        override val orderAccountKey: String? = TEST_KIS_ACCOUNT_KEY,
    ) : BrokerClient {
        var placed = 0
        /** 주문일 목록(`dailyOrders`) 응답 — 기본은 미지원(null). */
        var orderList: (java.time.LocalDate) -> List<com.quantfolio.broker.BrokerOrderRow>? = { null }
        var orderListCalls = 0
        override fun dailyOrders(day: java.time.LocalDate): List<com.quantfolio.broker.BrokerOrderRow>? { orderListCalls++; return orderList(day) }
        override fun verifyConnection() = true
        override fun getQuote(symbol: String) = Quote(symbol, BigDecimal.ONE)
        override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult { placed++; return place() }
        override fun getOrderExecution(orderId: String, symbol: String?) = execution(orderId)
        override fun getBalance() = Balance()
    }

    private fun setup(authorized: Boolean = true): Pair<Int, Int> {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x"))
        val s = strategies.save(Strategy(u.id!!, "s", config = mapOf("type" to "rsi")))
        if (authorized) willReturn(u.id).given(props).tradingOwnerUserId
        return u.id!! to s.id!!
    }

    private fun key() = "s0:TEST:buy:" + UUID.randomUUID()

    /** 다른 경로(정합 — 주기 또는 체결통보가 트리거)가 이 주문의 증분을 기록한 것과 같은 DB 반영. */
    private fun recordElsewhere(orderId: Int, qty: Int, price: String) {
        val o = orders.findById(orderId).orElseThrow()
        val already = fills.recordedQty(orderId)
        assertNotNull(tx.execute { fills.record(o, qty, BigDecimal(price), already + qty >= o.qty.toInt(), already) })
    }

    private fun acctPosition(uid: Int, sid: Int) = positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(uid, TEST_KIS_ACCOUNT_KEY, sid, "005930")!!

    /** [held] 를 안 주면 예약 직전 장부를 읽고 판단한 주문으로 본다. */
    private fun run(broker: BrokerClient, uid: Int, sid: Int, side: String, qty: Int, key: String = key(), price: String = "70000", symbol: String = "005930", held: BigDecimal? = null) =
        executor.execute(broker, uid, sid, symbol, side, qty, BigDecimal(price), key, held ?: ledgerQty(broker, uid, sid, symbol), "테스트 사유")

    private fun ledgerQty(broker: BrokerClient, uid: Int, sid: Int, symbol: String) =
        broker.orderAccountKey?.let { positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(uid, it, sid, symbol)?.qty } ?: BigDecimal.ZERO

    @Test
    fun directOrderWithoutAuthorizationDoesNotReserveOrSend() {
        val (uid, sid) = setup(authorized = false)
        val broker = FakeBroker()
        val idempotencyKey = key()
        assertNull(run(broker, uid, sid, "buy", 1, key = idempotencyKey))
        assertEquals(0, broker.placed)
        assertEquals(false, orders.existsByIdempotencyKey(idempotencyKey))
    }

    @Test
    fun ownerCannotSubmitAnOrderForAnotherUsersStrategy() {
        val (owner, _) = setup()
        val (_, foreignStrategy) = setup(authorized = false)
        val broker = FakeBroker()
        val idempotencyKey = key()
        assertNull(run(broker, owner, foreignStrategy, "buy", 1, key = idempotencyKey))
        assertEquals(0, broker.placed)
        assertEquals(false, orders.existsByIdempotencyKey(idempotencyKey))
    }

    @Test
    fun filledOrderRecordsExecutionPositionAndAveragePrice() {
        val (uid, sid) = setup()
        val broker = FakeBroker(execution = { Fill(3, BigDecimal("70100"), true) })
        val o = run(broker, uid, sid, "buy", 3)!!
        assertEquals(OrderStatus.filled, o.status)
        assertEquals("ODNO1", o.kisOrderId)
        assertEquals("ORG1", o.kisOrderOrgNo)
        assertEquals(TEST_KIS_ACCOUNT_KEY, orders.findById(o.id!!).orElseThrow().brokerAccountKey)
        assertEquals("테스트 사유", o.reason)
        assertEquals(BigDecimal("70100.0000"), executions.findAll().single { it.orderId == o.id }.filledPrice)
        val p = positions.findAll().single { it.userId == uid && it.strategyId == sid }
        assertEquals(0, BigDecimal("3").compareTo(p.qty))
        assertEquals(0, BigDecimal("70100").compareTo(p.avgPrice))

        broker.execution = { Fill(1, BigDecimal("70500"), false) }
        val partial = run(broker, uid, sid, "buy", 2)!!
        assertEquals(OrderStatus.partial, partial.status)
        val p2 = positions.findById(p.id!!).get()
        assertEquals(0, BigDecimal("4").compareTo(p2.qty))
        assertEquals(0, BigDecimal("70200").compareTo(p2.avgPrice)) // (3×70100 + 1×70500)/4
    }

    @Test
    fun newAccountFillsNeverMutateLegacyPosition() {
        val (uid, sid) = setup()
        val legacy = positions.save(Position(uid, sid, "005930", BigDecimal.TEN, BigDecimal("900")))
        val broker = FakeBroker(execution = { Fill(2, BigDecimal("1000"), true) })
        run(broker, uid, sid, "buy", 2)!!
        broker.execution = { Fill(1, BigDecimal("1200"), true) }
        run(broker, uid, sid, "sell", 1)!!

        val preserved = positions.findById(legacy.id!!).orElseThrow()
        val current = positions.findAll().single { it.userId == uid && it.strategyId == sid && it.id != legacy.id }
        assertNull(preserved.brokerAccountKey)
        assertEquals(TEST_KIS_ACCOUNT_KEY, current.brokerAccountKey)
        assertEquals(BigDecimal("10.0000"), preserved.qty)
        assertEquals(BigDecimal("900.0000"), preserved.avgPrice)
        assertEquals(BigDecimal("1.0000"), current.qty)
        assertEquals(BigDecimal("1000.0000"), current.avgPrice)
    }

    @Test
    fun missingAccountCannotPlaceOrder() {
        val (uid, sid) = setup()
        val broker = FakeBroker(orderAccountKey = null)
        assertThrows<BrokerError> { run(broker, uid, sid, "buy", 1) }
        assertEquals(0, broker.placed)
        assertTrue(orders.findAll().none { it.userId == uid })
    }

    @Test
    fun duplicatesAreBlockedByLockAndDb() {
        val (uid, sid) = setup()
        val broker = FakeBroker(execution = { Fill(1, BigDecimal("1"), true) })
        val k = key()
        redis.opsForValue().set(RedisKeys.ORDER_LOCK_PREFIX + k, "1")
        assertNull(run(broker, uid, sid, "buy", 1, k)) // 락 보유 중
        redis.delete(RedisKeys.ORDER_LOCK_PREFIX + k)
        assertNotNull(run(broker, uid, sid, "buy", 1, k))
        assertNull(run(broker, uid, sid, "buy", 1, k)) // DB 에 이미 존재
        assertEquals(1, broker.placed)
        assertEquals(false, redis.hasKey(RedisKeys.ORDER_LOCK_PREFIX + k)) // 락은 항상 해제
    }

    @Test
    fun brokerRejectionIsFinalButUnknownOutcomeStaysPendingAndIsNeverReordered() {
        val (uid, sid) = setup()
        val rejected = run(FakeBroker(place = { throw BrokerError("주문 오류: [APBK0919] 잔고 부족") }), uid, sid, "buy", 1)!!
        assertEquals(OrderStatus.rejected, rejected.status)

        val k = key()
        val broker = FakeBroker(place = { throw IOException("request timed out") })
        assertThrows<IOException> { run(broker, uid, sid, "buy", 7_500, k) }
        val pending = orders.findAll().single { it.idempotencyKey == k }
        assertEquals(OrderStatus.pending, pending.status) // REJECTED 로 확정하지 않는다
        assertNull(pending.kisOrderId)
        val alert = alerts.findAll().single { it.code == "order_unconfirmed" && it.strategyId == sid }
        assertEquals(AlertSeverity.critical, alert.severity)
        assertTrue(alert.message.startsWith("주문 접수 결과 불명 — 005930 buy 7,500주(주문 #${pending.id})."))
        // 같은 신호로 다시 와도 재주문하지 않는다(이중 매수 방지 — 회수는 reconcile 이 잔고로)
        broker.place = { OrderResult("X") }
        assertNull(run(broker, uid, sid, "buy", 7_500, k))
        assertEquals(1, broker.placed)
        assertEquals(false, redis.hasKey(RedisKeys.ORDER_LOCK_PREFIX + k))
    }

    /**
     * 주문 POST 의 HTTP 500 + IGW00002(인증 시점 계좌 ≠ 요청 계좌, 2026-11-16 실측)는 접수불명이 아니라 거절 — rejected 로 끝나고 알림·종목 보류가 없으며,
     * 같은 신호는 다시 보내지 않는다(다른 신호의 주문만 새로 나간다).
     */
    @Test
    fun accountMismatchHttp500IsRejectedNotUnknown() {
        val (uid, sid) = setup()
        val http = object : com.quantfolio.broker.BrokerHttp {
            var posts = 0
            override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?) = when {
                url.endsWith("/oauth2/tokenP") -> com.quantfolio.broker.BrokerResponse(200, """{"access_token":"t","expires_in":86400}""")
                url.endsWith("/uapi/hashkey") -> com.quantfolio.broker.BrokerResponse(200, """{"HASH":"h"}""")
                url.endsWith("/order-cash") -> { posts++; com.quantfolio.broker.BrokerResponse(500, """{"rt_cd":"1","msg_cd":"IGW00002","msg1":"인증 시점의 계좌번호와 요청 계좌번호가 일치하지 않습니다."}""") }
                else -> error("예상하지 않은 요청: $method $url")
            }
        }
        val support = com.quantfolio.broker.BrokerSupport(http, redis, com.quantfolio.common.QuantfolioProperties(), java.time.Clock.systemUTC(), tools.jackson.databind.json.JsonMapper.builder().build()) { }
        val kis = com.quantfolio.broker.KisClient("test-${UUID.randomUUID()}", "test", TEST_KIS_ACCOUNT, support)
        val k = key()

        val o = run(kis, uid, sid, "buy", 3, k)!!

        assertEquals(OrderStatus.rejected, o.status)
        assertEquals(OrderStatus.rejected, orders.findById(o.id!!).orElseThrow().status)
        assertNull(o.kisOrderId)
        assertTrue(alerts.findAll().none { it.strategyId == sid }, "접수불명·미전송 알림이 없어야 한다")
        assertNull(run(kis, uid, sid, "buy", 3, k)) // 같은 신호는 재전송하지 않는다
        assertEquals(1, http.posts)
        // 접수불명 집합에 없으므로 같은 종목의 다른 신호는 막히지 않는다(이것도 거절되지만 POST 는 신호당 한 번)
        assertEquals(OrderStatus.rejected, run(kis, uid, sid, "buy", 3)!!.status)
        assertEquals(2, http.posts)
    }

    /** 전송 전 실패(우리 쪽 장애)는 증권사의 명시적 거절과 같이 rejected 지만, 신호의 주문이 빠졌으니 알려야 한다. */
    @Test
    fun orderNotSentIsRejectedWithCriticalAlertButExplicitRejectionIsSilent() {
        val (uid, sid) = setup()
        run(FakeBroker(place = { throw BrokerError("주문 오류: [APBK0919] 잔고 부족") }), uid, sid, "buy", 1)!!
        assertEquals(0, alerts.findAll().count { it.strategyId == sid })

        val k = key()
        val broker = FakeBroker(place = { throw OrderNotSent("주문 전송 전 실패: java.io.IOException: token connect refused") })
        val order = run(broker, uid, sid, "buy", 7_500, k, symbol = "000660")!!
        assertEquals(OrderStatus.rejected, order.status)
        assertEquals(1, broker.placed)
        val alert = alerts.findAll().single { it.code == "order_not_sent" && it.strategyId == sid }
        assertEquals(AlertSeverity.critical, alert.severity)
        assertEquals(
            "주문 미전송 — 000660 buy 7,500주(주문 #${order.id}). 증권사로 주문이 전송되지 않았고 이 신호의 주문은 재시도되지 않습니다. " +
                "원인: 주문 전송 전 실패: java.io.IOException: token connect refused",
            alert.message,
        )
    }

    @Test
    fun unknownOrFailedFillLookupLeavesSubmittedForReconcile() {
        val (uid, sid) = setup()
        val noFill = run(FakeBroker(), uid, sid, "buy", 2)!!
        assertEquals(OrderStatus.submitted, noFill.status)
        val lookupFails = run(FakeBroker(execution = { throw BrokerError("체결 조회 실패") }), uid, sid, "buy", 2, symbol = "000660")!!
        assertEquals(OrderStatus.submitted, lookupFails.status)
        assertTrue(positions.findAll().none { it.strategyId == sid })

        val noAvg = run(FakeBroker(execution = { Fill(2, null, true) }), uid, sid, "buy", 2, price = "65000", symbol = "035720")!!
        assertEquals(BigDecimal("65000.0000"), executions.findAll().single { it.orderId == noAvg.id }.filledPrice) // 신호가 폴백
    }

    @Test
    fun acceptedOrderIsDurableBeforeFillLookupFails() {
        val (uid, sid) = setup()
        val k = key()
        val broker = FakeBroker(execution = {
            val saved = orders.findAll().single { it.idempotencyKey == k }
            assertEquals("ODNO1", saved.kisOrderId)
            assertEquals(OrderStatus.submitted, saved.status)
            throw IOException("fill lookup disconnected")
        })
        assertThrows<IOException> { run(broker, uid, sid, "buy", 1, k) }
        val saved = orders.findAll().single { it.idempotencyKey == k }
        assertEquals("ODNO1", saved.kisOrderId)
        assertEquals("ORG1", saved.kisOrderOrgNo)
        assertEquals(OrderStatus.submitted, saved.status)
        assertNull(run(broker, uid, sid, "buy", 1, k))
        assertEquals(1, broker.placed)
    }

    @Test
    fun pendingOutcomeBlocksAnotherSignalAndStrategyUntilResolved() {
        val (uid, sid) = setup()
        val broker = FakeBroker(place = { throw IOException("response lost") })
        assertThrows<IOException> { run(broker, uid, sid, "buy", 1) }
        val nextSid = strategies.save(Strategy(uid, "next", config = mapOf("type" to "rsi"))).id!!
        broker.place = { OrderResult("NEXT") }
        assertNull(run(broker, uid, nextSid, "buy", 1))
        assertNull(run(broker, uid, nextSid, "sell", 1))
        assertEquals(1, broker.placed)
        val pending = orders.findAll().single { it.userId == uid }
        pending.status = OrderStatus.rejected
        orders.saveAndFlush(pending)
        assertNotNull(run(broker, uid, nextSid, "buy", 1))
        assertEquals(2, broker.placed)
    }

    @Test
    fun liveNumberedOrderBlocksOtherSignalsOnSamePositionUntilRecorded() {
        val (uid, sid) = setup()
        val first = run(FakeBroker(), uid, sid, "sell", 2)!!
        assertEquals(OrderStatus.submitted, first.status)
        val broker = FakeBroker(place = { OrderResult("NEXT") })
        // 체결이 장부에 오기 전이면 손절(`:stop`)처럼 키가 다른 신호도 낡은 포지션으로 나가지 않는다
        assertNull(run(broker, uid, sid, "sell", 2))
        assertNull(run(broker, uid, sid, "buy", 2))
        assertEquals(0, broker.placed)
        recordElsewhere(first.id!!, 1, "70000")
        assertEquals(OrderStatus.partial, orders.findById(first.id!!).orElseThrow().status)
        assertNull(run(broker, uid, sid, "sell", 1))
        assertEquals(0, broker.placed)
        // 다른 종목·다른 전략의 포지션은 막지 않는다
        val otherSid = strategies.save(Strategy(uid, "other", config = mapOf("type" to "rsi"))).id!!
        assertNotNull(run(broker, uid, sid, "buy", 1, symbol = "000660"))
        assertNotNull(run(broker, uid, otherSid, "buy", 1))
        assertEquals(2, broker.placed)
        for (terminal in listOf(OrderStatus.filled, OrderStatus.rejected)) {
            val (u, s) = setup()
            val live = run(FakeBroker(), u, s, "buy", 1)!!
            assertNull(run(FakeBroker(), u, s, "buy", 1))
            live.status = terminal
            orders.saveAndFlush(live)
            assertNotNull(run(FakeBroker(), u, s, "buy", 1))
        }
    }

    @Test
    fun priorDayLiveOrderDoesNotBlockTodaysSignal() {
        val (uid, sid) = setup()
        val old = run(FakeBroker(), uid, sid, "buy", 2)!!
        jdbc.update("update orders set created_at = created_at - interval '1 day' where id = ?", old.id)
        assertNotNull(run(FakeBroker(), uid, sid, "buy", 2))
    }

    /** 정합이 종결(closed_at)한 주문은 접수불명·미완료 보류 어느 쪽에서도 더는 같은 종목 주문을 막지 않는다. */
    @Test
    fun closedOrdersNoLongerBlockNewSignals() {
        val (uid, sid) = setup()
        assertThrows<IOException> { run(FakeBroker(place = { throw IOException("response lost") }), uid, sid, "buy", 1) }
        val unknown = orders.findAll().single { it.userId == uid }
        assertNull(run(FakeBroker(), uid, sid, "buy", 1)) // 접수불명 보류
        jdbc.update("update orders set closed_at = now() where id = ?", unknown.id) // 상태는 pending 그대로 — 술어가 closed_at 을 봐야 풀린다
        val live = run(FakeBroker(), uid, sid, "buy", 1)!!
        assertEquals(OrderStatus.submitted, live.status)
        assertNull(run(FakeBroker(), uid, sid, "buy", 1)) // 같은 날 미완료 보류
        jdbc.update("update orders set closed_at = now() where id = ?", live.id) // 상태는 submitted 그대로여도 종결이면 보지 않는다
        assertNotNull(run(FakeBroker(), uid, sid, "buy", 1))
    }

    @Test
    fun ledgerChangedAfterDecisionHoldsOrder() {
        val (uid, sid) = setup()
        val pos = positions.save(Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val decided = acctPosition(uid, sid).qty
        // 판단 뒤·예약 전에 다른 경로가 체결을 기록했다
        jdbc.update("update positions set qty = 3 where id = ?", pos.id)
        val broker = FakeBroker()
        assertNull(run(broker, uid, sid, "sell", 5, held = decided))
        assertNull(run(broker, uid, sid, "buy", 1, held = decided))
        assertEquals(0, broker.placed)
        assertTrue(orders.findAll().none { it.userId == uid })
        assertNotNull(run(broker, uid, sid, "sell", 3, held = BigDecimal("3"))) // 새 장부로 다시 판단하면 나간다
        assertEquals(1, broker.placed)
    }

    @Test
    fun orderDecidedBeforeEarlierOrderFilledIsHeldAfterThatFillIsRecorded() {
        val (uid, sid) = setup()
        val first = run(FakeBroker(), uid, sid, "buy", 2)!!
        assertEquals(OrderStatus.submitted, first.status)
        val decided = BigDecimal.ZERO // 다음 신호가 첫 주문의 체결이 오기 전 장부로 판단
        // 게이트를 지나는 동안 정합(주기·체결통보 트리거)이 첫 주문을 filled 로 기록 — 미완료 주문 보류는 더 이상 첫 주문을 보지 않는다
        recordElsewhere(first.id!!, 2, "70000")
        assertEquals(OrderStatus.filled, orders.findById(first.id!!).orElseThrow().status)
        val broker = FakeBroker(place = { OrderResult("NEXT") })
        assertNull(run(broker, uid, sid, "buy", 2, held = decided))
        assertEquals(0, broker.placed)
        assertNotNull(run(broker, uid, sid, "buy", 2, held = acctPosition(uid, sid).qty))
        assertEquals(1, broker.placed)
    }

    @Test
    fun sameLedgerQtyPassesRegardlessOfScaleAndMissingPositionIsZero() {
        for (held in listOf(BigDecimal("5"), BigDecimal("5.0000"))) {
            val (uid, sid) = setup()
            positions.save(Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
            assertNotNull(run(FakeBroker(), uid, sid, "sell", 5, held = held))
        }
        for (row in listOf(false, true)) for (held in listOf(BigDecimal.ZERO, BigDecimal("0.0000"))) {
            val (uid, sid) = setup()
            if (row) positions.save(Position(uid, sid, "005930", BigDecimal.ZERO, BigDecimal.ZERO, brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
            assertNotNull(run(FakeBroker(), uid, sid, "buy", 1, held = held))
        }
        val (uid, sid) = setup()
        assertNull(run(FakeBroker(), uid, sid, "sell", 1, held = BigDecimal.ONE)) // 없는 포지션을 보유로 판단
    }

    @Test
    fun otherStrategySymbolOrAccountPositionChangesDoNotHold() {
        val (uid, sid) = setup()
        val otherSid = strategies.save(Strategy(uid, "other", config = mapOf("type" to "rsi"))).id!!
        positions.save(Position(uid, sid, "005930", BigDecimal("5"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        // 판단 뒤 다른 전략·다른 종목·다른 계좌·계좌 미상 포지션이 바뀌었다
        positions.save(Position(uid, otherSid, "005930", BigDecimal("7"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        positions.save(Position(uid, sid, "000660", BigDecimal("7"), BigDecimal("1000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        positions.save(Position(uid, sid, "005930", BigDecimal("7"), BigDecimal("1000"), brokerAccountKey = "different-account"))
        positions.save(Position(uid, sid, "005930", BigDecimal("7"), BigDecimal("1000")))
        assertNotNull(run(FakeBroker(), uid, sid, "sell", 5, held = BigDecimal("5")))
    }

    @Test
    fun missingOrderNumberRemainsPendingAndBlocksNewSignals() {
        for (number in listOf(null, "", "   ")) {
            val (uid, sid) = setup()
            val broker = FakeBroker(place = { OrderResult(number) }, execution = { error("must not query") })
            assertThrows<IllegalStateException> { run(broker, uid, sid, "buy", 1) }
            val pending = orders.findAll().single { it.userId == uid }
            assertEquals(OrderStatus.pending, pending.status)
            assertNull(pending.kisOrderId)
            assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "order_unconfirmed" })
            assertNull(run(broker, uid, sid, "buy", 1))
            assertEquals(1, broker.placed)
        }
    }

    @Test
    fun concurrentDifferentSignalsCannotPassPendingReservation() {
        val (uid, sid) = setup()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val broker = FakeBroker(place = {
            entered.countDown()
            check(finish.await(10, SECONDS))
            throw IOException("unknown")
        })
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val first = pool.submit { assertThrows<IOException> { run(broker, uid, sid, "buy", 1) } }
            try {
                assertTrue(entered.await(10, SECONDS))
                assertNull(run(FakeBroker(), uid, sid, "buy", 1))
            } finally { finish.countDown() }
            first.get(10, SECONDS)
        }
        assertEquals(1, orders.findAll().count { it.userId == uid })
    }

    @Test
    fun outerRollbackCannotEraseAcceptedOrderOrPendingReservation() {
        val (uid, sid) = setup()
        val acceptedKey = key()
        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                run(FakeBroker(), uid, sid, "buy", 1, acceptedKey)
                error("caller rolls back")
            }
        }
        val accepted = orders.findAll().single { it.idempotencyKey == acceptedKey }
        assertEquals(OrderStatus.submitted, accepted.status)
        assertEquals("ODNO1", accepted.kisOrderId)
        val pendingKey = key()
        val pendingSid = strategies.save(Strategy(uid, "pending", config = mapOf("type" to "rsi"))).id!!
        assertThrows<IllegalStateException> {
            tx.executeWithoutResult {
                run(FakeBroker(place = { throw IllegalStateException("response lost") }), uid, pendingSid, "buy", 1, pendingKey)
            }
        }
        assertEquals(OrderStatus.pending, orders.findAll().single { it.idempotencyKey == pendingKey }.status)
        assertNull(run(FakeBroker(), uid, sid, "buy", 1))
    }

    @Test
    fun fillRecordedByAnotherPathDuringImmediateLookupIsRecordedOnlyOnce() {
        val (uid, sid) = setup()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val broker = FakeBroker(execution = {
            entered.countDown()
            check(finish.await(10, SECONDS))
            Fill(2, BigDecimal("70100"), true)
        })
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val first = pool.submit<com.quantfolio.domain.trading.Order?> { run(broker, uid, sid, "buy", 2) }
            try {
                assertTrue(entered.await(10, SECONDS))
                recordElsewhere(orders.findAll().single { it.userId == uid }.id!!, 2, "70100")
            } finally { finish.countDown() }
            val order = first.get(10, SECONDS)!!
            assertEquals(1, executions.findAll().count { it.orderId == order.id })
            assertEquals(0, BigDecimal("2").compareTo(acctPosition(uid, sid).qty))
        }
    }

    @Test
    fun concurrentFillsPreserveExistingPosition() = verifyConcurrentFills(existing = true)

    @Test
    fun concurrentFirstFillsCreateOnlyOnePosition() = verifyConcurrentFills(existing = false)

    private fun verifyConcurrentFills(existing: Boolean) {
        val (uid, sid) = setup()
        val symbol = "005930"
        if (existing) positions.save(Position(uid, sid, symbol, BigDecimal("2"), BigDecimal("100"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        val first = run(FakeBroker(), uid, sid, "buy", 2)!!
        // 전날 비종결 주문의 체결이 오늘 주문과 같은 포지션에 늦게 기록되는 경우
        jdbc.update("update orders set created_at = created_at - interval '1 day' where id = ?", first.id)
        val second = run(FakeBroker(), uid, sid, "buy", 2)!!
        val written = CountDownLatch(1)
        val commit = CountDownLatch(1)
        val secondPid = CompletableFuture<Int>()
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            val firstFill = pool.submit {
                tx.executeWithoutResult {
                    assertNotNull(fills.record(first, 2, BigDecimal("130"), true, expectedRecordedQty = 0))
                    positions.flush()
                    written.countDown()
                    check(commit.await(15, SECONDS))
                }
            }
            val secondFill = pool.submit {
                check(written.await(10, SECONDS))
                tx.executeWithoutResult {
                    secondPid.complete(jdbc.queryForObject("select pg_backend_pid()", Int::class.java)!!)
                    assertNotNull(fills.record(second, 2, BigDecimal("160"), true, expectedRecordedQty = 0))
                    positions.flush()
                }
            }
            try {
                val pid = secondPid.get(10, SECONDS)
                // Before the first commits, the second is waiting either to read under the lock or to write a stale result.
                val deadline = System.nanoTime() + SECONDS.toNanos(3)
                fun blocked() = jdbc.queryForObject("select cardinality(pg_blocking_pids(?)) > 0", Boolean::class.java, pid) == true
                while (!blocked() && !secondFill.isDone && System.nanoTime() < deadline) Thread.sleep(10)
                assertTrue(blocked(), "Second fill must reach the database while the first is uncommitted")
            } finally {
                commit.countDown()
            }
            firstFill.get(10, SECONDS)
            secondFill.get(10, SECONDS)
        }
        val position = positions.findAll().single { it.userId == uid && it.strategyId == sid && it.symbol == symbol }
        assertEquals(BigDecimal(if (existing) "6.0000" else "4.0000"), position.qty)
        assertEquals(BigDecimal(if (existing) "130.0000" else "145.0000"), position.avgPrice)
        assertEquals(2, executions.findAll().count { it.orderId == first.id || it.orderId == second.id })
    }

    @Test
    fun staleFillDeltaIsRejectedAfterAnotherPathRecordsTheOrder() {
        val (uid, sid) = setup()
        val order = run(FakeBroker(), uid, sid, "buy", 3)!!
        val stale = orders.findById(order.id!!).orElseThrow()
        recordElsewhere(order.id!!, 1, "100")

        assertNull(tx.execute { fills.record(stale, 3, BigDecimal("110"), true, expectedRecordedQty = 0) })
        assertEquals(OrderStatus.partial, orders.findById(order.id!!).orElseThrow().status)
        assertEquals(1, fills.recordedQty(order.id!!))
        assertEquals(BigDecimal("1.0000"), acctPosition(uid, sid).qty)
    }

    @Test
    fun sellReducesAndOversellIsClampedWithWarning() {
        val (uid, sid) = setup()
        positions.save(Position(uid, sid, "000660", BigDecimal("3"), BigDecimal("120000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        run(FakeBroker(execution = { Fill(1, BigDecimal("125000"), true) }), uid, sid, "sell", 1, symbol = "000660")
        val p = positions.findAll().single { it.strategyId == sid && it.symbol == "000660" }
        assertEquals(0, BigDecimal("2").compareTo(p.qty))
        assertEquals(0, BigDecimal("120000").compareTo(p.avgPrice)) // 매도는 평단 불변
        run(FakeBroker(execution = { Fill(5, BigDecimal("125000"), true) }), uid, sid, "sell", 5, symbol = "000660")
        val p2 = positions.findById(p.id!!).get()
        val sellExecutions = executions.findAll().filter { it.strategyId == sid }.sortedBy { it.id }
        assertEquals(listOf(BigDecimal("1.0000"), BigDecimal("2.0000")), sellExecutions.map { it.accountPositionQty })
        assertEquals(0, BigDecimal.ZERO.compareTo(p2.qty))
        assertEquals(0, BigDecimal.ZERO.compareTo(p2.avgPrice))
        assertEquals("000660 매도 체결 5주가 보유수량 2.0000주를 초과해 포지션을 0으로 클램프했습니다(정합 경쟁 의심 — 확인 필요).", alerts.findAll().single { it.code == "oversell_clamped" && it.strategyId == sid }.message)
    }
}
