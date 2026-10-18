package com.quantfolio.web.query

import com.quantfolio.domain.user.User
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.market.symbols.SymbolInfo
import com.quantfolio.metrics.PanicOut
import com.quantfolio.metrics.PanicService
import com.quantfolio.metrics.RecommendOut
import com.quantfolio.metrics.RecommendService
import com.quantfolio.metrics.ScreenerService
import com.quantfolio.metrics.SectorMetric
import com.quantfolio.metrics.SectorMetricsService
import com.quantfolio.metrics.SectorsOut
import com.quantfolio.metrics.StockMetric
import com.quantfolio.metrics.StockMetricsService
import com.quantfolio.metrics.StocksOut
import com.quantfolio.metrics.TurnaroundScreenOut
import com.quantfolio.quant.pyFloat
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.JsonCache
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private const val MARKET = "ALL|KOSPI|KOSDAQ"

/** 정렬 키: 결측은 뒤로, 값은 내림차순(원본 `(v is None, -(v or 0.0))`, 안정 정렬). */
private fun <T> descNullsLast(f: (T) -> Double?): Comparator<T> = compareBy<T> { f(it) == null }.thenBy { -(f(it) ?: 0.0) }

@RestController
@RequestMapping("/api/symbols")
class SymbolsController(private val catalog: SymbolCatalog) {
    @GetMapping("/search")
    fun search(
        @CurrentUser user: User,
        @RequestParam @Size(min = 1, max = 40) q: String,
        @RequestParam(defaultValue = "20") @Min(1) @Max(50) limit: Int,
    ): List<SymbolInfo> = catalog.search(q, limit)

    @GetMapping("/names")
    fun names(@CurrentUser user: User): Map<String, String> = catalog.nameMap()
}

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
        val key = "metrics:$kind:$market:$asOf"
        cache.get(key, type)?.let { return it }
        val data = try {
            compute()
        } catch (e: Exception) {
            log.error("{} 계산 실패: {}", label, e.message, e)
            throw ApiException(HttpStatus.SERVICE_UNAVAILABLE, "$label 계산 중 오류가 발생했습니다.")
        }
        if (!isEmpty(data)) cache.put(key, data, JsonCache.EOD_TTL)
        else log.warn("{} 계산 결과가 비어 캐시를 건너뜀 (as_of={} market={})", label, asOf, market)
        return data
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

@RestController
class RecommendController(private val common: MetricsCommon, private val cache: JsonCache, private val recommend: RecommendService) {
    @GetMapping("/api/recommend/kospi200")
    fun kospi200(@CurrentUser user: User): RecommendOut {
        val asOf = common.lastBusinessDay()
        val key = "recommend:kospi200:$asOf"
        cache.get(key, RecommendOut::class.java)?.let { return it }
        val data = recommend.kospi200Scored(asOf)
        if (data.items.isNotEmpty()) cache.put(key, data, JsonCache.EOD_TTL)
        return data
    }
}

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
        // 키의 실수 표기는 Python str(float) 그대로(원본 캐시와 키가 같아야 한다).
        val key = "screener:turnaround:$market:$asOf:$topN:${pyFloat(smallcapPct)}:${pyFloat(surge)}:${pyFloat(maxDebt)}"
        cache.get(key, TurnaroundScreenOut::class.java)?.let { return it }
        val result = screener.screenTurnaround(asOf, market, topN, smallcapPct, surge = surge, maxDebt = maxDebt)
        if (result.items.isNotEmpty() && result.financialFilterApplied) cache.put(key, result, JsonCache.EOD_TTL)
        else log.warn(
            "턴어라운드 스크리닝 결과 캐시 건너뜀(items={} financial_filter_applied={}) (as_of={} market={})",
            result.items.size, result.financialFilterApplied, asOf, market,
        )
        return result
    }
}

data class NewsItemOut(val id: Int, val source: String, val title: String, val url: String, val summary: String?, val publishedAt: Instant, val symbols: List<String>)

data class NewsListOut(val items: List<NewsItemOut>, val hasMore: Boolean)

/** 원본 `routes/news.py`. 날짜 필터는 KST 하루 경계, 제목 검색은 %·_ 를 리터럴로 이스케이프. */
@RestController
class NewsController(private val jdbc: NamedParameterJdbcTemplate) {
    private val kst = ZoneOffset.ofHours(9)

    @GetMapping("/api/news")
    fun list(
        @CurrentUser user: User,
        @RequestParam(required = false) @Size(max = 20) symbol: String?,
        @RequestParam(required = false) @Size(max = 100) q: String?,
        @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate?,
        @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate?,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
    ): NewsListOut {
        val p = MapSqlParameterSource().addValue("limit", limit + 1).addValue("offset", offset)
        val where = mutableListOf<String>()
        var from = "news_articles a"
        if (!symbol.isNullOrEmpty()) {
            from += " join news_article_symbols s on s.article_id = a.id"
            where += "s.symbol = :symbol"
            p.addValue("symbol", symbol)
        }
        if (!q.isNullOrBlank()) {
            val escaped = q.trim().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
            where += "a.title ilike :q escape '\\'"
            p.addValue("q", "%$escaped%")
        }
        dateFrom?.let {
            where += "a.published_at >= :from"
            p.addValue("from", java.sql.Timestamp.from(it.atStartOfDay().toInstant(kst)))
        }
        dateTo?.let {
            where += "a.published_at < :to"
            p.addValue("to", java.sql.Timestamp.from(it.plusDays(1).atStartOfDay().toInstant(kst)))
        }
        val sql = "select a.id, a.source, a.title, a.url, a.summary, a.published_at from $from" +
            (if (where.isEmpty()) "" else " where " + where.joinToString(" and ")) +
            " order by a.published_at desc, a.id desc limit :limit offset :offset"
        val rows = jdbc.query(sql, p) { rs, _ ->
            NewsItemOut(rs.getInt("id"), rs.getString("source"), rs.getString("title"), rs.getString("url"), rs.getString("summary"), rs.getTimestamp("published_at").toInstant(), emptyList())
        }
        val page = rows.take(limit)
        val symbols = HashMap<Int, MutableList<String>>()
        if (page.isNotEmpty()) {
            jdbc.query("select article_id, symbol from news_article_symbols where article_id in (:ids)", MapSqlParameterSource("ids", page.map { it.id })) { rs ->
                symbols.getOrPut(rs.getInt(1)) { mutableListOf() } += rs.getString(2)
            }
        }
        return NewsListOut(page.map { it.copy(symbols = symbols[it.id].orEmpty().sorted()) }, rows.size > limit)
    }
}
