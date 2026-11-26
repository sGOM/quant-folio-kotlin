package com.quantfolio.test

import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.LedgerEntry

/** 프로세스 메모리 원장 — 테스트 전용. */
class InMemoryFetchLedger : FetchLedger {
    val rows = mutableMapOf<Pair<String, String>, LedgerEntry>()

    /** 테스트가 기록 시각을 지정한 키만. 없으면 fetchedAt = null. */
    private val times = mutableMapOf<Pair<String, String>, java.time.Instant>()

    override fun get(source: String, key: String) = rows[source to key]?.copy(fetchedAt = times[source to key])

    fun putAt(source: String, key: String, rowCount: Int, final: Boolean, at: java.time.Instant) {
        rows[source to key] = LedgerEntry(rowCount, final)
        times[source to key] = at
    }

    override fun put(source: String, key: String, rowCount: Int, final: Boolean) {
        times.remove(source to key)
        rows[source to key] = LedgerEntry(rowCount, final)
    }

    /** 거래일 원장(`krx_open|stk_bydd_trd|yyyyMMdd|KOSPI`)을 적재 완료로 채운다. */
    fun trading(days: Iterable<java.time.LocalDate>) = days.forEach { put("krx_open", tradingKey(it), 1, true) }

    /** 휴장 확정(0행·final). */
    fun holiday(days: Iterable<java.time.LocalDate>) = days.forEach { put("krx_open", tradingKey(it), 0, true) }

    private fun tradingKey(d: java.time.LocalDate) = "stk_bydd_trd|${d.toString().replace("-", "")}|KOSPI"
}
