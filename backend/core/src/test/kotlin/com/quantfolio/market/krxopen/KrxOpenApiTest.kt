package com.quantfolio.market.krxopen

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 합성 값만 쓴다(KRX 데이터 재배포 제한). */
class KrxOpenApiTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T23:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val cooldowns = Cooldowns(clock)
    private val day = LocalDate.of(2026, 11, 20)
    private fun api(key: String = "TESTKEY") = KrxOpenApi(http, QuantfolioProperties(krxOpen = QuantfolioProperties.KrxOpen(apiKey = key)), cooldowns)

    private fun daily(sym: String = "5930", close: String = "1,234", open: String = "-", high: String = "0") =
        """{"OutBlock_1":[{"BAS_DD":"20261120","ISU_CD":"$sym","ISU_NM":"가짜","MKT_NM":"KOSPI","TDD_CLSPRC":"$close",
        "CMPPREVDD_PRC":"-50","FLUC_RT":"-1.0","TDD_OPNPRC":"$open","TDD_HGPRC":"$high","TDD_LWPRC":"1,000",
        "ACC_TRDVOL":"1,000,000","ACC_TRDVAL":"123,456,789","MKTCAP":"9,999,999,999","LIST_SHRS":"1,000"}]}"""

    @Test
    fun `parses daily row with commas and dashes`() {
        http.onText({ it.url == "https://data-dbg.krx.co.kr/svc/apis/sto/stk_bydd_trd" }, body = daily())
        val r = api().stockDaily(day, "KOSPI").single()
        assertEquals(1234.0, r.close)
        assertNull(r.open)
        assertEquals(0.0, r.high)
        assertEquals(-50.0, r.prevDiff)
        assertEquals(1_000_000L, r.volume)
        assertEquals(9_999_999_999L, r.marketCap)
        assertEquals(day, r.day)
        val c = http.calls.single()
        assertEquals("20261120", c.query["basDd"])
        assertEquals("TESTKEY", c.headers["AUTH_KEY"])
    }

    @Test
    fun `kosdaq uses ksq service`() {
        http.onText({ it.url.endsWith("sto/ksq_bydd_trd") }, body = daily())
        assertEquals(1, api().stockDaily(day, "KOSDAQ").size)
    }

    @Test
    fun `symbol is 6-digit padded`() {
        http.onText({ true }, body = daily(sym = "5930"))
        assertEquals("005930", api().stockDaily(day, "KOSPI").single().symbol)
    }

    @Test
    fun `base info uses short code and keeps stock kind raw`() {
        http.onText({ it.url.endsWith("sto/stk_isu_base_info") }, body = """{"OutBlock_1":[{"ISU_CD":"KR7000000001","ISU_SRT_CD":"000001",
            "ISU_NM":"가짜보통","ISU_ABBRV":"가짜","MKT_TP_NM":"KOSPI","KIND_STKCERT_TP_NM":"보통주","LIST_SHRS":"10"}]}""")
        val r = api().stockBase(day, "KOSPI").single()
        assertEquals(OpenBaseRow("000001", "가짜", "KOSPI", "보통주"), r)
    }

    @Test
    fun `index daily`() {
        http.onText({ it.url.endsWith("idx/kospi_dd_trd") }, body = """{"OutBlock_1":[{"BAS_DD":"20261120","IDX_CLSS":"KOSPI","IDX_NM":"가짜지수",
            "CLSPRC_IDX":"2,500.55","CMPPREVDD_IDX":"1","FLUC_RT":"0.1","OPNPRC_IDX":"-","HGPRC_IDX":"2,600.00","LWPRC_IDX":"2,400.00"}]}""")
        val r = api().indexDaily(day, "KOSPI").single()
        assertEquals(2500.55, r.close)
        assertNull(r.open)
        assertEquals("가짜지수", r.name)
    }

    @Test
    fun `empty OutBlock is empty list not error`() {
        http.onText({ true }, body = """{"OutBlock_1":[]}""")
        assertTrue(api().stockDaily(day, "KOSPI").isEmpty())
    }

    @Test
    fun `missing required key is schema error`() {
        http.onText({ true }, body = """{"OutBlock_1":[{"BAS_DD":"20261120","ISU_CD":"5930"}]}""")
        assertThrows<SourceSchemaError> { api().stockDaily(day, "KOSPI") }
    }

    @Test
    fun `missing OutBlock is schema error`() {
        http.onText({ true }, body = """{"respMsg":"x"}""")
        assertThrows<SourceSchemaError> { api().stockDaily(day, "KOSPI") }
    }

    @Test
    fun `html body is SourceSchemaError`() {
        http.onText({ true }, body = "<html>점검중</html>")
        assertThrows<SourceSchemaError> { api().stockDaily(day, "KOSPI") }
    }

    @Test
    fun `401 is SourceAuthError`() {
        http.onText({ true }, status = 401, body = """{"respMsg":"Unauthorized API Call","respCode":"401"}""")
        assertThrows<SourceAuthError> { api().stockDaily(day, "KOSPI") }
        assertThrows<SourceQuotaError> { api().stockDaily(day, "KOSPI") } // 쿨다운 중이면 요청 없이 실패(OpenDART 와 같은 방식)
        assertEquals(1, http.calls.size)
    }

    @Test
    fun `blank api key throws SourceAuthError before any http call`() {
        assertThrows<SourceAuthError> { api("").stockDaily(day, "KOSPI") }
        assertTrue(http.calls.isEmpty())
    }
}
