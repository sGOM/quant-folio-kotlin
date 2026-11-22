package com.quantfolio.engine

import org.slf4j.LoggerFactory
import com.quantfolio.quant.pyFixed

enum class MarketState { NORMAL, HALTED, COOLDOWN }

/**
 * 시장 서킷브레이커 판정(원본 `engine/halt.py`). CB 는 개별 종목 필드로 오지 않으므로 **동시 정지 비율**로 간접 판정한다.
 *
 * NORMAL ─정지비율≥60%─▶ HALTED ─해소─▶ COOLDOWN ─180초─▶ NORMAL (유예 중 재발하면 HALTED)
 *
 * 정상장을 CB 로 오판하면 매매가 통째로 멈추므로 보수적으로 건다: 표본 5종목 미만이면 판정하지 않고(2~3 종목 전략의
 * 개별 VI 를 CB 로 읽는 사고 방지), 120초 넘은 관측은 버린다. 시각은 호출자가 단조시계(초)로 주입한다. I/O 없음.
 */
class MarketHaltMonitor(
    private val cbRatio: Double = 0.6,
    private val minSample: Int = 5,
    private val cooldownSec: Double = 180.0,
    private val observeTtlSec: Double = 120.0,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val obs = HashMap<String, Pair<Double, Boolean>>()
    private var cooldownFrom: Double? = null

    /** 마지막으로 평가된 상태(평가를 유발하지 않는다). */
    @Volatile var state = MarketState.NORMAL
        private set

    @Synchronized fun observe(symbol: String, halted: Boolean, now: Double) {
        obs[symbol] = now to halted
    }

    @Synchronized fun haltedRatio(now: Double): Pair<Double, Int> {
        val fresh = obs.values.filter { now - it.first <= observeTtlSec }.map { it.second }
        if (fresh.isEmpty()) return 0.0 to 0
        return fresh.count { it }.toDouble() / fresh.size to fresh.size
    }

    @Synchronized fun evaluate(now: Double): MarketState {
        val (ratio, n) = haltedRatio(now)
        if (n >= minSample && ratio >= cbRatio) {
            if (state != MarketState.HALTED) log.warn("시장 CB 판정 — 정지비율 {}% (표본 {}종목) → 주문 중단", pyFixed(ratio * 100, 0), n)
            state = MarketState.HALTED
            cooldownFrom = null
            return state
        }
        when (state) {
            MarketState.HALTED -> {
                log.warn("시장 CB 해소 — {}초 유예 후 주문 재개(재개 직후 호가 붕괴 회피)", pyFixed(cooldownSec, 0))
                state = MarketState.COOLDOWN
                cooldownFrom = now
            }
            MarketState.COOLDOWN -> {
                val started = cooldownFrom
                if (started == null || now - started >= cooldownSec) {
                    log.info("유예 종료 — 주문 재개")
                    state = MarketState.NORMAL
                    cooldownFrom = null
                }
            }
            MarketState.NORMAL -> Unit
        }
        return state
    }
}
