package com.quantfolio.market.store

import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StoreCacheTest {
    // 2026-10-19 10:00 KST
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val ledger = InMemoryFetchLedger()
    private val cache = StoreCache(ledger, clock)

    private data class Row(val a: Double?, val b: Double?)

    private var local = listOf<Row>()
    private var remoteCalls = 0

    private fun fetch(remote: List<Row>, final: Boolean = true, onFetch: () -> Unit = {}) = cache.cachedFetch(
        "src", "k",
        readLocal = { local },
        fetchRemote = { remoteCalls++; onFetch(); remote },
        writeLocal = { local = it },
        isFinal = { final },
        columns = { listOf(it.a, it.b) },
    )

    @Test
    fun keyIsDeterministic() {
        assertEquals("20260102|KOSDAQ,KOSPI|x", CacheKeys.of(LocalDate.of(2026, 1, 2), listOf("KOSPI", "KOSDAQ"), "x"))
        assertEquals(CacheKeys.of(listOf("b", "a")), CacheKeys.of(setOf("a", "b")))
    }

    @Test
    fun lastFinalDateIsKstYesterday() {
        val justAfterKstMidnight = StoreCache(ledger, Clock.fixed(Instant.parse("2026-10-18T15:30:00Z"), ZoneOffset.UTC))
        assertEquals(LocalDate.of(2026, 10, 18), justAfterKstMidnight.lastFinalDate())
        assertTrue(cache.isFinalDate(LocalDate.of(2026, 10, 18)))
        assertTrue(!cache.isFinalDate(LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun missFetchesWritesAndRecords() {
        val rows = listOf(Row(1.0, 2.0))
        assertEquals(rows, fetch(rows))
        assertEquals(rows, local)
        assertEquals(LedgerEntry(1, true), ledger.get("src", "k"))
    }

    @Test
    fun finalHitReadsLocalOnly() {
        ledger.put("src", "k", 1, true)
        local = listOf(Row(9.0, 9.0))
        assertEquals(local, fetch(listOf(Row(1.0, 1.0))))
        assertEquals(0, remoteCalls)
    }

    @Test
    fun nonFinalRefetches() {
        ledger.put("src", "k", 1, false)
        fetch(listOf(Row(1.0, 1.0)))
        assertEquals(1, remoteCalls)
    }

    @Test
    fun emptyResultNeverFinal() {
        fetch(emptyList(), final = true)
        assertEquals(LedgerEntry(0, false), ledger.get("src", "k"))
    }

    @Test
    fun allNullColumnNotFinal() {
        // 소스가 컬럼 포맷을 바꿔 b 가 전부 변환 실패(null) — 확정하면 영구 결측이 된다.
        fetch(listOf(Row(1.0, null), Row(2.0, null)), final = true)
        assertEquals(LedgerEntry(2, false), ledger.get("src", "k"))
    }

    @Test
    fun partialNullStillFinal() {
        fetch(listOf(Row(1.0, null), Row(2.0, 3.0)), final = true)
        assertEquals(LedgerEntry(2, true), ledger.get("src", "k"))
    }

    @Test
    fun isFinalEvaluatedAfterFetch() {
        var complete = false
        cache.cachedFetch(
            "src", "k",
            readLocal = { local },
            fetchRemote = { complete = true; listOf(Row(1.0, 1.0)) },
            writeLocal = { local = it },
            isFinal = { complete },
        )
        assertEquals(true, ledger.get("src", "k")!!.final)
    }

    @Test
    fun remoteFailurePropagates() {
        assertThrows<SourceAuthError> {
            cache.cachedFetch<Row>("src", "k", { local }, { throw SourceAuthError("krx", "차단") }, { local = it }, { true })
        }
        assertNull(ledger.get("src", "k"))
    }

    // ── 범위 조회 ──
    private val d = { day: Int -> LocalDate.of(2026, 10, day) }
    private var coverage = mutableListOf<Pair<LocalDate, LocalDate>>()
    private val merged = mutableListOf<Triple<LocalDate, LocalDate, Int>>()

    private fun range(start: LocalDate, end: LocalDate, remote: () -> List<Row>) = cache.cachedRange(
        "1028", start, end,
        readLocal = { local },
        fetchRemote = { remoteCalls++; remote() },
        writeLocal = { local = it },
        readCoverage = { coverage },
        mergeCoverage = { s, e, n -> merged += Triple(s, e, n) },
    )

    @Test
    fun rangeCoveredReadsLocal() {
        coverage += d(1) to d(18)
        local = listOf(Row(1.0, 1.0))
        assertEquals(local, range(d(2), d(18)) { error("원격 호출 금지") })
        assertEquals(0, remoteCalls)
    }

    @Test
    fun rangeNotCoveredWhenEndIsToday() {
        coverage += d(1) to d(30)
        range(d(2), d(19)) { listOf(Row(1.0, 1.0)) }
        assertEquals(1, remoteCalls)
        assertEquals(listOf(Triple(d(2), d(18), 1)), merged) // 확정일까지만 커버로 기록
    }

    @Test
    fun rangeIsNotUnionOfIntervals() {
        coverage += d(1) to d(5)
        coverage += d(6) to d(10)
        range(d(2), d(8)) { listOf(Row(1.0, 1.0)) }
        assertEquals(1, remoteCalls)
    }

    @Test
    fun rangeFailureAddsCoverageHint() {
        coverage += d(1) to d(5)
        val e = assertThrows<SourceUnavailableError> { range(d(2), d(10)) { throw SourceUnavailableError("krx", "점검") } }
        assertIs<SourceUnavailableError>(e)
        assertEquals("점검 — 로컬 확보 구간: 2026-10-01~2026-10-05", e.detail)
    }

    @Test
    fun rangeFailureHintWhenNoCoverage() {
        val e = assertThrows<SourceUnavailableError> { range(d(2), d(10)) { throw SourceUnavailableError("krx", "점검") } }
        assertEquals("점검 — 로컬 확보 구간: 없음", e.detail)
    }

    @Test
    fun rangeEmptyNotMerged() {
        range(d(2), d(10)) { emptyList() }
        assertTrue(merged.isEmpty())
    }
}
