package com.quantfolio.market.kofia

import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KofiaClientTest {
    private val http = FakeExternalHttp()
    private val cooldowns = Cooldowns(Clock.systemUTC())
    private val client = KofiaClient(http, cooldowns)
    private val start = LocalDate.of(2026, 6, 1)
    private val end = LocalDate.of(2026, 6, 30)

    private fun respond(body: String, status: Int = 200) = http.onText({ it.url == KofiaClient.URL }, status, body)

    @Test
    fun marketFundsMapsColumnsAndSorts() {
        respond("""{"ds1":[
            {"TMPV1":"20260603","TMPV5":"1000","TMPV6":"50","TMPV7":"5.5","TMPV2":"x"},
            {"TMPV1":"20260602","TMPV5":"","TMPV6":"40","TMPV7":"4.0"},
            {"TMPV1":"bad","TMPV5":"1"}]}""")
        val rows = client.marketFunds(start, end)
        assertEquals(listOf(LocalDate.of(2026, 6, 2), LocalDate.of(2026, 6, 3)), rows.map { it.asOf })
        assertNull(rows[0].receivable)
        assertEquals(1000.0 to 5.5, rows[1].receivable to rows[1].forcedSaleRatio)
        assertEquals("x", rows[1].raw["TMPV2"])
        val call = http.calls.single()
        assertEquals("""{"dmSearch":{"tmpV40":"1","tmpV41":"1","tmpV45":"20260601","tmpV46":"20260630","OBJ_NM":"STATSCU0100000060BO"}}""", call.body)
        assertEquals("https://freesis.kofia.or.kr/", call.headers["Referer"])
    }

    @Test
    fun creditBalanceUsesOtherTable() {
        respond("""{"ds1":[{"TMPV1":"20260602","TMPV2":"20000","TMPV3":"8000","TMPV4":"12000"}]}""")
        val row = client.creditBalance(start, end).single()
        assertEquals(Triple(20000.0, 8000.0, 12000.0), Triple(row.total, row.partA, row.partB))
        assert(http.calls.single().body!!.contains("STATSCU0100000070BO"))
    }

    @Test
    fun nullDs1IsEmpty() {
        respond("""{"ds1":null}""")
        assertEquals(emptyList(), client.marketFunds(start, end))
    }

    @Test
    fun missingDs1IsSchema() {
        respond("""{"other":[]}""")
        assertThrows<SourceSchemaError> { client.marketFunds(start, end) }
    }

    @Test
    fun serverErrorIsUnavailableAndCoolsDown() {
        respond("", 502)
        assertThrows<SourceUnavailableError> { client.marketFunds(start, end) }
        assertThrows<SourceQuotaError> { client.marketFunds(start, end) } // 쿨다운 중
        assertEquals(1, http.calls.size)
    }

    @Test
    fun startAfterEndRejected() {
        assertThrows<IllegalArgumentException> { client.marketFunds(end, start) }
    }
}
