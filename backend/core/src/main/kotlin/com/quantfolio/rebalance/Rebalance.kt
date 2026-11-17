package com.quantfolio.rebalance

import com.quantfolio.backtest.PriceData
import com.quantfolio.backtest.Signals
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Series
import java.time.LocalDate
import java.time.temporal.IsoFields
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 리밸런싱 코어(원본 `engine/rebalance.py`) — 백테스트와 실거래 러너가 같은 함수를 쓴다(정합성).
 * I/O 없음. 시장 개장 여부 같은 외부 상태는 호출자가 넘긴다.
 */
object Rebalance {
    const val INVERSE_VOL_MIN_WEIGHT = 0.005
    const val INVERSE_VOL_MAX_WEIGHT = 0.15

    private fun realizedVolAnn(close: Series, window: Int): Double? {
        val logRet = (close / close.shift(1)).map { ln(it) }.dropNa()
        if (logRet.size < window || window < 2) return null
        return logRet.tail(window).std() * sqrt(252.0)
    }

    /** 변동성 적격 게이트. series 는 호출자가 as_of 까지로 자른 종가. 데이터 부족은 불통과. */
    fun passesVolGate(series: Series?, gate: Map<String, Any?>): Boolean {
        val spikeLookback = Cfg.int(Cfg.get(gate, "spike_lookback", 20))
        val baseLookback = Cfg.int(Cfg.get(gate, "base_lookback", 252))
        val spikeMin = Cfg.num(Cfg.get(gate, "spike_min", 1.4))
        val spikeMax = gate["spike_max"]?.let { Cfg.num(it) }
        val cap = Cfg.num(Cfg.get(gate, "cap", 0.90))
        val requireUptrend = Cfg.truthy(Cfg.get(gate, "require_uptrend", true))
        if (series == null) return false
        val c = series.dropNa()
        if (c.size < baseLookback + 1) return false
        val short = realizedVolAnn(c, spikeLookback)
        val long = realizedVolAnn(c, baseLookback)
        if (short == null || long == null || long <= 0) return false
        if (short / long < spikeMin) return false
        if (spikeMax != null && short / long > spikeMax) return false
        if (short > cap) return false
        if (requireUptrend) {
            val sma = c.values.takeLast(spikeLookback).average()
            if (c.last() <= sma) return false
        }
        return true
    }

    /** 정규화 + [lo, hi] 캡(water-filling). 캡이 종목 수와 맞지 않으면 균등비중. 합은 항상 1. */
    fun capNormalize(raw: Map<String, Double>, lo: Double = INVERSE_VOL_MIN_WEIGHT, hi: Double = INVERSE_VOL_MAX_WEIGHT): Map<String, Double> {
        val n = raw.size
        if (n == 0) return emptyMap()
        if (n == 1) return mapOf(raw.keys.first() to 1.0)
        if (lo * n > 1.0 + 1e-9 || hi * n < 1.0 - 1e-9) return raw.mapValues { 1.0 / n }
        val total = raw.values.sum()
        if (total <= 0) return raw.mapValues { 1.0 / n }
        val fixed = LinkedHashMap<String, Double>()
        var free = LinkedHashMap(raw.mapValues { it.value / total })
        repeat(n + 2) {
            if (free.isEmpty()) return@repeat
            val remaining = 1.0 - fixed.values.sum()
            val freeTotal = free.values.sum()
            if (freeTotal <= 0) {
                val share = remaining / free.size
                free.keys.forEach { fixed[it] = share }
                free = LinkedHashMap()
                return@repeat
            }
            val scaled = free.mapValues { it.value / freeTotal * remaining }
            val over = scaled.filterValues { it > hi + 1e-12 }.keys
            val under = scaled.filterValues { it < lo - 1e-12 }.keys
            if (over.isEmpty() && under.isEmpty()) {
                fixed.putAll(scaled)
                free = LinkedHashMap()
                return@repeat
            }
            val (violated, bound) = if (over.isNotEmpty()) over to hi else under to lo
            for (k in violated) {
                fixed[k] = bound
                free.remove(k)
            }
        }
        if (free.isNotEmpty()) {
            val share = (1.0 - fixed.values.sum()) / free.size
            free.keys.forEach { fixed[it] = share }
        }
        return fixed
    }

    /** 인버스-변동성 비중. 무효 vol 은 0, 유효 종목이 없으면 동일비중. */
    fun inverseVol(vols: Map<String, Double?>, selected: List<String>, lo: Double = INVERSE_VOL_MIN_WEIGHT, hi: Double = INVERSE_VOL_MAX_WEIGHT): Map<String, Double> {
        if (selected.isEmpty()) return emptyMap()
        val valid = selected.mapNotNull { s -> vols[s]?.takeIf { !it.isNaN() && it > 0 }?.let { s to it } }.toMap()
        if (valid.isEmpty()) return selected.associateWith { 1.0 / selected.size }
        val w = capNormalize(valid.mapValues { 1.0 / it.value }, lo, hi)
        return selected.associateWith { w[it] ?: 0.0 }
    }

    /**
     * 선정 규칙(momentum/all/score/custom) → 목표비중. weighting: equal(기본)/score(순위 선형, score 전용)/inverse_vol.
     * score 방식의 점수는 호출자가 미래참조 없는 as_of 기준으로 미리 계산해 넘긴다. 미선정 종목은 키에 없음(=0).
     */
    fun targetWeights(
        priceHistory: Map<String, Series>,
        cfg: Map<String, Any?>,
        scores: Map<String, Double>? = null,
        vols: Map<String, Double?>? = null,
    ): Map<String, Double> {
        val selection = Cfg.map(cfg, "selection")
        val method = Cfg.get(selection, "method", "momentum")
        val lookback = Cfg.int(Cfg.get(selection, "lookback", 120))
        val topN = Cfg.int(Cfg.get(selection, "top_n", 5))
        val weighting = Cfg.get(cfg, "weighting", "equal")
        @Suppress("UNCHECKED_CAST")
        val universe = (Cfg.get(cfg, "universe", emptyList<String>()) as List<String>)
        var ranked: List<Pair<String, Double>>? = null
        fun hasData(s: Series?, need: Int) = s != null && s.dropNa().size >= need

        val selected: List<String> = when (method) {
            "score" -> {
                val sc = scores ?: emptyMap()
                var cands = universe.mapNotNull { s -> sc[s]?.takeIf { !it.isNaN() }?.let { s to it } }
                @Suppress("UNCHECKED_CAST")
                val gate = selection["vol_gate"] as? Map<String, Any?>
                if (Cfg.truthy(gate)) cands = cands.filter { passesVolGate(priceHistory[it.first], gate!!) }
                selection["min_score"]?.let { min -> cands = cands.filter { it.second >= Cfg.num(min) } }
                ranked = cands.sortedByDescending { it.second }.take(topN)
                ranked!!.map { it.first }
            }
            "custom" -> {
                val rule = mapOf("type" to "custom", "entry" to selection["entry"], "exit" to selection["exit"])
                val need = Signals.minPeriods(rule)
                universe.filter { s ->
                    val series = priceHistory[s]
                    if (!hasData(series, need)) return@filter false
                    val state = Signals.customPositionState(PriceData.close(series!!.dropNa()), rule)
                    state.size > 0 && state.last() > 0
                }
            }
            "all" -> universe.filter { hasData(priceHistory[it], 1) }
            else -> universe.mapNotNull { s ->
                val series = priceHistory[s]
                if (!hasData(series, lookback + 1)) return@mapNotNull null
                val past = series!![series.size - lookback - 1]
                if (past <= 0) null else s to series.last() / past - 1.0
            }.sortedByDescending { it.second }.take(topN).map { it.first }
        }
        if (selected.isEmpty()) return emptyMap()
        if (weighting == "score" && ranked != null) return rankWeighted(ranked!!)
        if (weighting == "inverse_vol") {
            val v = vols ?: selected.associateWith { s -> priceHistory[s]?.let { MetricsCommon.computeVolAnn(it.dropNa().tail(253)) } }
            return inverseVol(v, selected)
        }
        return selected.associateWith { 1.0 / selected.size }
    }

    /** 순위 선형 가중(1위=N … 꼴찌=1, 합 N(N+1)/2). 점수 부호·스케일에 무관. */
    fun rankWeighted(ranked: List<Pair<String, Double>>): Map<String, Double> {
        val n = ranked.size
        val total = n * (n + 1) / 2.0
        return ranked.withIndex().associate { (i, p) -> p.first to (n - i) / total }
    }

    /**
     * 목표비중 vs 보유수량 → (종목, buy|sell, 수량) 주문. 드리프트 밴드 이내는 매매 안 함(신규 편입·전량 청산은 예외).
     * 매도가 먼저. 현재가 없는 종목은 다음 주기로 미룬다.
     */
    fun orders(
        targets: Map<String, Double>,
        positions: Map<String, Int>,
        prices: Map<String, Double?>,
        capital: Double,
        driftBand: Double,
    ): List<Triple<String, String, Int>> {
        if (capital <= 0) return emptyList()
        val sells = mutableListOf<Triple<String, String, Int>>()
        val buys = mutableListOf<Triple<String, String, Int>>()
        for (sym in (targets.keys + positions.keys).sorted()) {
            val price = prices[sym]
            if (price == null || price <= 0) continue
            val tw = targets[sym] ?: 0.0
            val curQty = positions[sym] ?: 0
            val curW = curQty * price / capital
            val isNew = curQty <= 0 && tw > 0.0
            val isExit = tw <= 0.0 && curQty > 0
            if (!(isNew || isExit) && kotlin.math.abs(curW - tw) <= driftBand) continue
            val delta = floor(tw * capital / price).toInt() - curQty
            if (delta > 0) buys += Triple(sym, "buy", delta) else if (delta < 0) sells += Triple(sym, "sell", -delta)
        }
        return sells + buys
    }

    /**
     * 지금 리밸런싱을 실행할지(원본 `is_rebalance_due`): 장 운영 중 + 실행시각(rebalance_time, 기본 14:30) 이상 + 이번 주기 미실행
     * + weekly 는 요일 ≥ rebalance_weekday, monthly/quarterly 는 일 ≥ rebalance_dom(그 달 일수로 클램프). 지정일이 휴장이면 다음 영업일에 자연 발화.
     * now·last 는 KST.
     */
    fun isRebalanceDue(cfg: Map<String, Any?>, last: java.time.ZonedDateTime?, now: java.time.ZonedDateTime, marketOpen: Boolean): Boolean {
        val cadence = Cfg.get(cfg, "cadence", "monthly").toString()
        if (!marketOpen) return false
        val (h, m) = Cfg.get(cfg, "rebalance_time", "14:30").toString().split(":").let { it[0].toInt() to it[1].toInt() }
        if (now.hour < h || (now.hour == h && now.minute < m)) return false
        if (last != null && periodKey(last.toLocalDate(), cadence) == periodKey(now.toLocalDate(), cadence)) return false
        when (cadence) {
            "weekly" -> if (now.dayOfWeek.value - 1 < Cfg.int(Cfg.or(cfg, "rebalance_weekday", 0)).coerceIn(0, 4)) return false
            "monthly", "quarterly" -> if (now.dayOfMonth < Cfg.int(Cfg.or(cfg, "rebalance_dom", 1)).coerceIn(1, now.toLocalDate().lengthOfMonth())) return false
        }
        return true
    }

    /** cadence 별 '같은 주기' 키. daily=날짜, weekly=[ISO 연, 주], quarterly=[연, 분기0~3], monthly=[연, 월]. */
    fun periodKey(d: LocalDate, cadence: String): Any = when (cadence) {
        "daily" -> d
        "weekly" -> listOf(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR))
        "quarterly" -> listOf(d.year, (d.monthValue - 1) / 3)
        else -> listOf(d.year, d.monthValue)
    }
}
