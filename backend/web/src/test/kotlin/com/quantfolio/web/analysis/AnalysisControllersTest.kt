package com.quantfolio.web.analysis

import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Execution
import com.quantfolio.domain.trading.ExecutionRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.ohlcv.OhlcvLoader
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class AnalysisControllersTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val backtests: BacktestRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val executions: ExecutionRepository,
    @Autowired private val ticks: PriceTickStore,
) {
    @MockitoBean lateinit var loader: OhlcvLoader

    private val json = JsonMapper.builder().build()

    private inner class Me {
        val user: User = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        val cookie = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        fun strategy(config: Map<String, Any?> = mapOf("type" to "rsi", "symbol" to "005930", "cash" to 1_000_000)) = strategies.save(Strategy(user.id!!, "s", config = config))
    }

    /** Kotlin non-null 파라미터용 any(Mockito 는 null 을 돌려준다). */
    private fun anyDate(): LocalDate = any(LocalDate::class.java) ?: LocalDate.MIN

    private fun sym() = "9" + Random.nextInt(10_000, 99_999)

    /** 체결 1건짜리 주문. executedAt 은 KST 날짜 기준이 되도록 UTC 01:00(KST 10:00). */
    private fun fill(me: Me, s: Strategy, symbol: String, side: String, day: LocalDate, qty: Int, price: Double, decision: Double? = null, status: String = "filled"): Order {
        val at = day.atTime(1, 0).toInstant(ZoneOffset.UTC)
        val o = orders.save(Order(me.user.id!!, s.id, symbol, side, BigDecimal(qty), decision?.let { BigDecimal.valueOf(it) }, status = status, idempotencyKey = UUID.randomUUID().toString(), createdAt = at))
        executions.save(Execution(o.id!!, s.id, BigDecimal(qty), BigDecimal.valueOf(price), BigDecimal("10"), at))
        return o
    }

    private fun closes(symbol: String, days: List<LocalDate>, f: (Int) -> Double) =
        ticks.upsert(symbol, days.mapIndexed { i, d -> Bar(d.atStartOfDay().toInstant(ZoneOffset.UTC), f(i), f(i), f(i), f(i), 100) })

    private fun weekdays(from: LocalDate, n: Int) = generateSequence(from) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(n).toList()

    @Test
    fun trackingReplaysFromFirstFillAndComparesLatestBacktest() {
        val me = Me()
        val s = me.strategy()
        val code = sym()
        val days = weekdays(LocalDate.of(2026, 9, 1), 10)
        closes(code, days) { 1000.0 + 10 * it }
        fill(me, s, code, "buy", days[0], 100, 1000.0)
        fill(me, s, code, "sell", days[5], 50, 1050.0)
        fill(me, s, code, "buy", days[6], 1, 1.0, status = "rejected") // 미체결 상태는 제외
        backtests.save(Backtest(s.id!!, Instant.parse("2026-08-31T15:00:00Z"), Instant.parse("2026-09-14T14:59:59Z"), result = mapOf("equity_curve" to listOf(mapOf("t" to "2026-09-01T00:00:00", "v" to 1e6)))))
        val latest = backtests.save(Backtest(s.id!!, Instant.parse("2026-08-31T15:00:00Z"), Instant.parse("2026-09-14T14:59:59Z"),
            result = mapOf("equity_curve" to days.mapIndexed { i, d -> mapOf("t" to "${d}T00:00:00", "v" to 1e6 * (1 + 0.001 * i)) })))

        val body = json.readTree(mvc.get("/api/strategies/${s.id}/tracking?date_to=${days.last()}") { cookie(me.cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(latest.id, body.get("backtest_id").asInt())
        assertEquals(1_000_000.0, body.get("initial_capital").asDouble())
        assertEquals(days[0].toString(), body.get("window").get("date_from").asString())
        val curve = body.get("realized_curve")
        assertEquals(10, curve.size())
        assertEquals(1_000_000.0 - 100_000 - 10 + 100 * 1000.0, curve.get(0).get("v").asDouble()) // 현금 + 보유평가
        assertEquals(2, body.get("sample").get("n_executions").asInt())
        assertEquals(10, body.get("metrics").get("n_common_days").asInt())
        assertTrue(body.get("warning").isNull)

        // 표시 구간만 자르고 재생은 처음부터 — 이전 체결을 반영했다는 안내
        val windowed = json.readTree(mvc.get("/api/strategies/${s.id}/tracking?date_from=${days[5]}&date_to=${days.last()}&backtest_id=${latest.id}") { cookie(me.cookie) }.andReturn().response.contentAsString)
        assertEquals(5, windowed.get("realized_curve").size())
        assertEquals(curve.get(5).get("v").asDouble(), windowed.get("realized_curve").get(0).get("v").asDouble())
        assertEquals("조회 구간 이전 체결 1건을 반영해 표시 구간 시작 시점의 초기 보유·현금을 산출했습니다(표시 구간 자체는 그대로 유지).", windowed.get("notes").get(0).asString())
    }

    @Test
    fun trackingErrors() {
        val me = Me()
        val s = me.strategy()
        mvc.get("/api/strategies/${s.id}/tracking") { cookie(me.cookie) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("해당 기간 실측 체결이 없습니다(모의투자/실거래 체결 후 이용 가능).") }
        }
        mvc.get("/api/strategies/${s.id}/tracking?date_from=2026-10-02&date_to=2026-10-01") { cookie(me.cookie) }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { value("시작일이 종료일보다 늦습니다.") }
        }
        fill(me, s, sym(), "buy", LocalDate.of(2026, 9, 1), 1, 1000.0)
        mvc.get("/api/strategies/${s.id}/tracking?date_to=2026-09-30") { cookie(me.cookie) }.andExpect {
            jsonPath("$.warning") { value("비교할 백테스트 곡선이 없습니다 — 실측 곡선만 표시합니다.") }
            jsonPath("$.notes[0]") { value(org.hamcrest.Matchers.startsWith("종가 데이터 없음(0 으로 평가):")) }
        }
        mvc.get("/api/strategies/${s.id}/tracking?date_to=2026-09-30&backtest_id=999999") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        mvc.get("/api/strategies/${s.id}/tracking?initial_capital=0") { cookie(me.cookie) }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun fillQualityReportAndCalibrationApply() {
        val me = Me()
        val s = me.strategy(mapOf("type" to "rebalance", "universe" to listOf("x"), "slippage_bps" to 5.0))
        val code = sym()
        val today = com.quantfolio.common.MarketClock.todayKst()
        val days = weekdays(today.minusDays(70), 60).filter { it.isBefore(today) }
        given(loader.load(anyString(), anyDate(), anyDate())).willAnswer { _ -> days.mapIndexed { i, d -> Bar(d.atStartOfDay().toInstant(ZoneOffset.UTC), 0.0, 0.0, 0.0, 10_000.0 + 10 * i, 1) } }
        // 결정가 대비 12bp 불리하게 체결된 매수 32건 → 중앙값 12bp, 클램프(5±3) → 8.0bp 제안
        days.drop(10).take(32).forEach { d -> fill(me, s, code, "buy", d, 1, 10_000.0 * 1.0012, decision = 10_000.0) }

        mvc.get("/api/backtest/fill-quality?strategy_id=${Me().strategy().id}") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        val rep = json.readTree(mvc.get("/api/backtest/fill-quality?strategy_id=${s.id}") { cookie(me.cookie) }.andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(32, rep.get("sample").get("n_orders").asInt())
        assertFalse(rep.has("per_order"))
        assertEquals(s.id, rep.get("window").get("strategy_id").asInt())
        assertEquals(8.0, rep.get("calibration").get("proposed_bps").asDouble())
        assertTrue(json.readTree(mvc.get("/api/backtest/fill-quality?strategy_id=${s.id}&detail=true") { cookie(me.cookie) }.andReturn().response.contentAsString).has("per_order"))

        mvc.post("/api/backtest/slippage-calibration/${s.id}/apply") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"proposed_bps":6.0}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("제안값 불일치: 최신 제안은 8.0bp 입니다(요청 6.0bp). 다시 확인 후 승인하세요.") }
        }
        mvc.post("/api/backtest/slippage-calibration/${s.id}/apply") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"proposed_bps":8.2}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.previous_bps") { value(5.0) }
            jsonPath("$.applied_bps") { value(8.0) }
            jsonPath("$.clamped") { value(true) }
        }
        assertEquals(8.0, (strategies.findById(s.id!!).get().config["slippage_bps"] as Number).toDouble())

        val fresh = me.strategy(mapOf("type" to "rebalance", "universe" to listOf("x")))
        mvc.post("/api/backtest/slippage-calibration/${fresh.id}/apply") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"proposed_bps":5.0}"""
        }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("현재 반영할 슬리피지 캘리브레이션 제안이 없습니다(표본 부족 또는 유의 변화 없음).") }
        }
    }
}
