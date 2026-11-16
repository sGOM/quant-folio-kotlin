package com.quantfolio.web.config

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.willReturn
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import java.net.URI
import java.util.UUID
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
class ProxyHttpsTest(
    @org.springframework.beans.factory.annotation.Autowired private val redis: org.springframework.data.redis.core.StringRedisTemplate,
    @org.springframework.beans.factory.annotation.Autowired private val users: UserRepository,
) {
    @LocalServerPort var port: Int = 0

    @MockitoSpyBean lateinit var props: QuantfolioProperties

    @Test
    fun httpsBehindProxyIsSameOrigin() {
        // 로그인은 내부 소유자 행이 있어야 비밀번호 대조(401)까지 간다. 없으면 503.
        val owner = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        willReturn(owner.id).given(props).tradingOwnerUserId
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

    /**
     * 호스트 포트가 80·443 이 아니면(`APP_PORT=8080`) RemoteIpValve 는 X-Forwarded-Host 의 포트를 버리고
     * X-Forwarded-Proto 를 보면 포트를 X-Forwarded-Port(없으면 80/443)로 정한다. 그래서 Caddyfile 이
     * `X-Forwarded-Port` 를 넘겨야 같은 출처다 — 헤더 묶음은 Caddyfile 의 `reverse_proxy web:8000` 이 보내는 것.
     */
    @Test
    fun nonStandardHttpPortBehindProxyIsSameOrigin() {
        assertEquals(204, logout("http://localhost:8080", "localhost:8080", "http", "8080"))
    }

    @Test
    fun httpsWithoutPortInHostIsSameOrigin() {
        // Host 에 포트가 없을 때 Caddy 의 {http.request.port} 는 빈 값일 것(미관측) → 숫자가 아니라 무시, https 기본 443
        assertEquals(204, logout("https://10.8.0.1", "10.8.0.1", "https", ""))
    }

    @Test
    fun crossOriginBehindProxyIsStillRejected() {
        assertEquals(403, logout("http://localhost:9999", "localhost:8080", "http", "8080"))
    }

    private fun logout(origin: String, host: String, proto: String, forwardedPort: String): Int {
        val req = HttpRequest.newBuilder(URI("http://localhost:$port/api/auth/logout"))
            .header("Origin", origin)
            .header("X-Forwarded-Host", host)
            .header("X-Forwarded-Proto", proto)
            .header("X-Forwarded-Port", forwardedPort)
            .header("X-Forwarded-For", "10.8.0.77")
            .POST(HttpRequest.BodyPublishers.noBody())
            .build()
        return HttpClient.newHttpClient().send(req, HttpResponse.BodyHandlers.ofString()).statusCode()
    }
}
