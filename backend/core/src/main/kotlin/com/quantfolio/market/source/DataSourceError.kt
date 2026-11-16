package com.quantfolio.market.source

import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

/**
 * 외부 데이터 소스 실패를 **원인별로** 표현한다. 호출자의 관심은 "KRX 냐 DART 냐"가 아니라
 * "재시도해도 되나, 사람이 고쳐야 하나"라서 원인을 타입으로, 소스는 속성으로 싣는다.
 * 실패를 빈 값으로 삼키면 백테스트가 빈 패널 위에서 '성공'한다(원본 §44-1) — 반드시 예외로 흐르게 한다.
 */
sealed class DataSourceError(
    val source: String,
    val detail: String,
    val retryAfterSeconds: Double? = null,
) : RuntimeException("[$source] $detail") {
    abstract val retryable: Boolean

    /** 이 원인으로 실패했을 때 재조회를 막을 기본 쿨다운(초). null 이면 쿨다운 없음. */
    abstract val cooldownSeconds: Double?

    /** 응답 본문 `cause` 값 — 원본 Python 클래스명과 같다. */
    val causeName: String get() = javaClass.simpleName

    /** 같은 원인·소스·retryAfter 로 메시지만 바꾼 사본. */
    abstract fun copyWithDetail(detail: String): DataSourceError
}

/** 인증·권한·차단. 재시도해도 안 풀리고 차단만 악화된다. */
class SourceAuthError(source: String, detail: String, retryAfterSeconds: Double? = null) :
    DataSourceError(source, detail, retryAfterSeconds) {
    override val retryable = false
    override val cooldownSeconds = 300.0
    override fun copyWithDetail(detail: String) = SourceAuthError(source, detail, retryAfterSeconds)
}

/** 호출 한도 초과. 리셋 시각을 알면(DART 일일 한도) retryAfter 로 지정한다. */
class SourceQuotaError(source: String, detail: String, retryAfterSeconds: Double? = null) :
    DataSourceError(source, detail, retryAfterSeconds) {
    override val retryable = false
    override val cooldownSeconds = 300.0
    override fun copyWithDetail(detail: String) = SourceQuotaError(source, detail, retryAfterSeconds)
}

/** 일시 장애(네트워크·타임아웃·5xx·점검). 재시도가 유효하다. */
class SourceUnavailableError(source: String, detail: String, retryAfterSeconds: Double? = null) :
    DataSourceError(source, detail, retryAfterSeconds) {
    override val retryable = true
    override val cooldownSeconds = 60.0
    override fun copyWithDetail(detail: String) = SourceUnavailableError(source, detail, retryAfterSeconds)
}

/** 응답이 파서 계약과 다르다. 코드 수정이 필요하다 — 쿨다운으로 기다리면 문제를 은폐한다. */
class SourceSchemaError(source: String, detail: String, retryAfterSeconds: Double? = null) :
    DataSourceError(source, detail, retryAfterSeconds) {
    override val retryable = false
    override val cooldownSeconds: Double? = null
    override fun copyWithDetail(detail: String) = SourceSchemaError(source, detail, retryAfterSeconds)
}

/** 우리가 잘못 요청했다(부적절한 파라미터·접근). 버그이므로 쿨다운하지 않는다. */
class SourceRequestError(source: String, detail: String, retryAfterSeconds: Double? = null) :
    DataSourceError(source, detail, retryAfterSeconds) {
    override val retryable = false
    override val cooldownSeconds: Double? = null
    override fun copyWithDetail(detail: String) = SourceRequestError(source, detail, retryAfterSeconds)
}

object DataSourceErrors {
    /** 원인이 섞였을 때 대표를 고르는 우선순위 — 확정적·심각한 쪽이 앞선다. */
    private val PRIORITY = listOf(
        SourceAuthError::class,
        SourceQuotaError::class,
        SourceRequestError::class,
        SourceSchemaError::class,
        SourceUnavailableError::class,
    )

    /** 집계가 전량 실패했을 때 올릴 대표 예외. 메시지에 원인별 건수를 담아 로그 한 줄로 드러낸다. */
    fun representative(errors: List<DataSourceError>): DataSourceError {
        require(errors.isNotEmpty()) { "errors 가 비었다 — 실패가 없으면 대표 예외를 고를 수 없다" }
        val counts = errors.groupingBy { it.causeName }.eachCount()
        val picked = PRIORITY.firstNotNullOfOrNull { cls -> errors.firstOrNull { cls.isInstance(it) } } ?: errors.first()
        return picked.copyWithDetail("${picked.detail} — 총 ${errors.size}건 실패 $counts")
    }

    /** 다음 자정까지 남은 초(DART 일일 한도 리셋 근사). 원본처럼 프로세스 로컬 시각 기준. */
    fun secondsUntilMidnight(clock: Clock = Clock.systemDefaultZone()): Double {
        val now = LocalDateTime.now(clock)
        return Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay()).toMillis() / 1000.0
    }
}
