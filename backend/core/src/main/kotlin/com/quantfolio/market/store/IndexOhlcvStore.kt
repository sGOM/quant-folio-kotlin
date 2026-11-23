package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
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

/** 지수 일봉(index_ohlcv). 옛 확보 구간 테이블 index_ohlcv_coverage 는 남아 있지만 쓰지 않는다. */
@Component
class IndexOhlcvStore(private val jdbc: NamedParameterJdbcTemplate) {
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

    /** [day] 에 행이 있고 [prefix] 로 시작하는 지수 키와 그날 이름, 키 오름차순. */
    fun keys(prefix: String, day: LocalDate): List<Pair<String, String?>> = jdbc.query(
        "SELECT index_code, index_name FROM index_ohlcv WHERE trade_date = :day AND starts_with(index_code, :prefix)",
        mapOf("prefix" to prefix, "day" to day),
    ) { rs, _ -> rs.getString(1) to rs.getString(2) }.sortedBy { it.first }

    /** 지수 일봉을 지운다(재적재·테스트 준비용). */
    fun delete(code: String) {
        jdbc.update("DELETE FROM index_ohlcv WHERE index_code = :code", mapOf("code" to code))
    }
}
