package com.quantfolio.live

import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat

/**
 * 실전(prod) 전환 게이트의 판정부(원본 `services/live_gate.py evaluate_live_gate`). 실거래 주문 직전 마지막 하드 게이트.
 * ① prod 2단계 승인 ② 체결 정합 등급(표본 부족도 차단) ③ 1회·일일 주문 금액 상한 — 하나라도 걸리면 거부.
 * vts(모의투자)는 같은 검사를 하되 로깅만 하고 항상 통과. 입력 조회(DB·리포트)는 호출자(엔진)가 한다.
 */
object LiveGate {
    private val log = LoggerFactory.getLogger(javaClass)
    private const val DEFAULT_MIN_SAMPLE = 30
    private val BLOCKING = setOf("RED")

    data class Result(val passed: Boolean, val reasons: List<String>, val details: Map<String, Any?>)

    /** 주문 단위 호출일 때만. maxPositionSize=전략별(없으면 사용자 공통) 리스크 한도, dailyUsed=당일 체결 명목 + 당일 미체결 잔량 명목. */
    data class OrderCheck(val notional: BigDecimal, val maxPositionSize: BigDecimal?, val dailyUsed: BigDecimal, val dailyCap: Double?)

    /** Python `f"{Decimal:,.0f}"`(HALF_EVEN, 천 단위 쉼표). */
    private fun won(v: BigDecimal): String = DecimalFormat("#,##0").apply { roundingMode = RoundingMode.HALF_EVEN }.format(v)

    @Suppress("UNCHECKED_CAST")
    fun decide(paper: Boolean, kisEnv: String, prodApproved: Boolean, fillReport: Map<String, Any?>, order: OrderCheck? = null, strategyId: Int? = null): Result {
        val failures = mutableListOf<String>()
        val details = linkedMapOf<String, Any?>("paper" to paper, "kis_env" to kisEnv)
        if (!paper && !prodApproved) failures += "prod 승인 플래그(KIS_PROD_APPROVED)가 꺼져 있음 — 실전 전환 미승인"

        val sample = fillReport["sample"] as? Map<String, Any?> ?: emptyMap()
        val grades = fillReport["grades"] as? Map<String, Any?> ?: emptyMap()
        val n = (sample["n_orders"] as? Number)?.toInt() ?: 0
        val minSample = (sample["min_sample"] as? Number)?.toInt()?.takeIf { it != 0 } ?: DEFAULT_MIN_SAMPLE
        val m1 = grades["m1_exec"] as String?
        val m3 = grades["m3_total"] as String?
        details["fill_quality"] = linkedMapOf("n_orders" to n, "min_sample" to minSample, "m1" to m1, "m3" to m3)
        if (n < minSample) {
            // 미적재로 빠진 종목 수는 원본에 없는 사유(로컬 저장소 전환 뒤 생긴 실패 종류) — 0 이면 원본 문구 그대로.
            val notLoaded = (sample["n_symbols_not_loaded"] as? Number)?.toInt() ?: 0
            val why = if (notLoaded > 0) ", 시장데이터 미적재로 제외된 종목 ${notLoaded}개" else ""
            failures += "체결 정합 표본 부족(n=$n < $minSample$why) — 판단 불가로 실전 차단"
        } else if (m1 in BLOCKING || m3 in BLOCKING) {
            failures += "체결 정합 등급 미달(M1=${m1 ?: "None"}, M3=${m3 ?: "None"}) — 실전 차단"
        }

        if (order != null) {
            val maxPos = order.maxPositionSize
            if (maxPos != null && order.notional > maxPos) failures += "1회 주문 금액 ${won(order.notional)}원이 최대 포지션 한도 ${won(maxPos)}원 초과"
            details["order_notional"] = linkedMapOf(
                "this_order" to order.notional.toDouble(), "daily_used" to order.dailyUsed.toDouble(),
                "daily_cap" to order.dailyCap, "max_position_size" to maxPos?.toDouble(),
            )
            val cap = order.dailyCap?.let { BigDecimal(it.toString()) }
            if (cap != null && cap > BigDecimal.ZERO && order.dailyUsed + order.notional > cap) {
                failures += "일일 누적 주문 금액 ${won(order.dailyUsed)}+${won(order.notional)}원이 일일 상한 ${won(cap)}원 초과"
            }
        }

        if (paper) {
            log.info("[live_gate] vts(모의투자) 전략 {} — 통과{}", strategyId, if (failures.isEmpty()) "" else "(로깅만): " + failures.joinToString("; "))
            return Result(true, emptyList(), details + ("logged_failures" to failures.toList()))
        }
        if (failures.isEmpty()) log.info("[live_gate] 실전(prod) 게이트 통과 전략 {}", strategyId)
        else log.warn("[live_gate] 실전(prod) 게이트 실패 전략 {}: {}", strategyId, failures.joinToString("; "))
        return Result(failures.isEmpty(), failures, details)
    }
}
