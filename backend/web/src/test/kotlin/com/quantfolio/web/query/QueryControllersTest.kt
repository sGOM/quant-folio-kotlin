package com.quantfolio.web.query

import com.quantfolio.domain.news.NewsArticle
import com.quantfolio.domain.news.NewsArticleRepository
import com.quantfolio.domain.news.NewsArticleSymbol
import com.quantfolio.domain.news.NewsArticleSymbolRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.market.symbols.SymbolInfo
import com.quantfolio.metrics.PanicOut
import com.quantfolio.metrics.RecommendOut
import com.quantfolio.metrics.RecommendService
import com.quantfolio.metrics.ScreenerService
import com.quantfolio.metrics.SectorMetric
import com.quantfolio.metrics.SectorMetricsService
import com.quantfolio.metrics.SectorsOut
import com.quantfolio.metrics.StockMetric
import com.quantfolio.metrics.StockMetricsService
import com.quantfolio.metrics.StocksOut
import com.quantfolio.metrics.TurnaroundCandidate
import com.quantfolio.metrics.TurnaroundScreenOut
import com.quantfolio.metrics.PanicService
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.BDDMockito.given
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyDouble
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** 응답 `items[*].field` 가 기대 목록과 같은지(와일드카드 JsonPath 는 이 Spring 버전에서 null 로 평가된다). */
class PluckMatcher(private val field: String, private val expected: List<Any?>) : org.hamcrest.TypeSafeMatcher<String>() {
    private val json = tools.jackson.databind.json.JsonMapper.builder().build()
    private fun pluck(body: String) = json.readTree(body).get("items").let { a -> (0 until a.size()).map { a.get(it).get(field).let { v -> if (v.isNumber) v.asInt() else v.asString() } } }
    override fun matchesSafely(item: String) = pluck(item) == expected
    override fun describeTo(d: org.hamcrest.Description) { d.appendText("items[*].$field == $expected") }
    override fun describeMismatchSafely(item: String, d: org.hamcrest.Description) { d.appendText("was ${pluck(item)}") }
}

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class QueryControllersTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val articles: NewsArticleRepository,
    @Autowired private val articleSymbols: NewsArticleSymbolRepository,
) {
    @MockitoBean lateinit var common: MetricsCommon
    @MockitoBean lateinit var catalog: SymbolCatalog
    @MockitoBean lateinit var sectors: SectorMetricsService
    @MockitoBean lateinit var stocks: StockMetricsService
    @MockitoBean lateinit var panic: PanicService
    @MockitoBean lateinit var screener: ScreenerService
    @MockitoBean lateinit var recommend: RecommendService

    private val asOf = LocalDate.of(2026, 10, 16)

    /** Kotlin non-null 파라미터용 eq(Mockito 는 null 을 돌려준다). */
    private fun <T> eqK(v: T): T = eq(v) ?: v
    private lateinit var cookie: Cookie

    @BeforeEach
    fun setUp() {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        cookie = Cookie(SESSION_COOKIE, sessions.create(u.id!!))
        redis.keys("metrics:*").plus(redis.keys("screener:*")).plus(redis.keys("recommend:*")).forEach { redis.delete(it) }
        given(common.lastBusinessDay()).willReturn(asOf)
    }

    @Test
    fun symbolsSearchValidatesAndRequiresAuth() {
        given(catalog.search("삼성", 20)).willReturn(listOf(SymbolInfo("005930", "삼성전자", "Samsung Electronics", "KOSPI")))
        mvc.get("/api/symbols/search?q=삼성").andExpect { status { isUnauthorized() } }
        mvc.get("/api/symbols/search?q=삼성") { cookie(cookie) }.andExpect {
            status { isOk() }
            jsonPath("$[0].code") { value("005930") }
            jsonPath("$[0].name_en") { value("Samsung Electronics") }
        }
        mvc.get("/api/symbols/search?q=") { cookie(cookie) }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].loc[1]") { value("q") }
        }
        mvc.get("/api/symbols/search?q=a&limit=51") { cookie(cookie) }.andExpect { status { isUnprocessableContent() } }
        given(catalog.nameMap()).willReturn(mapOf("005930" to "삼성전자"))
        mvc.get("/api/symbols/names") { cookie(cookie) }.andExpect { jsonPath("$.005930") { value("삼성전자") } }
    }

    @Test
    fun sectorsSortNullsLastAndCacheNonEmpty() {
        given(sectors.compute("ALL", asOf)).willReturn(
            SectorsOut(asOf, listOf(SectorMetric("a", "KOSPI", rs126 = 1.0), SectorMetric("b", "KOSPI"), SectorMetric("c", "KOSDAQ", rs126 = 2.0))),
        )
        repeat(2) {
            mvc.get("/api/metrics/sectors") { cookie(cookie) }.andExpect {
                status { isOk() }
                content { string(PluckMatcher("name", listOf("c", "a", "b"))) }
                jsonPath("$.items[0].rs_126") { value(2.0) }
            }
        }
        verify(sectors, times(1)).compute("ALL", asOf)
        assertNotNull(redis.opsForValue().get("metrics:sectors:ALL:2026-10-16"))
        mvc.get("/api/metrics/sectors?sort=bogus") { cookie(cookie) }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun sectorsComputeFailureIs503AndEmptyIsNotCached() {
        given(sectors.compute("KOSPI", asOf)).willThrow(IllegalStateException("boom"))
        mvc.get("/api/metrics/sectors?market=KOSPI") { cookie(cookie) }.andExpect {
            status { isServiceUnavailable() }
            jsonPath("$.detail") { value("섹터 지표 계산 중 오류가 발생했습니다.") }
        }
        given(sectors.compute("KOSDAQ", asOf)).willReturn(SectorsOut(asOf, emptyList()))
        mvc.get("/api/metrics/sectors?market=KOSDAQ") { cookie(cookie) }.andExpect { status { isOk() } }
        assertNull(redis.opsForValue().get("metrics:sectors:KOSDAQ:2026-10-16"))
    }

    @Test
    fun stocksFilterSortLimit() {
        fun s(code: String, per: Double?, value: Double?, cap: Long?) = StockMetric(code, code, "KOSPI", per = per, avgValue20 = value, marketCap = cap)
        given(stocks.compute("ALL", asOf)).willReturn(
            StocksOut(asOf, 4, listOf(s("a", -3.0, 9e8, 2e11.toLong()), s("b", 12.0, 6e8, 3e11.toLong()), s("c", 5.0, 2e9, 5e11.toLong()), s("d", null, 3e9, null))),
        )
        mvc.get("/api/metrics/stocks?sort=per") { cookie(cookie) }.andExpect {
            content { string(PluckMatcher("code", listOf("c", "b", "a", "d"))) }
            jsonPath("$.count") { value(4) }
        }
        mvc.get("/api/metrics/stocks?sort=per&min_value=1000000000&min_mcap=100000000000&limit=1") { cookie(cookie) }.andExpect {
            content { string(PluckMatcher("code", listOf("c"))) }
            jsonPath("$.count") { value(1) }
        }
        mvc.get("/api/metrics/stocks?limit=0") { cookie(cookie) }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun panicCachedRoundTrip() {
        given(panic.compute("ALL", asOf)).willReturn(PanicOut(asOf, emptyList(), listOf("KOSPI", "KOSDAQ"), listOf("x")))
        mvc.get("/api/metrics/panic") { cookie(cookie) }.andExpect {
            jsonPath("$.unavailable[0]") { value("KOSPI") }
            jsonPath("$.as_of") { value("2026-10-16") }
        }
        assertNull(redis.opsForValue().get("metrics:panic:ALL:2026-10-16")) // 빈 items 는 캐시 안 함
    }

    @Test
    fun screenerCacheKeyUsesPythonFloatReprAndSkipsUnfiltered() {
        val item = TurnaroundCandidate("900001", "x", "KOSDAQ", 1, 1.0, score = 1.0)
        given(screener.screenTurnaround(eqK(asOf), eqK("ALL"), eqK(20), eqK(0.2), anyDouble(), eqK(2.0), eqK(1.5), anyInt()))
            .willReturn(TurnaroundScreenOut(asOf, "ALL", 1, 1, true, true, listOf(item)))
        mvc.get("/api/screener/turnaround?surge=2") { cookie(cookie) }.andExpect { jsonPath("$.items[0].avg_value_20") { value(1.0) } }
        assertNotNull(redis.opsForValue().get("screener:turnaround:ALL:2026-10-16:20:0.2:2.0:1.5"))
        given(screener.screenTurnaround(eqK(asOf), eqK("KOSPI"), anyInt(), anyDouble(), anyDouble(), anyDouble(), anyDouble(), anyInt()))
            .willReturn(TurnaroundScreenOut(asOf, "KOSPI", 1, 1, true, false, listOf(item)))
        mvc.get("/api/screener/turnaround?market=KOSPI") { cookie(cookie) }.andExpect { jsonPath("$.financial_filter_applied") { value(false) } }
        assertNull(redis.opsForValue().get("screener:turnaround:KOSPI:2026-10-16:20:0.2:1.5:1.5"))
        mvc.get("/api/screener/turnaround?surge=0.5") { cookie(cookie) }.andExpect { status { isUnprocessableContent() } }
    }

    @Test
    fun recommendUsesCache() {
        given(recommend.kospi200Scored(asOf)).willReturn(RecommendOut(asOf, "KOSPI200", 0, false, emptyList()))
        mvc.get("/api/recommend/kospi200") { cookie(cookie) }.andExpect { jsonPath("$.index") { value("KOSPI200") } }
        assertNull(redis.opsForValue().get("recommend:kospi200:2026-10-16"))
    }

    @Test
    fun newsFiltersPagesAndSortsSymbols() {
        val tag = UUID.randomUUID().toString().take(8)
        fun art(title: String, at: String) = articles.save(NewsArticle("src", title, "https://n/$tag/$title", null, Instant.parse(at)))
        val a = art("${tag} 반도체 100%_up", "2026-10-15T15:30:00Z") // KST 10-16 00:30
        val b = art("${tag} 배터리", "2026-10-15T14:59:00Z") // KST 10-15 23:59
        art("${tag} 은행", "2026-10-14T01:00:00Z")
        articleSymbols.save(NewsArticleSymbol(a.id!!, "000660"))
        articleSymbols.save(NewsArticleSymbol(a.id!!, "005930"))
        articleSymbols.save(NewsArticleSymbol(b.id!!, "373220"))
        mvc.get("/api/news?q=$tag&limit=2") { cookie(cookie) }.andExpect {
            content { string(PluckMatcher("id", listOf(a.id, b.id))) }
            jsonPath("$.items[0].symbols[0]") { value("000660") }
            jsonPath("$.items[0].symbols[1]") { value("005930") }
            jsonPath("$.has_more") { value(true) }
        }
        mvc.get("/api/news?q=$tag&date_from=2026-10-16&date_to=2026-10-16") { cookie(cookie) }.andExpect {
            content { string(PluckMatcher("id", listOf(a.id))) }
            jsonPath("$.has_more") { value(false) }
        }
        mvc.get("/api/news?q=100%_") { cookie(cookie) }.andExpect { content { string(PluckMatcher("id", listOf(a.id))) } } // %·_ 리터럴
        mvc.get("/api/news?symbol=373220&q=$tag") { cookie(cookie) }.andExpect { content { string(PluckMatcher("id", listOf(b.id))) } }
        mvc.get("/api/news?limit=101") { cookie(cookie) }.andExpect { status { isUnprocessableContent() } }
    }
}
