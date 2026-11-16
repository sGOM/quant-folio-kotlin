package com.quantfolio.web.engine

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.Fill
import com.quantfolio.broker.LegacyFillEvidence
import com.quantfolio.broker.OrderResult
import com.quantfolio.broker.Quote
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class LegacyAccountLinksTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val sessions: SessionStore,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials
    private val json = JsonMapper.builder().build()
    private val created = Instant.parse("2026-10-19T01:00:00Z")

    private class ReadOnlyBroker(override val orderAccountKey: String = "account-A") : BrokerClient {
        val evidence = mutableMapOf<String, LegacyFillEvidence>()
        var holdings: () -> Balance = { Balance() }
        val reads = mutableListOf<String>()
        override fun legacyFillEvidence(orderId: String, organizationNo: String?, symbol: String, orderDate: LocalDate): LegacyFillEvidence? {
            reads += orderId
            return evidence[orderId]
        }
        override fun getBalance() = holdings()
        override fun verifyConnection() = true
        override fun getQuote(symbol: String): Quote = error("quote not expected")
        override fun getOrderExecution(orderId: String, symbol: String?): Fill = error("unverified lookup not expected")
        override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult = error("must never send orders")
    }

    private inner class Me {
        val user = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x", kisAccountNo = "A"))
        val uid = user.id!!
        val strategy = strategies.save(Strategy(uid, "legacy", config = mapOf("type" to "rsi"))).id!!
        val cookie = Cookie(SESSION_COOKIE, sessions.create(uid))
        fun order(number: String?, side: String = "buy", qty: String = "2", price: String = "1000", second: Long = 0, status: OrderStatus = OrderStatus.filled): Order {
            val o = orders.save(Order(uid, strategy, "005930", side, BigDecimal(qty), BigDecimal(price), kisOrderId = number, kisOrderOrgNo = "12345",
                status = status, idempotencyKey = UUID.randomUUID().toString(), createdAt = created.plusSeconds(second)))
            if (status == OrderStatus.filled) executions.save(Execution(o.id!!, strategy, o.qty, BigDecimal(price), executedAt = o.createdAt.plusSeconds(2)))
            return o
        }
        fun holding(qty: String = "2", avg: String = "1000") = positions.save(Position(uid, strategy, "005930", BigDecimal(qty), BigDecimal(avg)))
    }

    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")
    private fun stub(broker: ReadOnlyBroker) { given(brokers.forUser(anyUser())).willReturn(broker) }
    private fun proof(o: Order) = LegacyFillEvidence(o.kisOrderId!!, o.symbol, o.side, o.createdAt.plusSeconds(1), o.qty, o.qty, o.qty * o.price!!)
    private fun balance(qty: String, avg: String) = Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to qty, "pchs_avg_pric" to avg)))

    private fun call(me: Me, apply: Boolean = false, ids: List<Int>? = null, expectedStatus: Int = 200): LegacyAccountLinkResult? {
        val response = mvc.post("/api/trading/legacy-account-links") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("symbol" to "005930", "apply" to apply, "order_ids" to ids))
        }.andReturn().response
        assertEquals(expectedStatus, response.status, response.contentAsString)
        if (expectedStatus != 200) return null
        val body = json.readTree(response.contentAsString)
        fun items(name: String) = body.get(name).toList().map { LegacyAccountLinkItem(it.get("id").asInt(), it.get("result").asString()) }
        return LegacyAccountLinkResult(items("orders"), items("positions"))
    }

    @Test
    fun previewThenLinkVerifiedOrdersAndPositionWithoutChangingQuantities() {
        val me = Me()
        val broker = ReadOnlyBroker()
        stub(broker)
        val buy = me.order("0001", qty = "3")
        val add = me.order("0002", qty = "1", price = "1400", second = 10)
        val sell = me.order("0003", side = "sell", qty = "2", price = "1300", second = 20)
        val position = me.holding(qty = "2", avg = "1100")
        val recordedIds = listOf(buy, add, sell).map { it.id!! }
        listOf(buy, add, sell).forEach { broker.evidence[it.kisOrderId!!] = proof(it) }
        broker.holdings = { balance("2", "1100") }

        val preview = call(me)!!
        assertEquals(recordedIds.map { LegacyAccountLinkItem(it, "verified") }, preview.orders)
        assertEquals(listOf(LegacyAccountLinkItem(position.id!!, "verified")), preview.positions)
        assertNull(orders.findById(buy.id!!).orElseThrow().brokerAccountKey)
        assertNull(positions.findById(position.id!!).orElseThrow().brokerAccountKey)

        val applied = call(me, apply = true)!!
        assertEquals(recordedIds.map { LegacyAccountLinkItem(it, "linked") }, applied.orders)
        assertEquals(listOf(LegacyAccountLinkItem(position.id!!, "linked")), applied.positions)
        for (id in recordedIds) assertEquals(broker.orderAccountKey, orders.findById(id).orElseThrow().brokerAccountKey)
        val saved = positions.findById(position.id!!).orElseThrow()
        assertEquals(broker.orderAccountKey, saved.brokerAccountKey)
        assertEquals(BigDecimal("2.0000"), saved.qty)
        assertEquals(BigDecimal("1100.0000"), saved.avgPrice)
        val fills = executions.findAll().filter { it.orderId in recordedIds }.sortedBy { it.id }
        assertEquals(listOf("3.0000", "1.0000", "2.0000"), fills.map { it.accountPositionQty?.toPlainString() })
        assertEquals(LegacyAccountLinkResult(emptyList(), emptyList()), call(me, apply = true))
    }

    @Test
    fun incompleteDuplicateAndMismatchingOrdersRemainUnlinkedEvenWhenHoldingsMatch() {
        val me = Me()
        val broker = ReadOnlyBroker()
        stub(broker)
        val first = me.order("DUP")
        val second = me.order("DUP", second = 3)
        val amountMismatch = me.order("AMOUNT")
        val timeMismatch = me.order("TIME")
        val unavailable = me.order("NO-EVIDENCE")
        val pending = me.order(null, status = OrderStatus.pending)
        val position = me.holding()
        broker.evidence["DUP"] = proof(first)
        broker.evidence["AMOUNT"] = proof(amountMismatch).copy(filledAmount = BigDecimal("2100"))
        broker.evidence["TIME"] = proof(timeMismatch).copy(orderedAt = created.plusSeconds(600))
        broker.holdings = { balance("2", "1000") }

        val result = call(me, apply = true)!!
        assertEquals(listOf("ambiguous_order_number", "ambiguous_order_number", "broker_evidence_mismatch", "broker_evidence_mismatch",
            "broker_evidence_missing", "incomplete_local_order"), result.orders.map { it.result })
        assertEquals(listOf(LegacyAccountLinkItem(position.id!!, "unverified_position_history")), result.positions)
        for (o in listOf(first, second, amountMismatch, timeMismatch, unavailable, pending)) assertNull(orders.findById(o.id!!).orElseThrow().brokerAccountKey)
        assertNull(positions.findById(position.id!!).orElseThrow().brokerAccountKey)
        assertEquals(listOf("AMOUNT", "TIME", "NO-EVIDENCE"), broker.reads)
    }

    @Test
    fun validOrderCanLinkWhileAmbiguousBrokerBalanceLeavesPositionUnchanged() {
        val me = Me()
        val broker = ReadOnlyBroker()
        stub(broker)
        val buy = me.order("KNOWN")
        broker.evidence["KNOWN"] = proof(buy)
        val position = me.holding()
        broker.holdings = { balance("2.9", "1000") }
        val result = call(me, apply = true)!!
        assertEquals(listOf(LegacyAccountLinkItem(buy.id!!, "linked")), result.orders)
        assertEquals(listOf(LegacyAccountLinkItem(position.id!!, "broker_balance_mismatch")), result.positions)
        assertNull(positions.findById(position.id!!).orElseThrow().brokerAccountKey)
        assertNull(executions.findAll().single { it.orderId == buy.id }.accountPositionQty)

        broker.holdings = { balance("2", "1001") }
        assertEquals("broker_balance_mismatch", call(me, apply = true)!!.positions.single().result)
        broker.holdings = { balance("2", "1000") }
        assertEquals("linked", call(me, apply = true)!!.positions.single().result)
    }

    @Test
    fun rejectsApplyIfAccountChangesWhileReadingBrokerEvidence() {
        val me = Me()
        val first = ReadOnlyBroker()
        val second = ReadOnlyBroker("account-B")
        given(brokers.forUser(anyUser())).willAnswer { if (it.getArgument<User>(0).kisAccountNo == "A") first else second }
        val order = me.order("OLD")
        val position = me.holding()
        first.evidence["OLD"] = proof(order)
        first.holdings = {
            val user = users.findById(me.uid).orElseThrow()
            user.kisAccountNo = "B"
            users.saveAndFlush(user)
            balance("2", "1000")
        }
        call(me, apply = true, expectedStatus = 409)
        assertNull(orders.findById(order.id!!).orElseThrow().brokerAccountKey)
        assertNull(positions.findById(position.id!!).orElseThrow().brokerAccountKey)
    }

    @Test
    fun rejectsApplyIfLocalPositionChangesWhileReadingBrokerEvidence() {
        val me = Me()
        val broker = ReadOnlyBroker()
        stub(broker)
        val order = me.order("OLD")
        val position = me.holding()
        broker.evidence["OLD"] = proof(order)
        broker.holdings = {
            val current = positions.findById(position.id!!).orElseThrow()
            current.qty = BigDecimal("3")
            positions.saveAndFlush(current)
            balance("2", "1000")
        }
        call(me, apply = true, expectedStatus = 409)
        assertNull(orders.findById(order.id!!).orElseThrow().brokerAccountKey)
        assertEquals(BigDecimal("3.0000"), positions.findById(position.id!!).orElseThrow().qty)
    }

    @Test
    fun brokerFailureAndAnotherUsersOrderCannotBecomeEvidence() {
        val me = Me()
        val other = Me()
        val broker = ReadOnlyBroker()
        stub(broker)
        val order = me.order("OWN")
        val foreign = other.order("FOREIGN")
        broker.evidence["OWN"] = proof(order)
        broker.evidence["FOREIGN"] = proof(foreign)
        broker.holdings = { throw BrokerError("unavailable") }
        val ownPosition = me.holding()
        assertEquals(LegacyAccountLinkResult(emptyList(), listOf(LegacyAccountLinkItem(ownPosition.id!!, "unverified_position_history"))),
            call(me, apply = true, ids = listOf(foreign.id!!)))
        assertEquals("broker_balance_unavailable", call(me, apply = true)!!.positions.single().result)
        assertNull(orders.findById(foreign.id!!).orElseThrow().brokerAccountKey)
        assertNull(positions.findById(ownPosition.id!!).orElseThrow().brokerAccountKey)
    }
}
