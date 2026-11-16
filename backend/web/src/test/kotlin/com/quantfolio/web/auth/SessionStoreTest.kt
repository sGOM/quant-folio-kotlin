package com.quantfolio.web.auth

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Duration
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(ContainersConfig::class)
class SessionStoreTest(
    @Autowired private val sessions: SessionStore,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val props: QuantfolioProperties,
) {
    private val ttl = props.sessionTtlMinutes * 60

    /** 내부 소유자를 [ownerId] 로 둔 세션 저장소(같은 Redis). */
    private fun ownedBy(ownerId: Int) = SessionStore(redis, props.copy(tradingOwnerUserId = ownerId))

    @Test
    fun createStoresUserIdWithTtl() {
        val sid = sessions.create(42)
        assertEquals(43, sid.length) // secrets.token_urlsafe(32) 와 같은 길이
        assertEquals("42", redis.opsForValue().get("session:$sid"))
        assertTrue(redis.getExpire("session:$sid") in (ttl - 5)..ttl)
    }

    @Test
    fun userIdRenewsTtl() {
        val sid = sessions.create(7)
        redis.expire("session:$sid", Duration.ofSeconds(10))
        assertEquals(7, ownedBy(7).userId(sid))
        assertTrue(redis.getExpire("session:$sid") > 10)
    }

    /** 내부 소유자(`TRADING_OWNER_USER_ID`)가 아닌 사용자의 세션은 없는 것으로 본다(미설정이면 전부). */
    @Test
    fun nonOwnerSessionNullWithoutRenewing() {
        val sid = sessions.create(8)
        redis.expire("session:$sid", Duration.ofSeconds(10))
        assertNull(sessions.userId(sid)) // 테스트 설정은 소유자 미설정
        assertNull(ownedBy(7).userId(sid))
        assertTrue(redis.getExpire("session:$sid") <= 10)
    }

    @Test
    fun unknownSidNull() {
        assertNull(sessions.userId("does-not-exist"))
    }

    @Test
    fun blankSidNull() {
        assertNull(sessions.userId(""))
        assertNull(sessions.userId(null))
    }

    @Test
    fun garbageValueNull() {
        redis.opsForValue().set("session:garbage", "not-an-int")
        assertNull(sessions.userId("garbage"))
    }

    @Test
    fun destroyRemoves() {
        val sid = sessions.create(1)
        sessions.destroy(sid)
        assertNull(redis.opsForValue().get("session:$sid"))
        sessions.destroy(null) // 무해
    }
}
