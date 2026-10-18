package com.quantfolio.market.source

import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class HttpBytes(val status: Int, val body: ByteArray) {
    fun text(charset: java.nio.charset.Charset = Charsets.UTF_8) = String(body, charset)
}

/** KRX 를 제외한 외부 소스(네이버·KOFIA·KIS CDN·OpenDART) 공용 HTTP. 연결 실패는 예외(IOException 등). */
interface ExternalHttp {
    fun get(
        url: String,
        query: Map<String, String> = emptyMap(),
        headers: Map<String, String> = emptyMap(),
        timeout: Duration = Duration.ofSeconds(20),
    ): HttpBytes

    fun postJson(url: String, json: String, headers: Map<String, String>, timeout: Duration): HttpBytes

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeout: Duration = Duration.ofSeconds(20)): HttpBytes
}

@Component
class JdkExternalHttp : ExternalHttp {
    private val client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, query: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpBytes {
        val qs = query.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val target = if (qs.isEmpty()) url else url + (if ('?' in url) "&" else "?") + qs
        val request = HttpRequest.newBuilder(URI.create(target)).timeout(timeout).GET()
        headers.forEach { (k, v) -> request.header(k, v) }
        return send(request.build())
    }

    override fun postJson(url: String, json: String, headers: Map<String, String>, timeout: Duration): HttpBytes {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(json))
        headers.forEach { (k, v) -> request.header(k, v) }
        return send(request.build())
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpBytes {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> request.header(k, v) }
        return send(request.build())
    }

    private fun send(request: HttpRequest): HttpBytes {
        val response = client.send(request, HttpResponse.BodyHandlers.ofByteArray())
        return HttpBytes(response.statusCode(), response.body())
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}

/** 원본 `classify_httpx` 의 상태코드·전송 실패 분류. */
object HttpErrors {
    /** 2xx 가 아니면 원인별 예외. 429 Quota, 401/403 Auth, 그 외 4xx Request, 5xx Unavailable. */
    fun check(source: String, r: HttpBytes, label: String) {
        val code = r.status
        if (code in 200..299) return
        throw when {
            code == 429 -> SourceQuotaError(source, "$label HTTP 429 요청 한도 초과")
            code == 401 || code == 403 -> SourceAuthError(source, "$label HTTP $code 인증·권한 거부")
            code in 400..499 -> SourceRequestError(source, "$label HTTP $code 잘못된 요청")
            else -> SourceUnavailableError(source, "$label HTTP $code 서버 오류")
        }
    }

    /** 연결·타임아웃 등 전송 실패. 이미 분류된 예외는 그대로. */
    fun transport(source: String, e: Exception, label: String): DataSourceError =
        e as? DataSourceError ?: SourceUnavailableError(source, "$label 연결 실패: ${e.message}")
}
