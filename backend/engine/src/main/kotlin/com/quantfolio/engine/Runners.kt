package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.alert.pyIsoformat
import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.common.withLockOrElse
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.live.LiveGate
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.krx.KrxIndexService
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.ohlcv.OhlcvLoader
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.metrics.UniverseScores
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.Logger
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.text.DecimalFormat
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

internal fun grouped(n: Number): String = DecimalFormat("#,##0").format(n)

/** Python `f"{x:,.0f}"`. */
internal fun won(x: Double): String = pyFixed(x, 0, grouping = true)

@Configuration(proxyBeanMethods = false)
class EngineBeans {
    /** 엔진 프로세스 전역 시장 정지 모니터(모든 러너가 관측을 모은다). */
    @Bean
    fun marketHaltMonitor() = MarketHaltMonitor()
}

/**
 * 실전 게이트 입력 조립(원본 `live_gate.evaluate_live_gate` 의 DB 부분) → 판정은 [LiveGate.decide].
 * 체결 정합 리포트는 최근 90일(KST), 금액 상한은 전략별→공통 리스크 한도와 KST 당일 체결 명목 합.
 */
@Component
class LiveGateService(
    private val reports: FillQualityReport,
    private val risk: RiskManager,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: QuantfolioProperties,
    private val clock: Clock,
    private val users: UserRepository,
) {
    /** 모의투자는 KIS 에만 있다 — 토스 사용자는 KIS 환경 설정과 무관하게 실전이다. */
    fun isPaperTrading(user: User): Boolean = user.broker.ifEmpty { "kis" } == "kis" && props.kis.isPaperTrading

    fun isPaperTrading(userId: Int): Boolean = isPaperTrading(users.findById(userId).orElseThrow())

    fun evaluate(userId: Int, strategyId: Int, orderNotional: BigDecimal? = null, paper: Boolean = isPaperTrading(userId)): LiveGate.Result {
        val today = MarketClock.todayKst(clock)
        val report = reports.report(userId, today.minusDays(90), today, strategyId)
        val order = orderNotional?.let {
            val used = jdbc.queryForObject(
                "select coalesce(sum(e.filled_qty * e.filled_price), 0) from executions e join orders o on e.order_id = o.id where o.user_id = :u and e.executed_at >= :t",
                MapSqlParameterSource("u", userId).addValue("t", Timestamp.from(today.atStartOfDay(MarketClock.KST).toInstant())), BigDecimal::class.java,
            )!!
            LiveGate.OrderCheck(it, risk.limit(userId, strategyId)?.maxPositionSize, used, props.kis.dailyOrderNotionalCap?.toDouble())
        }
        return LiveGate.decide(paper, props.kis.env, props.kis.prodApproved, report, order, strategyId)
    }
}

/** 러너들이 공유하는 의존성 묶음(러너는 전략마다 새로 만든다). 테스트는 copy 로 일부만 바꾼다. */
@Component
data class RunnerContext(
    val strategies: StrategyRepository,
    val users: UserRepository,
    val brokers: BrokerCredentials,
    val executor: OrderExecutor,
    val alerts: AlertPublisher,
    val redis: StringRedisTemplate,
    val liveGate: LiveGateService,
    val monitor: MarketHaltMonitor,
    val props: QuantfolioProperties,
    val clock: Clock,
    val risk: RiskManager,
    val calendar: MarketCalendar,
    val ticks: PriceTickStore,
    val loader: OhlcvLoader,
    val jdbc: NamedParameterJdbcTemplate,
    val snapshots: SnapshotSource,
    val common: MetricsCommon,
    val scores: UniverseScores,
    val krx: KrxIndexService,
    val positions: PositionRepository,
)

/**
 * 러너 공통 골격(원본 `engine/base_runner.py`): 전략 적재·자격 검증 → 주기 틱(타임아웃 방어) → 헬스 기록·연속 실패 알림,
 * 주문은 실전 게이트 → 거래정지/시장 CB 게이트 → 실행기 순. (사용자, 종목) 분산 락으로 읽기-판단-주문을 직렬화한다.
 */
abstract class BaseRunner(val strategyId: Int, protected val ctx: RunnerContext) {
    protected abstract val log: Logger
    protected abstract val label: String
    protected abstract val tickWord: String
    protected abstract val pollIntervalSec: Long
    protected abstract val tickTimeoutSec: Long

    protected var cfg: Map<String, Any?> = emptyMap()
    protected var userId: Int? = null
    protected lateinit var broker: BrokerClient
    protected lateinit var accountKey: String
        private set
    private var paperTrading = false
    var consecutiveFailures = 0
        private set
    private var lastSuccessTs: String? = null

    /** 전략·사용자 적재와 자격 검증. 성공하면 true(서브클래스 추가 적재는 [onLoad]). */
    fun load(): Boolean {
        val s = ctx.strategies.findById(strategyId).orElse(null)
        if (s == null) {
            log.warn("전략 {} 없음 — 실행 취소", strategyId)
            return false
        }
        val user = ctx.users.findById(s.userId).orElse(null)
        if (user == null || !ctx.brokers.has(user)) {
            log.warn("전략 {} 사용자 증권사 미등록 — 실행 취소", strategyId)
            return false
        }
        cfg = LinkedHashMap(s.config)
        userId = s.userId
        broker = ctx.brokers.forUser(user)
        accountKey = broker.orderAccountKey ?: run {
            log.warn("전략 {} 주문 계좌를 확인할 수 없음 — 실행 취소", strategyId)
            return false
        }
        paperTrading = ctx.liveGate.isPaperTrading(user)
        onLoad()
        return true
    }

    protected open fun onLoad() = Unit

    protected abstract fun logStart()

    abstract fun tickOnce()

    /** stop 될 때까지 주기 실행. 틱은 별도 가상 스레드에서 tickTimeoutSec 으로 감싼다(외부 I/O 무응답에도 러너가 멈추지 않게). */
    fun run(stop: CountDownLatch) {
        if (!load()) return
        logStart()
        Executors.newVirtualThreadPerTaskExecutor().use { pool ->
            while (stop.count > 0) {
                val abort = AtomicBoolean(false)
                val f = pool.submit {
                    tickAbort.set(abort)
                    tickOnce()
                }
                try {
                    f.get(tickTimeoutSec, TimeUnit.SECONDS)
                    recordSuccess()
                } catch (e: TimeoutException) {
                    abort.set(true) // 인터럽트는 틱 안의 catch 가 삼킬 수 있다 — 주문 직전 이 표식으로 막는다
                    f.cancel(true)
                    log.warn("{} {} {} 타임아웃({}초) — 외부 조회 지연으로 판단, 이번 틱 중단하고 다음 주기 재시도", label, strategyId, tickWord, tickTimeoutSec)
                    recordFailure("$tickWord 타임아웃(${tickTimeoutSec}초)")
                } catch (e: ExecutionException) {
                    log.error("{} {} {} 오류", label, strategyId, tickWord, e.cause)
                    recordFailure("$tickWord 오류: ${(e.cause ?: e).let { it.message ?: it.javaClass.simpleName }}") // 메시지 없는 예외도 원인이 남게
                } catch (e: InterruptedException) {
                    abort.set(true) // 엔진 종료로 중단 — 진행 중 틱은 더 주문하지 않는다
                    break
                }
                if (stop.await(pollIntervalSec, TimeUnit.SECONDS)) break
            }
        }
        log.info("{} {} 실행 종료", label, strategyId)
    }

    private fun writeHealth(lastError: String?) {
        val state = linkedMapOf<String, Any?>(
            "strategy_id" to strategyId, "user_id" to userId, "last_success_ts" to lastSuccessTs,
            "broker_account_key" to if (::accountKey.isInitialized) accountKey else null,
            "consecutive_failures" to consecutiveFailures, "last_error" to lastError, "updated_at" to pyIsoformat(MarketClock.nowKst(ctx.clock)),
        )
        runCatching { ctx.redis.opsForValue().set(RedisKeys.engineHealth(strategyId), pyJsonDumps(state), Duration.ofHours(24)) }
    }

    fun recordSuccess() {
        consecutiveFailures = 0
        lastSuccessTs = pyIsoformat(MarketClock.nowKst(ctx.clock))
        writeHealth(null)
    }

    /** 연속 실패가 임계(3)를 처음 넘는 순간에만 알림 — 계속 실패해도 재발송하지 않고, 성공으로 리셋된 뒤 다시 넘으면 재발송. */
    fun recordFailure(reason: String) {
        consecutiveFailures++
        writeHealth(reason)
        if (consecutiveFailures == RedisKeys.FAILURE_ALERT_THRESHOLD) {
            ctx.alerts.publish(userId, strategyId, AlertSeverity.critical, "$label $strategyId 연속 ${consecutiveFailures}회 실패 — 마지막 원인: $reason", "runner_failures")
        }
    }

    /** (사용자, 종목) 분산 락. 못 잡으면 block 을 실행하지 않고 false. */
    protected fun <T> withPositionLock(symbol: String, block: () -> T): Pair<Boolean, T?> {
        val key = RedisKeys.positionLock(userId!!, symbol)
        return ctx.redis.withLockOrElse(key, Duration.ofSeconds(POSITION_LOCK_TTL), busy = { false to null }) { true to block() }
    }

    /**
     * price_ticks 의 [start, end] 일봉. [minBars] 보다 적으면 외부 소스에서 적재해 다시 읽는다.
     * 적재 실패는 경고만 남기고 있는 만큼 돌려준다(`"$symbol $failLogLabel 적재 실패"`).
     */
    protected fun barsWithBackfill(symbol: String, start: Instant, end: Instant, minBars: Int, failLogLabel: String): List<Bar> {
        val bars = ctx.ticks.bars(symbol, start, end)
        if (bars.size >= minBars) return bars
        return try {
            ctx.ticks.upsert(symbol, ctx.loader.load(symbol, start.atZone(ZoneOffset.UTC).toLocalDate(), end.atZone(ZoneOffset.UTC).toLocalDate()))
            ctx.ticks.bars(symbol, start, end)
        } catch (e: Exception) {
            log.warn("{} {} 적재 실패: {}", symbol, failLogLabel, e.message)
            bars
        }
    }

    /** 이 전략에 귀속된 (사용자, 종목) 포지션. 없으면 null. */
    protected fun position(symbol: String): Position? =
        ctx.positions.findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(userId!!, accountKey, strategyId, symbol)

    protected fun holdingQty(symbol: String): BigDecimal = position(symbol)?.qty ?: BigDecimal.ZERO

    /**
     * 주문: 실전 게이트(prod 만) → 정지/CB 게이트 → 실행기(멱등 키 = 전략·종목·방향·신호봉).
     * [heldQty] 는 수량·방향을 정할 때 읽은 이 전략·종목의 보유수량 — 예약 시점 장부와 다르면 실행기가 보류한다.
     */
    protected fun place(symbol: String, side: String, qty: Int, price: BigDecimal, barTs: String, reason: String?, heldQty: BigDecimal) {
        if (tickAborted(symbol, side, qty)) return
        if (!paperTrading) {
            val gate = ctx.liveGate.evaluate(userId!!, strategyId, BigDecimal(qty) * price, paper = false)
            if (!gate.passed) {
                log.error("실전 게이트 차단 — 주문 거부 {} {} {}주: {}", symbol, side, qty, gate.reasons.joinToString("; "))
                ctx.alerts.publish(userId, strategyId, AlertSeverity.critical, "실전 게이트 차단 — $symbol $side ${grouped(qty)}주 주문 거부. 사유: ${gate.reasons.joinToString("; ")}", "live_gate_blocked")
                return
            }
        }
        if (!haltGatePassed(symbol, side, qty)) return
        if (tickAborted(symbol, side, qty)) return
        ctx.executor.execute(broker, userId!!, strategyId, symbol, side, qty, price, OrderExecutor.idempotencyKey(strategyId, symbol, side, barTs), heldQty, reason)
    }

    /**
     * 종목 정지는 그 종목만, 시장 CB 는 전체 주문을 막는다. 시세 조회 실패는 정지 여부를 "모르는" 것이라 종목 게이트는 통과
     * (조회 장애로 매매 전면 중단을 막는다)시키되 이미 판정된 시장 상태는 그대로 적용한다.
     */
    private fun haltGatePassed(symbol: String, side: String, qty: Int): Boolean {
        val now = System.nanoTime() / 1e9
        try {
            val q = broker.getQuote(symbol)
            ctx.monitor.observe(symbol, q.halted, now)
            if (q.halted) {
                log.warn("{} 거래정지(상태코드 {}) — 주문 건너뜀 {} {}주", symbol, q.statusCode.ifEmpty { "-" }, side, qty)
                ctx.monitor.evaluate(now)
                return false
            }
        } catch (e: Exception) {
            log.warn("{} 정지 여부 확인 실패(주문은 계속): {}", symbol, e.message)
        }
        val state = ctx.monitor.evaluate(now)
        if (state == MarketState.NORMAL) return true
        val (ratio, n) = ctx.monitor.haltedRatio(now)
        val name = state.name.lowercase()
        log.error("시장 {} — 주문 중단 {} {} {}주 (정지비율 {}%, 표본 {}종목)", name, symbol, side, qty, pyFixed(ratio * 100, 0), n)
        ctx.alerts.publish(
            userId, strategyId, AlertSeverity.critical,
            "시장 ${if (state == MarketState.HALTED) "서킷브레이커" else "재개 유예"} — $symbol $side ${grouped(qty)}주 주문 보류 (동시 정지 ${pyFixed(ratio * 100, 0)}%, 표본 ${n}종목)",
            "market_$name",
        )
        return false
    }

    /**
     * 타임아웃·종료로 버려진 틱인가. 원본 asyncio 는 취소(CancelledError)가 코루틴을 끝냈지만, 가상 스레드의 인터럽트는
     * 틱 안의 `catch (e: Exception)` 가 삼켜 주문까지 진행할 수 있다 — 버려진 틱의 주문은 이중 배분·정지 후 주문이 된다.
     */
    private fun tickAborted(symbol: String, side: String, qty: Int): Boolean {
        if (tickAbort.get()?.get() != true) return false
        log.warn("{} {} 틱 중단됨 — 주문 취소 {} {} {}주", label, strategyId, symbol, side, qty)
        return true
    }

    private val tickAbort = ThreadLocal<AtomicBoolean>()

    /** 이 틱이 버려졌나 — 주문뿐 아니라 "이번 주기 실행됨" 같은 상태 기록도 하지 않아야 한다(원본은 취소가 그 전에 끊는다). */
    protected val tickAbandoned: Boolean get() = tickAbort.get()?.get() == true

    private companion object {
        const val POSITION_LOCK_TTL = RedisKeys.POSITION_LOCK_TTL_SECONDS
    }
}
