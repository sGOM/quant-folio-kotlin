package com.quantfolio.market.store

import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.time.LocalDate

/** stock_daily_snapshots 한 칸(거래일 × 종목). 펀더멘털·시가총액·전종목 OHLCV 가 같은 행을 서로 다른 시점에 채운다. */
data class StockDaily(
    val symbol: String,
    val market: String? = null,
    val per: Double? = null,
    val pbr: Double? = null,
    val div: Double? = null,
    val marketCap: Long? = null,
    val shares: Long? = null,
    val open: Double? = null,
    val high: Double? = null,
    val low: Double? = null,
    val close: Double? = null,
    val volume: Long? = null,
    val tradingValue: Long? = null,
    val changePct: Double? = null,
)

@Component
class StockDailyStore(private val jdbc: NamedParameterJdbcTemplate) {
    /** upsert 는 **들어온 값이 null 이면 기존값을 보존**한다 — 시총만 적재된 행을 펀더멘털 적재가 null 로 덮지 않게. */
    fun write(day: LocalDate, rows: List<StockDaily>) {
        val unique = lastPerSymbol(rows) { it.symbol }
        if (unique.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO stock_daily_snapshots
              (trade_date, symbol, market, per, pbr, div, market_cap, shares, open, high, low, close, volume, trading_value, change_pct, created_at)
            VALUES (:day, :symbol, :market, :per, :pbr, :div, :marketCap, :shares, :open, :high, :low, :close, :volume, :tradingValue, :changePct, now())
            ON CONFLICT (trade_date, symbol) DO UPDATE SET
              market = COALESCE(EXCLUDED.market, stock_daily_snapshots.market),
              per = COALESCE(EXCLUDED.per, stock_daily_snapshots.per),
              pbr = COALESCE(EXCLUDED.pbr, stock_daily_snapshots.pbr),
              div = COALESCE(EXCLUDED.div, stock_daily_snapshots.div),
              market_cap = COALESCE(EXCLUDED.market_cap, stock_daily_snapshots.market_cap),
              shares = COALESCE(EXCLUDED.shares, stock_daily_snapshots.shares),
              open = COALESCE(EXCLUDED.open, stock_daily_snapshots.open),
              high = COALESCE(EXCLUDED.high, stock_daily_snapshots.high),
              low = COALESCE(EXCLUDED.low, stock_daily_snapshots.low),
              close = COALESCE(EXCLUDED.close, stock_daily_snapshots.close),
              volume = COALESCE(EXCLUDED.volume, stock_daily_snapshots.volume),
              trading_value = COALESCE(EXCLUDED.trading_value, stock_daily_snapshots.trading_value),
              change_pct = COALESCE(EXCLUDED.change_pct, stock_daily_snapshots.change_pct)
            """,
            unique.map { r ->
                mapOf(
                    "day" to day, "symbol" to padSymbol(r.symbol), "market" to r.market,
                    "per" to num(r.per), "pbr" to num(r.pbr), "div" to num(r.div),
                    "marketCap" to r.marketCap, "shares" to r.shares,
                    "open" to num(r.open), "high" to num(r.high), "low" to num(r.low), "close" to num(r.close),
                    "volume" to r.volume, "tradingValue" to r.tradingValue, "changePct" to num(r.changePct),
                )
            }.toTypedArray(),
        )
    }

    /**
     * @param markets null 이 아니면 그 시장 행만. KOSPI·KOSDAQ 행이 같은 키공간에 섞여 있어
     *   단일시장 조회는 반드시 이 필터를 건다(안 그러면 전 시장 적재 이후 결과가 오염된다).
     */
    fun read(day: LocalDate, markets: List<String>? = null): List<StockDaily> {
        val filter = if (markets.isNullOrEmpty()) "" else " AND market IN (:markets)"
        return jdbc.query(
            "SELECT * FROM stock_daily_snapshots WHERE trade_date = :day$filter ORDER BY symbol",
            mapOf("day" to day, "markets" to markets),
        ) { rs, _ ->
            StockDaily(
                symbol = rs.getString("symbol"), market = rs.getString("market"),
                per = rs.double("per"), pbr = rs.double("pbr"), div = rs.double("div"),
                marketCap = rs.long("market_cap"), shares = rs.long("shares"),
                open = rs.double("open"), high = rs.double("high"), low = rs.double("low"), close = rs.double("close"),
                volume = rs.long("volume"), tradingValue = rs.long("trading_value"), changePct = rs.double("change_pct"),
            )
        }
    }

    fun delete(day: LocalDate) {
        jdbc.update("DELETE FROM stock_daily_snapshots WHERE trade_date = :day", mapOf("day" to day))
    }
}
