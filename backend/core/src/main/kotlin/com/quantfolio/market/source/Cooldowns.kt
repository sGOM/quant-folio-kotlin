package com.quantfolio.market.source

import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * 소스 단위 재조회 차단. KRX 로그인 재시도 폭주가 차단을 악화시켰던 사고(원본 §44-1)의 방어를 원인별로 일반화한 것.
 * 프로세스 메모리에만 둔다(원본과 동일 — 프로세스마다 독립).
 */
@Component
class Cooldowns(private val clock: Clock) {
    private val until = ConcurrentHashMap<String, Instant>()

    /** 실패 원인에 맞는 쿨다운을 건다(Schema·Request 는 쿨다운 없음). */
    fun noteFailure(e: DataSourceError) {
        val seconds = e.retryAfterSeconds ?: e.cooldownSeconds ?: return
        if (seconds <= 0) return
        until[e.source] = clock.instant().plusMillis((seconds * 1000).toLong())
    }

    fun remaining(source: String): Double {
        val end = until[source] ?: return 0.0
        return maxOf(0.0, (end.toEpochMilli() - clock.instant().toEpochMilli()) / 1000.0)
    }

    fun clear(source: String) {
        until.remove(source)
    }

    /**
     * 종목별 집계 루프를 여기서 멈춰야 하는가(부분 결과는 호출자가 그대로 쓴다).
     * 1. 쿨다운 중 — 더 시도해도 같은 차단을 재현하고 대표 원인만 오염시킨다.
     * 2. 성공 0건에 최근 [SCHEMA_SYSTEMIC_THRESHOLD]건이 전부 스키마 오류 — 응답 형식이 통째로 바뀐 것이다.
     *    (Request 오류는 종목별 인자 문제일 수 있어 멈추지 않는다. 한 번이라도 성공했으면 형식은 맞다.)
     */
    fun stopAggregate(source: String, errors: List<DataSourceError>, ok: Int): Boolean {
        if (errors.isEmpty()) return false
        if (remaining(source) > 0) return true
        return ok == 0 && errors.size >= SCHEMA_SYSTEMIC_THRESHOLD &&
            errors.takeLast(SCHEMA_SYSTEMIC_THRESHOLD).all { it is SourceSchemaError }
    }

    private companion object {
        const val SCHEMA_SYSTEMIC_THRESHOLD = 3
    }
}
