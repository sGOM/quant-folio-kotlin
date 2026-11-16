package com.quantfolio.common

import com.quantfolio.domain.TestApp
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [withLockOrElse] — 자기 락만 해제하고, 만료 뒤 다른 보유자가 잡은 락은 지우지 않는다. */
@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class RedisLockTest(@Autowired private val redis: StringRedisTemplate) {
    private fun newKey() = "lock:test:" + UUID.randomUUID()

    /** TTL 이 지나 키가 사라질 때까지 기다린다(Redis 만료는 결정적이지만 지연될 수 있어 상한을 둔다). */
    private fun awaitExpired(key: String) {
        val deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos()
        while (redis.hasKey(key) == true) {
            check(System.nanoTime() < deadline) { "TTL 만료 대기 초과: $key" }
            Thread.sleep(20)
        }
    }

    @Test
    fun runsBlockAndReleasesOwnLockOnNormalCompletion() {
        val key = newKey()
        val result = redis.withLockOrElse(key, Duration.ofSeconds(30), busy = { "busy" }) {
            assertTrue(redis.hasKey(key) == true, "block 실행 중에는 락을 쥐고 있어야 한다")
            "done"
        }
        assertEquals("done", result)
        assertFalse(redis.hasKey(key) == true)
    }

    @Test
    fun releasesOwnLockWhenBlockThrows() {
        val key = newKey()
        assertThrows<IllegalStateException> {
            redis.withLockOrElse(key, Duration.ofSeconds(30), busy = { "busy" }) { throw IllegalStateException("boom") }
        }
        assertFalse(redis.hasKey(key) == true)
    }

    private fun earlyReturn(key: String): String {
        redis.withLockOrElse(key, Duration.ofSeconds(30), busy = { return "busy" }) { return "early" }
        return "late"
    }

    @Test
    fun releasesOwnLockOnReturnInsideBlock() {
        val key = newKey()
        assertEquals("early", earlyReturn(key))
        assertFalse(redis.hasKey(key) == true)
    }

    @Test
    fun busyDoesNotRunBlockAndKeepsSomeoneElsesLock() {
        val key = newKey()
        redis.opsForValue().set(key, "1", Duration.ofSeconds(30)) // 다른 프로세스가 이미 보유(테스트가 "1" 로 흉내 내는 관례)
        var ran = false
        val result = redis.withLockOrElse(key, Duration.ofSeconds(30), busy = { "busy" }) {
            ran = true
            "done"
        }
        assertEquals("busy", result)
        assertFalse(ran)
        assertEquals("1", redis.opsForValue().get(key))
        redis.delete(key)
    }

    @Test
    fun doesNotDeleteLockTakenByAnotherHolderAfterExpiry() {
        val key = newKey()
        redis.withLockOrElse(key, Duration.ofMillis(300), busy = { error("락을 잡아야 한다") }) {
            awaitExpired(key) // 내 락이 TTL 로 만료
            redis.opsForValue().set(key, "holder-B", Duration.ofSeconds(30)) // 다른 보유자 B 가 같은 키를 새로 잡음
        }
        assertEquals("holder-B", redis.opsForValue().get(key), "내 finally 가 B 의 락을 지우면 안 된다")
        redis.delete(key)
    }
}
