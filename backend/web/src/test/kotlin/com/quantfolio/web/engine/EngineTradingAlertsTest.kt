package com.quantfolio.web.engine

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.Alert
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
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
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class EngineTradingAlertsTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val positions: PositionRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val cf: RedisConnectionFactory,
    @Autowired private val cipher: CredentialCipher,
) {
    private val json = JsonMapper.builder().build()

    private inner class Me(withKeys: Boolean = false) {
        val user: User = users.save(
            User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")).apply {
                if (withKeys) {
                    kisAppKey = cipher.encrypt("k")
                    kisAppSecret = cipher.encrypt("s")
                }
            },
        )
        val cookie = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        fun strategy(status: String = "backtested") = strategies.save(Strategy(user.id!!, "s-$status", config = mapOf("type" to "rsi"), status = status))
    }

    private fun listen(channel: String): Pair<RedisMessageListenerContainer, LinkedBlockingQueue<String>> {
        val q = LinkedBlockingQueue<String>()
        val c = RedisMessageListenerContainer().apply {
            setConnectionFactory(cf)
            addMessageListener({ m, _ -> q.add(String(m.body)) }, ChannelTopic(channel))
            afterPropertiesSet()
            start()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!c.isListening && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return c to q
    }

    @Test
    fun startNeedsCredentialsThenPublishesControl() {
        val (container, q) = listen(RedisKeys.ENGINE_CONTROL)
        try {
            val noKeys = Me()
            val s0 = noKeys.strategy()
            mvc.post("/api/engine/strategies/${s0.id}/start") { cookie(noKeys.cookie) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("증권사 자격증명을 먼저 등록하세요.") }
            }
            val me = Me(withKeys = true)
            val s = me.strategy()
            mvc.post("/api/engine/strategies/${s.id}/start") { cookie(me.cookie) }.andExpect { jsonPath("$.status") { value("live") } }
            assertEquals("live", strategies.findById(s.id!!).get().status)
            assertEquals("""{"action": "start", "strategy_id": ${s.id}}""", q.poll(5, TimeUnit.SECONDS))
            mvc.post("/api/engine/strategies/${s.id}/stop") { cookie(me.cookie) }.andExpect { jsonPath("$.status") { value("stopped") } }
            assertEquals("backtested", strategies.findById(s.id!!).get().status)
            assertEquals("""{"action": "stop", "strategy_id": ${s.id}}""", q.poll(5, TimeUnit.SECONDS))
            mvc.post("/api/engine/strategies/${Me().strategy().id}/stop") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        } finally {
            container.stop()
        }
    }

    @Test
    fun statusAndStrategyHealth() {
        val me = Me()
        redis.opsForValue().set(RedisKeys.ENGINE_HEARTBEAT, "alive")
        redis.opsForValue().set(RedisKeys.BACKUP_LAST_SUCCESS, "2026-10-18T03:00:00+00:00")
        mvc.get("/api/engine/status") { cookie(me.cookie) }.andExpect {
            jsonPath("$.engine_alive") { value(true) }
            jsonPath("$.backup_last_success_at") { value("2026-10-18T03:00:00+00:00") }
        }
        val a = me.strategy("live")
        val b = me.strategy("live")
        me.strategy("backtested")
        redis.opsForValue().set(RedisKeys.engineHealth(a.id!!), """{"last_success_ts":"t1","consecutive_failures":3,"last_error":"boom","updated_at":"u"}""")
        redis.opsForValue().set(RedisKeys.mddState(a.id!!), """{"hwm":1.05,"killed":true,"kill_date":"2026-10-01"}""")
        redis.opsForValue().set(RedisKeys.engineHealth(b.id!!), "not json")
        val body = json.readTree(mvc.get("/api/engine/strategies/health") { cookie(me.cookie) }.andReturn().response.contentAsString)
        assertEquals(RedisKeys.FAILURE_ALERT_THRESHOLD, body.get("threshold").asInt())
        val list = body.get("strategies")
        assertEquals(listOf(b.id, a.id), (0 until list.size()).map { list.get(it).get("strategy_id").asInt() })
        val ha = list.get(1)
        assertEquals(false, ha.get("healthy").asBoolean())
        assertEquals(true, ha.get("reported").asBoolean())
        assertEquals(true, ha.get("mdd_killed").asBoolean())
        assertEquals(1.05, ha.get("mdd_hwm").asDouble())
        assertEquals("live", ha.get("status").asString())
        val hb = list.get(0)
        assertEquals(false, hb.get("reported").asBoolean())
        assertEquals(true, hb.get("healthy").asBoolean())
        assertTrue(hb.get("last_success_ts").isNull)
        assertTrue(hb.get("mdd_kill_date").isNull)
    }

    @Test
    fun positionsFallBackToDbAndOrdersAreCapped() {
        val me = Me()
        positions.save(Position(me.user.id!!, null, "005930", BigDecimal("10"), BigDecimal("70000.5")))
        positions.save(Position(me.user.id!!, null, "000660", BigDecimal("0"), BigDecimal("1")))
        mvc.get("/api/trading/positions") { cookie(me.cookie) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].symbol") { value("005930") }
            jsonPath("$[0].qty") { value(10.0) }
            jsonPath("$[0].avg_price") { value(70000.5) }
        }
        val t0 = Instant.parse("2026-10-01T00:00:00Z")
        repeat(3) { i -> orders.save(Order(me.user.id!!, null, "005930", "buy", BigDecimal("1"), null, idempotencyKey = UUID.randomUUID().toString(), createdAt = t0.plusSeconds(i * 60L))) }
        mvc.get("/api/trading/orders?limit=2") { cookie(me.cookie) }.andExpect {
            jsonPath("$.length()") { value(2) }
            jsonPath("$[0].created_at") { value("2026-10-01T00:02:00Z") }
            jsonPath("$[0].price") { value(null) }
        }
    }

    @Test
    fun alertsVisibilityPagingAndRead() {
        val me = Me()
        val other = Me()
        val s = me.strategy()
        val t0 = Instant.now().minusSeconds(3600)
        val mine = alerts.save(Alert(me.user.id, s.id!!, "warning", "x", "내 알림", createdAt = t0))
        val global = alerts.save(Alert(null, s.id!!, "critical", "backup_failed", "전역", createdAt = t0.plusSeconds(10)))
        val theirs = alerts.save(Alert(other.user.id, s.id!!, "warning", null, "남의 알림", createdAt = t0.plusSeconds(20)))
        val body = json.readTree(mvc.get("/api/alerts?limit=1") { cookie(me.cookie) }.andReturn().response.contentAsString)
        assertTrue(body.get("has_more").asBoolean())
        assertTrue(body.get("unread_count").asInt() >= 2)
        mvc.post("/api/alerts/${theirs.id}/read") { cookie(me.cookie) }.andExpect {
            status { isNotFound() }
            jsonPath("$.detail") { value("알림을 찾을 수 없습니다.") }
        }
        mvc.post("/api/alerts/${mine.id}/read") { cookie(me.cookie) }.andExpect {
            jsonPath("$.is_read") { value(true) }
            jsonPath("$.user_id") { value(me.user.id) }
        }
        val unread = json.readTree(mvc.get("/api/alerts?unread_only=true&limit=200") { cookie(me.cookie) }.andReturn().response.contentAsString)
        val ids = (0 until unread.get("items").size()).map { unread.get("items").get(it).get("id").asInt() }
        assertTrue(global.id in ids && mine.id !in ids && theirs.id !in ids)
        mvc.post("/api/alerts/read-all") { cookie(me.cookie) }.andExpect { status { isOk() } }
        assertTrue(alerts.findById(global.id!!).get().isRead)
        assertEquals(false, alerts.findById(theirs.id!!).get().isRead)
        mvc.get("/api/alerts?limit=201") { cookie(me.cookie) }.andExpect { status { isUnprocessableContent() } }
    }
}
