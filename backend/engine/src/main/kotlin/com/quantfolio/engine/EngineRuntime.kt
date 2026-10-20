package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.SmartLifecycle
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 매매 엔진 프로세스(원본 `engine/main.py`) — web 과 분리돼 24시간 동작. 기동 시 live 전략 복구, Redis 제어 채널로 start/stop,
 * heartbeat(5초·TTL 15초), 체결 정합 루프(60초), 체결통보 구독 재동기화(60초). 각 전략은 가상 스레드 러너.
 * `quantfolio.engine.autostart=false` 면 수명주기로 자동 시작하지 않는다(테스트).
 */
@Component
class EngineRuntime(
    private val strategies: StrategyRepository,
    private val users: UserRepository,
    private val brokers: BrokerCredentials,
    private val ctx: RunnerContext,
    private val reconciler: Reconciler,
    private val realtime: KisRealtime,
    private val liveGate: LiveGateService,
    private val alerts: AlertPublisher,
    private val redis: StringRedisTemplate,
    private val listeners: RedisMessageListenerContainer,
    private val props: QuantfolioProperties,
    @Value("\${quantfolio.engine.autostart:true}") private val autostart: Boolean,
) : SmartLifecycle {
    private val log = LoggerFactory.getLogger("engine")
    private val json = JsonMapper.builder().build()
    private val runners = ConcurrentHashMap<Int, Pair<CountDownLatch, Thread>>()
    private val shutdown = CountDownLatch(1)
    private val loops = mutableListOf<Thread>()
    @Volatile private var running = false
    private var reconcileFails = 0
    private var noticeFails = 0

    private val control = MessageListener { message, _ -> handleControl(String(message.body)) }

    override fun isAutoStartup() = autostart

    override fun isRunning() = running

    override fun start() {
        log.info("매매 엔진 시작 — KIS_ENV={} (모의투자={})", props.kis.env, props.kis.isPaperTrading)
        running = true
        recover()
        listeners.addMessageListener(control, ChannelTopic(RedisKeys.ENGINE_CONTROL))
        log.info("제어 채널 구독: {}", RedisKeys.ENGINE_CONTROL)
        loops += loop("engine-heartbeat", 5) { redis.opsForValue().set(RedisKeys.ENGINE_HEARTBEAT, "alive", Duration.ofSeconds(15)) }
        loops += loop("engine-reconcile", 60) { reconcileOnce() }
        loops += loop("engine-fill-notice", 60) { syncNoticesGuarded() }
    }

    override fun stop() {
        log.info("정리 중 — 전략 {}개 종료", runners.size)
        shutdown.countDown()
        listeners.removeMessageListener(control)
        // 전부 먼저 신호하고 공유 마감 시각까지 기다린다 — 하나씩 10초씩 기다리면 러너가 많을 때 compose stop_grace_period 를 넘겨 SIGKILL.
        val all = runners.toMap()
        runners.clear()
        all.values.forEach { it.first.countDown() }
        val deadline = System.nanoTime() + Duration.ofSeconds(STOP_WAIT_SEC).toNanos()
        for ((sid, entry) in all) {
            val t = entry.second
            t.join(Duration.ofNanos(maxOf(0L, deadline - System.nanoTime())))
            if (t.isAlive) t.interrupt()
            redis.opsForSet().remove(RedisKeys.ACTIVE_STRATEGIES, sid.toString())
            log.info("전략 {} stop", sid)
        }
        realtime.shutdown()
        loops.forEach { it.interrupt() }
        running = false
        log.info("매매 엔진 종료")
    }

    private fun loop(name: String, everySec: Long, body: () -> Unit): Thread = Thread.ofVirtual().name(name).start {
        while (shutdown.count > 0) {
            try {
                body()
            } catch (e: Exception) {
                log.error("{} 오류", name, e)
            }
            if (shutdown.await(everySec, TimeUnit.SECONDS)) break
        }
    }

    /** 제어 메시지 `{"action": "start"|"stop", "strategy_id": n}`. 형식이 틀리면 경고만. */
    fun handleControl(raw: String) {
        val (action, sid) = try {
            val m = json.readValue<Map<String, Any?>>(raw)
            m.getValue("action").toString() to m.getValue("strategy_id").toString().toInt()
        } catch (e: Exception) {
            log.warn("잘못된 제어 메시지: {}", raw)
            return
        }
        when (action) {
            "start" -> startStrategy(sid)
            "stop" -> stopStrategy(sid)
        }
    }

    fun activeStrategies(): Set<Int> = runners.keys.toSet()

    /** 실전은 기동 전에도 게이트를 본다 — 평가 실패도 보수적으로 차단. */
    private fun liveGateAllowsStart(sid: Int): Boolean {
        if (props.kis.isPaperTrading) return true
        return try {
            val uid = strategies.findById(sid).map { it.userId }.orElse(null) ?: return false
            val gate = liveGate.evaluate(uid, sid)
            if (gate.passed) return true
            log.error("실전 게이트 차단 — 전략 {} 기동 거부: {}", sid, gate.reasons.joinToString("; "))
            alerts.publish(uid, sid, "critical", "실전 게이트 차단 — 전략 $sid 기동 거부. 사유: ${gate.reasons.joinToString("; ")}", "live_gate_blocked")
            false
        } catch (e: Exception) {
            log.error("실전 게이트 평가 실패 — 전략 {} 기동 보수적 차단", sid, e)
            false
        }
    }

    fun startStrategy(sid: Int) {
        if (runners.containsKey(sid)) return
        if (!liveGateAllowsStart(sid)) return
        val runner = if (strategies.findById(sid).map { it.config["type"] }.orElse(null) == "rebalance") RebalanceRunner(sid, ctx) else StrategyRunner(sid, ctx)
        val stop = CountDownLatch(1)
        runners[sid] = stop to Thread.ofVirtual().name("runner-$sid").start { runner.run(stop) }
        redis.opsForSet().add(RedisKeys.ACTIVE_STRATEGIES, sid.toString())
        log.info("전략 {} start", sid)
        syncFeeds()
        syncNoticesGuarded()
    }

    fun stopStrategy(sid: Int) {
        runners.remove(sid)?.let { (stop, t) ->
            stop.countDown()
            t.join(Duration.ofSeconds(10))
            if (t.isAlive) t.interrupt()
        }
        redis.opsForSet().remove(RedisKeys.ACTIVE_STRATEGIES, sid.toString())
        log.info("전략 {} stop", sid)
        syncFeeds()
        syncNoticesGuarded()
    }

    /** 기동 시 DB 의 live 전략을 다시 띄운다. */
    fun recover() {
        val ids = strategies.findAll().filter { it.status == "live" }.mapNotNull { it.id }
        ids.forEach { startStrategy(it) }
        if (ids.isNotEmpty()) log.info("운용 중 전략 복구: {}", ids)
    }

    /** KIS 사용자별로 단일종목 전략 종목을 모아 시세 피드를 맞춘다(토스는 WS 미지원 — REST 폴링). */
    private fun kisCredsByUser(): Map<Int, Pair<Pair<String, String>, Set<String>>> {
        val out = LinkedHashMap<Int, Pair<Pair<String, String>, MutableSet<String>>>()
        for (s in strategies.findAllById(runners.keys)) {
            val user = users.findById(s.userId).orElse(null) ?: continue
            val c = brokers.resolve(user) ?: continue
            if (c.broker != "kis") continue
            val entry = out.getOrPut(user.id!!) { (c.appKey to c.appSecret) to mutableSetOf() }
            s.config["symbol"]?.let { entry.second.add(it.toString()) }
        }
        return out
    }

    private fun syncFeeds() {
        if (runners.isEmpty()) {
            realtime.shutdown()
            return
        }
        for ((uid, info) in kisCredsByUser()) {
            runCatching { realtime.ensurePrices(uid, info.first.first, info.first.second, info.second) }
                .onFailure { log.error("PriceFeed ensure 실패 user={}", uid, it) }
        }
    }

    private fun syncNotices() {
        val desired = if (runners.isEmpty()) emptyMap() else kisCredsByUser()
        val current = realtime.noticeUsers()
        (current - desired.keys).forEach { realtime.removeNotices(it) }
        (desired.keys - current).forEach { uid -> desired.getValue(uid).first.let { (k, s) -> realtime.ensureNotices(uid, k, s) } }
    }

    private fun syncNoticesGuarded() {
        try {
            syncNotices()
            noticeFails = 0
        } catch (e: Exception) {
            log.error("체결통보 재동기화 루프 오류", e)
            noticeFails++
            if (noticeFails == RedisKeys.FAILURE_ALERT_THRESHOLD) {
                alerts.publish(null, 0, "critical", "체결통보 재동기화 루프 연속 ${noticeFails}회 실패 — 체결통보 구독이 어긋난 상태일 수 있음. 마지막 원인: ${e.message}", "runner_failures")
            }
        }
    }

    /** 미체결 정합 1회. 브로커 오류 건수도 실패로 센다(자격증명 만료가 조용히 넘어가지 않게). */
    fun reconcileOnce() {
        val reason = try {
            val stats = reconciler.reconcileOpenOrders()
            if (stats.getValue("filled") > 0 || stats.getValue("partial") > 0) log.info("체결 재조회 정합: {}", stats)
            if (stats.getValue("errors") > 0) "${stats.getValue("errors")}건 브로커 조회 실패(개별 주문/사용자 단위)" else null
        } catch (e: Exception) {
            log.error("체결 재조회 루프 오류", e)
            e.message ?: e.toString()
        }
        if (reason == null) {
            reconcileFails = 0
            return
        }
        reconcileFails++
        if (reconcileFails == RedisKeys.FAILURE_ALERT_THRESHOLD) {
            alerts.publish(null, 0, "critical", "체결 재조회 루프 연속 ${reconcileFails}회 실패 — 미체결 주문이 정합되지 않고 있을 수 있음. 마지막 원인: $reason", "runner_failures")
        }
    }

    private companion object {
        /** 종료 시 러너 전체를 기다리는 시간. compose engine `stop_grace_period`(60s)보다 짧아야 한다. */
        const val STOP_WAIT_SEC = 20L
    }
}
