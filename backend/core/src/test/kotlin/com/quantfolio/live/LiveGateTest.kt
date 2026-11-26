package com.quantfolio.live

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 실전 전환 게이트 판정(원본 `services/live_gate.py`). 메시지는 원본 문구 그대로. */
class LiveGateTest {
    private fun report(n: Int, m1: String?, m3: String?, minSample: Int = 30) =
        mapOf("sample" to mapOf("n_orders" to n, "min_sample" to minSample), "grades" to mapOf("m1_exec" to m1, "m3_total" to m3))

    private val green = report(40, "GREEN", "GREEN")

    @Test
    fun prodWithoutApprovalIsBlocked() {
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = false, fillReport = green)
        assertFalse(r.passed)
        assertEquals(listOf("prod 승인 플래그(KIS_PROD_APPROVED)가 꺼져 있음 — 실전 전환 미승인"), r.reasons)
    }

    @Test
    fun prodApprovedGreenPasses() {
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = green)
        assertTrue(r.passed)
        assertEquals(mapOf("paper" to false, "kis_env" to "prod", "fill_quality" to mapOf("n_orders" to 40, "min_sample" to 30, "m1" to "GREEN", "m3" to "GREEN")), r.details)
    }

    @Test
    fun insufficientSampleBlocksBeforeGrades() {
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = report(12, "RED", "RED"))
        assertEquals(listOf("체결 정합 표본 부족(n=12 < 30) — 판단 불가로 실전 차단"), r.reasons)
    }

    /** 미적재로 종가를 못 읽어 빠진 종목이 있으면 표본 부족 사유에 그 수를 밝힌다(차단은 그대로). */
    @Test
    fun insufficientSampleNamesNotLoadedSymbols() {
        val rep = mapOf("sample" to mapOf("n_orders" to 3, "min_sample" to 30, "n_symbols_not_loaded" to 2), "grades" to emptyMap<String, Any?>())
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = rep)
        assertFalse(r.passed)
        assertEquals(listOf("체결 정합 표본 부족(n=3 < 30, 시장데이터 미적재로 제외된 종목 2개) — 판단 불가로 실전 차단"), r.reasons)
    }

    @Test
    fun redGradeBlocks() {
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = report(30, "AMBER", "RED"))
        assertEquals(listOf("체결 정합 등급 미달(M1=AMBER, M3=RED) — 실전 차단"), r.reasons)
        val missing = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = report(30, "RED", null))
        assertEquals(listOf("체결 정합 등급 미달(M1=RED, M3=None) — 실전 차단"), missing.reasons)
    }

    @Test
    fun emptyReportCountsAsZeroSample() {
        val r = LiveGate.decide(paper = false, kisEnv = "prod", prodApproved = true, fillReport = emptyMap())
        assertEquals(listOf("체결 정합 표본 부족(n=0 < 30) — 판단 불가로 실전 차단"), r.reasons)
    }

    @Test
    fun orderAndDailyCapsWithPythonDecimalFormatting() {
        val r = LiveGate.decide(
            paper = false, kisEnv = "prod", prodApproved = true, fillReport = green,
            order = LiveGate.OrderCheck(BigDecimal("1234567.5"), maxPositionSize = BigDecimal("1000000"), dailyUsed = BigDecimal("9000000"), dailyCap = 10_000_000.0),
        )
        assertEquals(
            listOf(
                "1회 주문 금액 1,234,568원이 최대 포지션 한도 1,000,000원 초과",
                "일일 누적 주문 금액 9,000,000+1,234,568원이 일일 상한 10,000,000원 초과",
            ),
            r.reasons,
        )
        assertEquals(
            mapOf("this_order" to 1234567.5, "daily_used" to 9_000_000.0, "daily_cap" to 10_000_000.0, "max_position_size" to 1_000_000.0),
            r.details["order_notional"],
        )
    }

    @Test
    fun capsAreSkippedWhenUnsetOrZero() {
        val r = LiveGate.decide(
            paper = false, kisEnv = "prod", prodApproved = true, fillReport = green,
            order = LiveGate.OrderCheck(BigDecimal("5000000"), maxPositionSize = null, dailyUsed = BigDecimal("99999999"), dailyCap = 0.0),
        )
        assertTrue(r.passed)
        val exact = LiveGate.decide(
            paper = false, kisEnv = "prod", prodApproved = true, fillReport = green,
            order = LiveGate.OrderCheck(BigDecimal("1000"), maxPositionSize = BigDecimal("1000"), dailyUsed = BigDecimal("9000"), dailyCap = 10_000.0),
        )
        assertTrue(exact.passed) // 상한과 같으면 통과(초과만 차단)
    }

    @Test
    fun paperAlwaysPassesButLogsFailures() {
        val r = LiveGate.decide(paper = true, kisEnv = "vts", prodApproved = false, fillReport = report(3, "RED", "RED"))
        assertTrue(r.passed)
        assertEquals(emptyList(), r.reasons)
        assertEquals(listOf("체결 정합 표본 부족(n=3 < 30) — 판단 불가로 실전 차단"), r.details["logged_failures"])
    }
}
