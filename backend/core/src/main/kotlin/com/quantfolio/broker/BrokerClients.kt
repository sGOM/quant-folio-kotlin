package com.quantfolio.broker

import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.quant.pyRepr
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

/** 증권사 클라이언트들이 공유하는 실행 환경. sleeper 는 테스트에서 실제 대기를 없애려고 주입한다. */
class BrokerSupport(
    val http: BrokerHttp,
    val redis: StringRedisTemplate,
    val props: QuantfolioProperties,
    val clock: Clock,
    val json: JsonMapper,
    val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) {
    /** Redis 토큰 캐시 + 분산 락(single-flight) — 여러 전략이 동시에 기동해도 발급은 한 번(KIS 분당 1회 제한). */
    fun cachedToken(cacheKey: String, label: String, issue: () -> Pair<String, Int>): String {
        redis.opsForValue().get(cacheKey)?.let { return it }
        val lockKey = "$cacheKey:lock"
        val gotLock = redis.opsForValue().setIfAbsent(lockKey, "1", Duration.ofSeconds(TOKEN_LOCK_TTL)) == true
        if (!gotLock) {
            repeat(TOKEN_LOCK_WAIT) {
                sleeper(500)
                redis.opsForValue().get(cacheKey)?.let { return it }
            }
        }
        try {
            redis.opsForValue().get(cacheKey)?.let { return it }
            val (token, expiresIn) = issue()
            val ttl = maxOf(expiresIn - TOKEN_TTL_SAFETY, 60L)
            redis.opsForValue().set(cacheKey, token, Duration.ofSeconds(ttl))
            log.info("{} 토큰 신규 발급 (ttl={}s)", label, ttl)
            return token
        } finally {
            if (gotLock) redis.delete(lockKey)
        }
    }

    fun parse(body: String): Map<String, Any?> = json.readValue(body)

    private companion object {
        val log = LoggerFactory.getLogger(BrokerSupport::class.java)
        const val TOKEN_TTL_SAFETY = 60L
        const val TOKEN_LOCK_TTL = 10L
        const val TOKEN_LOCK_WAIT = 8
    }
}

/**
 * 계좌 단위 호출 간격 강제(KIS 초당 한도 EGW00201 회피). 호출자에게 간격만큼 떨어진 슬롯을 순차 배정한다.
 * 프로세스 안의 버스트만 막는다 — web·engine·worker 교차 경합은 재시도가 보완.
 */
private class RateLimiter(private val minIntervalNanos: Long) {
    private var nextAt = 0L

    fun acquire(sleeper: (Long) -> Unit) {
        val wait: Long
        synchronized(this) {
            val now = System.nanoTime()
            val slot = maxOf(now, nextAt)
            nextAt = slot + minIntervalNanos
            wait = slot - now
        }
        if (wait > 0) sleeper(wait / 1_000_000)
    }
}

/**
 * 한국투자증권 KIS Developers REST(원본 `services/kis/client.py`). 기본 모의투자(vts), KIS_ENV=prod 일 때만 실전 도메인·tr_id.
 * app_key/secret 은 호출자가 복호화해 넘기며 로그에 남기지 않는다.
 */
class KisClient(
    private val appKey: String,
    private val appSecret: String,
    private val accountNo: String?,
    private val s: BrokerSupport,
) : BrokerClient {
    private val kis = s.props.kis
    private val baseUrl = kis.baseUrl

    private fun throttle() = LIMITERS.computeIfAbsent("${kis.env}:$appKey") {
        RateLimiter(if (kis.isPaperTrading) 500_000_000L else 120_000_000L)
    }.acquire(s.sleeper)

    fun accessToken(): String = s.cachedToken(RedisKeys.kisToken(kis.env, appKey), "KIS(env=${kis.env})") {
        throttle()
        val r = s.http.send("POST", "$baseUrl/oauth2/tokenP", json = s.json.writeValueAsString(linkedMapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)))
        if (r.status != 200) throw KisError("토큰 발급 실패: HTTP ${r.status} ${r.body.take(200)}")
        val data = s.parse(r.body)
        val token = data["access_token"] as String? ?: throw KisError("토큰 응답에 access_token 없음: $data")
        token to ((data["expires_in"] as? Number)?.toInt() ?: data["expires_in"]?.toString()?.toInt() ?: 86400)
    }

    private fun authHeaders(trId: String) = linkedMapOf(
        "content-type" to "application/json; charset=utf-8", "authorization" to "Bearer ${accessToken()}",
        "appkey" to appKey, "appsecret" to appSecret, "tr_id" to trId, "custtype" to "P",
    )

    /** 공통 호출 — HTTP·rt_cd 오류를 KisError 로, 유량제한(EGW00201)이면 0.6s×차수 백오프로 3회까지. 주문·체결·잔고에도 같은 재시도. */
    private fun request(method: String, url: String, headers: Map<String, String>, label: String, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null): Map<String, Any?> {
        var last: KisError? = null
        for (attempt in 0 until RATE_RETRY) {
            throttle()
            val r = s.http.send(method, url, query, headers, json = body?.let { s.json.writeValueAsString(it) })
            val err = if (r.status != 200) {
                KisError("$label 실패: HTTP ${r.status} ${r.body.take(200)}")
            } else {
                val data = s.parse(r.body)
                if (data["rt_cd"] == "0") return data
                KisError("$label 오류: [${data["msg_cd"] ?: ""}] ${data["msg1"] ?: data}")
            }
            if ("EGW00201" in (err.message ?: "") && attempt < RATE_RETRY - 1) {
                last = err
                s.sleeper(600L * (attempt + 1))
                continue
            }
            throw err
        }
        throw last ?: KisError("$label 실패")
    }

    @Suppress("UNCHECKED_CAST")
    fun currentPrice(symbol: String): Map<String, Any?> = request(
        "GET", "$baseUrl/uapi/domestic-stock/v1/quotations/inquire-price", authHeaders("FHKST01010100"), "시세 조회",
        linkedMapOf("FID_COND_MRKT_DIV_CODE" to "J", "FID_INPUT_ISCD" to symbol),
    )["output"] as Map<String, Any?>

    /** 부가 필드(등락·고저·시가)는 관대하게 — 결측은 0(소비처는 조회 API 뿐). */
    private fun dec(o: Map<String, Any?>, key: String): BigDecimal =
        o[key]?.toString()?.takeIf { it.isNotEmpty() }?.let { runCatching { BigDecimal(it) }.getOrNull() } ?: BigDecimal.ZERO

    /** 현재가는 엄격 — 결측을 0 으로 뭉개면 손절 판정이 "0 까지 폭락"으로 오독한다. */
    private fun price(o: Map<String, Any?>, symbol: String): BigDecimal {
        val raw = o["stck_prpr"]
        if (raw == null || raw.toString().isBlank()) throw KisError("시세 응답에 현재가(stck_prpr)가 없습니다: $symbol")
        return runCatching { BigDecimal(raw.toString().trim()) }.getOrElse { throw KisError("시세 현재가 파싱 실패: $symbol stck_prpr=${pyRepr(raw.toString())}") }
    }

    override fun getQuote(symbol: String): Quote {
        val o = currentPrice(symbol)
        val volume = runCatching { (o["acml_vol"]?.toString()?.takeIf { it.isNotEmpty() } ?: "0").toDouble().toLong() }.getOrDefault(0L)
        val status = o["iscd_stat_cls_code"]?.toString()?.trim() ?: ""
        return Quote(
            symbol, price(o, symbol), dec(o, "prdy_vrss"), dec(o, "prdy_ctrt"), volume, dec(o, "stck_hgpr"), dec(o, "stck_lwpr"), dec(o, "stck_oprc"),
            "KRW", isHaltedStatus(o["temp_stop_yn"]?.toString(), status, o["vi_cls_code"]?.toString()), status,
        )
    }

    override fun verifyConnection(): Boolean {
        accessToken()
        return true
    }

    /** '50012345-01' → (CANO, ACNT_PRDT_CD). */
    private fun accountParts(): Pair<String, String> {
        val acc = accountNo?.takeIf { it.isNotEmpty() } ?: throw KisError("계좌번호가 등록되지 않았습니다.")
        val raw = acc.replace("-", "").trim()
        if (raw.length < 10) throw KisError("계좌번호 형식 오류: $acc")
        return raw.substring(0, 8) to raw.substring(8, 10)
    }

    /** 주문 body 의 hashkey(변조 방지). */
    private fun hashkey(body: Map<String, Any?>): String {
        throttle()
        val r = s.http.send(
            "POST", "$baseUrl/uapi/hashkey",
            headers = mapOf("content-type" to "application/json; charset=utf-8", "appkey" to appKey, "appsecret" to appSecret),
            json = s.json.writeValueAsString(body),
        )
        if (r.status != 200) throw KisError("hashkey 발급 실패: HTTP ${r.status}")
        return s.parse(r.body)["HASH"].toString()
    }

    override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult {
        val ordDvsn = ORD_DVSN[orderType] ?: orderType
        val (cano, prdt) = accountParts()
        val buy = side == "buy"
        val trId = if (kis.isPaperTrading) (if (buy) "VTTC0802U" else "VTTC0801U") else (if (buy) "TTTC0802U" else "TTTC0801U")
        val body = linkedMapOf<String, Any?>("CANO" to cano, "ACNT_PRDT_CD" to prdt, "PDNO" to symbol, "ORD_DVSN" to ordDvsn, "ORD_QTY" to qty.toString(), "ORD_UNPR" to price.toString())
        val hash = hashkey(body)
        val headers = authHeaders(trId).apply { put("hashkey", hash) }
        val data = request("POST", "$baseUrl/uapi/domestic-stock/v1/trading/order-cash", headers, "주문", body = body)
        @Suppress("UNCHECKED_CAST")
        val out = data["output"] as? Map<String, Any?> ?: emptyMap()
        return OrderResult(out["ODNO"]?.toString(), out["KRX_FWDG_ORD_ORGNO"]?.toString(), data)
    }

    /** 당일 주문체결 조회 → 이 주문의 누적 체결수량·평균체결가. */
    @Suppress("UNCHECKED_CAST")
    override fun getOrderExecution(orderId: String, symbol: String?): Fill {
        val (cano, prdt) = accountParts()
        val today = MarketClock.todayKst(s.clock).format(DateTimeFormatter.BASIC_ISO_DATE)
        val data = request(
            "GET", "$baseUrl/uapi/domestic-stock/v1/trading/inquire-daily-ccld", authHeaders(if (kis.isPaperTrading) "VTTC8001R" else "TTTC8001R"), "체결 조회",
            linkedMapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to prdt, "INQR_STRT_DT" to today, "INQR_END_DT" to today, "SLL_BUY_DVSN_CD" to "00",
                "INQR_DVSN" to "00", "PDNO" to (symbol ?: ""), "CCLD_DVSN" to "00", "ORD_GNO_BRNO" to "", "ODNO" to orderId,
                "INQR_DVSN_3" to "00", "INQR_DVSN_1" to "", "CTX_AREA_FK100" to "", "CTX_AREA_NK100" to "",
            ),
        )
        val row = (data["output1"] as? List<Map<String, Any?>>).orEmpty().firstOrNull { it["odno"] == orderId }
            ?: return Fill(0, null, false, data)
        val filled = (row["tot_ccld_qty"]?.toString()?.takeIf { it.isNotEmpty() } ?: "0").toInt()
        val ordQty = (row["ord_qty"]?.toString()?.takeIf { it.isNotEmpty() } ?: "0").toInt()
        val avgRaw = listOf(row["avg_prvs"], row["ccld_prvs"]).firstOrNull { it != null && it.toString().isNotEmpty() }?.toString() ?: "0"
        val avg = if (filled > 0) runCatching { BigDecimal(avgRaw) }.getOrNull() else null
        return Fill(filled, avg, ordQty > 0 && filled >= ordQty, row)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getBalance(): Balance {
        val (cano, prdt) = accountParts()
        val data = request(
            "GET", "$baseUrl/uapi/domestic-stock/v1/trading/inquire-balance", authHeaders(if (kis.isPaperTrading) "VTTC8434R" else "TTTC8434R"), "잔고 조회",
            linkedMapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to prdt, "AFHR_FLPR_YN" to "N", "OFL_YN" to "", "INQR_DVSN" to "02", "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N", "FNCG_AMT_AUTO_RDPT_YN" to "N", "PRCS_DVSN" to "00", "CTX_AREA_FK100" to "", "CTX_AREA_NK100" to "",
            ),
        )
        return Balance((data["output1"] as? List<Map<String, Any?>>).orEmpty(), data["output2"] ?: emptyList<Any>())
    }

    private companion object {
        val LIMITERS = ConcurrentHashMap<String, RateLimiter>()
        val ORD_DVSN = mapOf("limit" to "00", "market" to "01")
        const val RATE_RETRY = 3
    }
}

/**
 * 토스증권 Open API(원본 `broker/toss.py`). REST 전용(WS 시세 없음 → 러너는 REST 폴링), 모의투자 없음(항상 실거래).
 * 자격증명은 (client_id, client_secret, accountSeq) 를 기존 app_key/app_secret/account_no 컬럼에 저장한다.
 */
class TossClient(
    private val clientId: String,
    private val clientSecret: String,
    private val account: String?,
    private val s: BrokerSupport,
) : BrokerClient {
    private val baseUrl = s.props.toss.baseUrl.trimEnd('/')

    fun accessToken(): String = s.cachedToken(RedisKeys.tossToken(clientId), "토스") {
        val r = s.http.send("POST", "$baseUrl/oauth2/token", form = linkedMapOf("grant_type" to "client_credentials", "client_id" to clientId, "client_secret" to clientSecret))
        if (r.status != 200) throw TossError("토큰 발급 실패: HTTP ${r.status} ${r.body.take(200)}")
        val data = s.parse(r.body)
        val token = data["access_token"] as String? ?: throw TossError("토큰 응답에 access_token 없음: $data")
        token to ((data["expires_in"] as? Number)?.toInt() ?: 86400)
    }

    private fun headers(withAccount: Boolean): Map<String, String> {
        if (withAccount && account.isNullOrEmpty()) throw TossError("토스 계좌(accountSeq)가 등록되지 않았습니다.")
        val h = linkedMapOf("authorization" to "Bearer ${accessToken()}", "content-type" to "application/json")
        if (withAccount) h["X-Tossinvest-Account"] = account!!
        return h
    }

    /** 429 면 Retry-After(0.5~5초)만큼 기다려 1회 재시도. */
    private fun request(method: String, path: String, withAccount: Boolean = false, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null): Any {
        for (attempt in 0..1) {
            val r = s.http.send(method, "$baseUrl$path", query, headers(withAccount), json = body?.let { s.json.writeValueAsString(it) })
            if (r.status == 429 && attempt == 0) {
                val wait = ((r.header("Retry-After") ?: r.header("X-RateLimit-Reset"))?.toDoubleOrNull() ?: 1.0).coerceIn(0.5, MAX_RETRY_AFTER)
                LoggerFactory.getLogger(javaClass).warn("토스 rate limit(429) — {}s 후 재시도 {}", wait, path)
                s.sleeper((wait * 1000).toLong())
                continue
            }
            if (r.status != 200 && r.status != 201) throw TossError("$method $path 실패: HTTP ${r.status} ${r.body.take(200)}")
            return if (r.body.isEmpty()) emptyMap<String, Any?>() else s.json.readValue<Any>(r.body)
        }
        throw TossError("$method $path 실패: 429 재시도 후에도 실패")
    }

    override fun verifyConnection(): Boolean {
        accessToken()
        return true
    }

    /** /prices 는 lastPrice 만 준다 — 등락·고저·시가는 0. */
    @Suppress("UNCHECKED_CAST")
    override fun getQuote(symbol: String): Quote {
        val data = request("GET", "/api/v1/prices", query = mapOf("symbols" to symbol))
        val items = (data as? List<Map<String, Any?>>) ?: (data as Map<String, Any?>).let { d ->
            listOf("result", "prices", "data").firstNotNullOfOrNull { (d[it] as? List<Map<String, Any?>>)?.takeIf { l -> l.isNotEmpty() } }.orEmpty()
        }
        val row = items.firstOrNull { it["symbol"] == symbol }
        val last = row?.get("lastPrice")
        if (row == null || last == null || last == "") throw TossError("시세 응답에 '$symbol' 매칭 없음: $data")
        val price = runCatching { BigDecimal(last.toString()) }.getOrElse { throw TossError("시세 가격 파싱 실패: $row") }
        return Quote(symbol, price, currency = row["currency"]?.toString()?.takeIf { it.isNotEmpty() } ?: "KRW")
    }

    @Suppress("UNCHECKED_CAST")
    override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult {
        val tossSide = mapOf("buy" to "BUY", "sell" to "SELL")[side] ?: throw TossError("알 수 없는 주문 방향: $side")
        val tossType = mapOf("market" to "MARKET", "limit" to "LIMIT")[orderType] ?: throw TossError("알 수 없는 주문 유형: $orderType")
        val body = linkedMapOf<String, Any?>("symbol" to symbol, "side" to tossSide, "orderType" to tossType, "quantity" to qty)
        if (tossType == "LIMIT") body["price"] = price
        val data = request("POST", "/api/v1/orders", withAccount = true, body = body) as Map<String, Any?>
        val id = data["orderId"] ?: data["order_id"] ?: throw TossError("주문 응답에 orderId 없음: $data")
        return OrderResult(id.toString(), raw = data)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getOrderExecution(orderId: String, symbol: String?): Fill {
        val data = request("GET", "/api/v1/orders/$orderId", withAccount = true) as Map<String, Any?>
        val ex = data["execution"] as? Map<String, Any?> ?: emptyMap()
        val filled = runCatching { (ex["filledQuantity"]?.toString()?.takeIf { it.isNotEmpty() } ?: "0").toDouble().toInt() }.getOrDefault(0)
        val avgRaw = ex["averageFilledPrice"]?.toString()?.takeIf { it.isNotEmpty() }
        val avg = if (filled > 0 && avgRaw != null) runCatching { BigDecimal(avgRaw) }.getOrNull() else null
        return Fill(filled, avg, data["status"]?.toString()?.uppercase() == "FILLED", data)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getBalance(): Balance {
        val data = request("GET", "/api/v1/holdings", withAccount = true) as Map<String, Any?>
        return Balance((data["items"] as? List<Map<String, Any?>>).orEmpty(), data.filterKeys { it != "items" })
    }

    private companion object {
        const val MAX_RETRY_AFTER = 5.0
    }
}
