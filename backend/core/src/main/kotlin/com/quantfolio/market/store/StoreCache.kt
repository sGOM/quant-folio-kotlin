package com.quantfolio.market.store

import com.quantfolio.common.MarketClock
import com.quantfolio.market.source.DataSourceError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter

object CacheKeys {
    private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 조회 인자 → 결정적 문자열. 목록형 인자는 호출 순서가 달라도 같은 조회이므로 정렬한다. */
    fun of(vararg parts: Any?): String = parts.joinToString("|") { p ->
        when (p) {
            is Collection<*> -> p.map { it.toString() }.sorted().joinToString(",")
            is LocalDate -> p.format(YMD)
            else -> p.toString()
        }
    }
}

/**
 * 로컬 우선 조회의 단일 진입점 — 4상태를 명시적으로 가른다.
 *
 * | 원장 상태              | 동작                                         |
 * |-----------------------|----------------------------------------------|
 * | final=true 기록 있음   | readLocal()                                   |
 * | 기록 없음 / final=false| fetchRemote() 1회 → writeLocal() + 원장 기록   |
 * | fetchRemote() 실패     | DataSourceError 그대로 전파(빈 값으로 삼키지 않음) |
 *
 * **빈 결과는 확정으로 굳히지 않는다.** 호출자는 "진짜 무자료"와 "스키마 변경으로 값을 잃음"을 구분할 수 없고,
 * 잘못 굳히면 수동 DB 삭제 전까지 영구히 0행이 된다(원본 §47·§49). 진짜 휴장일도 매번 재조회하지만
 * 백테스트는 거래일만 순회하므로 비용은 작다.
 */
@Component
class StoreCache(private val ledger: FetchLedger, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 확정으로 봐도 되는 마지막 날 — KST 기준 전날. 당일분은 장중 값이 바뀌므로 굳히면 안 된다. */
    fun lastFinalDate(): LocalDate = MarketClock.todayKst(clock).minusDays(1)

    fun isFinalDate(day: LocalDate): Boolean = !day.isAfter(lastFinalDate())

    /**
     * @param isFinal 원격 조회 **후** 평가한다(부분 실패 여부에 따라 달라지는 호출자가 있다).
     * @param columns 요청한 컬럼 값들. 확정 직전 한 컬럼이라도 전부 null 이면 확정을 보류한다 —
     *   소스가 포맷을 바꿔 변환이 통째로 실패해도 행 수는 그대로라 행 수로는 못 잡는다.
     */
    fun <T> cachedFetch(
        source: String,
        key: String,
        readLocal: () -> List<T>,
        fetchRemote: () -> List<T>,
        writeLocal: (List<T>) -> Unit,
        isFinal: () -> Boolean,
        columns: (T) -> List<Any?> = { emptyList() },
    ): List<T> {
        val entry = ledger.get(source, key)
        if (entry != null && entry.final) return readLocal()

        val rows = fetchRemote()
        writeLocal(rows)
        var final = isFinal()
        if (rows.isEmpty()) {
            final = false
        } else if (final && hasAllNullColumn(readLocal(), columns)) {
            log.warn("로컬 적재 컬럼 전체 결측 감지 — 확정 보류: {} {}", source, key)
            final = false
        }
        ledger.put(source, key, rows.size, final)
        return rows
    }

    /**
     * 범위 조회 — 원장 키 일치가 아니라 **확보 구간 포함**으로 판정한다. 합집합이 아니라 개별 구간을 본다
     * (사이 빈틈이 휴장일인지 미적재인지는 거래일 달력 없이 알 수 없다).
     * 실패 예외엔 로컬 확보 구간을 덧붙인다 — 사용자가 기간을 좁혀 재시도할 근거. 기간을 대신 축소하지는 않는다.
     */
    fun <T> cachedRange(
        key: String,
        start: LocalDate,
        end: LocalDate,
        readLocal: () -> List<T>,
        fetchRemote: () -> List<T>,
        writeLocal: (List<T>) -> Unit,
        readCoverage: () -> List<Pair<LocalDate, LocalDate>>,
        mergeCoverage: (LocalDate, LocalDate, Int) -> Unit,
    ): List<T> {
        val finalThrough = lastFinalDate()
        if (!end.isAfter(finalThrough) && readCoverage().any { (from, to) -> !from.isAfter(start) && !to.isBefore(end) }) {
            return readLocal()
        }
        val rows = try {
            fetchRemote()
        } catch (e: DataSourceError) {
            throw e.copyWithDetail("${e.detail} — 로컬 확보 구간: ${describe(readCoverage())}")
        }
        writeLocal(rows)
        // 커버로 기록하는 건 요청 범위다(정상 응답이면 그 창 안은 다 받은 것). 빈 결과는 기록하지 않는다.
        if (rows.isNotEmpty()) mergeCoverage(start, minOf(end, finalThrough), rows.size)
        log.debug("원격 조회: {} {}~{} rows={}", key, start, end, rows.size)
        return rows
    }

    private fun <T> hasAllNullColumn(rows: List<T>, columns: (T) -> List<Any?>): Boolean {
        if (rows.isEmpty()) return false
        val width = columns(rows.first()).size
        return (0 until width).any { i -> rows.all { columns(it)[i] == null } }
    }

    private fun describe(intervals: List<Pair<LocalDate, LocalDate>>): String {
        if (intervals.isEmpty()) return "없음"
        val newest = intervals.sortedByDescending { it.second }
        val shown = newest.take(3).joinToString(", ") { (f, t) -> "$f~$t" }
        return if (newest.size > 3) "$shown …외 ${newest.size - 3}건" else shown
    }
}
