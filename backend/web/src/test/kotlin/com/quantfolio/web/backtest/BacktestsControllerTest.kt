package com.quantfolio.web.backtest

import com.quantfolio.backtest.BacktestRunner
import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.ohlcv.OhlcvLoader
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.math.exp
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class BacktestsControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val backtests: BacktestRepository,
    @Autowired private val ticks: PriceTickStore,
) {
    @MockitoBean lateinit var loader: OhlcvLoader
    @MockitoBean lateinit var snapshots: SnapshotSource
    @MockitoBean lateinit var dart: DartFinancials

    private val json = JsonMapper.builder().build()

    private inner class Me {
        val user: User = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        val cookie = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        fun strategy(config: Map<String, Any?>) = strategies.save(Strategy(user.id!!, "s", config = config))
    }

    private fun sym() = "9" + Random.nextInt(10_000, 99_999)

    /** 평일만, UTC 자정(운영 DB 와 같은 저장 형태). */
    private fun seed(symbol: String, from: LocalDate, n: Int, seed: Int) {
        val r = Random(seed)
        var p = 10_000.0 + 5_000 * seed
        val days = generateSequence(from) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(n).toList()
        ticks.upsert(symbol, days.mapIndexed { i, d ->
            p *= exp(0.0004 * sin(i / 15.0 + seed) + (r.nextDouble() - 0.5) * 0.03)
            Bar(d.atStartOfDay().toInstant(ZoneOffset.UTC), p * 0.995, p * 1.01, p * 0.99, p, 1000L + i)
        })
    }

    private fun run(me: Me, s: Strategy, start: String, end: String) = mvc.post("/api/strategies/${s.id}/backtest") {
        cookie(me.cookie)
        contentType = MediaType.APPLICATION_JSON
        content = """{"period_start":"$start","period_end":"$end"}"""
    }

    @Test
    fun singleSymbolBacktestIsSavedAndMarksStrategyBacktested() {
        val me = Me()
        val code = sym()
        seed(code, LocalDate.of(2023, 1, 2), 260, 1)
        val s = me.strategy(mapOf("type" to "sma_crossover", "symbol" to code, "fast" to 5, "slow" to 20))
        val r = run(me, s, "2023-01-02", "2023-12-29").andExpect {
            status { isCreated() }
            jsonPath("$.strategy_id") { value(s.id) }
            jsonPath("$.period_start") { value("2023-01-01T15:00:00Z") } // KST 00:00
            jsonPath("$.period_end") { value("2023-12-29T14:59:59Z") } // KST 23:59:59
            jsonPath("$.result.equity_curve[0].t") { value("2023-01-02T00:00:00") }
        }
        val body = json.readTree(r.andReturn().response.contentAsString)
        assertEquals(body.get("result").get("total_return").asDouble(), body.get("total_return").asDouble(), 1e-6)
        assertEquals("backtested", strategies.findById(s.id!!).get().status)
        assertTrue(backtests.existsById(body.get("id").asInt()))
    }

    @Test
    fun missingDataAndBadConfigAre422() {
        val me = Me()
        val empty = me.strategy(mapOf("type" to "sma_crossover", "symbol" to sym()))
        run(me, empty, "2023-01-02", "2023-12-29").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { value("해당 기간 시세가 없습니다.") }
        }
        val code = sym()
        seed(code, LocalDate.of(2023, 1, 2), 10, 2)
        val short = me.strategy(mapOf("type" to "sma_crossover", "symbol" to code, "fast" to 5, "slow" to 20))
        run(me, short, "2023-01-02", "2023-12-29").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { value(org.hamcrest.Matchers.containsString("데이터")) }
        }
        assertEquals("draft", strategies.findById(short.id!!).get().status)
    }

    @Test
    fun rebalanceBacktestAddsFingerprint() {
        val me = Me()
        val codes = (1..4).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, LocalDate.of(2022, 1, 3), 520, 10 + k) }
        val cfg = mapOf("type" to "rebalance", "universe" to codes, "selection" to mapOf("method" to "momentum", "lookback" to 40, "top_n" to 2))
        val s = me.strategy(cfg)
        val r = run(me, s, "2023-01-02", "2023-12-29").andExpect { status { isCreated() } }
        val result = json.readTree(r.andReturn().response.contentAsString).get("result")
        assertEquals(BacktestRunner.universeFingerprint(codes, null), result.get("universe_fingerprint").asString())
        assertTrue(result.get("num_rebalances").asInt() > 0)

        val none = me.strategy(cfg + ("universe" to listOf(sym(), sym())))
        run(me, none, "2023-01-02", "2023-12-29").andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { value("universe 종목의 시세를 확보하지 못했습니다.") }
        }
    }

    @Test
    fun listGetOwnershipAndDsr() {
        val me = Me()
        val other = Me()
        val s = me.strategy(mapOf("type" to "sma_crossover", "symbol" to "005930"))
        val start = Instant.parse("2022-12-31T15:00:00Z")
        val end = Instant.parse("2023-12-29T14:59:59Z")
        val curve = (0 until 120).map { i -> mapOf("t" to "d$i", "v" to 1e7 * (1 + 0.001 * i + 0.01 * sin(i.toDouble()))) }
        val a = backtests.save(Backtest(s.id!!, start, end, result = mapOf("sharpe" to 1.2, "equity_curve" to curve)))
        val b = backtests.save(Backtest(s.id!!, start, end, result = mapOf("sharpe" to 0.4, "equity_curve" to curve.reversed())))
        val noResult = backtests.save(Backtest(s.id!!, start, end))

        mvc.get("/api/strategies/${s.id}/backtests") { cookie(me.cookie) }.andExpect {
            jsonPath("$[0].id") { value(noResult.id) }
            jsonPath("$[2].id") { value(a.id) }
        }
        mvc.get("/api/strategies/${s.id}/backtests") { cookie(other.cookie) }.andExpect { status { isNotFound() } }
        mvc.get("/api/backtests/${a.id}") { cookie(other.cookie) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("백테스트를 찾을 수 없습니다.") }
        }
        mvc.get("/api/backtests/${a.id}") { cookie(me.cookie) }.andExpect { jsonPath("$.result.sharpe") { value(1.2) } }
        mvc.get("/api/backtests/${noResult.id}/dsr") { cookie(me.cookie) }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail") { value("백테스트 결과 데이터가 없습니다.") }
        }
        mvc.get("/api/backtests/${b.id}/dsr") { cookie(me.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$.backtest_id") { value(b.id) }
            jsonPath("$.period_start") { value("2022-12-31T15:00:00Z") }
            jsonPath("$.N") { value(2) }
        }
    }
}
