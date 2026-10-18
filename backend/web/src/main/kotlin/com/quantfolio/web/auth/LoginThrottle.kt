package com.quantfolio.web.auth

import com.quantfolio.common.RedisKeys
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 로그인 브루트포스 방어. 같은 이메일·IP 로 실패가 누적되면 윈도우 동안 차단한다(Redis 고정 윈도우).
 * Redis 장애가 로그인 자체를 막으면 안 되므로 모든 연산은 실패 시 통과(fail-open)한다.
 */
@Component
class LoginThrottle(private val redis: StringRedisTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun blocked(email: String, ip: String): Boolean = runCatching {
        val byEmail = redis.opsForValue().get(RedisKeys.loginFailByEmail(email))?.toInt() ?: 0
        val byIp = redis.opsForValue().get(RedisKeys.loginFailByIp(ip))?.toInt() ?: 0
        byEmail >= LIMIT_EMAIL || byIp >= LIMIT_IP
    }.getOrElse { log.warn("로그인 스로틀 조회 실패 — 차단하지 않음: {}", it.message); false }

    fun recordFailure(email: String, ip: String) {
        runCatching {
            for (key in listOf(RedisKeys.loginFailByEmail(email), RedisKeys.loginFailByIp(ip))) {
                if (redis.opsForValue().increment(key) == 1L) redis.expire(key, WINDOW)
            }
        }.onFailure { log.warn("로그인 실패 기록 실패: {}", it.message) }
    }

    /** 성공 시 이메일 카운터만 리셋. IP 카운터는 다계정 스프레이 방어용이라 윈도우 만료에 맡긴다. */
    fun reset(email: String) {
        runCatching { redis.delete(RedisKeys.loginFailByEmail(email)) }
            .onFailure { log.warn("로그인 실패 카운터 리셋 실패: {}", it.message) }
    }

    private companion object {
        val WINDOW: Duration = Duration.ofMinutes(15)
        const val LIMIT_EMAIL = 10
        const val LIMIT_IP = 50
    }
}
