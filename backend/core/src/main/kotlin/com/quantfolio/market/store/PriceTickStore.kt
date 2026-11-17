package com.quantfolio.market.store

import com.quantfolio.quant.Series
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.sql.Timestamp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** 종목 일봉 한 개. 백테스트·차트는 price_ticks 를 단일 출처로 쓴다. */
data class Bar(val time: Instant, val open: Double, val high: Double, val low: Double, val close: Double, val volume: Long) {
    /** 봉 시각 → 달력 날짜(UTC 기준). */
    val date: LocalDate get() = time.atZone(ZoneOffset.UTC).toLocalDate()
}

/** 일봉 → 종가 Series(인덱스 [Bar.date]). */
fun List<Bar>.closeSeries(): Series = Series(map { it.date }, DoubleArray(size) { this[it].close })

@Component
class PriceTickStore(private val jdbc: NamedParameterJdbcTemplate) {
    /** 같은 (time, symbol) 은 새 값으로 덮어쓴다(소스가 수정주가를 갱신하므로). 적재 행 수 반환. */
    fun upsert(symbol: String, bars: List<Bar>): Int {
        if (bars.isEmpty()) return 0
        jdbc.batchUpdate(
            """
            INSERT INTO price_ticks (time, symbol, open, high, low, close, volume)
            VALUES (:time, :symbol, :open, :high, :low, :close, :volume)
            ON CONFLICT (time, symbol) DO UPDATE SET
              open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume
            """,
            bars.map { b ->
                mapOf(
                    "time" to Timestamp.from(b.time), "symbol" to symbol,
                    "open" to BigDecimal(b.open.toString()), "high" to BigDecimal(b.high.toString()),
                    "low" to BigDecimal(b.low.toString()), "close" to BigDecimal(b.close.toString()),
                    "volume" to b.volume,
                )
            }.toTypedArray(),
        )
        return bars.size
    }

    /** [start, end] 구간 일봉, 시간순. */
    fun bars(symbol: String, start: Instant, end: Instant): List<Bar> = jdbc.query(
        """
        SELECT time, open, high, low, close, volume FROM price_ticks
         WHERE symbol = :symbol AND time >= :start AND time <= :end
         ORDER BY time
        """,
        mapOf("symbol" to symbol, "start" to Timestamp.from(start), "end" to Timestamp.from(end)),
    ) { rs, _ ->
        Bar(
            time = rs.getTimestamp("time").toInstant(),
            open = rs.getBigDecimal("open").toDouble(), high = rs.getBigDecimal("high").toDouble(),
            low = rs.getBigDecimal("low").toDouble(), close = rs.getBigDecimal("close").toDouble(),
            volume = rs.long("volume") ?: 0L,
        )
    }

    /** [start, end] 안에 실제로 있는 첫·마지막 시각. 없으면 null. (부족 구간 보충 판정용) */
    fun span(symbol: String, start: Instant, end: Instant): Pair<Instant, Instant>? = jdbc.query(
        """
        SELECT min(time), max(time) FROM price_ticks
         WHERE symbol = :symbol AND time >= :start AND time <= :end
        """,
        mapOf("symbol" to symbol, "start" to Timestamp.from(start), "end" to Timestamp.from(end)),
    ) { rs, _ ->
        val min = rs.getTimestamp(1) ?: return@query null
        min.toInstant() to rs.getTimestamp(2).toInstant()
    }.firstOrNull()
}
