package com.quantfolio.web.auth

import jakarta.servlet.http.HttpServletRequest

/**
 * 오리진 IP 의 단일 출처. 프록시(Caddy)가 `X-Forwarded-For` 를 실제 접속 IP 로 덮어쓴다는 전제에서,
 * 콤마 목록이 오면 **마지막 항목**(가장 가까운 프록시 기록)만 신뢰한다. 앞 항목을 쓰면 스푸핑으로 스로틀을 우회할 수 있다.
 */
object ClientIp {
    fun of(request: HttpServletRequest): String {
        val forwarded = request.getHeader("X-Forwarded-For")
        if (!forwarded.isNullOrBlank()) return forwarded.substringAfterLast(",").trim()
        return request.remoteAddr ?: "unknown"
    }
}
