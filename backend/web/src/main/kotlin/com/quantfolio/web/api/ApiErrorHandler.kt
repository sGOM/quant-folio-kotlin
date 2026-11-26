package com.quantfolio.web.api

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.backtest.BacktestFailed
import com.quantfolio.backtest.BacktestInputError
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.strategy.StrategyConfigException
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.ErrorResponse
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.MissingServletRequestParameterException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.method.annotation.HandlerMethodValidationException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/**
 * 에러 응답을 원본 FastAPI 형태로 맞춘다. 프론트 `lib/api.ts::formatError` 가
 * `detail` 이 문자열이거나 `[{loc, msg, type}]` 배열인 두 경우를 파싱한다.
 */
@RestControllerAdvice
class ApiErrorHandler(private val props: QuantfolioProperties) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class ValidationError(val type: String, val loc: List<Any>, val msg: String)

    @ExceptionHandler(ApiException::class)
    fun api(e: ApiException) = ResponseEntity.status(e.status).body(mapOf("detail" to e.detail))

    /**
     * 외부 데이터 소스 실패 → 원인별 HTTP. **전부 503 으로 뭉개지 않는다** — "재시도/사람이 고칠 것"을 가르려고 원인을 나눴다.
     * Request(우리가 잘못 보냄)=500, Schema(상류 계약 변경)=502, 나머지(차단·한도·연결)=503.
     */
    @ExceptionHandler(DataSourceError::class)
    fun dataSource(e: DataSourceError, request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        val (status, detail) = when (e) {
            is SourceRequestError -> HttpStatus.INTERNAL_SERVER_ERROR to "외부 데이터 소스 요청이 잘못됐습니다."
            is SourceSchemaError -> HttpStatus.BAD_GATEWAY to "외부 데이터 소스 응답을 해석할 수 없습니다."
            else -> HttpStatus.SERVICE_UNAVAILABLE to "외부 데이터 소스를 사용할 수 없습니다."
        }
        log.error("외부 데이터 소스 실패 ({} → {}): {}", request.requestURI, status.value(), e.message)
        return ResponseEntity.status(status).body(
            mapOf("detail" to detail, "source" to e.source, "cause" to e.causeName, "retryable" to e.retryable),
        )
    }

    /** 로컬 DB 미적재 — 원격으로 대체하지 않았다. 사람이 적재(백필)해야 풀리므로 409 와 빠진 구간을 알린다. */
    @ExceptionHandler(MarketDataNotLoaded::class)
    fun notLoaded(e: MarketDataNotLoaded): ResponseEntity<Map<String, Any>> = ResponseEntity.status(HttpStatus.CONFLICT).body(
        mapOf(
            "detail" to "시장데이터가 적재되지 않았습니다.",
            "kind" to e.kind,
            "key" to e.key,
            "missing" to e.missing.map { mapOf("start" to it.first.toString(), "end" to it.second.toString()) },
        ),
    )

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(e: MethodArgumentNotValidException) = unprocessable(
        e.bindingResult.fieldErrors.map {
            ValidationError(it.code ?: "value_error", listOf("body", it.field), it.defaultMessage ?: "잘못된 값입니다.")
        },
    )

    /** 전략 설정 검증 실패 — pydantic 처럼 `loc` 이 body.config 부터(판별자 태그 → 필드). 문구·type 은 pydantic 원문. */
    @ExceptionHandler(StrategyConfigException::class)
    fun invalidConfig(e: StrategyConfigException) = unprocessable(e.errors.map { ValidationError(it.type, listOf("body", "config") + it.loc, it.msg) })

    /** 백테스트 입력 부적합(원본 ValueError → 422) / 실행 실패(원본 500 `백테스트 실패: {e}`, 원인 문구 노출은 원본 그대로). */
    @ExceptionHandler(BacktestInputError::class)
    fun backtestInput(e: BacktestInputError) = ResponseEntity.unprocessableContent().body(mapOf("detail" to e.message))

    @ExceptionHandler(BacktestFailed::class)
    fun backtestFailed(e: BacktestFailed) = ResponseEntity.internalServerError().body(mapOf("detail" to e.message))

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun unreadable(e: HttpMessageNotReadableException) =
        unprocessable(listOf(ValidationError("json_invalid", listOf("body"), "요청 본문을 해석할 수 없습니다.")))

    @ExceptionHandler(MissingServletRequestParameterException::class)
    fun missingParam(e: MissingServletRequestParameterException) =
        unprocessable(listOf(ValidationError("missing", listOf("query", e.parameterName), "필수 값입니다.")))

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun typeMismatch(e: MethodArgumentTypeMismatchException) =
        unprocessable(listOf(ValidationError("type_error", listOf("query", e.name), "형식이 올바르지 않습니다.")))

    @ExceptionHandler(HandlerMethodValidationException::class)
    fun invalidParams(e: HandlerMethodValidationException) = unprocessable(
        e.parameterValidationResults.flatMap { r ->
            r.resolvableErrors.map {
                ValidationError("value_error", listOf("query", r.methodParameter.parameterName ?: "?"), it.defaultMessage ?: "잘못된 값입니다.")
            }
        },
    )

    @ExceptionHandler(Exception::class)
    fun unhandled(e: Exception, request: HttpServletRequest): ResponseEntity<Map<String, Any>> {
        // 404·405 등 Spring 이 상태를 아는 예외 — FastAPI 처럼 `{"detail": "Not Found"}`.
        if (e is ErrorResponse) {
            val reason = HttpStatus.resolve(e.statusCode.value())?.reasonPhrase ?: e.statusCode.toString()
            return ResponseEntity.status(e.statusCode).body(mapOf("detail" to reason))
        }
        log.error("처리되지 않은 예외: {} {}", request.method, request.requestURI, e)
        val body = buildMap<String, Any> {
            put("detail", "Internal Server Error")
            if (props.exposeErrorDetails) {
                put(
                    "error",
                    mapOf(
                        "type" to e.javaClass.simpleName,
                        "message" to (e.message ?: ""),
                        "path" to request.requestURI,
                        "traceback" to e.stackTraceToString().lines(),
                    ),
                )
            }
        }
        return ResponseEntity.internalServerError().body(body)
    }

    private fun unprocessable(errors: List<ValidationError>) =
        ResponseEntity.status(HttpStatus.UNPROCESSABLE_CONTENT).body(mapOf("detail" to errors))
}
