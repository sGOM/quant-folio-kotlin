package com.quantfolio.broker

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RELEASE_LOCK_SCRIPT
import com.quantfolio.common.RedisKeys
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.util.UUID

/** 증권사 클라이언트들이 공유하는 실행 환경. sleeper 는 테스트에서 실제 대기를 없애려고 주입한다. */
class BrokerSupport(
    val http: BrokerHttp,
    val redis: StringRedisTemplate,
    val props: QuantfolioProperties,
    val clock: Clock,
    val json: JsonMapper,
    val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Redis 토큰 캐시 + 분산 락(single-flight) — 여러 전략이 동시에 기동해도 발급은 한 번(KIS 분당 1회 제한). 락 해제는 값이 내 토큰일 때만 — TTL 만료 뒤 남이 잡은 락을 지우지 않는다. */
    fun cachedToken(cacheKey: String, label: String, issue: () -> Pair<String, Int>): String {
        redis.opsForValue().get(cacheKey)?.let { return it }
        val lockKey = RedisKeys.tokenLock(cacheKey)
        val lockToken = UUID.randomUUID().toString()
        val gotLock = redis.opsForValue().setIfAbsent(lockKey, lockToken, Duration.ofSeconds(TOKEN_LOCK_TTL)) == true
        if (!gotLock) {
            repeat(TOKEN_LOCK_WAIT) {
                sleeper(TOKEN_LOCK_WAIT_STEP_MS)
                redis.opsForValue().get(cacheKey)?.let { return it }
            }
        }
        try {
            redis.opsForValue().get(cacheKey)?.let { return it }
            val (token, expiresIn) = issue()
            val ttl = maxOf(expiresIn - TOKEN_TTL_SAFETY, 60L)
            redis.opsForValue().set(cacheKey, token, Duration.ofSeconds(ttl))
            log.info("{} 토큰 신규 발급 (ttl={}s)", label, ttl)
            return token
        } finally {
            if (gotLock) redis.execute(RELEASE_LOCK_SCRIPT, listOf(lockKey), lockToken)
        }
    }

    fun parse(body: String): Map<String, Any?> = json.readValue(body)

    companion object {
        private val log = LoggerFactory.getLogger(BrokerSupport::class.java)
        private const val TOKEN_TTL_SAFETY = 60L
        private const val TOKEN_LOCK_TTL = 10L

        /** 남이 발급 중일 때 캐시를 기다리는 횟수×간격(ms) — 락 TTL 산식(`RedisKeys`)의 재료. */
        const val TOKEN_LOCK_WAIT = 8
        const val TOKEN_LOCK_WAIT_STEP_MS = 500L
    }
}

/**
 * 주문 POST 를 만들기 전 단계(토큰·hashkey)의 실패를 거절([BrokerError])로 올린다 — [block] 이 끝나기 전엔 주문 엔드포인트로
 * 아무것도 나가지 않았다. 그대로 두면 접수불명(pending·critical·그 종목 주문 보류)이 된다.
 * 인터럽트(틱 중단·엔진 종료)는 변환하지 않고 그대로 전파한다.
 */
internal inline fun <T> beforeOrderPost(block: () -> T): T = try {
    block()
} catch (e: BrokerError) {
    throw e
} catch (e: Exception) {
    if (e is InterruptedException || Thread.currentThread().isInterrupted) throw e
    throw OrderNotSent("주문 전송 전 실패: $e")
}

/**
 * 계좌 단위 호출 간격 강제(KIS 초당 한도 EGW00201 회피). 호출자에게 간격만큼 떨어진 슬롯을 순차 배정한다.
 * 프로세스 안의 버스트만 막는다 — web·engine·worker 교차 경합은 재시도가 보완.
 */
internal class RateLimiter(private val minIntervalNanos: Long) {
    private var nextAt = 0L

    fun acquire(sleeper: (Long) -> Unit) {
        val wait: Long
        synchronized(this) {
            val now = System.nanoTime()
            val slot = maxOf(now, nextAt)
            nextAt = slot + minIntervalNanos
            wait = slot - now
        }
        if (wait > 0) sleeper(wait / 1_000_000)
    }
}

internal fun Any?.decimal(): BigDecimal? = this?.toString()?.toBigDecimalOrNull()

/** 원본 `int(body.get("expires_in", 86400))` — 숫자 문자열도 받는다. */
internal fun expiresIn(data: Map<String, Any?>): Int =
    (data["expires_in"] as? Number)?.toInt() ?: data["expires_in"]?.toString()?.toInt() ?: 86400
