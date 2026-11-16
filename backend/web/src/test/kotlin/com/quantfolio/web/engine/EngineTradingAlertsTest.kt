package com.quantfolio.web.engine

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.Alert
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
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
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
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
import kotlin.test.assertNull
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
    @MockitoSpyBean lateinit var props: QuantfolioProperties

    private val json = JsonMapper.builder().build()

    private inner class Me(withKeys: Boolean = false, accountNo: String? = "50012345-01") {
        val user: User = users.save(
            User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")).apply {
                if (withKeys) {
                    kisAppKey = cipher.encrypt("k")
                    kisAppSecret = cipher.encrypt("s")
                    kisAccountNo = accountNo
                }
            },
        )
        val cookie = Cookie(SESSION_COOKIE, sessions.create(user.id!!))
        fun strategy(status: StrategyStatus = StrategyStatus.backtested) = strategies.save(Strategy(user.id!!, "s-$status", config = mapOf("type" to "rsi"), status = status))
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
    fun unauthorizedStartDoesNotChangeStatusOrPublishControl() {
        val (container, q) = listen(RedisKeys.ENGINE_CONTROL)
        try {
            val me = Me(withKeys = true)
            val owner = Me()
            val s = me.strategy()
            for (ownerId in listOf(null, owner.user.id)) {
                willReturn(ownerId).given(props).tradingOwnerUserId
                // 비소유자 세션은 세션 조회 단계에서 401(소유자 미설정이면 모든 세션).
                mvc.post("/api/engine/strategies/${s.id}/start") { cookie(me.cookie) }.andExpect { status { isUnauthorized() } }
                assertEquals(StrategyStatus.backtested, strategies.findById(s.id!!).orElseThrow().status)
                assertNull(q.poll(250, TimeUnit.MILLISECONDS))
            }
        } finally {
            container.stop()
        }
    }

    @Test
    fun startNeedsCredentialsThenPublishesControl() {
        val (container, q) = listen(RedisKeys.ENGINE_CONTROL)
        try {
            val noKeys = Me()
            willReturn(noKeys.user.id).given(props).tradingOwnerUserId
            val s0 = noKeys.strategy()
            mvc.post("/api/engine/strategies/${s0.id}/start") { cookie(noKeys.cookie) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("증권사 API 자격증명이 서버에 설정되지 않았습니다.") }
            }
            val noAccount = Me()
            willReturn(noAccount.user.id).given(props).tradingOwnerUserId
            willReturn(QuantfolioProperties.Kis(appKey = "test-key", appSecret = "test-secret")).given(props).kis
            val withoutAccount = noAccount.strategy()
            mvc.post("/api/engine/strategies/${withoutAccount.id}/start") { cookie(noAccount.cookie) }.andExpect {
                status { isBadRequest() }
                jsonPath("$.detail") { value("주문 계좌를 확인할 수 없습니다.") }
            }
            assertEquals(StrategyStatus.backtested, strategies.findById(withoutAccount.id!!).orElseThrow().status)

            val me = Me()
            willReturn(me.user.id).given(props).tradingOwnerUserId
            willReturn(QuantfolioProperties.Kis(appKey = "test-key", appSecret = "test-secret", accountNo = "50012345-01")).given(props).kis
            val s = me.strategy()
            mvc.post("/api/engine/strategies/${s.id}/start") { cookie(me.cookie) }.andExpect { jsonPath("$.status") { value("live") } }
            assertEquals(StrategyStatus.live, strategies.findById(s.id!!).get().status)
            assertEquals("""{"action": "start", "strategy_id": ${s.id}}""", q.poll(5, TimeUnit.SECONDS))
            mvc.post("/api/engine/strategies/${s.id}/stop") { cookie(me.cookie) }.andExpect { jsonPath("$.status") { value("stopped") } }
            assertEquals(StrategyStatus.backtested, strategies.findById(s.id!!).get().status)
            assertEquals("""{"action": "stop", "strategy_id": ${s.id}}""", q.poll(5, TimeUnit.SECONDS))
            mvc.post("/api/engine/strategies/${Me().strategy().id}/stop") { cookie(me.cookie) }.andExpect { status { isNotFound() } }
        } finally {
            container.stop()
        }
    }

    @Test
    fun userProfileShowsServerBrokerStateOnlyToOwner() {
        val owner = Me(withKeys = true, accountNo = "12345678-02")
        val other = Me(withKeys = true)
        willReturn(owner.user.id).given(props).tradingOwnerUserId
        willReturn(QuantfolioProperties.Kis(appKey = "test-key", appSecret = "test-secret", accountNo = "50012345-01")).given(props).kis
        willReturn(QuantfolioProperties.Toss(appKey = "test-toss-key", appSecret = "test-toss-secret")).given(props).toss

        mvc.get("/api/auth/me") { cookie(owner.cookie) }.andExpect {
            jsonPath("$.kis_account_no") { value("50012345-01") }
            jsonPath("$.has_kis_credentials") { value(true) }
            jsonPath("$.has_toss_quote") { value(true) }
        }
        // 비소유자는 기존 세션으로도 들어올 수 없다 — 소유자 계좌·잔고 응답 자체가 없다.
        mvc.get("/api/auth/me") { cookie(other.cookie) }.andExpect { status { isUnauthorized() } }
        mvc.get("/api/trading/positions") { cookie(other.cookie) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun statusAndStrategyHealth() {
        val me = Me()
        willReturn(me.user.id).given(props).tradingOwnerUserId
        redis.opsForValue().set(RedisKeys.ENGINE_HEARTBEAT, "alive")
        redis.opsForValue().set(RedisKeys.BACKUP_LAST_SUCCESS, "2026-10-18T03:00:00+00:00")
        mvc.get("/api/engine/status") { cookie(me.cookie) }.andExpect {
            jsonPath("$.engine_alive") { value(true) }
            jsonPath("$.backup_last_success_at") { value("2026-10-18T03:00:00+00:00") }
        }
        val a = me.strategy(StrategyStatus.live)
        val b = me.strategy(StrategyStatus.live)
        me.strategy(StrategyStatus.backtested)
        redis.opsForValue().set(RedisKeys.engineHealth(a.id!!), """{"last_success_ts":"t1","consecutive_failures":3,"last_error":"boom","updated_at":"u","broker_account_key":"running-account"}""")
        redis.opsForValue().set(RedisKeys.mddState(a.id!!, "running-account"), """{"hwm":1.05,"killed":true,"kill_date":"2026-10-01"}""")
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
    fun unknownAccountPositionsAreHiddenAndOrdersAreCapped() {
        val me = Me()
        willReturn(me.user.id).given(props).tradingOwnerUserId
        positions.save(Position(me.user.id!!, null, "005930", BigDecimal("10"), BigDecimal("70000.5")))
        positions.save(Position(me.user.id!!, null, "000660", BigDecimal("0"), BigDecimal("1")))
        mvc.get("/api/trading/positions") { cookie(me.cookie) }.andExpect {
            jsonPath("$.length()") { value(0) }
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
        willReturn(me.user.id).given(props).tradingOwnerUserId
        val s = me.strategy()
        val t0 = Instant.now().minusSeconds(3600)
        val mine = alerts.save(Alert(me.user.id, s.id!!, AlertSeverity.warning, "x", "내 알림", createdAt = t0))
        val global = alerts.save(Alert(null, s.id!!, AlertSeverity.critical, "backup_failed", "전역", createdAt = t0.plusSeconds(10)))
        val theirs = alerts.save(Alert(other.user.id, s.id!!, AlertSeverity.warning, null, "남의 알림", createdAt = t0.plusSeconds(20)))
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
