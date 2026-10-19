package com.quantfolio.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.Fill
import com.quantfolio.broker.OrderResult
import com.quantfolio.broker.Quote
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.io.IOException
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
) {
    /** 가짜 증권사 — 주문·체결 응답을 시나리오별로 바꾼다. */
    class FakeBroker(
        var place: () -> OrderResult = { OrderResult("ODNO1", "ORG1") },
        var execution: (String) -> Fill = { Fill(0, null, false) },
    ) : BrokerClient {
        var placed = 0
        override fun verifyConnection() = true
        override fun getQuote(symbol: String) = Quote(symbol, BigDecimal.ONE)
        override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult { placed++; return place() }
        override fun getOrderExecution(orderId: String, symbol: String?) = execution(orderId)
        override fun getBalance() = Balance()
    }

    private fun setup(): Pair<Int, Int> {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x"))
        val s = strategies.save(Strategy(u.id!!, "s", config = mapOf("type" to "rsi")))
        return u.id!! to s.id!!
    }

    private fun key() = "s0:TEST:buy:" + UUID.randomUUID()

    private fun run(broker: BrokerClient, uid: Int, sid: Int, side: String, qty: Int, key: String = key(), price: String = "70000", symbol: String = "005930") =
        executor.execute(broker, uid, sid, symbol, side, qty, BigDecimal(price), key, "테스트 사유")

    @Test
    fun filledOrderRecordsExecutionPositionAndAveragePrice() {
        val (uid, sid) = setup()
        val broker = FakeBroker(execution = { Fill(3, BigDecimal("70100"), true) })
        val o = run(broker, uid, sid, "buy", 3)!!
        assertEquals("filled", o.status)
        assertEquals("ODNO1", o.kisOrderId)
        assertEquals("ORG1", o.kisOrderOrgNo)
        assertEquals("테스트 사유", o.reason)
        assertEquals(BigDecimal("70100.0000"), executions.findAll().single { it.orderId == o.id }.filledPrice)
        val p = positions.findAll().single { it.userId == uid && it.strategyId == sid }
        assertEquals(0, BigDecimal("3").compareTo(p.qty))
        assertEquals(0, BigDecimal("70100").compareTo(p.avgPrice))

        broker.execution = { Fill(1, BigDecimal("70500"), false) }
        val partial = run(broker, uid, sid, "buy", 2)!!
        assertEquals("partial", partial.status)
        val p2 = positions.findById(p.id!!).get()
        assertEquals(0, BigDecimal("4").compareTo(p2.qty))
        assertEquals(0, BigDecimal("70200").compareTo(p2.avgPrice)) // (3×70100 + 1×70500)/4
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
        assertEquals("rejected", rejected.status)

        val k = key()
        val broker = FakeBroker(place = { throw IOException("request timed out") })
        assertThrows<IOException> { run(broker, uid, sid, "buy", 7_500, k) }
        val pending = orders.findAll().single { it.idempotencyKey == k }
        assertEquals("pending", pending.status) // REJECTED 로 확정하지 않는다
        assertNull(pending.kisOrderId)
        val alert = alerts.findAll().single { it.code == "order_unconfirmed" && it.strategyId == sid }
        assertEquals("critical", alert.severity)
        assertTrue(alert.message.startsWith("주문 접수 결과 불명 — 005930 buy 7,500주(주문 #${pending.id})."))
        // 같은 신호로 다시 와도 재주문하지 않는다(이중 매수 방지 — 회수는 reconcile 이 잔고로)
        broker.place = { OrderResult("X") }
        assertNull(run(broker, uid, sid, "buy", 7_500, k))
        assertEquals(1, broker.placed)
        assertEquals(false, redis.hasKey(RedisKeys.ORDER_LOCK_PREFIX + k))
    }

    @Test
    fun unknownOrFailedFillLookupLeavesSubmittedForReconcile() {
        val (uid, sid) = setup()
        val noFill = run(FakeBroker(), uid, sid, "buy", 2)!!
        assertEquals("submitted", noFill.status)
        val lookupFails = run(FakeBroker(execution = { throw BrokerError("체결 조회 실패") }), uid, sid, "buy", 2)!!
        assertEquals("submitted", lookupFails.status)
        assertTrue(positions.findAll().none { it.strategyId == sid })
        val noOrderNo = run(FakeBroker(place = { OrderResult(null) }, execution = { error("호출되면 안 됨") }), uid, sid, "buy", 1)!!
        assertEquals("submitted", noOrderNo.status)

        val noAvg = run(FakeBroker(execution = { Fill(2, null, true) }), uid, sid, "buy", 2, price = "65000")!!
        assertEquals(BigDecimal("65000.0000"), executions.findAll().single { it.orderId == noAvg.id }.filledPrice) // 신호가 폴백
    }

    @Test
    fun sellReducesAndOversellIsClampedWithWarning() {
        val (uid, sid) = setup()
        positions.save(Position(uid, sid, "000660", BigDecimal("3"), BigDecimal("120000")))
        run(FakeBroker(execution = { Fill(1, BigDecimal("125000"), true) }), uid, sid, "sell", 1, symbol = "000660")
        val p = positions.findAll().single { it.strategyId == sid && it.symbol == "000660" }
        assertEquals(0, BigDecimal("2").compareTo(p.qty))
        assertEquals(0, BigDecimal("120000").compareTo(p.avgPrice)) // 매도는 평단 불변
        run(FakeBroker(execution = { Fill(5, BigDecimal("125000"), true) }), uid, sid, "sell", 5, symbol = "000660")
        val p2 = positions.findById(p.id!!).get()
        assertEquals(0, BigDecimal.ZERO.compareTo(p2.qty))
        assertEquals(0, BigDecimal.ZERO.compareTo(p2.avgPrice))
        assertEquals("000660 매도 체결 5주가 보유수량 2.0000주를 초과해 포지션을 0으로 클램프했습니다(정합 경쟁 의심 — 확인 필요).", alerts.findAll().single { it.code == "oversell_clamped" && it.strategyId == sid }.message)
    }
}
