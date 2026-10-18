package com.quantfolio.market.symbols

import com.quantfolio.market.krx.ListedStock
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolCatalogTest {
    private val http = FakeExternalHttp()

    private fun fdrListing(vararg rows: Triple<String, String, String>) {
        http.onText({ it.url.contains("executeForResourceBundle") }, body = """{"result":{"output":[{"max_work_dt":"20261016"}]}}""")
        http.onText(
            { it.url.endsWith("getJsonData.cmd") && it.query["bld"] == "dbms/MDC/STAT/standard/MDCSTAT01501" },
            body = """{"OutBlock_1":[${rows.joinToString(",") { (c, n, m) -> """{"ISU_SRT_CD":"$c","ISU_ABBRV":"$n","MKT_NM":"$m","MKTCAP":"1"}""" }}]}""",
        )
    }

    @Test
    fun seedFirstThenKrxThenFdr() {
        fdrListing(Triple("700002", "FDR종목", "KOSDAQ"), Triple("005930", "다른이름", "KOSPI"))
        val catalog = SymbolCatalog(
            { listOf(ListedStock("005930", "삼성전자(KRX)", "KOSPI"), ListedStock("700001", "KRX종목", "KOSPI"), ListedStock("ABC123", "잘못", "")) },
            http,
        )
        val byCode = catalog.catalog().associateBy { it.code }
        assertEquals("삼성전자", byCode["005930"]!!.name) // seed 이름 유지
        assertEquals("Samsung Electronics", byCode["005930"]!!.nameEn)
        assertEquals("KRX종목", byCode["700001"]!!.name)
        assertEquals("KOSDAQ", byCode["700002"]!!.market)
        assertTrue("ABC123" !in byCode)
        val fdrPost = http.calls.single { it.url.endsWith("getJsonData.cmd") }
        assertEquals("20261016", fdrPost.query["trdDd"])
        assertEquals("ALL", fdrPost.query["mktId"])
    }

    @Test
    fun allExternalFailuresReturnSeedWithoutCaching() {
        http.failWith { true }
        var krxCalls = 0
        val catalog = SymbolCatalog({ krxCalls++; throw SourceAuthError("krx", "차단") }, http)
        assertEquals(70, catalog.catalog().size)
        catalog.catalog()
        assertEquals(2, krxCalls) // 캐시 보류 — 다음 호출에서 다시 시도
    }

    @Test
    fun searchRanking() {
        http.failWith { true }
        val catalog = SymbolCatalog({ listOf(ListedStock("035421", "네이버테스트", "KOSPI")) }, http)
        val r = catalog.search("0354")
        assertEquals(listOf("035420", "035421"), r.map { it.code }) // 코드 접두(1점) 동률 → 코드순
        assertEquals("005930", catalog.search("005930").first().code) // 정확 일치 0점
        assertEquals("035420", catalog.search("naver").first().code) // 영문 접두
        assertEquals(listOf("035720", "293490"), catalog.search("카카오").filter { it.code in setOf("035720", "293490") }.map { it.code })
        assertTrue(catalog.search("   ").isEmpty())
        assertEquals("삼성전자", catalog.nameMap()["005930"])
    }
}
