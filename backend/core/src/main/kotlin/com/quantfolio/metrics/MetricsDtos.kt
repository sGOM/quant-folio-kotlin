package com.quantfolio.metrics

import com.fasterxml.jackson.annotation.JsonProperty
import java.time.LocalDate

// 지표 화면 응답(원본 schemas/metrics.py). 결측은 null(0 sentinel 아님).

data class SectorsOut(val asOf: LocalDate, val items: List<SectorMetric>)

data class StockMetric(
    val code: String,
    val name: String,
    val market: String,
    val price: Long? = null,
    val changeRate: Double? = null,
    val marketCap: Long? = null,
    @JsonProperty("avg_value_20") val avgValue20: Double? = null,
    val per: Double? = null,
    val pbr: Double? = null,
    val div: Double? = null,
    @JsonProperty("mom_1m") val mom1m: Double? = null,
    @JsonProperty("mom_3m") val mom3m: Double? = null,
    @JsonProperty("mom_6m") val mom6m: Double? = null,
    @JsonProperty("mom_12_1") val mom121: Double? = null,
    @JsonProperty("high_52w_ratio") val high52wRatio: Double? = null,
    @JsonProperty("rsi14") val rsi14: Double? = null,
    val volAnn: Double? = null,
    @JsonProperty("mdd_252") val mdd252: Double? = null,
    val trendAligned: Boolean? = null,
    @JsonProperty("above_sma200") val aboveSma200: Boolean? = null,
    val score: Double? = null,
    val scoreValue: Double? = null,
    val scoreMomentum: Double? = null,
    val scoreLowvol: Double? = null,
)

data class StocksOut(val asOf: LocalDate, val count: Int, val items: List<StockMetric>)

data class PanicSignal(
    val key: String,
    val label: String,
    val value: Double? = null,
    val subscore: Double? = null,
    val weight: Double,
    val warn: Double? = null,
    val panic: Double? = null,
)

data class PanicMarket(
    val market: String,
    val score: Double,
    val level: String,
    val gated: Boolean,
    val hardTrigger: Boolean,
    val priceSub: Double? = null,
    val breadthSub: Double? = null,
    @JsonProperty("dd60") val dd60: Double? = null,
    val universe: Int,
    val signals: List<PanicSignal>,
    val caveats: List<String> = emptyList(),
)

data class PanicOut(val asOf: LocalDate, val items: List<PanicMarket>, val unavailable: List<String> = emptyList(), val caveats: List<String> = emptyList())

/** 패닉 롤링 시계열 한 행(백테스트 패닉 오버레이 입력, 원본 compute_panic_series). */
data class PanicDay(val date: LocalDate, val score: Double, val level: String, val gated: Boolean, val hardTrigger: Boolean, val close: Double?, val low: Double?, val dd60: Double?)

data class VulnerabilityOut(
    val asOf: LocalDate,
    val score: Double?,
    val exposureScale: Double,
    val signals: Map<String, Double?>,
    val subscores: Map<String, Double?>,
)
