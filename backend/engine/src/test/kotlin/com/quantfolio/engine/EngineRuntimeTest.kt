package com.quantfolio.engine

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.BDDMockito.given
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
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
        runtime.handleControl("""{"action": "stop", "strategy_id": $sid.0}""") // 원본 int(7.0) = 7
        assertFalse(sid in runtime.activeStrategies())
        assertEquals(false, redis.opsForSet().isMember(RedisKeys.ACTIVE_STRATEGIES, sid.toString()))
    }

    @Test
    fun resolveOrderMessageIsPassedToReconciler() {
        runtime.handleControl("""{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": 3, "avg_price": "71200"}""")
        verify(reconciler).resolveManually(5, "005930", 3, BigDecimal("71200"))
        runtime.handleControl("""{"action": "resolve_order", "order_id": "6", "symbol": " 000660 ", "filled_qty": 0}""") // 숫자만으로 된 문자열 허용, 체결 0 은 평균가 생략, 종목은 trim
        verify(reconciler).resolveManually(6, "000660", 0, null)
        runtime.handleControl("""{"action": "resolve_order", "order_id": 7, "symbol": "005930", "filled_qty": 1, "avg_price": 71200.5, "strategy_id": 99}""")
        verify(reconciler).resolveManually(7, "005930", 1, BigDecimal("71200.5"))
        assertFalse(99 in runtime.activeStrategies())
        // 처리 중 예외가 리스너로 새지 않는다
        given(reconciler.resolveManually(8, "005930", 0, null)).willThrow(IllegalStateException("db down"))
        runtime.handleControl("""{"action": "resolve_order", "order_id": 8, "symbol": "005930", "filled_qty": 0}""")
    }

    @Test
    fun malformedResolveOrderMessagesOnlyWarn() {
        listOf(
            """{"action": "resolve_order", "symbol": "005930", "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": "3.5", "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": 3.5, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": 3.0, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": -1, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": "-1", "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": "", "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": null}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": true, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": true, "symbol": "005930", "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": 3, "avg_price": true}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "005930", "filled_qty": 3, "avg_price": "71,200"}""",
            """{"action": "resolve_order", "order_id": 99999999999, "symbol": "005930", "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "start", "order_id": 5, "filled_qty": 3}""", // start 는 strategy_id 가 필요 — 기존대로 경고만
            """["resolve_order"]""",
        ).forEach { runtime.handleControl(it) }
        verify(reconciler, never()).resolveManually(anyInt(), anyString(), anyInt(), any())
    }

    /** symbol 은 필수이고 JSON 문자열이어야 한다 — 숫자 5930 은 앞자리 0 이 사라진 값이라 받지 않는다. */
    @Test
    fun resolveOrderWithoutAStringSymbolOnlyWarns() {
        listOf(
            """{"action": "resolve_order", "order_id": 5, "filled_qty": 3, "avg_price": "71200"}""", // 누락(옛 형식)
            """{"action": "resolve_order", "order_id": 5, "symbol": null, "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "", "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": "   ", "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": 5930, "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": 5930.0, "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": true, "filled_qty": 3, "avg_price": "71200"}""",
            """{"action": "resolve_order", "order_id": 5, "symbol": ["005930"], "filled_qty": 3, "avg_price": "71200"}""",
        ).forEach { runtime.handleControl(it) }
        verify(reconciler, never()).resolveManually(anyInt(), anyString(), anyInt(), any())
    }

    @Test
    fun tossStrategyCannotStartWithPaperKisSettings() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x", broker = "toss")).id!!
        val sid = strategies.save(Strategy(uid, "toss", config = mapOf("type" to "rsi", "symbol" to "005930"))).id!!
        try {
            runtime.startStrategy(sid)
            assertFalse(sid in runtime.activeStrategies())
            assertTrue(alerts.findAll().any { it.strategyId == sid && it.code == "live_gate_blocked" })
        } finally { runtime.stopStrategy(sid) }
    }

    @Test
    fun reconcileFailuresAlertOnceAtThreshold() {
        alerts.findAll().filter { it.userId == null && it.code == "runner_failures" }.forEach { alerts.delete(it) }
        given(reconciler.reconcileOpenOrders(null)).willReturn(mapOf("checked" to 1, "filled" to 0, "partial" to 0, "errors" to 2))
        repeat(4) { runtime.reconcileOnce() }
        val a = alerts.findAll().filter { it.userId == null && it.code == "runner_failures" }
        assertEquals(1, a.size)
        assertEquals("체결 재조회 루프 연속 3회 실패 — 미체결 주문이 정합되지 않고 있을 수 있음. 마지막 원인: 2건 체결 정합 오류(개별 주문/사용자 단위)", a.single().message)
    }

    /** 수용 기준 7: 재기동 시 DB 의 live 전략 복구 + heartbeat 키. */
    @Test
    fun startRecoversLiveStrategiesAndBeatsHeart() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val live = strategies.save(Strategy(uid, "live", config = mapOf("type" to "rsi", "symbol" to "005930"), status = StrategyStatus.live)).id!!
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
