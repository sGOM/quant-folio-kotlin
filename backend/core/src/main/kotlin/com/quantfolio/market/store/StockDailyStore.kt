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
    val prevDiff: Double? = null,
    val stockKind: String? = null,
)

/** [StockDailyStore.capsWithKind] 한 행: 시총과, 그 종목의 기준일 이하 최신 주권 종류(없으면 null). */
data class CapKind(val symbol: String, val marketCap: Long, val stockKind: String?)

@Component
class StockDailyStore(private val jdbc: NamedParameterJdbcTemplate) {
    /** upsert 는 **들어온 값이 null 이면 기존값을 보존**한다 — 시총만 적재된 행을 펀더멘털 적재가 null 로 덮지 않게. */
    fun write(day: LocalDate, rows: List<StockDaily>) {
        val unique = lastPerSymbol(rows) { it.symbol }
        if (unique.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO stock_daily_snapshots
              (trade_date, symbol, market, per, pbr, div, market_cap, shares, open, high, low, close, volume, trading_value, change_pct, prev_diff, stock_kind, created_at)
            VALUES (:day, :symbol, :market, :per, :pbr, :div, :marketCap, :shares, :open, :high, :low, :close, :volume, :tradingValue, :changePct, :prevDiff, :stockKind, now())
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
              change_pct = COALESCE(EXCLUDED.change_pct, stock_daily_snapshots.change_pct),
              prev_diff = COALESCE(EXCLUDED.prev_diff, stock_daily_snapshots.prev_diff),
              stock_kind = COALESCE(EXCLUDED.stock_kind, stock_daily_snapshots.stock_kind)
            """,
            unique.map { r ->
                mapOf(
                    "day" to day, "symbol" to padSymbol(r.symbol), "market" to r.market,
                    "per" to num(r.per), "pbr" to num(r.pbr), "div" to num(r.div),
                    "marketCap" to r.marketCap, "shares" to r.shares,
                    "open" to num(r.open), "high" to num(r.high), "low" to num(r.low), "close" to num(r.close),
                    "volume" to r.volume, "tradingValue" to r.tradingValue, "changePct" to num(r.changePct),
                    "prevDiff" to num(r.prevDiff), "stockKind" to r.stockKind,
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
                prevDiff = rs.double("prev_diff"), stockKind = rs.getString("stock_kind"),
            )
        }
    }

    /** 종목별 (asOf - lookbackDays, asOf] 안의 가장 최근 행. 미래 행은 보지 않는다. 구간에 행이 없는 종목은 결과에 없다. */
    fun latestUpTo(symbols: List<String>, asOf: LocalDate, lookbackDays: Long): List<StockDaily> {
        if (symbols.isEmpty()) return emptyList()
        return jdbc.query(
            """
            SELECT DISTINCT ON (symbol) * FROM stock_daily_snapshots
             WHERE symbol IN (:symbols) AND trade_date <= :asOf AND trade_date > :from
             ORDER BY symbol, trade_date DESC
            """,
            mapOf("symbols" to symbols.map(::padSymbol), "asOf" to asOf, "from" to asOf.minusDays(lookbackDays)),
        ) { rs, _ ->
            StockDaily(
                symbol = rs.getString("symbol"), market = rs.getString("market"),
                marketCap = rs.long("market_cap"), close = rs.double("close"),
            )
        }
    }

    /** 한 종목의 행(날짜 오름차순), 기본은 전 기간. 수정주가 재계산용. */
    fun readSymbol(symbol: String, from: LocalDate = LocalDate.of(1900, 1, 1), to: LocalDate = LocalDate.of(9999, 12, 31)): List<Pair<LocalDate, StockDaily>> = jdbc.query(
        "SELECT * FROM stock_daily_snapshots WHERE symbol = :symbol AND trade_date BETWEEN :from AND :to ORDER BY trade_date",
        mapOf("symbol" to padSymbol(symbol), "from" to from, "to" to to),
    ) { rs, _ ->
        rs.getObject("trade_date", LocalDate::class.java) to StockDaily(
            symbol = rs.getString("symbol"), market = rs.getString("market"),
            open = rs.double("open"), high = rs.double("high"), low = rs.double("low"), close = rs.double("close"),
            volume = rs.long("volume"), prevDiff = rs.double("prev_diff"), stockKind = rs.getString("stock_kind"),
        )
    }

    /** [from, to] 안에 주권 종류가 적재된 시장들. */
    fun kindMarkets(from: LocalDate, to: LocalDate): Set<String> = jdbc.query(
        "SELECT DISTINCT market FROM stock_daily_snapshots WHERE stock_kind IS NOT NULL AND trade_date BETWEEN :from AND :to AND market IS NOT NULL",
        mapOf("from" to from, "to" to to),
    ) { rs, _ -> rs.getString(1) }.toSet()

    /** 어느 종목이든 행이 있는 거래일(오름차순). */
    fun symbols(): List<String> = jdbc.query(
        "SELECT DISTINCT symbol FROM stock_daily_snapshots ORDER BY symbol", emptyMap<String, Any>(),
    ) { rs, _ -> rs.getString(1) }

    fun tradeDates(): List<LocalDate> = jdbc.query(
        "SELECT DISTINCT trade_date FROM stock_daily_snapshots ORDER BY trade_date", emptyMap<String, Any>(),
    ) { rs, _ -> rs.getObject(1, LocalDate::class.java) }

    /** 종목별 **최신** 주권 종류가 [kind] 인 [market] 종목(코드 오름차순). */
    fun symbolsWithKind(market: String, kind: String): List<String> = jdbc.query(
        """
        SELECT symbol FROM (
          SELECT DISTINCT ON (symbol) symbol, market, stock_kind FROM stock_daily_snapshots
           WHERE stock_kind IS NOT NULL ORDER BY symbol, trade_date DESC) t
         WHERE market = :market AND stock_kind = :kind ORDER BY symbol
        """,
        mapOf("market" to market, "kind" to kind),
    ) { rs, _ -> rs.getString(1) }

    /** [day] 의 [market] 시총이 있는 종목과 각 종목의 [day] 이하 최신 주권 종류(한 번의 조회). */
    fun capsWithKind(day: LocalDate, market: String): List<CapKind> = jdbc.query(
        """
        SELECT s.symbol, s.market_cap, k.stock_kind FROM stock_daily_snapshots s
          LEFT JOIN LATERAL (
            SELECT stock_kind FROM stock_daily_snapshots
             WHERE symbol = s.symbol AND stock_kind IS NOT NULL AND trade_date <= :day
             ORDER BY trade_date DESC LIMIT 1) k ON true
         WHERE s.trade_date = :day AND s.market = :market AND s.market_cap IS NOT NULL
        """,
        mapOf("day" to day, "market" to market),
    ) { rs, _ -> CapKind(rs.getString("symbol"), rs.getLong("market_cap"), rs.getString("stock_kind")) }

    /** [from, to] 의 [market] 시총 행들과 각 행 날짜 기준 종목별 최신 주권 종류(이월). 구간 앞의 기본정보 적재일도 이월에 쓴다 — 창 함수는 같은 단계의 WHERE 뒤에 돌므로 구간 필터는 바깥에서. */
    fun capsWithKindRange(from: LocalDate, to: LocalDate, market: String): List<Pair<LocalDate, CapKind>> = jdbc.query(
        """
        SELECT trade_date, symbol, market_cap, kind FROM (
          SELECT trade_date, symbol, market, market_cap, max(stock_kind) OVER (PARTITION BY symbol, grp) AS kind FROM (
            SELECT trade_date, symbol, market, market_cap, stock_kind,
                   count(stock_kind) OVER (PARTITION BY symbol ORDER BY trade_date) AS grp
              FROM stock_daily_snapshots WHERE trade_date <= :to) t) k
         WHERE trade_date >= :from AND market = :market AND market_cap IS NOT NULL
        """,
        mapOf("from" to from, "to" to to, "market" to market),
    ) { rs, _ -> rs.getObject("trade_date", LocalDate::class.java) to CapKind(rs.getString("symbol"), rs.getLong("market_cap"), rs.getString("kind")) }

    fun delete(day: LocalDate) {
        jdbc.update("DELETE FROM stock_daily_snapshots WHERE trade_date = :day", mapOf("day" to day))
    }
}
