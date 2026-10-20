package com.quantfolio.common

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

    /** 주문 멱등 분산락 `lock:order:{idempotency_key}` */
    const val ORDER_LOCK_PREFIX = "lock:order:"

    /** (user, symbol) 읽기-판단-주문 직렬화 — 이중 매수/매도(TOCTOU) 방지 */
    const val POSITION_LOCK_PREFIX = "lock:position:"
    fun positionLock(userId: Int, symbol: String): String = "$POSITION_LOCK_PREFIX$userId:$symbol"

    /** 주문 단위 체결 반영 직렬화 — reconcile 폴링과 실시간 체결통보의 이중 기록 방지 */
    const val RECONCILE_LOCK_PREFIX = "reconcile:lock:"
    const val RECONCILE_LOCK_TTL_SECONDS = 30L
    fun reconcileLock(orderId: Int): String = "$RECONCILE_LOCK_PREFIX$orderId"

    /** 마지막 DB 백업 성공 시각(ISO) */
    const val BACKUP_LAST_SUCCESS = "backup:last_success_at"

    /** 전략별 MDD 킬스위치 상태 JSON `{"hwm":float,"killed":bool,"kill_date":"YYYY-MM-DD"|null}` */
    const val MDD_STATE_PREFIX = "rebalance:mdd:"
    fun mddState(strategyId: Int): String = "$MDD_STATE_PREFIX$strategyId"

    /** 증권사 접근토큰 캐시(분당 1회 발급 제한) — 원본과 같은 키여야 컷오버 때 재발급하지 않는다. 락은 키 + ":lock". */
    fun kisToken(env: String, appKey: String): String = "kis:token:$env:$appKey"
    fun tossToken(clientId: String): String = "toss:token:$clientId"

    /** 실시간 체결가 캐시 `price:{symbol}`(TTL 120초) — 러너가 우선 쓰고 없으면 REST 폴백. */
    fun price(symbol: String): String = "price:$symbol"

    /** 리밸런싱 러너 상태: 마지막 정기 실행 시각(ISO)·직전 레짐("1"=위험회피). TTL 90일. */
    fun rebalanceLast(strategyId: Int): String = "rebalance:last:$strategyId"
    fun rebalanceRegime(strategyId: Int): String = "rebalance:regime:$strategyId"

    /** 단일종목 러너 트레일링 스탑 고점 `trail:{strategy}:{symbol}`(TTL 7일). */
    fun trail(strategyId: Int, symbol: String): String = "trail:$strategyId:$symbol"

    /** 토큰 발급 single-flight 락 = 토큰 캐시 키 + ":lock". */
    fun tokenLock(tokenKey: String): String = "$tokenKey:lock"

    /** web 조회 캐시(원본 라우트와 같은 키). 스크리너 키의 실수는 Python str(float) 표기. */
    fun metricsCache(kind: String, market: String, asOf: java.time.LocalDate): String = "metrics:$kind:$market:$asOf"
    fun recommendCache(asOf: java.time.LocalDate): String = "recommend:kospi200:$asOf"
    fun screenerTurnaroundCache(market: String, asOf: java.time.LocalDate, topN: Int, smallcapPct: Double, surge: Double, maxDebt: Double): String =
        "screener:turnaround:$market:$asOf:$topN:${com.quantfolio.quant.pyFloat(smallcapPct)}:${com.quantfolio.quant.pyFloat(surge)}:${com.quantfolio.quant.pyFloat(maxDebt)}"
}
