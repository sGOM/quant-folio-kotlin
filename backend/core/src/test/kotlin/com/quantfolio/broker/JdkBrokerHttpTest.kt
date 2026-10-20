package com.quantfolio.broker

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals

/** 실제 전송 헤더 — 가짜 BrokerHttp 로는 안 보인다. 원본 httpx `post(json=...)` 은 Content-Type 을 항상 붙였다(KIS 토큰·승인키 요청). */
class JdkBrokerHttpTest {
    @Test
    fun jsonBodyCarriesContentTypeUnlessCallerSetsOne() {
        val seen = mutableListOf<String?>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += ex.requestHeaders.getFirst("Content-Type")
                ex.sendResponseHeaders(200, 2)
                ex.responseBody.use { it.write("{}".toByteArray()) }
            }
            start()
        }
        try {
            val url = "http://127.0.0.1:${server.address.port}/t"
            val http: BrokerHttp = JdkBrokerHttp()
            http.send("POST", url, json = "{}")
            http.send("POST", url, headers = mapOf("content-type" to "application/json; charset=utf-8"), json = "{}")
            assertEquals<List<String?>>(listOf("application/json", "application/json; charset=utf-8"), seen)
        } finally {
            server.stop(0)
        }
    }
}
