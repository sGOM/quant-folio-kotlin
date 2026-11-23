package com.quantfolio.market.store

import com.quantfolio.domain.TestApp
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class StoresTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
    @Autowired private val dart: DartFinancialStore,
    @Autowired private val ticks: PriceTickStore,
) {
    private var dayCounter = 0L
    // 다른 테스트의 고정 날짜(2001~2026)와 겹치지 않는 구간 — 겹치면 공유 DB 에서 순서에 따라 깨진다
    private fun freshDay(): LocalDate = LocalDate.of(2101, 1, 1).plusDays(UUID.randomUUID().mostSignificantBits.mod(9000L) + dayCounter++)
    private fun uniq() = UUID.randomUUID().toString().take(8)

    // ── stock_daily_snapshots ──
    @Test
    fun dailyRoundTrip() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("005930", market = "KOSPI", per = 12.5, pbr = 1.1, marketCap = 400_000_000_000_000L)))
        val got = daily.read(day).single()
        assertEquals(12.5, got.per)
        assertEquals(400_000_000_000_000L, got.marketCap)
        assertEquals("KOSPI", got.market)
    }

    @Test
    fun upsertPreservesExistingWhenIncomingNull() {
        // 시총만 적재된 행을 펀더멘털 적재가 null 로 덮으면 앞선 작업이 날아간다.
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000660", market = "KOSPI", marketCap = 100L, shares = 7L)))
        daily.write(day, listOf(StockDaily("000660", market = "KOSPI", per = 8.0)))
        val got = daily.read(day).single()
        assertEquals(100L, got.marketCap)
        assertEquals(7L, got.shares)
        assertEquals(8.0, got.per)
    }

    @Test
    fun marketFilter() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000001", market = "KOSPI", close = 1.0), StockDaily("000002", market = "KOSDAQ", close = 2.0)))
        assertEquals(listOf("000002"), daily.read(day, listOf("KOSDAQ")).map { it.symbol })
        assertEquals(2, daily.read(day).size)
    }

    @Test
    fun symbolZeroPaddedAndDuplicateLastWins() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("5930", close = 1.0), StockDaily("005930", close = 2.0)))
        val got = daily.read(day).single()
        assertEquals("005930", got.symbol)
        assertEquals(2.0, got.close)
    }

    @Test
    fun nonFiniteStoredAsNull() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000003", per = Double.NaN, pbr = Double.POSITIVE_INFINITY, div = 1.23456)))
        val got = daily.read(day).single()
        assertNull(got.per)
        assertNull(got.pbr)
        assertEquals(1.2346, got.div) // numeric(18,4) 반올림
    }

    @Test
    fun prevDiffAndStockKindRoundTrip() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000010", close = 100.0, prevDiff = -1500.5, stockKind = "보통주")))
        val got = daily.read(day).single()
        assertEquals(-1500.5, got.prevDiff)
        assertEquals("보통주", got.stockKind)
    }

    @Test
    fun nullPrevDiffDoesNotOverwriteExisting() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000011", prevDiff = 20.0, stockKind = "우선주")))
        daily.write(day, listOf(StockDaily("000011", close = 5.0)))
        val got = daily.read(day).single()
        assertEquals(20.0, got.prevDiff)
        assertEquals("우선주", got.stockKind)
        assertEquals(5.0, got.close)
    }

    @Test
    fun deleteDay() {
        val day = freshDay()
        daily.write(day, listOf(StockDaily("000004", close = 1.0)))
        daily.delete(day)
        assertTrue(daily.read(day).isEmpty())
    }

    // ── index_ohlcv ──
    @Test
    fun indexBarsSortedAndUpserted() {
        val code = "T${uniq()}"
        val d1 = LocalDate.of(2024, 1, 3)
        val d2 = LocalDate.of(2024, 1, 2)
        indexes.write(code, listOf(IndexBar(d1, close = 10.0), IndexBar(d2, close = 9.0)), indexName = "테스트지수")
        indexes.write(code, listOf(IndexBar(d1, close = 11.0, volume = 5L)), indexName = null)
        val bars = indexes.read(code, d2, d1)
        assertEquals(listOf(d2, d1), bars.map { it.date })
        assertEquals(11.0, bars[1].close)
        assertEquals(5L, bars[1].volume)
    }

    // ── dart_financials ──
    @Test
    fun confirmedDateRule() {
        assertEquals(LocalDate.of(2024, 6, 14), DartFinancialStore.confirmedDate(LocalDate.of(2024, 3, 16), 2023))
        assertEquals(LocalDate.of(2024, 12, 31), DartFinancialStore.confirmedDate(null, 2023))
    }

    @Test
    fun dartRoundTripFinalAndCoalesce() {
        val corp = uniq()
        val accounts = listOf(mapOf("account_nm" to "매출액", "thstrm_amount" to "1,000", "rcept_no" to "20100331000123"))
        dart.write(corp, 2009, "11011", "CFS", accounts)
        val (got, final) = dart.read(corp, 2009, "11011", "CFS")!!
        assertEquals(accounts, got)
        assertTrue(final) // 2010-03-31 + 90일은 과거
        // 접수번호 없는 재적재도 기존 접수일을 지우지 않는다.
        dart.write(corp, 2009, "11011", "CFS", listOf(mapOf("account_nm" to "매출액")))
        assertEquals(true, dart.read(corp, 2009, "11011", "CFS")!!.second)
        assertNull(dart.read(corp, 2009, "11012", "CFS"))
    }

    @Test
    fun dartFutureConfirmNotFinal() {
        val corp = uniq()
        dart.write(corp, 2099, "11011", "OFS", listOf(mapOf("account_nm" to "x")))
        assertEquals(false, dart.read(corp, 2099, "11011", "OFS")!!.second)
    }

    // ── price_ticks ──
    @Test
    fun priceTicksUpsertAndRange() {
        val sym = uniq().take(6)
        val t1 = Instant.parse("2024-01-02T00:00:00Z")
        val t2 = Instant.parse("2024-01-03T00:00:00Z")
        assertEquals(2, ticks.upsert(sym, listOf(Bar(t2, 1.0, 2.0, 0.5, 1.5, 10), Bar(t1, 1.0, 1.0, 1.0, 1.0, 5))))
        ticks.upsert(sym, listOf(Bar(t2, 1.0, 2.0, 0.5, 1.75, 11)))
        val bars = ticks.bars(sym, t1, t2)
        assertEquals(listOf(t1, t2), bars.map { it.time })
        assertEquals(1.75, bars[1].close)
        assertEquals(11L, bars[1].volume)
        assertEquals(t1 to t2, ticks.span(sym, t1, t2))
        assertNull(ticks.span(sym, Instant.parse("2020-01-01T00:00:00Z"), Instant.parse("2020-02-01T00:00:00Z")))
    }
}
