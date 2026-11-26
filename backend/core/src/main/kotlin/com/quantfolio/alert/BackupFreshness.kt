package com.quantfolio.alert

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.quant.pyFixed
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.OffsetDateTime

/**
 * DB 백업 신선도 — 워커 야간 백업의 성공 기록(`backup:last_success_at`)이 없거나 26시간 넘게 갱신 안 됐으면 critical.
 * 워커 사망 같은 상위 침묵 실패를 잡는 감시라 **워커가 아닌 엔진에서 부른다**(`EngineRuntime`) — 워커 안에서 돌면
 * 워커가 멈출 때 감시도 같이 멈춘다(2026-11-25~26 워커 20시간 정지·백업 누락이 알림 없이 지나갔다).
 */
@Component
class BackupFreshness(private val redis: StringRedisTemplate, private val alerts: AlertPublisher, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun check(): Map<String, Any?> {
        val raw = redis.opsForValue().get(RedisKeys.BACKUP_LAST_SUCCESS)
        val ageHours = raw?.let { Duration.between(OffsetDateTime.parse(it).toInstant(), clock.instant()).toMillis() / 3_600_000.0 }
        val stale = ageHours == null || ageHours > STALE_AFTER_HOURS
        if (stale) {
            val reason = if (ageHours == null) "백업 성공 기록이 없습니다(worker/beat 미기동 가능성)" else "마지막 백업 성공 이후 ${pyFixed(ageHours, 1)}시간 경과"
            log.error("DB 백업 신선도 점검 실패: {}", reason)
            alerts.publish(null, 0, AlertSeverity.critical, "DB 백업이 최신이 아닙니다: $reason", "db_backup_stale", dedupWindowHours = 20.0)
        }
        return linkedMapOf("ok" to !stale, "last_success_at" to raw, "age_hours" to ageHours)
    }

    private companion object {
        const val STALE_AFTER_HOURS = 26.0
    }
}
