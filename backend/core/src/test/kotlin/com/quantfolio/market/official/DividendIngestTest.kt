package com.quantfolio.market.official

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.market.datagokr.DataGoKrDividendApi
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.market.store.DividendStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 합성 값만 쓴다(공공데이터 재배포 제한). 종목코드는 테스트마다 유일한 6자리 숫자. */
@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class DividendIngestTest(@Autowired private val store: DividendStore) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val ledger = InMemoryFetchLedger()
    private val id = UUID.randomUUID().toString().filter { it.isDigit() }.padEnd(6, '3')
    private var seq = 0
    private val key = "A+B/c=KEY"

    private fun newSymbol() = id.take(5) + (seq++)

    private fun api(h: FakeExternalHttp = http, apiKey: String = key, pageSize: Int = 2) = DataGoKrDividendApi(
        h, QuantfolioProperties(dataGoKr = QuantfolioProperties.DataGoKr(apiKey = apiKey, pageSize = pageSize)), Cooldowns(clock),
    )

    private fun item(symbol: String, record: String, amt: String, rcd: String = "02") =
        """{"basDt":"20260101","crno":"1","isinCd":"KR7${symbol}003","isinCdNm":"가짜","scrsItmsKcd":"0101","dvdnBasDt":"$record",
        "cashDvdnPayDt":"20260401","stckDvdnRcd":"$rcd","stckGenrDvdnAmt":"$amt","stckGrdnDvdnAmt":"0","stckGenrCashDvdnRt":"1.0"}"""

    private fun page(items: List<String>, total: Int, pageNo: Int) =
        """{"response":{"header":{"resultCode":"00","resultMsg":"M"},"body":{"items":{"item":[${items.joinToString(",")}]},"numOfRows":2,"pageNo":$pageNo,"totalCount":$total}}}"""

    private fun serve(pages: Map<Int, String>) {
        for ((n, body) in pages) http.onText({ it.query["pageNo"] == n.toString() }, body = body)
    }

    private fun stored(s: String) = store.read(listOf(s), LocalDate.of(2000, 1, 1), LocalDate.of(2100, 1, 1))

    @Test
    fun `pages through everything keeps only positive cash dividends and marks the full ingest final`() {
        val a = newSymbol(); val b = newSymbol(); val c = newSymbol()
        serve(
            mapOf(
                1 to page(listOf(item(a, "20251231", "361"), item(a, "20250630", "0")), 5, 1),
                2 to page(listOf(item(b, "20251231", "1,200"), item(b, "20241231", "500", rcd = "01")), 5, 2),
                3 to page(listOf(item(c, "20251231", "77")), 5, 3),
            ),
        )
        val r = DividendIngest(api(), store, ledger).run()
        assertEquals(3, r.stored)
        assertEquals(3, http.calls.size)
        val c1 = http.calls.first()
        assertEquals(key, c1.query["serviceKey"])
        assertEquals("json", c1.query["resultType"])
        assertEquals("2", c1.query["numOfRows"])
        assertFalse(c1.query.containsKey("basDt"))
        val ea = stored(a).single()
        assertEquals(LocalDate.of(2025, 12, 31), ea.recordDate)
        assertEquals(361.0, ea.dps)
        assertNull(ea.announcedOn)
        assertEquals("datagokr", ea.source)
        assertEquals(1200.0, stored(b).single().dps)
        val l = ledger.get("datagokr", "dividends_full")!!
        assertTrue(l.final)
        assertEquals(3, l.rowCount)
    }

    @Test
    fun `a failing page fails the run and leaves no rows and no ledger`() {
        val a = newSymbol()
        serve(mapOf(1 to page(listOf(item(a, "20251231", "361"), item(a, "20241231", "300")), 4, 1)))
        http.onText({ it.query["pageNo"] == "2" }, status = 500, body = "boom")
        assertThrows<SourceUnavailableError> { DividendIngest(api(), store, ledger).run() }
        assertTrue(stored(a).isEmpty())
        assertNull(ledger.get("datagokr", "dividends_full"))
    }

    @Test
    fun `duplicate record dates keep the last value and count the disagreement`() {
        val a = newSymbol()
        serve(
            mapOf(
                1 to page(listOf(item(a, "20251231", "100"), item(a, "20251231", "120")), 3, 1),
                2 to page(listOf(item(a, "20251231", "120")), 3, 2),
            ),
        )
        val r = DividendIngest(api(), store, ledger).run()
        assertEquals(1, r.stored)
        assertEquals(1, r.conflicts)
        assertEquals(120.0, stored(a).single().dps)
    }

    @Test
    fun `result codes map to source errors and the key is masked`() {
        fun codeBody(code: String) = """{"response":{"header":{"resultCode":"$code","resultMsg":"A+B/c=KEY 거부"},"body":{}}}"""
        for ((code, cls) in listOf("30" to SourceAuthError::class, "22" to SourceQuotaError::class, "05" to SourceUnavailableError::class, "77" to SourceSchemaError::class)) {
            val h = FakeExternalHttp()
            h.onText({ true }, body = codeBody(code))
            val e = assertThrows<Exception> { api(h).page(1) }
            assertEquals(cls, e::class, "code $code")
            assertFalse(e.message!!.contains(key), "key leaked for $code")
        }
    }

    @Test
    fun `gateway error body on http 400 is a request error and blank key is auth`() {
        http.onText({ true }, status = 400, body = """{"OpenAPI_ServiceResponse":{"cmmMsgHeader":{"errMsg":"NO_OPENAPI_SERVICE_ERROR","returnReasonCode":"12"}}}""")
        assertThrows<SourceRequestError> { api().page(1) }
        assertThrows<SourceAuthError> { api(apiKey = "").page(1) }
    }
}
