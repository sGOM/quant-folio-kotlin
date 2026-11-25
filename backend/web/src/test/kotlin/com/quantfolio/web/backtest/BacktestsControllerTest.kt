package com.quantfolio.web.backtest

import com.quantfolio.backtest.BacktestRunner
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.argThat
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
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
    @Autowired private val clock: Clock,
) {
    @MockitoBean lateinit var coverage: LocalOhlcvCoverage // 로컬 시세 커버리지 점검은 통과(적재는 seed 가 한다)
    @Qualifier("local") @MockitoBean lateinit var snapshots: SnapshotSource
    @MockitoBean lateinit var dart: DartFinancials
    @MockitoBean lateinit var sectorMaps: com.quantfolio.market.official.LocalSectorMap
    @MockitoSpyBean lateinit var props: QuantfolioProperties

    private val json = JsonMapper.builder().build()

    private inner class Me {
        val user: User = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        private val session = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        /** 단일 소유자 서비스라 세션을 쓰는 사용자가 곧 설정된 소유자다(그 외 세션은 401) — 쿠키를 쓰는 순간 소유자로 지정한다. */
        val cookie: Cookie get() = session.also { willReturn(user.id).given(props).tradingOwnerUserId }
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
            jsonPath("$.result.equity_curve[0].t") { value("2023-01-02T00:00:00+00:00") } // 원본 DB 의 단일종목 결과와 같은 형식
        }
        val body = json.readTree(r.andReturn().response.contentAsString)
        assertEquals(body.get("result").get("total_return").asDouble(), body.get("total_return").asDouble(), 1e-6)
        assertEquals(StrategyStatus.backtested, strategies.findById(s.id!!).get().status)
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
        assertEquals(StrategyStatus.draft, strategies.findById(short.id!!).get().status)
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

    private fun dateThat(test: (LocalDate) -> Boolean): LocalDate {
        org.mockito.ArgumentMatchers.argThat<LocalDate> { test(it) }
        return LocalDate.MIN
    }

    /** Mockito any() 는 null 을 돌려줘 비널 인자에 못 쓴다. */
    private fun anyDate(): LocalDate {
        org.mockito.ArgumentMatchers.any(LocalDate::class.java)
        return LocalDate.MIN
    }

    /** 원본 `_with_sector_map`: max_sector_pct 가 있을 때만 업종 매핑을 조회해 섹터 캡을 건다. */
    @Test
    fun sectorCapLooksUpSectorMapOnlyWhenLimitSet() {
        val me = Me()
        val codes = (1..4).map { sym() }
        codes.forEachIndexed { k, c -> seed(c, LocalDate.of(2022, 1, 3), 520, 20 + k) }
        val base = mapOf("type" to "rebalance", "universe" to codes, "selection" to mapOf("method" to "momentum", "lookback" to 40, "top_n" to 2))
        val capped = base + ("risk_layer" to mapOf("max_sector_pct" to 0.3))
        fun invested(cfg: Map<String, Any?>): Double {
            val r = run(me, me.strategy(cfg), "2023-01-02", "2023-12-29").andExpect { status { isCreated() } }
            val holdings = json.readTree(r.andReturn().response.contentAsString).get("result").get("holdings")
            return holdings.properties().sumOf { it.value.asDouble() }
        }

        val uncapped = invested(base)
        verify(sectorMaps, never()).sectorMap(anyDate())
        assertTrue(uncapped > 0.9, "캡 없으면 거의 전액 투자: $uncapped")

        given(sectorMaps.sectorMap(anyDate())).willReturn(codes.associateWith { "전기전자" })
        val before = MarketClock.todayUtcLegacy(clock)
        val withCap = invested(capped)
        val after = MarketClock.todayUtcLegacy(clock)
        verify(sectorMaps).sectorMap(dateThat { it == before || it == after }) // 원본 date.today()(UTC)
        assertTrue(withCap < 0.5, "한 업종 30% 상한이면 나머지는 현금: $withCap")

        // 업종 스냅샷 미적재는 캡을 조용히 빼지 않고 409 로 멈춘다(로컬 전용 — 원격 조회 실패라는 사건이 없다).
        given(sectorMaps.sectorMap(anyDate())).willThrow(MarketDataNotLoaded("sector_map", "2026-01-01"))
        run(me, me.strategy(capped), "2023-01-02", "2023-12-29").andExpect { status { isConflict() } }
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
            // 원본 DSR 라우트는 응답 모델 없이 jsonable_encoder → isoformat(+00:00). BacktestOut(응답 모델)의 Z 와 다르다
            jsonPath("$.period_start") { value("2022-12-31T15:00:00+00:00") }
            jsonPath("$.period_end") { value("2023-12-29T14:59:59+00:00") }
            jsonPath("$.N") { value(2) }
            jsonPath("$.pbo") { value(org.hamcrest.Matchers.nullValue()) } // 날짜 아닌 곡선 → 공통 월 없음 → null(0 아님)
            jsonPath("$.min_trl") { value(com.quantfolio.backtest.DeflatedSharpe.minTrl(b.result!!)!!) }
        }
    }
}
