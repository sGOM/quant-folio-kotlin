package com.quantfolio.market.store

/** 기간 등락 한 칸(종목) — `SnapshotSource.priceChange` 결과. */
data class StockPeriod(
    val symbol: String,
    val market: String? = null,
    val name: String? = null,
    val changePct: Double? = null,
    val open: Double? = null,
    val close: Double? = null,
    val volume: Long? = null,
    val tradingValue: Long? = null,
)
