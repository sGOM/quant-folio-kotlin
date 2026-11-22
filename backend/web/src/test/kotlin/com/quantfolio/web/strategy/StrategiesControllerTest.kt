package com.quantfolio.web.strategy

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class StrategiesControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val backtests: BacktestRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val redis: StringRedisTemplate,
) {
    @MockitoSpyBean lateinit var props: QuantfolioProperties
    private val json = JsonMapper.builder().build()

    private inner class Me {
        val user: User = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        private val session = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        /** 단일 소유자 서비스라 세션을 쓰는 사용자가 곧 설정된 소유자다(그 외 세션은 401) — 쿠키를 쓰는 순간 소유자로 지정한다. */
        val cookie: Cookie get() = session.also { willReturn(user.id).given(props).tradingOwnerUserId }
        fun strategy(name: String = "s", config: Map<String, Any?> = mapOf("type" to "sma_crossover", "symbol" to "005930")) =
            strategies.save(Strategy(user.id!!, name, config = config))
    }

    private fun body(r: org.springframework.test.web.servlet.ResultActionsDsl) = json.readTree(r.andReturn().response.contentAsString)

    @Test
    fun createDumpsDefaultsAndValidatesConfig() {
        val me = Me()
        val r = mvc.post("/api/strategies") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"골든크로스","description":"","config":{"type":"sma_crossover","symbol":"005930"}}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.status") { value("draft") }
            jsonPath("$.description") { value(null) }
            jsonPath("$.config.fast") { value(5) }
        }
        assertTrue(body(r).get("updated_at").isString)

        mvc.post("/api/strategies") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"x","config":{"type":"sma_crossover","symbol":"005930","fast":0}}"""
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].loc[0]") { value("body") }
            jsonPath("$.detail[0].loc[1]") { value("config") }
            jsonPath("$.detail[0].loc[2]") { value("sma_crossover") }
            jsonPath("$.detail[0].loc[3]") { value("fast") }
        }
        mvc.post("/api/strategies") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"x","config":{"type":"nope"}}"""
        }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].type") { value("union_tag_invalid") }
        }
        mvc.post("/api/strategies") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"","config":{"type":"sma_crossover","symbol":"005930"}}"""
        }.andExpect { status { isUnprocessableContent() } }
    }

    /** 저장 응답의 config 는 DB 에서 다시 읽은 jsonb 순서(원본 db.refresh) — 다음 GET 과 텍스트가 같아야 한다. */
    @Test
    fun saveResponseConfigKeyOrderMatchesGet() {
        val me = Me()
        fun keys(raw: String): List<String> {
            @Suppress("UNCHECKED_CAST")
            val cfg = json.readValue(raw, LinkedHashMap::class.java)["config"] as Map<String, Any?>
            return cfg.keys.toList()
        }
        val created = mvc.post("/api/strategies") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"name":"순서","config":{"type":"sma_crossover","symbol":"005930"}}"""
        }.andReturn().response.contentAsString
        val id = json.readTree(created).get("id").asInt()
        val got = mvc.get("/api/strategies/$id") { cookie(me.cookie) }.andReturn().response.contentAsString
        kotlin.test.assertEquals(keys(got), keys(created))
    }

    @Test
    fun listOrderAndOwnershipIsolation() {
        val me = Me()
        val other = Me()
        val a = me.strategy("a")
        val b = me.strategy("b").also { it.isFavorite = true; strategies.save(it) }
        val c = me.strategy("c")
        other.strategy("x")
        val r = mvc.get("/api/strategies") { cookie(me.cookie) }.andExpect { status { isOk() } }
        val ids = body(r).let { n -> (0 until n.size()).map { n.get(it).get("id").asInt() } }
        assertEquals(listOf(b.id, c.id, a.id), ids)
        mvc.get("/api/strategies/${other.strategy().id}") { cookie(me.cookie) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("전략을 찾을 수 없습니다.") }
        }
    }

    @Test
    fun patchReorderFavorite() {
        val me = Me()
        val other = Me()
        val s1 = me.strategy("one")
        val s2 = me.strategy("two")
        val foreign = other.strategy()
        mvc.patch("/api/strategies/${s1.id}") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"description":"   ","config":{"type":"rsi","symbol":"000660"}}"""
        }.andExpect {
            jsonPath("$.name") { value("one") }
            jsonPath("$.description") { value(null) }
            jsonPath("$.config.period") { value(14) }
        }
        mvc.patch("/api/strategies/reorder") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ordered_ids":[${s2.id},${foreign.id},${s1.id}]}"""
        }.andExpect { status { isNoContent() } }
        assertEquals(0, strategies.findById(s2.id!!).get().sortOrder)
        assertEquals(2, strategies.findById(s1.id!!).get().sortOrder)
        assertEquals(0, strategies.findById(foreign.id!!).get().sortOrder)
        mvc.patch("/api/strategies/reorder") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"ordered_ids":[]}"""
        }.andExpect { status { isUnprocessableContent() } }

        mvc.post("/api/strategies/${s1.id}/favorite") { cookie(me.cookie) }.andExpect { jsonPath("$.is_favorite") { value(true) } }
        mvc.delete("/api/strategies/${s1.id}/favorite") { cookie(me.cookie) }.andExpect { jsonPath("$.is_favorite") { value(false) } }
        mvc.post("/api/strategies/${foreign.id}/favorite") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun credentialRegistrationEndpointsAreGone() {
        val me = Me()
        for (path in listOf("/api/kis/credentials", "/api/kis/toss-quote")) {
            mvc.put(path) {
                cookie(me.cookie)
                contentType = MediaType.APPLICATION_JSON
                content = """{"app_key":"k","app_secret":"s","account_no":"1"}"""
            }.andExpect { status { isNotFound() } }
        }
    }

    /** 공유·좋아요·복사·대표 백테스트 API 는 삭제됐다. 응답에도 공유·대표 백테스트 필드가 없다. */
    @Test
    fun sharingEndpointsAreGone() {
        val me = Me()
        val s = me.strategy()
        val base = "/api/strategies/${s.id}"
        for (path in listOf("$base/share", "$base/copy", "$base/like")) {
            mvc.post(path) { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        }
        for (path in listOf("$base/share", "$base/like")) {
            mvc.delete(path) { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        }
        mvc.put("$base/featured-backtest") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"backtest_id":null}"""
        }.andExpect { status { isNotFound() } }
        // "shared" 는 이제 GET /{id} 에 걸려 정수 변환 실패(422)가 된다 — 목록이 아니다.
        mvc.get("/api/strategies/shared") { cookie(me.cookie) }.andExpect { status { isUnprocessableContent() } }
        mvc.get(base) { cookie(me.cookie) }.andExpect {
            status { isOk() }
            jsonPath("$.is_shared") { doesNotExist() }
            jsonPath("$.featured_backtest_id") { doesNotExist() }
        }
    }

    @Test
    fun deleteGuardsLiveActiveAndOpenPositions() {
        val me = Me()
        val live = me.strategy().also { it.status = StrategyStatus.live; strategies.save(it) }
        mvc.delete("/api/strategies/${live.id}") { cookie(me.cookie) }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("가동 중인 전략은 삭제할 수 없습니다. 먼저 중지하세요.") }
        }
        val active = me.strategy()
        redis.opsForSet().add(RedisKeys.ACTIVE_STRATEGIES, active.id.toString())
        mvc.delete("/api/strategies/${active.id}") { cookie(me.cookie) }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("엔진에서 실행 중인 전략입니다. 먼저 중지하세요.") }
        }
        redis.opsForSet().remove(RedisKeys.ACTIVE_STRATEGIES, active.id.toString())
        val held = me.strategy()
        positions.save(Position(me.user.id!!, held.id, "005930", qty = BigDecimal("3")))
        mvc.delete("/api/strategies/${held.id}") { cookie(me.cookie) }.andExpect {
            status { isConflict() }
            jsonPath("$.detail") { value("미청산 포지션이 있어 삭제할 수 없습니다. 먼저 청산하거나 다른 전략으로 이관하세요.") }
        }
        val ok = me.strategy()
        val bt = backtests.save(Backtest(ok.id!!, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z")))
        mvc.delete("/api/strategies/${ok.id}") { cookie(me.cookie) }.andExpect { status { isNoContent() } }
        assertFalse(strategies.existsById(ok.id!!))
        assertFalse(backtests.existsById(bt.id!!)) // DB FK CASCADE
    }
}
