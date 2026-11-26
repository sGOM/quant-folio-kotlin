package com.quantfolio.market.symbols

import com.quantfolio.market.kis.MasterRow
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SymbolCatalogTest {
    private fun rows(vararg r: Pair<String, String>) = r.map { (c, n) -> MasterRow(c, n, emptyMap()) }

    @Test
    fun `catalog built without KRX MDC or FDR`() {
        val calls = mutableListOf<String>()
        val catalog = SymbolCatalog { market ->
            calls += market
            if (market == "KOSPI") rows("005930" to "삼성전자(마스터)", "700001" to "마스터종목", "ABC123" to "잘못") else rows("700002" to "코스닥종목")
        }
        val byCode = catalog.catalog().associateBy { it.code }
        assertEquals(listOf("KOSPI", "KOSDAQ"), calls)
        assertEquals("삼성전자", byCode["005930"]!!.name) // seed 이름 유지
        assertEquals("Samsung Electronics", byCode["005930"]!!.nameEn)
        assertEquals("마스터종목", byCode["700001"]!!.name)
        assertEquals("KOSPI", byCode["700001"]!!.market)
        assertEquals("KOSDAQ", byCode["700002"]!!.market)
        assertTrue("ABC123" !in byCode)
        catalog.catalog()
        assertEquals(2, calls.size) // 성공은 캐시
    }

    @Test
    fun masterFailureReturnsSeedWithoutCaching() {
        var n = 0
        val catalog = SymbolCatalog { n++; throw IllegalStateException("down") }
        assertEquals(70, catalog.catalog().size)
        catalog.catalog()
        assertEquals(4, n) // 캐시 보류 — 다음 호출에서 다시 시도
    }

    @Test
    fun searchRanking() {
        val catalog = SymbolCatalog { if (it == "KOSPI") rows("035421" to "네이버테스트") else emptyList() }
        val r = catalog.search("0354")
        assertEquals(listOf("035420", "035421"), r.map { it.code })
        assertEquals("005930", catalog.search("005930").first().code)
        assertEquals("035420", catalog.search("naver").first().code)
        assertEquals(listOf("035720", "293490"), catalog.search("카카오").filter { it.code in setOf("035720", "293490") }.map { it.code })
        assertTrue(catalog.search("   ").isEmpty())
        assertEquals("삼성전자", catalog.nameMap()["005930"])
    }
}
