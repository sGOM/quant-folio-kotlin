package com.quantfolio.web.engine

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.quant.pyJsonDumps
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue

/**
 * 엔진 제어·상태(원본 `routes/engine.py`). 실제 매매는 분리된 엔진 프로세스가 하고, web 은 Redis 로 명령만 보낸다.
 */
@RestController
@RequestMapping("/api/engine")
class EngineController(
    private val strategies: StrategyRepository,
    private val credentials: BrokerCredentials,
    private val redis: StringRedisTemplate,
    private val json: JsonMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 메시지 문자열은 원본 `json.dumps` 그대로(엔진이 파싱). */
    private fun control(action: String, id: Int) =
        redis.convertAndSend(RedisKeys.ENGINE_CONTROL, pyJsonDumps(linkedMapOf("action" to action, "strategy_id" to id)))

    @PostMapping("/strategies/{id}/start")
    fun start(@CurrentUser user: User, @PathVariable id: Int): Map<String, String> {
        val s = strategies.owned(user, id)
        if (!credentials.isOwner(user.id)) throw ApiException(HttpStatus.FORBIDDEN, "지정된 거래 소유자만 전략을 시작할 수 있습니다.")
        val broker = try {
            credentials.forUser(user)
        } catch (e: BrokerError) {
            throw ApiException(HttpStatus.BAD_REQUEST, e.message ?: "증권사 자격증명이 서버에 설정되지 않았습니다.")
        }
        if (broker.orderAccountKey == null) throw ApiException(HttpStatus.BAD_REQUEST, "주문 계좌를 확인할 수 없습니다.")
        s.status = StrategyStatus.live
        strategies.save(s)
        control("start", id)
        log.info("전략 {} start 명령 발행", id)
        return mapOf("status" to "live")
    }

    @PostMapping("/strategies/{id}/stop")
    fun stop(@CurrentUser user: User, @PathVariable id: Int): Map<String, String> {
        val s = strategies.owned(user, id)
        s.status = StrategyStatus.backtested
        strategies.save(s)
        control("stop", id)
        log.info("전략 {} stop 명령 발행", id)
        return mapOf("status" to "stopped")
    }

    @GetMapping("/status")
    fun status(@CurrentUser user: User): Map<String, Any?> = linkedMapOf(
        "engine_alive" to (redis.opsForValue().get(RedisKeys.ENGINE_HEARTBEAT) == "alive"),
        "backup_last_success_at" to redis.opsForValue().get(RedisKeys.BACKUP_LAST_SUCCESS),
    )

    private fun redisJson(key: String): Map<String, Any?> = try {
        redis.opsForValue().get(key)?.let { json.readValue<Map<String, Any?>>(it) } ?: emptyMap()
    } catch (e: Exception) {
        emptyMap()
    }

    /**
     * 운용 중 전략별 러너 헬스. 프로세스 heartbeat 가 alive 여도 러너는 조용히 실패할 수 있어 러너 단위로 드러낸다.
     * MDD 킬스위치 상태(고점·발동·발동일)도 함께 — 발동 이후 "청산 상태로 재가동 대기 중"임을 확인할 유일한 경로.
     */
    @GetMapping("/strategies/health")
    fun health(@CurrentUser user: User): Map<String, Any?> {
        val live = strategies.findByUserId(user.id!!).filter { it.status == StrategyStatus.live }.sortedByDescending { it.id }
        val out = live.map { s ->
            val h = redisJson(RedisKeys.engineHealth(s.id!!))
            val failures = (h["consecutive_failures"] as? Number)?.toInt() ?: 0
            val mdd = (h["broker_account_key"] as? String)?.let { redisJson(RedisKeys.mddState(s.id!!, it)) }.orEmpty()
            linkedMapOf(
                "strategy_id" to s.id, "name" to s.name, "status" to s.status,
                "last_success_ts" to h["last_success_ts"], "consecutive_failures" to failures,
                "last_error" to h["last_error"], "updated_at" to h["updated_at"], "reported" to h.isNotEmpty(),
                "healthy" to (failures < RedisKeys.FAILURE_ALERT_THRESHOLD),
                "mdd_killed" to (mdd["killed"] == true), "mdd_kill_date" to mdd["kill_date"], "mdd_hwm" to mdd["hwm"],
            )
        }
        return linkedMapOf("threshold" to RedisKeys.FAILURE_ALERT_THRESHOLD, "strategies" to out)
    }
}
