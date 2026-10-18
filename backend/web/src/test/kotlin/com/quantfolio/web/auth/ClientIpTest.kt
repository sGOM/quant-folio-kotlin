package com.quantfolio.web.auth

import org.junit.jupiter.api.Test
import org.springframework.mock.web.MockHttpServletRequest
import kotlin.test.assertEquals

class ClientIpTest {
    @Test
    fun lastForwardedForWins() {
        // 앞에 끼워 넣은 스푸핑 값이 아니라 가장 가까운 프록시가 기록한 마지막 값을 쓴다.
        val req = MockHttpServletRequest().apply { addHeader("X-Forwarded-For", "1.1.1.1, 9.9.9.9 ") }
        assertEquals("9.9.9.9", ClientIp.of(req))
    }

    @Test
    fun fallsBackToRemoteAddr() {
        val req = MockHttpServletRequest().apply { remoteAddr = "10.0.0.5" }
        assertEquals("10.0.0.5", ClientIp.of(req))
    }
}
