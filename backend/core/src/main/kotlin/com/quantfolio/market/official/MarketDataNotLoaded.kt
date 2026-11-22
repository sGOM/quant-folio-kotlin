package com.quantfolio.market.official

import java.time.LocalDate

/**
 * 로컬 DB 에 필요한 시장데이터가 적재돼 있지 않다. 원격으로 조용히 대체하지 않고 무엇이·어디가·어느 날짜가 빠졌는지 알린다.
 * 조회 실패(`DataSourceError`)와는 다른 사건이라 그 하위가 아니다.
 *
 * @param kind 빠진 데이터 종류(`stock_daily`·`index_ohlcv`·`price_ticks`·`dart_accounts`·`valuation_pending`·`unsupported_in_phase1`)
 * @param key 대상(시장·종목·조회 키)
 * @param missing 빠진 날짜 구간(양끝 포함). 날짜 개념이 없는 종류는 빈 목록.
 */
class MarketDataNotLoaded(val kind: String, val key: String, val missing: List<Pair<LocalDate, LocalDate>> = emptyList()) :
    RuntimeException("시장데이터 미적재: $kind $key ${missing.joinToString(",") { "${it.first}~${it.second}" }}".trim())

/** [order] 에서 연속인 [missing] 날짜들을 (처음, 끝) 구간으로 묶는다. [missing] 은 [order] 의 부분집합. */
internal fun contiguousRanges(missing: Collection<LocalDate>, order: List<LocalDate>): List<Pair<LocalDate, LocalDate>> {
    val out = mutableListOf<Pair<LocalDate, LocalDate>>()
    var runStart: LocalDate? = null
    var prev: LocalDate? = null
    var prevMissing = false
    for (d in order) {
        val gap = d in missing
        if (gap && !prevMissing) runStart = d
        if (!gap && prevMissing) out += runStart!! to prev!!
        prevMissing = gap
        prev = d
    }
    if (prevMissing) out += runStart!! to prev!!
    return out
}
