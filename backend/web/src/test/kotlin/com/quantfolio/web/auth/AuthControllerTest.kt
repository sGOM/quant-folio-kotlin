package com.quantfolio.web.auth

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.willReturn
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class AuthControllerTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val redis: StringRedisTemplate,
) {
    private fun email() = "${UUID.randomUUID()}@test.dev"

    private fun saveUser(email: String, password: String = "password-1", hash: String = Passwords.hash(password)) =
        users.save(User(email = email, passwordHash = hash))

    @MockitoSpyBean lateinit var props: QuantfolioProperties

    private val ownerFailKey = RedisKeys.loginFailByEmail(OWNER_THROTTLE_KEY)

    @BeforeEach
    fun clearOwnerThrottle() {
        redis.delete(ownerFailKey)
    }

    /** 내부 소유자(`TRADING_OWNER_USER_ID`)로 지정한 사용자. */
    private fun ownerUser(hash: String = Passwords.hash("password-1")) =
        saveUser(email(), hash = hash).also { willReturn(it.id).given(props).tradingOwnerUserId }

    private fun login(username: String?, password: String, ip: String = UUID.randomUUID().toString()) =
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            username?.let { param("username", it) }
            param("password", password)
            header("X-Forwarded-For", ip)
        }

    private fun sessionFor(user: User) = Cookie(SESSION_COOKIE, sessions.create(user.id!!))

    @Test
    fun registerIsRejectedWithoutCreatingUser() {
        val before = users.count()
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"${email()}","password":"password-1"}"""
        }.andExpect { status { isForbidden() }; jsonPath("$.detail") { value("회원가입은 지원하지 않습니다.") } }
        assertEquals(before, users.count())
    }

    @Test
    fun loginWithPasswordOnlyAsOwnerSetsCookieAndResets() {
        val owner = ownerUser()
        redis.opsForValue().set(ownerFailKey, "3")
        val result = login(null, "password-1").andExpect {
            status { isOk() }
            jsonPath("$.id") { value(owner.id!!) }
            jsonPath("$.email") { value(owner.email) }
            cookie { exists(SESSION_COOKIE); httpOnly(SESSION_COOKIE, true); path(SESSION_COOKIE, "/") }
        }.andReturn()
        val sid = result.response.getCookie(SESSION_COOKIE)!!.value
        assertEquals(owner.id, sessions.userId(sid))
        assertNull(redis.opsForValue().get(ownerFailKey))
    }

    @Test
    fun usernameCannotSelectAnotherUser() {
        val owner = ownerUser()
        val other = saveUser(email(), password = "other-pass-1")
        // 다른 사용자의 올바른 이메일+비밀번호로는 들어갈 수 없다.
        login(other.email, "other-pass-1").andExpect { status { isUnauthorized() } }
        // username 이 무엇이든 소유자 비밀번호면 소유자로 로그인한다.
        login(other.email, "password-1").andExpect { status { isOk() }; jsonPath("$.id") { value(owner.id!!) } }
    }

    @Test
    fun loginWrongPassword401CountsOwnerKey() {
        ownerUser()
        login(null, "wrong-pass").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("비밀번호가 올바르지 않습니다.") }
        }
        assertEquals("1", redis.opsForValue().get(ownerFailKey))
    }

    @Test
    fun loginFailsWhenOwnerUnsetOrMissing() {
        saveUser(email())
        for (ownerId in listOf(null, Int.MAX_VALUE)) {
            willReturn(ownerId).given(props).tradingOwnerUserId
            login(null, "password-1").andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.detail") { value("로그인을 사용할 수 없습니다. 서버 설정을 확인하세요.") }
                cookie { doesNotExist(SESSION_COOKIE) }
            }
        }
    }

    @Test
    fun loginBlockedAfterTenFailuresEvenWithChangingUsername() {
        ownerUser()
        repeat(10) { login(email(), "wrong-pass").andExpect { status { isUnauthorized() } } }
        // 올바른 비밀번호·새 username·새 IP 여도 차단 상태면 429.
        login(email(), "password-1").andExpect {
            status { isTooManyRequests() }
            jsonPath("$.detail") { value("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.") }
        }
    }

    @Test
    fun nonOwnerSessionIsRejected() {
        val owner = ownerUser()
        val other = saveUser(email())
        mvc.get("/api/auth/me") { cookie(sessionFor(other)) }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("자격 증명을 확인할 수 없습니다.") }
        }
        val ownerCookie = sessionFor(owner)
        mvc.get("/api/auth/me") { cookie(ownerCookie) }.andExpect { status { isOk() } }
        willReturn(null).given(props).tradingOwnerUserId
        mvc.get("/api/auth/me") { cookie(ownerCookie) }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun logoutDestroysAndClears() {
        val user = ownerUser()
        val cookie = sessionFor(user)
        mvc.post("/api/auth/logout") { cookie(cookie) }.andExpect {
            status { isNoContent() }
            cookie { maxAge(SESSION_COOKIE, 0) }
        }
        assertNull(sessions.userId(cookie.value))
    }

    @Test
    fun logoutWithoutCookie204() {
        mvc.post("/api/auth/logout").andExpect { status { isNoContent() } }
    }

    @Test
    fun meRequiresSession401() {
        mvc.get("/api/auth/me").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("자격 증명을 확인할 수 없습니다.") }
        }
        mvc.get("/api/auth/me") { cookie(Cookie(SESSION_COOKIE, "bogus")) }.andExpect { status { isUnauthorized() } }
    }

    /** 소유자라도 서버 설정이 없으면 자격증명·계좌는 없음으로 표시한다. */
    @Test
    fun meShowsNoCredentialsWithoutServerSettings() {
        val user = ownerUser()
        mvc.get("/api/auth/me") { cookie(sessionFor(user)) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(user.id!!) }
            jsonPath("$.has_kis_credentials") { value(false) }
            jsonPath("$.has_toss_quote") { value(false) }
            jsonPath("$.kis_account_no") { value(null) }
        }
    }

    @Test
    fun patchMeTrims() {
        val user = ownerUser()
        mvc.patch("/api/auth/me") {
            cookie(sessionFor(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name":"  닉네임  "}"""
        }.andExpect { status { isOk() }; jsonPath("$.display_name") { value("닉네임") } }
    }

    @Test
    fun patchMeBlankToNull() {
        val user = ownerUser()
        user.displayName = "old"
        users.save(user)
        mvc.patch("/api/auth/me") {
            cookie(sessionFor(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name":"   "}"""
        }.andExpect { status { isOk() }; jsonPath("$.display_name") { value(null) } }
    }

    @Test
    fun existingPythonUserCanLogin() {
        ownerUser(hash = "\$2b\$12\$tzU.mBUX9gtZUw3/q0ZafehPvEa.xtjVjhvJqfSPzzETSeaPxJC3y")
        login(null, "correct horse 8").andExpect { status { isOk() } }
    }
}
