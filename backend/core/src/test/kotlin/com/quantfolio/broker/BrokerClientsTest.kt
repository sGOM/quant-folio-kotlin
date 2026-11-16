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
import java.io.IOException
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
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
    fun tokenLockIsReleasedAfterIssue() {
        val cacheKey = "tok-" + UUID.randomUUID()
        val lockKey = RedisKeys.tokenLock(cacheKey)
        support(FakeHttp()).cachedToken(cacheKey, "t") { assertTrue(redis.hasKey(lockKey)); "T" to 3600 }
        assertFalse(redis.hasKey(lockKey))
        redis.delete(cacheKey)
    }

    @Test
    fun tokenLockIsReleasedWhenIssueThrows() {
        val cacheKey = "tok-" + UUID.randomUUID()
        val lockKey = RedisKeys.tokenLock(cacheKey)
        assertThrows<IllegalStateException> { support(FakeHttp()).cachedToken(cacheKey, "t") { error("boom") } }
        assertFalse(redis.hasKey(lockKey))
    }

    @Test
    fun tokenLockTakenByAnotherHolderAfterExpiryIsNotDeleted() {
        val cacheKey = "tok-" + UUID.randomUUID()
        val lockKey = RedisKeys.tokenLock(cacheKey)
        support(FakeHttp()).cachedToken(cacheKey, "t") {
            redis.delete(lockKey) // 내 락 TTL 만료
            redis.opsForValue().set(lockKey, "holder-B") // 다른 보유자가 새로 잡음
            "T" to 3600
        }
        assertEquals("holder-B", redis.opsForValue().get(lockKey))
        redis.delete(listOf(cacheKey, lockKey))
    }

    @Test
    fun tokenLockHeldByOthersIsNotDeletedWhenProceedingWithoutLock() {
        val cacheKey = "tok-" + UUID.randomUUID()
        val lockKey = RedisKeys.tokenLock(cacheKey)
        redis.opsForValue().set(lockKey, "holder-A")
        assertEquals("T", support(FakeHttp()).cachedToken(cacheKey, "t") { "T" to 3600 })
        assertEquals("holder-A", redis.opsForValue().get(lockKey))
        redis.delete(listOf(cacheKey, lockKey))
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
    fun orderAccountIdentityUsesEffectiveAccountAndTradingEnvironment() {
        val http = FakeHttp()
        val paper = kisWithToken(http, account = "50012345-01").orderAccountKey
        assertTrue(paper != null && paper.length == 64 && !paper.contains("50012345"))
        assertEquals(paper, kisWithToken(http, account = " 5001234501 ").orderAccountKey)
        assertNotEquals(paper, kisWithToken(http, account = "50012345-02").orderAccountKey)
        assertNotEquals(paper, kisWithToken(http, env = "prod", account = "50012345-01").orderAccountKey)
        val toss = TossClient("client", "secret", "1", support(http)).orderAccountKey
        assertNotEquals(toss, TossClient("other-client", "secret", "1", support(http)).orderAccountKey)
        assertNotEquals(toss, TossClient("client", "secret", "2", support(http)).orderAccountKey)
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun kisLegacyEvidenceRequiresOneCompleteExactBranchAndOrderRow() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        val date = LocalDate.parse("2026-10-19")
        val row = """{"odno":"0000123","ord_dt":"20261019","ord_gno_brno":"91252","pdno":"005930","sll_buy_dvsn_cd":"02","ord_tmd":"100001","ord_qty":"2","tot_ccld_qty":"2","tot_ccld_amt":"2000"}"""
        var rows = row
        var page = "E"
        http.on({ it.url.endsWith("/inquire-daily-ccld") }) {
            BrokerResponse(200, """{"rt_cd":"0","output1":[$rows]}""", mapOf("tr_cont" to listOf(page)))
        }
        val evidence = kis.legacyFillEvidence("0000123", "91252", "005930", date)!!
        assertEquals(Instant.parse("2026-10-19T01:00:01Z"), evidence.orderedAt)
        assertEquals(BigDecimal("2000"), evidence.filledAmount)
        assertEquals("VTTC0081R", http.calls.last().headers["tr_id"])
        assertEquals("91252", http.calls.last().query["ORD_GNO_BRNO"])
        assertEquals("ALL", http.calls.last().query["EXCG_ID_DVSN_CD"])
        assertNull(kis.legacyFillEvidence("0000123", "wrong", "005930", date))
        assertNull(kis.legacyFillEvidence("0000123", null, "005930", date))
        rows = "$row,$row"
        assertNull(kis.legacyFillEvidence("0000123", "91252", "005930", date))
        rows = row
        page = "M"
        assertThrows<KisError> { kis.legacyFillEvidence("0000123", "91252", "005930", date) }
        page = "E"
        rows = row.replace("20261019", "20260119")
        assertTrue(kis.legacyFillEvidence("0000123", "91252", "005930", LocalDate.parse("2026-01-19")) != null)
        assertEquals("VTSC9215R", http.calls.last().headers["tr_id"])
    }

    @Test
    fun tossLegacyEvidenceChecksReturnedOrderIdentity() {
        val http = FakeHttp()
        val toss = TossClient("legacy-" + UUID.randomUUID(), "test", "1", support(http))
        http.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"test","expires_in":3600}""") }
        var returnedId = "abc123"
        http.on({ it.url.endsWith("/api/v1/orders/abc123") }) {
            BrokerResponse(200, """{"orderId":"$returnedId","symbol":"005930","side":"SELL","quantity":"2","status":"FILLED","orderedAt":"2026-10-19T10:00:01+09:00","execution":{"filledQuantity":"2","filledAmount":"2000"}}""")
        }
        val date = LocalDate.parse("2026-10-19")
        assertEquals("sell", toss.legacyFillEvidence("abc123", null, "005930", date)?.side)
        assertEquals("1", http.calls.last().headers["X-Tossinvest-Account"])
        returnedId = "other"
        assertNull(toss.legacyFillEvidence("abc123", null, "005930", date))
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
        // 2026-11-16 모의 실측: VTTC8001R 은 체결된 주문도 "조회할 내역 없음"(행 0개) — VTTC0081R + ODNO 는 그 주문의 행을 준다.
        assertEquals("VTTC0081R", c.headers["tr_id"])
        assertEquals("0000123", c.query["ODNO"])
        assertEquals("005930", c.query["PDNO"])
        assertEquals("ALL", c.query["EXCG_ID_DVSN_CD"])
        val none = kis.getOrderExecution("777")
        assertEquals(0, none.filledQty)
        assertNull(none.avgPrice)
        // KIS 는 종료 상태를 체결 조회로 알리지 않는다 — 전량 체결 행이어도 terminal 은 false(종결 근거는 주문 목록 행)
        val full = kis.getOrderExecution("999")
        assertTrue(full.fullyFilled)
        assertFalse(full.terminal)
        assertFalse(f.terminal)
        assertFalse(none.terminal)
    }

    /** 2026-11-16 모의 실측의 0081R 단건 행 모양 — 부분 체결 뒤 잔량이 취소된 원주문(ord_qty 300, 총 체결 50, 잔량 0). */
    @Test
    fun executionReadsDailyOrderRowOfPartiallyCancelledOrder() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/inquire-daily-ccld") }) {
            BrokerResponse(
                200,
                """{"rt_cd":"0","msg_cd":"70070000","output1":[{"ord_dt":"20261019","ord_gno_brno":"00950","odno":"0000022464","orgn_odno":"0000000000",""" +
                    """"sll_buy_dvsn_cd":"02","pdno":"003480","ord_qty":"300","ord_unpr":"0","ord_tmd":"113930","tot_ccld_qty":"50","avg_prvs":"4265.5",""" +
                    """"cncl_yn":"N","tot_ccld_amt":"213275","rmn_qty":"0"}],"output2":{}}""",
                mapOf("tr_cont" to listOf("E")),
            )
        }
        val f = kis.getOrderExecution("0000022464", "003480", LocalDate.parse("2026-10-19"))
        assertEquals(50, f.filledQty)
        assertEquals(BigDecimal("4265.5"), f.avgPrice)
        assertFalse(f.fullyFilled)
        assertFalse(f.terminal)
        assertEquals("VTTC0081R", http.calls.last().headers["tr_id"])
        val prod = FakeHttp()
        prod.on({ it.url.endsWith("/inquire-daily-ccld") }) { BrokerResponse(200, """{"rt_cd":"0","output1":[]}""") }
        assertEquals(0, kisWithToken(prod, "prod").getOrderExecution("0000022464", "003480").filledQty)
        assertEquals("TTTC0081R", prod.calls.last().headers["tr_id"])
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

    @Test
    fun serverErrorsDuringSubmissionAreUnknownNotRejections() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(503, "unavailable") }
        assertThrows<OrderOutcomeUnknown> { kis.placeOrder("005930", "buy", 1) }
        assertEquals(1, http.calls.count { it.url.endsWith("/order-cash") })
        val toss = TossClient("c-" + UUID.randomUUID(), "s", "ACC", support(http))
        http.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"t"}""") }
        http.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(503, "unavailable") }
        assertThrows<OrderOutcomeUnknown> { toss.placeOrder("005930", "buy", 1) }
        assertEquals(1, http.calls.count { it.url.endsWith("/api/v1/orders") })
    }

    /** 주문 전송의 5xx·408 은 접수불명(재주문 금지), 같은 상태 코드라도 조회 요청이면 평범한 조회 실패. 같은 URL 은 나중에 등록한 핸들러가 이긴다. */
    @Test
    fun orderSubmitStatusesAreUnknownButQueryStatusesAreErrors() {
        val kHttp = FakeHttp()
        val kis = kisWithToken(kHttp)
        kHttp.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        val tHttp = FakeHttp()
        val toss = TossClient("c-" + UUID.randomUUID(), "s", "ACC", support(tHttp))
        tHttp.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"t"}""") }
        for (status in listOf(408, 500, 502, 503)) {
            kHttp.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(status, "x") }
            assertThrows<OrderOutcomeUnknown>("KIS order $status") { kis.placeOrder("005930", "buy", 1) }
            kHttp.on({ it.url.endsWith("/inquire-balance") }) { BrokerResponse(status, "x") }
            assertThrows<KisError>("KIS balance $status") { kis.getBalance() }
            tHttp.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(status, "x") }
            assertThrows<OrderOutcomeUnknown>("Toss order $status") { toss.placeOrder("005930", "buy", 1) }
            tHttp.on({ "/api/v1/orders/42" in it.url }) { BrokerResponse(status, "x") }
            assertThrows<TossError>("Toss execution $status") { toss.getOrderExecution("42") }
            tHttp.on({ it.url.endsWith("/api/v1/prices") }) { BrokerResponse(status, "x") }
            assertThrows<TossError>("Toss quote $status") { toss.getQuote("005930") }
        }
    }

    /**
     * 주문 POST 의 5xx 는 접수불명이되, 본문이 JSON 이고 rt_cd=1·msg_cd=IGW00002(인증 시점 계좌 ≠ 요청 계좌 — 2026-11-16 모의 주문 POST 에서 HTTP 500 으로 실측)일 때만 거절.
     * 다른 코드·rt_cd 없음·JSON 아님·408 은 그대로 접수불명. 어느 쪽이든 주문 POST 는 한 번만 나간다.
     */
    @Test
    fun orderSubmit5xxIsRejectionOnlyForMeasuredAccountMismatchCode() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        fun posts() = http.calls.count { it.url.endsWith("/order-cash") }
        val mismatch = """{"rt_cd":"1","msg_cd":"IGW00002","msg1":"인증 시점의 계좌번호와 요청 계좌번호가 일치하지 않습니다."}"""
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(500, mismatch) }
        assertEquals("주문 오류: HTTP 500 [IGW00002] 인증 시점의 계좌번호와 요청 계좌번호가 일치하지 않습니다.", assertThrows<KisError> { kis.placeOrder("005930", "buy", 1) }.message)
        assertEquals(1, posts())
        for (body in listOf(
            """{"rt_cd":"1","msg_cd":"EGW00215","msg1":"원장에서 허용 가능한 초당 거래건수를 초과하였습니다."}""",
            """{"rt_cd":"1","msg_cd":"EGW00201","msg1":"초당 거래건수를 초과하였습니다."}""",
            """{"rt_cd":"1","msg_cd":"IGW00001","msg1":"?"}""",
            """{"msg_cd":"IGW00002","msg1":"?"}""",
            """{"rt_cd":"2","msg_cd":"IGW00002","msg1":"?"}""",
            """{"rt_cd":"0","msg_cd":"IGW00002","msg1":"?"}""",
            """["IGW00002"]""",
            "IGW00002",
        )) {
            val before = posts()
            http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(500, body) }
            assertThrows<OrderOutcomeUnknown>(body) { kis.placeOrder("005930", "buy", 1) }
            assertEquals(before + 1, posts(), body)
        }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(408, mismatch) }
        assertThrows<OrderOutcomeUnknown> { kis.placeOrder("005930", "buy", 1) }
        // 같은 본문이라도 조회(GET)는 원래대로 조회 실패
        http.on({ it.url.endsWith("/inquire-balance") }) { BrokerResponse(500, mismatch) }
        assertThrows<KisError> { kis.getBalance() }
    }

    /** EGW00215(원장 초당 거래건수 초과 — 2026-11-16 잔고 GET 에서 HTTP 500 으로 1회 실측)는 조회에서만 EGW00201 처럼 재시도. 주문 POST 는 재시도하지 않는다. */
    @Test
    fun ledgerRateLimitIsRetriedForQueriesButNotForOrderSubmit() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        val limited = """{"rt_cd":"1","msg1":"원장에서 허용 가능한 초당 거래건수를 초과하였습니다.","msg_cd":"EGW00215"}"""
        val ok = """{"rt_cd":"0","output1":[{"pdno":"005930","hldg_qty":"10","pchs_avg_pric":"70000.5"}],"output2":[{}]}"""
        for (status in listOf(500, 200)) {
            var n = 0
            http.on({ it.url.endsWith("/inquire-balance") }) { if (++n < 3) BrokerResponse(status, limited) else BrokerResponse(200, ok) }
            sleeps.clear()
            assertEquals(listOf(NormalizedPosition("005930", 10, BigDecimal("70000.5"))), kis.getBalance().positionsNormalized(), "HTTP $status")
            assertEquals(3, n, "HTTP $status")
            assertTrue(sleeps.containsAll(listOf(600L, 1200L)), "HTTP $status $sleeps")
        }
        http.on({ it.url.endsWith("/inquire-balance") }) { BrokerResponse(500, limited) }
        assertThrows<KisError> { kis.getBalance() } // 3회 모두 제한이면 조회 실패

        http.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(500, limited) }
        assertThrows<OrderOutcomeUnknown> { kis.placeOrder("005930", "buy", 1) }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, limited) }
        assertThrows<KisError> { kis.placeOrder("005930", "buy", 1) }
        assertEquals(2, http.calls.count { it.url.endsWith("/order-cash") })
    }

    /** KIS 주문: 결과 코드 없음·알 수 없는 값은 접수불명, 명시적 거절(rt_cd=1)만 KisError. */
    @Test
    fun kisOrderResultCodeDistinguishesUnknownFromRejection() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, """{"msg1":"?"}""") }
        assertThrows<OrderOutcomeUnknown> { kis.placeOrder("005930", "buy", 1) }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, """{"rt_cd":"9","msg1":"?"}""") }
        assertThrows<OrderOutcomeUnknown> { kis.placeOrder("005930", "buy", 1) }
        http.on({ it.url.endsWith("/order-cash") }) { BrokerResponse(200, """{"rt_cd":"1","msg_cd":"APBK0013","msg1":"거부"}""") }
        assertEquals("주문 오류: [APBK0013] 거부", assertThrows<KisError> { kis.placeOrder("005930", "buy", 1) }.message)
    }

    /** 토스 주문이 429 → 재시도 → 5xx 면 접수불명(429 분기가 먼저지만 재시도 응답도 같은 판정). */
    @Test
    fun tossOrderRetriedAfter429ThenServerErrorIsUnknown() {
        val http = FakeHttp()
        val toss = TossClient("c-" + UUID.randomUUID(), "s", "ACC", support(http))
        http.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"t"}""") }
        var n = 0
        http.on({ it.url.endsWith("/api/v1/orders") }) { if (++n == 1) BrokerResponse(429, "") else BrokerResponse(503, "x") }
        assertThrows<OrderOutcomeUnknown> { toss.placeOrder("005930", "buy", 1) }
        assertEquals(2, http.calls.count { it.url.endsWith("/api/v1/orders") })
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
        assertThrows<OrderNotSent> { kis.placeOrder("005930", "buy", 1) } // 전송 전 실패라 거절
        assertEquals(0, h2.calls.count { it.url.endsWith("/order-cash") }) // HASH 없으면 주문을 보내지 않는다

        val h3 = FakeHttp()
        val cid = "cid-" + UUID.randomUUID()
        val toss = TossClient(cid, "cs", "ACC1", support(h3))
        h3.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"T","expires_in":"3600"}""") }
        h3.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(201, """{"orderId":""}""") }
        assertThrows<IllegalStateException> { toss.placeOrder("005930", "buy", 1) }
        assertTrue(redis.getExpire(RedisKeys.tossToken(cid)) in 1..3600)
    }

    /** 주문 POST 전(토큰·hashkey) 실패는 주문이 나간 적 없으니 거절. 인터럽트와 주문 POST 자체의 실패는 접수불명 경로 그대로. */
    @Test
    fun failuresBeforeOrderPostAreRejectionsAndNeverReachOrderEndpoint() {
        val orderOk = BrokerResponse(200, """{"rt_cd":"0","output":{"ODNO":"1"}}""")

        val h1 = FakeHttp()
        h1.on({ it.url.endsWith("/oauth2/tokenP") }) { throw IOException("token connect refused") }
        h1.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        h1.on({ it.url.endsWith("/order-cash") }) { orderOk }
        val e1 = assertThrows<OrderNotSent> { KisClient("k-" + UUID.randomUUID(), "secret", "50012345-01", support(h1)).placeOrder("005930", "buy", 1) }
        assertTrue(e1.message!!.startsWith("주문 전송 전 실패: "), e1.message)
        assertEquals(0, h1.calls.count { it.url.endsWith("/order-cash") })

        val h2 = FakeHttp()
        val kis = kisWithToken(h2)
        h2.on({ it.url.endsWith("/order-cash") }) { orderOk }
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { throw IOException("hashkey read timeout") }
        assertThrows<OrderNotSent> { kis.placeOrder("005930", "buy", 1) }
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"msg1":"오류"}""") }
        assertEquals("주문 전송 전 실패: java.lang.IllegalStateException: hashkey 응답에 HASH 없음", assertThrows<OrderNotSent> { kis.placeOrder("005930", "buy", 1) }.message)
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(500, "boom") }
        assertEquals(KisError::class, assertThrows<KisError> { kis.placeOrder("005930", "buy", 1) }::class) // 증권사가 돌려준 실패는 OrderNotSent 가 아니다
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { throw InterruptedException() }
        assertThrows<InterruptedException> { kis.placeOrder("005930", "buy", 1) } // 틱 중단·종료 — 거절로 확정하지 않는다
        assertEquals(0, h2.calls.count { it.url.endsWith("/order-cash") })
        h2.on({ it.url.endsWith("/uapi/hashkey") }) { BrokerResponse(200, """{"HASH":"h"}""") }
        h2.on({ it.url.endsWith("/order-cash") }) { throw IOException("order read timeout") }
        assertThrows<IOException> { kis.placeOrder("005930", "buy", 1) } // 주문 POST 자체의 연결 장애는 접수불명
        assertEquals(1, h2.calls.count { it.url.endsWith("/order-cash") })

        val h3 = FakeHttp()
        h3.on({ it.url.endsWith("/oauth2/token") }) { throw IOException("token connect refused") }
        h3.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(201, """{"orderId":"1"}""") }
        val e3 = assertThrows<OrderNotSent> { TossClient("c-" + UUID.randomUUID(), "s", "ACC", support(h3)).placeOrder("005930", "buy", 1) }
        assertTrue(e3.message!!.startsWith("주문 전송 전 실패: "), e3.message)
        assertEquals("토스 계좌(accountSeq)가 등록되지 않았습니다.", assertThrows<TossError> { TossClient("c-" + UUID.randomUUID(), "s", null, support(h3)).placeOrder("005930", "buy", 1) }.message)
        assertEquals(1, h3.calls.count { it.url.endsWith("/oauth2/token") }) // 계좌 검사가 토큰 발급보다 먼저(원본 순서)
        assertEquals(0, h3.calls.count { it.url.endsWith("/api/v1/orders") })
    }

    private fun balancePage(rows: String, trCont: String?, fk: String = "", nk: String = "", summary: String = "{}") = BrokerResponse(
        200, """{"rt_cd":"0","output1":[$rows],"output2":[$summary],"ctx_area_fk100":"$fk","ctx_area_nk100":"$nk"}""",
        if (trCont == null) emptyMap() else mapOf("tr_cont" to listOf(trCont)),
    )

    private val rowA = """{"pdno":"005930","hldg_qty":"10","pchs_avg_pric":"70000"}"""
    private val rowB = """{"pdno":"000660","hldg_qty":"5","pchs_avg_pric":"120000"}"""

    /** 공식 규약(`inquire_balance.py`): 응답 tr_cont F/M 이면 응답 본문의 연속 키를 다음 요청에 싣고 요청 헤더 tr_cont=N, D/E 에서 끝. */
    @Test
    fun kisBalanceFollowsContinuationAcrossPages() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/inquire-balance") }) {
            if (it.query["CTX_AREA_FK100"].isNullOrEmpty()) balancePage(rowA, "F", "FK1", "NK1", """{"tot":"1"}""")
            else balancePage(rowB, "D", summary = """{"tot":"2"}""")
        }
        val b = kis.getBalance()
        assertEquals(listOf("005930", "000660"), b.positionsNormalized().map { it.symbol })
        assertEquals(2, (b.summary as List<*>).size) // 공식 예제는 페이지마다의 output2 를 이어 붙인다
        val calls = http.calls.filter { it.url.endsWith("/inquire-balance") }
        assertEquals(2, calls.size)
        assertFalse(calls[0].headers.keys.any { it.equals("tr_cont", ignoreCase = true) }) // 첫 조회는 기존처럼 헤더 없음
        assertEquals("N", calls[1].headers["tr_cont"])
        assertEquals("FK1", calls[1].query["CTX_AREA_FK100"])
        assertEquals("NK1", calls[1].query["CTX_AREA_NK100"])
        // 엄격 경로(레거시 증거)도 마지막 페이지가 D/E 면 합쳐서 받는다
        assertEquals(2, kis.legacyBalanceEvidence().positions.size)
    }

    @Test
    fun kisBalanceWithoutTrContHeaderIsSinglePage() {
        for (header in listOf(null, "", " ")) {
            val http = FakeHttp()
            val kis = kisWithToken(http)
            http.on({ it.url.endsWith("/inquire-balance") }) { balancePage(rowA, header, "FK1", "NK1") }
            assertEquals(1, kis.getBalance().positions.size)
            assertEquals(1, http.calls.count { it.url.endsWith("/inquire-balance") }, "header=$header")
        }
    }

    /** 일부만 보이는 잔고는 "보유 없음"으로 오독된다 — 규약 위반·상한 초과는 부분 잔고가 아니라 조회 실패. */
    @Test
    fun kisBalanceProtocolViolationsAreErrorsNotPartialBalances() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on({ it.url.endsWith("/inquire-balance") }) { balancePage(rowA, "M") } // 다음 있음인데 연속 키 없음
        assertThrows<KisError> { kis.getBalance() }
        assertEquals(1, http.calls.count { it.url.endsWith("/inquire-balance") })
        http.on({ it.url.endsWith("/inquire-balance") }) { balancePage(rowA, "X", "FK1", "NK1") } // 알 수 없는 값
        assertThrows<KisError> { kis.getBalance() }
        http.calls.clear()
        http.on({ it.url.endsWith("/inquire-balance") }) { balancePage(rowA, "M", "FK1", "NK1") } // 끝나지 않는 응답
        assertThrows<KisError> { kis.getBalance() }
        assertEquals(KisClient.BALANCE_MAX_PAGES, http.calls.count { it.url.endsWith("/inquire-balance") })
        // 엄격 경로는 헤더가 없는 응답도 기존처럼 실패
        http.on({ it.url.endsWith("/inquire-balance") }) { balancePage(rowA, null) }
        assertThrows<KisError> { kis.legacyBalanceEvidence() }
    }

    private fun tossWithToken(http: FakeHttp): TossClient {
        http.on({ it.url.endsWith("/oauth2/token") }) { BrokerResponse(200, """{"access_token":"T","expires_in":3600}""") }
        return TossClient("cid-" + UUID.randomUUID(), "cs", "ACC1", support(http))
    }

    /** 공식 스펙: 토큰 발급을 뺀 성공 응답은 `{"result": ...}` 봉투(`ApiResponse`). 봉투·최상위 두 모양이 같은 결과여야 한다. */
    @Test
    fun tossOrderAcceptsEnvelopedAndFlatResponses() {
        val http = FakeHttp()
        val toss = tossWithToken(http)
        for (body in listOf("""{"orderId":"A1"}""", """{"result":{"orderId":"A1","clientOrderId":null}}""")) {
            http.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(200, body) }
            assertEquals("A1", toss.placeOrder("005930", "buy", 1).orderId, body)
        }
        for (body in listOf("""{"result":{}}""", """{"result":{"orderId":""}}""", """{"result":{"orderId":null}}""", """{"result":[]}""")) {
            http.on({ it.url.endsWith("/api/v1/orders") }) { BrokerResponse(200, body) }
            assertThrows<OrderOutcomeUnknown>(body) { toss.placeOrder("005930", "buy", 1) } // 주문번호가 없으면 지금처럼 접수불명
        }
    }

    @Test
    fun tossExecutionAndBalanceAcceptEnvelopedAndFlatResponses() {
        val http = FakeHttp()
        val toss = tossWithToken(http)
        val exec = """"status":"FILLED","orderId":"A1","symbol":"005930","side":"BUY","quantity":"2","orderedAt":"2026-10-19T10:00:01+09:00","execution":{"filledQuantity":"2","averageFilledPrice":"70010","filledAmount":"140020"}"""
        for (body in listOf("{$exec}", """{"result":{$exec}}""")) {
            http.on({ "/api/v1/orders/A1" in it.url }) { BrokerResponse(200, body) }
            val f = toss.getOrderExecution("A1")
            assertEquals(2, f.filledQty, body)
            assertEquals(BigDecimal("70010"), f.avgPrice)
            assertTrue(f.fullyFilled)
            assertEquals("buy", toss.legacyFillEvidence("A1", null, "005930", LocalDate.parse("2026-10-19"))?.side, body)
        }
        http.on({ "/api/v1/orders/A1" in it.url }) { BrokerResponse(200, """{"result":{"orderId":"A1"}}""") }
        assertEquals(0, toss.getOrderExecution("A1").filledQty) // 봉투 안에 체결 정보가 없으면 지금처럼 미체결 0

        val holdings = """"totalPurchaseAmount":{"krw":"700000"},"items":[{"symbol":"005930","quantity":"10","averagePurchasePrice":"70000"}]"""
        val flat = run { http.on({ it.url.endsWith("/api/v1/holdings") }) { BrokerResponse(200, "{$holdings}") }; toss.getBalance() }
        http.on({ it.url.endsWith("/api/v1/holdings") }) { BrokerResponse(200, """{"result":{$holdings}}""") }
        val wrapped = toss.getBalance()
        assertEquals(1, wrapped.positions.size)
        assertEquals(flat.positions, wrapped.positions)
        assertEquals(flat.summary, wrapped.summary)
        assertEquals(setOf("totalPurchaseAmount"), (wrapped.summary as Map<*, *>).keys)
    }

    /**
     * 토스 공식 스펙 `OrderStatus`(openapi.json:6337) 중 이 주문이 더 체결되지 않는 상태(FILLED·CANCELED·REJECTED)만 terminal.
     * 진행 중·정정됨(REPLACED — 체결이 새 주문번호로 넘어간다)·정정/취소 거부 레코드·모르는 값·빈 값은 false.
     * 종료 상태여도 체결 수량을 읽지 못하면 false — "체결 0 으로 종료"와 "읽지 못함"을 구분한다.
     */
    @Test
    fun tossExecutionIsTerminalOnlyForFinalStatusWithReadableFilledQuantity() {
        val http = FakeHttp()
        val toss = tossWithToken(http)
        fun fill(body: String): Fill {
            http.on({ "/api/v1/orders/A1" in it.url }) { BrokerResponse(200, body) }
            return toss.getOrderExecution("A1")
        }
        fun both(order: String) = listOf("{$order}", """{"result":{$order}}""")
        for (status in listOf("FILLED", "CANCELED", "REJECTED", "canceled")) {
            for (filled in listOf("0", "3")) {
                for (body in both(""""status":"$status","execution":{"filledQuantity":"$filled"}""")) {
                    val f = fill(body)
                    assertTrue(f.terminal, body)
                    assertEquals(filled.toInt(), f.filledQty, body)
                }
            }
            for (body in both(""""status":"$status","execution":{"filledQuantity":3}""")) assertTrue(fill(body).terminal, body) // JSON 숫자
        }
        for (status in listOf("PENDING", "PENDING_CANCEL", "PENDING_REPLACE", "PARTIAL_FILLED", "REPLACED", "CANCEL_REJECTED", "REPLACE_REJECTED", "EXPIRED", "", " FILLED")) {
            for (body in both(""""status":"$status","execution":{"filledQuantity":"1"}""")) assertFalse(fill(body).terminal, body)
        }
        for (order in listOf(
            """"execution":{"filledQuantity":"1"}""", // status 없음
            """"status":null,"execution":{"filledQuantity":"1"}""",
            """"status":"CANCELED"""", // execution 없음
            """"status":"CANCELED","execution":null""",
            """"status":"CANCELED","execution":{}""", // filledQuantity 없음
            """"status":"CANCELED","execution":{"filledQuantity":null}""",
            """"status":"CANCELED","execution":{"filledQuantity":""}""",
            """"status":"CANCELED","execution":{"filledQuantity":"abc"}""",
            """"status":"CANCELED","execution":{"filledQuantity":"1.5"}""", // 정수가 아니면 filledQty(1)와 다르다
            """"status":"CANCELED","execution":{"filledQuantity":"-1"}""",
        )) {
            for (body in both(order)) {
                val f = fill(body)
                assertFalse(f.terminal, body)
            }
        }
        assertEquals(0, fill("""{"status":"CANCELED","execution":{"filledQuantity":"abc"}}""").filledQty) // 기존 filledQty 계산(원본)은 그대로
    }

    private val orderDay = LocalDate.parse("2026-10-19")

    private fun ordersPage(rows: String, trCont: String?, fk: String = "", nk: String = "") = BrokerResponse(
        200, """{"rt_cd":"0","output1":[$rows],"output2":{},"ctx_area_fk100":"$fk","ctx_area_nk100":"$nk"}""",
        if (trCont == null) emptyMap() else mapOf("tr_cont" to listOf(trCont)),
    )

    @Suppress("LongParameterList")
    private fun orderRowJson(
        odno: String, pdno: String = "005930", side: String = "02", qty: String = "10", filled: String = "0", avg: String = "0",
        time: String = "090001", day: String? = "20261019", original: String? = "", extra: String = "",
    ) = buildString {
        append("""{"odno":"$odno","pdno":"$pdno","sll_buy_dvsn_cd":"$side","ord_qty":"$qty","tot_ccld_qty":"$filled","avg_prvs":"$avg","ord_tmd":"$time"""")
        if (day != null) append(""","ord_dt":"$day"""")
        if (original != null) append(""","orgn_odno":"$original"""")
        append(extra).append("}")
    }

    private val ordersUrl: (Call) -> Boolean = { it.url.endsWith("/inquire-daily-ccld") }

    /** 매수 전량·매도 미체결·매수 일부 체결 — 필드 매핑, 요청은 주문번호·종목 조건 없이 그 하루(공식 예제 파라미터). */
    @Test
    fun kisDailyOrdersMapsRowsAndQueriesTheWholeDay() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on(ordersUrl) {
            ordersPage(
                listOf(
                    orderRowJson("0000001", filled = "10", avg = "70050.25"),
                    orderRowJson("0000002", pdno = "000660", side = "01", qty = "5", time = "093000"),
                    orderRowJson("0000003", qty = "10", filled = "4", avg = "70000", time = "bad", original = null, day = null),
                ).joinToString(","),
                "D",
            )
        }
        val rows = kis.dailyOrders(orderDay)!!
        assertEquals(
            listOf(
                BrokerOrderRow("0000001", "005930", "buy", 10, 10, BigDecimal("70050.25"), java.time.LocalTime.of(9, 0, 1)),
                BrokerOrderRow("0000002", "000660", "sell", 5, 0, null, java.time.LocalTime.of(9, 30)),
                BrokerOrderRow("0000003", "005930", "buy", 10, 4, BigDecimal("70000"), null), // 시각을 못 읽어도 행은 남는다
            ),
            rows,
        )
        val call = http.calls.single { ordersUrl(it) }
        assertEquals("VTTC0081R", call.headers["tr_id"])
        assertEquals("20261019", call.query["INQR_STRT_DT"])
        assertEquals("20261019", call.query["INQR_END_DT"])
        assertEquals("", call.query["ODNO"])
        assertEquals("", call.query["PDNO"])
        assertEquals("00", call.query["SLL_BUY_DVSN_CD"])
        assertEquals("00", call.query["CCLD_DVSN"])
        assertEquals("ALL", call.query["EXCG_ID_DVSN_CD"])
        assertFalse(call.headers.keys.any { it.equals("tr_cont", ignoreCase = true) })
        val prod = FakeHttp()
        val prodKis = kisWithToken(prod, "prod")
        prod.on(ordersUrl) { ordersPage("", "E") }
        prodKis.dailyOrders(orderDay)
        assertEquals("TTTC0081R", prod.calls.last().headers["tr_id"])
    }

    @Test
    fun kisDailyOrdersFollowsContinuationAcrossPages() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on(ordersUrl) {
            if (it.query["CTX_AREA_FK100"].isNullOrEmpty()) ordersPage(orderRowJson("0000001"), "M", "FK1", "NK1")
            else ordersPage(orderRowJson("0000002", side = "01"), "E")
        }
        assertEquals(listOf("0000001", "0000002"), kis.dailyOrders(orderDay)!!.map { it.orderNo })
        val calls = http.calls.filter { ordersUrl(it) }
        assertEquals(2, calls.size)
        assertEquals("N", calls[1].headers["tr_cont"])
        assertEquals("FK1", calls[1].query["CTX_AREA_FK100"])
        assertEquals("NK1", calls[1].query["CTX_AREA_NK100"])
    }

    /**
     * 정정·취소 주문 행(원주문번호 있음)은 빼지 않고 originalOrderNo(trim 한 원문)로 표시한다 — 정정 주문에서 난 체결을 원주문에 이을 재료.
     * 원주문번호가 비었거나 전부 0 이면 원주문(null). 다른 주문일·번호 없는 행은 뺀다.
     */
    @Test
    fun kisDailyOrdersMarksAmendCancelRowsAndDropsOtherDayAndNumberlessRows() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on(ordersUrl) {
            ordersPage(
                listOf(
                    orderRowJson("0000001", original = "0000000000"),
                    orderRowJson("0000002", original = "0000000001", extra = ""","cncl_yn":"N""""), // 정정·취소 주문 행
                    orderRowJson("0000003", day = "20261018"), // 다른 주문일
                    orderRowJson("0000004", original = null, day = null), // 판별 필드 없음 → 원주문
                    orderRowJson("  "), // 주문번호 없음
                    orderRowJson("0000006", original = "", extra = ""","cncl_yn":"Y""""), // 취소된 원주문 자체는 남는다
                    orderRowJson("0000007", original = " 0000001 ", qty = "0", filled = "0"), // 0 채움을 고치지 않고 trim 만
                ).joinToString(","),
                "D",
            )
        }
        val rows = kis.dailyOrders(orderDay)!!
        assertEquals(listOf("0000001", "0000002", "0000004", "0000006", "0000007"), rows.map { it.orderNo })
        assertEquals(listOf(null, "0000000001", null, null, "0000001"), rows.map { it.originalOrderNo })
        assertEquals(0, rows.last().orderQty)
        // 정정·취소 행도 읽지 못하면 목록 전체 실패
        http.on(ordersUrl) { ordersPage(orderRowJson("0000008", original = "0000001", qty = ""), "D") }
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
    }

    /** "없음"이 근거로 쓰일 수 있는 목록 — 완결을 확인하지 못하면 빈/부분 목록이 아니라 KisError. */
    @Test
    fun kisDailyOrdersFailsWhenCompletenessIsNotConfirmed() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        val row = orderRowJson("0000001")
        for (header in listOf(null, "", " ")) { // 마지막 응답의 tr_cont 없음 — 잔고와 달리 한 페이지로 끝난 것으로 보지 않는다
            http.on(ordersUrl) { ordersPage(row, header) }
            assertThrows<KisError>("header=$header") { kis.dailyOrders(orderDay) }
        }
        http.on(ordersUrl) { ordersPage(row, "M") } // 다음 있음인데 연속 키 없음
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
        http.on(ordersUrl) { ordersPage(row, "X", "FK1", "NK1") } // 해석 못 하는 값
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
        http.calls.removeAll { ordersUrl(it) }
        http.on(ordersUrl) { ordersPage(row, "M", "FK1", "NK1") } // 끝나지 않는 응답
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
        assertEquals(KisClient.DAILY_ORDERS_MAX_PAGES, http.calls.count { ordersUrl(it) })
        http.on(ordersUrl) { BrokerResponse(200, """{"rt_cd":"0","ctx_area_fk100":"","ctx_area_nk100":""}""", mapOf("tr_cont" to listOf("D"))) } // output1 자체가 없음
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
    }

    /** 읽지 못한 행을 조용히 0 으로 만들지 않는다: 수량·매도매수구분·종목 해석 실패는 목록 전체 실패. */
    @Test
    fun kisDailyOrdersFailsOnUnreadableRowFields() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        for (bad in listOf(
            orderRowJson("0000001", filled = ""), orderRowJson("0000001", qty = "abc"), orderRowJson("0000001", filled = "1.5"),
            orderRowJson("0000001", filled = "-1"), orderRowJson("0000001", side = ""), orderRowJson("0000001", pdno = " "),
        )) {
            http.on(ordersUrl) { ordersPage(bad, "D") }
            assertThrows<KisError>(bad) { kis.dailyOrders(orderDay) }
        }
    }

    @Test
    fun kisDailyOrdersQueryErrorIsKisError() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on(ordersUrl) { BrokerResponse(200, """{"rt_cd":"1","msg_cd":"EGW99999","msg1":"조회 오류"}""") }
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
        http.on(ordersUrl) { BrokerResponse(500, "boom") }
        assertThrows<KisError> { kis.dailyOrders(orderDay) }
    }

    /** 완결된 빈 목록은 "그날 주문 없음" — null(미지원)과 다르다. */
    @Test
    fun kisDailyOrdersEmptyCompleteListIsEmptyNotNull() {
        val http = FakeHttp()
        val kis = kisWithToken(http)
        http.on(ordersUrl) { ordersPage("", "D") }
        assertEquals(emptyList(), kis.dailyOrders(orderDay))
    }

    @Test
    fun tossDailyOrdersIsUnsupported() {
        val http = FakeHttp()
        val toss = tossWithToken(http)
        assertNull(toss.dailyOrders(orderDay))
        assertTrue(http.calls.isEmpty())
    }

    /**
     * 토스 공식 스펙 `HoldingsItem`(openapi.json:5600) 의 수량은 `quantity`, 평단은 `averagePurchasePrice` 다.
     * 원본 `base.py:136,141` 은 qty/avg_price 만 읽어 토스 보유가 전부 수량 0 으로 빠졌다 — 기존 키 우선, 원본 `a or b` 의미(빈 문자열·0 은 다음 키).
     */
    @Test
    fun positionsNormalizedReadsTossSpecKeysAndKeepsOldKeys() {
        fun norm(vararg p: Map<String, Any?>) = Balance(p.toList()).positionsNormalized()
        assertEquals(
            listOf(NormalizedPosition("005930", 100, BigDecimal("65000"))),
            norm(mapOf("symbol" to "005930", "name" to "삼성전자", "quantity" to "100", "averagePurchasePrice" to "65000", "lastPrice" to "72000")),
        )
        // 기존 모양은 그대로
        assertEquals(listOf(NormalizedPosition("005930", 10, BigDecimal("70000"))), norm(mapOf("pdno" to "005930", "hldg_qty" to "10", "pchs_avg_pric" to "70000")))
        assertEquals(listOf(NormalizedPosition("000660", 5, BigDecimal("120000"))), norm(mapOf("symbol" to "000660", "qty" to 5, "avg_price" to "120000")))
        // 기존 키가 있으면 기존 키가 이긴다
        assertEquals(listOf(NormalizedPosition("005930", 3, BigDecimal("1"))), norm(mapOf("symbol" to "005930", "qty" to "3", "quantity" to "9", "avg_price" to "1", "averagePurchasePrice" to "2")))
        // 원본 `a or b`: 앞 키가 빈 문자열이면 다음 키(`?:` 는 null 만 거른다)
        assertEquals(listOf(NormalizedPosition("005930", 7, BigDecimal("2"))), norm(mapOf("symbol" to "005930", "hldg_qty" to "", "quantity" to "7", "pchs_avg_pric" to "", "averagePurchasePrice" to "2")))
        // 수량 0 은 제외, 평단 "0"·없음은 null
        assertEquals(emptyList(), norm(mapOf("symbol" to "005930", "quantity" to "0", "averagePurchasePrice" to "65000")))
        assertEquals(listOf(NormalizedPosition("005930", 4, null)), norm(mapOf("symbol" to "005930", "quantity" to "4", "averagePurchasePrice" to "0")))
        assertEquals(listOf(NormalizedPosition("005930", 4, null)), norm(mapOf("symbol" to "005930", "quantity" to "4")))
    }
}
