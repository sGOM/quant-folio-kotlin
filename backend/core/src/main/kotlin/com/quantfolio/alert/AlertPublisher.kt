package com.quantfolio.alert

import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** Python `datetime.isoformat()`(마이크로초가 0 이면 생략). */
fun pyIsoformat(t: ZonedDateTime): String {
    val micros = t.nano / 1000
    val base = t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))
    return base + (if (micros != 0) ".%06d".format(micros) else "") + t.format(DateTimeFormatter.ofPattern("xxx"))
}

/**
 * 엔진·배치 이상 알림(원본 `engine/alerts.publish_alert`): DB 적재(알림함) + 사용자 이벤트 채널(WS 토스트) + critical 은 텔레그램.
 * 무인 운영의 "조용한 실패"를 사람에게 넘기는 유일한 통로라, 한 경로의 실패가 다른 경로를 막지 않는다.
 * dedupWindowHours 를 주면 같은 (사용자, 전략, code) 의 미확인 알림이 창 안에 있을 때 DB·텔레그램을 생략한다(WS 는 항상).
 */
@Component
class AlertPublisher(
    private val jdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val http: ExternalHttp,
    private val props: QuantfolioProperties,
    private val clock: Clock,
    txManager: PlatformTransactionManager,
) {
    private val log = LoggerFactory.getLogger("engine.alerts")

    /**
     * 알림 적재는 호출자 트랜잭션과 분리(원본도 별도 세션). 체결 트랜잭션 안에서 부르면 적재 실패가 체결 기록을 통째로 롤백시키고,
     * 반대로 호출자가 롤백하면 접수불명 같은 critical 알림이 사라진다.
     */
    private val ownTx = TransactionTemplate(txManager).apply { propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW }

    fun publish(userId: Int?, strategyId: Int, severity: String, message: String, code: String? = null, dedupWindowHours: Double? = null) {
        log.warn("엔진 알림[{}] 전략 {}{}: {}", severity, strategyId, code?.let { "($it)" } ?: "", message)
        val now = clock.instant()
        var dup = false
        try {
            ownTx.executeWithoutResult {
            if (dedupWindowHours != null && code != null) {
                val cutoff = now.minusMillis((dedupWindowHours * 3_600_000).toLong())
                dup = jdbc.queryForObject(
                    "select exists(select 1 from alerts where user_id is not distinct from :u and strategy_id = :s and code = :c and not is_read and created_at >= :t)",
                    MapSqlParameterSource("u", userId).addValue("s", strategyId).addValue("c", code).addValue("t", Timestamp.from(cutoff)),
                    Boolean::class.java,
                ) == true
            }
            if (!dup) {
                jdbc.update(
                    "insert into alerts(user_id, strategy_id, severity, code, message, is_read, created_at) values (:u, :s, :sev, :c, :m, false, :t)",
                    MapSqlParameterSource("u", userId).addValue("s", strategyId).addValue("sev", severity).addValue("c", code)
                        .addValue("m", message).addValue("t", Timestamp.from(now)),
                )
            }
            }
        } catch (e: Exception) {
            log.warn("알림 DB 영속화 실패: {}", e.message)
        }
        if (dup) {
            log.info("알림[{}] dedup 창 내 미확인 알림 존재 — DB/텔레그램 생략", code)
        } else if (severity == "critical" && props.telegram.enabled) {
            telegram("[QuantFolio] 전략 $strategyId${code?.let { "($it)" } ?: ""}\n$message")
        }
        if (userId == null) return
        val payload = linkedMapOf<String, Any?>(
            "type" to "alert", "user_id" to userId, "strategy_id" to strategyId, "severity" to severity, "message" to message,
            "ts" to pyIsoformat(now.atZone(MarketClock.KST)),
        )
        code?.let { payload["code"] = it }
        publishEvent(payload)
    }

    /** 사용자 채널(`engine:events:{uid}`) + 하위호환 공용 채널로 발행(원본 `fills.publish_event`, json.dumps 문자열 그대로). */
    fun publishEvent(payload: Map<String, Any?>) {
        val data = pyJsonDumps(payload)
        (payload["user_id"] as? Number)?.let { redis.convertAndSend(RedisKeys.engineEvents(it.toInt()), data) }
        redis.convertAndSend(RedisKeys.ENGINE_EVENTS, data)
    }

    private fun telegram(text: String) {
        try {
            val r = http.postJson(
                "https://api.telegram.org/bot${props.telegram.botToken}/sendMessage",
                pyJsonDumps(linkedMapOf("chat_id" to props.telegram.chatId, "text" to text)),
                mapOf("Content-Type" to "application/json"), Duration.ofSeconds(5),
            )
            if (r.status !in 200..299) log.warn("텔레그램 알림 발송 실패: HTTP {}", r.status)
        } catch (e: Exception) {
            log.warn("텔레그램 알림 발송 실패: {}", e.message)
        }
    }
}
