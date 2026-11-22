package com.quantfolio.broker

import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime

/** 증권사 API 호출 실패(공통). 호출부는 이 타입으로 잡는다 — 네트워크 단절(IOException)은 별개로 전파된다(원본 동일). */
open class BrokerError(message: String) : RuntimeException(message)

/** 전송 후 접수 여부를 확정할 수 없음. 거절로 처리하거나 자동 재전송하면 안 된다. */
class OrderOutcomeUnknown(message: String) : IllegalStateException(message)

class KisError(message: String) : BrokerError(message)

class TossError(message: String) : BrokerError(message)

/** 주문 POST 가 나가기 전에 실패 — 접수됐을 가능성이 없다. */
class OrderNotSent(message: String) : BrokerError(message)

/** 정규화 현재가. price 만 필수, 증권사가 안 주는 부가 필드는 0. */
data class Quote(
    val symbol: String,
    val price: BigDecimal,
    val change: BigDecimal = BigDecimal.ZERO,
    val changeRate: BigDecimal = BigDecimal.ZERO,
    val volume: Long = 0,
    val high: BigDecimal = BigDecimal.ZERO,
    val low: BigDecimal = BigDecimal.ZERO,
    val open: BigDecimal = BigDecimal.ZERO,
    val currency: String = "KRW",
    val halted: Boolean = false,
    val statusCode: String = "",
)

data class OrderResult(val orderId: String?, val orderOrgNo: String? = null, val raw: Map<String, Any?> = emptyMap())

/**
 * 체결 조회 결과. 시장가라도 실제 체결가는 신호 시점가와 다르다 — 체결 기록·평단에는 반드시 이 값.
 * [terminal] = 증권사가 이 주문을 종료 상태(더 체결되지 않음)로 보고했고 그 응답의 체결 수량을 읽었다. false 는 "진행 중"이 아니라
 * "종료 근거 없음"(진행 중·모름·읽지 못함·그런 정보를 주지 않는 증권사). KIS 는 항상 false — 주문 목록 행([BrokerOrderRow])으로 판단한다.
 */
data class Fill(
    val filledQty: Int,
    val avgPrice: BigDecimal?,
    val fullyFilled: Boolean,
    val raw: Map<String, Any?>? = null,
    val terminal: Boolean = false,
)

/**
 * 증권사 주문 목록의 한 행. 정정·취소 주문 행도 포함하며 [originalOrderNo] 로 구분한다(원주문 행은 null).
 * 정정 주문에서 난 체결은 원주문 행이 아니라 그 정정 행에 잡힌다 — 원주문 행의 총 체결만으로 주문 전체를 판단하면 안 된다.
 */
data class BrokerOrderRow(
    val orderNo: String,          // 증권사 주문번호 — getOrderExecution·주문 응답의 주문번호와 같은 표기(비교 방식도 같게)
    val symbol: String,
    val side: String,             // "buy" | "sell"
    val orderQty: Int,
    val filledQty: Int,           // 그 주문의 총 체결수량
    val avgPrice: BigDecimal?,    // 평균 체결가. 없거나 0 이면 null
    val orderTime: LocalTime?,    // 증권사 주문 시각(KST). 파싱 못 하면 null
    val originalOrderNo: String? = null, // 정정·취소 행의 원주문번호(증권사 원문 trim). [orderNo] 와 같은 표기라고 확인하지 못했다 — 구현별 KDoc 참고
)

data class NormalizedPosition(val symbol: String, val qty: Int, val avgPrice: BigDecimal?)

/** 잔고(증권사 원형을 느슨하게 보존). */
data class Balance(val positions: List<Map<String, Any?>> = emptyList(), val summary: Any? = emptyMap<String, Any?>()) {
    /**
     * KIS(pdno/hldg_qty/pchs_avg_pric)·원본의 토스 키(symbol/qty/avg_price)·토스 공식 스펙 `HoldingsItem` 키(quantity/averagePurchasePrice)
     * 차이를 흡수. 앞 키가 우선이고 원본 `a or b` 처럼 빈 문자열·0 은 다음 키로 넘어간다. 수량 0 이하는 제외.
     * 원본 `base.py` 는 스펙 키를 읽지 않아 토스 보유가 수량 0 으로 빠졌다(공식 스펙 openapi.json `HoldingsItem`).
     */
    fun positionsNormalized(): List<NormalizedPosition> = positions.mapNotNull { p ->
        val code = (p["pdno"] ?: p["symbol"])?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val qty = runCatching { p.firstTruthy("hldg_qty", "qty", "quantity").orZero().toDouble().toInt() }.getOrDefault(0)
        if (qty <= 0) return@mapNotNull null
        val avgRaw = p.firstTruthy("pchs_avg_pric", "avg_price", "averagePurchasePrice")
        val avg = avgRaw?.toString()?.takeIf { it.isNotEmpty() && it != "0" }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        NormalizedPosition(code, qty, avg)
    }
}

/** 원본 `p.get(a) or p.get(b) or ...` — 없음·빈 문자열·숫자 0 은 거짓이라 다음 키로 넘어간다(문자열 "0" 은 참). 다 거짓이면 null. */
private fun Map<String, Any?>.firstTruthy(vararg keys: String): Any? = keys.firstNotNullOfOrNull { k ->
    this[k]?.takeUnless { it is String && it.isEmpty() || it is Number && it.toDouble() == 0.0 }
}

/** 원본 `x or 0` — null 뿐 아니라 빈 문자열도 "0". 숫자 변환은 호출부가 원본대로 고른다. */
internal fun Any?.orZero(): String = this?.toString()?.takeIf { it.isNotEmpty() } ?: "0"

/**
 * 체결 불가(정지·VI) 판정 — 브로커 응답 → `Quote.halted` 정규화의 단일 출처.
 * VI 는 `temp_stop_yn` 이 아니라 전용 필드(`vi_cls_code`)로 온다(실응답 확인). 실제 발동 시 'Y' 인지는 아직 미검증(원본 주석).
 */
fun isHaltedStatus(tempStopYn: String?, statusCode: String?, viClsCode: String? = null): Boolean =
    tempStopYn?.trim()?.uppercase() == "Y" || viClsCode?.trim()?.uppercase() == "Y" || (statusCode?.trim() ?: "") in HALT_STATUS_CODES

val HALT_STATUS_CODES = setOf("58")

/** 증권사 클라이언트 공통 인터페이스. 사용자별 자격증명으로 만들고, 토큰 캐시·인증은 내부에서. */
interface BrokerClient {
    val orderAccountKey: String?
        get() = null
    fun verifyConnection(): Boolean
    fun getQuote(symbol: String): Quote
    /** side="buy"|"sell", orderType="market"|"limit"(시장가면 price 0). */
    fun placeOrder(symbol: String, side: String, qty: Int, price: Int = 0, orderType: String = "market"): OrderResult
    fun getOrderExecution(orderId: String, symbol: String? = null): Fill
    /** 주문일 기준 조회를 지원하는 브로커에 KST 주문일을 전달한다. */
    fun getOrderExecution(orderId: String, symbol: String?, orderDate: LocalDate): Fill = getOrderExecution(orderId, symbol)
    /**
     * [day](KST 주문일)에 이 계좌로 낸 주문 전체(미체결·거부·정정·취소 주문 행 포함 — [BrokerOrderRow.originalOrderNo]). null = 이 증권사는 목록 조회를 지원하지 않는다.
     * 조회 실패·목록이 완결되지 않음(다음 페이지 확인 불가, 상한 초과)은 빈 목록이 아니라 [BrokerError].
     */
    fun dailyOrders(day: LocalDate): List<BrokerOrderRow>? = null
    fun getBalance(): Balance
}
