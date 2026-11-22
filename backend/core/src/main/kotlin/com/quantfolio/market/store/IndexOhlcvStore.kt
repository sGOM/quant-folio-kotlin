package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.LocalDate

data class IndexBar(
    val date: LocalDate,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val volume: Long? = null,
    val tradingValue: Long? = null,
)

/** 지수 일봉 + 확보 구간(index_ohlcv_coverage). 범위 조회는 [StoreCache.cachedRange] 가 이 구간으로 판정한다. */
@Component
class IndexOhlcvStore(private val jdbc: NamedParameterJdbcTemplate, private val tx: TransactionTemplate) {
    fun write(code: String, bars: List<IndexBar>, indexName: String?) {
        if (bars.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO index_ohlcv (index_code, trade_date, index_name, open, high, low, close, volume, trading_value, created_at)
            VALUES (:code, :date, :name, :open, :high, :low, :close, :volume, :tradingValue, now())
            ON CONFLICT (index_code, trade_date) DO UPDATE SET
              index_name = COALESCE(EXCLUDED.index_name, index_ohlcv.index_name),
              open = COALESCE(EXCLUDED.open, index_ohlcv.open),
              high = COALESCE(EXCLUDED.high, index_ohlcv.high),
              low = COALESCE(EXCLUDED.low, index_ohlcv.low),
              close = COALESCE(EXCLUDED.close, index_ohlcv.close),
              volume = COALESCE(EXCLUDED.volume, index_ohlcv.volume),
              trading_value = COALESCE(EXCLUDED.trading_value, index_ohlcv.trading_value)
            """,
            bars.map { b ->
                mapOf(
                    "code" to code, "date" to b.date, "name" to indexName,
                    "open" to num(b.open), "high" to num(b.high), "low" to num(b.low), "close" to num(b.close),
                    "volume" to b.volume, "tradingValue" to b.tradingValue,
                )
            }.toTypedArray(),
        )
    }

    fun read(code: String, start: LocalDate, end: LocalDate): List<IndexBar> = jdbc.query(
        """
        SELECT * FROM index_ohlcv
         WHERE index_code = :code AND trade_date BETWEEN :start AND :end
         ORDER BY trade_date
        """,
        mapOf("code" to code, "start" to start, "end" to end),
    ) { rs, _ ->
        IndexBar(
            date = rs.getObject("trade_date", LocalDate::class.java),
            open = rs.double("open"), high = rs.double("high"), low = rs.double("low"), close = rs.double("close"),
            volume = rs.long("volume"), tradingValue = rs.long("trading_value"),
        )
    }

    /** 적재된 첫·마지막 거래일. 없으면 null. */
    fun span(code: String): Pair<LocalDate, LocalDate>? = jdbc.query(
        "SELECT min(trade_date), max(trade_date) FROM index_ohlcv WHERE index_code = :code",
        mapOf("code" to code),
    ) { rs, _ ->
        val min = rs.getObject(1, LocalDate::class.java) ?: return@query null
        min to rs.getObject(2, LocalDate::class.java)
    }.firstOrNull()

    /** 지수 일봉과 확보 구간을 함께 지운다(재적재용). */
    fun delete(code: String) {
        tx.executeWithoutResult {
            jdbc.update("DELETE FROM index_ohlcv WHERE index_code = :code", mapOf("code" to code))
            jdbc.update("DELETE FROM index_ohlcv_coverage WHERE index_code = :code", mapOf("code" to code))
        }
    }

    fun readCoverage(code: String): List<Pair<LocalDate, LocalDate>> = jdbc.query(
        "SELECT covered_from, covered_to FROM index_ohlcv_coverage WHERE index_code = :code ORDER BY covered_from",
        mapOf("code" to code),
    ) { rs, _ -> rs.getObject(1, LocalDate::class.java) to rs.getObject(2, LocalDate::class.java) }

    /**
     * [start, end] 를 확보 구간에 병합한다. 겹치거나 **하루 차로 맞닿은** 구간은 하나로 합친다.
     * 동시 병합이 서로의 결과를 지우지 않게 지수코드 단위 advisory lock 안에서 한다.
     */
    fun mergeCoverage(code: String, start: LocalDate, end: LocalDate) {
        if (end.isBefore(start)) return
        tx.executeWithoutResult {
            jdbc.queryForList("SELECT pg_advisory_xact_lock(hashtext(:code))", mapOf("code" to code))
            val overlapping = jdbc.query(
                """
                SELECT covered_from, covered_to FROM index_ohlcv_coverage
                 WHERE index_code = :code AND covered_to >= :startMinus AND covered_from <= :endPlus
                """,
                mapOf("code" to code, "startMinus" to start.minusDays(1), "endPlus" to end.plusDays(1)),
            ) { rs, _ -> rs.getObject(1, LocalDate::class.java) to rs.getObject(2, LocalDate::class.java) }
            val from = (overlapping.map { it.first } + start).min()
            val to = (overlapping.map { it.second } + end).max()
            if (overlapping.isNotEmpty()) {
                jdbc.update(
                    "DELETE FROM index_ohlcv_coverage WHERE index_code = :code AND covered_from IN (:froms)",
                    mapOf("code" to code, "froms" to overlapping.map { it.first }),
                )
            }
            jdbc.update(
                """
                INSERT INTO index_ohlcv_coverage (index_code, covered_from, covered_to, updated_at)
                VALUES (:code, :from, :to, now())
                ON CONFLICT (index_code, covered_from) DO UPDATE SET covered_to = :to, updated_at = now()
                """,
                mapOf("code" to code, "from" to from, "to" to to),
            )
        }
    }
}
