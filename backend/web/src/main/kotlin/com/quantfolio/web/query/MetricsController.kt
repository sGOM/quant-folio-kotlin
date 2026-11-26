package com.quantfolio.web.query

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.metrics.PanicOut
import com.quantfolio.metrics.PanicService
import com.quantfolio.metrics.SectorMetric
import com.quantfolio.metrics.SectorMetricsService
import com.quantfolio.metrics.SectorsOut
import com.quantfolio.metrics.StockMetric
import com.quantfolio.metrics.StockMetricsService
import com.quantfolio.metrics.StocksOut
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.JsonCache
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 정렬 키: 결측은 뒤로, 값은 내림차순(원본 `(v is None, -(v or 0.0))`, 안정 정렬). */
private fun <T> descNullsLast(f: (T) -> Double?): Comparator<T> = compareBy<T> { f(it) == null }.thenBy { -(f(it) ?: 0.0) }

/** 지표 화면(원본 `routes/metrics.py`). 캐시는 정렬·필터 전 전체 데이터, 정렬·필터·limit 은 요청마다. */
@RestController
@RequestMapping("/api/metrics")
class MetricsController(
    private val common: MetricsCommon,
    private val cache: JsonCache,
    private val sectors: SectorMetricsService,
    private val stocks: StockMetricsService,
    private val panic: PanicService,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun <T : Any> cached(kind: String, market: String, label: String, type: Class<T>, isEmpty: (T) -> Boolean, compute: () -> T): T {
        val asOf = common.lastBusinessDay()
        return cache.getOrCompute(
            RedisKeys.metricsCache(kind, market, asOf), type, { !isEmpty(it) },
            { log.warn("{} 계산 결과가 비어 캐시를 건너뜀 (as_of={} market={})", label, asOf, market) },
        ) {
            try {
                compute()
            } catch (e: MarketDataNotLoaded) {
                throw e // 미적재는 계산 오류(503)가 아니라 ApiErrorHandler 의 409
            } catch (e: Exception) {
                log.error("{} 계산 실패: {}", label, e.message, e)
                throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "$label 계산 중 오류가 발생했습니다.")
            }
        }
    }

    @GetMapping("/sectors")
    fun sectors(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "ALL") @Pattern(regexp = MARKET) market: String,
        @RequestParam(defaultValue = "rs") @Pattern(regexp = "rs|mom_6m|mom_3m|value_trend") sort: String,
    ): SectorsOut {
        val data = cached("sectors", market, "섹터 지표", SectorsOut::class.java, { it.items.isEmpty() }) { sectors.compute(market, common.lastBusinessDay()) }
        val key: (SectorMetric) -> Double? = when (sort) {
            "mom_6m" -> { s -> s.mom6m }
            "mom_3m" -> { s -> s.mom3m }
            "value_trend" -> { s -> s.valueTrend }
            else -> { s -> s.rs126 }
        }
        return SectorsOut(data.asOf, data.items.sortedWith(descNullsLast(key)))
    }

    @GetMapping("/stocks")
    fun stocks(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "ALL") @Pattern(regexp = MARKET) market: String,
        @RequestParam(defaultValue = "score") @Pattern(regexp = "score|mom_6m|mom_3m|per|pbr|div") sort: String,
        @RequestParam(name = "min_value", defaultValue = "500000000") @Min(0) minValue: Long,
        @RequestParam(name = "min_mcap", defaultValue = "100000000000") @Min(0) minMcap: Long,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
    ): StocksOut {
        val data = cached("stocks", market, "종목 지표", StocksOut::class.java, { it.items.isEmpty() }) { stocks.compute(market, common.lastBusinessDay()) }
        var items = data.items
        // 캐시는 기본 필터(5억/1,000억)로 산출됐으므로 더 엄격한 조건만 추가로 거른다.
        if (minValue > 500_000_000) items = items.filter { (it.avgValue20 ?: return@filter false) >= minValue }
        if (minMcap > 100_000_000_000) items = items.filter { (it.marketCap ?: return@filter false) >= minMcap }
        val cmp: Comparator<StockMetric> = when (sort) {
            "mom_6m" -> descNullsLast { it.mom6m }
            "mom_3m" -> descNullsLast { it.mom3m }
            "div" -> descNullsLast { it.div }
            // 적자·결측 PER 은 뒤로, 나머지는 낮을수록 앞(원본 `(per is None or per <= 0, per or inf)`)
            "per" -> compareBy<StockMetric> { (it.per ?: 0.0) <= 0 }.thenBy { it.per?.takeIf { p -> p != 0.0 } ?: Double.POSITIVE_INFINITY }
            "pbr" -> compareBy<StockMetric> { it.pbr == null }.thenBy { it.pbr?.takeIf { p -> p != 0.0 } ?: Double.POSITIVE_INFINITY }
            else -> descNullsLast { it.score }
        }
        items = items.sortedWith(cmp).take(limit)
        return StocksOut(data.asOf, items.size, items)
    }

    @GetMapping("/panic")
    fun panic(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "ALL") @Pattern(regexp = MARKET) market: String,
    ): PanicOut = cached("panic", market, "패닉셀 지표", PanicOut::class.java, { it.items.isEmpty() }) { panic.compute(market, common.lastBusinessDay()) }
}
