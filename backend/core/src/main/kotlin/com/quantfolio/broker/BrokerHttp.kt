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

/** 증권사 REST 전송(응답 헤더까지 필요 — 토스 Retry-After). 테스트에서 교체 가능. */
interface BrokerHttp {
    /** body: JSON 문자열 또는 form(application/x-www-form-urlencoded) 중 하나. */
    fun send(method: String, url: String, query: Map<String, String> = emptyMap(), headers: Map<String, String> = emptyMap(), json: String? = null, form: Map<String, String>? = null): BrokerResponse
}

@Component
class JdkBrokerHttp : BrokerHttp {
    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build()

    override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
        val qs = query.entries.joinToString("&") { (k, v) -> enc(k) + "=" + enc(v) }
        val req = HttpRequest.newBuilder(URI.create(if (qs.isEmpty()) url else "$url?$qs")).timeout(Duration.ofSeconds(10))
        headers.forEach { (k, v) -> req.header(k, v) }
        val body = when {
            json != null -> HttpRequest.BodyPublishers.ofString(json)
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
