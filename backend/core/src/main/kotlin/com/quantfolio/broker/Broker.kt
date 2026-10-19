package com.quantfolio.broker

import java.math.BigDecimal

/** 증권사 API 호출 실패(공통). 호출부는 이 타입으로 잡는다 — 네트워크 단절(IOException)은 별개로 전파된다(원본 동일). */
open class BrokerError(message: String) : RuntimeException(message)

class KisError(message: String) : BrokerError(message)

class TossError(message: String) : BrokerError(message)

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

/** 체결 조회 결과. 시장가라도 실제 체결가는 신호 시점가와 다르다 — 체결 기록·평단에는 반드시 이 값. */
data class Fill(val filledQty: Int, val avgPrice: BigDecimal?, val fullyFilled: Boolean, val raw: Map<String, Any?>? = null)

data class NormalizedPosition(val symbol: String, val qty: Int, val avgPrice: BigDecimal?)

/** 잔고(증권사 원형을 느슨하게 보존). */
data class Balance(val positions: List<Map<String, Any?>> = emptyList(), val summary: Any? = emptyMap<String, Any?>()) {
    /** KIS(pdno/hldg_qty/pchs_avg_pric)·토스(symbol/qty/avg_price) 키 차이를 흡수. 수량 0 이하는 제외. */
    fun positionsNormalized(): List<NormalizedPosition> = positions.mapNotNull { p ->
        val code = (p["pdno"] ?: p["symbol"])?.toString()?.takeIf { it.isNotEmpty() } ?: return@mapNotNull null
        val qty = runCatching { ((p["hldg_qty"] ?: p["qty"])?.toString()?.takeIf { it.isNotEmpty() } ?: "0").toDouble().toInt() }.getOrDefault(0)
        if (qty <= 0) return@mapNotNull null
        val avgRaw = p["pchs_avg_pric"] ?: p["avg_price"]
        val avg = avgRaw?.toString()?.takeIf { it.isNotEmpty() && it != "0" }?.let { runCatching { BigDecimal(it) }.getOrNull() }
        NormalizedPosition(code, qty, avg)
    }
}

/**
 * 체결 불가(정지·VI) 판정 — 브로커 응답 → `Quote.halted` 정규화의 단일 출처.
 * VI 는 `temp_stop_yn` 이 아니라 전용 필드(`vi_cls_code`)로 온다(실응답 확인). 실제 발동 시 'Y' 인지는 아직 미검증(원본 주석).
 */
fun isHaltedStatus(tempStopYn: String?, statusCode: String?, viClsCode: String? = null): Boolean =
    tempStopYn?.trim()?.uppercase() == "Y" || viClsCode?.trim()?.uppercase() == "Y" || (statusCode?.trim() ?: "") in HALT_STATUS_CODES

val HALT_STATUS_CODES = setOf("58")

/** 증권사 클라이언트 공통 인터페이스. 사용자별 자격증명으로 만들고, 토큰 캐시·인증은 내부에서. */
interface BrokerClient {
    fun verifyConnection(): Boolean
    fun getQuote(symbol: String): Quote
    /** side="buy"|"sell", orderType="market"|"limit"(시장가면 price 0). */
    fun placeOrder(symbol: String, side: String, qty: Int, price: Int = 0, orderType: String = "market"): OrderResult
    fun getOrderExecution(orderId: String, symbol: String? = null): Fill
    fun getBalance(): Balance
}
