package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.test.FakeKrxTransport
import com.quantfolio.market.krx.HttpResult
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class KrxSessionTest {
    private class MutableClock(var now: Instant) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId?) = this
        override fun instant() = now
    }

    private val clock = MutableClock(Instant.parse("2026-10-19T00:00:00Z"))
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val withCreds = QuantfolioProperties(krx = QuantfolioProperties.Krx(id = "me", pw = "pw"))

    private fun session(props: QuantfolioProperties = withCreds) = KrxSession(transport, props, cooldowns, clock)

    private fun loginReplies(vararg codes: String) {
        val queue = ArrayDeque(codes.toList())
        transport.on({ it.url == KrxSession.LOGIN_URL }) { HttpResult(200, """{"_error_code":"${queue.removeFirst()}"}""") }
    }

    private val logins get() = transport.calls.count { it.url == KrxSession.LOGIN_URL }

    @Test
    fun unsetCredentialsReturnsNull() {
        assertNull(session(QuantfolioProperties()).current())
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun loginFlowSendsWarmupThenPost() {
        loginReplies("CD001")
        assertEquals(true, session().current())
        val (warm1, warm2, post) = transport.calls
        assertEquals("GET" to KrxSession.LOGIN_PAGE, warm1.method to warm1.url)
        assertEquals("GET" to KrxSession.LOGIN_JSP, warm2.method to warm2.url)
        assertEquals(KrxSession.LOGIN_PAGE, warm2.headers["Referer"])
        assertEquals(KrxSession.LOGIN_URL, post.url)
        assertEquals(mapOf("mbrNm" to "", "telNo" to "", "di" to "", "certType" to "", "mbrId" to "me", "pw" to "pw"), post.form)
    }

    @Test
    fun duplicateLoginRetriesWithSkipDup() {
        loginReplies("CD011", "CD001")
        assertEquals(true, session().current())
        val posts = transport.calls.filter { it.url == KrxSession.LOGIN_URL }
        assertEquals(2, posts.size)
        assertEquals("Y", posts[1].form["skipDup"])
    }

    @Test
    fun loginFailureIsAuthAndCoolsDown() {
        loginReplies("CD010")
        assertThrows<SourceAuthError> { session().current() }
        assertEquals(300.0, cooldowns.remaining("krx"))
    }

    @Test
    fun cooldownCheckedBeforeCachedSession() {
        loginReplies("CD001")
        val s = session()
        s.current()
        cooldowns.noteFailure(SourceAuthError("krx", "차단"))
        assertThrows<SourceAuthError> { s.current() }
        assertEquals(1, logins)
    }

    @Test
    fun sessionReusedUntilExpiryMinusBuffer() {
        loginReplies("CD001", "CD001")
        val s = session()
        s.current()
        clock.now = clock.now.plus(Duration.ofSeconds(3299))
        s.current()
        assertEquals(1, logins)
        clock.now = clock.now.plus(Duration.ofSeconds(2))
        s.current()
        assertEquals(2, logins)
    }

    @Test
    fun successClearsCooldown() {
        loginReplies("CD001")
        cooldowns.noteFailure(SourceAuthError("krx", "x"))
        clock.now = clock.now.plus(Duration.ofSeconds(301))
        session().current()
        assertEquals(0.0, cooldowns.remaining("krx"))
    }

    @Test
    fun requireAuthMessages() {
        val unset = assertThrows<SourceAuthError> { session(QuantfolioProperties()).requireAuth() }
        assertEquals("KRX_ID/KRX_PW 가 설정되지 않았다 — PIT 유니버스 백테스트에는 필수다", unset.detail)

        loginReplies("CD010")
        val s = session()
        val cooling = assertThrows<SourceAuthError> { s.requireAuth() }
        assertTrue(cooling.detail.startsWith("KRX 로그인 실패 후 쿨다운 중("))
        assertFalse(s.hasAuth())
    }
}
