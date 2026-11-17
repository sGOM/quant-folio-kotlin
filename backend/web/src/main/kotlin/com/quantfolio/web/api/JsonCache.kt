package com.quantfolio.web.api

import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Duration

/**
 * 무거운 계산 결과의 Redis JSON 캐시(원본 라우트의 `redis.get` → `model_validate_json` / `setex(model_dump_json)`).
 * 직렬화는 응답과 같은 매퍼(snake_case)라 원본 Python 이 쓴 캐시를 그대로 읽는다.
 * Redis 장애·역직렬화 실패는 캐시 미스로 본다 — 캐시 때문에 응답이 실패하지 않게(원본 metrics·recommend 와 같은 정책).
 */
@Component
class JsonCache(private val redis: StringRedisTemplate, private val json: JsonMapper) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun <T : Any> get(key: String, type: Class<T>): T? {
        val raw = try {
            redis.opsForValue().get(key)
        } catch (e: Exception) {
            log.warn("Redis get 실패 (key={}): {}", key, e.message)
            return null
        } ?: return null
        return try {
            json.readValue(raw, type)
        } catch (e: Exception) {
            log.warn("캐시 역직렬화 실패, 재계산 (key={}): {}", key, e.message)
            null
        }
    }

    fun put(key: String, value: Any, ttl: Duration) {
        try {
            redis.opsForValue().set(key, json.writeValueAsString(value), ttl)
        } catch (e: Exception) {
            log.warn("캐시 저장 실패 (key={}): {}", key, e.message)
        }
    }

    /**
     * 캐시 조회 → 미스면 계산 → [cacheable] 이면 저장, 아니면 [onSkip] 후 저장하지 않음(일시 장애가 TTL 만큼 굳지 않게).
     * 계산 예외는 그대로 전파한다 — 감싸려면 [compute] 안에서.
     */
    fun <T : Any> getOrCompute(key: String, type: Class<T>, cacheable: (T) -> Boolean, onSkip: (T) -> Unit = {}, compute: () -> T): T {
        get(key, type)?.let { return it }
        val data = compute()
        if (cacheable(data)) put(key, data, EOD_TTL) else onSkip(data)
        return data
    }

    companion object {
        /** EOD 스냅샷 캐시 수명(장 마감 후 변하지 않음). 원본 *_CACHE_TTL = 6h. */
        val EOD_TTL: Duration = Duration.ofHours(6)
    }
}
