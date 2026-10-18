package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.FakeKrxTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.assertEquals

class KrxMdcTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC)
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val session = KrxSession(transport, QuantfolioProperties(), cooldowns, clock)
    private val sleeps = mutableListOf<Duration>()
    private val mdc = KrxMdc(transport, session, cooldowns) { sleeps += it }

    private val payload = mapOf("bld" to "dbms/X", "trdDd" to "20260101")

    private fun respond(status: Int, body: String) = transport.on({ it.url == KrxMdc.JSON_URL }) { HttpResult(status, body) }

    @Test
    fun postsBldWithHeaders() {
        respond(200, """{"output":[{"ISU_SRT_CD":"005930"}]}""")
        assertEquals(listOf(mapOf<String, Any?>("ISU_SRT_CD" to "005930")), mdc.rows(payload, "output", "테스트"))
        val call = transport.calls.single()
        assertEquals(payload, call.form)
        assertEquals("https://data.krx.co.kr/contents/MDC/MDI/outerLoader/index.cmd", call.headers["Referer"])
        assertEquals("XMLHttpRequest", call.headers["X-Requested-With"])
        assertEquals(KrxSession.USER_AGENT, call.headers["User-Agent"])
    }

    @Test
    fun htmlResponseIsAuth() {
        respond(200, "<html>차단</html>")
        assertThrows<SourceAuthError> { mdc.rows(payload, "output", "테스트") }
        assertEquals(300.0, cooldowns.remaining("krx"))
    }

    @Test
    fun missingKeyIsSchema() {
        respond(200, """{"block1":[]}""")
        assertThrows<SourceSchemaError> { mdc.rows(payload, "output", "테스트") }
    }

    @Test
    fun nonListIsSchema() {
        respond(200, """{"output":[1,2]}""")
        assertThrows<SourceSchemaError> { mdc.rows(payload, "output", "테스트") }
    }

    @Test
    fun nullKeyIsEmpty() {
        respond(200, """{"output":null}""")
        assertEquals(emptyList(), mdc.rows(payload, "output", "테스트"))
    }

    @Test
    fun http503IsUnavailable() {
        respond(503, "")
        assertThrows<SourceUnavailableError> { mdc.rows(payload, "output", "테스트") }
        assertEquals(60.0, cooldowns.remaining("krx"))
    }

    @Test
    fun http404IsRequest() {
        respond(404, "")
        assertThrows<SourceRequestError> { mdc.rows(payload, "output", "테스트") }
    }

    @Test
    fun connectionFailureIsUnavailable() {
        transport.failWith { it.url == KrxMdc.JSON_URL }
        assertThrows<SourceUnavailableError> { mdc.rows(payload, "output", "테스트") }
    }

    @Test
    fun cooldownSkipsRequest() {
        cooldowns.noteFailure(SourceUnavailableError("krx", "x"))
        assertThrows<SourceAuthError> { mdc.rows(payload, "output", "테스트") }
        assertEquals(0, transport.calls.size)
    }

    @Test
    fun pykrxSplitsLongRanges() {
        respond(200, """{"output":[{"d":"x"}]}""")
        val rows = mdc.pykrx("dbms/Y", mapOf("strtDd" to "20200101", "endDd" to "20241231", "mktId" to "STK"), "output")
        assertEquals(3, rows.size)
        val ranges = transport.calls.map { it.form["strtDd"] to it.form["endDd"] }
        // pandas Timedelta("730 days") 단위: 2020-01-01+730 = 2021-12-31, 2022-01-01+730 = 2024-01-01
        assertEquals(listOf("20200101" to "20211231", "20220101" to "20240101", "20240102" to "20241231"), ranges)
        assertEquals(List(2) { Duration.ofSeconds(1) }, sleeps)
        assertEquals("dbms/Y", transport.calls.first().form["bld"])
        assertEquals("Mozilla/5.0", transport.calls.first().headers["User-Agent"])
    }

    @Test
    fun pykrxMultiChunkNonOutputKeyFails() {
        // 원본 pykrx 는 조각을 항상 result["output"] 에 이어붙여, OutBlock_1 응답은 2년 초과 조회에서 KeyError 로 실패한다(보존).
        respond(200, """{"OutBlock_1":[{"d":"x"}]}""")
        assertThrows<IllegalStateException> {
            mdc.pykrx("dbms/Y", mapOf("strtDd" to "20200101", "endDd" to "20241231"), "OutBlock_1")
        }
    }

    @Test
    fun pykrxShortRangeSingleCall() {
        respond(200, """{"output":[]}""")
        mdc.pykrx("dbms/Y", mapOf("strtDd" to "20240101", "endDd" to "20241231"), "output")
        assertEquals(1, transport.calls.size)
        assertEquals(0, sleeps.size)
    }
}
