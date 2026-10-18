package com.quantfolio.common

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

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
        assertEquals(30L, RedisKeys.RECONCILE_LOCK_TTL_SECONDS)
        assertEquals("backup:last_success_at", RedisKeys.BACKUP_LAST_SUCCESS)
        assertEquals("rebalance:mdd:23", RedisKeys.mddState(23))
    }
}
