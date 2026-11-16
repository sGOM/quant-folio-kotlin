package com.quantfolio.common

import com.quantfolio.broker.BROKER_HTTP_TIMEOUT_SEC
import com.quantfolio.broker.BrokerSupport
import com.quantfolio.broker.KisClient
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.DefaultRedisScript
import java.time.Duration
import java.util.UUID

/**
 * web ↔ engine ↔ worker 간 Redis 통신 규약의 단일 소스. 문자열은 원본 Python(channels.py)과 동일해야 한다.
 * 키·채널 문자열을 다른 곳에 리터럴로 쓰지 말 것.
 */
object RedisKeys {
    /** web → engine 전략 ON/OFF. JSON `{"action":"start"|"stop","strategy_id":int}` */
    const val ENGINE_CONTROL = "engine:control"

    /** 운용 중 전략 ID SET — 엔진 재기동 복구용 */
    const val ACTIVE_STRATEGIES = "engine:active_strategies"

    /** engine → web 이벤트. 사용자별 채널은 [engineEvents]. type: order|execution|position|signal|alert */
    const val ENGINE_EVENTS = "engine:events"
    fun engineEvents(userId: Int): String = "$ENGINE_EVENTS:$userId"

    /** 로그인 세션 `session:{sid}` → user_id, 슬라이딩 TTL */
    const val SESSION_PREFIX = "session:"

    /** 로그인 실패 카운터(고정 윈도우) — 브루트포스 방어 */
    fun loginFailByEmail(email: String): String = "login:fail:email:$email"
    fun loginFailByIp(ip: String): String = "login:fail:ip:$ip"

    /** 엔진 생존 신호(TTL 키) */
    const val ENGINE_HEARTBEAT = "engine:heartbeat"

    /** 러너별 헬스 JSON */
    const val ENGINE_HEALTH_PREFIX = "engine:health:"
    fun engineHealth(strategyId: Int): String = "$ENGINE_HEALTH_PREFIX$strategyId"

    /** 러너 연속 실패가 이 횟수면 알림 1회. web 의 healthy 판정도 같은 값. */
    const val FAILURE_ALERT_THRESHOLD = 3

    // 락 TTL = 락 안 증권사 HTTP 의 "코드 상한" 합(초 올림, 원본은 모두 30초). 측정값이 아니라 상수에서 유도한 상한이다.
    // 호출 1건(KIS 모의 — throttle 간격이 가장 커 토스·실전을 덮는다, RedisKeysTest 가 확인):
    //   요청 = 시도 수 × (throttle 간격 + 응답헤더 한도) + 유량제한 백오프 합 / 토큰 발급 = 캐시 대기 + throttle + 응답헤더 한도 / hashkey = throttle + 응답헤더 한도.
    // 토큰은 락 구간당 한 번만 더한다(발급 실패 호출은 요청 대신 토큰 비용만 쓰고, 성공하면 캐시된다).
    // TTL 로 덮을 수 없어 뺀 것: 응답 본문 수신, 같은 계좌 키를 쓰는 다른 호출자 뒤의 throttle 대기, DB 잠금·커넥션 풀·Redis 명령 대기.
    private const val HTTP_MS = BROKER_HTTP_TIMEOUT_SEC * 1000L
    private const val KIS_REQUEST_MAX_MS =
        KisClient.RATE_RETRY * (KisClient.PAPER_MIN_INTERVAL_MS + HTTP_MS) + KisClient.RATE_BACKOFF_MS * (KisClient.RATE_RETRY * (KisClient.RATE_RETRY - 1) / 2)
    private const val KIS_TOKEN_MAX_MS = BrokerSupport.TOKEN_LOCK_WAIT * BrokerSupport.TOKEN_LOCK_WAIT_STEP_MS + KisClient.PAPER_MIN_INTERVAL_MS + HTTP_MS
    private const val KIS_HASHKEY_MAX_MS = KisClient.PAPER_MIN_INTERVAL_MS + HTTP_MS

    /** 주문 락 안: hashkey → (토큰) → 주문 전송 → 체결조회. */
    const val ORDER_LOCK_MAX_MS = KIS_HASHKEY_MAX_MS + KIS_TOKEN_MAX_MS + 2 * KIS_REQUEST_MAX_MS

    /** 포지션 락 안: 정지 게이트 시세 조회 → 주문 락 안의 전부. */
    const val POSITION_LOCK_MAX_MS = KIS_REQUEST_MAX_MS + ORDER_LOCK_MAX_MS

    /** 주문 멱등 분산락 `lock:order:{idempotency_key}` */
    const val ORDER_LOCK_PREFIX = "lock:order:"
    const val ORDER_LOCK_TTL_SECONDS = (ORDER_LOCK_MAX_MS + 999L) / 1000L

    /** (user, symbol) 읽기-판단-주문 직렬화 — 이중 매수/매도(TOCTOU) 방지 */
    const val POSITION_LOCK_PREFIX = "lock:position:"
    const val POSITION_LOCK_TTL_SECONDS = (POSITION_LOCK_MAX_MS + 999L) / 1000L
    fun positionLock(userId: Int, symbol: String): String = "$POSITION_LOCK_PREFIX$userId:$symbol"

    /** 주문 단위 체결 반영 직렬화 — reconcile 폴링과 실시간 체결통보의 이중 기록 방지 */
    const val RECONCILE_LOCK_PREFIX = "reconcile:lock:"
    /** 원본 값(`channels.py:68`). 락 안은 DB 반영만 — 증권사 조회는 락 밖(정합·체결통보·실행기 2차 반영 모두). */
    const val RECONCILE_LOCK_TTL_SECONDS = 30L
    fun reconcileLock(orderId: Int): String = "$RECONCILE_LOCK_PREFIX$orderId"

    /** 마지막 DB 백업 성공 시각(ISO) */
    const val BACKUP_LAST_SUCCESS = "backup:last_success_at"

    /** 전략별 MDD 킬스위치 상태 JSON `{"hwm":float,"killed":bool,"kill_date":"YYYY-MM-DD"|null}` */
    const val MDD_STATE_PREFIX = "rebalance:mdd:"
    fun mddState(strategyId: Int, accountKey: String): String = "$MDD_STATE_PREFIX$strategyId:$accountKey"

    /** 증권사 접근토큰 캐시(분당 1회 발급 제한) — 원본과 같은 키여야 컷오버 때 재발급하지 않는다. 락은 키 + ":lock". */
    fun kisToken(env: String, appKey: String): String = "kis:token:$env:$appKey"
    fun tossToken(clientId: String): String = "toss:token:$clientId"

    /** 실시간 체결가 캐시 `price:{symbol}`(TTL 120초) — 러너가 우선 쓰고 없으면 REST 폴백. */
    fun price(symbol: String): String = "price:$symbol"

    /** 리밸런싱 러너 상태: 마지막 정기 실행 시각(ISO)·직전 레짐("1"=위험회피). TTL 90일. */
    fun rebalanceLast(strategyId: Int): String = "rebalance:last:$strategyId"
    fun rebalanceRegime(strategyId: Int): String = "rebalance:regime:$strategyId"

    /** 단일종목 러너 트레일링 스탑 고점 `trail:{strategy}:{symbol}`(TTL 7일). */
    fun trail(strategyId: Int, symbol: String, accountKey: String): String = "trail:$strategyId:$symbol:$accountKey"

    /** 토큰 발급 single-flight 락 = 토큰 캐시 키 + ":lock". */
    fun tokenLock(tokenKey: String): String = "$tokenKey:lock"

    /** web 조회 캐시(원본 라우트와 같은 키). 스크리너 키의 실수는 Python str(float) 표기. */
    fun metricsCache(kind: String, market: String, asOf: java.time.LocalDate): String = "metrics:$kind:$market:$asOf"
    fun recommendCache(asOf: java.time.LocalDate): String = "recommend:kospi200:$asOf"
    fun screenerTurnaroundCache(market: String, asOf: java.time.LocalDate, topN: Int, smallcapPct: Double, surge: Double, maxDebt: Double): String =
        "screener:turnaround:$market:$asOf:$topN:${com.quantfolio.quant.pyFloat(smallcapPct)}:${com.quantfolio.quant.pyFloat(surge)}:${com.quantfolio.quant.pyFloat(maxDebt)}"
}

/**
 * Redis 분산 락(SET NX + TTL). 잡으면 [block] 결과를, 이미 누가 잡고 있으면 [busy] 결과를 돌려준다(block 미실행).
 * 해제는 block 이 예외·조기 return 으로 끝나도 항상 한다. inline 이라 두 람다 안의 `return` 은 호출 함수에서 돌아간다.
 * 락 값은 호출마다 고유 토큰이고 해제는 값이 내 토큰일 때만 한다 — block 이 TTL 보다 길어 락이 만료되고 다른 보유자가 새로 잡았을 때,
 * 늦게 끝난 쪽이 그 락을 지워 제3자가 동시에 들어오는 것을 막는다(만료 뒤 두 보유자가 겹치는 것 자체는 막지 못한다).
 */
inline fun <T> StringRedisTemplate.withLockOrElse(key: String, ttl: Duration, busy: () -> T, block: () -> T): T {
    val token = UUID.randomUUID().toString()
    if (opsForValue().setIfAbsent(key, token, ttl) != true) return busy()
    try {
        return block()
    } finally {
        execute(RELEASE_LOCK_SCRIPT, listOf(key), token)
    }
}

/** 값이 내 토큰일 때만 삭제(GET+DEL 을 Redis 안에서 원자적으로). [withLockOrElse] 가 쓴다. */
@PublishedApi
internal val RELEASE_LOCK_SCRIPT = DefaultRedisScript<Long>(
    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
    Long::class.java,
)
