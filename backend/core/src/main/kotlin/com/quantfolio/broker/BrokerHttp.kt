package com.quantfolio.broker

import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class BrokerResponse(val status: Int, val body: String, val headers: Map<String, List<String>> = emptyMap()) {
    fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.firstOrNull()
}

/** 증권사 REST 1회 전송의 연결·응답 헤더 수신 한도(초). 락 TTL 산식(`RedisKeys`)의 재료 — 본문 수신은 이 한도 밖이다. */
const val BROKER_HTTP_TIMEOUT_SEC = 10L

/** 증권사 REST 전송(응답 헤더까지 필요 — 토스 Retry-After). 테스트에서 교체 가능. */
interface BrokerHttp {
    /** body: JSON 문자열 또는 form(application/x-www-form-urlencoded) 중 하나. */
    fun send(method: String, url: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), json: String? = null, form: Map<String, String>? = null): BrokerResponse
}

@Component
class JdkBrokerHttp : BrokerHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(BROKER_HTTP_TIMEOUT_SEC)).build()

    override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
        val qs = query.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val req = HttpRequest.newBuilder(URI.create(if (qs.isEmpty()) url else "$url?$qs")).timeout(Duration.ofSeconds(BROKER_HTTP_TIMEOUT_SEC))
        headers.forEach { (k, v) -> req.header(k, v) }
        val body = when {
            json != null -> {
                // 원본 httpx `post(json=...)` 처럼 기본 Content-Type — JDK 클라이언트는 붙이지 않는다(KIS 토큰·승인키 요청이 헤더 없이 나갔다)
                if (headers.keys.none { it.equals("content-type", ignoreCase = true) }) req.header("Content-Type", "application/json")
                HttpRequest.BodyPublishers.ofString(json)
            }
            form != null -> {
                req.header("Content-Type", "application/x-www-form-urlencoded")
                HttpRequest.BodyPublishers.ofString(form.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) })
            }
            else -> HttpRequest.BodyPublishers.noBody()
        }
        req.method(method, body)
        val r = client.send(req.build(), HttpResponse.BodyHandlers.ofString())
        return BrokerResponse(r.statusCode(), r.body(), r.headers().map())
    }

    private fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8)
}
