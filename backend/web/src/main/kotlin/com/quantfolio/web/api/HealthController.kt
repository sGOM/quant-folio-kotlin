package com.quantfolio.web.api

import com.quantfolio.common.QuantfolioProperties
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class HealthController(private val redis: StringRedisTemplate, private val props: QuantfolioProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/health")
    fun health(): Map<String, Any> {
        val redisOk = runCatching { redis.connectionFactory!!.connection.use { it.ping() } == "PONG" }
            .onFailure { log.warn("Redis ping 실패: {}", it.message) }
            .getOrDefault(false)
        return mapOf(
            "status" to "ok",
            "redis" to redisOk,
            "kis_env" to props.kis.env,
            "paper_trading" to props.kis.isPaperTrading,
        )
    }
}
