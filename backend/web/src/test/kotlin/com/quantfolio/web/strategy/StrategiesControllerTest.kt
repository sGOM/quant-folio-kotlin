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
import com.quantfolio.security.CredentialCipher
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
import org.springframework.jdbc.core.JdbcTemplate
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
import kotlin.test.assertNull
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
    @Autowired private val cipher: CredentialCipher,
    @Autowired private val jdbc: JdbcTemplate,
) {
    @MockitoSpyBean lateinit var props: QuantfolioProperties
    private val json = JsonMapper.builder().build()

    private inner class Me(displayName: String? = null) {
        val user: User = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1"), displayName = displayName))
        private val session = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        /** 단일 소유자 서비스라 세션을 쓰는 사용자가 곧 설정된 소유자다(그 외 세션은 401) — 쿠키를 쓰는 순간 소유자로 지정한다. */
        val cookie: Cookie get() = session.also { willReturn(user.id).given(props).tradingOwnerUserId }
        fun strategy(name: String = "s", config: Map<String, Any?> = mapOf("type" to "sma_crossover", "symbol" to "005930"), shared: Boolean = false) =
            strategies.save(Strategy(user.id!!, name, config = config, isShared = shared, sharedAt = if (shared) Instant.now() else null))
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
            jsonPath("$.is_shared") { value(false) }
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
    fun patchReorderShareFavorite() {
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

        mvc.post("/api/strategies/${s1.id}/share") { cookie(me.cookie) }.andExpect { jsonPath("$.is_shared") { value(true) } }
        assertTrue(strategies.findById(s1.id!!).get().sharedAt != null)
        mvc.delete("/api/strategies/${s1.id}/share") { cookie(me.cookie) }.andExpect { jsonPath("$.is_shared") { value(false) } }
        assertNull(strategies.findById(s1.id!!).get().sharedAt)
        mvc.post("/api/strategies/${s1.id}/favorite") { cookie(me.cookie) }.andExpect { jsonPath("$.is_favorite") { value(true) } }
        mvc.delete("/api/strategies/${s1.id}/favorite") { cookie(me.cookie) }.andExpect { jsonPath("$.is_favorite") { value(false) } }
        mvc.post("/api/strategies/${foreign.id}/share") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun copyLikeAndFeatured() {
        val me = Me()
        val other = Me()
        val shared = other.strategy("공유", shared = true)
        val private = other.strategy("비공개")
        mvc.post("/api/strategies/${shared.id}/copy") { cookie(me.cookie) }.andExpect {
            status { isCreated() }
            jsonPath("$.name") { value("공유 (사본)") }
            jsonPath("$.status") { value("draft") }
            jsonPath("$.is_shared") { value(false) }
        }
        mvc.post("/api/strategies/${private.id}/copy") { cookie(me.cookie) }.andExpect { status { isNotFound() } }

        repeat(2) {
            mvc.post("/api/strategies/${shared.id}/like") { cookie(me.cookie) }.andExpect {
                jsonPath("$.like_count") { value(1) }
                jsonPath("$.liked_by_me") { value(true) }
            }
        }
        mvc.post("/api/strategies/${shared.id}/like") { cookie(other.cookie) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("자신의 전략에는 좋아요를 누를 수 없습니다.") }
        }
        mvc.post("/api/strategies/${private.id}/like") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        mvc.delete("/api/strategies/${shared.id}/like") { cookie(me.cookie) }.andExpect {
            jsonPath("$.like_count") { value(0) }
            jsonPath("$.liked_by_me") { value(false) }
        }

        val mine = me.strategy()
        val bt = backtests.save(Backtest(mine.id!!, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z")))
        val foreignBt = backtests.save(Backtest(shared.id!!, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-12-31T00:00:00Z")))
        mvc.put("/api/strategies/${mine.id}/featured-backtest") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"backtest_id":${foreignBt.id}}"""
        }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("백테스트를 찾을 수 없습니다.") }
        }
        mvc.put("/api/strategies/${mine.id}/featured-backtest") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{"backtest_id":${bt.id}}"""
        }.andExpect { jsonPath("$.featured_backtest_id") { value(bt.id) } }
        mvc.put("/api/strategies/${mine.id}/featured-backtest") {
            cookie(me.cookie)
            contentType = MediaType.APPLICATION_JSON
            content = """{}"""
        }.andExpect { jsonPath("$.featured_backtest_id") { value(null) } }
    }

    @Test
    fun sharedListFiltersSortsAndSummarizes() {
        val tag = UUID.randomUUID().toString().take(8)
        val me = Me()
        val alice = Me("앨리스")
        val anon = Me()
        val single = alice.strategy("$tag 단일", mapOf("type" to "sma_crossover", "symbol" to "123456"), shared = true)
        val rebal = anon.strategy("$tag 리밸", mapOf("type" to "rebalance", "universe" to listOf("123456", "654321")), shared = true)
        alice.strategy("$tag 비공개", shared = false)
        val bt = backtests.save(Backtest(single.id!!, Instant.parse("2024-01-01T00:00:00Z"), Instant.parse("2024-06-30T00:00:00Z"), totalReturn = BigDecimal("0.1234"), sharpe = BigDecimal("1.5")))
        single.featuredBacktestId = bt.id
        strategies.save(single)
        mvc.post("/api/strategies/${rebal.id}/like") { cookie(me.cookie) }
        mvc.post("/api/strategies/${rebal.id}/like") { cookie(alice.cookie) }

        val r = mvc.get("/api/strategies/shared?q=$tag") { cookie(me.cookie) }.andExpect { status { isOk() } }
        val list = body(r)
        assertEquals(listOf(rebal.id, single.id), (0 until list.size()).map { list.get(it).get("id").asInt() })
        assertEquals("익명", list.get(0).get("author_name").asString())
        assertEquals(2, list.get(0).get("like_count").asInt())
        assertTrue(list.get(0).get("liked_by_me").asBoolean())
        assertFalse(list.get(0).get("is_mine").asBoolean())
        assertTrue(list.get(0).get("backtest").isNull)
        assertEquals("앨리스", list.get(1).get("author_name").asString())
        assertEquals(0.1234, list.get(1).get("backtest").get("total_return").asDouble())
        assertTrue(list.get(1).get("backtest").get("mdd").isNull)

        val bySymbol = body(mvc.get("/api/strategies/shared?q=$tag&symbol=654321") { cookie(me.cookie) })
        assertEquals(listOf(rebal.id), (0 until bySymbol.size()).map { bySymbol.get(it).get("id").asInt() })
        val byName = body(mvc.get("/api/strategies/shared?q=$tag&sort=name") { cookie(me.cookie) })
        assertEquals(listOf(single.id, rebal.id), (0 until byName.size()).map { byName.get(it).get("id").asInt() })
        mvc.get("/api/strategies/shared?sort=bad") { cookie(me.cookie) }.andExpect { status { isUnprocessableContent() } }
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

    @Test
    fun legacyAccountChangeTriggerDisablesBalanceInferenceButKeyRotationDoesNot() {
        val me = Me()
        fun credentials(account: String, broker: String = "kis", key: String = "APPKEY", who: Me = me) {
            jdbc.update(
                "update users set broker = ?, kis_app_key = ?, kis_app_secret = ?, kis_account_no = ? where id = ?",
                broker, cipher.encrypt(key), cipher.encrypt("SECRET"), account, who.user.id,
            )
        }
        fun blocked(who: Me = me) = jdbc.queryForObject(
            "select kis_balance_reconcile_blocked from users where id = ?",
            Boolean::class.java, who.user.id,
        ) == true

        credentials("12345678-01")
        assertFalse(blocked())
        credentials("1234567801", key = "NEWKEY")
        assertFalse(blocked())
        credentials("12345678-02")
        assertTrue(blocked())
        credentials("12345678-01")
        assertTrue(blocked())

        val other = Me()
        credentials("12345678-01", who = other)
        credentials("12345678-01", broker = "toss", who = other)
        credentials("12345678-01", who = other)
        assertTrue(blocked(other))

        val withWhitespace = Me()
        credentials("\t12345678-01", who = withWhitespace)
        assertFalse(blocked(withWhitespace))
        credentials("\t12345678-02", who = withWhitespace)
        assertTrue(blocked(withWhitespace))

        val alreadyLive = Me()
        val live = alreadyLive.strategy()
        live.status = StrategyStatus.live
        strategies.saveAndFlush(live)
        credentials("12345678-01", who = alreadyLive)
        assertTrue(blocked(alreadyLive))
    }

    @Test
    fun credentialRoutesRejectWrites() {
        val me = Me()
        for (route in listOf("credentials", "toss-quote")) {
            mvc.put("/api/kis/$route") {
                cookie(me.cookie)
                contentType = MediaType.APPLICATION_JSON
                content = """{"broker":"toss","kis_app_key":"APPKEY","kis_app_secret":"SECRET","kis_account_no":"1234"}"""
            }.andExpect {
                status { isForbidden() }
                jsonPath("$.detail") { value("증권사 자격증명은 서버 설정에서 관리합니다.") }
            }
        }
        val saved = users.findById(me.user.id!!).orElseThrow()
        assertEquals("kis", saved.broker)
        assertNull(saved.kisAppKey)
        assertNull(saved.kisAppSecret)
        assertNull(saved.kisAccountNo)
        assertNull(saved.tossAppKey)
        assertNull(saved.tossAppSecret)
        assertNull(saved.tossAccountNo)
    }
}
