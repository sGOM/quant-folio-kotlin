package com.quantfolio.news

import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.symbols.SymbolCatalog
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration

/** 경제 뉴스 수집(원본 `news.ingest_news`·`purge_old_news`). url 유니크 기준 멱등, 피드 하나의 실패는 집계만 하고 계속. */
@Component
class NewsIngestor(
    private val http: ExternalHttp,
    private val catalog: SymbolCatalog,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    data class Feed(val source: String, val url: String)

    /** 수집 결과: feeds_ok·feeds_failed(소스명)·fetched(중복 url 제거 후)·inserted. 전체 실패면 호출자가 알림. */
    fun ingest(): Map<String, Any> {
        val nameMap = runCatching { catalog.nameMap() }.getOrDefault(emptyMap())
        val now = clock.instant()
        var ok = 0
        val failed = mutableListOf<String>()
        val byUrl = LinkedHashMap<String, Pair<String, FeedItem>>()
        for (feed in FEEDS) {
            try {
                val r = http.get(feed.url, emptyMap(), mapOf("User-Agent" to USER_AGENT), Duration.ofSeconds(10))
                if (r.status !in 200..299) throw IllegalStateException("HTTP ${r.status}")
                NewsFeeds.parseFeed(r.body).forEach { byUrl.putIfAbsent(it.url, feed.source to it) }
                ok++
            } catch (e: Exception) {
                failed += feed.source
                log.warn("뉴스 피드 수집 실패({}): {}", feed.source, e.message)
            }
        }
        var inserted = 0
        if (byUrl.isNotEmpty()) {
            tx.executeWithoutResult {
                val existing = jdbc.queryForList("select url from news_articles where url in (:u)", MapSqlParameterSource("u", byUrl.keys.toList()), String::class.java).toSet()
                for ((url, pair) in byUrl) {
                    if (url in existing) continue
                    val (source, item) = pair
                    val summary = NewsFeeds.summarize(item.title, item.description)
                    val id = jdbc.queryForObject(
                        "insert into news_articles(source, title, url, summary, published_at, created_at) values (:s, :t, :u, :sum, :p, :c) returning id",
                        MapSqlParameterSource("s", source).addValue("t", item.title).addValue("u", url).addValue("sum", summary)
                            .addValue("p", Timestamp.from(item.publishedAt ?: now)).addValue("c", Timestamp.from(now)),
                        Int::class.java,
                    )
                    for (code in NewsFeeds.matchSymbols("${item.title} ${summary ?: ""}", nameMap)) {
                        jdbc.update("insert into news_article_symbols(article_id, symbol) values (:a, :s)", MapSqlParameterSource("a", id).addValue("s", code))
                    }
                    inserted++
                }
            }
        }
        val result = linkedMapOf<String, Any>("feeds_ok" to ok, "feeds_failed" to failed, "fetched" to byUrl.size, "inserted" to inserted)
        log.info("뉴스 수집 완료: {}", result)
        return result
    }

    /** 보존기간(60일)이 지난 기사 삭제(종목 매핑은 FK CASCADE). */
    fun purge(days: Long = RETENTION_DAYS): Int =
        jdbc.update("delete from news_articles where published_at < :c", MapSqlParameterSource("c", Timestamp.from(clock.instant().minus(Duration.ofDays(days)))))

    companion object {
        const val RETENTION_DAYS = 60L
        private const val USER_AGENT = "QuantFolio/1.0 (news aggregator; contact: admin)"
        val FEEDS = listOf(
            Feed("연합뉴스 경제", "https://www.yna.co.kr/rss/economy.xml"),
            Feed("매일경제 경제", "https://www.mk.co.kr/rss/30100041/"),
            Feed("매일경제 증권", "https://www.mk.co.kr/rss/50200011/"),
            Feed("한국경제 경제", "https://www.hankyung.com/feed/economy"),
            Feed("한국경제 증권", "https://www.hankyung.com/feed/finance"),
        )
    }
}
