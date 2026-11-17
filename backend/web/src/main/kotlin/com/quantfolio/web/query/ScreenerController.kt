package com.quantfolio.web.query

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.metrics.ScreenerService
import com.quantfolio.metrics.TurnaroundScreenOut
import com.quantfolio.web.api.JsonCache
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 원본 `routes/screener.py`. 재무 필터를 못 건 결과·빈 결과는 캐시하지 않는다(일시 장애가 6시간 굳지 않게). */
@RestController
class ScreenerController(private val common: MetricsCommon, private val cache: JsonCache, private val screener: ScreenerService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/screener/turnaround")
    fun turnaround(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "ALL") @Pattern(regexp = MARKET) market: String,
        @RequestParam(name = "top_n", defaultValue = "20") @Min(1) @Max(100) topN: Int,
        @RequestParam(name = "smallcap_pct", defaultValue = "0.20") @DecimalMin(value = "0", inclusive = false) @DecimalMax("1") smallcapPct: Double,
        @RequestParam(defaultValue = "1.5") @DecimalMin("1") @DecimalMax("10") surge: Double,
        @RequestParam(name = "max_debt", defaultValue = "1.5") @DecimalMin(value = "0", inclusive = false) @DecimalMax("10") maxDebt: Double,
    ): TurnaroundScreenOut {
        val asOf = common.lastBusinessDay()
        val key = RedisKeys.screenerTurnaroundCache(market, asOf, topN, smallcapPct, surge, maxDebt)
        return cache.getOrCompute(
            key, TurnaroundScreenOut::class.java, { it.items.isNotEmpty() && it.financialFilterApplied },
            { log.warn("턴어라운드 스크리닝 결과 캐시 건너뜀(items={} financial_filter_applied={}) (as_of={} market={})", it.items.size, it.financialFilterApplied, asOf, market) },
        ) { screener.screenTurnaround(asOf, market, topN, smallcapPct, surge = surge, maxDebt = maxDebt) }
    }
}
