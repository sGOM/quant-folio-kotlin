package com.quantfolio.web.config

import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import kotlin.test.assertEquals

/**
 * Caddy 가 TLS 를 끝내고 평문으로 넘겨도(https://WG_HOST) 같은 출처 요청이어야 한다. 앱이 X-Forwarded-Proto 를 무시하면
 * 자신을 http:80 으로 보고 `Origin: https://…` 를 교차출처로 판정해 403 — 폰 HTTPS 로그인이 전부 막힌다.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainersConfig::class)
class ProxyHttpsTest(@org.springframework.beans.factory.annotation.Autowired private val redis: org.springframework.data.redis.core.StringRedisTemplate) {
    @LocalServerPort var port: Int = 0

    @Test
    fun httpsBehindProxyIsSameOrigin() {
        val req = HttpRequest.newBuilder(URI("http://localhost:$port/api/auth/login"))
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Origin", "https://10.8.0.1")
            .header("X-Forwarded-Proto", "https")
            .header("X-Forwarded-Host", "10.8.0.1")
            .header("X-Forwarded-For", "10.8.0.77") // Caddy 가 덮어쓴 실제 접속 IP(WireGuard 사설망)
            .POST(HttpRequest.BodyPublishers.ofString("username=nobody%40t.dev&password=wrong-password"))
            .build()
        val res = HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString())
        assertEquals(401, res.statusCode(), res.body())
        // Tomcat RemoteIpValve 가 XFF 를 소비해도 로그인 스로틀은 같은 접속 IP 로 센다
        assertEquals(true, redis.hasKey(com.quantfolio.common.RedisKeys.loginFailByIp("10.8.0.77")))
    }
}
