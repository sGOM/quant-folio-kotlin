package com.quantfolio.web.auth

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.web.api.ApiException
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import jakarta.validation.Valid
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.web.bind.annotation.CookieValue
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/** 회원가입·로그인·로그아웃(서버측 세션). 로그인은 프론트가 OAuth2 form(username=이메일)으로 보낸다. */
@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val users: UserRepository,
    private val sessions: SessionStore,
    private val throttle: LoginThrottle,
    private val props: QuantfolioProperties,
) {
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    fun register(@Valid @RequestBody body: RegisterRequest): UserOut {
        if (users.findByEmail(body.email) != null) throw ApiException(HttpStatus.CONFLICT, "이미 등록된 이메일입니다.")
        return users.save(User(email = body.email, passwordHash = Passwords.hash(body.password))).toOut(props)
    }

    @PostMapping("/login")
    fun login(
        @RequestParam username: String,
        @RequestParam password: String,
        request: HttpServletRequest,
        response: HttpServletResponse,
    ): UserOut {
        val ip = ClientIp.of(request)
        if (throttle.blocked(username, ip)) {
            throw ApiException(HttpStatus.TOO_MANY_REQUESTS, "로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.")
        }
        val user = users.findByEmail(username)
        if (user == null || !Passwords.verify(password, user.passwordHash)) {
            throttle.recordFailure(username, ip)
            throw ApiException(HttpStatus.UNAUTHORIZED, "이메일 또는 비밀번호가 올바르지 않습니다.")
        }
        throttle.reset(username)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie(sessions.create(user.id!!), Duration.ofMinutes(props.sessionTtlMinutes)))
        return user.toOut(props)
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(@CookieValue(SESSION_COOKIE, required = false) sid: String?, response: HttpServletResponse) {
        sessions.destroy(sid)
        response.addHeader(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO))
    }

    @GetMapping("/me")
    fun me(@CurrentUser user: User) = user.toOut(props)

    @PatchMapping("/me")
    fun updateMe(@CurrentUser user: User, @Valid @RequestBody body: ProfileUpdate): UserOut {
        user.displayName = body.displayName?.trim()?.ifEmpty { null }
        return users.save(user).toOut(props)
    }

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
