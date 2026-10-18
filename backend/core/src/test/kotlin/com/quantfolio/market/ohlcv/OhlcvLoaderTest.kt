package com.quantfolio.market.ohlcv

import com.quantfolio.domain.TestApp
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class OhlcvLoaderTest(@Autowired private val ticks: PriceTickStore) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val loader = OhlcvLoader(NaverDaily(http), ticks, clock)

    private fun xml(vararg days: String) =
        """<?xml version="1.0" encoding="EUC-KR"?><protocol><chartdata symbol="x">${
            days.joinToString("") { """<item data="$it|100|110|90|105|1000" />""" }
        }</chartdata></protocol>"""

    private fun fdr(code: String, body: String) = http.onText({ it.url.startsWith("https://fchart") && it.query["symbol"] == code }, body = body)
    private fun pykrx(code: String, body: String) = http.onText({ it.url.startsWith("http://fchart") && it.query["symbol"] == code }, body = body)

    private fun day(d: String) = LocalDate.parse(d)
    private fun utc(d: String) = day(d).atStartOfDay().toInstant(ZoneOffset.UTC)

    @Test
    fun loadParsesFdrAndFilters() {
        fdr("005930", xml("20261013", "20261014", "20261015"))
        val bars = loader.load("005930", day("2026-10-14"), day("2026-10-15"))
        assertEquals(listOf(Bar(utc("2026-10-14"), 100.0, 110.0, 90.0, 105.0, 1000), Bar(utc("2026-10-15"), 100.0, 110.0, 90.0, 105.0, 1000)), bars)
        val q = http.calls.single().query
        assertEquals(listOf("day", "6000", "0"), listOf(q["timeframe"], q["count"], q["requestType"]))
    }

    @Test
    fun fallsBackToPykrxWithElapsedCount() {
        fdr("000020", xml())
        pykrx("000020", xml("20261001"))
        val bars = loader.load("000020", day("2026-10-01"), day("2026-10-02"))
        assertEquals(1, bars.size)
        // 오늘(2026-10-19) − 시작(10-01) + 2일 = 20
        assertEquals("20", http.calls.last().query["count"])
    }

    @Test
    fun bothEmptyThrows() {
        fdr("999999", "no data")
        pykrx("999999", xml())
        val e = assertThrows<IllegalStateException> { loader.load("999999", day("2026-10-01"), day("2026-10-02")) }
        assertEquals("999999 시세를 가져오지 못했습니다 (2026-10-01~2026-10-02).", e.message)
    }

    @Test
    fun ensureCoverageFetchesOnlyGaps() {
        val sym = "C" + UUID.randomUUID().toString().take(5)
        ticks.upsert(sym, listOf(Bar(utc("2024-01-15"), 1.0, 1.0, 1.0, 1.0, 1), Bar(utc("2024-02-20"), 1.0, 1.0, 1.0, 1.0, 1)))
        fdr(sym, xml("20240102", "20240115", "20240220", "20240228"))
        loader.ensureCoverage(sym, day("2024-01-01"), day("2024-02-29"))
        // 머리 갭(01-01~01-15)과 꼬리 갭(02-20~02-29) 두 번 조회
        assertEquals(2, http.calls.size)
        val all = ticks.bars(sym, utc("2024-01-01"), utc("2024-02-29"))
        assertEquals(listOf("2024-01-02", "2024-01-15", "2024-02-20", "2024-02-28"), all.map { it.time.atZone(ZoneOffset.UTC).toLocalDate().toString() })
    }

    @Test
    fun ensureCoverageWithinGraceDoesNothing() {
        val sym = "G" + UUID.randomUUID().toString().take(5)
        ticks.upsert(sym, listOf(Bar(utc("2024-01-03"), 1.0, 1.0, 1.0, 1.0, 1), Bar(utc("2024-01-29"), 1.0, 1.0, 1.0, 1.0, 1)))
        loader.ensureCoverage(sym, day("2024-01-01"), day("2024-01-31"))
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun ensureCoverageSwallowsFetchFailure() {
        val sym = "F" + UUID.randomUUID().toString().take(5)
        http.failWith { true }
        loader.ensureCoverage(sym, day("2024-01-01"), day("2024-01-31")) // 예외 없이 끝난다
        assertTrue(ticks.bars(sym, utc("2024-01-01"), utc("2024-01-31")).isEmpty())
    }
}
