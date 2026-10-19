package com.quantfolio.engine

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 엔진 프로세스(원본 `engine/main.py`) — 제어 메시지·활성 집합·정합 루프 실패 경보. */
@SpringBootTest
@Import(ContainersConfig::class)
class EngineRuntimeTest(
    @Autowired private val runtime: EngineRuntime,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Value("\${quantfolio.engine.autostart}") private val autostart: Boolean,
) {
    @MockitoBean lateinit var reconciler: Reconciler

    @Test
    fun testsNeverAutostartTheEngine() {
        assertFalse(autostart)
        assertFalse(runtime.isRunning)
    }

    @Test
    fun controlMessagesStartAndStopRunners() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi", "symbol" to "005930"))).id!!
        runtime.handleControl("""{"action": "start", "strategy_id": $sid}""")
        assertTrue(sid in runtime.activeStrategies())
        assertEquals(true, redis.opsForSet().isMember(RedisKeys.ACTIVE_STRATEGIES, sid.toString()))
        runtime.handleControl("""{"action": "start", "strategy_id": $sid}""") // 중복 start 는 무시
        assertEquals(1, runtime.activeStrategies().count { it == sid })
        runtime.handleControl("not json")
        runtime.handleControl("""{"action": "start"}""")
        runtime.handleControl("""{"action": "stop", "strategy_id": "$sid"}""")
        assertFalse(sid in runtime.activeStrategies())
        assertEquals(false, redis.opsForSet().isMember(RedisKeys.ACTIVE_STRATEGIES, sid.toString()))
    }

    @Test
    fun reconcileFailuresAlertOnceAtThreshold() {
        alerts.findAll().filter { it.userId == null && it.code == "runner_failures" }.forEach { alerts.delete(it) }
        given(reconciler.reconcileOpenOrders(null)).willReturn(mapOf("checked" to 1, "filled" to 0, "partial" to 0, "errors" to 2))
        repeat(4) { runtime.reconcileOnce() }
        val a = alerts.findAll().filter { it.userId == null && it.code == "runner_failures" }
        assertEquals(1, a.size)
        assertEquals("체결 재조회 루프 연속 3회 실패 — 미체결 주문이 정합되지 않고 있을 수 있음. 마지막 원인: 2건 브로커 조회 실패(개별 주문/사용자 단위)", a.single().message)
    }

    /** 수용 기준 7: 재기동 시 DB 의 live 전략 복구 + heartbeat 키. */
    @Test
    fun startRecoversLiveStrategiesAndBeatsHeart() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val live = strategies.save(Strategy(uid, "live", config = mapOf("type" to "rsi", "symbol" to "005930"), status = "live")).id!!
        val draft = strategies.save(Strategy(uid, "draft", config = mapOf("type" to "rsi", "symbol" to "005930"))).id!!
        redis.delete(RedisKeys.ENGINE_HEARTBEAT)
        runtime.start()
        try {
            assertTrue(live in runtime.activeStrategies())
            assertFalse(draft in runtime.activeStrategies())
            val deadline = System.currentTimeMillis() + 5_000
            while (redis.opsForValue().get(RedisKeys.ENGINE_HEARTBEAT) == null && System.currentTimeMillis() < deadline) Thread.sleep(50)
            assertEquals("alive", redis.opsForValue().get(RedisKeys.ENGINE_HEARTBEAT))
        } finally {
            runtime.stop()
        }
        assertTrue(runtime.activeStrategies().isEmpty())
    }
}
