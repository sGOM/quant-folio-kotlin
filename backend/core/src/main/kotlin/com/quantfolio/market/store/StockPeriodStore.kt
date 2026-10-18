package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/** stock_period_stats 한 칸 — (기간, 투자자군, 종목). 기간등락률·투자자별 순매수가 채운다. */
data class StockPeriod(
    val symbol: String,
    val market: String? = null,
    val name: String? = null,
    val changePct: Double? = null,
    val open: Double? = null,
    val close: Double? = null,
    val volume: Long? = null,
    val tradingValue: Long? = null,
    val netBuyValue: Double? = null,
)

@Component
class StockPeriodStore(private val jdbc: NamedParameterJdbcTemplate) {
    /** 들어온 null 은 기존값 보존(stock_daily_snapshots 와 같은 규칙). */
    fun write(start: LocalDate, end: LocalDate, investors: String, rows: List<StockPeriod>) {
        val unique = lastPerSymbol(rows) { it.symbol }
        if (unique.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO stock_period_stats
              (start_date, end_date, investors, symbol, market, name, change_pct, open, close, volume, trading_value, net_buy_value, created_at)
            VALUES (:start, :end, :investors, :symbol, :market, :name, :changePct, :open, :close, :volume, :tradingValue, :netBuyValue, now())
            ON CONFLICT (start_date, end_date, investors, symbol) DO UPDATE SET
              market = COALESCE(EXCLUDED.market, stock_period_stats.market),
              name = COALESCE(EXCLUDED.name, stock_period_stats.name),
              change_pct = COALESCE(EXCLUDED.change_pct, stock_period_stats.change_pct),
              open = COALESCE(EXCLUDED.open, stock_period_stats.open),
              close = COALESCE(EXCLUDED.close, stock_period_stats.close),
              volume = COALESCE(EXCLUDED.volume, stock_period_stats.volume),
              trading_value = COALESCE(EXCLUDED.trading_value, stock_period_stats.trading_value),
              net_buy_value = COALESCE(EXCLUDED.net_buy_value, stock_period_stats.net_buy_value)
            """,
            unique.map { r ->
                mapOf(
                    "start" to start, "end" to end, "investors" to investors, "symbol" to padSymbol(r.symbol),
                    "market" to r.market, "name" to r.name, "changePct" to num(r.changePct),
                    "open" to num(r.open), "close" to num(r.close), "volume" to r.volume,
                    "tradingValue" to r.tradingValue, "netBuyValue" to num(r.netBuyValue),
                )
            }.toTypedArray(),
        )
    }

    fun read(start: LocalDate, end: LocalDate, investors: String, markets: List<String>? = null): List<StockPeriod> {
        val filter = if (markets.isNullOrEmpty()) "" else " AND market IN (:markets)"
        return jdbc.query(
            """
            SELECT * FROM stock_period_stats
             WHERE start_date = :start AND end_date = :end AND investors = :investors$filter
             ORDER BY symbol
            """,
            mapOf("start" to start, "end" to end, "investors" to investors, "markets" to markets),
        ) { rs, _ ->
            StockPeriod(
                symbol = rs.getString("symbol"), market = rs.getString("market"), name = rs.getString("name"),
                changePct = rs.double("change_pct"), open = rs.double("open"), close = rs.double("close"),
                volume = rs.long("volume"), tradingValue = rs.long("trading_value"), netBuyValue = rs.double("net_buy_value"),
            )
        }
    }

    fun delete(start: LocalDate, end: LocalDate, investors: String) {
        jdbc.update(
            "DELETE FROM stock_period_stats WHERE start_date = :start AND end_date = :end AND investors = :investors",
            mapOf("start" to start, "end" to end, "investors" to investors),
        )
    }
}
