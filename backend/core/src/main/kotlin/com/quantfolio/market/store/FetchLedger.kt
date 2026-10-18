package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component

/**
 * 조회 1건의 기록. rowCount 0 은 "조회했고 정말 데이터가 없었다", final=false 면 다음 호출에서 재조회.
 * 정규화 테이블만으로는 휴장일(0행)과 미적재(0행)를 구분할 수 없어서 조회 사실 자체를 남긴다.
 */
data class LedgerEntry(val rowCount: Int, val final: Boolean)

interface FetchLedger {
    /** 조회한 적이 없으면 null. */
    fun get(source: String, key: String): LedgerEntry?

    /** 같은 키는 덮어쓴다. */
    fun put(source: String, key: String, rowCount: Int, final: Boolean)
}

/** `external_fetches` 테이블 원장. */
@Component
class SqlFetchLedger(private val jdbc: NamedParameterJdbcTemplate) : FetchLedger {
    override fun get(source: String, key: String): LedgerEntry? = jdbc.query(
        "SELECT row_count, final FROM external_fetches WHERE source = :source AND cache_key = :key",
        mapOf("source" to source, "key" to key),
    ) { rs, _ -> LedgerEntry(rs.getInt(1), rs.getBoolean(2)) }.firstOrNull()

    override fun put(source: String, key: String, rowCount: Int, final: Boolean) {
        jdbc.update(
            """
            INSERT INTO external_fetches (source, cache_key, row_count, final, fetched_at)
            VALUES (:source, :key, :rowCount, :final, now())
            ON CONFLICT (source, cache_key) DO UPDATE
               SET row_count = EXCLUDED.row_count, final = EXCLUDED.final, fetched_at = EXCLUDED.fetched_at
            """,
            mapOf("source" to source, "key" to key, "rowCount" to rowCount, "final" to final),
        )
    }
}
