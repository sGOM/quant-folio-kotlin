package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 지수 구성종목 PIT 스냅샷(기준일별). 생존편향 제거 유니버스의 원천. */
@Component
class IndexConstituentStore(private val jdbc: NamedParameterJdbcTemplate) {
    fun write(code: String, baseDate: LocalDate, symbols: List<String>) {
        if (symbols.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO index_constituents (index_code, base_date, symbol, created_at)
            VALUES (:code, :base, :symbol, now())
            ON CONFLICT (index_code, base_date, symbol) DO NOTHING
            """,
            symbols.map { mapOf("code" to code, "base" to baseDate, "symbol" to padSymbol(it)) }.toTypedArray(),
        )
    }

    fun read(code: String, baseDate: LocalDate): List<String> = jdbc.queryForList(
        "SELECT symbol FROM index_constituents WHERE index_code = :code AND base_date = :base",
        mapOf("code" to code, "base" to baseDate),
        String::class.java,
    ).filterNotNull()

    fun delete(code: String, baseDate: LocalDate) {
        jdbc.update(
            "DELETE FROM index_constituents WHERE index_code = :code AND base_date = :base",
            mapOf("code" to code, "base" to baseDate),
        )
    }
}
