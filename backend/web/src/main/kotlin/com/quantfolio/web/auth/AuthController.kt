package com.quantfolio.web.auth

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.web.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** 로그인 시도 제한의 사용자 키. 입력 username 과 무관한 고정값이라 username 을 바꿔 제한을 우회할 수 없다. */
internal const val OWNER_THROTTLE_KEY = "owner"

/**
 * 로그인·로그아웃(서버측 세션). 혼자 쓰는 서비스라 로그인은 form `password` 하나로 내부 소유자(`TRADING_OWNER_USER_ID`)의
 * 기존 비밀번호 해시만 검증한다. 구버전 클라이언트가 보내는 `username` 은 읽지 않는다(다른 사용자를 고를 수 없다). 회원가입은 거부.
 */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val sessions: SessionStore,
    private val throttle: LoginThrottle,
    private val props: QuantfolioProperties,
    private val brokers: BrokerCredentials,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/login")
    fun login(
        @RequestParam password: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): UserOut {
        val ip = ClientIp.of(request)
        if (throttle.blocked(OWNER_THROTTLE_KEY, ip)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.")
        }
        val ownerId = props.tradingOwnerUserId
        val user = ownerId?.let { users.findById(it).orElse(null) }
        if (user == null) {
            // 응답은 원인을 가리지만(계정 존재 여부 비노출) 로그는 구분한다.
            if (ownerId == null) log.error("로그인 불가: TRADING_OWNER_USER_ID 미설정")
            else log.error("로그인 불가: TRADING_OWNER_USER_ID={} 사용자 행 없음", ownerId)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "로그인을 사용할 수 없습니다. 서버 설정을 확인하세요.")
        }
        if (!Passwords.verify(password, user.passwordHash)) {
            throttle.recordFailure(OWNER_THROTTLE_KEY, ip)
            throw ApiException(HttpStatus.UNAUTHORIZED, "비밀번호가 올바르지 않습니다.")
        }
        throttle.reset(OWNER_THROTTLE_KEY)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(sessions.create(user.id!!), Duration.ofMinutes(props.sessionTtlMinutes)))
        return user.toOut(brokers)
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@CookieValue(SESSION_COOKIE, required = false) sid: String?, response: HttpServletResponse) {
        sessions.destroy(sid)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO))
    }

    @GetMapping("/me")
    fun me(@CurrentUser user: User) = user.toOut(brokers)

    private fun sessionCookie(value: String, maxAge: Duration): String =
        ResponseCookie.from(SESSION_COOKIE, value)
            .maxAge(maxAge)
            .httpOnly(true)
            .secure(props.cookie.secure)
            .sameSite(props.cookie.samesite.replaceFirstChar { it.uppercase() })
            .domain(props.cookie.domain?.ifBlank { null })
            .path("/")
            .build()
            .toString()
}
