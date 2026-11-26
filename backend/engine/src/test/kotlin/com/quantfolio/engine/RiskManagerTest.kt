package com.quantfolio.engine

import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
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
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 주문 전 리스크 게이트(원본 `engine/risk.py`). */
@SpringBootTest
@Import(ContainersConfig::class)
class RiskManagerTest(
    @Autowired private val risk: RiskManager,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val limits: RiskLimitRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val fills: Fills,
    @Autowired private val txManager: org.springframework.transaction.PlatformTransactionManager,
) {
    @MockitoBean lateinit var master: KisStockMaster

    private fun user() = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
    private fun strategy(uid: Int) = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
    private fun d(s: String) = BigDecimal(s)

    @Test
    fun buySizingRespectsLimitsAndManagementBlock() {
        val uid = user()
        val a = strategy(uid)
        val b = strategy(uid)
        assertEquals(RiskManager.Decision(false, 0, "유효하지 않은 가격"), risk.evaluateBuy(uid, a, "005930", d("0"), d("1000000"), TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(true, 14, ""), risk.evaluateBuy(uid, a, "005930", d("70000"), d("1000000"), TEST_KIS_ACCOUNT_KEY)) // 한도 없음
        limits.save(RiskLimit(uid, null, maxPositionSize = d("500000")))
        // 다른 전략 보유분까지 계좌 합산: 3×100000 = 300000 → 남은 200000
        positions.save(Position(uid, b, "005930", d("3"), d("100000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(true, 2, ""), risk.evaluateBuy(uid, a, "005930", d("70000"), d("1000000"), TEST_KIS_ACCOUNT_KEY))
        limits.save(RiskLimit(uid, a, maxPositionSize = d("300000"))) // 전략 한도가 공통보다 우선
        assertEquals(RiskManager.Decision(false, 0, "최대 포지션 한도 도달"), risk.evaluateBuy(uid, a, "005930", d("70000"), d("1000000"), TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(false, 0, "주문 가능 수량 부족"), risk.evaluateBuy(uid, a, "000660", d("70000"), d("50000"), TEST_KIS_ACCOUNT_KEY))

        given(master.latest("123456")).willReturn(mapOf("trade_date" to LocalDate.now(), "market" to "KOSPI", "관리종목" to "Y"))
        assertTrue(risk.evaluateBuy(uid, a, "123456", d("1000"), d("100000"), TEST_KIS_ACCOUNT_KEY).reason.startsWith("관리종목 지정 종목"))
    }

    @Test
    fun sellIsScopedToTheStrategy() {
        val uid = user()
        val a = strategy(uid)
        val b = strategy(uid)
        positions.save(Position(uid, a, "005930", d("2"), d("1"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        positions.save(Position(uid, b, "005930", d("5"), d("1"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(true, 2, ""), risk.evaluateSell(uid, "005930", a, TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(true, 5, ""), risk.evaluateSell(uid, "005930", b, TEST_KIS_ACCOUNT_KEY))
        assertEquals(RiskManager.Decision(false, 0, "보유 수량 없음"), risk.evaluateSell(uid, "000660", a, TEST_KIS_ACCOUNT_KEY))
    }

    @Test
    fun dailyLossGatesAreIndependentAndSuppressRuleIsKept() {
        val uid = user()
        val a = strategy(uid)
        val b = strategy(uid)
        fun buy(sid: Int, sym: String, qty: Int, price: String, accountKey: String? = TEST_KIS_ACCOUNT_KEY) {
            val o = orders.save(Order(uid, sid, sym, "buy", d(qty.toString()), d(price), status = OrderStatus.filled, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = accountKey))
            executions.save(Execution(o.id!!, sid, d(qty.toString()), d(price), d("0"), Instant.now(), accountPositionQty = d(qty.toString())))
            positions.save(Position(uid, sid, sym, d(qty.toString()), d(price), brokerAccountKey = accountKey))
        }
        buy(a, "005930", 10, "10000")
        buy(b, "000660", 10, "10000")
        buy(a, "005930", 30, "100000", "different-account")
        buy(a, "005930", 40, "100000", null)
        val prices = mapOf("005930" to d("9000"), "000660" to d("10000")) // a 평가손 −10,000
        // 손익 = 실현(매도 없음, 수수료 0) + 평가손익 → 계좌 −10,000(a 만), 전략 a −10,000. 다른 계좌·계좌 미상 체결은 제외
        assertTrue(risk.checkDailyLoss(uid, a, prices, TEST_KIS_ACCOUNT_KEY).approved)
        limits.save(RiskLimit(uid, null, dailyLossLimit = d("10000"))) // 같으면 차단(≤)
        assertEquals(RiskManager.Decision(false, 0, "일일 손실 한도 초과"), risk.checkDailyLoss(uid, a, prices, TEST_KIS_ACCOUNT_KEY))
        val strat = limits.save(RiskLimit(uid, a, dailyLossLimit = null))
        assertTrue(risk.checkDailyLoss(uid, a, prices, TEST_KIS_ACCOUNT_KEY).approved) // 전략 행이 있고 한도 null 이면 계좌 게이트도 생략(원본 회귀 보존)
        strat.dailyLossLimit = d("10000")
        limits.save(strat)
        assertEquals("일일 손실 한도 초과", risk.checkDailyLoss(uid, a, prices, TEST_KIS_ACCOUNT_KEY).reason) // 계좌 게이트가 먼저
        limits.findAll().filter { it.userId == uid && it.strategyId == null }.forEach { limits.delete(it) }
        assertEquals(RiskManager.Decision(false, 0, "전략별 일일 손실 한도 초과"), risk.checkDailyLoss(uid, a, prices, TEST_KIS_ACCOUNT_KEY))
    }

    private fun fill(uid: Int, sid: Int, sym: String, side: String, qty: Int, price: String, fee: String, at: Instant) {
        val o = orders.save(Order(uid, sid, sym, side, d(qty.toString()), d(price), status = OrderStatus.filled, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        executions.save(Execution(o.id!!, sid, d(qty.toString()), d(price), d(fee), at, accountPositionQty = d(qty.toString())))
    }

    /** 원본은 매도대금을 그대로 이익으로 셌다 — 손실 매도가 손실 한도를 못 건드렸다(migration-notes #16②). */
    @Test
    fun dailyLossCountsTodaysSellAgainstItsCost() {
        val uid = user()
        val a = strategy(uid)
        fill(uid, a, "005930", "buy", 10, "10000", "0", Instant.now().minusSeconds(3 * 86400)) // 며칠 전 매수
        fill(uid, a, "005930", "sell", 10, "9000", "100", Instant.now()) // 오늘 전량 손실 매도
        positions.save(Position(uid, a, "005930", d("0"), d("0"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        limits.save(RiskLimit(uid, null, dailyLossLimit = d("10000")))
        // 실현 = (9,000 − 10,000) × 10 − 100 = −10,100 ≤ −10,000
        assertEquals(RiskManager.Decision(false, 0, "일일 손실 한도 초과"), risk.checkDailyLoss(uid, a, mapOf("005930" to d("9000")), TEST_KIS_ACCOUNT_KEY))
    }

    /** 매수대금은 손실이 아니다 — 원본은 현금흐름으로 빼서 매수만으로 손실 한도에 걸렸다. */
    @Test
    fun dailyLossDoesNotCountTodaysBuyCashAsLoss() {
        val uid = user()
        val a = strategy(uid)
        fill(uid, a, "005930", "buy", 10, "10000", "15", Instant.now())
        positions.save(Position(uid, a, "005930", d("10"), d("10000"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        limits.save(RiskLimit(uid, null, dailyLossLimit = d("50000")))
        assertTrue(risk.checkDailyLoss(uid, a, mapOf("005930" to d("10000")), TEST_KIS_ACCOUNT_KEY).approved) // 손익 = 수수료 −15
    }

    /**
     * 일손익 재생(`RiskManager.dailyPnl`)은 `Fills.record` 의 평단 규칙을 따로 구현한다 — 기록 경로로 만든 체결에서 둘이 일치해야 한다.
     * 평단은 기록 결과(positions)에서 읽는다: `Fills.record` 규칙만 바뀌어도 기대값이 따라 바뀌어 재생 쪽 불일치가 드러난다(trading-safety.md).
     */
    @Test
    fun dailyPnlReplayMatchesFillsRecordAverage() {
        val uid = user()
        val a = strategy(uid)
        val tx = org.springframework.transaction.support.TransactionTemplate(txManager)
        val ids = mutableListOf<Int>()
        fun record(side: String, qty: Int, price: String) {
            val o = orders.save(Order(uid, a, "005930", side, d(qty.toString()), d(price), status = OrderStatus.submitted, idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
            ids += o.id!!
            kotlin.test.assertNotNull(tx.execute { fills.record(o, qty, d(price), true, 0) })
        }
        record("buy", 10, "100")
        record("buy", 10, "200")
        val avg = positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(uid, TEST_KIS_ACCOUNT_KEY, a, "005930")!!.avgPrice
        record("sell", 5, "140")
        val fees = executions.findAll().filter { it.orderId in ids }.sumOf { it.fee }
        val loss = (avg - d("140")) * d("5") + fees // 오늘 체결 전부라 수수료도 전부 차감
        val limit = limits.save(RiskLimit(uid, null, dailyLossLimit = loss))
        val noPrices = emptyMap<String, BigDecimal>() // 평가손익 제외 — 실현손익만 본다
        assertEquals("일일 손실 한도 초과", risk.checkDailyLoss(uid, a, noPrices, TEST_KIS_ACCOUNT_KEY).reason)
        limit.dailyLossLimit = loss + d("1")
        limits.save(limit)
        assertTrue(risk.checkDailyLoss(uid, a, noPrices, TEST_KIS_ACCOUNT_KEY).approved)
    }

    @Test
    fun stopLossUsesAggregateAverageAndIgnoresMissingPrices() {
        val uid = user()
        val a = strategy(uid)
        val b = strategy(uid)
        positions.save(Position(uid, a, "005930", d("1"), d("100"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY))
        positions.save(Position(uid, b, "005930", d("3"), d("200"), brokerAccountKey = TEST_KIS_ACCOUNT_KEY)) // 가중평균 175
        positions.save(Position(uid, a, "005930", d("50"), d("999"), brokerAccountKey = "different-account"))
        positions.save(Position(uid, a, "005930", d("50"), d("999")))
        assertFalse(risk.checkStopLoss(uid, a, "005930", d("100"), TEST_KIS_ACCOUNT_KEY)) // 한도 없음
        limits.save(RiskLimit(uid, null, stopLossPct = d("0.1")))
        assertFalse(risk.checkStopLoss(uid, a, "005930", d("0"), TEST_KIS_ACCOUNT_KEY)) // 0 은 시세 결측 — 손절 금지
        assertFalse(risk.checkStopLoss(uid, a, "005930", d("158"), TEST_KIS_ACCOUNT_KEY)) // 하락 9.7%
        assertTrue(risk.checkStopLoss(uid, a, "005930", d("157.5"), TEST_KIS_ACCOUNT_KEY)) // 정확히 10%
    }
}
