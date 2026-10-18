package com.quantfolio.strategy

import com.quantfolio.strategy.Ty.BoolT
import com.quantfolio.strategy.Ty.FloatT
import com.quantfolio.strategy.Ty.IntT
import com.quantfolio.strategy.Ty.Lit
import com.quantfolio.strategy.Ty.ListT
import com.quantfolio.strategy.Ty.ModelT
import com.quantfolio.strategy.Ty.RuleNodeT
import com.quantfolio.strategy.Ty.StrT
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyFloat
import com.quantfolio.quant.pyRepr
import kotlin.math.abs

/**
 * 전략 설정 스키마(원본 `schemas/strategy.py`). 원본은 생성·수정 시 이 검증을 통과한 **기본값 채운 dump** 를 DB 에 저장하고,
 * 백테스트는 그 dict 를 그대로 읽는다. 제약 값은 원본에 적힌 타입 그대로(Int/Double) — 오류 문구 숫자 표기가 같아진다.
 */
object StrategySchemas {
    // ── 단일종목 공통(_BaseConfig) ──
    private val BASE = listOf(
        F("symbol", StrT, minLen = 6, maxLen = 20),
        F("cash", FloatT, 10_000_000, gt = 0),
        F("fees", FloatT, 0.00015, ge = 0, le = 0.01),
        F("tax", FloatT, 0.002, ge = 0, le = 0.01),
        F("stop_loss_pct", FloatT, null, nullable = true, gt = 0, le = 1),
        F("take_profit_pct", FloatT, null, nullable = true, gt = 0, le = 1),
        F("trailing_stop_pct", FloatT, null, nullable = true, gt = 0, le = 1),
        F("fill_mode", Lit(listOf("next_close", "same_close")), "next_close"),
        F("slippage_bps", FloatT, 5.0, ge = 0, le = 200),
        F("slippage_vol_scale", FloatT, 0.0, ge = 0, le = 5),
        F("risk_free_rate", FloatT, 0.0, ge = 0, le = 0.2),
    )

    private fun lessThan(a: String, b: String, message: String): (MutableMap<String, Any?>) -> Unit = { m ->
        if ((m[a] as Number).toDouble() >= (m[b] as Number).toDouble()) throw ValueError(message)
    }

    private fun single(type: String, params: List<F>, vararg validators: (MutableMap<String, Any?>) -> Unit) =
        type to Model(type, BASE + params + F("type", Lit(listOf(type)), type), validators.toList())

    // ── 조건 빌더 ──
    private val OPERAND = Model(
        "Operand",
        listOf(
            F("kind", Lit(listOf("price", "const", "sma", "ema", "rsi", "macd_line", "macd_signal"))),
            F("source", Lit(listOf("close", "open", "high", "low", "volume")), null, nullable = true),
            F("value", FloatT, null, nullable = true),
            F("period", IntT, null, nullable = true, ge = 1, le = 480),
            F("fast", IntT, null, nullable = true, ge = 1, le = 240),
            F("slow", IntT, null, nullable = true, ge = 2, le = 480),
            F("signal", IntT, null, nullable = true, ge = 1, le = 120),
        ),
        listOf { m ->
            val kind = m["kind"]
            if (kind == "price" && m["source"] == null) throw ValueError("price 피연산자는 source 가 필요합니다.")
            if (kind == "const" && m["value"] == null) throw ValueError("const 피연산자는 value 가 필요합니다.")
            if (kind in setOf("sma", "ema", "rsi") && m["period"] == null) throw ValueError("$kind 피연산자는 period 가 필요합니다.")
            if (kind in setOf("macd_line", "macd_signal")) {
                if (m["fast"] == null || m["slow"] == null || m["signal"] == null) throw ValueError("MACD 피연산자는 fast/slow/signal 이 필요합니다.")
                if ((m["fast"] as Int) >= (m["slow"] as Int)) throw ValueError("MACD fast 는 slow 보다 작아야 합니다.")
            }
        },
    )

    val CONDITION = Model("Condition", listOf(F("left", ModelT(OPERAND)), F("op", Lit(listOf(">", "<", ">=", "<="))), F("right", ModelT(OPERAND))))

    val CONDITION_GROUP: Model = Model(
        "ConditionGroup",
        listOf(F("combinator", Lit(listOf("and", "or")), "and"), F("children", ListT(RuleNodeT), minLen = 1)),
    )

    /** 레거시 리스트형 entry/exit → and 그룹. */
    private val wrapLegacyList: (Any?) -> Any? = { v -> if (v is List<*>) mapOf("combinator" to "and", "children" to v) else v }

    private val SINGLES: Map<String, Model> = mapOf(
        single("sma_crossover", listOf(F("fast", IntT, 5, ge = 1, le = 240), F("slow", IntT, 20, ge = 2, le = 480)),
            lessThan("fast", "slow", "fast 기간은 slow 기간보다 작아야 합니다.")),
        single("ema_crossover", listOf(F("fast", IntT, 12, ge = 1, le = 240), F("slow", IntT, 26, ge = 2, le = 480)),
            lessThan("fast", "slow", "fast 기간은 slow 기간보다 작아야 합니다.")),
        single("rsi", listOf(F("period", IntT, 14, ge = 2, le = 240), F("lower", FloatT, 30, ge = 1, le = 99), F("upper", FloatT, 70, ge = 1, le = 99)),
            lessThan("lower", "upper", "RSI lower 는 upper 보다 작아야 합니다.")),
        single("macd", listOf(F("fast", IntT, 12, ge = 1, le = 240), F("slow", IntT, 26, ge = 2, le = 480), F("signal", IntT, 9, ge = 1, le = 120)),
            lessThan("fast", "slow", "MACD fast 는 slow 보다 작아야 합니다.")),
        single("bollinger", listOf(F("period", IntT, 20, ge = 2, le = 240), F("num_std", FloatT, 2.0, gt = 0, le = 5))),
        single("breakout", listOf(F("period", IntT, 20, ge = 2, le = 480))),
        single("momentum", listOf(F("lookback", IntT, 120, ge = 1, le = 480))),
        single("zscore", listOf(F("period", IntT, 20, ge = 2, le = 240), F("entry", FloatT, 2.0, gt = 0, le = 5))),
        single("disparity", listOf(F("period", IntT, 20, ge = 2, le = 240), F("lower", FloatT, 95.0, ge = 1, le = 200), F("upper", FloatT, 105.0, ge = 1, le = 200)),
            lessThan("lower", "upper", "이격도 lower 는 upper 보다 작아야 합니다.")),
        single("donchian_squeeze", listOf(F("period", IntT, 20, ge = 3, le = 480), F("bb_mult", FloatT, 1.0, gt = 0, le = 10), F("kc_mult", FloatT, 1.3, gt = 0, le = 10))),
        single("trix", listOf(F("period", IntT, 15, ge = 1, le = 240), F("signal_period", IntT, 9, ge = 1, le = 120))),
        single("obv_trend", listOf(F("period", IntT, 20, ge = 2, le = 480))),
        single("atr_trailing", listOf(F("period", IntT, 22, ge = 2, le = 480), F("atr_period", IntT, 22, ge = 2, le = 240), F("k", FloatT, 3.0, gt = 0, le = 20))),
        single("volatility_breakout", listOf(F("k", FloatT, 0.5, gt = 0, le = 5))),
        single("keltner", listOf(F("ema_period", IntT, 20, ge = 2, le = 240), F("atr_period", IntT, 10, ge = 2, le = 240), F("mult", FloatT, 2.0, gt = 0, le = 10))),
        single("stochastic", listOf(F("k_period", IntT, 14, ge = 2, le = 240), F("d_period", IntT, 3, ge = 1, le = 60), F("lower", FloatT, 20.0, ge = 1, le = 99), F("upper", FloatT, 80.0, ge = 1, le = 99)),
            lessThan("lower", "upper", "스토캐스틱 lower 는 upper 보다 작아야 합니다.")),
        single("custom", listOf(F("entry", ModelT(CONDITION_GROUP), before = wrapLegacyList), F("exit", ModelT(CONDITION_GROUP), before = wrapLegacyList))),
    )

    // ── 리밸런싱 ──
    private val FACTOR_KEYS = listOf("momentum", "value", "lowvol", "quality", "growth", "flow", "residual_momentum", "pead")

    private val FACTOR_WEIGHTS = Model(
        "FactorWeights",
        listOf(
            F("momentum", FloatT, 0.4, ge = 0, le = 1), F("value", FloatT, 0.3, ge = 0, le = 1), F("lowvol", FloatT, 0.3, ge = 0, le = 1),
            F("quality", FloatT, 0.0, ge = 0, le = 1), F("growth", FloatT, 0.0, ge = 0, le = 1), F("flow", FloatT, 0.0, ge = 0, le = 1),
            F("residual_momentum", FloatT, 0.0, ge = 0, le = 1), F("pead", FloatT, 0.0, ge = 0, le = 1),
        ),
        listOf { m ->
            val total = FACTOR_KEYS.sumOf { (m[it] as Number).toDouble() }
            if (abs(total - 1.0) > 1e-6) {
                throw ValueError("factor_weights 합은 1.0 이어야 합니다(momentum+value+lowvol+quality+growth+flow+residual_momentum+pead=${pyFixed(total, 4)}).")
            }
        },
    )

    private val UNIVERSE_RULE = Model(
        "UniverseRule",
        listOf(
            F("type", Lit(listOf("momentum")), "momentum"),
            F("source", Lit(listOf("fixed", "KOSPI200", "KOSPI100", "KRX300")), "fixed"),
            F("lookback", IntT, 120, ge = 20, le = 480),
            F("pick", IntT, 20, ge = 1, le = 100),
            F("min_market_cap", IntT, null, nullable = true, ge = 0),
        ),
    )

    private val VOL_GATE = Model(
        "VolGate",
        listOf(
            F("spike_lookback", IntT, 20, ge = 5, le = 252), F("base_lookback", IntT, 252, ge = 5, le = 1000),
            F("spike_min", FloatT, 1.4, gt = 0, le = 100), F("spike_max", FloatT, null, nullable = true, gt = 0, le = 100),
            F("cap", FloatT, 0.9, gt = 0, le = 5), F("require_uptrend", BoolT, true),
        ),
        listOf { m ->
            if ((m["base_lookback"] as Int) < (m["spike_lookback"] as Int)) throw ValueError("base_lookback 은 spike_lookback 이상이어야 합니다")
            val max = m["spike_max"] as Double?
            if (max != null && max < (m["spike_min"] as Number).toDouble()) throw ValueError("spike_max 는 spike_min 이상이어야 합니다")
        },
    )

    private val SELECTION = Model(
        "RebalanceSelection",
        listOf(
            F("method", Lit(listOf("momentum", "all", "score", "custom")), "momentum"),
            F("lookback", IntT, 120, ge = 2, le = 480),
            F("top_n", IntT, 5, ge = 1, le = 50),
            F("factor_weights", ModelT(FACTOR_WEIGHTS), DefaultModel),
            F("flow_window", IntT, 90, ge = 20, le = 250),
            F("flow_denom", Lit(listOf("mcap", "value")), "mcap"),
            F("resid_mom_reg_window", IntT, 36, ge = 12, le = 60),
            F("resid_mom_window", IntT, 11, ge = 3, le = 24),
            F("resid_mom_skip", IntT, 1, ge = 0, le = 3),
            F("pead_lookback_q", IntT, 8, ge = 4, le = 16),
            F("neutralize", Lit(listOf("none", "size", "sector", "size_sector")), "none"),
            F("min_score", FloatT, null, nullable = true),
            F("vol_gate", ModelT(VOL_GATE), null, nullable = true),
            F("universe_rule", ModelT(UNIVERSE_RULE), null, nullable = true),
            F("entry", ModelT(CONDITION_GROUP), null, nullable = true, before = wrapLegacyList),
            F("exit", ModelT(CONDITION_GROUP), null, nullable = true, before = wrapLegacyList),
        ),
    )

    private val REGIME_FILTER = Model(
        "RegimeFilter",
        listOf(
            F("enabled", BoolT, true), F("index", Lit(listOf("KOSPI", "KOSDAQ")), "KOSPI"), F("ma_period", IntT, 200, ge = 5, le = 400),
            F("reentry_buffer_pct", FloatT, 0.0, ge = 0, le = 0.2), F("exit_buffer_pct", FloatT, 0.0, ge = 0, le = 0.2),
        ),
    )

    private val RISK_LAYER = Model(
        "RiskLayer",
        listOf(
            F("max_position_pct", FloatT, null, nullable = true, gt = 0, le = 1),
            F("max_sector_pct", FloatT, null, nullable = true, gt = 0, le = 1),
            F("target_vol", FloatT, null, nullable = true, gt = 0, le = 2),
            F("vol_lookback", IntT, 20, ge = 5, le = 120),
            F("max_leverage", FloatT, 1.0, ge = 0.1, le = 1.0),
            F("mdd_kill_pct", FloatT, null, nullable = true, gt = 0, le = 1),
            F("mdd_rearm_days", IntT, 20, ge = 1, le = 250),
        ),
    )

    private val PANIC_OVERLAY = Model(
        "PanicOverlay",
        listOf(
            F("enabled", BoolT, true), F("market", Lit(listOf("KOSPI", "KOSDAQ")), "KOSPI"),
            F("arm_level", Lit(listOf("caution", "warning", "panic")), "warning"),
            F("arm_window", IntT, 5, ge = 1, le = 60), F("hold_days", IntT, 20, ge = 1, le = 250),
            F("profit_reclaim_pct", FloatT, 0.5, gt = 0, le = 1), F("knife_stop_pct", FloatT, 0.05, gt = 0, le = 1),
            F("base_exposure", FloatT, 0.7, ge = 0, le = 1), F("panic_exposure", FloatT, 1.0, gt = 0, le = 1),
            F("scale_in_confirm", FloatT, 0.5, ge = 0, le = 1), F("ma_recovery_period", IntT, 20, ge = 2, le = 120),
            F("event_only", BoolT, false),
        ),
        listOf { m ->
            if ((m["base_exposure"] as Number).toDouble() > (m["panic_exposure"] as Number).toDouble()) throw ValueError("base_exposure 는 panic_exposure 이하여야 합니다.")
        },
    )

    private val validateUniverse: (Any?) -> Any? = { v ->
        val cleaned = (v as List<*>).map { (it as String).trim() }
        for (s in cleaned) if (s.length !in 6..20) throw ValueError("종목코드 길이가 올바르지 않습니다: ${pyRepr(s)}")
        if (cleaned.toSet().size != cleaned.size) throw ValueError("universe 에 중복 종목코드가 있습니다.")
        cleaned
    }

    private val validateTime: (Any?) -> Any? = { v ->
        val parts = (v as String).split(":")
        val h = parts.getOrNull(0)?.trim()?.toIntOrNull()
        val m = parts.getOrNull(1)?.trim()?.toIntOrNull()
        if (parts.size != 2 || h == null || m == null) throw ValueError("rebalance_time 은 'HH:MM' 형식이어야 합니다.")
        if (h !in 0..23 || m !in 0..59) throw ValueError("rebalance_time 시각 범위가 올바르지 않습니다.")
        "%02d:%02d".format(h, m)
    }

    /** 조건 트리의 모든 피연산자. */
    private fun operands(node: Map<*, *>): Sequence<Map<*, *>> = sequence {
        val children = node["children"] as? List<*>
        if (children != null) {
            for (child in children) yieldAll(operands(child as Map<*, *>))
        } else {
            yield(node["left"] as Map<*, *>)
            yield(node["right"] as Map<*, *>)
        }
    }

    @Suppress("CyclomaticComplexMethod")
    private val rebalanceConsistency: (MutableMap<String, Any?>) -> Unit = { c ->
        val sel = c["selection"] as Map<*, *>
        val rule = sel["universe_rule"] as Map<*, *>?
        val universe = c["universe"] as List<*>
        val method = sel["method"]
        val topN = sel["top_n"] as Int
        val weighting = c["weighting"]
        val drift = (c["drift_band_pct"] as Number).toDouble()
        val pit = rule != null && rule["source"] != "fixed"
        if (universe.isEmpty() && !pit) throw ValueError("universe 가 비어 있습니다(지수 소스가 아니면 종목 목록 필수).")
        if (method == "momentum" || method == "score") {
            if (rule != null) {
                val pick = rule["pick"] as Int
                if (!pit && pick > universe.size) throw ValueError("universe_rule.pick 은 universe(후보풀) 종목 수 이하여야 합니다.")
                if (topN > pick) throw ValueError("selection.top_n 은 universe_rule.pick 이하여야 합니다.")
            } else if (topN > universe.size) {
                throw ValueError("selection.top_n 은 universe 종목 수 이하여야 합니다.")
            }
        }
        if (weighting == "score" && method != "score") throw ValueError("weighting='score' 는 selection.method='score' 일 때만 사용할 수 있습니다.")
        if ((method == "momentum" || method == "score") && topN > 0) {
            val floor = if (weighting == "score") 2.0 / (topN + 1) else 1.0 / topN
            if (drift >= floor) {
                val f = pyFixed(floor, 3)
                throw ValueError(
                    "drift_band_pct(${pyFloat(drift)})가 과대합니다: 선정 ${topN}종목·'$weighting' 비중에선 목표비중이 드리프트 밴드($f) 안에 갇혀 " +
                        "매매가 발생하지 않습니다. drift_band_pct 를 $f 미만으로 낮추세요.",
                )
            }
        }
        if (method == "custom") {
            val entry = sel["entry"] as Map<*, *>?
            val exit = sel["exit"] as Map<*, *>?
            if (entry == null || exit == null) throw ValueError("selection.method='custom' 은 entry·exit 규칙이 모두 필요합니다.")
            if (weighting != "equal" && weighting != "inverse_vol") throw ValueError("selection.method='custom' 은 weighting='equal' 또는 'inverse_vol' 만 지원합니다.")
            val ohlc = setOf("open", "high", "low", "volume")
            for (grp in listOf(entry, exit)) {
                for (op in operands(grp)) {
                    if (op["kind"] == "price" && op["source"] in ohlc) {
                        throw ValueError("리밸런싱 커스텀 규칙은 종가 기반 지표만 사용할 수 있습니다(open/high/low/volume 불가).")
                    }
                }
            }
        }
        val maxPos = (c["risk_layer"] as Map<*, *>?)?.get("max_position_pct") as Double?
        if (maxPos != null && maxPos <= drift) {
            throw ValueError(
                "risk_layer.max_position_pct(${pyFloat(maxPos)})는 drift_band_pct(${pyFloat(drift)})보다 커야 합니다: " +
                    "상한이 드리프트 밴드 이하이면 목표비중이 밴드 안에 갇혀 매매가 발생하지 않습니다.",
            )
        }
        if (c["cadence"] == "weekly" && c["rebalance_weekday"] == null) c["rebalance_weekday"] = 0
        if ((c["cadence"] == "monthly" || c["cadence"] == "quarterly") && c["rebalance_dom"] == null) c["rebalance_dom"] = 1
    }

    private val REBALANCE = Model(
        "rebalance",
        listOf(
            F("type", Lit(listOf("rebalance")), "rebalance"),
            F("universe", ListT(StrT), emptyList<String>(), maxLen = 300, after = validateUniverse),
            F("selection", ModelT(SELECTION), DefaultModel),
            F("weighting", Lit(listOf("equal", "score", "inverse_vol")), "equal"),
            F("cadence", Lit(listOf("daily", "weekly", "monthly", "quarterly")), "monthly"),
            F("rebalance_weekday", IntT, null, nullable = true, ge = 0, le = 4),
            F("rebalance_dom", IntT, null, nullable = true, ge = 1, le = 28),
            F("rebalance_time", StrT, "14:30", after = validateTime),
            F("initial_fill_immediate", BoolT, false),
            F("drift_band_pct", FloatT, 0.05, ge = 0, le = 1),
            F("regime_filter", ModelT(REGIME_FILTER), null, nullable = true),
            F("risk_layer", ModelT(RISK_LAYER), null, nullable = true),
            F("panic_overlay", ModelT(PANIC_OVERLAY), null, nullable = true),
            F("capital", FloatT, 10_000_000, gt = 0),
            F("fees", FloatT, 0.00015, ge = 0, le = 0.01),
            F("tax", FloatT, 0.002, ge = 0, le = 0.01),
            F("fill_mode", Lit(listOf("next_close", "same_close")), "next_close"),
            F("slippage_bps", FloatT, 5.0, ge = 0, le = 200),
            F("slippage_vol_scale", FloatT, 0.0, ge = 0, le = 5),
            F("integer_shares", BoolT, true),
            F("adv_participation_cap", FloatT, null, nullable = true, ge = 0, le = 1),
            F("price_limit_model", BoolT, false),
            F("financial_period", Lit(listOf("annual", "ttm")), "annual"),
            F("benchmark_index", Lit(listOf("KOSPI200", "KOSPI", "KOSDAQ")), "KOSPI200"),
            F("risk_free_rate", FloatT, 0.0, ge = 0, le = 0.2),
        ),
        listOf(rebalanceConsistency),
    )

    /** 판별자(type) → 모델. 순서는 원본 Union 순서(오류 문구의 태그 나열 순서). */
    val BY_TYPE: Map<String, Model> = SINGLES + ("rebalance" to REBALANCE)
}

/** 전략 설정 검증 진입점 — 성공하면 pydantic `model_dump()` 와 같은 맵(저장할 값), 실패하면 [StrategyConfigException]. */
object StrategyConfigParser {
    fun parse(input: Any?): Map<String, Any?> {
        val errors = mutableListOf<ConfigError>()
        if (input !is Map<*, *> || !input.containsKey("type")) {
            throw StrategyConfigException(listOf(ConfigError(emptyList(), "Unable to extract tag using discriminator 'type'", "union_tag_not_found")))
        }
        val tag = input["type"]
        val model = StrategySchemas.BY_TYPE[tag]
            ?: throw StrategyConfigException(
                listOf(
                    ConfigError(
                        emptyList(),
                        "Input tag '$tag' found using 'type' does not match any of the expected tags: " +
                            StrategySchemas.BY_TYPE.keys.joinToString(", ") { "'$it'" },
                        "union_tag_invalid",
                    ),
                ),
            )
        val result = Validator.validateModel(model, input, listOf(tag as String), errors)
        if (result == null || errors.isNotEmpty()) throw StrategyConfigException(errors)
        return result
    }
}
