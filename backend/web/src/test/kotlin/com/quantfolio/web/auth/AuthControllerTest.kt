package com.quantfolio.web.auth

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.patch
import org.springframework.test.web.servlet.post
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    private fun login(email: String, password: String, ip: String = UUID.randomUUID().toString()) =
        mvc.post("/api/auth/login") {
            contentType = MediaType.APPLICATION_FORM_URLENCODED
            param("username", email)
            param("password", password)
            header("X-Forwarded-For", ip)
        }

    private fun sessionFor(user: User) = Cookie(SESSION_COOKIE, sessions.create(user.id!!))

    @Test
    fun registerHashesPassword() {
        val e = email()
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$e","password":"password-1"}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.email") { value(e) }
            jsonPath("$.display_name") { value(null) }
            jsonPath("$.broker") { value("kis") }
            jsonPath("$.has_kis_credentials") { value(false) }
            jsonPath("$.has_toss_quote") { value(false) }
        }
        val saved = users.findByEmail(e)!!
        assertTrue(saved.passwordHash != "password-1" && Passwords.verify("password-1", saved.passwordHash))
    }

    @Test
    fun registerDuplicate409() {
        val e = email()
        saveUser(e)
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$e","password":"password-1"}"""
        }.andExpect { status { isConflict() }; jsonPath("$.detail") { value("이미 등록된 이메일입니다.") } }
    }

    @Test
    fun registerShortPassword422() {
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"${email()}","password":"short"}"""
        }.andExpect { status { isUnprocessableContent() }; jsonPath("$.detail[0].loc[1]") { value("password") } }
    }

    @Test
    fun registerInvalidEmail422() {
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"not-an-email","password":"password-1"}"""
        }.andExpect { status { isUnprocessableContent() }; jsonPath("$.detail[0].loc[1]") { value("email") } }
    }

    /** 원본 EmailStr(pydantic+email-validator) 정규화 — 앞뒤 공백 제거·도메인 소문자, 도메인에 점 없으면 422(원본 컨테이너 실측). */
    @Test
    fun registerNormalizesEmailLikeOriginal() {
        val local = "N" + UUID.randomUUID().toString().take(8)
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"  $local@Test.DEV ","password":"password-1"}"""
        }.andExpect { status { isCreated() }; jsonPath("$.email") { value("$local@test.dev") } }
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"$local@TEST.dev","password":"password-1"}"""
        }.andExpect { status { isConflict() } }
        mvc.post("/api/auth/register") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"email":"a@localhost","password":"password-1"}"""
        }.andExpect { status { isUnprocessableContent() }; jsonPath("$.detail[0].loc[1]") { value("email") } }
    }

    @Test
    fun loginSetsCookieAndResets() {
        val e = email()
        saveUser(e)
        redis.opsForValue().set(RedisKeys.loginFailByEmail(e), "3")
        val result = login(e, "password-1").andExpect {
            status { isOk() }
            jsonPath("$.email") { value(e) }
            cookie { exists(SESSION_COOKIE); httpOnly(SESSION_COOKIE, true); path(SESSION_COOKIE, "/") }
        }.andReturn()
        val sid = result.response.getCookie(SESSION_COOKIE)!!.value
        assertNotNull(sessions.userId(sid))
        assertNull(redis.opsForValue().get(RedisKeys.loginFailByEmail(e)))
    }

    @Test
    fun loginWrongPassword401Counts() {
        val e = email()
        saveUser(e)
        login(e, "wrong-pass").andExpect {
            status { isUnauthorized() }
            jsonPath("$.detail") { value("이메일 또는 비밀번호가 올바르지 않습니다.") }
        }
        assertEquals("1", redis.opsForValue().get(RedisKeys.loginFailByEmail(e)))
    }

    @Test
    fun loginUnknownEmail401Counts() {
        val e = email()
        login(e, "whatever").andExpect { status { isUnauthorized() } }
        assertEquals("1", redis.opsForValue().get(RedisKeys.loginFailByEmail(e)))
    }

    @Test
    fun loginBlocked429() {
        val e = email()
        saveUser(e)
        redis.opsForValue().set(RedisKeys.loginFailByEmail(e), "10")
        // 올바른 비밀번호여도 차단 상태면 429.
        login(e, "password-1").andExpect {
            status { isTooManyRequests() }
            jsonPath("$.detail") { value("로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.") }
        }
    }

    @Test
    fun logoutDestroysAndClears() {
        val user = saveUser(email())
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

    @Test
    fun meReturnsUser() {
        val user = saveUser(email())
        user.kisAppKey = "enc"
        users.save(user)
        mvc.get("/api/auth/me") { cookie(sessionFor(user)) }.andExpect {
            status { isOk() }
            jsonPath("$.id") { value(user.id!!) }
            jsonPath("$.has_kis_credentials") { value(true) }
        }
    }

    @Test
    fun patchMeTrims() {
        val user = saveUser(email())
        mvc.patch("/api/auth/me") {
            cookie(sessionFor(user))
            contentType = MediaType.APPLICATION_JSON
            content = """{"display_name":"  닉네임  "}"""
        }.andExpect { status { isOk() }; jsonPath("$.display_name") { value("닉네임") } }
    }

    @Test
    fun patchMeBlankToNull() {
        val user = saveUser(email())
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
        val e = email()
        saveUser(e, hash = "\$2b\$12\$tzU.mBUX9gtZUw3/q0ZafehPvEa.xtjVjhvJqfSPzzETSeaPxJC3y")
        login(e, "correct horse 8").andExpect { status { isOk() } }
    }
}
