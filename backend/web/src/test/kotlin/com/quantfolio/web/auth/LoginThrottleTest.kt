package com.quantfolio.web.auth

import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisStandaloneConfiguration
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest
@Import(ContainersConfig::class)
class LoginThrottleTest(
    @Autowired private val throttle: LoginThrottle,
    @Autowired private val redis: StringRedisTemplate,
) {
    private fun unique() = UUID.randomUUID().toString()

    @Test
    fun blockedAtEmailLimitNotBefore() {
        val email = "${unique()}@x.y"
        val ip = unique()
        repeat(9) { throttle.recordFailure(email, ip) }
        assertFalse(throttle.blocked(email, ip))
        throttle.recordFailure(email, ip)
        assertTrue(throttle.blocked(email, ip))
    }

    @Test
    fun blockedByIpAcrossEmails() {
        val ip = unique()
        repeat(50) { throttle.recordFailure("${unique()}@x.y", ip) }
        assertTrue(throttle.blocked("fresh-${unique()}@x.y", ip))
    }

    @Test
    fun failureCounterHasWindowTtl() {
        val email = "${unique()}@x.y"
        throttle.recordFailure(email, unique())
        assertTrue(redis.getExpire("login:fail:email:$email") in 890..900)
    }

    @Test
    fun resetClearsEmailNotIp() {
        val email = "${unique()}@x.y"
        val ip = unique()
        throttle.recordFailure(email, ip)
        throttle.reset(email)
        assertNull(redis.opsForValue().get("login:fail:email:$email"))
        assertEquals("1", redis.opsForValue().get("login:fail:ip:$ip"))
    }

    @Test
    fun failOpenWhenRedisDown() {
        val dead = LettuceConnectionFactory(RedisStandaloneConfiguration("127.0.0.1", 1)).apply { afterPropertiesSet() }
        val broken = LoginThrottle(StringRedisTemplate(dead))
        assertFalse(broken.blocked("a@b.c", "1.1.1.1"))
        broken.recordFailure("a@b.c", "1.1.1.1")
        broken.reset("a@b.c")
        dead.destroy()
    }
}
