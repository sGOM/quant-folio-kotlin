package com.quantfolio.web.api

import com.quantfolio.common.QuantfolioProperties
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

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun invalidBody(e: MethodArgumentNotValidException) = unprocessable(
        e.bindingResult.fieldErrors.map {
            ValidationError(it.code ?: "value_error", listOf("body", it.field), it.defaultMessage ?: "잘못된 값입니다.")
        },
    )

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
