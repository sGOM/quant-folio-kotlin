package com.quantfolio.market.krx

import org.springframework.stereotype.Component
import java.net.CookieManager
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

data class HttpResult(val status: Int, val body: String)

/** KRX 데이터 포털 HTTP. 로그인 쿠키(JSESSIONID)를 프로세스 안에서 유지한다. 연결 실패는 IOException. */
interface KrxTransport {
    fun get(url: String, headers: Map<String, String>): HttpResult

    fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpResult

    /** 재로그인 전에 이전 세션 쿠키를 버린다(원본은 requests.Session 을 새로 만든다). */
    fun clearCookies()
}

@Component
class JdkKrxTransport : KrxTransport {
    private val cookies = CookieManager()
    private val client = HttpClient.newBuilder()
        .cookieHandler(cookies)
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun get(url: String, headers: Map<String, String>): HttpResult {
        val request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15)).GET()
        headers.forEach { (k, v) -> request.header(k, v) }
        return send(request.build())
    }

    override fun postForm(url: String, form: Map<String, String>, headers: Map<String, String>, timeout: Duration): HttpResult {
        val body = form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(timeout)
            .header("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
            .POST(HttpRequest.BodyPublishers.ofString(body))
        headers.forEach { (k, v) -> request.header(k, v) }
        return send(request.build())
    }

    override fun clearCookies() {
        cookies.cookieStore.removeAll()
    }

    private fun send(request: HttpRequest): HttpResult {
        val response = client.send(request, HttpResponse.BodyHandlers.ofString())
        return HttpResult(response.statusCode(), response.body())
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
