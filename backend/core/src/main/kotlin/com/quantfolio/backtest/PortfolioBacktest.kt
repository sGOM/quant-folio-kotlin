package com.quantfolio.backtest

import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.metrics.PanicDay
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Num
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.rebalance.Rebalance
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate
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

    // KRX 호가단위(2023-01-25 개정): (가격 미만 상한, 호가단위)
    private val TICKS = listOf(2_000.0 to 1.0, 5_000.0 to 5.0, 20_000.0 to 10.0, 50_000.0 to 50.0, 200_000.0 to 100.0, 500_000.0 to 500.0, Double.POSITIVE_INFINITY to 1_000.0)

    fun tickSize(price: Double) = TICKS.first { price < it.first }.second

    fun roundToTick(price: Double): Double {
        if (!price.isFinite() || price <= 0) return price
        val t = tickSize(price)
        return Math.rint(price / t) * t
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
        val rs = regime.reindex(dates).ffill()
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
            put("vol_ann", i, MetricsCommon.computeVolAnn(c.tail(253)))
            put("mdd_252", i, MetricsCommon.computeMdd(c.tail(252)))
        }
        return cols
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
        (selection["universe_rule"] as? Map<String, Any?>)?.takeIf { Cfg.truthy(it) }?.let { universe = Rebalance.dynamicUniverse(hist, universe, it) }
        val method = Cfg.get(selection, "method", "momentum")
        val config = ctx.config + ("universe" to universe)
        val risk = Cfg.map(ctx.config, "risk_layer")

        if (method == "score") {
            val cols = factorFrameOf(hist, universe)
            var sector: List<String?>? = null
            val fdf = ctx.fundamentals?.let { runCatching { it(d, universe) }.getOrNull() }
            if (!fdf.isNullOrEmpty()) {
                for (col in FactorScores.FUNDAMENTAL_COLS) {
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

    /** `_dividends`(종목 → [{ex_date, dps}]) → 배당락일별 (종목, dps), 종목 오름차순. 없거나 비면 빈 맵. */
    private fun dividendEvents(config: Map<String, Any?>): Map<LocalDate, List<Pair<String, Double>>> {
        val raw = config["_dividends"] as? Map<*, *> ?: return emptyMap()
        val byDay = HashMap<LocalDate, MutableList<Pair<String, Double>>>()
        for (sym in raw.keys.map { it.toString() }.sorted()) {
            for (e in raw[sym] as List<*>) {
                e as Map<*, *>
                val ex = e["ex_date"].let { it as? LocalDate ?: LocalDate.parse(it.toString()) }
                byDay.getOrPut(ex) { mutableListOf() } += sym to Cfg.num(e["dps"])
            }
        }
        return byDay
    }

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
        // 원본 `int(config.get("delisting_gap_days", 10) or 0)` — 키가 없으면 10, 있는데 None·0·"" 이면 0(ffill 무제한)
        val gap = Cfg.int(if (config.containsKey("delisting_gap_days")) Cfg.or(config, "delisting_gap_days", 0) else 10)
        val delistRecovery = Cfg.num(Cfg.get(config, "delisting_recovery", 0.9)).coerceIn(0.0, 1.0)
        val dates = closePanel.dates
        val panel = Panel(dates, symbols, raw.mapValues { it.value.ffill(if (gap > 0) gap else null).values })
        if (dates.isEmpty()) throw IllegalArgumentException("가격 데이터가 비어 있습니다.")
        val simDates = dates.filter { !it.isBefore(simStart) && !it.isAfter(simEnd) }
        if (simDates.size < 2) throw IllegalArgumentException("시뮬레이션 구간의 거래일이 부족합니다(최소 2일).")
        val dateIdx = dates.withIndex().associate { (i, d) -> d to i }

        val ex = Exec.from(config)
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
                val vol = if (volume.hasSymbol(s)) volume.column(s).reindex(dates).fillNa(0.0).values else DoubleArray(dates.size)
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
        val pov = panicParams(po)
        val panicEnabled = Cfg.truthy(po["enabled"])
        val overlay = if (panicEnabled) PanicOverlay.build(dates, panic, pov) else null
        if (panicEnabled && overlay == null) log.warn("패닉 오버레이가 켜져 있으나 유효한 패닉 시계열을 확보하지 못해 오버레이를 적용하지 않는다.")

        val dividendsByDay = dividendEvents(config)
        val dividendTax = Cfg.num(Cfg.get(config, "dividend_tax", 0.154))
        var dividendIncome = 0.0
        val book = Book(1.0)
        var prevPrices: Map<String, Double>? = null
        var prevRiskOff = false
        var hwm = 1.0
        var killed = false
        var killIdx = -1
        var numKills = 0
        val journal = Journal()
        var pending: Decision? = null

        /** 결정을 d 의 가격으로 체결. 청산은 보유 전량, 리밸런싱은 가격이 있는 목표 종목만. */
        fun runDecision(dec: Decision, prices: Map<String, Double>, d: LocalDate, limitRef: Map<String, Double>?) {
            val liquidate = dec.action == Action.LIQUIDATE
            val targets = if (liquidate) emptyMap() else dec.targets.filter { (s, _) -> prices[s]?.let { !it.isNaN() && it > 0 } == true }
            if (if (liquidate) book.value.isEmpty() else targets.isEmpty()) return
            val equity = book.equity()
            val advToday = adv?.let { a -> val i = dateIdx.getValue(d); a.mapValues { it.value[i] } }
            val slipSymbols: Collection<String> = if (liquidate) book.value.keys.toList() else targets.keys + book.value.keys
            val slipMap = Slippage.volMap(panel.upTo(d), slipSymbols, ex.slipBase, slipVolScale)
            val (turnover, executed) = applyRebalance(book, targets, equity, prices, d, ex, liquidate, dec.reason, slipMap, advToday, limitRef)
            if (turnover > 0) {
                journal.turnovers += turnover
                when {
                    !liquidate -> journal.marker(d, "rebalance", "holdings" to book.value.size)
                    dec.reason == "mdd_kill" -> journal.marker(d, "mdd_exit")
                    else -> journal.marker(d, "regime_exit") // 원본도 패닉 오버레이 청산(panic_exit_*)을 regime_exit 로 표기
                }
            }
            if (executed > 0) journal.turnoversActual += executed
        }

        for ((i, d) in simDates.withIndex()) {
            val di = dateIdx.getValue(d)
            val prices = panel.row(d)!!
            val limitRef = prevPrices
            // 1) 마크투마켓 + 상장폐지(terminal gap) 확정
            prevPrices?.let { markToMarket(book, it, prices, d, lastValid, delistRecovery, journal) }
            dividendsByDay[d]?.let { dividendIncome += accrueDividends(book, it, prices, dividendTax) }
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

            if (overlay != null && !killed && !mddTrigger) {
                decision = overlay.step(i, di, d, riskOff, journal) { exposure -> targetsAt(d, ctx, exposure) }
            }

            if (mddTrigger) {
                killed = true
                killIdx = i
                numKills++
                overlay?.reset()
                if (book.value.isNotEmpty()) decision = Decision.liquidate("mdd_kill")
            } else if (decision != null || killed || overlay?.holding == true) {
                // 패닉 오버레이 결정 확정 / 쿨다운 대기 / 오버레이 유지(레짐 청산·정기 리밸런싱 억제)
            } else if (riskOff && book.value.isNotEmpty()) {
                decision = Decision.liquidate("regime_exit")
            } else if (!riskOff) {
                val regimeReentry = regimeOn != null && prevRiskOff && book.value.isEmpty()
                val due = d in rebalDates || regimeReentry || (justRearmed && book.value.isEmpty())
                if (due && overlay?.params?.eventOnly != true) {
                    decision = Decision.rebalance("rebalance", targetsAt(d, ctx, overlay?.params?.baseExposure ?: 1.0))
                }
            }

            decision?.let { if (defer) pending = it else runDecision(it, prices, d, limitRef) }
            val equityNow = book.equity()
            journal.equities += equityNow
            journal.equityCurve += linkedMapOf("t" to iso(d), "v" to Num.safe(equityNow * ex.capital))
            prevRiskOff = riskOff
        }

        val result = summarize(
            journal, book, simDates, dates, dateIdx, benchmark, rfAnnual, snapshots, panel, numKills, overlay?.numEvents ?: 0,
        )
        // 배당 이벤트가 있을 때만 키를 더한다(없으면 이전 결과와 동일). 금액은 원화(자본 × 비율).
        if (dividendsByDay.isNotEmpty()) result["dividend_income"] = Num.safe(dividendIncome * ex.capital)
        return result
    }

    /** 일별 기록 → 결과(성과지표·곡선·마커·보유·거래). 키 순서가 응답 계약. */
    private fun summarize(
        journal: Journal, book: Book, simDates: List<LocalDate>, dates: List<LocalDate>, dateIdx: Map<LocalDate, Int>,
        benchmark: Series?, rfAnnual: Double, snapshots: List<ScoreSnapshot>?, panel: Panel, numKills: Int, numPanicEvents: Int,
    ): LinkedHashMap<String, Any?> {
        val series = DoubleArray(journal.equities.size + 1) { if (it == 0) 1.0 else journal.equities[it - 1] }
        val rets = DoubleArray(series.size - 1) { series[it + 1] / series[it] - 1.0 }
        val totalReturn = Num.safe(series.last() - 1.0)
        var peak = Double.NEGATIVE_INFINITY
        var mddMin = Double.POSITIVE_INFINITY
        for (v in series) {
            peak = maxOf(peak, v)
            mddMin = minOf(mddMin, v / peak - 1.0)
        }
        val benchRets = benchmark?.takeIf { it.size > 0 }?.let { b ->
            val pct = b.reindex(dates).ffill().pctChange()
            DoubleArray(simDates.size) { pct[dateIdx.getValue(simDates[it])] }
        }
        val radj = Attribution.riskAdjusted(rets, benchRets, rfAnnual)
        val cagr = if (rets.isNotEmpty() && series.last() > 0) Num.safe(series.last().pow(252.0 / rets.size) - 1.0) else null
        val denom = maxOf(series.last(), EPS)
        val holdings = book.value.entries.sortedByDescending { it.value }
            .associate { (s, v) -> s to BigDecimal(v / denom).setScale(4, RoundingMode.HALF_EVEN).toDouble() }
        val factorIc = if (!snapshots.isNullOrEmpty()) Attribution.factorAttribution(snapshots, panel) else emptyMap()
        log.info("리밸런싱 백테스트 완료 — 리밸런싱 {}회, 거래 {}건, 총수익률 {}, MDD {}", journal.turnovers.size, book.trades.size, totalReturn, mddMin)
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
            "num_rebalances" to journal.turnovers.size,
            "num_kills" to numKills,
            "num_panic_events" to numPanicEvents,
            "factor_ic" to factorIc,
            "avg_turnover" to if (journal.turnovers.isEmpty()) 0.0 else Num.safe(journal.turnovers.average()),
            "avg_turnover_actual" to if (journal.turnoversActual.isEmpty()) 0.0 else Num.safe(journal.turnoversActual.average()),
            "equity_curve" to journal.equityCurve,
            "markers" to journal.markers,
            "holdings" to holdings,
            "trades" to book.trades,
        )
    }
}
