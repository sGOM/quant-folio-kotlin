package com.quantfolio.web.query

import com.quantfolio.domain.user.User
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import jakarta.validation.constraints.Size
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

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
