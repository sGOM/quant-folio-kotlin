package com.quantfolio.backtest

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sign
import kotlin.math.sqrt

/**
 * 원본이 쓰던 vectorbt 0.27.2 `Portfolio.from_signals` 의 **사용 부분집합** 재현(정답지 `engine.json`).
 * 원본 호출: 단일 종목, 롱 전용, size=∞(가용 현금 전부·소수 주식), accumulate=False, 체결가=종가, freq=1D.
 *
 * vectorbt 기본값 그대로:
 * - 같은 봉 진입·청산 충돌은 **둘 다 무시**(upon_long_conflict='ignore'), 보유 중 진입·미보유 청산도 무시.
 * - 손절·익절은 봉 시작에 먼저 판정한다. open/high/low 를 주지 않으므로 셋 다 종가 — 걸리면 **그 봉 종가에, 슬리피지 없이**
 *   (stop_exit_price='stoplimit') 청산되고, 그 봉의 신호는 보지 않는다. 기준가는 진입 봉 종가(stop_entry_price='close').
 *   트레일링은 판정 **후** 고점(=종가)으로 기준가를 올린다.
 * - 수수료는 체결대금 비례, 슬리피지는 매수 ×(1+s)·매도 ×(1−s).
 * - 지표: 일간수익률 r[0] = value[0]/init − 1, 연환산 계수 365(year_freq='365 days'), 표준편차 ddof=1.
 */
object FromSignals {
    private const val REL_TOL = 1e-9
    private const val ABS_TOL = 1e-12
    private const val MIN_SIZE = 1e-8
    private const val ANN_FACTOR = 365.0

    data class Params(
        val initCash: Double,
        val fees: Double,
        val slippage: DoubleArray,
        val slStop: Double? = null,
        val slTrail: Boolean = false,
        val tpStop: Double? = null,
    )

    data class Order(val idx: Int, val size: Double, val price: Double, val fees: Double, val buy: Boolean)

    data class Trade(val size: Double, val entryPrice: Double, val entryFees: Double, val exitPrice: Double, val exitFees: Double, val open: Boolean) {
        val pnl: Double get() = size * (exitPrice - entryPrice) - entryFees - exitFees
    }

    class Result(val initCash: Double, val value: DoubleArray, val orders: List<Order>, val trades: List<Trade>) {
        val returns: DoubleArray by lazy {
            var input = initCash
            DoubleArray(value.size) { i -> ret(input, value[i]).also { input = value[i] } }
        }

        fun totalReturn(): Double = ret(initCash, value.last())

        fun maxDrawdown(): Double {
            var cum = 100.0
            var peak = Double.NEGATIVE_INFINITY
            var mdd = Double.POSITIVE_INFINITY
            for (r in returns) {
                if (!r.isNaN()) cum *= r + 1
                if (cum > peak) peak = cum
                mdd = minOf(mdd, cum / peak - 1)
            }
            return mdd
        }

        fun sharpe(riskFree: Double): Double {
            if (returns.size < 2) return Double.NaN
            val adj = returns.map { it - riskFree }.filter { !it.isNaN() }
            val mean = adj.average()
            val std = nanStd(adj, ddof = 1)
            if (std == 0.0) return Double.POSITIVE_INFINITY
            return mean / std * sqrt(ANN_FACTOR)
        }

        fun sortino(requiredReturn: Double): Double {
            if (returns.size < 2) return Double.NaN
            val adj = returns.map { it - requiredReturn }.filter { !it.isNaN() }
            val annualized = adj.average() * ANN_FACTOR
            val downside = sqrt(adj.map { minOf(it, 0.0) }.map { it * it }.average()) * sqrt(ANN_FACTOR)
            if (downside == 0.0) return Double.POSITIVE_INFINITY
            return annualized / downside
        }

        /** 이긴 거래(pnl>0) / 전체 거래(미청산 포함). 거래가 없으면 null. */
        fun winRate(): Double? = if (trades.isEmpty()) null else trades.count { it.pnl > 0 }.toDouble() / trades.size
    }

    @Suppress("CyclomaticComplexMethod", "LongMethod")
    fun simulate(close: DoubleArray, entries: BooleanArray, exits: BooleanArray, p: Params): Result {
        val useStops = p.slStop != null || p.tpStop != null
        var cash = p.initCash
        var position = 0.0
        var slPrice = Double.NaN
        var slStop = Double.NaN
        var slTrail = false
        var tpPrice = Double.NaN
        var tpStop = Double.NaN
        val value = DoubleArray(close.size)
        val orders = mutableListOf<Order>()

        for (i in close.indices) {
            var price = close[i]
            var slip = p.slippage[i]
            var stopPrice = Double.NaN
            if (useStops && (!slStop.isNaN() || !tpStop.isNaN())) {
                val bar = close[i] // open·high·low 미지정 → 모두 종가
                if (!slStop.isNaN()) stopPrice = stopPrice(position, slPrice, slStop, bar, hitBelow = true)
                if (stopPrice.isNaN() && !tpStop.isNaN()) stopPrice = stopPrice(position, tpPrice, tpStop, bar, hitBelow = false)
                if (!slStop.isNaN() && slTrail && position > 0 && bar > slPrice) slPrice = bar
            }
            var longEntry: Boolean
            var longExit: Boolean
            if (useStops && !stopPrice.isNaN()) {
                longEntry = false
                longExit = position > 0
                price = stopPrice
                slip = 0.0
            } else {
                longEntry = entries[i]
                longExit = exits[i]
                if (longEntry && longExit) {
                    longEntry = false
                    longExit = false
                }
            }
            val size = when {
                position > 0 && longExit -> -position
                position > 0 -> 0.0
                longEntry -> Double.POSITIVE_INFINITY
                else -> 0.0
            }
            val prevPosition = position
            var filled = false
            if (size > 0) {
                // 매수(가용 현금 전부)
                if (cash != 0.0) {
                    val adj = price * (1 + slip)
                    val maxReqCash = cash / (1 + p.fees)
                    if (maxReqCash > 0) {
                        val finalSize = maxReqCash / adj
                        if (!isLess(finalSize, MIN_SIZE)) {
                            orders += Order(i, finalSize, adj, cash - maxReqCash, buy = true)
                            cash = add(cash, -cash)
                            position = add(position, finalSize)
                            filled = true
                        }
                    }
                }
            } else if (size < 0) {
                // 전량 매도
                val adj = price * (1 - slip)
                val sizeLimit = minOf(position, -size)
                if (position != 0.0 && !isClose(sizeLimit, 0.0) && !isLess(sizeLimit, MIN_SIZE)) {
                    val acq = sizeLimit * adj
                    val feesPaid = acq * p.fees
                    val finalAcq = add(acq, -feesPaid)
                    if (finalAcq >= 0) {
                        orders += Order(i, sizeLimit, adj, feesPaid, buy = false)
                        cash += finalAcq
                        position = add(position, -sizeLimit)
                        filled = true
                    }
                }
            }
            if (useStops && filled) {
                if (position == 0.0) {
                    slPrice = Double.NaN; slStop = Double.NaN; slTrail = false; tpPrice = Double.NaN; tpStop = Double.NaN
                } else if (prevPosition == 0.0 || sign(position) != sign(prevPosition)) {
                    slPrice = close[i]; slStop = p.slStop ?: Double.NaN; slTrail = p.slTrail
                    tpPrice = close[i]; tpStop = p.tpStop ?: Double.NaN
                }
            }
            value[i] = cash + position * close[i]
        }
        return Result(p.initCash, value, orders, trades(orders, close.last()))
    }

    /** 진입→청산 쌍이 거래 하나. 끝까지 보유 중이면 마지막 종가로 평가한 미청산 거래(청산 수수료 0). */
    private fun trades(orders: List<Order>, lastClose: Double): List<Trade> {
        val out = mutableListOf<Trade>()
        var entry: Order? = null
        for (o in orders) {
            if (o.buy) {
                entry = o
            } else if (entry != null) {
                out += Trade(entry.size, entry.price, entry.fees, o.price, o.fees, open = false)
                entry = null
            }
        }
        entry?.let { out += Trade(it.size, it.price, it.fees, lastClose, 0.0, open = true) }
        return out
    }

    /** vectorbt get_stop_price_nb(롱): 손절은 기준가×(1−stop) 이하, 익절은 ×(1+stop) 이상이면 그 봉 시가(=종가). */
    private fun stopPrice(position: Double, base: Double, stop: Double, bar: Double, hitBelow: Boolean): Double {
        if (position > 0 && hitBelow) {
            val sp = base * (1 - stop)
            return if (bar <= sp) bar else Double.NaN
        }
        if (position > 0 && !hitBelow) {
            val sp = base * (1 + stop)
            return if (sp <= bar) bar else Double.NaN
        }
        return Double.NaN
    }

    private fun ret(input: Double, output: Double): Double {
        if (input == 0.0) return if (output == 0.0) 0.0 else Double.POSITIVE_INFINITY * sign(output)
        val r = (output - input) / input
        return if (input < 0) -r else r
    }

    private fun nanStd(a: List<Double>, ddof: Int): Double {
        val n = a.size
        if (n - ddof <= 0) return Double.NaN
        val mean = a.average()
        val variance = a.sumOf { (it - mean) * (it - mean) } / n
        return sqrt(variance * n / (n - ddof))
    }

    private fun isClose(a: Double, b: Double): Boolean {
        if (a.isNaN() || b.isNaN() || a.isInfinite() || b.isInfinite()) return false
        if (a == b) return true
        return abs(a - b) <= max(REL_TOL * max(abs(a), abs(b)), ABS_TOL)
    }

    private fun isLess(a: Double, b: Double) = !isClose(a, b) && a < b

    private fun add(a: Double, b: Double): Double {
        val zero = if (sign(a) != sign(b)) isClose(abs(a), abs(b)) else isClose(a + b, 0.0)
        return if (zero) 0.0 else a + b
    }
}
