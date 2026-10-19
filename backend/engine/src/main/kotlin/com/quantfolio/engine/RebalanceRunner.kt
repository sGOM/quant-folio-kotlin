package com.quantfolio.engine

import com.quantfolio.alert.pyIsoformat
import com.quantfolio.backtest.PortfolioBacktest
import com.quantfolio.backtest.RiskCaps
import com.quantfolio.backtest.Tracking
import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.backtest.Signals
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.metrics.UniverseScoreParams
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyJsonDumps
import com.quantfolio.rebalance.Cfg
import com.quantfolio.rebalance.Rebalance
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/**
 * 리밸런싱 실행기(원본 `engine/rebalance_runner.py`). 60초마다 점검:
 * MDD 킬스위치(발동 중이면 즉시 청산·현금 대피) → panic_overlay 거부 → 레짐 히스테리시스(위험회피면 청산) → 발화 판정
 * (정기 cadence·레짐 재진입·콜드스타트·킬스위치 재가동) → 목표비중 → 드리프트 초과분 주문(매도 → 매수).
 * 멱등 키에 실행일·사유 태그가 들어가 같은 날 중복 발화해도 이중 주문이 없다.
 */
class RebalanceRunner(strategyId: Int, ctx: RunnerContext) : BaseRunner(strategyId, ctx) {
    override val log = LoggerFactory.getLogger("engine.rebalance_runner")!!
    override val label = "리밸런싱 전략"
    override val tickWord = "점검"
    override val pollIntervalSec = 60L
    override val tickTimeoutSec = 300L

    private val json = JsonMapper.builder().build()
    private var pitFallbackActive = false
    private var panicAlerted = false
    private var sectorMap: Map<String, String>? = null

    private val selection get() = Cfg.map(cfg, "selection")

    @Suppress("UNCHECKED_CAST")
    private val rule get() = (selection["universe_rule"] as? Map<String, Any?>).orEmpty()

    override fun logStart() {
        val src = Cfg.get(rule, "source", "fixed").toString()
        val pool = if (src != "fixed") src else "${(cfg["universe"] as? List<*>).orEmpty().size}종목"
        log.info("리밸런싱 전략 {} 실행 시작 (universe={}, {})", strategyId, pool, cfg["cadence"])
    }

    override fun tickOnce() {
        val now = MarketClock.nowKst(ctx.clock)
        val rf = Cfg.map(cfg, "regime_filter")
        val regimeEnabled = Cfg.truthy(rf["enabled"])
        val marketOpen = ctx.calendar.isMarketOpen(now)
        log.info("전략 {} 틱 시작 (now={}, regime_enabled={}, market_open={})", strategyId, now, regimeEnabled, marketOpen)

        val riskLayer = Cfg.map(cfg, "risk_layer")
        val mddKill = riskLayer["mdd_kill_pct"]?.takeIf { Cfg.truthy(it) }?.let { Cfg.num(it) }
        if (mddKill != null && marketOpen) {
            if (evaluateMddKill(mddKill, Cfg.int(Cfg.or(riskLayer, "mdd_rearm_days", 20)))) {
                log.info("리밸런싱 전략 {} MDD 킬스위치 활성 — 즉시 청산·현금 대피(쿨다운 유지)", strategyId)
                rebalanceOnce(now, riskOff = true, barTag = "mdd", liqKind = "mdd")
                return
            }
        }
        if (Cfg.truthy(Cfg.map(cfg, "panic_overlay")["enabled"])) {
            alertPanicUnsupported()
            clearRearmPending()
            return
        }

        var riskOff = false
        var reentry = false
        if (regimeEnabled && marketOpen) {
            riskOff = isRiskOff()
            val prev = getRegime()
            setRegime(riskOff)
            if (!riskOff && prev == true && !hasHoldings()) reentry = true
        }
        if (riskOff) {
            log.info("리밸런싱 전략 {} 레짐 위험회피 — 즉시 청산 점검", strategyId)
            rebalanceOnce(now, riskOff = true, barTag = "regime")
            return
        }

        val last = getLast()
        val due = Rebalance.isRebalanceDue(cfg, last, now, marketOpen)
        var rearm = false
        if (mddKill != null && marketOpen) {
            val state = mddState()
            if (state?.get("rearm_pending") == true) {
                rearm = !hasHoldings()
                if (!rearm) clearRearmPending()
            }
        }
        log.info("전략 {} 발화 판정: last={} due={} reentry={} rearm={}", strategyId, last, due, reentry, rearm)
        val bootstrap = !due && Cfg.truthy(cfg["initial_fill_immediate"]) && last == null && marketOpen && !hasHoldings()
        if (!(due || reentry || bootstrap || rearm)) return
        val (kind, tag) = when {
            due -> "정기 발화" to "rebal"
            bootstrap -> "콜드스타트 즉시 발화" to "rebal"
            reentry -> "레짐 재진입" to "regime"
            else -> "MDD 쿨다운 해제 재진입" to "rearm"
        }
        log.info("리밸런싱 전략 {} {} ({})", strategyId, kind, now)
        rebalanceOnce(now, riskOff = false, barTag = tag)
        if (due || bootstrap) setLast(now) // 재진입은 cadence 스케줄을 소비하지 않는다
        if (rearm) clearRearmPending()
    }

    // ── Redis 상태 ──
    private fun getLast(): ZonedDateTime? = ctx.redis.opsForValue().get(RedisKeys.rebalanceLast(strategyId))?.let {
        runCatching { OffsetDateTime.parse(it).atZoneSameInstant(MarketClock.KST) }.getOrNull()
    }

    private fun setLast(t: ZonedDateTime) = ctx.redis.opsForValue().set(RedisKeys.rebalanceLast(strategyId), pyIsoformat(t), STATE_TTL)

    private fun getRegime(): Boolean? = ctx.redis.opsForValue().get(RedisKeys.rebalanceRegime(strategyId))?.let { it == "1" }

    private fun setRegime(off: Boolean) = ctx.redis.opsForValue().set(RedisKeys.rebalanceRegime(strategyId), if (off) "1" else "0", STATE_TTL)

    fun mddState(): Map<String, Any?>? = ctx.redis.opsForValue().get(RedisKeys.mddState(strategyId))?.let {
        runCatching { json.readValue<Map<String, Any?>>(it) }.getOrNull()?.takeIf { s -> "hwm" in s }
    }

    private fun setMddState(hwm: Double, killed: Boolean, killDate: LocalDate?, rearmPending: Boolean = false) =
        ctx.redis.opsForValue().set(
            RedisKeys.mddState(strategyId),
            pyJsonDumps(linkedMapOf("hwm" to hwm, "killed" to killed, "kill_date" to killDate?.toString(), "rearm_pending" to rearmPending)),
            STATE_TTL,
        )

    private fun clearRearmPending() {
        val s = mddState() ?: return
        if (s["rearm_pending"] != true) return
        setMddState(Cfg.num(s["hwm"]), s["killed"] == true, (s["kill_date"] as String?)?.let(LocalDate::parse), false)
    }

    private fun hasHoldings(): Boolean = ctx.jdbc.queryForObject(
        "select exists(select 1 from positions where user_id = :u and strategy_id = :s and qty > 0)",
        MapSqlParameterSource("u", userId).addValue("s", strategyId), Boolean::class.java,
    ) == true

    // ── MDD 킬스위치 ──

    /** 실시간 자산가치 = 체결 재생 현금 + 보유 평가(현재가 → 로컬 최근 종가 → 평단 순, 평단은 과대평가 위험 경고). */
    private fun liveEquity(): Double {
        val capital = Cfg.num(Cfg.get(cfg, "capital", 10_000_000))
        val execs = ctx.jdbc.query(
            "select o.symbol, o.side, e.filled_qty, e.filled_price, e.fee, e.executed_at from executions e join orders o on e.order_id = o.id " +
                "where o.user_id = :u and o.strategy_id = :s and o.status in ('partial', 'filled') order by o.created_at, o.id, e.id",
            MapSqlParameterSource("u", userId).addValue("s", strategyId),
        ) { rs, _ ->
            Tracking.Execution(FillQualityReport.kstDate(rs.getTimestamp(6).toInstant()), rs.getString(1), rs.getString(2), rs.getBigDecimal(3).toDouble(), rs.getBigDecimal(4).toDouble(), rs.getBigDecimal(5).toDouble())
        }.filter { it.qty > 0 && it.price > 0 }
        val cash = Tracking.replayCashBalance(execs, capital)
        val positions = ctx.jdbc.query(
            "select symbol, qty, avg_price from positions where user_id = :u and strategy_id = :s and qty > 0",
            MapSqlParameterSource("u", userId).addValue("s", strategyId),
        ) { rs, _ -> Triple(rs.getString(1), rs.getBigDecimal(2), rs.getBigDecimal(3)) }
        if (positions.isEmpty()) return cash
        val prices = quotes(positions.map { it.first }.toSet())
        val missing = positions.map { it.first }.filter { it !in prices }
        val lastCloses = if (missing.isEmpty()) emptyMap() else lastKnownCloses(missing)
        var mv = 0.0
        for ((sym, qty, avg) in positions) {
            val price = prices[sym]?.toDouble() ?: lastCloses[sym] ?: run {
                log.warn("전략 {} {} 현재가·로컬 종가 모두 없음 — 평단가로 평가(자산가치 과대평가 위험)", strategyId, sym)
                avg.toDouble()
            }
            mv += qty.toDouble() * price
        }
        return cash + mv
    }

    private fun lastKnownCloses(symbols: List<String>): Map<String, Double> {
        val end = ctx.clock.instant()
        val start = end.minus(Duration.ofDays(30))
        return symbols.mapNotNull { s -> ctx.ticks.bars(s, start, end).lastOrNull()?.let { s to it.close } }.toMap()
    }

    private fun businessDaysSince(start: LocalDate, end: LocalDate): Int {
        if (!end.isAfter(start)) return 0
        var n = 0
        var d = start.plusDays(1)
        while (!d.isAfter(end)) {
            if (ctx.calendar.isBusinessDay(d)) n++
            d = d.plusDays(1)
        }
        return n
    }

    /**
     * 고점(HWM) 대비 낙폭이 임계를 넘으면 발동(전량 청산·현금 대피), 영업일 rearm_days 경과하면 재가동(고점 리셋 + 강제 재진입 예약).
     * 상태가 없으면 현재 자산가치를 고점으로 시작한다(안전측 — 오발동 방지). 발동 중이면 true.
     */
    private fun evaluateMddKill(killPct: Double, rearmDays: Int): Boolean {
        val equity = liveEquity()
        val today = MarketClock.todayKst(ctx.clock)
        val s = mddState()
        var hwm = s?.let { Cfg.num(it["hwm"]) } ?: equity
        var killed = s?.get("killed") == true
        var killDate = (s?.get("kill_date") as String?)?.let(LocalDate::parse)
        var rearmPending = s?.get("rearm_pending") == true
        if (equity > hwm) hwm = equity
        if (killed && killDate != null && businessDaysSince(killDate, today) >= rearmDays) {
            killed = false
            killDate = null
            hwm = equity
            rearmPending = true
            log.info("리밸런싱 전략 {} MDD 킬스위치 쿨다운 경과 — 재가동(고점 리셋 {}), cadence 무관 재진입 예약", strategyId, pyFixed(hwm, 0))
        }
        if (!killed && hwm > 0 && equity / hwm - 1.0 <= -killPct) {
            killed = true
            killDate = today
            rearmPending = false
            val dd = (equity / hwm - 1.0) * 100
            log.warn("리밸런싱 전략 {} MDD 킬스위치 발동 — 자산가치 {} / 고점 {} (낙폭 {}% ≤ −{}%) → 전량 청산·현금 대피", strategyId, pyFixed(equity, 0), pyFixed(hwm, 0), pyFixed(dd, 2), pyFixed(killPct * 100, 2))
            ctx.alerts.publish(
                userId, strategyId, "critical",
                "MDD 킬스위치 발동 — 고점 ${won(hwm)} 대비 낙폭 ${pyFixed(dd, 2)}%가 임계 −${pyFixed(killPct * 100, 1)}%를 넘어 전량 청산·현금 대피", "mdd_kill",
            )
        }
        setMddState(hwm, killed, killDate, rearmPending)
        return killed
    }

    // ── 계획·주문 ──

    /** (후보풀, 목표비중). 위험회피면 목표 없음(전량 청산). */
    private fun computePlan(asOf: LocalDate, riskOff: Boolean): Pair<List<String>, Map<String, Double>> {
        val pool = resolveUniverse(asOf)
        log.info("전략 {} 후보풀 확보: {}종목", strategyId, pool.size)
        if (riskOff) return pool to emptyMap()
        val method = selection["method"]
        val weighting = Cfg.get(cfg, "weighting", "equal")
        val riskLayer = Cfg.map(cfg, "risk_layer")
        val wantVolTarget = Cfg.truthy(riskLayer["target_vol"])
        @Suppress("UNCHECKED_CAST")
        val volGate = (selection["vol_gate"] as? Map<String, Any?>).orEmpty()
        val hasRuleType = Cfg.truthy(rule["type"])
        var history: Map<String, Series> = emptyMap()
        if (hasRuleType || method in setOf("momentum", "custom", "all") || weighting == "inverse_vol" || wantVolTarget || volGate.isNotEmpty()) {
            var minBars = if (hasRuleType) Cfg.int(Cfg.get(rule, "lookback", 0)) + 1 else 0
            if (weighting == "inverse_vol") minBars = maxOf(minBars, 253)
            if (wantVolTarget) minBars = maxOf(minBars, Cfg.int(Cfg.or(riskLayer, "vol_lookback", 20)) + 1)
            if (volGate.isNotEmpty()) minBars = maxOf(minBars, Cfg.int(Cfg.or(volGate, "base_lookback", 252)) + 1)
            history = seedHistory(pool, minBars)
        }
        val universe = if (hasRuleType) PortfolioBacktest.dynamicUniverse(Panel.fromSeries(history), pool, rule) else pool
        val planCfg = cfg + ("universe" to universe)
        var scores: Map<String, Double>? = null
        if (method == "score") {
            @Suppress("UNCHECKED_CAST")
            val fw = (selection["factor_weights"] as? Map<String, Any?>)?.mapValues { Cfg.num(it.value) }
            scores = ctx.scores.compute(
                universe, asOf,
                UniverseScoreParams(
                    fw, Cfg.get(selection, "neutralize", "none").toString(), Cfg.get(cfg, "financial_period", "annual").toString(),
                    Cfg.int(Cfg.get(selection, "flow_window", 90)), Cfg.get(selection, "flow_denom", "mcap").toString(),
                    Cfg.int(Cfg.get(selection, "resid_mom_reg_window", 36)), Cfg.int(Cfg.get(selection, "resid_mom_window", 11)),
                    Cfg.int(Cfg.get(selection, "resid_mom_skip", 1)), Cfg.int(Cfg.get(selection, "pead_lookback_q", 8)),
                ),
            )
        }
        var targets = Rebalance.targetWeights(history, planCfg, scores)
        if (riskLayer.isNotEmpty() && targets.isNotEmpty()) {
            val smap = if (Cfg.truthy(riskLayer["max_sector_pct"])) sectors() else null
            targets = RiskCaps.apply(targets, Panel.fromSeries(history), riskLayer, smap)
        }
        return pool to targets
    }

    private fun sectors(): Map<String, String> {
        sectorMap?.takeIf { it.isNotEmpty() }?.let { return it }
        val smap = try {
            ctx.krx.sectorMap()
        } catch (e: DataSourceError) {
            log.error("전략 {} 섹터 한도용 업종 매핑 조회 실패 — 섹터 캡 미적용: {}", strategyId, e.message)
            return emptyMap() // 실패는 캐시하지 않는다
        }
        if (smap.isNotEmpty()) sectorMap = smap else log.warn("전략 {} 섹터 한도 설정됨이나 업종 매핑 미확보 — 섹터 캡 미적용.", strategyId)
        return smap
    }

    fun rebalanceOnce(now: ZonedDateTime, riskOff: Boolean, barTag: String = "rebal", liqKind: String = "regime") {
        val asOf = ctx.common.lastBusinessDay()
        val (pool, targets) = computePlan(asOf, riskOff)
        if (riskOff) {
            log.info("리밸런싱 전략 {} 레짐 위험회피 — 보유 청산·신규 매수 중단", strategyId)
        } else if (targets.isEmpty()) {
            log.warn("리밸런싱 전략 {} 선정 종목 없음(데이터 부족) — 건너뜀", strategyId)
            ctx.alerts.publish(userId, strategyId, "warning", "리밸런싱 발화 시점에 선정 종목이 하나도 산정되지 않아 이번 리밸런싱을 건너뜁니다(팩터/가격 데이터 조회 장애 가능). 다음 주기 재시도.", "factor_outage")
            return
        }
        val positions = holdings(pool)
        if (targets.isEmpty() && positions.isEmpty()) {
            log.info("리밸런싱 전략 {} 레짐 위험회피 & 보유 없음 — 매매 없음", strategyId)
            return
        }
        val prices = quotes(targets.keys + positions.keys)
        val drift = if (riskOff) 0.0 else Cfg.num(Cfg.get(cfg, "drift_band_pct", 0.05))
        val orders = Rebalance.orders(targets, positions.mapValues { it.value.toInt() }, prices.mapValues { it.value.toDouble() }, Cfg.num(Cfg.get(cfg, "capital", 10_000_000)), drift)
        if (orders.isEmpty()) {
            log.info("리밸런싱 전략 {} 드리프트 밴드 내 — 매매 없음", strategyId)
            return
        }
        executeOrders(orders, prices, "${now.toLocalDate()}:$barTag", targets, riskOff, liqKind)
    }

    private fun sellReason(sym: String, targets: Map<String, Double>, riskOff: Boolean, qty: Int, liqKind: String): String {
        if (riskOff) {
            return if (liqKind == "mdd") "MDD 킬스위치 발동(고점 대비 낙폭 임계 초과): ${grouped(qty)}주 전량 청산·현금 대피" else "레짐 위험회피(현금화): ${grouped(qty)}주 전량 청산"
        }
        val w = targets[sym] ?: 0.0
        if (w <= 0) return "리밸런싱 선정 제외(목표비중 0%): ${grouped(qty)}주 전량 청산"
        return "리밸런싱 비중 축소: 목표비중 ${pyFixed(w * 100, 1)}%로 조정 (드리프트 밴드 초과분 ${grouped(qty)}주 매도)"
    }

    /**
     * 기준지수 vs 이동평균 히스테리시스(백테스트 `regimeOnFlags` 와 같은 규약): 직전 상태가 없으면 무상태 판정,
     * on 이면 MA×(1−exit) 아래로 내려갈 때만 off, off 면 MA×(1+reentry) 이상 회복할 때만 on. 조회 실패·데이터 부족은 투자 유지.
     */
    private fun isRiskOff(): Boolean {
        val rf = Cfg.map(cfg, "regime_filter")
        if (!Cfg.truthy(rf["enabled"])) return false
        val ma = Cfg.int(Cfg.get(rf, "ma_period", 200))
        val exitB = maxOf(0.0, Cfg.num(Cfg.or(rf, "exit_buffer_pct", 0.0)))
        val reB = maxOf(0.0, Cfg.num(Cfg.or(rf, "reentry_buffer_pct", 0.0)))
        val ticker = mapOf("KOSPI" to "1001", "KOSDAQ" to "2001")[Cfg.get(rf, "index", "KOSPI")] ?: "1001"
        val asOf = ctx.common.lastBusinessDay()
        val bars = ctx.snapshots.indexOhlcv(MetricsCommon.approxStart(asOf, ma + 10), asOf, ticker)
        if (bars.isNullOrEmpty()) {
            log.warn("리밸런싱 전략 {} 레짐 기준지수 조회 실패 — 오버레이 미적용(투자 유지)", strategyId)
            return false
        }
        val close = bars.mapNotNull { it.close?.takeIf { c -> !c.isNaN() } }
        if (close.size < ma) {
            log.warn("리밸런싱 전략 {} 레짐 기준지수 데이터 부족({}<{}) — 투자 유지", strategyId, close.size, ma)
            return false
        }
        val avg = close.takeLast(ma).average()
        val last = close.last()
        val off = when (getRegime()) {
            null -> last < avg
            false -> last < avg * (1.0 - exitB)
            true -> !(last >= avg * (1.0 + reB))
        }
        log.info("리밸런싱 전략 {} 레짐 판정(히스테리시스): 지수={} MA{}={} → {}", strategyId, pyFixed(last, 1), ma, pyFixed(avg, 1), if (off) "위험회피(현금화)" else "위험선호(투자)")
        return off
    }

    /** universe_rule.source 가 지수면 그 시점 구성종목(PIT). 조회 실패·빈 결과면 config.universe 폴백 + 1회 경보(재발은 해소 후). */
    private fun resolveUniverse(asOf: LocalDate): List<String> {
        val source = Cfg.get(rule, "source", "fixed").toString()
        @Suppress("UNCHECKED_CAST")
        val fixed = (cfg["universe"] as? List<String>).orEmpty()
        if (source == "fixed") return fixed
        var members = try {
            ctx.krx.indexMembers(asOf, source)
        } catch (e: Exception) {
            log.warn("전략 {} PIT 유니버스({}) 조회 실패 — config.universe 폴백: {}", strategyId, source, e.message)
            alertPitFallback(source, "조회 실패(${e.message})")
            return fixed
        }
        if (members.isEmpty()) {
            log.warn("전략 {} PIT 유니버스({}) 비어있음 — config.universe 폴백", strategyId, source)
            alertPitFallback(source, "구성종목 비어있음")
            return fixed
        }
        pitFallbackActive = false
        rule["min_market_cap"]?.takeIf { Cfg.truthy(it) }?.let { min ->
            val caps = ctx.krx.marketCaps(asOf)
            if (caps.isNotEmpty()) members = members.filter { (caps[it] ?: 0L) >= Cfg.num(min).toLong() * 100_000_000L }
        }
        return members
    }

    private fun alertPitFallback(source: String, cause: String) {
        if (pitFallbackActive) return
        pitFallbackActive = true
        ctx.alerts.publish(userId, strategyId, "warning", "PIT 유니버스($source) $cause — config.universe 고정 폴백으로 전환. 생존편향 검증 원칙상 지수 구성 이탈은 즉시 점검이 필요합니다.", "pit_fallback")
    }

    /** panic_overlay 는 백테스트에만 구현 — 실거래에서 재현할 수 없으니 매매를 중단한다(보유 유지, 킬스위치는 계속). 1회 경보. */
    private fun alertPanicUnsupported() {
        if (panicAlerted) return
        panicAlerted = true
        log.error("리밸런싱 전략 {} panic_overlay 가 설정돼 있으나 실거래 엔진에 구현이 없어 매매를 중단한다(보유 유지).", strategyId)
        ctx.alerts.publish(
            userId, strategyId, "critical",
            "panic_overlay 는 백테스트에만 구현돼 있어 실거래에서 재현할 수 없습니다. 무시하고 매매하면 base_exposure·event_only 를 반영하지 않아 백테스트보다 " +
                "공격적으로 운용되므로, 이 전략의 매매를 중단합니다(기존 보유는 유지, MDD 킬스위치는 계속 동작). 실거래하려면 panic_overlay 를 해제하십시오.",
            "panic_overlay_unsupported",
        )
    }

    private fun seedHistory(universe: List<String>, minBars: Int): Map<String, Series> {
        var need = Cfg.int(Cfg.get(selection, "lookback", 120)) + 1
        if (selection["method"] == "custom") need = maxOf(need, Signals.minPeriods(mapOf("type" to "custom", "entry" to selection["entry"], "exit" to selection["exit"])))
        need = maxOf(need, minBars)
        val end = ctx.clock.instant()
        val start = end.minus(Duration.ofDays(need * 3L))
        val out = linkedMapOf<String, Series>()
        universe.forEachIndexed { i, sym ->
            var bars = ctx.ticks.bars(sym, start, end)
            if (bars.size < need) {
                try {
                    ctx.ticks.upsert(sym, ctx.loader.load(sym, start.atZone(ZoneOffset.UTC).toLocalDate(), end.atZone(ZoneOffset.UTC).toLocalDate()))
                    bars = ctx.ticks.bars(sym, start, end)
                } catch (e: Exception) {
                    log.warn("{} 리밸런싱 시드 적재 실패: {}", sym, e.message)
                }
            }
            out[sym] = Series(bars.map { it.time.atZone(ZoneOffset.UTC).toLocalDate() }, DoubleArray(bars.size) { bars[it].close })
            if ((i + 1) % 20 == 0 || i + 1 == universe.size) log.info("전략 {} 히스토리 시딩 진행: {}/{}", strategyId, i + 1, universe.size)
        }
        return out
    }

    private fun holdings(universe: List<String>): Map<String, BigDecimal> {
        if (universe.isEmpty()) return emptyMap()
        return ctx.jdbc.query(
            "select symbol, qty from positions where user_id = :u and strategy_id = :s and qty > 0 and symbol in (:syms)",
            MapSqlParameterSource("u", userId).addValue("s", strategyId).addValue("syms", universe),
        ) { rs, _ -> rs.getString(1) to rs.getBigDecimal(2) }.toMap()
    }

    /** 종목별 현재가. 실패·0 이하는 이번 리밸런싱에서 제외(결측을 0 원으로 흘리지 않는다). */
    private fun quotes(symbols: Set<String>): Map<String, BigDecimal> = symbols.sorted().mapNotNull { sym ->
        val q = try {
            broker.getQuote(sym)
        } catch (e: Exception) {
            log.warn("{} 현재가 조회 실패 — 이번 리밸런싱에서 제외: {}", sym, e.message)
            return@mapNotNull null
        }
        if (q.price.signum() <= 0) {
            log.warn("{} 현재가 0 이하({}) — 결측으로 간주해 제외", sym, q.price)
            null
        } else {
            sym to q.price
        }
    }.toMap()

    /** 매도 먼저 → 매수(일일 손실 한도 초과면 매수 중단). 각 종목은 (사용자, 종목) 락 안에서. */
    private fun executeOrders(orders: List<Triple<String, String, Int>>, prices: Map<String, BigDecimal>, barTs: String, targets: Map<String, Double>, riskOff: Boolean, liqKind: String) {
        val ranked = targets.entries.sortedByDescending { it.value }.map { it.key }
        val rankOf = ranked.withIndex().associate { (i, s) -> s to i + 1 }
        val daily = ctx.risk.checkDailyLoss(userId!!, strategyId, prices)
        if (!daily.approved) log.info("리밸런싱 매수 차단: {}", daily.reason)
        for ((sym, side, qty) in orders) {
            val price = prices[sym] ?: continue
            if (qty <= 0) continue
            val (acquired, _) = withPositionLock(sym) {
                if (side == "sell") {
                    val pos = ctx.jdbc.query(
                        "select qty, avg_price from positions where user_id = :u and strategy_id = :s and symbol = :sym",
                        MapSqlParameterSource("u", userId).addValue("s", strategyId).addValue("sym", sym),
                    ) { rs, _ -> rs.getBigDecimal(1) to rs.getBigDecimal(2) }.firstOrNull()
                    val sellQty = minOf(qty, pos?.first?.toInt() ?: 0)
                    if (sellQty <= 0) return@withPositionLock
                    var pnl = ""
                    val avg = pos?.second
                    if (avg != null && avg.signum() > 0) {
                        val r = (price - avg).divide(avg, MathContext.DECIMAL128).toDouble() * 100
                        pnl = " · 평단 ${won(avg.toDouble())} 대비 손익 ${if (r >= 0) "+" else ""}${pyFixed(r, 2)}%"
                    }
                    place(sym, "sell", sellQty, price, barTs, sellReason(sym, targets, riskOff, sellQty, liqKind) + pnl)
                } else {
                    if (!daily.approved) return@withPositionLock
                    val d = ctx.risk.evaluateBuy(userId!!, strategyId, sym, price, BigDecimal(qty) * price)
                    if (!d.approved) {
                        log.info("리밸런싱 매수 보류 {}: {}", sym, d.reason)
                        return@withPositionLock
                    }
                    val rank = rankOf[sym]?.let { "편입 $it/${ranked.size}위, " } ?: ""
                    place(sym, "buy", d.qty, price, barTs, "리밸런싱 ${rank}목표비중 ${pyFixed((targets[sym] ?: 0.0) * 100, 1)}% 구성 · ${grouped(d.qty)}주 @ ${won(price.toDouble())} 매수")
                }
            }
            if (!acquired) log.info("포지션 락 경합 — {} 이번 리밸런싱 건너뜀", sym)
        }
    }

    private companion object {
        val STATE_TTL: Duration = Duration.ofDays(90)
    }
}
