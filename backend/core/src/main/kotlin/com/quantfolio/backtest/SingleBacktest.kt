package com.quantfolio.backtest

import com.quantfolio.quant.BoolSeries
import com.quantfolio.quant.Num
import com.quantfolio.quant.Series
import java.time.format.DateTimeFormatter
import kotlin.math.pow

/**
 * 단일종목 백테스트(원본 `backtest/engine.run_backtest`). 체결가는 항상 종가.
 *
 * - fill_mode="next_close"(기본): 신호는 종가(t) 확정 후, 체결은 익일 종가(t+1) — 당일 미래참조 제거.
 * - slippage_bps 편도, slippage_vol_scale>0 이면 최근 20봉 변동성/시점별(확장) 중앙값 비로 봉별 조정(전체구간 중앙값은 미래참조).
 * - 증권거래세는 매도에만 붙지만 vectorbt 는 대칭 수수료만 받으므로 `fees + tax/2` 로 환산한다(왕복 총액 정확).
 */
object SingleBacktest {
    private val ISO_SECONDS: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    private fun Map<String, Any?>.num(key: String, default: Double): Double =
        (this[key] ?: default).let { if (it is Number) it.toDouble() else it.toString().toDouble() }

    /** 원본 `float(cfg.get(k, d) or 0.0)` — None·0 이면 0. */
    private fun Map<String, Any?>.numOrZero(key: String, default: Double): Double =
        (if (containsKey(key)) this[key] else default)?.let { (it as Number).toDouble() } ?: 0.0

    fun run(data: PriceData, config: Map<String, Any?>): Map<String, Any?> {
        val cash = config.num("cash", 10_000_000.0)
        val fees = config.num("fees", 0.00015)
        val tax = config.num("tax", 0.0020)
        val effectiveFees = fees + tax / 2.0
        val fillMode = (config["fill_mode"] ?: "next_close").toString()
        val baseSlip = maxOf(0.0, config.numOrZero("slippage_bps", 5.0) / 10_000.0)
        val slipVolScale = maxOf(0.0, config.numOrZero("slippage_vol_scale", 0.0))
        val rfAnnual = config.numOrZero("risk_free_rate", 0.0)
        val rfDaily = (1.0 + rfAnnual).pow(1.0 / 252.0) - 1.0

        // 결측 봉(어느 컬럼이든 NaN)은 제거하고 종가를 따로 뽑는다.
        val clean = dropNa(data)
        val close = clean.close
        val need = Signals.minPeriods(config) + 1
        if (close.size < need) throw SignalConfigError("데이터가 부족합니다 (필요 ${need}봉 이상, 보유 ${close.size}봉).")

        val (entries, exits) = Signals.generate(clean, config)
        val execEntries = if (fillMode == "next_close") entries.shift(1, fill = false) else entries
        val execExits = if (fillMode == "next_close") exits.shift(1, fill = false) else exits

        val slippage = if (baseSlip > 0 && slipVolScale > 0) volSlippage(close, baseSlip, slipVolScale) else DoubleArray(close.size) { baseSlip }

        val slStop = (config["trailing_stop_pct"] ?: config["stop_loss_pct"])?.let { (it as Number).toDouble() }?.takeIf { it != 0.0 }
        val slTrail = config["trailing_stop_pct"] != null
        val tpStop = config["take_profit_pct"]?.let { (it as Number).toDouble() }

        val r = FromSignals.simulate(
            close.values, execEntries.values, execExits.values,
            FromSignals.Params(cash, effectiveFees, slippage, slStop, slTrail, tpStop),
        )
        val equity = close.index.indices.mapNotNull { i -> Num.safe(r.value[i])?.let { mapOf("t" to iso(close, i), "v" to it) } }
        val markers = (markersOf(close, execEntries, "buy") + markersOf(close, execExits, "sell")).sortedBy { it["t"] as String }
        return linkedMapOf(
            "total_return" to Num.safe(r.totalReturn()),
            "mdd" to Num.safe(r.maxDrawdown()),
            "sharpe" to Num.safe(r.sharpe(rfDaily)),
            "sortino" to Num.safe(r.sortino(rfDaily)),
            "win_rate" to r.winRate()?.let { Num.safe(it) },
            "num_trades" to r.trades.size,
            "equity_curve" to equity,
            "markers" to markers,
        )
    }

    private fun iso(close: Series, i: Int) = close.index[i].atStartOfDay().format(ISO_SECONDS)

    private fun markersOf(close: Series, mask: BoolSeries, type: String) =
        close.index.indices.filter { mask[it] }.map { mapOf("t" to iso(close, it), "type" to type, "price" to Num.safe(close[it])) }

    private fun dropNa(data: PriceData): PriceData {
        val cols = listOfNotNull(data.close, data.open, data.high, data.low, data.volume)
        val keep = data.close.index.indices.filter { i -> cols.all { !it[i].isNaN() } }
        fun sub(s: Series?) = s?.let { Series(keep.map { i -> it.index[i] }, DoubleArray(keep.size) { k -> it[keep[k]] }) }
        return if (data.frame) PriceData.ohlcv(sub(data.open), sub(data.high), sub(data.low), sub(data.close)!!, sub(data.volume)) else PriceData.close(sub(data.close)!!)
    }

    /** 변동성 비례 봉별 슬리피지 = base × (1 + scale × (vol/확장중앙값 − 1)), 0 하한, 산출 불가면 base. */
    private fun volSlippage(close: Series, base: Double, scale: Double): DoubleArray {
        val vol = close.pctChange().rolling(20, 10).std()
        val seen = mutableListOf<Double>()
        return DoubleArray(close.size) { i ->
            if (!vol[i].isNaN()) seen += vol[i]
            val med = if (seen.size >= 10) seen.sorted().let { s -> if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2 } else Double.NaN
            val ratio = (vol[i] / med).let { if (it.isInfinite()) Double.NaN else it }
            val slip = maxOf(0.0, base * (1.0 + scale * (ratio - 1.0)))
            if (slip.isNaN()) base else slip
        }
    }
}
