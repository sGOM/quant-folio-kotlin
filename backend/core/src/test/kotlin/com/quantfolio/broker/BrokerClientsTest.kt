package com.quantfolio.broker

import com.quantfolio.common.RedisKeys

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.TestApp
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 증권사 REST 규약(원본 `services/kis/client.py`·`broker/toss.py`) — 가짜 전송으로 요청 형식·응답 정규화·재시도를 확인한다. */
@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class BrokerClientsTest(@Autowired private val redis: StringRedisTemplate) {
    private val json = JsonMapper.builder().build()

    class Call(val method: String, val url: String, val query: Map<String, String>, val headers: Map<String, String>, val json: String?, val form: Map<String, String>?)

    class FakeHttp : BrokerHttp {
        val calls = mutableListOf<Call>()
        private val routes = mutableListOf<Pair<(Call) -> Boolean, (Call) -> BrokerResponse>>()
        fun on(match: (Call) -> Boolean, respond: (Call) -> BrokerResponse) { routes += match to respond }
        override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
            val c = Call(method, url, query, headers, json, form)
            calls += c
            return routes.lastOrNull { it.first(c) }?.second?.invoke(c) ?: BrokerResponse(404, "no route")
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val sleeps = mutableListOf<Long>()

    private fun support(http: FakeHttp, env: String = "vts") =
        BrokerSupport(http, redis, QuantfolioProperties(kis = QuantfolioProperties.Kis(env = env, prodApproved = env == "prod")), clock, json) { sleeps += it }

    private fun kisWithToken(http: FakeHttp, env: String = "vts", account: String? = "50012345-01"): KisClient {
        val key = "k-" + UUID.randomUUID()
        http.on({ it.url.endsWith("/oauth2/tokenP") }) { BrokerResponse(200, """{"access_token":"TKN","expires_in":86400}""") }
        return KisClient(key, "secret", account, support(http, env))
    }

    @Test
    fun tokenIsIssuedOnceAndCachedInRedis() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/quotations/inquire-price") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"stck_prpr":"70100","acml_vol":"12.0","prdy_vrss":"-100","iscd_stat_cls_code":"55","temp_stop_yn":"N","vi_cls_code":"N"}}""") }
        repeat(3) { kis.getQuote("005930") }
        assertEquals(1, http.calls.count { it.url.endsWith("/oauth2/tokenP") })
        val q = http.calls.last { it.url.endsWith("/inquire-price") }
        assertEquals("Bearer TKN", q.headers["authorization"])
        assertEquals("FHKST01010100", q.headers["tr_id"])
        assertEquals(mapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to "005930"), q.query)
        assertTrue(q.url.startsWith("https://openapivts.koreainvestment.com:29443"))
    }

    @Test
    fun quoteIsStrictAboutPriceAndDetectsHalt() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/inquire-price") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"stck_prpr":"1000","acml_vol":"5","iscd_stat_cls_code":"58"}}""") }
        val q = kis.getQuote("000010")
        assertTrue(q.halted)
        assertEquals(BigDecimal("1000"), q.price)
        assertEquals(BigDecimal.ZERO, q.high) // 부가 필드 결측은 0
        http.on({ it.url.endsWith("/inquire-price") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"stck_prpr":""}}""") }
        assertEquals("시세 응답에 현재가(stck_prpr)가 없습니다: 000010", assertThrows<KisError> { kis.getQuote("000010") }.message)
        http.on({ it.url.endsWith("/inquire-price") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"stck_prpr":"1,000"}}""") }
        assertEquals("시세 현재가 파싱 실패: 000010 stck_prpr='1,000'", assertThrows<KisError> { kis.getQuote("000010") }.message)
    }

    @Test
    fun orderUsesEnvTrIdHashkeyAndAccountParts() {
        for ((env, buyTr) in listOf("vts" to "VTTC0802U", "prod" to "TTTC0802U")) {
            val http = FakeHttp()
            val kis = kisWithToken(http, env)
            http.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"H1"}""") }
            http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"ODNO":"0000123","KRX_FWDG_ORD_ORGNO":"91252"}}""") }
            val r = kis.placeOrder("005930", "buy", 3)
            assertEquals("0000123", r.orderId)
            assertEquals("91252", r.orderOrgNo)
            val o = http.calls.single { it.url.endsWith("/order-cash") }
            assertEquals(buyTr, o.headers["tr_id"])
            assertEquals("H1", o.headers["hashkey"])
            assertEquals(
                mapOf("CANO" to "50012345", "ACNT_PRDT_CD" to "01", "PDNO" to "005930", "ORD_DVSN" to "01", "ORD_QTY" to "3", "ORD_UNPR" to "0"),
                json.readValue(o.json, Map::class.java),
            )
        }
        val http = FakeHttp()
        assertEquals("계좌번호가 등록되지 않았습니다.", assertThrows<KisError> { kisWithToken(http, account = null).placeOrder("005930", "sell", 1) }.message)
        assertEquals("계좌번호 형식 오류: 123", assertThrows<KisError> { kisWithToken(http, account = "123").placeOrder("005930", "sell", 1) }.message)
    }

    @Test
    fun rateLimitIsRetriedWithBackoffThenSurfaces() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        var n = 0
        http.on({ it.url.endsWith("/inquire-balance") }) {
            n++
            if (n < 3) BrokerResponse(200, """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""")
            else BrokerResponse(200, """{"rt_cd":"0","output1":[{"pdno":"005930","hldg_qty":"10","pchs_avg_pric":"70000.5"},{"pdno":"000660","hldg_qty":"0"}],"output2":[{}]}""")
        }
        sleeps.clear()
        val b = kis.getBalance()
        assertEquals(listOf(NormalizedPosition("005930", 10, BigDecimal("70000.5"))), b.positionsNormalized())
        assertTrue(sleeps.containsAll(listOf(600L, 1200L))) // 0.6s × 시도 차수
        n = -10
        val e = assertThrows<KisError> { kis.getBalance() }
        assertEquals("잔고 조회 오류: [EGW00201] 초당 거래건수를 초과하였습니다.", e.message)
        http.on({ it.url.endsWith("/inquire-balance") }) { BrokerResponse(500, "boom") }
        assertEquals("잔고 조회 실패: HTTP 500 boom", assertThrows<KisError> { kis.getBalance() }.message)
    }

    @Test
    fun executionParsesTotalsForTheOrder() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/inquire-daily-ccld") }) {
            BrokerResponse(200, """{"rt_cd":"0","output1":[{"odno":"999","tot_ccld_qty":"1","ord_qty":"1","avg_prvs":"1"},{"odno":"0000123","tot_ccld_qty":"2","ord_qty":"3","avg_prvs":"70050.25"}]}""")
        }
        val f = kis.getOrderExecution("0000123", "005930")
        assertEquals(2, f.filledQty)
        assertEquals(BigDecimal("70050.25"), f.avgPrice)
        assertFalse(f.fullyFilled)
        val c = http.calls.last()
        assertEquals("20261019", c.query["INQR_STRT_DT"]) // KST 날짜
        assertEquals("VTTC8001R", c.headers["tr_id"])
        val none = kis.getOrderExecution("777")
        assertEquals(0, none.filledQty)
        assertNull(none.avgPrice)
    }

    @Test
    fun tossRetriesOnceOn429AndNormalizes() {
        val http = FakeHttp()
        val toss = TossClient("cid-" + UUID.randomUUID(), "csecret", "ACC1", support(http))
        http.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"T","expires_in":3600}""") }
        var n = 0
        http.on({ it.url.endsWith("/api/v1/prices") }) {
            n++
            if (n == 1) BrokerResponse(429, "", mapOf("Retry-After" to listOf("30"))) else BrokerResponse(200, """{"result":[{"symbol":"AAPL","lastPrice":"190.5","currency":"USD"}]}""")
        }
        sleeps.clear()
        val q = toss.getQuote("AAPL")
        assertEquals(BigDecimal("190.5"), q.price)
        assertEquals("USD", q.currency)
        assertEquals(listOf(5000L), sleeps) // Retry-After 상한 5초
        assertEquals(listOf("grant_type", "client_id", "client_secret"), http.calls.first().form!!.keys.toList())
        assertEquals("csecret", http.calls.first().form!!["client_secret"])

        http.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(201, """{"orderId":42}""") }
        assertEquals("42", toss.placeOrder("005930", "buy", 2, 70000, "limit").orderId)
        val o = http.calls.last()
        assertEquals("ACC1", o.headers["X-Tossinvest-Account"])
        assertEquals(mapOf("symbol" to "005930", "side" to "BUY", "orderType" to "LIMIT", "quantity" to 2, "price" to 70000), json.readValue(o.json, Map::class.java))
        assertEquals("알 수 없는 주문 방향: hold", assertThrows<TossError> { toss.placeOrder("005930", "hold", 1) }.message)

        http.on({ "/api/v1/orders/42" in it.url }) { BrokerResponse(200, """{"status":"filled","execution":{"filledQuantity":"2","averageFilledPrice":"70010"}}""") }
        val f = toss.getOrderExecution("42")
        assertTrue(f.fullyFilled)
        assertEquals(BigDecimal("70010"), f.avgPrice)
    }

    /** 원본 truthiness 검사 그대로: 빈 토큰·HASH 없음·빈 orderId 는 실패, 문자열 expires_in 도 숫자로(원본 int()). */
    @Test
    fun emptyOrMissingBrokerFieldsFailLikeOriginal() {
        val http = FakeHttp()
        val key = "k-" + UUID.randomUUID()
        http.on({ it.url.endsWith("/oauth2/tokenP") }) { BrokerResponse(200, """{"access_token":"","expires_in":86400}""") }
        assertThrows<KisError> { KisClient(key, "secret", "50012345-01", support(http)).accessToken() }
        assertEquals(false, redis.hasKey(RedisKeys.kisToken("vts", key))) // 빈 토큰을 하루 동안 캐시하지 않는다

        val h2 = FakeHttp()
        val kis = kisWithToken(h2)
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"msg1":"오류"}""") }
        h2.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, """{"rt_cd":"0","output":{"ODNO":"1"}}""") }
        assertThrows<IllegalStateException> { kis.placeOrder("005930", "buy", 1) }
        assertEquals(0, h2.calls.count { it.url.endsWith("/order-cash") }) // HASH 없으면 주문을 보내지 않는다

        val h3 = FakeHttp()
        val cid = "cid-" + UUID.randomUUID()
        val toss = TossClient(cid, "cs", "ACC1", support(h3))
        h3.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"T","expires_in":"3600"}""") }
        h3.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(201, """{"orderId":""}""") }
        assertThrows<TossError> { toss.placeOrder("005930", "buy", 1) }
        assertTrue(redis.getExpire(RedisKeys.tossToken(cid)) in 1..3600)
    }
}
