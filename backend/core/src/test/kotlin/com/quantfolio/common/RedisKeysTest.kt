package com.quantfolio.common

import com.quantfolio.broker.BROKER_HTTP_TIMEOUT_SEC
import com.quantfolio.broker.BrokerSupport
import com.quantfolio.broker.KisClient
import com.quantfolio.broker.TossClient
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** 원본 app/core/channels.py 와 문자열이 같아야 한다(컷오버 시 Redis 상태 호환). */
class RedisKeysTest {
    @Test
    fun redisKeysMatchPython() {
        assertEquals("engine:control", RedisKeys.ENGINE_CONTROL)
        assertEquals("engine:active_strategies", RedisKeys.ACTIVE_STRATEGIES)
        assertEquals("engine:events", RedisKeys.ENGINE_EVENTS)
        assertEquals("engine:events:7", RedisKeys.engineEvents(7))
        assertEquals("session:", RedisKeys.SESSION_PREFIX)
        assertEquals("login:fail:email:a@b.c", RedisKeys.loginFailByEmail("a@b.c"))
        assertEquals("login:fail:ip:1.2.3.4", RedisKeys.loginFailByIp("1.2.3.4"))
        assertEquals("engine:heartbeat", RedisKeys.ENGINE_HEARTBEAT)
        assertEquals("engine:health:3", RedisKeys.engineHealth(3))
        assertEquals(3, RedisKeys.FAILURE_ALERT_THRESHOLD)
        assertEquals("lock:order:", RedisKeys.ORDER_LOCK_PREFIX)
        assertEquals("lock:position:5:005930", RedisKeys.positionLock(5, "005930"))
        assertEquals("reconcile:lock:11", RedisKeys.reconcileLock(11))
        assertEquals("backup:last_success_at", RedisKeys.BACKUP_LAST_SUCCESS)
        assertEquals("rebalance:mdd:23:account", RedisKeys.mddState(23, "account"))
    }

    /** 주문·포지션 락 TTL 은 락 안 증권사 HTTP 의 코드 상한 합이다(산식은 RedisKeys). 모의 KIS 기준 산식이 토스·실전도 덮고, 원본 30초보다 짧아지지 않는지 고정한다. */
    @Test
    fun lockTtlCoversBrokerHttpBounds() {
        val http = BROKER_HTTP_TIMEOUT_SEC * 1000
        val tossRequest = TossClient.REQUEST_ATTEMPTS * http + (TossClient.MAX_RETRY_AFTER * 1000).toLong()
        val tossToken = BrokerSupport.TOKEN_LOCK_WAIT * BrokerSupport.TOKEN_LOCK_WAIT_STEP_MS + http
        val tossOrder = tossToken + 2 * tossRequest // 토스는 hashkey 없음
        assertTrue(KisClient.PROD_MIN_INTERVAL_MS <= KisClient.PAPER_MIN_INTERVAL_MS) // 실전 throttle 은 모의 이하
        assertTrue(tossOrder <= RedisKeys.ORDER_LOCK_MAX_MS)
        assertTrue(tossRequest + tossOrder <= RedisKeys.POSITION_LOCK_MAX_MS)
        assertTrue(RedisKeys.ORDER_LOCK_TTL_SECONDS * 1000 >= RedisKeys.ORDER_LOCK_MAX_MS)
        assertTrue(RedisKeys.POSITION_LOCK_TTL_SECONDS * 1000 >= RedisKeys.POSITION_LOCK_MAX_MS)
        listOf(RedisKeys.ORDER_LOCK_TTL_SECONDS, RedisKeys.POSITION_LOCK_TTL_SECONDS).forEach { assertTrue(it >= 30L) }
        assertEquals(30L, RedisKeys.RECONCILE_LOCK_TTL_SECONDS) // 정합 락 안은 DB 반영만 — 원본 channels.py 값
    }
}
