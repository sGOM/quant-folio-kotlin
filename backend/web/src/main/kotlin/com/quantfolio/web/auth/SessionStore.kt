package com.quantfolio.web.auth

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64

/**
 * 서버측 로그인 세션. 쿠키엔 추측 불가 난수(sid)만 담고 user_id 는 Redis 에 둔다 —
 * 세션을 지우면 즉시 무효화된다(JWT 와 달리 로그아웃·탈취 대응이 단순).
 * 인증이 성공할 때마다 TTL 을 갱신한다(슬라이딩 만료).
 */
@Component
class SessionStore(private val redis: StringRedisTemplate, props: QuantfolioProperties) {
    private val ttl = Duration.ofMinutes(props.sessionTtlMinutes)
    private val random = SecureRandom()

    fun create(userId: Int): String {
        val sid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        redis.opsForValue().set(key(sid), userId.toString(), ttl)
        return sid
    }

    fun userId(sid: String?): Int? {
        if (sid.isNullOrEmpty()) return null
        val raw = redis.opsForValue().get(key(sid)) ?: return null
        redis.expire(key(sid), ttl)
        return raw.toIntOrNull()
    }

    fun destroy(sid: String?) {
        if (!sid.isNullOrEmpty()) redis.delete(key(sid))
    }

    private fun key(sid: String) = RedisKeys.SESSION_PREFIX + sid
}
