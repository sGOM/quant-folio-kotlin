package com.quantfolio.test

import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.LedgerEntry

/** 프로세스 메모리 원장 — 테스트 전용. */
class InMemoryFetchLedger : FetchLedger {
    val rows = mutableMapOf<Pair<String, String>, LedgerEntry>()

    override fun get(source: String, key: String) = rows[source to key]

    override fun put(source: String, key: String, rowCount: Int, final: Boolean) {
        rows[source to key] = LedgerEntry(rowCount, final)
    }
}
