package com.quantfolio.news

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.OffsetDateTime
import kotlin.test.assertEquals

/** 뉴스 파싱 정답지(tools/oracle/gen_news.py → news.json) 대조. */
class NewsFeedsOracleTest {
    private val json = JsonMapper.builder().build()
    private val data = Oracle.load("news")
    private fun items(n: JsonNode) = (0 until n.size()).map { n.get(it) }
    private fun instant(n: JsonNode) = if (n.isNull) null else OffsetDateTime.parse(n.asString()).toInstant()

    @Test
    fun parseFeeds() {
        for (key in listOf("rss", "atom")) {
            val expected = items(data.get("parsed").get(key))
            val actual = NewsFeeds.parseFeed(data.get(key).asString())
            assertEquals(expected.size, actual.size, key)
            expected.zip(actual).forEachIndexed { i, (e, a) ->
                assertEquals(e.get("title").asString(), a.title, "$key[$i].title")
                assertEquals(e.get("url").asString(), a.url, "$key[$i].url")
                assertEquals(e.get("description").asString(), a.description, "$key[$i].description")
                assertEquals(instant(e.get("published_at")), a.publishedAt, "$key[$i].published_at")
            }
        }
        assertEquals(data.get("parsed").get("many").asInt(), NewsFeeds.parseFeed(data.get("many").asString()).size)
    }

    @Test
    fun datesStripSummariesMatches() {
        for (c in data.get("dates")) {
            val raw = c.get("raw").let { if (it.isNull) null else it.asString() }
            assertEquals(instant(c.get("out")), NewsFeeds.parseDatetime(raw), "date '$raw'")
        }
        for (c in data.get("strip")) assertEquals(c.get("out").asString(), NewsFeeds.stripHtml(c.get("raw").asString()), c.get("raw").asString())
        for (c in data.get("summaries")) {
            val e = c.get("out").let { if (it.isNull) null else it.asString() }
            assertEquals(e, NewsFeeds.summarize(c.get("title").asString(), c.get("desc").asString()), c.get("desc").asString().take(20))
        }
        val names = json.convertValue<Map<String, String>>(data.get("name_map"))
        for (c in data.get("matches")) {
            assertEquals(json.convertValue<List<String>>(c.get("out")), NewsFeeds.matchSymbols(c.get("text").asString(), names), c.get("text").asString())
        }
    }

    /** 원본 str.strip() 은 유니코드 공백(NBSP·전각 공백)도 지운다 — url 이 달라지면 같은 기사가 두 번 적재된다. */
    @org.junit.jupiter.api.Test
    fun unicodeWhitespaceIsStrippedLikePython() {
        val rss = "<rss><channel><item><title>　제목　</title><link>https://n/a </link></item></channel></rss>"
        val item = NewsFeeds.parseFeed(rss).single()
        assertEquals("https://n/a", item.url)
        assertEquals("제목", item.title)
    }
}
