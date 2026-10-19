package com.quantfolio.rebalance

import org.junit.jupiter.api.Test
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** 리밸런싱 발화 판정(원본 `engine/rebalance.is_rebalance_due`). */
class RebalanceDueTest {
    private val kst = ZoneId.of("Asia/Seoul")
    private fun at(s: String) = ZonedDateTime.of(java.time.LocalDateTime.parse(s), kst)

    @Test
    fun requiresOpenMarketTimeNewPeriodAndThreshold() {
        val monthly = mapOf("cadence" to "monthly", "rebalance_dom" to 15, "rebalance_time" to "10:00")
        assertFalse(Rebalance.isRebalanceDue(monthly, null, at("2026-10-15T10:00"), marketOpen = false))
        assertFalse(Rebalance.isRebalanceDue(monthly, null, at("2026-10-15T09:59"), marketOpen = true))
        assertTrue(Rebalance.isRebalanceDue(monthly, null, at("2026-10-15T10:00"), marketOpen = true))
        assertFalse(Rebalance.isRebalanceDue(monthly, null, at("2026-10-14T11:00"), marketOpen = true)) // dom 전
        assertFalse(Rebalance.isRebalanceDue(monthly, at("2026-10-15T10:00"), at("2026-10-20T11:00"), marketOpen = true)) // 같은 달 이미 실행
        assertTrue(Rebalance.isRebalanceDue(monthly, at("2026-10-15T10:00"), at("2026-11-16T11:00"), marketOpen = true))
        // dom 31 → 2월 말일로 클램프
        assertTrue(Rebalance.isRebalanceDue(mapOf("cadence" to "monthly", "rebalance_dom" to 31), null, at("2026-02-28T15:00"), marketOpen = true))
        val weekly = mapOf("cadence" to "weekly", "rebalance_weekday" to 2) // 수요일, 기본 시각 14:30
        assertFalse(Rebalance.isRebalanceDue(weekly, null, at("2026-10-13T15:00"), marketOpen = true)) // 화요일
        assertTrue(Rebalance.isRebalanceDue(weekly, null, at("2026-10-14T14:30"), marketOpen = true))
        assertFalse(Rebalance.isRebalanceDue(weekly, at("2026-10-14T14:30"), at("2026-10-16T15:00"), marketOpen = true)) // 같은 ISO 주
    }
}
