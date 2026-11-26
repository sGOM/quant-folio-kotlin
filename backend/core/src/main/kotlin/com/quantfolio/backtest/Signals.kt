package com.quantfolio.backtest

import com.quantfolio.quant.BoolSeries
import com.quantfolio.quant.Indicators.atr
import com.quantfolio.quant.Indicators.crossDown
import com.quantfolio.quant.Indicators.crossUp
import com.quantfolio.quant.Indicators.ema
import com.quantfolio.quant.Indicators.macd
import com.quantfolio.quant.Indicators.rsi
import com.quantfolio.quant.Indicators.sma
import com.quantfolio.quant.Series
import com.quantfolio.quant.constant
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyFloat

/**
 * 단일종목 매매 신호(원본 `backtest/signals.py`). 진입·청산은 **그날 종가로 확정된 사건**(교차·돌파)이다.
 * 설정은 저장된 config 맵을 원본처럼 `.get(키, 기본값)` 으로 읽는다(옛 행에 키가 없어도 동작).
 * 지표 정의는 pandas 와 같다(정답지 `signals.json`): SMA=rolling mean, EMA=ewm(span, adjust=False, min_periods=n),
 * RSI/ATR=Wilder(ewm alpha=1/n).
 */
object Signals {
    private val OHLC_TYPES = setOf("atr_trailing", "volatility_breakout", "keltner", "stochastic", "obv_trend")
    private val OHLC_SOURCES = setOf("open", "high", "low", "volume")

    // ── 설정 읽기(원본 int(cfg.get(k, d)) / float(...)) ──
    private fun Map<String, Any?>.i(key: String, default: Int): Int = (if (containsKey(key)) this[key] else default).let { v ->
        when (v) {
            is Number -> v.toInt()
            is String -> v.trim().toDouble().toInt()
            is Boolean -> if (v) 1 else 0
            else -> throw SignalConfigError("$key 값이 올바르지 않습니다: $v")
        }
    }

    private fun Map<String, Any?>.d(key: String, default: Double): Double = (if (containsKey(key)) this[key] else default).let { v ->
        when (v) {
            is Number -> v.toDouble()
            is String -> v.trim().toDouble()
            is Boolean -> if (v) 1.0 else 0.0
            else -> throw SignalConfigError("$key 값이 올바르지 않습니다: $v")
        }
    }

    private fun Map<String, Any?>.type(): String = (this["type"] ?: "sma_crossover").toString()

    /** 유형별 파라미터 기본값(원본 `cfg.get(k, d)` 의 d). 신호·설명 문구가 같은 값을 읽는다. 스키마 기본값과는 별개(strategy-config.md). */
    private val DEFAULTS: Map<String, Map<String, Number>> = mapOf(
        "sma_crossover" to mapOf("fast" to 5, "slow" to 20),
        "ema_crossover" to mapOf("fast" to 12, "slow" to 26),
        "rsi" to mapOf("period" to 14, "lower" to 30.0, "upper" to 70.0),
        "macd" to mapOf("fast" to 12, "slow" to 26, "signal" to 9),
        "bollinger" to mapOf("period" to 20, "num_std" to 2.0),
        "breakout" to mapOf("period" to 20),
        "momentum" to mapOf("lookback" to 120),
        "zscore" to mapOf("period" to 20, "entry" to 2.0),
        "disparity" to mapOf("period" to 20, "lower" to 95.0, "upper" to 105.0),
        "donchian_squeeze" to mapOf("period" to 20, "bb_mult" to 1.0, "kc_mult" to 1.3),
        "trix" to mapOf("period" to 15, "signal_period" to 9),
        "atr_trailing" to mapOf("period" to 22, "atr_period" to 22, "k" to 3.0),
        "volatility_breakout" to mapOf("k" to 0.5),
        "keltner" to mapOf("ema_period" to 20, "atr_period" to 10, "mult" to 2.0),
        "stochastic" to mapOf("k_period" to 14, "d_period" to 3, "lower" to 20.0, "upper" to 80.0),
        "obv_trend" to mapOf("period" to 20),
    )

    private fun Map<String, Any?>.default(key: String): Number =
        DEFAULTS[type()]?.get(key) ?: error("기본값 표에 없음: ${type()}.$key")

    private fun Map<String, Any?>.i(key: String): Int = i(key, default(key).toInt())

    private fun Map<String, Any?>.d(key: String): Double = d(key, default(key).toDouble())

    fun requiresOhlc(config: Map<String, Any?>): Boolean {
        val type = config.type()
        return if (type == "custom") customOperands(config).any { it["kind"] == "price" && it["source"] in OHLC_SOURCES } else type in OHLC_TYPES
    }

    fun generate(data: PriceData, config: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        val c = config
        val close = data.close
        return when (val type = config.type()) {
            "custom" -> customSignals(data, c)
            "sma_crossover" -> maCross(close, c.i("fast"), c.i("slow"), ::sma)
            "ema_crossover" -> maCross(close, c.i("fast"), c.i("slow"), ::ema)
            "rsi" -> {
                val lower = c.d("lower")
                val upper = c.d("upper")
                if (lower >= upper) throw SignalConfigError("RSI lower 는 upper 보다 작아야 합니다.")
                val r = rsi(close, c.i("period"))
                crossUp(r, constant(close.index, lower)) to crossDown(r, constant(close.index, upper))
            }
            "macd" -> {
                val fast = c.i("fast")
                val slow = c.i("slow")
                if (fast >= slow) throw SignalConfigError("MACD fast 는 slow 보다 작아야 합니다.")
                val (line, sig) = macd(close, fast, slow, c.i("signal"))
                crossUp(line, sig) to crossDown(line, sig)
            }
            "bollinger" -> {
                val period = c.i("period")
                val k = c.d("num_std")
                val mid = sma(close, period)
                val std = close.rolling(period, period).std(ddof = 0)
                crossUp(close, mid - std * k) to crossDown(close, mid + std * k)
            }
            "breakout" -> {
                val period = c.i("period")
                val upper = close.rolling(period, period).max().shift(1)
                val lower = close.rolling(period, period).min().shift(1)
                val prev = close.shift(1)
                ((close gt upper) and (prev le upper)) to ((close lt lower) and (prev ge lower))
            }
            "momentum" -> {
                val lookback = c.i("lookback")
                if (lookback < 1) throw SignalConfigError("모멘텀 lookback 은 1 이상이어야 합니다.")
                val mom = close / close.shift(lookback) - 1.0
                val zero = constant(close.index, 0.0)
                crossUp(mom, zero) to crossDown(mom, zero)
            }
            "zscore" -> {
                val period = c.i("period")
                val entry = c.d("entry")
                if (entry <= 0) throw SignalConfigError("z-score entry 임계는 0 보다 커야 합니다.")
                val z = (close - sma(close, period)) / close.rolling(period, period).std()
                crossUp(z, constant(close.index, -entry)) to crossUp(z, constant(close.index, 0.0))
            }
            "disparity" -> {
                val period = c.i("period")
                val lower = c.d("lower")
                val upper = c.d("upper")
                if (lower >= upper) throw SignalConfigError("이격도 lower 는 upper 보다 작아야 합니다.")
                val disp = close * 100.0 / sma(close, period)
                crossUp(disp, constant(close.index, lower)) to crossDown(disp, constant(close.index, upper))
            }
            "donchian_squeeze" -> donchianSqueeze(close, c)
            "trix" -> {
                val period = c.i("period")
                val sp = c.i("signal_period")
                if (period < 1 || sp < 1) throw SignalConfigError("TRIX period/signal_period 는 1 이상이어야 합니다.")
                val (trix, sig) = trix(close, period, sp)
                crossUp(trix, sig) to crossDown(trix, sig)
            }
            "atr_trailing" -> atrTrailing(data, c)
            "volatility_breakout" -> volatilityBreakout(data, c)
            "keltner" -> keltner(data, c)
            "stochastic" -> stochastic(data, c)
            "obv_trend" -> obvTrend(data, c)
            else -> throw SignalConfigError("알 수 없는 전략 유형: $type")
        }
    }

    private fun maCross(close: Series, fast: Int, slow: Int, ma: (Series, Int) -> Series): Pair<BoolSeries, BoolSeries> {
        if (fast >= slow) throw SignalConfigError("fast 기간은 slow 기간보다 작아야 합니다.")
        val f = ma(close, fast)
        val s = ma(close, slow)
        return crossUp(f, s) to crossDown(f, s)
    }

    private fun trix(close: Series, period: Int, signalPeriod: Int): Pair<Series, Series> {
        val e3 = ema(ema(ema(close, period), period), period)
        val prev = e3.shift(1)
        val trix = (e3 - prev) / prev * 100.0
        return trix to trix.ewm(span = signalPeriod.toDouble(), adjust = false, minPeriods = signalPeriod).mean()
    }

    /** 변동성 수축(볼린저 < 켈트너) 해제 + 종가가 중심선 위면 진입. 같은 날 진입이 있으면 청산은 지운다. */
    private fun donchianSqueeze(close: Series, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        val period = c.i("period")
        val bb = c.d("bb_mult")
        val kc = c.d("kc_mult")
        if (period < 3) throw SignalConfigError("Donchian 스퀴즈 period 는 3 이상이어야 합니다.")
        if (bb <= 0 || kc <= 0) throw SignalConfigError("Donchian 스퀴즈 bb_mult/kc_mult 는 0 보다 커야 합니다.")
        val r = close.diff()
        val squeezeOn = (r.rolling(period, period).std(ddof = 0) * bb) lt (r.abs().rolling(period, period).mean() * kc)
        val mid = sma(close, period)
        val released = squeezeOn.shift(1, fill = false) and squeezeOn.not()
        val entries = released and (close gt mid)
        val exits = crossDown(close, mid) and entries.not()
        return entries to exits
    }

    private fun atrTrailing(data: PriceData, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        data.require("high", "low", "close")
        val high = data.high!!
        val close = data.close
        val period = c.i("period")
        val atrPeriod = c.i("atr_period")
        val k = c.d("k")
        if (period < 1 || atrPeriod < 1) throw SignalConfigError("ATR 트레일링 period/atr_period 는 1 이상이어야 합니다.")
        if (k <= 0) throw SignalConfigError("ATR 트레일링 k 는 0 보다 커야 합니다.")
        val upper = high.rolling(period, period).max().shift(1)
        val entries = (close gt upper) and (close.shift(1) le upper)
        val chandelier = high.rolling(period, period).max() - atr(high, data.low!!, close, atrPeriod) * k
        return entries to crossDown(close, chandelier)
    }

    /** 당일 고가가 시가+k×전일변동폭을 넘으면 진입, 다음 봉에 청산(같은 날 재진입이면 청산 안 함). */
    private fun volatilityBreakout(data: PriceData, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        data.require("open", "high", "low", "close")
        val high = data.high!!
        val k = c.d("k")
        if (k <= 0) throw SignalConfigError("변동성 돌파 k 는 0 보다 커야 합니다.")
        val prevRange = high.shift(1) - data.low!!.shift(1)
        val entries = (high ge (data.open!! + prevRange * k)).where(prevRange.notNa(), false)
        val exits = entries.shift(1, fill = false) and entries.not()
        return entries to exits
    }

    private fun keltner(data: PriceData, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        data.require("high", "low", "close")
        val close = data.close
        val emaPeriod = c.i("ema_period")
        val atrPeriod = c.i("atr_period")
        val mult = c.d("mult")
        if (emaPeriod < 1 || atrPeriod < 1) throw SignalConfigError("켈트너 ema_period/atr_period 는 1 이상이어야 합니다.")
        if (mult <= 0) throw SignalConfigError("켈트너 mult 는 0 보다 커야 합니다.")
        val mid = ema(close, emaPeriod)
        val upper = mid + atr(data.high!!, data.low!!, close, atrPeriod) * mult
        return crossUp(close, upper) to crossDown(close, mid)
    }

    private fun stochasticKd(data: PriceData, kPeriod: Int, dPeriod: Int): Pair<Series, Series> {
        val lowest = data.low!!.rolling(kPeriod, kPeriod).min()
        val highest = data.high!!.rolling(kPeriod, kPeriod).max()
        val rng = highest - lowest
        val pctK = ((data.close - lowest) * 100.0 / rng).where(BoolSeries(rng.index, BooleanArray(rng.size) { rng[it] != 0.0 }), 50.0)
        return pctK to pctK.rolling(dPeriod, dPeriod).mean()
    }

    private fun stochastic(data: PriceData, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        data.require("high", "low", "close")
        val kPeriod = c.i("k_period")
        val dPeriod = c.i("d_period")
        val lower = c.d("lower")
        val upper = c.d("upper")
        if (kPeriod < 1 || dPeriod < 1) throw SignalConfigError("스토캐스틱 k_period/d_period 는 1 이상이어야 합니다.")
        if (lower >= upper) throw SignalConfigError("스토캐스틱 lower 는 upper 보다 작아야 합니다.")
        val (k, d) = stochasticKd(data, kPeriod, dPeriod)
        return (crossUp(k, d) and (k lt lower)) to (crossDown(k, d) and (k gt upper))
    }

    private fun obv(data: PriceData): Series = (data.close.diff().sign() * data.volume!!).fillNa(0.0).cumSum()

    private fun obvTrend(data: PriceData, c: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        data.require("close", "volume")
        val period = c.i("period")
        if (period < 2) throw SignalConfigError("OBV 추세 period 는 2 이상이어야 합니다.")
        val o = obv(data)
        val ma = sma(o, period)
        return crossUp(o, ma) to crossDown(o, ma)
    }

    // ── 사용자 정의 조건 ──
    @Suppress("UNCHECKED_CAST")
    private fun asMap(v: Any?): Map<String, Any?> = v as Map<String, Any?>

    private fun operandSeries(op: Map<String, Any?>, data: PriceData): Series {
        val close = data.close
        return when (val kind = op["kind"]) {
            "const" -> constant(close.index, (op["value"] as Number).toDouble())
            "price" -> {
                val source = (op["source"] ?: "close").toString()
                if (source == "close") close else { data.require(source); data.column(source)!! }
            }
            "sma" -> sma(close, (op["period"] as Number).toInt())
            "ema" -> ema(close, (op["period"] as Number).toInt())
            "rsi" -> rsi(close, (op["period"] as Number).toInt())
            "macd_line", "macd_signal" -> {
                val (line, sig) = macd(close, (op["fast"] as Number).toInt(), (op["slow"] as Number).toInt(), (op["signal"] as Number).toInt())
                if (kind == "macd_line") line else sig
            }
            else -> throw SignalConfigError("알 수 없는 피연산자 종류: $kind")
        }
    }

    /** (조건 상태, 두 피연산자 모두 유효). */
    private fun evalCondition(cond: Map<String, Any?>, data: PriceData): Pair<BoolSeries, BoolSeries> {
        val left = operandSeries(asMap(cond["left"]), data)
        val right = operandSeries(asMap(cond["right"]), data)
        val state = when (val op = cond["op"]) {
            ">" -> left gt right
            "<" -> left lt right
            ">=" -> left ge right
            "<=" -> left le right
            else -> throw SignalConfigError("알 수 없는 비교 연산자: $op")
        }
        return state to (left.notNa() and right.notNa())
    }

    private fun asGroup(node: Any?): Any? = if (node is List<*>) mapOf("combinator" to "and", "children" to node) else node

    private fun conditions(node: Any?): Sequence<Map<String, Any?>> = sequence {
        when {
            node == null || (node is Collection<*> && node.isEmpty()) || (node is Map<*, *> && node.isEmpty()) -> Unit
            node is List<*> -> node.forEach { yieldAll(conditions(it)) }
            node is Map<*, *> && node.containsKey("children") -> (node["children"] as List<*>).forEach { yieldAll(conditions(it)) }
            else -> yield(asMap(node))
        }
    }

    private fun evalNode(node: Any?, data: PriceData): Pair<BoolSeries, BoolSeries> {
        val map = asMap(node)
        if (!map.containsKey("children")) return evalCondition(map, data)
        val n = data.close.size
        val idx = data.close.index
        val isOr = (map["combinator"] ?: "and") == "or"
        var state = BoolSeries(idx, BooleanArray(n) { !isOr })
        var valid = BoolSeries(idx, BooleanArray(n) { true })
        for (child in map["children"] as List<*>) {
            val (s, v) = evalNode(child, data)
            state = if (isOr) state or s else state and s
            valid = valid and v
        }
        return state to valid
    }

    /** 상태가 거짓→참으로 바뀐 날(직전 날이 유효했을 때만). */
    private fun risingEdge(state: BoolSeries, valid: BoolSeries) = state and state.shift(1, fill = false).not() and valid.shift(1, fill = false)

    private fun customSignals(data: PriceData, cfg: Map<String, Any?>): Pair<BoolSeries, BoolSeries> {
        if (conditions(cfg["entry"]).none() || conditions(cfg["exit"]).none()) {
            throw SignalConfigError("사용자 정의 전략은 진입·청산 조건이 각각 1개 이상 필요합니다.")
        }
        val (es, ev) = evalNode(asGroup(cfg["entry"]), data)
        val (xs, xv) = evalNode(asGroup(cfg["exit"]), data)
        val entries = risingEdge(es, ev)
        return entries to (risingEdge(xs, xv) and entries.not())
    }

    /** 사용자 정의 전략의 보유 상태(1=보유, 0=미보유): 진입일 1, 청산일 0, 그 사이는 직전 상태 유지. */
    fun customPositionState(data: PriceData, cfg: Map<String, Any?>): Series {
        val (entries, exits) = customSignals(data, cfg)
        var state = 0.0
        return Series(entries.index, DoubleArray(entries.size) { i ->
            if (entries[i]) state = 1.0
            if (exits[i]) state = 0.0
            state
        })
    }

    private fun customOperands(cfg: Map<String, Any?>): Sequence<Map<String, Any?>> =
        (conditions(cfg["entry"]) + conditions(cfg["exit"])).flatMap { sequenceOf(asMap(it["left"]), asMap(it["right"])) }

    /** 지표가 유효해지기까지 필요한 최소 봉 수. */
    fun minPeriods(config: Map<String, Any?>): Int {
        val c = config
        return when (val type = c.type()) {
            "custom" -> {
                var longest = 1
                for (op in customOperands(c)) {
                    when (op["kind"]) {
                        "sma", "ema", "rsi" -> longest = maxOf(longest, ((op["period"] ?: 1) as Number).toInt())
                        "macd_line", "macd_signal" -> longest = maxOf(longest, ((op["slow"] ?: 1) as Number).toInt() + ((op["signal"] ?: 1) as Number).toInt())
                    }
                }
                maxOf(2, longest + 1)
            }
            "sma_crossover", "ema_crossover" -> c.i("slow") // 신호 계산과 같은 타입별 기본값(sma 20·ema 26). 원본은 둘 다 20(migration-notes #17)
            "rsi" -> c.i("period") + 1
            "macd" -> c.i("slow") + c.i("signal")
            "bollinger" -> c.i("period")
            "breakout" -> c.i("period") + 1
            "momentum" -> c.i("lookback") + 1
            "zscore" -> c.i("period")
            "disparity" -> c.i("period")
            "donchian_squeeze" -> c.i("period") + 2
            "trix" -> 3 * c.i("period") + c.i("signal_period")
            "atr_trailing" -> maxOf(c.i("period"), c.i("atr_period")) + 1
            "volatility_breakout" -> 2
            "keltner" -> maxOf(c.i("ema_period"), c.i("atr_period"))
            "stochastic" -> c.i("k_period") + c.i("d_period")
            "obv_trend" -> c.i("period") + 1
            else -> throw SignalConfigError("알 수 없는 전략 유형: $type")
        }
    }

    /** 마지막 봉의 신호: "buy" | "sell" | null. */
    fun latestSignal(data: PriceData, config: Map<String, Any?>): String? {
        val (entries, exits) = generate(data, config)
        if (entries.size == 0) return null
        if (entries[entries.size - 1]) return "buy"
        if (exits[exits.size - 1]) return "sell"
        return null
    }

    // ── 설명 문구(주문 사유) ──
    /** 원본 `_fmt`: 절댓값 1000 이상은 천 단위 구분·정수, 아니면 소수 digits 자리. */
    fun fmt(v: Double, digits: Int = 2): String = if (v.isNaN()) "nan" else if (kotlin.math.abs(v) >= 1000) pyFixed(v, 0, grouping = true) else pyFixed(v, digits)

    /** 주문 사유로 남길 한국어 설명. 지표값 산출이 실패해도 문구는 만든다(원본 동일). */
    fun explain(data: PriceData, config: Map<String, Any?>, side: String): String {
        val type = config.type()
        return try {
            explainOrThrow(data, config, type, side)
        } catch (e: Exception) {
            "$type 전략 ${if (side == "buy") "진입(매수)" else "청산(매도)"} 신호 발생 (자세한 지표값 산출 실패 — 규칙 충족)"
        }
    }

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    private fun explainOrThrow(data: PriceData, c: Map<String, Any?>, type: String, side: String): String {
        val close = data.close
        val px = close.last()
        val buy = side == "buy"
        fun dir() = if (buy) "상향 돌파" else "하향 이탈"
        fun bs() = if (buy) "매수" else "매도"
        return when (type) {
            "sma_crossover", "ema_crossover" -> {
                val isSma = type == "sma_crossover"
                val label = if (isSma) "SMA" else "EMA"
                val fast = c.i("fast")
                val slow = c.i("slow")
                val ma: (Series, Int) -> Series = if (isSma) ::sma else ::ema
                val f = ma(close, fast).last()
                val s = ma(close, slow).last()
                if (buy) "골든크로스: 단기 $label$fast(${fmt(f)})가 장기 $label$slow(${fmt(s)})를 상향 돌파"
                else "데드크로스: 단기 $label$fast(${fmt(f)})가 장기 $label$slow(${fmt(s)})를 하향 이탈"
            }
            "rsi" -> {
                val period = c.i("period")
                val r = rsi(close, period).last()
                if (buy) "RSI($period) ${fmt(r)}가 과매도선 ${fmt(c.d("lower"))}를 상향 복귀(매수)"
                else "RSI($period) ${fmt(r)}가 과매수선 ${fmt(c.d("upper"))}를 하향 이탈(매도)"
            }
            "macd" -> {
                val fast = c.i("fast")
                val slow = c.i("slow")
                val signal = c.i("signal")
                val (ml, sl) = macd(close, fast, slow, signal)
                "MACD($fast,$slow,$signal) 선 ${fmt(ml.last())}가 시그널선 ${fmt(sl.last())}를 ${dir()}(${bs()})"
            }
            "bollinger" -> {
                val period = c.i("period")
                val k = c.d("num_std")
                val band = sma(close, period).last() + (if (!buy) k else -k) * close.rolling(period, period).std(ddof = 0).last()
                if (buy) "종가 ${fmt(px)}가 볼린저 하단밴드 ${fmt(band)}(−${pyFloat(k)}σ)를 상향 복귀(매수)"
                else "종가 ${fmt(px)}가 볼린저 상단밴드 ${fmt(band)}(+${pyFloat(k)}σ)를 하향 이탈(매도)"
            }
            "breakout" -> {
                val period = c.i("period")
                if (buy) "종가 ${fmt(px)}가 직전 ${period}봉 최고가 ${fmt(close.rolling(period, period).max().shift(1).last())}를 상향 돌파(매수)"
                else "종가 ${fmt(px)}가 직전 ${period}봉 최저가 ${fmt(close.rolling(period, period).min().shift(1).last())}를 하향 돌파(매도)"
            }
            "momentum" -> {
                val lookback = c.i("lookback")
                val mom = (close / close.shift(lookback) - 1.0).last() * 100
                if (buy) "${lookback}봉 모멘텀 ${fmt(mom)}%가 0%를 상향 돌파(추세 양전, 매수)"
                else "${lookback}봉 모멘텀 ${fmt(mom)}%가 0%를 하향 돌파(추세 음전, 매도)"
            }
            "zscore" -> {
                val period = c.i("period")
                val z = ((close - sma(close, period)) / close.rolling(period, period).std()).last()
                if (buy) "z-score ${fmt(z)}가 저평가 임계 −${fmt(c.d("entry"))}를 상향 복귀(평균회귀 매수)"
                else "z-score ${fmt(z)}가 평균선 0을 상향 돌파(회귀 완료, 매도)"
            }
            "disparity" -> {
                val period = c.i("period")
                val disp = (close * 100.0 / sma(close, period)).last()
                if (buy) "이격도(종가/SMA$period) ${fmt(disp)}%가 하한 ${fmt(c.d("lower"))}%를 상향 복귀(매수)"
                else "이격도(종가/SMA$period) ${fmt(disp)}%가 상한 ${fmt(c.d("upper"))}%를 하향 이탈(매도)"
            }
            "donchian_squeeze" -> {
                val period = c.i("period")
                val mid = sma(close, period).last()
                if (buy) "변동성 스퀴즈 해제 & 종가 ${fmt(px)}가 중심선 SMA$period ${fmt(mid)} 위(추세 진입 매수)"
                else "종가 ${fmt(px)}가 중심선 SMA$period ${fmt(mid)}를 하향 돌파(매도)"
            }
            "trix" -> {
                val (t, s) = trix(close, c.i("period"), c.i("signal_period"))
                "TRIX ${fmt(t.last(), 4)}가 시그널선 ${fmt(s.last(), 4)}를 ${dir()}(${bs()})"
            }
            "atr_trailing" -> {
                val high = data.high!!
                val period = c.i("period")
                val k = c.d("k")
                if (buy) {
                    "종가 ${fmt(px)}가 ${period}봉 채널 상단 ${fmt(high.rolling(period, period).max().shift(1).last())}를 상향 돌파(추세 매수)"
                } else {
                    val chand = high.rolling(period, period).max().last() - k * atr(high, data.low!!, close, c.i("atr_period")).last()
                    "종가 ${fmt(px)}가 샹들리에 청산선 ${fmt(chand)}(고점−${pyFloat(k)}×ATR)을 하향 돌파(매도)"
                }
            }
            "volatility_breakout" -> {
                val k = c.d("k")
                val prevRange = (data.high!!.shift(1) - data.low!!.shift(1)).last()
                val target = data.open!!.last() + k * prevRange
                if (buy) "당일 고가가 변동성 돌파 목표가 ${fmt(target)}(시가+${pyFloat(k)}×전일변동폭 ${fmt(prevRange)})를 상향 돌파(매수)"
                else "변동성 돌파 진입 다음 봉 — 당일 종가 청산 규약(매도)"
            }
            "keltner" -> {
                val emaPeriod = c.i("ema_period")
                val mult = c.d("mult")
                val mid = ema(close, emaPeriod)
                if (buy) {
                    val upper = mid.last() + mult * atr(data.high!!, data.low!!, close, c.i("atr_period")).last()
                    "종가 ${fmt(px)}가 켈트너 채널 상단 ${fmt(upper)}(EMA+${pyFloat(mult)}×ATR)를 상향 돌파(매수)"
                } else {
                    "종가 ${fmt(px)}가 켈트너 중심선 EMA$emaPeriod ${fmt(mid.last())}를 하향 돌파(매도)"
                }
            }
            "stochastic" -> {
                val (k, d) = stochasticKd(data, c.i("k_period"), c.i("d_period"))
                if (buy) "스토캐스틱 과매도(%K ${fmt(k.last())} < ${fmt(c.d("lower"))})에서 %K가 %D ${fmt(d.last())}를 상향 교차(매수)"
                else "스토캐스틱 과매수(%K ${fmt(k.last())} > ${fmt(c.d("upper"))})에서 %K가 %D ${fmt(d.last())}를 하향 교차(매도)"
            }
            "obv_trend" -> "OBV(거래량 누적)가 ${c.i("period")}봉 이동평균을 ${dir()}(${bs()})"
            "custom" -> {
                val conds = conditions(if (buy) c["entry"] else c["exit"]).map { "${operandDesc(asMap(it["left"]))} ${it["op"]} ${operandDesc(asMap(it["right"]))}" }.toList()
                val joined = conds.take(4).joinToString(", ") + if (conds.size > 4) "…" else ""
                val kind = if (buy) "진입(매수)" else "청산(매도)"
                if (joined.isNotEmpty()) "사용자 정의 $kind 조건 충족: $joined" else "사용자 정의 $kind 신호"
            }
            else -> "$type 전략 ${bs()} 신호 발생(규칙 충족)"
        }
    }

    private fun operandDesc(op: Map<String, Any?>): String = when (val kind = op["kind"]) {
        "const" -> fmt(((op["value"] ?: 0) as Number).toDouble())
        "price" -> {
            val source = (op["source"] ?: "close").toString()
            mapOf("close" to "종가", "open" to "시가", "high" to "고가", "low" to "저가", "volume" to "거래량")[source] ?: source
        }
        "sma", "ema", "rsi" -> "${kind.toString().uppercase()}${op["period"] ?: ""}"
        "macd_line" -> "MACD선"
        "macd_signal" -> "MACD시그널"
        else -> kind.toString()
    }
}
