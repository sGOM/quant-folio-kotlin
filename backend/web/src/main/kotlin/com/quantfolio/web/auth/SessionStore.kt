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
class SessionStore(private val redis: StringRedisTemplate, private val props: QuantfolioProperties) {
    private val ttl = Duration.ofMinutes(props.sessionTtlMinutes)
    private val random = SecureRandom()

    fun create(userId: Int): String {
        val sid = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32).also { random.nextBytes(it) })
        redis.opsForValue().set(key(sid), userId.toString(), ttl)
        return sid
    }

    /**
     * 세션의 user_id. HTTP(`CurrentUserResolver`)·WebSocket(`EventSocket`) 인증이 함께 쓰는 단일 지점이라,
     * 내부 소유자(`TRADING_OWNER_USER_ID`)가 아닌 사용자의 세션은 여기서 없는 것으로 본다(미설정이면 전부). 갱신도 하지 않는다.
     */
    fun userId(sid: String?): Int? {
        if (sid.isNullOrEmpty()) return null
        val userId = redis.opsForValue().get(key(sid))?.toIntOrNull() ?: return null
        if (userId != props.tradingOwnerUserId) return null
        redis.expire(key(sid), ttl)
        return userId
    }

    fun destroy(sid: String?) {
        if (!sid.isNullOrEmpty()) redis.delete(key(sid))
    }

    private fun key(sid: String) = RedisKeys.SESSION_PREFIX + sid
}
