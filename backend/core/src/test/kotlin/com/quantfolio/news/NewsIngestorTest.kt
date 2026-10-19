package com.quantfolio.news

import com.quantfolio.domain.TestApp
import com.quantfolio.domain.news.NewsArticle
import com.quantfolio.domain.news.NewsArticleRepository
import com.quantfolio.domain.news.NewsArticleSymbolRepository
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.market.symbols.SymbolInfo
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class NewsIngestorTest(
    @Autowired private val jdbc: NamedParameterJdbcTemplate,
    @Autowired private val tx: TransactionTemplate,
    @Autowired private val articles: NewsArticleRepository,
    @Autowired private val symbols: NewsArticleSymbolRepository,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T00:00:00Z"), ZoneOffset.UTC)
    private val catalog = Mockito.mock(SymbolCatalog::class.java).also {
        Mockito.`when`(it.nameMap()).thenReturn(mapOf("005930" to "삼성전자", "000660" to "SK하이닉스"))
    }

    private fun rss(tag: String, vararg items: Pair<String, String>) =
        "<rss><channel>" + items.joinToString("") { (t, u) -> "<item><title>$t</title><link>https://$tag/$u</link><description>$t 요약 본문</description></item>" } + "</channel></rss>"

    @Test
    fun ingestIsIdempotentTagsSymbolsAndCountsFeedFailures() {
        val tag = UUID.randomUUID().toString().take(8)
        val http = FakeExternalHttp()
        val feeds = NewsIngestor.FEEDS
        http.onText({ it.url == feeds[0].url }, body = rss(tag, "삼성전자 신고가" to "a", "중복" to "dup"))
        http.onText({ it.url == feeds[1].url }, body = rss(tag, "SK하이닉스 감산" to "b", "중복" to "dup"))
        http.onText({ it.url == feeds[2].url }, status = 500, body = "err")
        http.onText({ it.url == feeds[3].url }, body = "<<< not xml")
        http.failWith { it.url == feeds[4].url }
        val ingestor = NewsIngestor(http, catalog, jdbc, tx, clock)

        val r = ingestor.ingest()
        assertEquals(2, r["feeds_ok"])
        assertEquals(listOf(feeds[2].source, feeds[3].source, feeds[4].source), r["feeds_failed"])
        assertEquals(3, r["fetched"])
        assertEquals(3, r["inserted"])
        val a = articles.findAll().single { it.url == "https://$tag/a" }
        assertEquals(feeds[0].source, a.source)
        assertEquals(clock.instant(), a.publishedAt) // pubDate 없으면 수집 시각
        assertEquals("삼성전자 신고가 요약 본문", a.summary)
        assertEquals(listOf("005930"), symbols.findAll().filter { it.articleId == a.id }.map { it.symbol })
        assertTrue(http.calls.first().headers["User-Agent"]!!.startsWith("QuantFolio/1.0"))

        assertEquals(0, ingestor.ingest()["inserted"]) // url 멱등
    }

    @Test
    fun purgeDeletesOlderThanRetention() {
        val tag = UUID.randomUUID().toString()
        val old = articles.save(NewsArticle("s", "old", "https://old/$tag", null, Instant.parse("2026-08-20T00:00:00Z").minusSeconds(1)))
        val keep = articles.save(NewsArticle("s", "keep", "https://keep/$tag", null, Instant.parse("2026-08-20T00:00:01Z")))
        NewsIngestor(FakeExternalHttp(), catalog, jdbc, tx, clock).purge()
        assertFalse(articles.existsById(old.id!!))
        assertTrue(articles.existsById(keep.id!!))
    }
}
