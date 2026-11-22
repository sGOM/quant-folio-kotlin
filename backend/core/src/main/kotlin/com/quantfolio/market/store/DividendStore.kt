package com.quantfolio.market.store

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 현금배당 1건(종목 × 기준일). dps 는 주당 배당금(원). */
data class DividendEvent(
    val symbol: String,
    val recordDate: LocalDate,
    val dps: Double,
    val announcedOn: LocalDate?,
    val source: String,
)

@Component
class DividendStore(private val jdbc: NamedParameterJdbcTemplate) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 같은 (종목, 기준일) 은 마지막 값이 이긴다. dps 가 NaN/±Inf 인 행은 not null 컬럼이라 거른다. */
    fun write(rows: List<DividendEvent>) {
        val finite = rows.filter { it.dps.isFinite() }
        if (finite.size < rows.size) log.warn("비유한 dps 배당 {}건 건너뜀", rows.size - finite.size)
        val unique = lastPerSymbol(finite) { "${padSymbol(it.symbol)}|${it.recordDate}" }
        if (unique.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO dividends (symbol, record_date, dps, announced_on, source)
            VALUES (:symbol, :recordDate, :dps, :announcedOn, :source)
            ON CONFLICT (symbol, record_date) DO UPDATE SET
              dps = EXCLUDED.dps, announced_on = COALESCE(EXCLUDED.announced_on, dividends.announced_on), source = EXCLUDED.source
            """,
            unique.map {
                mapOf("symbol" to padSymbol(it.symbol), "recordDate" to it.recordDate, "dps" to num(it.dps),
                    "announcedOn" to it.announcedOn, "source" to it.source)
            }.toTypedArray(),
        )
    }

    fun read(symbols: List<String>, from: LocalDate, to: LocalDate): List<DividendEvent> {
        if (symbols.isEmpty()) return emptyList()
        return jdbc.query(
            "SELECT * FROM dividends WHERE symbol IN (:symbols) AND record_date BETWEEN :from AND :to ORDER BY symbol, record_date",
            mapOf("symbols" to symbols.map(::padSymbol), "from" to from, "to" to to),
        ) { rs, _ ->
            DividendEvent(
                symbol = rs.getString("symbol"), recordDate = rs.getDate("record_date").toLocalDate(),
                dps = rs.getBigDecimal("dps").toDouble(), announcedOn = rs.getDate("announced_on")?.toLocalDate(),
                source = rs.getString("source"),
            )
        }
    }
}
