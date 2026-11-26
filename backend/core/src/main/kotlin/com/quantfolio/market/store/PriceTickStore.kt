package com.quantfolio.market.store

import com.quantfolio.quant.Series
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
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
class PriceTickStore(private val jdbc: NamedParameterJdbcTemplate, private val tx: TransactionTemplate) {
    /** 같은 (time, symbol) 은 새 값으로 덮어쓴다(소스가 수정주가를 갱신하므로). 적재 행 수 반환. */
    fun upsert(symbol: String, bars: List<Bar>): Int = upsertMany(bars.map { symbol to it })

    /** 여러 종목의 봉을 한 배치로 upsert. */
    fun upsertMany(items: List<Pair<String, Bar>>): Int {
        if (items.isEmpty()) return 0
        jdbc.batchUpdate(
            """
            INSERT INTO price_ticks (time, symbol, open, high, low, close, volume)
            VALUES (:time, :symbol, :open, :high, :low, :close, :volume)
            ON CONFLICT (time, symbol) DO UPDATE SET
              open = EXCLUDED.open, high = EXCLUDED.high, low = EXCLUDED.low, close = EXCLUDED.close, volume = EXCLUDED.volume
            """,
            items.map { (symbol, b) ->
                mapOf(
                    "time" to Timestamp.from(b.time), "symbol" to symbol,
                    "open" to BigDecimal(b.open.toString()), "high" to BigDecimal(b.high.toString()),
                    "low" to BigDecimal(b.low.toString()), "close" to BigDecimal(b.close.toString()),
                    "volume" to b.volume,
                )
            }.toTypedArray(),
        )
        return items.size
    }

    /** [bars] 의 첫 시각 이후 이 종목 봉을 지우고 [bars] 로 바꾼다(수정주가 재계산용). 그 앞 봉은 건드리지 않는다. */
    fun replaceFrom(symbol: String, bars: List<Bar>) {
        if (bars.isEmpty()) return
        tx.executeWithoutResult {
            jdbc.update("DELETE FROM price_ticks WHERE symbol = :symbol AND time >= :from", mapOf("symbol" to symbol, "from" to Timestamp.from(bars.first().time)))
            upsert(symbol, bars)
        }
    }

    /** 종목별 [before] 이전의 가장 최근 봉(시각, 종가). 봉이 없는 종목은 결과에 없다. */
    fun lastBars(symbols: List<String>, before: Instant): Map<String, Pair<Instant, Double>> {
        if (symbols.isEmpty()) return emptyMap()
        return jdbc.query(
            // 종목마다 (symbol, time) 인덱스로 1행만 본다. DISTINCT ON 은 전 기간을 훑어 하루 적재마다 26초, 병렬 실행 시 DB OOM(2026-11-25).
            """
            SELECT s.symbol, b.time, b.close FROM (SELECT unnest(ARRAY[:symbols]) AS symbol) s
            CROSS JOIN LATERAL (SELECT time, close FROM price_ticks p WHERE p.symbol = s.symbol AND p.time < :before ORDER BY time DESC LIMIT 1) b
            """,
            mapOf("symbols" to symbols, "before" to Timestamp.from(before)),
        ) { rs, _ -> rs.getString(1) to (rs.getTimestamp(2).toInstant() to rs.getBigDecimal(3).toDouble()) }.toMap()
    }

    /** [symbols] 중 [after] 보다 늦은 봉이 이미 있는 종목. */
    fun symbolsWithBarsAfter(symbols: List<String>, after: Instant): Set<String> {
        if (symbols.isEmpty()) return emptySet()
        return jdbc.query(
            "SELECT s.symbol FROM (SELECT unnest(ARRAY[:symbols]) AS symbol) s WHERE EXISTS (SELECT 1 FROM price_ticks p WHERE p.symbol = s.symbol AND p.time > :after)",
            mapOf("symbols" to symbols, "after" to Timestamp.from(after)),
        ) { rs, _ -> rs.getString(1) }.toSet()
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
