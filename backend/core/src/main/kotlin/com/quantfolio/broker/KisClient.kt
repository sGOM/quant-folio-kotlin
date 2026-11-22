package com.quantfolio.broker

import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.quant.pyRepr
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.ConcurrentHashMap

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
    override val orderAccountKey = BrokerAccountKey.kis(accountNo, kis.isPaperTrading)

    private fun throttle() = LIMITERS.computeIfAbsent("${kis.env}:$appKey") {
        RateLimiter((if (kis.isPaperTrading) PAPER_MIN_INTERVAL_MS else PROD_MIN_INTERVAL_MS) * 1_000_000L)
    }.acquire(s.sleeper)

    fun accessToken(): String = s.cachedToken(RedisKeys.kisToken(kis.env, appKey), "KIS(env=${kis.env})") {
        throttle()
        val r = s.http.send("POST", "$baseUrl/oauth2/tokenP", json = s.json.writeValueAsString(linkedMapOf("grant_type" to "client_credentials", "appkey" to appKey, "appsecret" to appSecret)))
        if (r.status != 200) throw KisError("토큰 발급 실패: HTTP ${r.status} ${r.body.take(200)}")
        val data = s.parse(r.body)
        val token = (data["access_token"] as String?)?.takeIf { it.isNotEmpty() } ?: throw KisError("토큰 응답에 access_token 없음: $data") // 원본 `if not token`
        token to expiresIn(data)
    }

    private fun authHeaders(trId: String) = linkedMapOf(
        "content-type" to "application/json; charset=utf-8", "authorization" to "Bearer ${accessToken()}",
        "appkey" to appKey, "appsecret" to appSecret, "tr_id" to trId, "custtype" to "P",
    )

    /**
     * 공통 호출 — HTTP·rt_cd 오류를 KisError 로, 유량제한(본문 `msg_cd` EGW00201)이면 0.6s×차수 백오프로 3회까지. 주문·체결·잔고에도 같은 재시도.
     * EGW00215(원장 초당 거래건수 초과)는 주문 전송이 아닌 요청만 같은 재시도 대상이다.
     * [orderSubmit] 이면 접수 여부를 알 수 없는 응답(5xx·408·결과 코드 없음)을 거절이 아니라 [OrderOutcomeUnknown] 으로 올린다 —
     * 예외는 5xx 본문이 JSON 이고 `rt_cd`="1"·`msg_cd` ∈ [ORDER_REJECTED_ON_5XX] 일 때뿐(명시적 거절 [KisError]).
     */
    private fun request(method: String, url: String, headers: Map<String, String>, label: String, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null, orderSubmit: Boolean = false): Map<String, Any?> =
        requestPage(method, url, headers, label, query, body, orderSubmit).first

    /** [request] 와 같되 성공 응답의 `tr_cont` 헤더(앞뒤 공백 제거, 없으면 null)도 돌려준다 — 연속조회 호출부용. */
    private fun requestPage(method: String, url: String, headers: Map<String, String>, label: String, query: Map<String, String> = emptyMap(), body: Map<String, Any?>? = null, orderSubmit: Boolean = false): Pair<Map<String, Any?>, String?> {
        var last: KisError? = null
        for (attempt in 0 until RATE_RETRY) {
            throttle()
            val r = s.http.send(method, url, query, headers, json = body?.let { s.json.writeValueAsString(it) })
            // 200 이 아니어도 본문이 KIS JSON 일 수 있다(2026-11-16 실측: HTTP 500 + rt_cd="1"). JSON 객체가 아니면 null.
            val errorBody = if (r.status != 200) runCatching { s.parse(r.body) }.getOrNull() else null
            var code = errorBody?.get("msg_cd") as? String
            if (orderSubmit && (r.status >= 500 || r.status == 408)) {
                if (r.status >= 500 && errorBody?.get("rt_cd") == "1" && code in ORDER_REJECTED_ON_5XX) {
                    throw KisError("$label 오류: HTTP ${r.status} [$code] ${errorBody["msg1"] ?: ""}")
                }
                throw OrderOutcomeUnknown("KIS 주문 응답 불명: HTTP ${r.status}")
            }
            val err = if (r.status != 200) {
                KisError("$label 실패: HTTP ${r.status} ${r.body.take(200)}")
            } else {
                val data = s.parse(r.body)
                if (data["rt_cd"] == "0") return data to r.header("tr_cont")?.trim()
                if (orderSubmit && data["rt_cd"] != "1") throw OrderOutcomeUnknown("KIS 주문 응답에 유효한 결과 코드 없음")
                code = data["msg_cd"] as? String
                KisError("$label 오류: [${data["msg_cd"] ?: ""}] ${data["msg1"] ?: data}")
            }
            if ((code == "EGW00201" || (!orderSubmit && code == "EGW00215")) && attempt < RATE_RETRY - 1) {
                last = err
                s.sleeper(RATE_BACKOFF_MS * (attempt + 1))
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
        val volume = runCatching { o["acml_vol"].orZero().toDouble().toLong() }.getOrDefault(0L)
        val status = o["iscd_stat_cls_code"]?.toString()?.trim() ?: ""
        return Quote(
            symbol, price(o, symbol), dec(o, "prdy_vrss"), dec(o, "prdy_ctrt"), volume, dec(o, "stck_hgpr"), dec(o, "stck_lwpr"), dec(o, "stck_oprc"),
            "KRW", isHaltedStatus(o["temp_stop_yn"]?.toString(), status, o["vi_cls_code"]?.toString()), status,
        )
    }

    /**
     * 국내휴장일조회(공식 예제 `chk_holiday.py`, TR `CTCA0903R`) — [from] 이후 날짜별 개장일 여부(`opnd_yn`). 한 페이지만 읽는다(연속조회 안 함).
     * 원장 연동 API 라 KIS 는 하루 1회 이하 호출을 권한다 — 호출부가 캐시한다. 모의투자(vts) 미지원이라 호출하지 않고 [KisError].
     */
    @Suppress("UNCHECKED_CAST")
    fun holidays(from: LocalDate): Map<LocalDate, Boolean> {
        if (kis.isPaperTrading) throw KisError("국내휴장일조회는 모의투자 미지원")
        val rows = request(
            "GET", "$baseUrl/uapi/domestic-stock/v1/quotations/chk-holiday", authHeaders("CTCA0903R"), "휴장일 조회",
            linkedMapOf("BASS_DT" to from.format(DateTimeFormatter.BASIC_ISO_DATE), "CTX_AREA_NK" to "", "CTX_AREA_FK" to ""),
        )["output"] as? List<Map<String, Any?>> ?: throw KisError("휴장일 조회 응답에 output 목록이 없습니다.")
        return rows.associate { row ->
            val day = runCatching { LocalDate.parse(row["bass_dt"].toString().trim(), DateTimeFormatter.BASIC_ISO_DATE) }.getOrNull()
                ?: throw KisError("휴장일 조회 행의 bass_dt 를 읽을 수 없습니다: ${pyRepr(row["bass_dt"].toString())}")
            val open = when (row["opnd_yn"]?.toString()?.trim()) {
                "Y" -> true
                "N" -> false
                else -> throw KisError("휴장일 조회 행의 opnd_yn 을 해석할 수 없습니다: $day ${pyRepr(row["opnd_yn"].toString())}")
            }
            day to open
        }
    }

    override fun verifyConnection(): Boolean {
        accessToken()
        return true
    }

    /** '50012345-01' → (CANO, ACNT_PRDT_CD). */
    private fun accountParts(): Pair<String, String> {
        val acc = accountNo?.takeIf { it.isNotEmpty() } ?: throw KisError("계좌번호가 등록되지 않았습니다.")
        val raw = BrokerAccountKey.kisNumber(acc) ?: throw KisError("계좌번호 형식 오류: $acc")
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
        // 원본 `resp.json()["HASH"]` 은 없으면 KeyError — 주문을 보내기 전에 멈춘다("null" 을 hashkey 로 실어 보내지 않는다)
        return s.parse(r.body)["HASH"]?.toString() ?: throw IllegalStateException("hashkey 응답에 HASH 없음")
    }

    /** KIS 거래 ID — 모의투자는 V, 실전은 T 접두(예: TTC0802U → VTTC0802U / TTTC0802U). */
    private fun trId(suffix: String) = (if (kis.isPaperTrading) "V" else "T") + suffix

    override fun placeOrder(symbol: String, side: String, qty: Int, price: Int, orderType: String): OrderResult {
        val ordDvsn = ORD_DVSN[orderType] ?: orderType
        val (cano, prdt) = accountParts()
        val buy = side == "buy"
        val trId = trId(if (buy) "TTC0802U" else "TTC0801U")
        val body = linkedMapOf<String, Any?>("CANO" to cano, "ACNT_PRDT_CD" to prdt, "PDNO" to symbol, "ORD_DVSN" to ordDvsn, "ORD_QTY" to qty.toString(), "ORD_UNPR" to price.toString())
        val headers = beforeOrderPost {
            val hash = hashkey(body)
            authHeaders(trId).apply { put("hashkey", hash) }
        }
        val data = request("POST", "$baseUrl/uapi/domestic-stock/v1/trading/order-cash", headers, "주문", body = body, orderSubmit = true)
        @Suppress("UNCHECKED_CAST")
        val out = data["output"] as? Map<String, Any?> ?: emptyMap()
        return OrderResult(out["ODNO"]?.toString(), out["KRX_FWDG_ORD_ORGNO"]?.toString(), data)
    }

    override fun getOrderExecution(orderId: String, symbol: String?): Fill =
        getOrderExecution(orderId, symbol, MarketClock.todayKst(s.clock))

    /**
     * 주문일 하루만 조회한다. 날짜가 다른 주문의 번호가 같아도 서로 섞지 않는다.
     * TR 은 `TTC0081R`(3개월 이내 일별주문체결, 공식 샘플과 같은 TR) — 원본의 `TTC8001R` 은 2026-11-16 모의 실측에서 전량·부분 체결 주문 모두
     * `rt_cd=0` "조회할 내역 없음"(행 0개)이라 체결을 0 으로 오독했고, `VTTC0081R` + `ODNO` 는 그 주문의 행 하나를 돌려줬다. 실전(`TTTC0081R`)은 측정하지 못했다.
     */
    @Suppress("UNCHECKED_CAST")
    override fun getOrderExecution(orderId: String, symbol: String?, orderDate: LocalDate): Fill {
        val day = orderDate.format(DateTimeFormatter.BASIC_ISO_DATE)
        val data = dailyExecutions(orderId, symbol, day)
        val row = (data["output1"] as? List<Map<String, Any?>>).orEmpty().firstOrNull {
            val reportedDay = it["ord_dt"]?.toString()?.trim()
            it["odno"] == orderId && (reportedDay.isNullOrEmpty() || reportedDay == day)
        } ?: return Fill(0, null, false, data)
        val filled = row["tot_ccld_qty"].orZero().toInt()
        val ordQty = row["ord_qty"].orZero().toInt()
        val avgRaw = listOf(row["avg_prvs"], row["ccld_prvs"]).firstOrNull { it != null && it.toString().isNotEmpty() }?.toString() ?: "0"
        val avg = if (filled > 0) runCatching { BigDecimal(avgRaw) }.getOrNull() else null
        return Fill(filled, avg, ordQty > 0 && filled >= ordQty, row)
    }

    private fun dailyExecutions(orderId: String, symbol: String?, day: String): Map<String, Any?> {
        val (cano, prdt) = accountParts()
        return request(
            "GET", "$baseUrl/uapi/domestic-stock/v1/trading/inquire-daily-ccld", authHeaders(trId("TTC0081R")), "체결 조회",
            linkedMapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to prdt, "INQR_STRT_DT" to day, "INQR_END_DT" to day, "SLL_BUY_DVSN_CD" to "00",
                "INQR_DVSN" to "00", "PDNO" to (symbol ?: ""), "CCLD_DVSN" to "00", "ORD_GNO_BRNO" to "", "ODNO" to orderId,
                "INQR_DVSN_3" to "00", "INQR_DVSN_1" to "", "CTX_AREA_FK100" to "", "CTX_AREA_NK100" to "", "EXCG_ID_DVSN_CD" to "ALL",
            ),
        )
    }

    /**
     * 공식 예제(`koreainvestment/open-trading-api` `inquire_balance.py`)의 연속조회 규약: 응답 헤더 tr_cont 가 F/M 이면 응답 본문의
     * ctx_area_fk100·nk100 을 CTX_AREA_FK100·NK100 으로, 요청 헤더 tr_cont 를 N 으로 실어 다음 페이지를 받고 D/E 에서 끝난다.
     * 보유 종목(output1)은 합치고 계좌 요약(output2)은 예제처럼 이어 붙인다. 원본 Python 은 첫 페이지만 읽는다(`kis/client.py` get_balance).
     * 일부 페이지만 있는 잔고는 "보유 없음"으로 오독되므로 상한·규약 위반은 부분 잔고가 아니라 [KisError]. tr_cont 헤더가 없거나 빈 값이면 한 페이지로 끝난 것.
     */
    @Suppress("UNCHECKED_CAST")
    override fun getBalance(): Balance {
        val (cano, prdt) = accountParts()
        val positions = mutableListOf<Map<String, Any?>>()
        val summary = mutableListOf<Any?>()
        fetchPages(
            "잔고 조회", "$baseUrl/uapi/domestic-stock/v1/trading/inquire-balance", trId("TTC8434R"),
            linkedMapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to prdt, "AFHR_FLPR_YN" to "N", "OFL_YN" to "", "INQR_DVSN" to "02", "UNPR_DVSN" to "01",
                "FUND_STTL_ICLD_YN" to "N", "FNCG_AMT_AUTO_RDPT_YN" to "N", "PRCS_DVSN" to "00",
            ),
            BALANCE_MAX_PAGES, requireEnd = false,
        ) { data ->
            positions.addAll((data["output1"] as? List<Map<String, Any?>>).orEmpty())
            data["output2"]?.let { summary.addAll(it as? List<*> ?: listOf(it)) }
        }
        return Balance(positions, summary)
    }

    /**
     * 연속조회 공통 루프(잔고·주문 목록): [baseQuery] 에 연속 키(CTX_AREA_FK100·NK100)를 얹어 페이지마다 [onPage] 로 넘긴다.
     * 응답 tr_cont 가 F/M 이면 본문의 ctx_area_fk100·nk100 을 다음 요청에 싣고 요청 헤더 tr_cont=N, D/E 에서 끝. 헤더가 없거나 빈 값이면 거기서 끝난 것으로
     * 보되, [requireEnd] 면 마지막 응답이 D/E 여야 한다(없음도 실패). 해석 못 하는 tr_cont·연속 키 없음·[maxPages] 초과는 부분 결과가 아니라 [KisError].
     */
    private fun fetchPages(
        label: String, url: String, trId: String, baseQuery: Map<String, String>, maxPages: Int, requireEnd: Boolean, onPage: (Map<String, Any?>) -> Unit,
    ) {
        var fk = ""
        var nk = ""
        for (page in 1..maxPages) {
            val headers = authHeaders(trId).apply { if (page > 1) put("tr_cont", "N") }
            val (data, trCont) = requestPage("GET", url, headers, label, baseQuery + mapOf("CTX_AREA_FK100" to fk, "CTX_AREA_NK100" to nk))
            onPage(data)
            val more = when (trCont) {
                "M", "F" -> true
                null, "", "D", "E" -> false
                else -> throw KisError("$label 응답의 tr_cont 를 해석할 수 없습니다: ${pyRepr(trCont)}")
            }
            if (!more) {
                if (requireEnd && trCont !in setOf("D", "E")) throw KisError("완전한 체결 조회 결과를 확인할 수 없습니다.")
                return
            }
            fk = data["ctx_area_fk100"]?.toString().orEmpty()
            nk = data["ctx_area_nk100"]?.toString().orEmpty()
            if (fk.isBlank() && nk.isBlank()) throw KisError("$label 응답이 다음 페이지가 있다고 하는데 연속 키(ctx_area_fk100·nk100)가 없습니다.")
        }
        throw KisError("${label}가 ${maxPages}페이지를 넘어 끝나지 않습니다.")
    }

    /**
     * 주문일의 주문 목록 — 원본 Python 에는 없는 조회. 일별주문체결조회(공식 예제 `inquire_daily_ccld`)를 주문번호·종목 조건 없이 그 하루로 받는다.
     * "없음"이 근거로 쓰일 수 있어 잔고보다 엄격하다: 마지막 응답이 D/E 가 아니면(헤더 없음 포함) 완결 미확인 → [KisError].
     * 행 규칙: 주문일(ord_dt)이 있고 [day] 와 다르면 제외, 주문번호(odno)가 빈 행은 제외(어떤 주문에도 붙일 수 없다),
     * 원주문번호(orgn_odno)가 있는(공백·전부 0 이 아닌) 행은 정정·취소 주문 — 빼지 않고 [BrokerOrderRow.originalOrderNo] 에 trim 한 원문을 담는다.
     * 종목·매도매수구분·수량을 읽지 못하면(정정·취소 행 포함) 목록을 믿을 수 없으므로 [KisError].
     * 2026-11-16 모의 실측: 원주문 행 orgn_odno="0000000000", 정정·취소 행 orgn_odno 는 원주문 odno 와 같은 10자리 0 채움 표기(그래서 정규화 없이 비교한다).
     * 취소 행은 ord_qty=취소 수량·tot_ccld_qty=0·cncl_yn="Y", 정정 행은 cncl_yn="N". 정정된 원주문 행은 rmn_qty=0·tot_ccld_qty=0.
     */
    override fun dailyOrders(day: LocalDate): List<BrokerOrderRow>? {
        val (cano, prdt) = accountParts()
        val ymd = day.format(DateTimeFormatter.BASIC_ISO_DATE)
        val rows = mutableListOf<BrokerOrderRow>()
        fetchPages(
            "주문 목록 조회", "$baseUrl/uapi/domestic-stock/v1/trading/inquire-daily-ccld", trId("TTC0081R"),
            linkedMapOf(
                "CANO" to cano, "ACNT_PRDT_CD" to prdt, "INQR_STRT_DT" to ymd, "INQR_END_DT" to ymd, "SLL_BUY_DVSN_CD" to "00",
                "INQR_DVSN" to "00", "PDNO" to "", "CCLD_DVSN" to "00", "ORD_GNO_BRNO" to "", "ODNO" to "",
                "INQR_DVSN_3" to "00", "INQR_DVSN_1" to "", "EXCG_ID_DVSN_CD" to "ALL",
            ),
            DAILY_ORDERS_MAX_PAGES, requireEnd = true,
        ) { data ->
            val output = data["output1"] as? List<*> ?: throw KisError("주문 목록 조회 응답에 output1 목록이 없습니다.")
            output.forEach { orderRow(it, ymd)?.let(rows::add) }
        }
        return rows
    }

    /** [dailyOrders] 의 행 하나 — 목록에서 뺄 행이면 null, 읽을 수 없으면 [KisError]. */
    @Suppress("UNCHECKED_CAST")
    private fun orderRow(raw: Any?, ymd: String): BrokerOrderRow? {
        val row = raw as? Map<String, Any?> ?: throw KisError("주문 목록 조회 행이 객체가 아닙니다: ${pyRepr(raw.toString())}")
        val reportedDay = row["ord_dt"]?.toString()?.trim()
        if (!reportedDay.isNullOrEmpty() && reportedDay != ymd) return null
        val orderNo = row["odno"]?.toString()
        if (orderNo.isNullOrBlank()) return null
        val originalNo = row["orgn_odno"]?.toString()?.trim()?.takeIf { no -> no.any { it != '0' } }
        val symbol = row["pdno"]?.toString()?.trim()?.takeIf { it.isNotEmpty() } ?: throw KisError("주문 목록 행에 종목(pdno)이 없습니다: odno=$orderNo")
        val side = when (row["sll_buy_dvsn_cd"]?.toString()?.trim()) {
            "01" -> "sell"
            "02" -> "buy"
            else -> throw KisError("주문 목록 행의 매도매수구분을 해석할 수 없습니다: odno=$orderNo ${pyRepr(row["sll_buy_dvsn_cd"].toString())}")
        }
        val avg = listOf(row["avg_prvs"], row["ccld_prvs"]).firstOrNull { it != null && it.toString().isNotEmpty() }
            ?.toString()?.trim()?.toBigDecimalOrNull()?.takeIf { it.signum() > 0 }
        val time = row["ord_tmd"]?.toString()?.trim()?.let { runCatching { LocalTime.parse(it, ORDER_TIME) }.getOrNull() }
        return BrokerOrderRow(orderNo, symbol, side, orderQuantity(row, "ord_qty", orderNo), orderQuantity(row, "tot_ccld_qty", orderNo), avg, time, originalNo)
    }

    /** 수량은 비어 있어도(원본 `or 0` 처럼 0 으로 두지 않고) 읽지 못한 것 — 0 이상의 정수가 아니면 [KisError]. */
    private fun orderQuantity(row: Map<String, Any?>, key: String, orderNo: String): Int =
        runCatching { row[key].toString().trim().toBigDecimal().intValueExact() }.getOrNull()?.takeIf { it >= 0 }
            ?: throw KisError("주문 목록 행의 $key 를 읽을 수 없습니다: odno=$orderNo ${pyRepr(row[key].toString())}")

    companion object {
        private val LIMITERS = ConcurrentHashMap<String, RateLimiter>()
        private val ORD_DVSN = mapOf("limit" to "00", "market" to "01")

        /**
         * 주문 POST 의 HTTP 5xx 라도 명시적 거절(접수 안 됨)로 보는 `msg_cd`(본문 `rt_cd`="1" 일 때만). **주문 POST 에서 실측한 코드만 넣는다** —
         * 조회에서 본 코드나 문서상 의미만으로 넣으면 접수됐을 주문을 거절로 확정할 수 있다. 실측(2026-11-16 모의): IGW00002 "인증 시점의 계좌번호와 요청 계좌번호가 일치하지 않습니다."(HTTP 500).
         */
        val ORDER_REJECTED_ON_5XX = setOf("IGW00002")

        /** 유량제한(EGW00201·EGW00215) 재시도 시도 횟수·백오프 기본 간격(ms)·계좌 단위 호출 최소 간격(ms) — 락 TTL 산식(`RedisKeys`)의 재료. */
        const val RATE_RETRY = 3
        const val RATE_BACKOFF_MS = 600L
        const val PAPER_MIN_INTERVAL_MS = 500L
        const val PROD_MIN_INTERVAL_MS = 120L

        /** 잔고 연속조회 최대 페이지 수. 공식 예제 `inquire_balance.py` 의 `max_depth=10` 과 같은 값(모의 20건·실전 50건/페이지 — 그 예제 docstring). */
        const val BALANCE_MAX_PAGES = 10

        /**
         * 주문 목록 연속조회 최대 페이지 수 — 같은 예제 `inquire_daily_ccld.py` 의 `max_depth=10`. 한 페이지는 실전 100건·모의 15건(그 예제 docstring)이라
         * 모의는 하루 150건을 넘으면 [KisError]. 유량제한 재시도(`RATE_RETRY`)까지 치면 한 조회의 HTTP 호출은 페이지 × 최대 3회.
         */
        const val DAILY_ORDERS_MAX_PAGES = 10
        private val ORDER_TIME = DateTimeFormatter.ofPattern("HHmmss").withResolverStyle(ResolverStyle.STRICT)
    }
}
