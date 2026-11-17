package com.quantfolio.engine

import com.quantfolio.broker.BrokerHttp
import com.quantfolio.broker.BrokerResponse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 모의투자 확인 도구([KisProbe]) — 가짜 전송으로 vts 고정·읽기 전용·마스킹·판정 요약을 확인한다. 실제 KIS 에 접속하지 않는다. */
class KisProbeTest {
    private class Call(val method: String, val url: String, val headers: Map<String, String>, val json: String?)

    private class FakeHttp(val respond: (String, Map<String, String>, Map<String, String>) -> BrokerResponse) : BrokerHttp {
        val calls = mutableListOf<Call>()
        override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
            calls += Call(method, url, headers, json)
            return respond(url, query, headers)
        }
    }

    private val mapper = JsonMapper.builder().build()

    private fun body(c: Call): Map<String, Any?> = mapper.readValue(c.json!!)

    private fun FakeHttp.orderPosts() = calls.filter { "order-cash" in it.url || "order-rvsecncl" in it.url }

    private val clock = Clock.fixed(Instant.parse("2026-11-16T01:00:00Z"), ZoneOffset.UTC)
    private val env = mapOf(
        "KIS_PROBE_APP_KEY" to "APPKEY-abcdef", "KIS_PROBE_APP_SECRET" to "SECRET-ghijkl",
        "KIS_PROBE_ACCOUNT_NO" to "50012345-01", "KIS_PROBE_HTS_ID" to "htsuser9",
    )
    private val secretValues = listOf("APPKEY-abcdef", "SECRET-ghijkl", "50012345", "5001234501", "htsuser9", "TOKEN-xyz-777")

    private fun ok(body: String, trCont: String? = null) = BrokerResponse(200, body, trCont?.let { mapOf("tr_cont" to listOf(it)) }.orEmpty())

    private fun tokenOr(url: String, other: () -> BrokerResponse) =
        if (url.endsWith("/oauth2/tokenP")) ok("""{"access_token":"TOKEN-xyz-777","expires_in":86400}""") else other()

    private fun assertSafe(http: FakeHttp, out: List<String>, orders: Int = 0, tokens: Int = 1) {
        assertTrue(http.calls.all { it.url.startsWith("https://openapivts.koreainvestment.com:29443/") }, "vts 외 주소")
        assertTrue(http.calls.all { it.headers["tr_id"]?.let { t -> t.startsWith("V") || t == "FHKST01010100" } ?: true }, "실전 TR")
        assertEquals(orders, http.orderPosts().size, "주문계 POST 수")
        if (orders == 0) assertTrue(http.calls.none { "hashkey" in it.url }, "hashkey")
        assertEquals(tokens, http.calls.count { it.url.endsWith("/oauth2/tokenP") })
        val text = out.joinToString("\n")
        secretValues.forEach { assertFalse(it in text, "출력에 비밀 값: $it\n$text") }
    }

    @Test
    fun missingEnvOrBadArgsPrintUsageWithoutRequests() {
        for ((args, e) in listOf(listOf("orders") to emptyMap(), emptyList<String>() to env, listOf("orders", "2026-11-16") to env, listOf("notices", "0") to env)) {
            val http = FakeHttp { _, _, _ -> error("요청이 나가면 안 된다") }
            val out = mutableListOf<String>()
            assertEquals(2, KisProbe(e, http, out::add, clock).run(args))
            assertTrue(out.any { "사용법" in it })
            assertTrue(http.calls.isEmpty())
        }
        // 형식이 틀린 계좌번호는 값을 출력하지 않는다(KisClient 의 오류 메시지는 원문을 담는다).
        val out = mutableListOf<String>()
        assertEquals(2, KisProbe(env + ("KIS_PROBE_ACCOUNT_NO" to "1234-5678"), FakeHttp { _, _, _ -> error("x") }, out::add, clock).run(listOf("balance")))
        assertFalse(out.any { "1234" in it })
    }

    @Test
    fun ordersPrintsPagesAndJudgesOriginalOrderNumbers() {
        val http = FakeHttp { url, query, _ ->
            tokenOr(url) {
                assertEquals("20261116", query["INQR_STRT_DT"])
                assertEquals("ALL", query["EXCG_ID_DVSN_CD"])
                if (query["CTX_AREA_NK100"].isNullOrEmpty()) {
                    ok("""{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","ctx_area_fk100":"F1","ctx_area_nk100":"N1","output1":[
                        {"ord_dt":"20261116","odno":"0000012345","orgn_odno":"","sll_buy_dvsn_cd":"02","pdno":"005930","ord_qty":"2","tot_ccld_qty":"2","avg_prvs":"70000","ord_tmd":"090102","rvse_cncl_dvsn_name":"","ctac_tlno":"01099998888"}]}""", "F")
                } else {
                    ok("""{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[
                        {"ord_dt":"20261116","odno":"0000012346","orgn_odno":"0000012345","sll_buy_dvsn_cd":"02","pdno":"005930","ord_qty":"1","tot_ccld_qty":"0","ord_tmd":"090500"},
                        {"ord_dt":"20261116","odno":"0000012347","orgn_odno":"12345","sll_buy_dvsn_cd":"02","pdno":"005930","ord_qty":"1","tot_ccld_qty":"0","ord_tmd":"090600"}]}""", "D")
                }
            }
        }
        val out = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http, out::add, clock).run(listOf("orders")))
        val text = out.joinToString("\n")
        assertSafe(http, out)
        assertTrue("페이지 1 [VTTC0081R] HTTP 200, 응답 헤더 tr_cont=\"F\"" in text, text)
        assertTrue("페이지 2 [VTTC0081R] HTTP 200, 응답 헤더 tr_cont=\"D\"" in text, text)
        assertTrue("첫 행의 키 목록(11개): ord_dt, odno, orgn_odno," in text, text)
        assertTrue("rvse_cncl_dvsn_name=\"\"" in text, text) // 키 목록에서 고른 정정·취소 구분 후보
        assertFalse("01099998888" in text) // 요청한 필드·키 이름 밖의 값은 내지 않는다
        assertTrue("마지막 tr_cont: \"D\" (D/E — 완결)" in text, text)
        assertTrue("orgn_odno 가 있는 행(공백·전부 0 제외): 2개" in text, text)
        assertTrue("orgn_odno=\"0000012345\" → 다른 행 odno 와 원문 일치: 예" in text, text)
        assertTrue("orgn_odno=\"12345\" → 다른 행 odno 와 원문 일치: 아니오, trim 일치: 아니오, 앞 0 제거 일치: 예" in text, text)
        assertTrue("행 3개" in text, text) // 우리 파서(dailyOrders) 결과
    }

    @Test
    fun ccldSendsGivenArgsAsQueryOnePageReadOnly() {
        val sent = mutableListOf<Pair<Map<String, String>, String?>>()
        val http = FakeHttp { url, query, headers ->
            tokenOr(url) {
                sent += query to headers["tr_id"]
                ok("""{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","ctx_area_nk100":"N1","output1":[{"odno":"0000012345","orgn_odno":"","ord_gno_brno":"06010","pdno":"005930","ord_qty":"2","tot_ccld_qty":"2","avg_prvs":"70000","ord_tmd":"090102"}]}""", "F")
            }
        }
        val out = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http, out::add, clock).run(listOf("ccld", "VTTC8001R", "0000012345", "005930", "-", "-")))
        val (q1, tr1) = sent.single()
        assertEquals("VTTC8001R", tr1)
        assertEquals("0000012345", q1["ODNO"]); assertEquals("005930", q1["PDNO"]); assertEquals("", q1["ORD_GNO_BRNO"])
        assertFalse("EXCG_ID_DVSN_CD" in q1); assertEquals("20261116", q1["INQR_STRT_DT"])
        val text = out.joinToString(" ")
        assertTrue("output1 행 수: 1" in text && "odno=\"0000012345\"" in text && "ord_gno_brno=\"06010\"" in text, text)
        assertSafe(http, out)
        sent.clear()
        KisProbe(env, http, out::add, clock).run(listOf("ccld", "VTTC0081R", "-", "-", "ALL", "06010", "20261113"))
        val (q2, tr2) = sent.single()
        assertEquals("VTTC0081R", tr2)
        assertEquals("", q2["ODNO"]); assertEquals("", q2["PDNO"]); assertEquals("ALL", q2["EXCG_ID_DVSN_CD"]); assertEquals("06010", q2["ORD_GNO_BRNO"]); assertEquals("20261113", q2["INQR_END_DT"])
        assertTrue(http.calls.none { "hashkey" in it.url || "order-" in it.url })
    }

    @Test
    fun ccldRejectsOtherTrWithoutRequests() {
        for (args in listOf(listOf("ccld", "TTTC8001R", "-", "-", "-", "-"), listOf("ccld", "VTSC9215R", "-", "-", "-", "-"), listOf("ccld", "VTTC0081R", "-", "-", "-"))) {
            val http = FakeHttp { _, _, _ -> error("요청이 나가면 안 된다") }
            val out = mutableListOf<String>()
            assertEquals(2, KisProbe(env, http, out::add, clock).run(args))
            assertTrue(http.calls.isEmpty())
        }
    }

    @Test
    fun ordersShowsMissingTrContAndParserFailure() {
        val http = FakeHttp { url, _, _ -> tokenOr(url) { ok("""{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output1":[]}""") } }
        val out = mutableListOf<String>()
        KisProbe(env, http, out::add, clock).run(listOf("orders", "20261113"))
        val text = out.joinToString("\n")
        assertTrue("tr_cont=헤더 없음" in text, text)
        assertTrue("행 0(원본 주석 '늘 빈 응답'과 같음)" in text, text)
        assertTrue("오류: KisError: 완전한 체결 조회 결과를 확인할 수 없습니다." in text, text)
    }

    @Test
    fun balanceAndSingleOrder() {
        val http = FakeHttp { url, _, headers ->
            tokenOr(url) {
                when (headers["tr_id"]) {
                    "VTTC8434R" -> ok("""{"rt_cd":"0","output1":[{"pdno":"005930","hldg_qty":"3","pchs_avg_pric":"70000.0000"}],"output2":[{}]}""", "D")
                    "VTTC8001R" -> ok("""{"rt_cd":"0","output1":[{"odno":"0000012345","ord_dt":"20261116","ord_qty":"2","tot_ccld_qty":"1","avg_prvs":"70000"}]}""")
                    else -> ok("""{"rt_cd":"0","output1":[{"ord_dt":"20261116","odno":"0000012345","orgn_odno":"","sll_buy_dvsn_cd":"02","pdno":"005930","ord_qty":"2","tot_ccld_qty":"1","avg_prvs":"70000"}]}""", "E")
                }
            }
        }
        val out = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http, out::add, clock).run(listOf("balance")))
        assertTrue(out.any { "보유: pdno=\"005930\" hldg_qty=\"3\" pchs_avg_pric=\"70000.0000\"" in it }, out.joinToString("\n"))
        assertTrue(out.any { "NormalizedPosition(symbol=005930, qty=3, avgPrice=70000.0000)" in it })
        assertSafe(http, out)

        val http2 = FakeHttp(http.respond)
        val out2 = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http2, out2::add, clock).run(listOf("order", "0000012345", "005930")))
        val text = out2.joinToString("\n")
        assertTrue("Fill(filledQty=1, avgPrice=70000, fullyFilled=false, terminal=false)" in text, text)
        assertTrue("단건 filledQty=1, 목록 filledQty=1 → 일치" in text, text)
        assertSafe(http2, out2)
    }

    @Test
    fun guardRefusesProdOrderEndpointsAndReissue() {
        val base = "https://openapivts.koreainvestment.com:29443"
        val delegate = FakeHttp { _, _, _ -> ok("{}") }
        val g = ProbeHttp(delegate)
        assertThrows<IllegalArgumentException> { g.send("GET", "https://openapi.koreainvestment.com:9443/uapi/domestic-stock/v1/trading/inquire-balance") }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-cash") }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-rvsecncl") }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/hashkey") }
        assertThrows<IllegalArgumentException> { g.send("GET", "$base/uapi/domestic-stock/v1/trading/inquire-balance", headers = mapOf("tr_id" to "TTTC8434R")) }
        assertThrows<IllegalArgumentException> { g.send("GET", "$base/uapi/domestic-stock/v1/quotations/inquire-price", headers = mapOf("tr_id" to "FHKST01010200")) }
        g.send("POST", "$base/oauth2/tokenP")
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/oauth2/tokenP") } // 실패해도 성공해도 재발급 없음
        assertEquals(1, delegate.calls.size)

        // 주문 명령 중에도: 실전 호스트·실전 TR·목록 밖 TR 은 거부, 주문계 POST 는 주문·정정취소 합쳐 1건
        g.ordersAllowed = true
        assertThrows<IllegalArgumentException> { g.send("POST", "https://openapi.koreainvestment.com:9443/uapi/domestic-stock/v1/trading/order-cash", headers = mapOf("tr_id" to "VTTC0802U")) }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-cash", headers = mapOf("tr_id" to "TTTC0802U")) }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-cash", headers = mapOf("tr_id" to "VTTC0012U")) }
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-rvsecncl", headers = mapOf("tr_id" to "VTTC0802U")) }
        assertThrows<IllegalArgumentException> { g.send("GET", "$base/uapi/domestic-stock/v1/trading/order-cash", headers = mapOf("tr_id" to "VTTC0802U")) }
        g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-rvsecncl", headers = mapOf("tr_id" to "VTTC0803U"))
        assertThrows<IllegalArgumentException> { g.send("POST", "$base/uapi/domestic-stock/v1/trading/order-cash", headers = mapOf("tr_id" to "VTTC0802U")) }
        assertEquals(2, delegate.calls.size)
    }

    private val quoteBody = """{"rt_cd":"0","msg_cd":"MCA00000","msg1":"정상","output":{"stck_prpr":"70000","stck_mxpr":"91000","stck_llam":"49000","stck_sdpr":"70000","temp_stop_yn":"N","iscd_stat_cls_code":"55"}}"""
    private val orderOk = """{"rt_cd":"0","msg_cd":"APBK0013","msg1":"주문 전송 완료 되었습니다.","output":{"KRX_FWDG_ORD_ORGNO":"00950","ODNO":"0000117057","ORD_TMD":"121052"}}"""

    private fun orderHttp(orderResponse: () -> BrokerResponse = { ok(orderOk) }) = FakeHttp { url, _, _ ->
        tokenOr(url) {
            when {
                url.endsWith("/uapi/hashkey") -> ok("""{"HASH":"HASH-1"}""")
                url.endsWith("/inquire-price") -> ok(quoteBody)
                url.endsWith("/order-cash") || url.endsWith("/order-rvsecncl") -> orderResponse()
                else -> ok("""{"rt_cd":"0","output1":[],"output2":[{}]}""", "D")
            }
        }
    }

    @Test
    fun readCommandsRefuseOrderPathsAndQuoteWorks() {
        val base = "https://openapivts.koreainvestment.com:29443"
        for (args in listOf(listOf("orders"), listOf("balance"), listOf("order", "0000012345", "005930"), listOf("quote", "005930"), listOf("notices", "0"))) {
            val http = orderHttp()
            val out = mutableListOf<String>()
            val probe = KisProbe(env, http, out::add, clock)
            probe.run(args)
            assertSafe(http, out, tokens = if (args[0] == "notices") 0 else 1)
            for ((path, tr) in listOf("order-cash" to "VTTC0802U", "order-cash" to "VTTC0801U", "order-rvsecncl" to "VTTC0803U")) {
                assertThrows<IllegalArgumentException>("$args $path") { probe.http.send("POST", "$base/uapi/domestic-stock/v1/trading/$path", headers = mapOf("tr_id" to tr)) }
            }
            assertThrows<IllegalArgumentException> { probe.http.send("POST", "$base/uapi/hashkey") }
            if (args[0] == "quote") {
                val text = out.joinToString("\n")
                assertTrue(http.calls.any { it.url.endsWith("/uapi/domestic-stock/v1/quotations/inquire-price") && it.headers["tr_id"] == "FHKST01010100" })
                assertTrue("stck_prpr=\"70000\"(5) stck_mxpr=\"91000\"(5) stck_llam=\"49000\"(5) stck_sdpr=\"70000\"(5)" in text, text)
                assertTrue("output 키 목록(6개): stck_prpr, stck_mxpr" in text, text)
                assertTrue("Quote(symbol=005930, price=70000," in text && "halted=false" in text, text)
            }
        }
    }

    @Test
    fun buyAndSellSendExactlyOneOrderThroughKisClient() {
        val http = orderHttp()
        val out = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http, out::add, clock).run(listOf("buy", "005930", "1")))
        assertSafe(http, out, orders = 1)
        val buy = http.orderPosts().single()
        assertEquals("POST", buy.method)
        assertEquals("https://openapivts.koreainvestment.com:29443/uapi/domestic-stock/v1/trading/order-cash", buy.url)
        assertEquals("VTTC0802U", buy.headers["tr_id"])
        assertEquals("HASH-1", buy.headers["hashkey"])
        assertEquals(mapOf("CANO" to "50012345", "ACNT_PRDT_CD" to "01", "PDNO" to "005930", "ORD_DVSN" to "01", "ORD_QTY" to "1", "ORD_UNPR" to "0"), body(buy))
        val text = out.joinToString("\n")
        assertTrue("hashkey POST HTTP 200" in text, text)
        assertTrue("[VTTC0802U] HTTP 200" in text && "rt_cd=\"0\" msg_cd=\"APBK0013\"" in text, text)
        assertTrue("output 키 목록(3개): KRX_FWDG_ORD_ORGNO, ODNO, ORD_TMD" in text, text)
        assertTrue("ODNO=\"0000117057\"(10) KRX_FWDG_ORD_ORGNO=\"00950\"(5) ORD_TMD=\"121052\"(6)" in text, text)
        assertTrue("OrderResult(orderId=\"0000117057\", orderOrgNo=\"00950\")" in text, text)

        val http2 = orderHttp()
        val out2 = mutableListOf<String>()
        assertEquals(0, KisProbe(env, http2, out2::add, clock).run(listOf("sell", "005930", "2", "70000")))
        assertSafe(http2, out2, orders = 1)
        val sell = http2.orderPosts().single()
        assertEquals("VTTC0801U", sell.headers["tr_id"])
        assertEquals(mapOf("CANO" to "50012345", "ACNT_PRDT_CD" to "01", "PDNO" to "005930", "ORD_DVSN" to "00", "ORD_QTY" to "2", "ORD_UNPR" to "70000"), body(sell))
    }

    @Test
    fun xorderSendsRawOrdDvsnAndOptionalExchange() {
        for ((args, excg) in listOf(listOf("xorder", "sell", "005930", "1", "0", "07", "NXT") to "NXT", listOf("xorder", "sell", "005930", "1", "0", "07") to null)) {
            val http = orderHttp()
            val out = mutableListOf<String>()
            assertEquals(0, KisProbe(env, http, out::add, clock).run(args))
            assertSafe(http, out, orders = 1)
            val c = http.orderPosts().single()
            assertEquals("VTTC0801U", c.headers["tr_id"])
            val expected = mapOf("CANO" to "50012345", "ACNT_PRDT_CD" to "01", "PDNO" to "005930", "ORD_DVSN" to "07", "ORD_QTY" to "1", "ORD_UNPR" to "0")
            assertEquals(if (excg == null) expected else expected + ("EXCG_ID_DVSN_CD" to excg), body(c))
            assertEquals(c.json, http.calls.single { it.url.endsWith("/uapi/hashkey") }.json)
        }
    }

    @Test
    fun secondOrderPostIsRefusedEvenWhenKisClientRetriesRateLimit() {
        val http = orderHttp { ok("""{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""") }
        val out = mutableListOf<String>()
        KisProbe(env, http, out::add, clock).run(listOf("buy", "005930", "1"))
        assertSafe(http, out, orders = 1)
        val text = out.joinToString("\n")
        assertTrue("msg_cd=\"EGW00201\"" in text, text)
        assertTrue("예외 IllegalArgumentException: 주문계 POST 는 실행당 1건만 보냅니다" in text, text)
    }

    @Test
    fun amendAndCancelBodies() {
        for ((args, expected) in listOf(
            listOf("amend", "0000117057", "00950", "1", "69000") to listOf("01", "1", "69000"),
            listOf("cancel", "0000117057", "00950", "3") to listOf("02", "3", "0"),
        )) {
            val http = orderHttp { ok("""{"rt_cd":"0","msg_cd":"APBK0013","msg1":"주문 전송 완료 되었습니다.","output":{"KRX_FWDG_ORD_ORGNO":"00950","ODNO":"0000117058","ORD_TMD":"121530"}}""") }
            val out = mutableListOf<String>()
            assertEquals(0, KisProbe(env, http, out::add, clock).run(args))
            assertSafe(http, out, orders = 1)
            val c = http.orderPosts().single()
            assertEquals("https://openapivts.koreainvestment.com:29443/uapi/domestic-stock/v1/trading/order-rvsecncl", c.url)
            assertEquals("VTTC0803U", c.headers["tr_id"])
            assertEquals("Bearer TOKEN-xyz-777", c.headers["authorization"])
            assertEquals("HASH-1", c.headers["hashkey"])
            assertEquals("P", c.headers["custtype"])
            assertEquals(
                mapOf(
                    "CANO" to "50012345", "ACNT_PRDT_CD" to "01", "KRX_FWDG_ORD_ORGNO" to "00950", "ORGN_ODNO" to "0000117057", "ORD_DVSN" to "00",
                    "RVSE_CNCL_DVSN_CD" to expected[0], "ORD_QTY" to expected[1], "ORD_UNPR" to expected[2], "QTY_ALL_ORD_YN" to "N",
                ),
                body(c),
            )
            assertEquals(c.json, http.calls.single { it.url.endsWith("/uapi/hashkey") }.json) // hashkey 는 보낸 본문 그대로
            assertTrue("ODNO=\"0000117058\"(10)" in out.joinToString("\n"), out.joinToString("\n"))
        }
    }

    @Test
    fun badOrderArgsSendNothing() {
        for (args in listOf(
            listOf("buy", "005930", "0"), listOf("buy", "005930", "1001"), listOf("sell", "00593", "1"), listOf("buy", "00593!", "1"),
            listOf("buy", "005930", "1", "-1"), listOf("buy", "005930", "1", "1.5"), listOf("amend", "0000117057", "00950", "1"),
            listOf("xorder", "buy", "005930", "1", "0", "7"), listOf("xorder", "buy", "005930", "1", "0", "07", "XXX"), listOf("xorder", "hold", "005930", "1", "0", "07"),
            listOf("cancel", "", "00950", "1"), listOf("cancel", "0000117057", "0-950", "1"), listOf("quote", "5930"),
        )) {
            val http = FakeHttp { _, _, _ -> error("요청이 나가면 안 된다") }
            val out = mutableListOf<String>()
            assertEquals(2, KisProbe(env, http, out::add, clock).run(args), "$args")
            assertTrue(http.calls.isEmpty(), "$args")
        }
    }

    @Test
    fun tokenFileIsReusedWithoutIssuing(@TempDir dir: Path) {
        val file = dir.resolve("kis-token.txt")
        val withFile = env + ("KIS_PROBE_TOKEN_FILE" to file.toString())
        val http = orderHttp()
        val out = mutableListOf<String>()
        assertEquals(0, KisProbe(withFile, http, out::add, clock).run(listOf("quote", "005930")))
        assertSafe(http, out, tokens = 1)
        assertEquals("TOKEN-xyz-777", Files.readString(file))
        assertFalse(Files.readString(file).contains("APPKEY")) // 토큰만(키 이름에 든 앱키를 쓰지 않는다)

        val http2 = orderHttp()
        val out2 = mutableListOf<String>()
        assertEquals(0, KisProbe(withFile, http2, out2::add, clock).run(listOf("quote", "005930")))
        assertSafe(http2, out2, tokens = 0)
        assertEquals("Bearer TOKEN-xyz-777", http2.calls.single().headers["authorization"])
        assertTrue(out2.any { "토큰 파일의 토큰을 씀" in it })

        // 캐시 토큰이 만료돼도 재발급하지 않고 안내만
        val http3 = FakeHttp { _, _, _ -> BrokerResponse(500, """{"rt_cd":"1","msg_cd":"EGW00123","msg1":"기간이 만료된 token 입니다."}""") }
        val out3 = mutableListOf<String>()
        KisProbe(withFile, http3, out3::add, clock).run(listOf("quote", "005930"))
        assertSafe(http3, out3, tokens = 0)
        assertTrue(out3.any { "토큰 파일을 지우고 다시 실행" in it }, out3.joinToString("\n"))
        assertEquals("TOKEN-xyz-777", Files.readString(file))
    }

    @Test
    fun noticeFramesAreMaskedAndParsed() {
        val key = "abcdefghijklmnopqrstuvwxyz012345"
        val iv = "0123456789abcdef"
        fun enc(plain: String): String {
            val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
            c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
            return Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray()))
        }
        fun notice(cntg: String, qty: String) = MutableList(26) { "" }.apply {
            this[0] = "custid77"; this[1] = "5001234501"; this[2] = "0000012345"; this[4] = "02"; this[8] = "005930"; this[9] = qty; this[10] = "70000"
            this[12] = "0"; this[13] = cntg; this[16] = "2"; this[17] = "홍길동"; this[21] = "김철수"; this[24] = "삼성전자"
        }.joinToString("^")
        val out = mutableListOf<String>()
        val secrets = mutableListOf<String>()
        val pongs = mutableListOf<String>()
        val w = NoticeWatch(clock, out::add, secrets::add)
        w.onFrame("0|H0STCNI9|001|${enc(notice("2", "1"))}", pongs::add) // key/iv 전
        w.onFrame("""{"header":{"tr_id":"H0STCNI9"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS","output":{"iv":"$iv","key":"$key"}}}""", pongs::add)
        w.onFrame("""{"header":{"tr_id":"PINGPONG","datetime":"20261116100000"}}""", pongs::add)
        w.onFrame("1|H0STCNI9|001|${enc(notice("1", "2"))}", pongs::add)
        w.onFrame("1|H0STCNI9|001|${enc(notice("2", "1"))}", pongs::add)
        w.summary()
        val text = out.joinToString("\n")
        assertEquals(listOf(key, iv), secrets) // 출력 단계에서 지울 값으로 넘긴다
        assertEquals(1, pongs.size)
        assertTrue("key/iv 미수신 — 데이터 프레임 무시" in text, text)
        assertTrue("msg1=\"SUBSCRIBE SUCCESS\" key/iv 수신=예" in text, text)
        listOf("custid77", "5001234501", "홍길동", "김철수", key, iv).forEach { assertFalse(it in text, "출력에 가릴 값: $it\n$text") }
        assertTrue("0=*** 1=*** 2=\"0000012345\"" in text, text)
        assertTrue("13=\"1\"" in text && "17=*** " in text && "21=*** " in text && "24=\"삼성전자\"" in text, text)
        assertTrue("parseFillNotice → null(체결 아님으로 판정)" in text, text)
        assertTrue("parseFillNotice → FillNotice(odno=0000012345, symbol=005930, qty=1, price=70000, side=buy)" in text, text)
        assertTrue("받은 체결통보 2건, PINGPONG 1회, CNTG_YN(13) 값별: \"1\"=1건, \"2\"=1건" in text, text)
    }
}
