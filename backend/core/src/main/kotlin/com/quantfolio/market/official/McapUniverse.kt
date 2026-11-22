package com.quantfolio.market.official

import com.quantfolio.market.store.CapKind
import com.quantfolio.market.store.StockDailyStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 기간 [union] 은 패널 적재용(기간 내 모든 결정일 풀의 합집합), [members] 는 결정일별 후보(날짜별 메모). */
class McapPool(val union: List<String>, val members: (LocalDate) -> List<String>)

/**
 * 시총 상위 N 유니버스(소스 `KOSPI_MCAP`). 후보 = asOf **직전 거래일** KOSPI 보통주를 시가총액 내림차순(동률은 종목코드 오름차순)으로 N개.
 * 직전 거래일까지의 데이터만 쓴다(미래참조 금지). 주권 종류는 기본정보 적재일에만 채워지므로 종목별로 직전 거래일 이하의 최신 값을 쓴다.
 */
@Component
class McapUniverse(private val daily: StockDailyStore, private val days: LocalTradingDays) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun members(asOf: LocalDate, n: Int): List<String> {
        val prev = previousTradingDay(asOf)
        return top(prev, daily.capsWithKind(prev, "KOSPI"), n)
    }

    /**
     * [start, end] 의 모든 결정일 d 에 대해 직전 거래일 시총 상위 [n] 을 한 번의 조회로 구해 합집합을 만들고,
     * 결정일별 풀은 [members] 로 날짜마다 계산(메모)한다.
     */
    fun pool(start: LocalDate, end: LocalDate, n: Int): McapPool {
        val first = previousTradingDay(start)
        val byDay = daily.capsWithKindRange(first, end.minusDays(1), "KOSPI").groupBy({ it.first }, { it.second })
        if (byDay.isEmpty()) throw MarketDataNotLoaded("stock_daily", "KOSPI", listOf(first to end.minusDays(1)))
        val union = byDay.toSortedMap().flatMap { (day, rows) -> top(day, rows, n, warn = false) }.toSortedSet().toList()
        val unknown = byDay.values.maxOf { rows -> rows.count { it.stockKind == null } }
        if (unknown > 0) log.warn("주권 종류를 모르는 KOSPI 종목이 하루 최대 {}개 있어 시총 유니버스에서 제외한다({}~{}).", unknown, first, end)
        val memo = HashMap<LocalDate, List<String>>()
        // 이미 읽은 구간 안이면 재조회 없이 같은 행으로 계산(결정일 d 는 직전 거래일 행만 본다).
        return McapPool(union) { d ->
            memo.getOrPut(d) {
                val prev = previousTradingDay(d)
                byDay[prev]?.let { top(prev, it, n, warn = false) } ?: members(d, n)
            }
        }
    }

    private fun top(prev: LocalDate, rows: List<CapKind>, n: Int, warn: Boolean = true): List<String> {
        if (rows.isEmpty()) throw MarketDataNotLoaded("stock_daily", "KOSPI", listOf(prev to prev))
        if (rows.all { it.stockKind == null }) throw MarketDataNotLoaded("stock_kind", "KOSPI", listOf(prev to prev))
        val unknown = rows.count { it.stockKind == null }
        if (warn && unknown > 0) log.warn("주권 종류를 모르는 KOSPI 종목 {}개를 시총 유니버스에서 제외한다(기준일 {}).", unknown, prev)
        return rows.filter { it.stockKind == OfficialIngest.COMMON_STOCK }
            .sortedWith(compareByDescending<CapKind> { it.marketCap }.thenBy { it.symbol })
            .take(n).map { it.symbol }
    }

    /** 직전 거래일. 원장이 모르면(미적재) 추측하지 않고 미적재. */
    private fun previousTradingDay(asOf: LocalDate): LocalDate =
        days.previous(asOf) ?: days.expected(asOf.minusDays(10), asOf.minusDays(1)).lastOrNull().let { gap ->
            // previous 가 null 이면 직전 기대 거래일(원장이 모르는 날)이 빠진 것 - 그 날을 알려야 호출자가 "T-1 만 빠짐"을 판별한다
            val day = gap ?: asOf.minusDays(1)
            throw MarketDataNotLoaded("trading_days", "KOSPI", listOf(day to day))
        }
}
