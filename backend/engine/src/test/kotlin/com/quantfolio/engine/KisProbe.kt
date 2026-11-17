package com.quantfolio.engine

import com.quantfolio.broker.BrokerHttp
import com.quantfolio.broker.BrokerResponse
import com.quantfolio.broker.BrokerSupport
import com.quantfolio.broker.JdkBrokerHttp
import com.quantfolio.broker.KisClient
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import org.springframework.data.redis.core.script.ScriptExecutor
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.lang.reflect.Proxy
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.concurrent.CountDownLatch
import kotlin.system.exitProcess

/**
 * KIS 모의투자(vts) 실응답 확인 도구 — 테스트가 아니다(JUnit 이 실행하지 않는다). 운영 jar 에 들어가지 않게 test 소스셋에 둔다.
 * 실행: `cd backend && ./gradlew -q --console=plain :engine:kisProbe --args="orders"` (사용법은 인자 없이 실행).
 *
 * 하는 일: 조회 명령(orders·balance·order·quote·notices)은 읽기 전용 — 주문·정정·취소·hashkey 경로는 [ProbeHttp] 가 막는다.
 * 주문 명령(buy·sell·amend·cancel)은 **모의투자 계좌에 실제 주문을 낸다** — 실행당 주문계 POST 최대 1건, 어떤 실패에도 다시 보내지 않는다.
 * 조회·매수·매도는 운영 코드 [KisClient] 를 그대로 호출하고, 그 요청·응답을 [ProbeHttp] 가 기록해 원시 필드를 낸다 — 같은 요청을 다시 만들지 않으므로
 * 쿼리가 운영 코드와 어긋날 수 없다. 정정·취소만 [KisClient] 에 기능이 없어 도구가 직접 만든다.
 * 토큰 캐시는 [MemoryRedis](프로세스 메모리)라 운영 Redis 의 토큰 키를 건드리지 않는다. `KIS_PROBE_TOKEN_FILE` 이 있으면 토큰만 그 파일에 두고 재사용한다(분당 1회 발급 제한).
 */
fun main(args: Array<String>) {
    exitProcess(KisProbe(System.getenv(), JdkBrokerHttp(), ::println).run(args.toList()))
}

/** 모의투자 고정 설정 — 환경변수(KIS_ENV 등)를 읽지 않는다. 주소는 운영 기본값 그대로(WS 는 `ws://…:31000/tryitout` — 원본 wss 기본값의 접속 실패는 migration-notes). */
internal val PROBE_PROPS = QuantfolioProperties(kis = QuantfolioProperties.Kis(env = "vts")).also { check(it.kis.isPaperTrading) }

/**
 * 모의투자 전용 전송. vts 호스트의 허용 경로만(토큰·승인키 POST, 주문체결조회·잔고 GET, 시세 GET 은 TR `FHKST01010100` 만), 그 밖의 tr_id 는 V 접두만.
 * 주문계(hashkey·주문·정정취소 POST)는 [ordersAllowed](주문 명령 실행 중)일 때만, TR 은 정확히 `VTTC0802U`·`VTTC0801U`(주문)·`VTTC0803U`(정정취소).
 * 토큰·승인키·hashkey 는 실행당 1회, 주문계 POST(주문·정정취소 합산)도 실행당 1건 — 두 번째는 보내기 전에 거부한다(재발급·재시도·재전송 없음).
 * 토큰·승인키 밖의 응답은 [exchanges] 에 기록한다.
 */
internal class ProbeHttp(private val delegate: BrokerHttp) : BrokerHttp {
    class Exchange(val path: String, val trId: String?, val response: BrokerResponse)

    val exchanges = mutableListOf<Exchange>()
    var ordersAllowed = false
    private val issued = mutableSetOf<String>()

    override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?): BrokerResponse {
        val base = PROBE_PROPS.kis.baseUrlVts
        require(url.startsWith("$base/")) { "모의투자(vts) 주소가 아닌 요청을 거부했습니다." }
        val path = url.removePrefix(base)
        val allowed = when (path) {
            TOKEN, APPROVAL -> method == "POST"
            ORDERS, BALANCE, QUOTE -> method == "GET"
            HASHKEY, ORDER_CASH, RVSECNCL -> method == "POST" && ordersAllowed
            else -> false
        }
        require(allowed) { "이 명령에서 허용하지 않는 요청을 거부했습니다: $method $path" }
        val trId = headers["tr_id"]
        val trOk = when (path) {
            QUOTE -> trId == QUOTE_TR
            ORDER_CASH -> trId == "VTTC0802U" || trId == "VTTC0801U"
            RVSECNCL -> trId == RVSECNCL_TR
            else -> trId == null || trId.startsWith("V")
        }
        require(trOk) { "허용하지 않는 TR 을 거부했습니다: $path $trId" }
        val once = if (path == ORDER_CASH || path == RVSECNCL) "주문계 POST" else path
        if (method == "POST") require(issued.add(once)) { "$once 는 실행당 1건만 보냅니다(재발급·재시도·재전송 안 함)." }
        val r = delegate.send(method, url, query, headers, json, form)
        if (path != TOKEN && path != APPROVAL) exchanges += Exchange(path, trId, r)
        return r
    }

    companion object {
        const val TOKEN = "/oauth2/tokenP"
        const val APPROVAL = "/oauth2/Approval"
        const val ORDERS = "/uapi/domestic-stock/v1/trading/inquire-daily-ccld"
        const val BALANCE = "/uapi/domestic-stock/v1/trading/inquire-balance"
        const val QUOTE = "/uapi/domestic-stock/v1/quotations/inquire-price"
        const val HASHKEY = "/uapi/hashkey"
        const val ORDER_CASH = "/uapi/domestic-stock/v1/trading/order-cash"
        const val RVSECNCL = "/uapi/domestic-stock/v1/trading/order-rvsecncl"
        const val QUOTE_TR = "FHKST01010100"
        const val RVSECNCL_TR = "VTTC0803U"
    }
}

/** [BrokerSupport.cachedToken] 이 쓰는 연산(get·set·setIfAbsent·락 해제 스크립트)만 메모리로 — 그 밖의 연산은 실패한다. 연결 팩토리가 없어 Redis 에 접속하지 않는다. */
internal class MemoryRedis : StringRedisTemplate() {
    val values = linkedMapOf<String, String>()

    @Suppress("UNCHECKED_CAST")
    private val ops = Proxy.newProxyInstance(ValueOperations::class.java.classLoader, arrayOf(ValueOperations::class.java)) { _, m, a ->
        when (m.name) {
            "get" -> values[a[0] as String]
            "set" -> values.put(a[0] as String, a[1] as String).let { null }
            "setIfAbsent" -> values.putIfAbsent(a[0] as String, a[1] as String) == null
            else -> throw UnsupportedOperationException("MemoryRedis: ${m.name}")
        }
    } as ValueOperations<String, String>

    override fun opsForValue(): ValueOperations<String, String> = ops

    init {
        // 락 해제 스크립트(RELEASE_LOCK_SCRIPT): 값이 내 토큰일 때만 지운다.
        @Suppress("UNCHECKED_CAST")
        setScriptExecutor(
            Proxy.newProxyInstance(ScriptExecutor::class.java.classLoader, arrayOf(ScriptExecutor::class.java)) { _, m, a ->
                check(m.name == "execute" && a.size == 3) { "MemoryRedis: ${m.name}" }
                val key = (a[1] as List<String>).single()
                if (values[key] == (a[2] as Array<Any?>).singleOrNull()) values.remove(key)
                null
            } as ScriptExecutor<String>,
        )
    }
}

internal class KisProbe(private val env: Map<String, String>, transport: BrokerHttp, private val sink: (String) -> Unit, private val clock: Clock = Clock.systemUTC()) {
    internal val http = ProbeHttp(transport)
    private val redis = MemoryRedis()
    private val json = JsonMapper.builder().build()
    private var tokenFromFile = false

    /** 출력 전 지우는 값: 앱키·시크릿·계좌번호(원문·숫자·CANO)·HTS ID·토큰·approval key·AES key/iv. 4자 미만은 다른 출력을 망가뜨려 빼지만 그런 값은 형식 검사에서 걸러진다. */
    private val secrets = mutableListOf<String>()

    private fun say(s: String = "") = sink(secrets.filter { it.length >= 4 }.fold(s) { acc, x -> acc.replace(x, "***") })

    private fun secret(v: String?) = v?.takeIf { it.isNotBlank() }?.let { secrets += it }

    fun run(args: List<String>): Int {
        val cmd = args.firstOrNull()
        http.ordersAllowed = cmd in ORDER_COMMANDS
        val appKey = env["KIS_PROBE_APP_KEY"].orEmpty()
        val appSecret = env["KIS_PROBE_APP_SECRET"].orEmpty()
        val account = env["KIS_PROBE_ACCOUNT_NO"].orEmpty()
        val hts = env["KIS_PROBE_HTS_ID"].orEmpty()
        listOf(appKey, appSecret, account, hts).forEach(::secret)
        val digits = account.replace("-", "").trim()
        secret(digits)
        secret(digits.take(8))
        val missing = buildList {
            if (appKey.isBlank()) add("KIS_PROBE_APP_KEY")
            if (appSecret.isBlank()) add("KIS_PROBE_APP_SECRET")
            if (cmd == "notices") { if (hts.isBlank()) add("KIS_PROBE_HTS_ID") } else if (account.isBlank()) add("KIS_PROBE_ACCOUNT_NO")
        }
        val parsed = parseArgs(args) ?: return usage("인자가 올바르지 않습니다.")
        if (missing.isNotEmpty()) return usage("환경변수가 없습니다: ${missing.joinToString()}")
        // KisClient 의 계좌 형식 오류 메시지는 계좌번호 원문을 담는다 — 그 전에 걸러 값이 출력되지 않게 한다.
        if (cmd != "notices" && (digits.length != 10 || digits.any { it !in '0'..'9' })) return usage("KIS_PROBE_ACCOUNT_NO 형식 오류(값은 출력하지 않음) — 'CANO-상품코드' 10자리 숫자")
        if (cmd == "notices" && hts.length < 4) return usage("KIS_PROBE_HTS_ID 가 너무 짧습니다(값은 출력하지 않음).")

        say("# KIS 모의투자 확인 도구 — 실행 시각 ${MarketClock.nowKst(clock).format(STAMP)} KST, 주소 ${PROBE_PROPS.kis.baseUrlVts}")
        if (cmd != "notices") say("# 계좌 ********-${digits.takeLast(2)}")
        return try {
            when (cmd) {
                "notices" -> notices(appKey, appSecret, hts, parsed.minutes)
                else -> {
                    val client = KisClient(appKey, appSecret, account, BrokerSupport(http, redis, PROBE_PROPS, clock, json))
                    val tokenFile = env["KIS_PROBE_TOKEN_FILE"]?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
                    loadToken(tokenFile, appKey)
                    if (!issueToken(client)) return 1
                    if (tokenFile != null && !tokenFromFile) saveToken(tokenFile, appKey)
                    when (cmd) {
                        "orders" -> orders(client, parsed.day)
                        "balance" -> balance(client)
                        "order" -> order(client, parsed.orderNo!!, parsed.symbol!!, parsed.day)
                        "quote" -> quote(client, parsed.symbol!!)
                        "ccld" -> ccld(client, appKey, appSecret, digits, parsed.ccld!!, parsed.day)
                        "buy", "sell" -> placeOrder(client, cmd, parsed.symbol!!, parsed.qty, parsed.price)
                        "xorder" -> xorder(client, appKey, appSecret, digits, parsed.side!!, parsed.symbol!!, parsed.qty, parsed.price!!, parsed.ordDvsn!!, parsed.excg)
                        else -> modify(client, appKey, appSecret, digits, cmd == "cancel", parsed.orderNo!!, parsed.orgNo!!, parsed.qty, parsed.price!!)
                    }
                }
            }
            0
        } catch (e: Exception) {
            say("오류로 중단: ${e.javaClass.simpleName}: ${e.message}")
            1
        } finally {
            if (tokenFromFile && http.exchanges.any(::tokenError)) say("토큰 관련 오류 응답이 있습니다 — 토큰 파일의 토큰이 만료·무효일 수 있습니다. 토큰 파일을 지우고 다시 실행하세요(자동 재발급하지 않음).")
        }
    }

    private class Args(
        val day: LocalDate, val minutes: Long = 10, val orderNo: String? = null, val symbol: String? = null,
        val qty: Int = 0, val price: Int? = null, val orgNo: String? = null, val ccld: List<String>? = null,
        val side: String? = null, val ordDvsn: String? = null, val excg: String? = null,
    )

    private fun parseArgs(args: List<String>): Args? {
        val today = MarketClock.todayKst(clock)
        fun day(s: String?) = if (s == null) today else runCatching { LocalDate.parse(s, YMD) }.getOrNull()
        fun alnum(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' || it in 'A'..'Z' || it in 'a'..'z' }
        fun symbol(s: String) = s.length == 6 && alnum(s)
        fun qty(s: String) = s.toIntOrNull()?.takeIf { it in 1..MAX_QTY }
        fun price(s: String) = s.toIntOrNull()?.takeIf { it >= 0 }
        return when (args.firstOrNull()) {
            "orders" -> if (args.size <= 2) day(args.getOrNull(1))?.let { Args(it) } else null
            "balance" -> if (args.size == 1) Args(today) else null
            "order" -> if (args.size in 3..4 && args[1].isNotBlank() && args[2].isNotBlank()) day(args.getOrNull(3))?.let { Args(it, orderNo = args[1], symbol = args[2]) } else null
            "notices" -> if (args.size <= 2) (args.getOrNull(1)?.toLongOrNull() ?: if (args.size == 1) 10 else null)?.takeIf { it in 1..480 }?.let { Args(today, minutes = it) } else null
            "ccld" -> if (args.size in 6..7 && args[1] in CCLD_TRS) day(args.getOrNull(6))?.let { Args(it, ccld = args.subList(1, 6)) } else null
            "quote" -> if (args.size == 2 && symbol(args[1])) Args(today, symbol = args[1]) else null
            "buy", "sell" -> if (args.size in 3..4 && symbol(args[1])) {
                val p = args.getOrNull(3)?.let { price(it) ?: return null }
                qty(args[2])?.let { Args(today, symbol = args[1], qty = it, price = p) }
            } else null
            "xorder" -> if (args.size in 6..7 && args[1] in setOf("buy", "sell") && symbol(args[2]) && args[5].length == 2 && args[5].all { it in '0'..'9' } && args.getOrNull(6).let { it == null || it in EXCGS }) {
                qty(args[3])?.let { q -> price(args[4])?.let { Args(today, symbol = args[2], qty = q, price = it, side = args[1], ordDvsn = args[5], excg = args.getOrNull(6)) } }
            } else null
            "amend" -> if (args.size == 5 && alnum(args[1]) && alnum(args[2])) qty(args[3])?.let { q -> price(args[4])?.let { Args(today, orderNo = args[1], orgNo = args[2], qty = q, price = it) } } else null
            "cancel" -> if (args.size == 4 && alnum(args[1]) && alnum(args[2])) qty(args[3])?.let { Args(today, orderNo = args[1], orgNo = args[2], qty = it, price = 0) } else null
            else -> null
        }
    }

    private fun usage(reason: String): Int {
        say("오류: $reason")
        say(
            """
            |사용법: cd backend && ./gradlew -q --console=plain :engine:kisProbe --args="<명령>"
            |  orders [YYYYMMDD]                      일별주문체결조회(VTTC0081R) 원시 행·파서 결과·판정 요약 (기본 오늘 KST)
            |  balance                                잔고조회(VTTC8434R) 페이지·보유 종목·positionsNormalized()
            |  order <주문번호> <종목코드> [YYYYMMDD]  단건 체결조회(VTTC0081R + ODNO, KisClient.getOrderExecution) 원시 행·Fill, 같은 날 주문 목록(VTTC0081R)의 같은 주문과 비교
            |  quote <종목코드>                       현재가(FHKST01010100) Quote·상한가·하한가·기준가
            |  ccld <TR> <odno|-> <pdno|-> <excg|-> <brno|-> [YYYYMMDD]  조건 하나씩 바꿔 inquire-daily-ccld 한 페이지(TR 은 VTTC8001R·VTTC0081R, - 는 빈 값, excg - 는 키 생략)
            |  notices [분]                           체결통보(H0STCNI9) 수신·출력 (기본 10분, 1~480)
            |  -- 아래는 모의투자 계좌에 실제 주문을 낸다(실행당 1건, 실패해도 다시 보내지 않음) --
            |  buy <종목코드> <수량> [지정가]          매수(VTTC0802U) — 지정가 없으면 시장가. 수량 1~$MAX_QTY
            |  sell <종목코드> <수량> [지정가]         매도(VTTC0801U) — 지정가 없으면 시장가
            |  xorder <buy|sell> <종목코드> <수량> <가격> <ORD_DVSN 2자리> [KRX|NXT|SOR]  ORD_DVSN 원시 코드·거래소(EXCG_ID_DVSN_CD, 생략 시 키 없음)로 주문 1건.
            |        코드 의미는 KIS 문서 기준(00 지정가·01 시장가·05 장전 시간외·06 장후 시간외·07 시간외 단일가) — 모의투자가 받는지는 미측정. 가격 0 가능
            |  amend <주문번호> <주문조직번호> <수량> <가격>  정정(VTTC0803U, 지정가)
            |  cancel <주문번호> <주문조직번호> <수량>        취소(VTTC0803U)
            |환경변수: KIS_PROBE_APP_KEY, KIS_PROBE_APP_SECRET, KIS_PROBE_ACCOUNT_NO(notices 밖 전부), KIS_PROBE_HTS_ID(notices)
            |  KIS_PROBE_TOKEN_FILE(선택): 토큰을 이 파일에 두고 재사용(발급 분당 1회 제한). 인증 오류면 파일을 지우고 다시 실행.
            |모의투자(vts) 전용 · 조회 명령은 읽기 전용, buy·sell·xorder·amend·cancel 은 주문을 낸다.
            """.trimMargin(),
        )
        return 2
    }

    /** 토큰은 한 번만 발급한다 — 실패하면 재시도하지 않고 그대로 보여 준다(이후 조회도 하지 않는다). */
    private fun issueToken(client: KisClient): Boolean = try {
        client.verifyConnection()
        redis.values.values.forEach(::secret)
        true
    } catch (e: Exception) {
        say("토큰 발급 실패(재시도하지 않음): ${e.javaClass.simpleName}: ${e.message}")
        false
    }

    // ---------- 토큰 파일 캐시: 토큰 한 줄만(락 키 없음). 내용은 출력하지 않는다. ----------

    private fun loadToken(file: Path?, appKey: String) {
        val token = file?.let { runCatching { Files.readString(it).trim() }.getOrNull() }?.takeIf { it.isNotEmpty() } ?: return
        secret(token)
        redis.values[RedisKeys.kisToken(PROBE_PROPS.kis.env, appKey)] = token
        tokenFromFile = true
        say("# 토큰 파일의 토큰을 씀(발급 안 함)")
    }

    private fun saveToken(file: Path, appKey: String) {
        val token = redis.values[RedisKeys.kisToken(PROBE_PROPS.kis.env, appKey)] ?: return
        runCatching { Files.writeString(file, token) }.fold({ say("# 새로 발급한 토큰을 토큰 파일에 저장") }, { say("# 토큰 파일 저장 실패: ${it.javaClass.simpleName}") })
    }

    /** 토큰을 언급하는 실패 응답(HTTP 200 아님 또는 rt_cd 가 "0" 아님) — 캐시된 토큰의 만료·무효로 볼 후보. */
    private fun tokenError(ex: ProbeHttp.Exchange): Boolean {
        val r = ex.response
        val rtCd = runCatching { json.readValue<Map<String, Any?>>(r.body)["rt_cd"] }.getOrNull()
        return "token" in r.body.lowercase() && (r.status != 200 || rtCd != "0")
    }

    // ---------- quote / buy·sell / amend·cancel ----------

    /** [from] 이후 기록된 응답마다 머리 줄과 output 객체의 키 목록·[fields] 값(따옴표+길이)을 낸다 — 원문 덤프는 하지 않는다. */
    @Suppress("UNCHECKED_CAST")
    private fun printOutputs(from: Int, fields: List<String>) {
        http.exchanges.drop(from).forEachIndexed { i, ex ->
            if (ex.path == ProbeHttp.HASHKEY) return@forEachIndexed say("  hashkey POST HTTP ${ex.response.status}")
            val data = page(i, ex) ?: return@forEachIndexed
            val out = data["output"] as? Map<String, Any?> ?: return@forEachIndexed say("    output 객체 없음(${data["output"]?.javaClass?.simpleName ?: "키 없음"})")
            say("    output 키 목록(${out.size}개): ${out.keys.joinToString(", ")}")
            say("    " + fields.joinToString(" ") { k -> "$k=${field(out, k)}${out[k]?.let { "(${it.toString().length})" } ?: ""}" })
        }
    }

    private fun quote(client: KisClient, symbol: String) {
        say()
        say("== quote $symbol: 현재가 — KisClient.getQuote 와 같은 요청 ==")
        val from = http.exchanges.size
        val result = runCatching { client.getQuote(symbol) }
        printOutputs(from, listOf("stck_prpr", "stck_mxpr", "stck_llam", "stck_sdpr", "temp_stop_yn", "iscd_stat_cls_code", "vi_cls_code"))
        say()
        say("-- 우리 파서 결과: getQuote --")
        result.fold({ say("$it") }, { e -> say("오류: ${e.javaClass.simpleName}: ${e.message}") })
    }

    private fun placeOrder(client: KisClient, side: String, symbol: String, qty: Int, price: Int?) {
        val type = if (price == null) "market" else "limit"
        say()
        say("== $side $symbol ${qty}주 ${price?.let { "지정가 $it" } ?: "시장가"}: 모의투자 계좌에 실제 주문 — KisClient.placeOrder 그대로(실행당 1건, 재전송 안 함) ==")
        val from = http.exchanges.size
        val result = runCatching { client.placeOrder(symbol, side, qty, price ?: 0, type) }
        printOutputs(from, ORDER_FIELDS)
        say()
        say("-- 우리 파서 결과: placeOrder --")
        result.fold(
            { say("OrderResult(orderId=${q(it.orderId)}, orderOrgNo=${q(it.orderOrgNo)})") },
            { e -> say("예외 ${e.javaClass.simpleName}: ${e.message}") },
        )
    }

    /** 유형·거래소 지정 주문 — [KisClient] 가 ORD_DVSN 원시 코드·거래소를 못 넘겨서 직접 만든다. 본문·헤더·hashkey 는 KisClient.placeOrder 와 같고 ORD_DVSN·ORD_UNPR·선택 EXCG_ID_DVSN_CD 만 다르다. */
    private fun xorder(client: KisClient, appKey: String, appSecret: String, digits: String, side: String, symbol: String, qty: Int, price: Int, ordDvsn: String, excg: String?) {
        say()
        say("== xorder $side $symbol ${qty}주 가격 $price ORD_DVSN=${q(ordDvsn)} EXCG_ID_DVSN_CD=${excg?.let(::q) ?: "(키 생략)"}: 모의투자 계좌에 실제 주문(실행당 1건, 재전송 안 함) ==")
        val body = linkedMapOf(
            "CANO" to digits.take(8), "ACNT_PRDT_CD" to digits.substring(8, 10), "PDNO" to symbol, "ORD_DVSN" to ordDvsn, "ORD_QTY" to qty.toString(), "ORD_UNPR" to price.toString(),
        ).apply { if (excg != null) put("EXCG_ID_DVSN_CD", excg) }
        val bodyJson = json.writeValueAsString(body)
        val base = PROBE_PROPS.kis.baseUrlVts
        val from = http.exchanges.size
        runCatching {
            Thread.sleep(KisClient.PAPER_MIN_INTERVAL_MS)
            val h = http.send("POST", "$base${ProbeHttp.HASHKEY}", headers = mapOf("content-type" to JSON_TYPE, "appkey" to appKey, "appsecret" to appSecret), json = bodyJson)
            check(h.status == 200) { "hashkey 발급 실패: HTTP ${h.status} — 주문을 보내지 않음" }
            val hash = json.readValue<Map<String, Any?>>(h.body)["HASH"]?.toString() ?: error("hashkey 응답에 HASH 없음 — 주문을 보내지 않음")
            val headers = linkedMapOf(
                "content-type" to JSON_TYPE, "authorization" to "Bearer ${client.accessToken()}", "appkey" to appKey, "appsecret" to appSecret,
                "tr_id" to if (side == "buy") "VTTC0802U" else "VTTC0801U", "custtype" to "P", "hashkey" to hash,
            )
            Thread.sleep(KisClient.PAPER_MIN_INTERVAL_MS)
            http.send("POST", "$base${ProbeHttp.ORDER_CASH}", headers = headers, json = bodyJson)
        }.onFailure { e -> say("예외(재전송 안 함) ${e.javaClass.simpleName}: ${e.message}") }
        printOutputs(from, ORDER_FIELDS)
    }

    /** 정정·취소 — [KisClient] 에 없는 기능이라 직접 만든다. 헤더·hashkey·계좌 분해는 [KisClient] 의 placeOrder 와 같은 방식. */
    private fun modify(client: KisClient, appKey: String, appSecret: String, digits: String, cancel: Boolean, orderNo: String, orgNo: String, qty: Int, price: Int) {
        say()
        say("== ${if (cancel) "cancel" else "amend"} 원주문 $orderNo 조직 $orgNo ${qty}주${if (cancel) "" else " 가격 $price"}: 모의투자 계좌에 실제 정정·취소(실행당 1건, 재전송 안 함) ==")
        val body = linkedMapOf(
            "CANO" to digits.take(8), "ACNT_PRDT_CD" to digits.substring(8, 10), "KRX_FWDG_ORD_ORGNO" to orgNo, "ORGN_ODNO" to orderNo,
            "ORD_DVSN" to "00", "RVSE_CNCL_DVSN_CD" to if (cancel) "02" else "01", "ORD_QTY" to qty.toString(), "ORD_UNPR" to price.toString(),
            "QTY_ALL_ORD_YN" to "N",
        )
        val bodyJson = json.writeValueAsString(body)
        val base = PROBE_PROPS.kis.baseUrlVts
        val from = http.exchanges.size
        runCatching {
            Thread.sleep(KisClient.PAPER_MIN_INTERVAL_MS) // KisClient 의 호출 간격(throttle)과 같게 — 유량제한으로 단 한 번의 주문을 날리지 않게
            val h = http.send("POST", "$base${ProbeHttp.HASHKEY}", headers = mapOf("content-type" to JSON_TYPE, "appkey" to appKey, "appsecret" to appSecret), json = bodyJson)
            check(h.status == 200) { "hashkey 발급 실패: HTTP ${h.status} — 정정·취소를 보내지 않음" }
            val hash = json.readValue<Map<String, Any?>>(h.body)["HASH"]?.toString() ?: error("hashkey 응답에 HASH 없음 — 정정·취소를 보내지 않음")
            val headers = linkedMapOf(
                "content-type" to JSON_TYPE, "authorization" to "Bearer ${client.accessToken()}", "appkey" to appKey, "appsecret" to appSecret,
                "tr_id" to ProbeHttp.RVSECNCL_TR, "custtype" to "P", "hashkey" to hash,
            )
            Thread.sleep(KisClient.PAPER_MIN_INTERVAL_MS)
            http.send("POST", "$base${ProbeHttp.RVSECNCL}", headers = headers, json = bodyJson)
        }.onFailure { e -> say("예외(재전송 안 함) ${e.javaClass.simpleName}: ${e.message}") }
        printOutputs(from, ORDER_FIELDS)
    }

    // ---------- ccld ----------

    /** 읽기 전용 — dailyOrders 와 같은 쿼리에서 TR·ODNO·PDNO·EXCG_ID_DVSN_CD·ORD_GNO_BRNO 만 인자로 바꿔 한 페이지만 조회한다(연속조회 없음). */
    @Suppress("UNCHECKED_CAST")
    private fun ccld(client: KisClient, appKey: String, appSecret: String, digits: String, a: List<String>, day: LocalDate) {
        val (tr, odno, pdno, excg, brno) = a.map { if (it == "-") null else it }.let { v -> listOf(a[0]) + v.drop(1) }
        say()
        say("== ccld: TR=$tr ODNO=${q(odno.orEmpty())} PDNO=${q(pdno.orEmpty())} EXCG_ID_DVSN_CD=${excg?.let(::q) ?: "(키 생략)"} ORD_GNO_BRNO=${q(brno.orEmpty())} 날짜=${day.format(YMD)} ==")
        val ymd = day.format(YMD)
        val query = linkedMapOf(
            "CANO" to digits.take(8), "ACNT_PRDT_CD" to digits.substring(8, 10), "INQR_STRT_DT" to ymd, "INQR_END_DT" to ymd, "SLL_BUY_DVSN_CD" to "00",
            "INQR_DVSN" to "00", "PDNO" to pdno.orEmpty(), "CCLD_DVSN" to "00", "ORD_GNO_BRNO" to brno.orEmpty(), "ODNO" to odno.orEmpty(),
            "INQR_DVSN_3" to "00", "INQR_DVSN_1" to "",
        ).apply { if (excg != null) put("EXCG_ID_DVSN_CD", excg) }
        val headers = linkedMapOf(
            "content-type" to JSON_TYPE, "authorization" to "Bearer ${client.accessToken()}", "appkey" to appKey, "appsecret" to appSecret, "tr_id" to tr!!, "custtype" to "P",
        )
        val from = http.exchanges.size
        runCatching {
            Thread.sleep(KisClient.PAPER_MIN_INTERVAL_MS)
            http.send("GET", "${PROBE_PROPS.kis.baseUrlVts}${ProbeHttp.ORDERS}", query, headers)
        }.onFailure { e -> say("예외 ${e.javaClass.simpleName}: ${e.message}") }
        http.exchanges.drop(from).forEachIndexed { i, ex ->
            val data = page(i, ex) ?: return@forEachIndexed
            val rows = (data["output1"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
            say("    output1 행 수: ${rows?.size ?: "output1 목록 없음(${data["output1"]?.javaClass?.simpleName ?: "키 없음"})"}")
            rows.orEmpty().forEach { r -> say("    행: " + CCLD_FIELDS.joinToString(" ") { k -> "$k=${field(r, k)}" }) }
        }
    }

    // ---------- orders ----------

    private fun orders(client: KisClient, day: LocalDate) {
        say()
        say("== orders ${day.format(YMD)}: 모의 일별주문체결조회가 행을 주는지·연속조회 tr_cont·odno/orgn_odno 표기 확인(가정 3·4) — KisClient.dailyOrders 와 같은 요청 ==")
        val from = http.exchanges.size
        val result = runCatching { client.dailyOrders(day) }
        val rows = printOrderPages(http.exchanges.drop(from))
        say()
        say("-- 우리 파서 결과: KisClient.dailyOrders(${day.format(YMD)}) --")
        result.fold(
            { list -> if (list == null) say("null(미지원)") else { say("행 ${list.size}개"); list.forEach { say("  $it") } } },
            { e -> say("오류: ${e.javaClass.simpleName}: ${e.message}") },
        )
        say()
        say("-- 판정 요약 --")
        val lastTrCont = http.exchanges.drop(from).lastOrNull()?.response?.header("tr_cont")
        say("응답 페이지 ${http.exchanges.size - from}개, 원시 행 ${rows.size}개${if (rows.isEmpty()) " — 행 0(원본 주석 '늘 빈 응답'과 같음)" else ""}")
        say("마지막 tr_cont: ${trContText(lastTrCont)}${if (lastTrCont?.trim() in setOf("D", "E")) " (D/E — 완결)" else " (D/E 아님 — dailyOrders 는 조회 실패로 본다)"}")
        val odnos = rows.mapNotNull { it["odno"]?.toString() }
        val withOrig = rows.filter { r -> r["orgn_odno"]?.toString()?.trim()?.any { it != '0' } == true }
        say("orgn_odno 가 있는 행(공백·전부 0 제외): ${withOrig.size}개")
        withOrig.forEach { r ->
            val orig = r["orgn_odno"].toString()
            val others = rows.filter { it !== r }.mapNotNull { it["odno"]?.toString() }
            fun yn(b: Boolean) = if (b) "예" else "아니오"
            say(
                "  odno=${q(r["odno"])} orgn_odno=${q(orig)} → 다른 행 odno 와 원문 일치: ${yn(orig in others)}, trim 일치: ${yn(orig.trim() in others.map { it.trim() })}, " +
                    "앞 0 제거 일치: ${yn(orig.trim().trimStart('0') in others.map { it.trim().trimStart('0') })}",
            )
        }
        val lengths = odnos.map { it.length }.toSortedSet()
        if (odnos.isNotEmpty()) say("odno 길이 분포: $lengths")
    }

    /** 페이지별 헤더·결과 코드·행 수, 첫 행의 키 목록, 행마다 확인할 필드를 내고 모든 행을 돌려준다. */
    @Suppress("UNCHECKED_CAST")
    private fun printOrderPages(pages: List<ProbeHttp.Exchange>): List<Map<String, Any?>> {
        val all = mutableListOf<Map<String, Any?>>()
        var keysShown = false
        pages.forEachIndexed { i, ex ->
            val data = page(i, ex) ?: return@forEachIndexed
            val rows = (data["output1"] as? List<*>)?.filterIsInstance<Map<String, Any?>>()
            say("    output1 행 수: ${rows?.size ?: "output1 목록 없음(${data["output1"]?.javaClass?.simpleName ?: "키 없음"})"}")
            rows.orEmpty().forEach { r ->
                if (!keysShown) {
                    say("    첫 행의 키 목록(${r.size}개): ${r.keys.joinToString(", ")}")
                    keysShown = true
                }
                val extra = r.keys.filter { k -> listOf("rvse", "cncl", "dvsn").any { it in k } && k !in ROW_FIELDS }
                say(
                    "    행: odno=${q(r["odno"])}(${r["odno"]?.toString()?.length}) orgn_odno=${q(r["orgn_odno"])}(${r["orgn_odno"]?.toString()?.length}) " +
                        (ROW_FIELDS.drop(2) + extra).joinToString(" ") { k -> "$k=${field(r, k)}" },
                )
            }
            all += rows.orEmpty()
        }
        return all
    }

    /** 한 페이지의 머리 줄(HTTP·tr_cont·rt_cd·msg_cd·msg1). 본문이 JSON 객체가 아니면 null. */
    private fun page(i: Int, ex: ProbeHttp.Exchange): Map<String, Any?>? {
        val r = ex.response
        val data = runCatching { json.readValue<Map<String, Any?>>(r.body) }.getOrNull()
        say(
            "  페이지 ${i + 1} [${ex.trId}] HTTP ${r.status}, 응답 헤더 tr_cont=${trContText(r.header("tr_cont"))}" +
                (data?.let { ", rt_cd=${q(it["rt_cd"])} msg_cd=${q(it["msg_cd"])} msg1=${q(it["msg1"])}" } ?: ", 본문 JSON 아님(${r.body.length}자)"),
        )
        return data
    }

    // ---------- balance ----------

    @Suppress("UNCHECKED_CAST")
    private fun balance(client: KisClient) {
        say()
        say("== balance: 잔고조회 연속조회 tr_cont·페이지 수, 체결 직후 hldg_qty 반영 여부(주문 전후 두 번 실행해 비교, 가정 5) — KisClient.getBalance 와 같은 요청 ==")
        val from = http.exchanges.size
        val result = runCatching { client.getBalance() }
        val pages = http.exchanges.drop(from)
        say("페이지 ${pages.size}개")
        pages.forEachIndexed { i, ex ->
            val data = page(i, ex) ?: return@forEachIndexed
            val rows = (data["output1"] as? List<*>)?.filterIsInstance<Map<String, Any?>>().orEmpty()
            say("    output1 행 수: ${rows.size}")
            rows.forEach { say("    보유: pdno=${field(it, "pdno")} hldg_qty=${field(it, "hldg_qty")} pchs_avg_pric=${field(it, "pchs_avg_pric")}") }
        }
        say()
        say("-- 우리 파서 결과: getBalance().positionsNormalized() --")
        result.fold(
            { b -> b.positionsNormalized().also { say("종목 ${it.size}개") }.forEach { say("  $it") } },
            { e -> say("오류: ${e.javaClass.simpleName}: ${e.message}") },
        )
    }

    // ---------- order ----------

    @Suppress("UNCHECKED_CAST")
    private fun order(client: KisClient, orderNo: String, symbol: String, day: LocalDate) {
        say()
        say("== order $orderNo $symbol ${day.format(YMD)}: 단건 체결조회와 주문 목록의 같은 주문 총 체결 비교(가정 6) — KisClient.getOrderExecution 과 같은 요청 ==")
        val from = http.exchanges.size
        val fill = runCatching { client.getOrderExecution(orderNo, symbol, day) }
        val rows = printOrderPages(http.exchanges.drop(from))
        val parserRow = rows.firstOrNull { it["odno"] == orderNo && (it["ord_dt"]?.toString()?.trim().isNullOrEmpty() || it["ord_dt"].toString().trim() == day.format(YMD)) }
        say("파서가 고르는 행(odno 원문 일치·주문일): ${parserRow?.let { "odno=${q(it["odno"])} ccld_prvs=${field(it, "ccld_prvs")}" } ?: "없음"}")
        say()
        say("-- 우리 파서 결과: getOrderExecution --")
        fill.fold(
            { f -> say("Fill(filledQty=${f.filledQty}, avgPrice=${f.avgPrice}, fullyFilled=${f.fullyFilled}, terminal=${f.terminal})") },
            { e -> say("오류: ${e.javaClass.simpleName}: ${e.message}") },
        )
        say()
        say("-- 비교: 같은 날 주문 목록(dailyOrders) --")
        runCatching { client.dailyOrders(day) }.fold(
            { list ->
                val same = list.orEmpty().filter { it.orderNo == orderNo }
                val modified = list.orEmpty().filter { it.originalOrderNo == orderNo }
                say("목록에서 orderNo 원문 일치 행 ${same.size}개: ${same.joinToString { "filledQty=${it.filledQty} avgPrice=${it.avgPrice}" }}")
                say("목록에서 originalOrderNo == $orderNo 인 정정·취소 행 ${modified.size}개: ${modified.joinToString { "orderNo=${q(it.orderNo)} orderQty=${it.orderQty} filledQty=${it.filledQty}" }}")
                val single = fill.getOrNull()?.filledQty
                if (same.size == 1 && single != null) say("단건 filledQty=$single, 목록 filledQty=${same[0].filledQty} → ${if (single == same[0].filledQty) "일치" else "불일치"}")
            },
            { e -> say("목록 조회 오류: ${e.javaClass.simpleName}: ${e.message}") },
        )
    }

    // ---------- notices ----------

    private fun notices(appKey: String, appSecret: String, hts: String, minutes: Long) {
        say()
        say("== notices ${minutes}분: 체결통보 H0STCNI9 필드 순서(13=CNTG_YN·12=RFUS_YN·9=CNTG_QTY·16=ODER_QTY)와 분할 체결 시 CNTG_QTY 누적/건별 확인(가정 1·2) ==")
        val approval = KisFrames.approvalKey(http, PROBE_PROPS.kis.baseUrlVts, appKey, appSecret)
        secret(approval)
        val watch = NoticeWatch(clock, ::say, ::secret)
        val socket = KisSocket(PROBE_PROPS.kis.wsUrlVts)
        val stop = CountDownLatch(1)
        val timer = Thread.ofVirtual().start {
            try { Thread.sleep(Duration.ofMinutes(minutes)) } catch (_: InterruptedException) { }
            stop.countDown()
        }
        try {
            socket.send(KisFrames.subscribe(approval, NoticeWatch.TR_ID, hts))
            say("구독 요청 보냄(tr_id=${NoticeWatch.TR_ID}, ${PROBE_PROPS.kis.wsUrlVts}) — ${minutes}분 동안 수신. 이제 HTS·앱에서 주문하세요.")
            while (true) watch.onFrame(socket.next(stop) ?: break, socket::pong)
        } finally {
            socket.close()
            timer.interrupt()
            watch.summary()
        }
    }

    private fun field(r: Map<String, Any?>, k: String) = if (k in r) q(r[k]) else "(키 없음)"

    private companion object {
        val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuuMMdd").withResolverStyle(ResolverStyle.STRICT)
        val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss")
        val ORDER_COMMANDS = setOf("buy", "sell", "xorder", "amend", "cancel")
        val CCLD_TRS = setOf("VTTC8001R", "VTTC0081R")
        val CCLD_FIELDS = listOf("odno", "orgn_odno", "ord_gno_brno", "pdno", "ord_qty", "tot_ccld_qty", "avg_prvs", "ord_tmd")
        const val MAX_QTY = 1000
        val EXCGS = setOf("KRX", "NXT", "SOR")
        const val JSON_TYPE = "application/json; charset=utf-8"
        val ORDER_FIELDS = listOf("ODNO", "KRX_FWDG_ORD_ORGNO", "ORD_TMD")
        val ROW_FIELDS = listOf("odno", "orgn_odno", "ord_dt", "ord_tmd", "sll_buy_dvsn_cd", "pdno", "ord_qty", "tot_ccld_qty", "avg_prvs", "cncl_yn", "rmn_qty", "rjct_qty")
    }
}

private fun q(v: Any?) = if (v == null) "null" else "\"$v\""

private fun trContText(v: String?) = if (v == null) "헤더 없음" else q(v)

/**
 * 체결통보 수신 루프의 프레임 처리 — 제어 메시지(구독 응답·PINGPONG)와 key/iv 갱신은 [KisRealtime] 의 runNotices 와 같은 규칙.
 *
 * 출력에서 가리는 필드(인덱스는 KIS 공식 샘플의 H0STCNI9 순서 — 실응답으로 확인하려는 바로 그 가정이다):
 * - 0 CUST_ID(고객ID — HTS ID 와 같은 값일 수 있다), 1 ACNT_NO(계좌번호), 17 ACNT_NAME(계좌명 = 예금주 이름).
 *   엔진 로그(`체결통보 수신 …`)가 빼는 자리와 같다.
 * - 24(CNTG_ISNM40 종목명)가 아닌 자리에 한글이 있으면 가린다 — 순서가 샘플과 달라 이름이 다른 자리로 밀려 와도 내보내지 않으려고.
 * - 어느 자리든 HTS ID·계좌번호와 같은 문자열은 출력 단계([KisProbe] 의 비밀 목록)에서 `***` 로 지운다.
 */
internal class NoticeWatch(private val clock: Clock, private val say: (String) -> Unit, private val secret: (String) -> Unit) {
    private var key: String? = null
    private var iv: String? = null
    private var received = 0
    private var pings = 0
    private val byCntgYn = sortedMapOf<String, Int>()

    @Suppress("UNCHECKED_CAST")
    fun onFrame(raw: String, pong: (String) -> Unit) {
        val at = MarketClock.nowKst(clock).format(TIME)
        if (raw.startsWith("{")) {
            val msg = KisFrames.control(raw) ?: return say("[$at] 해석 못 한 제어 메시지(${raw.length}자)")
            val header = msg["header"] as? Map<String, Any?>
            if (header?.get("tr_id") == "PINGPONG") {
                pong(raw)
                pings++
                return
            }
            val body = msg["body"] as? Map<String, Any?>
            val out = (body?.get("output") as? Map<String, Any?>).orEmpty()
            val hasKeyIv = !out["key"]?.toString().isNullOrEmpty() && !out["iv"]?.toString().isNullOrEmpty()
            if (hasKeyIv) {
                key = out["key"].toString().also(secret)
                iv = out["iv"].toString().also(secret)
            }
            say("[$at] 제어 메시지 tr_id=${q(header?.get("tr_id"))} rt_cd=${q(body?.get("rt_cd"))} msg_cd=${q(body?.get("msg_cd"))} msg1=${q(body?.get("msg1"))} key/iv 수신=${if (hasKeyIv) "예" else "아니오"}")
            return
        }
        val parts = raw.split("|")
        if (parts.size < 4 || parts[1] != TR_ID) return say("[$at] 다른 프레임 무시(tr_id=${parts.getOrNull(1)})")
        val k = key
        val v = iv
        if (k == null || v == null) return say("[$at] key/iv 미수신 — 데이터 프레임 무시")
        val plain = try {
            KisFrames.aesDecrypt(k, v, parts[3])
        } catch (e: Exception) {
            return say("[$at] 복호화 실패: ${e.javaClass.simpleName}")
        }
        received++
        val f = plain.split("^")
        byCntgYn.merge(f.getOrNull(13)?.let(::q) ?: "(13번 필드 없음)", 1, Int::plus)
        say("[$at] 체결통보 #$received 암호화=${parts[0]} 건수=${parts[2]} 필드 ${f.size}개")
        say("  " + maskedFields(f))
        say("  parseFillNotice → ${KisFrames.parseFillNotice(plain) ?: "null(체결 아님으로 판정)"}")
    }

    fun summary() {
        say("")
        say("-- 요약: 받은 체결통보 ${received}건, PINGPONG ${pings}회, CNTG_YN(13) 값별: ${byCntgYn.entries.joinToString { "${it.key}=${it.value}건" }.ifEmpty { "없음" }}")
    }

    companion object {
        const val TR_ID = "H0STCNI9"
        val MASKED = setOf(0, 1, 17)
        private val TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")

        fun maskedFields(f: List<String>) = f.withIndex().joinToString(" ") { (i, v) ->
            "$i=" + if (i in MASKED || (i != 24 && v.any { it in '가'..'힣' })) "***" else q(v)
        }
    }
}
