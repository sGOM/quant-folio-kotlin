package com.quantfolio.broker

import com.quantfolio.common.RedisKeys
import org.slf4j.LoggerFactory
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal

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
    override val orderAccountKey = BrokerAccountKey.toss(clientId, account)

    fun accessToken(): String = s.cachedToken(RedisKeys.tossToken(clientId), "토스") {
        val r = s.http.send("POST", "$baseUrl/oauth2/token", form = linkedMapOf("grant_type" to "client_credentials", "client_id" to clientId, "client_secret" to clientSecret))
        if (r.status != 200) throw TossError("토큰 발급 실패: HTTP ${r.status} ${r.body.take(200)}")
        val data = s.parse(r.body)
        val token = (data["access_token"] as String?)?.takeIf { it.isNotEmpty() } ?: throw TossError("토큰 응답에 access_token 없음: $data")
        token to expiresIn(data)
    }

    private fun headers(withAccount: Boolean): Map<String, String> {
        if (withAccount && account.isNullOrEmpty()) throw TossError("토스 계좌(accountSeq)가 등록되지 않았습니다.")
        val h = linkedMapOf("authorization" to "Bearer ${accessToken()}", "content-type" to "application/json")
        if (withAccount) h["X-Tossinvest-Account"] = account!!
        return h
    }

    /** 429 면 Retry-After(0.5~5초)만큼 기다려 1회 재시도. [orderSubmit] 이면 접수 여부를 알 수 없는 응답(5xx·408)을 거절이 아니라 [OrderOutcomeUnknown] 으로 올린다. */
    private fun request(method: String, path: String, withAccount: Boolean = false, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null, orderSubmit: Boolean = false): Any {
        for (attempt in 0 until REQUEST_ATTEMPTS) {
            val r = s.http.send(method, "$baseUrl$path", query, headers(withAccount), json = body?.let { s.json.writeValueAsString(it) })
            if (r.status == 429 && attempt < REQUEST_ATTEMPTS - 1) {
                val wait = ((r.header("Retry-After")?.takeIf { it.isNotBlank() } ?: r.header("X-RateLimit-Reset"))?.toDoubleOrNull() ?: 1.0).coerceIn(0.5, MAX_RETRY_AFTER)
                LoggerFactory.getLogger(javaClass).warn("토스 rate limit(429) — {}s 후 재시도 {}", wait, path)
                s.sleeper((wait * 1000).toLong())
                continue
            }
            if (orderSubmit && (r.status >= 500 || r.status == 408)) {
                throw OrderOutcomeUnknown("토스 주문 응답 불명: HTTP ${r.status}")
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
        // 계좌 검사·토큰 확보를 주문 POST 앞에서 먼저 — request() 는 재시도(429)마다 헤더를 다시 만들어 그 안의 실패는 전송 전/후를 구분할 수 없다
        beforeOrderPost { headers(withAccount = true) }
        val data = request("POST", "/api/v1/orders", withAccount = true, body = body, orderSubmit = true).unwrapResult()
        // 원본 `a or b` + `if not order_id` — 빈 문자열도 없음으로 본다(빈 주문번호로는 정합이 체결을 못 찾는다)
        val id = listOf(data["orderId"], data["order_id"]).firstOrNull { it != null && it.toString().isNotBlank() }
            ?: throw OrderOutcomeUnknown("토스 주문 응답에 orderId 없음")
        return OrderResult(id.toString(), raw = data)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getOrderExecution(orderId: String, symbol: String?): Fill {
        val data = request("GET", "/api/v1/orders/$orderId", withAccount = true).unwrapResult()
        val ex = data["execution"] as? Map<String, Any?> ?: emptyMap()
        val filled = runCatching { ex["filledQuantity"].orZero().toDouble().toInt() }.getOrDefault(0)
        val avgRaw = ex["averageFilledPrice"]?.toString()?.takeIf { it.isNotEmpty() }
        val avg = if (filled > 0 && avgRaw != null) runCatching { BigDecimal(avgRaw) }.getOrNull() else null
        val status = data["status"]?.toString()?.uppercase()
        // filled 는 원본대로 못 읽어도 0 — 종료 판단에는 실제로 읽은 0 이상의 정수만(못 읽은 것을 "체결 0 으로 종료"로 오독하지 않게)
        val readQty = ex["filledQuantity"]?.toString()?.trim()?.toBigDecimalOrNull()
            ?.let { runCatching { it.intValueExact() }.getOrNull() }?.takeIf { it >= 0 && it == filled }
        return Fill(filled, avg, status == "FILLED", data, terminal = status in TERMINAL_STATUSES && readQty != null)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getBalance(): Balance {
        val data = request("GET", "/api/v1/holdings", withAccount = true).unwrapResult()
        return Balance((data["items"] as? List<Map<String, Any?>>).orEmpty(), data.filterKeys { it != "items" })
    }

    /**
     * 공식 스펙상 토큰 발급 외 성공 응답은 `{"result": ...}` 봉투(`ApiResponse`)다. 실응답을 확인하지 못했으므로 두 모양을 모두 받는다 —
     * 최상위 `result` 가 객체면 그 안을, 아니면 최상위를 본문으로 본다(원본 `toss.py` 는 주문·체결·잔고를 최상위에서 읽는다). 객체 응답용(시세 목록은 [getQuote]).
     */
    @Suppress("UNCHECKED_CAST")
    private fun Any.unwrapResult(): Map<String, Any?> {
        val top = this as Map<String, Any?>
        return top["result"] as? Map<String, Any?> ?: top
    }

    companion object {
        /**
         * 이 주문이 더 체결되지 않는 상태 — 공식 스펙(openapi.json `OrderStatus`) 설명: FILLED 전량 체결, CANCELED 취소 완료·REJECTED 거부
         * (둘 다 "execution.filledQuantity 로 부분 체결 여부 확인"). 제외: PENDING·PENDING_CANCEL·PENDING_REPLACE·PARTIAL_FILLED(진행 중),
         * REPLACED(정정됨 — 정정은 새 orderId 를 발급해 이후 체결은 그 주문에 잡힌다), CANCEL_REJECTED·REPLACE_REJECTED(거부된 요청의 별도
         * 레코드, 원주문은 이전 상태로 복귀). 스펙의 CLOSED 그룹 설명은 PARTIAL_FILLED 도 넣지만 같은 스펙의 다른 설명과 어긋나 종료로 보지 않는다.
         * 스펙에 없는 값(스펙: "unknown code 허용")은 종료 근거가 아니다.
         */
        private val TERMINAL_STATUSES = setOf("FILLED", "CANCELED", "REJECTED")

        /** 429 재시도: 최대 시도 횟수와 Retry-After 대기 상한(초) — 락 TTL 산식(`RedisKeys`)의 재료. */
        const val REQUEST_ATTEMPTS = 2
        const val MAX_RETRY_AFTER = 5.0
    }
}
