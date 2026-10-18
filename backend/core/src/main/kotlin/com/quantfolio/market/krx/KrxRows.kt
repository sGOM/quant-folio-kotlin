package com.quantfolio.market.krx

import java.time.LocalDate

/** pykrx `get_market_ohlcv(date, market)` 한 행. */
data class TickerOhlcv(
    val symbol: String,
    val open: Long,
    val high: Long,
    val low: Long,
    val close: Long,
    val volume: Long,
    val tradingValue: Long,
    /** float32 정밀도(원본 numpy astype float32) */
    val changePct: Double,
    val marketCap: Long,
)

/** pykrx `get_market_cap(date, market)` 한 행. */
data class TickerCap(val symbol: String, val close: Long, val marketCap: Long, val volume: Long, val tradingValue: Long, val shares: Long)

/** pykrx `get_market_fundamental(date, market)` 한 행. 값이 없으면 0(원본 그대로 — 결측 처리는 호출자). */
data class TickerFundamental(val symbol: String, val bps: Long, val per: Double, val pbr: Double, val eps: Long, val div: Double, val dps: Long)

/** pykrx `get_market_price_change(from, to, market)` 한 행. 기간 중 상폐 종목은 종가 0·등락률 −100. */
data class TickerPriceChange(
    val symbol: String,
    val name: String,
    val open: Long,
    val close: Long,
    val change: Long,
    val changePct: Double,
    val volume: Long,
    val tradingValue: Long,
)

/** pykrx `get_market_net_purchases_of_equities(from, to, market, investor)` 한 행. */
data class TickerNetPurchase(
    val symbol: String,
    val name: String,
    val sellVolume: Long,
    val buyVolume: Long,
    val netVolume: Long,
    val sellValue: Long,
    val buyValue: Long,
    val netValue: Long,
)

/** pykrx `get_index_ohlcv(from, to, ticker)` 한 행. */
data class IndexDaily(
    val date: LocalDate,
    val open: Double,
    val high: Double,
    val low: Double,
    val close: Double,
    val volume: Long,
    val tradingValue: Long,
    val marketCap: Long,
)
