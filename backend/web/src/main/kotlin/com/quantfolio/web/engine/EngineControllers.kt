package com.quantfolio.web.engine

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.Alert
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.quant.pyJsonDumps
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.Instant

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

data class PositionOut(val symbol: String, val qty: Double, val avgPrice: Double?)

data class OrderOut(val id: Int, val symbol: String, val side: String, val qty: Double, val price: Double?, val status: String, val reason: String?, val createdAt: Instant)

/**
 * 매매 조회(원본 `routes/trading.py`). 잔고의 신뢰 소스는 **증권사 실계좌** — 로컬 DB 는 이 시스템이 낸 주문만 알아서
 * 수동 매매·다른 계좌 보유를 놓친다. 잔고 조회 실패 시 확인된 계좌의 로컬 포지션만 보여준다.
 */
@RestController
@RequestMapping("/api/trading")
class TradingController(private val jdbc: NamedParameterJdbcTemplate, private val brokers: BrokerCredentials) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/positions")
    fun positions(@CurrentUser user: User): List<PositionOut> {
        val broker = try {
            brokers.forUser(user)
        } catch (e: BrokerError) {
            return emptyList()
        }
        val balance = try {
            broker.getBalance()
        } catch (e: BrokerError) {
            log.warn("실계좌 잔고 조회 실패 — 로컬 포지션으로 폴백 user={}: {}", user.id, e.message)
            return broker.orderAccountKey?.let { dbPositions(user, it) }.orEmpty()
        }
        return balance.positionsNormalized().map { PositionOut(it.symbol, it.qty.toDouble(), it.avgPrice?.toDouble()) }
    }

    private fun dbPositions(user: User, accountKey: String): List<PositionOut> =
        jdbc.query("select symbol, qty, avg_price from positions where user_id = :u and broker_account_key = :a and qty > 0",
            mapOf("u" to user.id, "a" to accountKey)) { rs, _ ->
            PositionOut(rs.getString(1), rs.getBigDecimal(2).toDouble(), rs.getBigDecimal(3).toDouble())
        }

    /** 최근 주문(감사 로그) 최신순, limit 최대 200. */
    @GetMapping("/orders")
    fun orders(@CurrentUser user: User, @RequestParam(defaultValue = "50") limit: Int): List<OrderOut> =
        jdbc.query(
            "select id, symbol, side, qty, price, status, reason, created_at from orders where user_id = :u order by created_at desc limit :n",
            MapSqlParameterSource("u", user.id).addValue("n", minOf(limit, 200)),
        ) { rs, _ ->
            OrderOut(
                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).toDouble(), (rs.getObject(5) as BigDecimal?)?.toDouble(),
                rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
            )
        }
}

data class AlertOut(val id: Int, val userId: Int?, val strategyId: Int, val severity: AlertSeverity, val code: String?, val message: String, val isRead: Boolean, val createdAt: Instant)

fun Alert.toOut() = AlertOut(id!!, userId, strategyId, severity, code, message, isRead, createdAt)

data class AlertListOut(val items: List<AlertOut>, val unreadCount: Int, val hasMore: Boolean)

/** 알림함(원본 `routes/alerts.py`). 본인 알림 + 전역(user_id null) 알림만 보인다. */
@RestController
@RequestMapping("/api/alerts")
class AlertsController(private val alerts: AlertRepository, private val jdbc: NamedParameterJdbcTemplate) {
    private val visible = "(user_id = :u or user_id is null)"

    @GetMapping
    fun list(
        @CurrentUser user: User,
        @RequestParam(name = "unread_only", defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
    ): AlertListOut {
        val p = MapSqlParameterSource("u", user.id).addValue("n", limit + 1).addValue("o", offset)
        val ids = jdbc.queryForList(
            "select id from alerts where $visible${if (unreadOnly) " and not is_read" else ""} order by created_at desc limit :n offset :o",
            p, Int::class.java,
        )
        val byId = alerts.findAllById(ids.filterNotNull()).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        val unread = jdbc.queryForObject("select count(*) from alerts where $visible and not is_read", p, Int::class.java) ?: 0
        return AlertListOut(rows.take(limit).map { it.toOut() }, unread, rows.size > limit)
    }

    @PostMapping("/{id}/read")
    fun read(@CurrentUser user: User, @PathVariable id: Int): AlertOut {
        val a = alerts.findById(id).orElse(null)
        if (a == null || (a.userId != null && a.userId != user.id)) throw ApiException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.")
        a.isRead = true
        return alerts.save(a).toOut()
    }

    @PostMapping("/read-all")
    fun readAll(@CurrentUser user: User): Map<String, Int> =
        mapOf("updated" to jdbc.update("update alerts set is_read = true where $visible and not is_read", MapSqlParameterSource("u", user.id)))
}
