package com.quantfolio.backtest

import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Series
import java.time.LocalDate

private val PANIC_RANK = mapOf("normal" to 0, "caution" to 1, "warning" to 2, "panic" to 3)

internal data class PanicParams(
    val armRank: Int, val armWindow: Int, val holdDays: Int, val profitReclaim: Double, val knifeStop: Double,
    val baseExposure: Double, val panicExposure: Double, val scaleInConfirm: Double, val maRecovery: Int, val eventOnly: Boolean,
)

internal fun panicParams(po: Map<String, Any?>): PanicParams {
    fun f(k: String, d: Double) = po[k]?.let { Cfg.num(it) } ?: d
    return PanicParams(
        PANIC_RANK[(Cfg.or(po, "arm_level", "warning")).toString()] ?: 2,
        Cfg.int(Cfg.or(po, "arm_window", 5)), Cfg.int(Cfg.or(po, "hold_days", 20)),
        f("profit_reclaim_pct", 0.5), f("knife_stop_pct", 0.05), f("base_exposure", 0.70), f("panic_exposure", 1.00),
        f("scale_in_confirm", 0.5), Cfg.int(Cfg.or(po, "ma_recovery_period", 20)), Cfg.truthy(Cfg.get(po, "event_only", false)),
    )
}

/**
 * 패닉 오버레이 상태기계: IDLE →(패닉 수준 도달) ARMED →(반등 확인) 진입 →(칼날·익절·보유기한) IDLE.
 * 진입은 event_only 면 CONFIRMED 한 단계, 아니면 CONFIRMED_HALF →(MA 회복) CONFIRMED_FULL 로 증액.
 * `i` 는 시뮬레이션 일 순번(기한 계산), `di` 는 패널 행 번호(시계열 조회).
 */
internal class PanicOverlay(
    val params: PanicParams,
    private val level: Array<String?>,
    private val hard: BooleanArray,
    private val close: Series,
    private val low: Series,
) {
    private enum class State { IDLE, ARMED, CONFIRMED_HALF, CONFIRMED_FULL, CONFIRMED }

    private val prevClose = close.shift(1)
    private val ma = close.rolling(params.maRecovery, maxOf(5, params.maRecovery / 2)).mean()
    private var state = State.IDLE
    private var armDeadline = -1
    private var capitClose: Double? = null
    private var capitLow: Double? = null
    private var capitDrop: Double? = null
    private var confirmIdx = -1
    var numEvents = 0
        private set

    /** 진입해 있는 동안 — 레짐 청산·정기 리밸런싱을 억제한다. */
    val holding get() = state == State.CONFIRMED_HALF || state == State.CONFIRMED_FULL || state == State.CONFIRMED

    fun reset() {
        state = State.IDLE
        capitClose = null
        capitLow = null
        capitDrop = null
        confirmIdx = -1
    }

    /** 하루치 전이. 매매가 필요하면 결정을, 아니면 null. `targets` 는 노출도 → 목표비중(필요할 때만 호출). */
    fun step(i: Int, di: Int, d: LocalDate, riskOff: Boolean, journal: Journal, targets: (Double) -> Map<String, Double>): Decision? {
        val p = params
        val lv = level[di] ?: "nan"
        val c = close[di]
        val prev = prevClose[di]
        val hasClose = !c.isNaN()
        val hasPrev = !prev.isNaN()
        val cc = capitClose
        val cl = capitLow
        val cd = capitDrop
        when (state) {
            State.IDLE -> if (hasClose && ((PANIC_RANK[lv] ?: 0) >= p.armRank || hard[di])) {
                state = State.ARMED
                armDeadline = i + p.armWindow
                capitClose = c
                capitLow = low[di].takeIf { !it.isNaN() } ?: c
                capitDrop = if (hasPrev) prev - c else null
                journal.marker(d, "panic_arm", "level" to lv)
            }
            State.ARMED -> if (i > armDeadline) {
                reset()
                journal.marker(d, "panic_arm_timeout")
            } else if (hasClose && hasPrev && cc != null && c > cc && c > prev) {
                val exposure = if (p.eventOnly) p.panicExposure else p.baseExposure + p.scaleInConfirm * (p.panicExposure - p.baseExposure)
                val decision = Decision.rebalance("panic_confirm", targets(exposure))
                state = if (p.eventOnly) State.CONFIRMED else State.CONFIRMED_HALF
                confirmIdx = i
                numEvents++
                journal.marker(d, "panic_confirm")
                return decision
            }
            State.CONFIRMED_HALF, State.CONFIRMED_FULL, State.CONFIRMED -> {
                val exitReason = when {
                    hasClose && cl != null && c <= cl * (1.0 - p.knifeStop) -> "panic_exit_knife"
                    hasClose && cc != null && cd != null && cd > 0 && c >= cc + p.profitReclaim * cd -> "panic_exit_profit"
                    confirmIdx >= 0 && i - confirmIdx >= p.holdDays -> "panic_exit_hold"
                    else -> null
                }
                if (exitReason != null) {
                    val decision = if (p.eventOnly || riskOff) Decision.liquidate(exitReason) else Decision.rebalance(exitReason, targets(p.baseExposure))
                    reset()
                    return decision
                }
                if (!p.eventOnly && state == State.CONFIRMED_HALF && hasClose && !ma[di].isNaN() && c > ma[di]) {
                    val decision = Decision.rebalance("panic_scale_full", targets(p.panicExposure))
                    state = State.CONFIRMED_FULL
                    journal.marker(d, "panic_scale_full")
                    return decision
                }
            }
        }
        return null
    }

    companion object {
        /** 패닉 일지 → 날짜별 시계열(수준·하드트리거는 마지막 관측 유지, 종가·저가는 ffill). 유효 종가가 없으면 null. */
        fun build(dates: List<LocalDate>, panic: List<PanicDay>?, params: PanicParams): PanicOverlay? {
            if (panic.isNullOrEmpty()) return null
            val byDate = panic.associateBy { it.date }
            val lv = arrayOfNulls<String>(dates.size)
            val hd = BooleanArray(dates.size)
            var lastLv: String? = null
            var lastHd = false
            dates.forEachIndexed { i, d ->
                byDate[d]?.let { lastLv = it.level; lastHd = it.hardTrigger }
                lv[i] = lastLv
                hd[i] = lastHd
            }
            val close = Series(dates, DoubleArray(dates.size) { byDate[dates[it]]?.close ?: Double.NaN }).ffill()
            if (close.values.all { it.isNaN() }) return null
            val low = Series(dates, DoubleArray(dates.size) { byDate[dates[it]]?.low ?: Double.NaN }).ffill()
            return PanicOverlay(params, lv, hd, close, low)
        }
    }
}
