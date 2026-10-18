package com.quantfolio.backtest

import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.market.MetricsCommon
import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Num
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.rebalance.Cfg
import com.quantfolio.rebalance.Rebalance
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
import kotlin.math.floor
import kotlin.math.pow

/** 리밸런싱일 d 의 후보풀 공급(시점별 지수 구성종목 → 생존편향 제거). */
typealias PoolProvider = (LocalDate) -> List<String>?

/** method="score" 의 펀더멘털 공급: (as_of, codes) → 종목 → {PER, PBR, DIV, roe, …, market_cap, sector}. */
typealias FundamentalsProvider = (LocalDate, List<String>) -> Map<String, Map<String, Any?>>?

/**
 * 다종목 리밸런싱 백테스트(원본 `backtest/portfolio.py run_rebalance_backtest`). 일별 시뮬레이션.
 *
 * - **미래참조 금지**: 결정일 d 의 선정·비중·청산 판단은 `panel.upTo(d)` 만 본다. 체결은 fill_mode —
 *   next_close(기본) 는 d 의 결정을 다음 거래일 종가에, same_close 는 d 종가에.
 * - 비용: 매수 fees+슬리피지, 매도 fees+tax+슬리피지. 드리프트 밴드 이내 보유 종목은 매매 안 함.
 * - 결정 우선순위: MDD 킬스위치 → 패닉 오버레이 → (쿨다운 대기) → 레짐 청산 → 정기 리밸런싱/재진입.
 * - 상장폐지: ffill 은 delisting_gap_days 까지만, 그 뒤 **terminal gap** 이면 회수율만 현금화(interior gap 은 동결).
 * - 체결 정밀도: 정수주(기본), ADV 참여율 캡(강제청산 면제), 호가단위·상하한가(opt-in).
 *
 * 수치는 원본과 같은 연산 순서(dict 삽입 순서, 정렬된 종목 순회, 매도→매수)로 계산해 정답지와 1e-8 이내로 맞춘다.
 */
object PortfolioBacktest {
    private val log = LoggerFactory.getLogger(javaClass)
    private val PANIC_RANK = mapOf("normal" to 0, "caution" to 1, "warning" to 2, "panic" to 3)

    // KRX 호가단위(2023-01-25 개정): (가격 미만 상한, 호가단위)
    private val TICKS = listOf(2_000.0 to 1.0, 5_000.0 to 5.0, 20_000.0 to 10.0, 50_000.0 to 50.0, 200_000.0 to 100.0, 500_000.0 to 500.0, Double.POSITIVE_INFINITY to 1_000.0)

    fun tickSize(price: Double) = TICKS.first { price < it.first }.second

    fun roundToTick(price: Double): Double {
        if (!price.isFinite() || price <= 0) return price
        val t = tickSize(price)
        return Math.rint(price / t) * t
    }

    private fun iso(d: LocalDate) = "${d}T00:00:00"

    private data class PanicParams(
        val armRank: Int, val armWindow: Int, val holdDays: Int, val profitReclaim: Double, val knifeStop: Double,
        val baseExposure: Double, val panicExposure: Double, val scaleInConfirm: Double, val maRecovery: Int, val eventOnly: Boolean,
    )

    private fun panicParams(po: Map<String, Any?>): PanicParams {
        fun f(k: String, d: Double) = po[k]?.let { Cfg.num(it) } ?: d
        return PanicParams(
            PANIC_RANK[(Cfg.or(po, "arm_level", "warning")).toString()] ?: 2,
            Cfg.int(Cfg.or(po, "arm_window", 5)), Cfg.int(Cfg.or(po, "hold_days", 20)),
            f("profit_reclaim_pct", 0.5), f("knife_stop_pct", 0.05), f("base_exposure", 0.70), f("panic_exposure", 1.00),
            f("scale_in_confirm", 0.5), Cfg.int(Cfg.or(po, "ma_recovery_period", 20)), Cfg.truthy(Cfg.get(po, "event_only", false)),
        )
    }

    /** 각 주기(일/주/월/분기)에서 지정 임계(weekday·dom, 달 길이로 클램프) 이상인 첫 거래일. */
    fun rebalanceDates(dates: List<LocalDate>, cfg: Map<String, Any?>): Set<LocalDate> {
        val cadence = Cfg.get(cfg, "cadence", "monthly").toString()
        val weekday = Cfg.int(Cfg.or(cfg, "rebalance_weekday", 0)).coerceIn(0, 4)
        val dom = Cfg.int(Cfg.or(cfg, "rebalance_dom", 1))
        val picked = LinkedHashSet<LocalDate>()
        val seen = HashSet<Any>()
        for (d in dates) {
            val key = Rebalance.periodKey(d, cadence)
            if (key in seen) continue
            if (cadence == "weekly" && d.dayOfWeek.value - 1 < weekday) continue
            if ((cadence == "monthly" || cadence == "quarterly") && d.dayOfMonth < dom.coerceIn(1, d.lengthOfMonth())) continue
            seen += key
            picked += d
        }
        return picked
    }

    /** 기준지수 vs 이동평균 히스테리시스 레짐(on=참여). MA 미확정 구간은 참여. 지수가 없으면 null. */
    fun regimeOnFlags(dates: List<LocalDate>, regime: Series?, maPeriod: Int, exitBuffer: Double = 0.0, reentryBuffer: Double = 0.0): BooleanArray? {
        if (regime == null || regime.size == 0) return null
        val rs = align(regime, dates)
        val ma = rs.rolling(maPeriod, maxOf(5, maPeriod / 2)).mean()
        val exitB = maxOf(0.0, exitBuffer)
        val reB = maxOf(0.0, reentryBuffer)
        var state = true
        var init = false
        return BooleanArray(dates.size) { i ->
            val v = rs[i]
            val m = ma[i]
            if (v.isNaN() || m.isNaN()) return@BooleanArray true
            if (!init) {
                state = v >= m
                init = true
            } else if (state) {
                if (v < m * (1.0 - exitB)) state = false
            } else if (v >= m * (1.0 + reB)) {
                state = true
            }
            state
        }
    }

    /** pandas `s.reindex(dates).ffill()` — 같은 날짜 값만 가져온 뒤 앞값 채움. */
    private fun align(s: Series, dates: List<LocalDate>): Series {
        val byDate = HashMap<LocalDate, Double>()
        s.index.forEachIndexed { i, d -> byDate[d] = s.values[i] }
        return Series(dates, DoubleArray(dates.size) { byDate[dates[it]] ?: Double.NaN }).ffill()
    }

    private fun factorFrameOf(hist: Panel, codes: List<String>): MutableMap<String, DoubleArray> {
        val cols = linkedMapOf<String, DoubleArray>()
        fun put(k: String, i: Int, v: Double?) {
            cols.getOrPut(k) { DoubleArray(codes.size) { Double.NaN } }[i] = v ?: Double.NaN
        }
        codes.forEachIndexed { i, code ->
            if (!hist.hasSymbol(code)) return@forEachIndexed
            val c = hist.column(code).dropNa()
            val n = c.size
            val last = if (n > 0) c.last() else Double.NaN
            if (n >= 22 && c[n - 22] > 0) put("mom_1m", i, last / c[n - 22] - 1.0)
            if (n >= 64 && c[n - 64] > 0) put("mom_3m", i, last / c[n - 64] - 1.0)
            if (n >= 127 && c[n - 127] > 0) put("mom_6m", i, last / c[n - 127] - 1.0)
            if (n >= 252) {
                val hi = c.values.takeLast(252).max()
                if (hi > 0) put("high_52w_ratio", i, last / hi)
            }
            put("vol_ann", i, MetricsCommon.computeVolAnn(tail(c, 253)))
            put("mdd_252", i, MetricsCommon.computeMdd(tail(c, 252)))
        }
        return cols
    }

    private fun tail(s: Series, n: Int): Series {
        val from = maxOf(0, s.size - n)
        return Series(s.index.subList(from, s.size), s.values.copyOfRange(from, s.size))
    }

    private fun dynamicUniverse(hist: Panel, pool: List<String>, rule: Map<String, Any?>): List<String> {
        val lookback = Cfg.int(Cfg.get(rule, "lookback", 120))
        val pick = Cfg.int(Cfg.get(rule, "pick", 20))
        return pool.mapNotNull { code ->
            if (!hist.hasSymbol(code)) return@mapNotNull null
            val c = hist.column(code).dropNa()
            if (c.size < lookback + 1) return@mapNotNull null
            val p0 = c[c.size - lookback - 1]
            if (p0 > 0) code to c.last() / p0 - 1.0 else null
        }.sortedByDescending { it.second }.take(pick).map { it.first }
    }

    private fun scale(w: Map<String, Double>, exposure: Double): Map<String, Double> =
        if (w.isEmpty() || exposure >= 1.0 - 1e-9) w else maxOf(0.0, exposure).let { e -> w.mapValues { it.value * e } }

    private class Ctx(
        val panel: Panel,
        val config: Map<String, Any?>,
        val fundamentals: FundamentalsProvider?,
        val pool: PoolProvider?,
        val sectorMap: Map<String, String?>?,
        val snapshots: MutableList<ScoreSnapshot>?,
    )

    /** 리밸런싱일 d 의 목표비중(d 종가까지의 데이터만). exposure<1 이면 나머지는 현금. */
    private fun targetsAt(d: LocalDate, ctx: Ctx, exposure: Double = 1.0): Map<String, Double> {
        val hist = ctx.panel.upTo(d)
        val selection = Cfg.map(ctx.config, "selection")
        @Suppress("UNCHECKED_CAST")
        var universe: List<String> = ctx.pool?.invoke(d) ?: (Cfg.get(ctx.config, "universe", emptyList<String>()) as List<String>)
        @Suppress("UNCHECKED_CAST")
        (selection["universe_rule"] as? Map<String, Any?>)?.takeIf { Cfg.truthy(it) }?.let { universe = dynamicUniverse(hist, universe, it) }
        val method = Cfg.get(selection, "method", "momentum")
        val config = ctx.config + ("universe" to universe)
        val risk = Cfg.map(ctx.config, "risk_layer")

        if (method == "score") {
            val cols = factorFrameOf(hist, universe)
            var sector: List<String?>? = null
            val fdf = ctx.fundamentals?.let { runCatching { it(d, universe) }.getOrNull() }
            if (!fdf.isNullOrEmpty()) {
                for (col in listOf("PER", "PBR", "DIV", "roe", "debt_ratio", "fcf", "f_score", "op_growth", "net_growth", "turnaround", "market_cap", "flow_norm", "resid_mom", "pead_sue")) {
                    if (fdf.values.none { col in it }) continue
                    cols[col] = DoubleArray(universe.size) { (fdf[universe[it]]?.get(col) as? Number)?.toDouble() ?: Double.NaN }
                }
                if (fdf.values.any { "sector" in it }) sector = universe.map { fdf[it]?.get("sector")?.toString() }
            }
            @Suppress("UNCHECKED_CAST")
            val weights = (selection["factor_weights"] as? Map<String, Any?>)?.mapValues { Cfg.num(it.value) }
            val scored = FactorScores.compute(FactorFrame(universe, cols, sector), weights, Cfg.get(selection, "neutralize", "none").toString())
            ctx.snapshots?.add(ScoreSnapshot(d, universe, Attribution.FACTOR_SCORE_COLS.filter { it in scored }.associateWith { scored.getValue(it) }))
            val total = scored.getValue("score")
            val scores = universe.indices.filter { !total[it].isNaN() }.associate { universe[it] to total[it] }
            val vols = cols["vol_ann"]?.let { v -> universe.indices.filter { !v[it].isNaN() }.associate { universe[it] to v[it] } }
            val gateHistory = if (Cfg.truthy(selection["vol_gate"])) universe.filter { hist.hasSymbol(it) }.associateWith { hist.column(it).dropNa() } else emptyMap()
            val w = Rebalance.targetWeights(gateHistory, config, scores, vols)
            return scale(RiskCaps.apply(w, hist, risk, ctx.sectorMap), exposure)
        }
        val priceHistory = universe.filter { hist.hasSymbol(it) }.associateWith { hist.column(it).dropNa() }
        val w = Rebalance.targetWeights(priceHistory, config)
        return scale(RiskCaps.apply(w, hist, risk, ctx.sectorMap), exposure)
    }

    /** 체결 1회의 설정(시뮬레이션 내내 불변). */
    private class Exec(
        val capital: Double, val driftBand: Double, val fees: Double, val tax: Double, val slipBase: Double,
        val integerShares: Boolean, val advCapFrac: Double, val priceLimitModel: Boolean,
    )

    private class Book(var cash: Double) {
        val value = LinkedHashMap<String, Double>()
        val cost = LinkedHashMap<String, Double>()
        val trades = mutableListOf<Map<String, Any?>>()
        fun equity() = cash + value.values.sum()
    }

    private fun tradeRec(d: LocalDate, sym: String, side: String, amt: Double, fill: Map<String, Double>?, capital: Double, portReturn: Double, posReturn: Double?, reason: String) =
        linkedMapOf<String, Any?>(
            "t" to iso(d), "symbol" to sym, "side" to side, "amount" to Math.rint(amt * capital).toLong(),
            "price" to fill?.get(sym)?.let { Num.safe(it) }, "port_return" to Num.safe(portReturn),
            "position_return" to posReturn?.let { Num.safe(it) }, "reason" to reason,
        )

    private fun floorToShares(amt: Double, sym: String, fill: Map<String, Double>?, capital: Double): Double {
        val price = fill?.get(sym)
        if (price == null || price.isNaN() || price <= 0 || capital <= 0) return amt
        val qty = floor(amt * capital / price)
        return if (qty <= 0) 0.0 else qty * price / capital
    }

    /**
     * 목표비중으로 리밸런싱(매도 먼저 → 매수). book 을 제자리 수정하고 (turnover, 실체결 거래대금)을 돌려준다.
     * liquidate 면 드리프트 밴드 무시 전량 청산이며 ADV 캡이 면제된다(라이브 러너가 캡 없이 즉시 전량 매도하므로).
     */
    private fun applyRebalance(
        book: Book, targets: Map<String, Double>, equity: Double, prices: Map<String, Double>, d: LocalDate, ex: Exec,
        liquidate: Boolean, reason: String, slipMap: Map<String, Double>, advMap: Map<String, Double>?, prevPrices: Map<String, Double>?,
    ): Pair<Double, Double> {
        if (equity <= 0) {
            book.cash = 0.0
            return 0.0 to 0.0
        }
        fun slip(s: String) = slipMap[s] ?: ex.slipBase
        val fill = if (ex.priceLimitModel) prices.mapValues { (_, p) -> if (p.isNaN()) p else roundToTick(p) } else prices
        fun blocked(sym: String, side: String): Boolean {
            if (!ex.priceLimitModel || prevPrices == null) return false
            val cur = prices[sym]
            val prev = prevPrices[sym]
            if (cur == null || cur.isNaN() || prev == null || prev.isNaN() || prev <= 0) return false
            val lower = roundToTick(prev * 0.7).takeIf { it != 0.0 && !it.isNaN() } ?: 0.0
            val upper = roundToTick(prev * 1.3).takeIf { it != 0.0 && !it.isNaN() } ?: 0.0
            return if (side == "buy") cur >= upper - 1e-6 else cur <= lower + 1e-6
        }
        var executed = 0.0
        val portReturn = equity - 1.0
        var sells = mutableListOf<Pair<String, Double>>()
        var buys = mutableListOf<Pair<String, Double>>()
        var turnover = 0.0
        if (liquidate) {
            for ((sym, v) in book.value) if (v > 1e-12) {
                sells += sym to v
                turnover += v / equity
            }
        } else {
            for (sym in (targets.keys + book.value.keys).sorted()) {
                val curVal = book.value[sym] ?: 0.0
                val tw = targets[sym] ?: 0.0
                val dev = tw - curVal / equity
                val isNew = curVal <= 1e-12 && tw > 0.0
                val isExit = tw <= 0.0 && curVal > 1e-12
                if (!(isNew || isExit) && kotlin.math.abs(dev) <= ex.driftBand) continue
                turnover += kotlin.math.abs(dev)
                if (dev < 0) sells += sym to -dev * equity else buys += sym to dev * equity
            }
        }
        if (ex.advCapFrac > 0 && advMap != null && !liquidate) {
            fun cap(sym: String, amt: Double): Double {
                if (ex.capital <= 0) return amt
                val adv = advMap[sym]
                if (adv == null || !adv.isFinite() || adv <= 0) return amt
                return minOf(amt, ex.advCapFrac * adv / ex.capital)
            }
            sells = sells.map { (s, a) -> s to cap(s, a) }.toMutableList()
            buys = buys.map { (s, a) -> s to cap(s, a) }.toMutableList()
        }
        var cash = equity - book.value.values.sum()
        for ((sym, a0) in sells) {
            if (blocked(sym, "sell")) continue
            val curVal = book.value[sym] ?: 0.0
            var amt = a0
            val fullExit = amt >= curVal - 1e-9
            if (ex.integerShares && !fullExit) amt = floorToShares(amt, sym, fill, ex.capital)
            amt = minOf(amt, curVal)
            if (amt <= 1e-12) continue
            cash += amt * (1.0 - ex.fees - ex.tax - slip(sym))
            val frac = if (curVal > 0) amt / curVal else 0.0
            val costPortion = (book.cost[sym] ?: 0.0) * frac
            val posRet = if (costPortion > 1e-12) amt / costPortion - 1.0 else null
            book.value[sym] = curVal - amt
            book.cost[sym] = (book.cost[sym] ?: 0.0) - costPortion
            if (book.value.getValue(sym) <= 1e-12) {
                book.value.remove(sym)
                book.cost.remove(sym)
            }
            book.trades += tradeRec(d, sym, "sell", amt, fill, ex.capital, portReturn, posRet, reason)
            executed += amt
        }
        var bs = buys.filter { !blocked(it.first, "buy") }
        if (ex.integerShares) bs = bs.map { (s, a) -> s to floorToShares(a, s, fill, ex.capital) }
        val totalBuyCost = bs.sumOf { (s, a) -> a * (1.0 + ex.fees + slip(s)) }
        val k = if (totalBuyCost > cash && totalBuyCost > 0) cash / totalBuyCost else 1.0
        for ((sym, a0) in bs) {
            val amt = a0 * k
            if (amt <= 1e-12) continue
            cash -= amt * (1.0 + ex.fees + slip(sym))
            book.value[sym] = (book.value[sym] ?: 0.0) + amt
            book.cost[sym] = (book.cost[sym] ?: 0.0) + amt
            book.trades += tradeRec(d, sym, "buy", amt, fill, ex.capital, portReturn, null, reason)
            executed += amt
        }
        book.cash = cash
        return turnover to executed
    }

    private data class Decision(val kind: String, val reason: String, val targets: Map<String, Double> = emptyMap())

    fun run(
        closePanel: Panel,
        config: Map<String, Any?>,
        simStart: LocalDate,
        simEnd: LocalDate,
        fundamentals: FundamentalsProvider? = null,
        regime: Series? = null,
        pool: PoolProvider? = null,
        benchmark: Series? = null,
        panic: List<PanicDay>? = null,
        volume: Panel? = null,
    ): Map<String, Any?> {
        val symbols = closePanel.symbols
        val raw = symbols.associateWith { closePanel.column(it) }
        val lastValid = raw.mapNotNull { (s, c) -> c.values.indexOfLast { !it.isNaN() }.takeIf { it >= 0 }?.let { s to c.index[it] } }.toMap()
        val gap = Cfg.int(Cfg.or(config, "delisting_gap_days", 0).let { if (config.containsKey("delisting_gap_days")) it else 10 })
        val delistRecovery = Cfg.num(Cfg.get(config, "delisting_recovery", 0.9)).coerceIn(0.0, 1.0)
        val dates = closePanel.dates
        val panel = Panel(dates, symbols, raw.mapValues { it.value.ffill(if (gap > 0) gap else null).values })
        if (dates.isEmpty()) throw IllegalArgumentException("가격 데이터가 비어 있습니다.")
        val simDates = dates.filter { !it.isBefore(simStart) && !it.isAfter(simEnd) }
        if (simDates.size < 2) throw IllegalArgumentException("시뮬레이션 구간의 거래일이 부족합니다(최소 2일).")
        val dateIdx = dates.withIndex().associate { (i, d) -> d to i }

        val ex = Exec(
            capital = Cfg.num(Cfg.get(config, "capital", 10_000_000)),
            driftBand = Cfg.num(Cfg.get(config, "drift_band_pct", 0.05)),
            fees = Cfg.num(Cfg.get(config, "fees", 0.00015)),
            tax = Cfg.num(Cfg.get(config, "tax", 0.0020)),
            slipBase = maxOf(0.0, Cfg.num(Cfg.get(config, "slippage_bps", 5.0).takeIf { Cfg.truthy(it) } ?: 0.0) / 10_000.0),
            integerShares = Cfg.truthy(Cfg.get(config, "integer_shares", true)),
            advCapFrac = Cfg.num(Cfg.or(config, "adv_participation_cap", 0.0)),
            priceLimitModel = Cfg.truthy(Cfg.get(config, "price_limit_model", false)),
        )
        val rebalDates = rebalanceDates(simDates, config)
        val attribution = Cfg.map(config, "selection")["method"]?.toString() == "score"
        val snapshots = if (attribution) mutableListOf<ScoreSnapshot>() else null
        val defer = Cfg.get(config, "fill_mode", "next_close").toString() == "next_close"
        val slipVolScale = maxOf(0.0, Cfg.num(Cfg.get(config, "slippage_vol_scale", 0.0).takeIf { Cfg.truthy(it) } ?: 0.0))
        val rfAnnual = Cfg.num(Cfg.get(config, "risk_free_rate", 0.0).takeIf { Cfg.truthy(it) } ?: 0.0)

        val adv: Map<String, Series>? = when {
            ex.advCapFrac <= 0 -> null
            volume == null || volume.dates.isEmpty() -> {
                log.warn("adv_participation_cap 설정됨이나 거래량 패널(volume_panel) 미확보 — ADV 캡을 적용하지 않는다.")
                null
            }
            else -> symbols.associateWith { s ->
                val vol = if (volume.hasSymbol(s)) align0(volume.column(s), dates) else DoubleArray(dates.size)
                val c = panel.column(s)
                Series(dates, DoubleArray(dates.size) { vol[it] * c[it] }).rolling(20, 10).mean()
            }
        }

        val risk = Cfg.map(config, "risk_layer")
        val mddKill = risk["mdd_kill_pct"]?.takeIf { Cfg.truthy(it) }?.let { Cfg.num(it) }
        val mddRearm = Cfg.int(Cfg.or(risk, "mdd_rearm_days", 20))
        @Suppress("UNCHECKED_CAST")
        val sectorMap = config["_sector_map"] as? Map<String, String?>
        val ctx = Ctx(panel, config, fundamentals, pool, sectorMap, snapshots)

        val rf = Cfg.map(config, "regime_filter")
        var regimeOn: BooleanArray? = null
        if (Cfg.truthy(rf["enabled"])) {
            regimeOn = regimeOnFlags(
                dates, regime, Cfg.int(Cfg.get(rf, "ma_period", 200)),
                Cfg.num(Cfg.or(rf, "exit_buffer_pct", 0.0)), Cfg.num(Cfg.or(rf, "reentry_buffer_pct", 0.0)),
            )
            if (regimeOn == null) log.warn("레짐 필터가 켜져 있으나 기준지수 시세를 확보하지 못해 오버레이를 적용하지 않는다.")
        }

        val po = Cfg.map(config, "panic_overlay")
        var pLevel: Array<String?>? = null
        var pHard: BooleanArray? = null
        var pClose: Series? = null
        var pLow: Series? = null
        if (Cfg.truthy(po["enabled"])) {
            if (!panic.isNullOrEmpty()) {
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
                if (close.values.any { !it.isNaN() }) {
                    pLevel = lv
                    pHard = hd
                    pClose = close
                    pLow = Series(dates, DoubleArray(dates.size) { byDate[dates[it]]?.low ?: Double.NaN }).ffill()
                }
            }
            if (pClose == null) log.warn("패닉 오버레이가 켜져 있으나 유효한 패닉 시계열을 확보하지 못해 오버레이를 적용하지 않는다.")
        }
        val panicOn = pClose != null
        val pov = panicParams(po)
        val panicMa = pClose?.rolling(pov.maRecovery, maxOf(5, pov.maRecovery / 2))?.mean()
        val panicPrev = pClose?.shift(1)

        var panicState = "idle"
        var armDeadline = -1
        var capitClose: Double? = null
        var capitLow: Double? = null
        var capitDrop: Double? = null
        var confirmIdx = -1
        var numPanicEvents = 0

        val book = Book(1.0)
        var prevPrices: Map<String, Double>? = null
        var prevRiskOff = false
        var hwm = 1.0
        var killed = false
        var killIdx = -1
        var numKills = 0
        val equities = mutableListOf<Double>()
        val equityCurve = mutableListOf<Map<String, Any?>>()
        val markers = mutableListOf<Map<String, Any?>>()
        val turnovers = mutableListOf<Double>()
        val turnoversActual = mutableListOf<Double>()
        var pending: Decision? = null

        fun runDecision(dec: Decision, prices: Map<String, Double>, d: LocalDate, limitRef: Map<String, Double>?) {
            val equity = book.equity()
            val advToday = adv?.let { a -> val i = dateIdx.getValue(d); a.mapValues { it.value[i] } }
            val hist = panel.upTo(d)
            if (dec.kind == "liquidate") {
                if (book.value.isEmpty()) return
                val slipMap = Slippage.volMap(hist, book.value.keys.toList(), ex.slipBase, slipVolScale)
                val (turnover, executed) = applyRebalance(book, emptyMap(), equity, prices, d, ex, true, dec.reason, slipMap, advToday, limitRef)
                if (turnover > 0) {
                    turnovers += turnover
                    markers += linkedMapOf("t" to iso(d), "type" to if (dec.reason == "mdd_kill") "mdd_exit" else "regime_exit")
                }
                if (executed > 0) turnoversActual += executed
            } else {
                val targets = dec.targets.filter { (s, _) -> prices[s]?.let { !it.isNaN() && it > 0 } == true }
                if (targets.isEmpty()) return
                val slipMap = Slippage.volMap(hist, targets.keys + book.value.keys, ex.slipBase, slipVolScale)
                val (turnover, executed) = applyRebalance(book, targets, equity, prices, d, ex, false, dec.reason, slipMap, advToday, limitRef)
                if (turnover > 0) {
                    turnovers += turnover
                    markers += linkedMapOf("t" to iso(d), "type" to "rebalance", "holdings" to book.value.size)
                }
                if (executed > 0) turnoversActual += executed
            }
        }

        for ((i, d) in simDates.withIndex()) {
            val di = dateIdx.getValue(d)
            val prices = panel.row(d)!!
            val limitRef = prevPrices
            // 1) 마크투마켓 + 상장폐지(terminal gap) 확정
            if (prevPrices != null && book.value.isNotEmpty()) {
                for (sym in book.value.keys.toList()) {
                    val p0 = prevPrices[sym]
                    val p1 = prices[sym]
                    if (p1 != null && !p1.isNaN() && p0 != null && !p0.isNaN() && p0 > 0) {
                        book.value[sym] = book.value.getValue(sym) * (p1 / p0)
                    } else if (p1 == null || p1.isNaN()) {
                        val lv = lastValid[sym]
                        if (lv != null && d.isAfter(lv)) {
                            book.cash += book.value.remove(sym)!! * delistRecovery
                            book.cost.remove(sym)
                            markers += linkedMapOf("t" to iso(d), "type" to "delist", "symbol" to sym)
                        }
                    }
                }
            }
            prevPrices = prices
            // 2) 전일 결정의 지연 체결
            pending?.let {
                runDecision(it, prices, d, limitRef)
                pending = null
            }
            // 3) MDD 킬스위치 상태
            val curEquity = book.equity()
            var justRearmed = false
            if (mddKill != null) {
                if (curEquity > hwm) hwm = curEquity
                if (killed && i - killIdx >= mddRearm) {
                    killed = false
                    hwm = curEquity
                    justRearmed = true
                }
            }
            // 4) 당일 결정
            val riskOff = regimeOn != null && !regimeOn[di]
            var decision: Decision? = null
            val mddTrigger = mddKill != null && !killed && hwm > 0 && curEquity / hwm - 1.0 <= -mddKill

            if (panicOn && !killed && !mddTrigger) {
                val level = pLevel!![di] ?: "nan"
                val hard = pHard!![di]
                val close = pClose!![di]
                val low = pLow!![di]
                val prevClose = panicPrev!![di]
                val ma = panicMa!![di]
                val hasClose = !close.isNaN()
                val hasPrev = !prevClose.isNaN()
                when (panicState) {
                    "idle" -> if (hasClose && ((PANIC_RANK[level] ?: 0) >= pov.armRank || hard)) {
                        panicState = "armed"
                        armDeadline = i + pov.armWindow
                        capitClose = close
                        capitLow = if (!low.isNaN()) low else close
                        capitDrop = if (hasPrev) prevClose - close else null
                        markers += linkedMapOf("t" to iso(d), "type" to "panic_arm", "level" to level)
                    }
                    "armed" -> if (i > armDeadline) {
                        panicState = "idle"
                        capitClose = null
                        capitLow = null
                        capitDrop = null
                        markers += linkedMapOf("t" to iso(d), "type" to "panic_arm_timeout")
                    } else if (hasClose && hasPrev && capitClose != null && close > capitClose!! && close > prevClose) {
                        val fillExposure = if (pov.eventOnly) pov.panicExposure else pov.baseExposure + pov.scaleInConfirm * (pov.panicExposure - pov.baseExposure)
                        decision = Decision("rebalance", "panic_confirm", targetsAt(d, ctx, fillExposure))
                        panicState = if (pov.eventOnly) "confirmed" else "confirmed_half"
                        confirmIdx = i
                        numPanicEvents++
                        markers += linkedMapOf("t" to iso(d), "type" to "panic_confirm")
                    }
                    "confirmed_half", "confirmed_full", "confirmed" -> {
                        val exitReason = when {
                            hasClose && capitLow != null && close <= capitLow!! * (1.0 - pov.knifeStop) -> "panic_exit_knife"
                            hasClose && capitClose != null && capitDrop != null && capitDrop!! > 0 && close >= capitClose!! + pov.profitReclaim * capitDrop!! -> "panic_exit_profit"
                            confirmIdx >= 0 && i - confirmIdx >= pov.holdDays -> "panic_exit_hold"
                            else -> null
                        }
                        if (exitReason != null) {
                            decision = if (pov.eventOnly || riskOff) Decision("liquidate", exitReason) else Decision("rebalance", exitReason, targetsAt(d, ctx, pov.baseExposure))
                            panicState = "idle"
                            capitClose = null
                            capitLow = null
                            capitDrop = null
                            confirmIdx = -1
                        } else if (!pov.eventOnly && panicState == "confirmed_half" && hasClose && !ma.isNaN() && close > ma) {
                            decision = Decision("rebalance", "panic_scale_full", targetsAt(d, ctx, pov.panicExposure))
                            panicState = "confirmed_full"
                            markers += linkedMapOf("t" to iso(d), "type" to "panic_scale_full")
                        }
                    }
                }
            }

            if (mddTrigger) {
                killed = true
                killIdx = i
                numKills++
                if (panicOn && panicState != "idle") {
                    panicState = "idle"
                    capitClose = null
                    capitLow = null
                    capitDrop = null
                    confirmIdx = -1
                }
                if (book.value.isNotEmpty()) decision = Decision("liquidate", "mdd_kill")
            } else if (decision != null || killed || panicState in setOf("confirmed_half", "confirmed_full", "confirmed")) {
                // 패닉 오버레이 결정 확정 / 쿨다운 대기 / 오버레이 유지(레짐 청산·정기 리밸런싱 억제)
            } else if (riskOff && book.value.isNotEmpty()) {
                decision = Decision("liquidate", "regime_exit")
            } else if (!riskOff) {
                val regimeReentry = regimeOn != null && prevRiskOff && book.value.isEmpty()
                val due = d in rebalDates || regimeReentry || (justRearmed && book.value.isEmpty())
                if (due && !(panicOn && pov.eventOnly)) {
                    decision = Decision("rebalance", "rebalance", targetsAt(d, ctx, if (panicOn) pov.baseExposure else 1.0))
                }
            }

            decision?.let { if (defer) pending = it else runDecision(it, prices, d, limitRef) }
            val equityNow = book.equity()
            equities += equityNow
            equityCurve += linkedMapOf("t" to iso(d), "v" to Num.safe(equityNow * ex.capital))
            prevRiskOff = riskOff
        }

        // ── 성과지표 ──
        val series = DoubleArray(equities.size + 1) { if (it == 0) 1.0 else equities[it - 1] }
        val rets = DoubleArray(series.size - 1) { series[it + 1] / series[it] - 1.0 }
        val totalReturn = Num.safe(series.last() - 1.0)
        var peak = Double.NEGATIVE_INFINITY
        var mddMin = Double.POSITIVE_INFINITY
        for (v in series) {
            peak = maxOf(peak, v)
            mddMin = minOf(mddMin, v / peak - 1.0)
        }
        val benchRets = benchmark?.takeIf { it.size > 0 }?.let { b ->
            val pct = align(b, dates).pctChange()
            DoubleArray(simDates.size) { pct[dateIdx.getValue(simDates[it])] }
        }
        val radj = Attribution.riskAdjusted(rets, benchRets, rfAnnual)
        val cagr = if (rets.isNotEmpty() && series.last() > 0) Num.safe(series.last().pow(252.0 / rets.size) - 1.0) else null
        val denom = maxOf(series.last(), 1e-12)
        val holdings = book.value.entries.sortedByDescending { it.value }
            .associate { (s, v) -> s to BigDecimal(v / denom).setScale(4, RoundingMode.HALF_EVEN).toDouble() }
        val factorIc = if (!snapshots.isNullOrEmpty()) Attribution.factorAttribution(snapshots, panel) else emptyMap()
        log.info("리밸런싱 백테스트 완료 — 리밸런싱 {}회, 거래 {}건, 총수익률 {}, MDD {}", turnovers.size, book.trades.size, totalReturn, mddMin)
        val bret = radj["benchmark_return"]
        return linkedMapOf(
            "total_return" to totalReturn,
            "mdd" to Num.safe(mddMin),
            "sharpe" to radj["sharpe"],
            "sortino" to radj["sortino"],
            "alpha" to radj["alpha"],
            "beta" to radj["beta"],
            "information_ratio" to radj["information_ratio"],
            "tracking_error" to radj["tracking_error"],
            "benchmark_return" to bret,
            "excess_return" to if (totalReturn != null && bret != null) Num.safe(totalReturn - bret) else null,
            "cagr" to cagr,
            "win_rate" to null,
            "num_trades" to book.trades.size,
            "num_rebalances" to turnovers.size,
            "num_kills" to numKills,
            "num_panic_events" to numPanicEvents,
            "factor_ic" to factorIc,
            "avg_turnover" to if (turnovers.isEmpty()) 0.0 else Num.safe(turnovers.average()),
            "avg_turnover_actual" to if (turnoversActual.isEmpty()) 0.0 else Num.safe(turnoversActual.average()),
            "equity_curve" to equityCurve,
            "markers" to markers,
            "holdings" to holdings,
            "trades" to book.trades,
        )
    }

    /** 거래량 `reindex(dates).fillna(0)`. */
    private fun align0(s: Series, dates: List<LocalDate>): DoubleArray {
        val byDate = HashMap<LocalDate, Double>()
        s.index.forEachIndexed { i, d -> byDate[d] = s.values[i] }
        return DoubleArray(dates.size) { (byDate[dates[it]] ?: 0.0).let { v -> if (v.isNaN()) 0.0 else v } }
    }
}
