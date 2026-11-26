package com.quantfolio.backtest

import com.quantfolio.backtest.PortfolioBacktest.roundToTick
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Num
import java.time.LocalDate
import kotlin.math.floor

// KRX 가격제한폭 ±30%(전일 종가 대비)
private const val LIMIT_DOWN = 0.7
private const val LIMIT_UP = 1.3

internal fun iso(d: LocalDate) = "${d}T00:00:00"

/** 체결 1회의 설정(시뮬레이션 내내 불변). */
internal class Exec(
    val capital: Double, val driftBand: Double, val fees: Double, val tax: Double, val slipBase: Double,
    val integerShares: Boolean, val advCapFrac: Double, val priceLimitModel: Boolean,
) {
    companion object {
        fun from(config: Map<String, Any?>) = Exec(
            capital = Cfg.num(Cfg.get(config, "capital", 10_000_000)),
            driftBand = Cfg.num(Cfg.get(config, "drift_band_pct", 0.05)),
            fees = Cfg.num(Cfg.get(config, "fees", 0.00015)),
            tax = Cfg.num(Cfg.get(config, "tax", 0.0020)),
            slipBase = maxOf(0.0, Cfg.num(Cfg.get(config, "slippage_bps", 5.0).takeIf { Cfg.truthy(it) } ?: 0.0) / 10_000.0),
            integerShares = Cfg.truthy(Cfg.get(config, "integer_shares", true)),
            advCapFrac = Cfg.num(Cfg.or(config, "adv_participation_cap", 0.0)),
            priceLimitModel = Cfg.truthy(Cfg.get(config, "price_limit_model", false)),
        )
    }
}

internal class Book(var cash: Double) {
    val value = LinkedHashMap<String, Double>()
    val cost = LinkedHashMap<String, Double>()
    val trades = mutableListOf<Map<String, Any?>>()
    fun equity() = cash + value.values.sum()
}

internal fun tradeRec(d: LocalDate, sym: String, side: String, amt: Double, fill: Map<String, Double>?, capital: Double, portReturn: Double, posReturn: Double?, reason: String) =
    linkedMapOf<String, Any?>(
        "t" to iso(d), "symbol" to sym, "side" to side, "amount" to Math.rint(amt * capital).toLong(),
        "price" to fill?.get(sym)?.let { Num.safe(it) }, "port_return" to Num.safe(portReturn),
        "position_return" to posReturn?.let { Num.safe(it) }, "reason" to reason,
    )

internal fun floorToShares(amt: Double, sym: String, fill: Map<String, Double>?, capital: Double): Double {
    val price = fill?.get(sym)
    if (price == null || price.isNaN() || price <= 0 || capital <= 0) return amt
    val qty = floor(amt * capital / price)
    return if (qty <= 0) 0.0 else qty * price / capital
}

/**
 * 목표비중으로 리밸런싱(매도 먼저 → 매수). book 을 제자리 수정하고 (turnover, 실체결 거래대금)을 돌려준다.
 * liquidate 면 드리프트 밴드 무시 전량 청산이며 ADV 캡이 면제된다(라이브 러너가 캡 없이 즉시 전량 매도하므로).
 */
internal fun applyRebalance(
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
        val lower = roundToTick(prev * LIMIT_DOWN).takeIf { it != 0.0 && !it.isNaN() } ?: 0.0
        val upper = roundToTick(prev * LIMIT_UP).takeIf { it != 0.0 && !it.isNaN() } ?: 0.0
        return if (side == "buy") cur >= upper - 1e-6 else cur <= lower + 1e-6
    }
    var executed = 0.0
    val portReturn = equity - 1.0
    var sells = mutableListOf<Pair<String, Double>>()
    var buys = mutableListOf<Pair<String, Double>>()
    var turnover = 0.0
    if (liquidate) {
        for ((sym, v) in book.value) if (v > EPS) {
            sells += sym to v
            turnover += v / equity
        }
    } else {
        for (sym in (targets.keys + book.value.keys).sorted()) {
            val curVal = book.value[sym] ?: 0.0
            val tw = targets[sym] ?: 0.0
            val dev = tw - curVal / equity
            val isNew = curVal <= EPS && tw > 0.0
            val isExit = tw <= 0.0 && curVal > EPS
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
        if (amt <= EPS) continue
        cash += amt * (1.0 - ex.fees - ex.tax - slip(sym))
        val frac = if (curVal > 0) amt / curVal else 0.0
        val costPortion = (book.cost[sym] ?: 0.0) * frac
        val posRet = if (costPortion > EPS) amt / costPortion - 1.0 else null
        book.value[sym] = curVal - amt
        book.cost[sym] = (book.cost[sym] ?: 0.0) - costPortion
        if (book.value.getValue(sym) <= EPS) {
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
        if (amt <= EPS) continue
        cash -= amt * (1.0 + ex.fees + slip(sym))
        book.value[sym] = (book.value[sym] ?: 0.0) + amt
        book.cost[sym] = (book.cost[sym] ?: 0.0) + amt
        book.trades += tradeRec(d, sym, "buy", amt, fill, ex.capital, portReturn, null, reason)
        executed += amt
    }
    book.cash = cash
    return turnover to executed
}

/**
 * 전일 대비 가격 변화로 보유 평가액 갱신. 오늘 시세가 없으면(ffill 한도 delisting_gap_days 를 넘긴 결측) 상장폐지로 보고
 * 회수율만 현금화한다 — 결측이 나중에 재개될지는 d 에 알 수 없으므로 묻지 않는다. 원본은 패널 전체의 마지막 유효일로
 * 재개(interior gap → 동결)와 종료(terminal gap → 현금화)를 갈라 미래를 봤다(migration-notes #10).
 */
internal fun markToMarket(
    book: Book, prevPrices: Map<String, Double>, prices: Map<String, Double>, d: LocalDate,
    delistRecovery: Double, journal: Journal,
) {
    for (sym in book.value.keys.toList()) {
        val p0 = prevPrices[sym]
        val p1 = prices[sym]
        if (p1 != null && !p1.isNaN() && p0 != null && !p0.isNaN() && p0 > 0) {
            book.value[sym] = book.value.getValue(sym) * (p1 / p0)
        } else if (p1 == null || p1.isNaN()) {
            book.cash += book.value.remove(sym)!! * delistRecovery
            book.cost.remove(sym)
            journal.marker(d, "delist", "symbol" to sym)
        }
    }
}

internal enum class Action { REBALANCE, LIQUIDATE }

/** 하루치 매매 결정. 청산은 목표비중 없이 보유 전량 매도. */
internal data class Decision(val action: Action, val reason: String, val targets: Map<String, Double> = emptyMap()) {
    companion object {
        fun rebalance(reason: String, targets: Map<String, Double>) = Decision(Action.REBALANCE, reason, targets)
        fun liquidate(reason: String) = Decision(Action.LIQUIDATE, reason)
    }
}

/** 일별 루프가 쌓는 기록 — 성과지표의 입력이자 결과 그대로의 일부. */
internal class Journal {
    val equities = mutableListOf<Double>()
    val equityCurve = mutableListOf<Map<String, Any?>>()
    val markers = mutableListOf<Map<String, Any?>>()
    val turnovers = mutableListOf<Double>()
    val turnoversActual = mutableListOf<Double>()

    fun marker(d: LocalDate, type: String, vararg extra: Pair<String, Any?>) {
        markers += linkedMapOf<String, Any?>("t" to iso(d), "type" to type).apply { putAll(extra) }
    }
}

/**
 * 배당락일 [d] 의 현금배당 적립. 대상은 **전일까지 들고 있던** 보유(마크투마켓 직후·당일 체결 전 — 배당락일에 사서는 못 받고
 * 배당락일에 팔아도 받는다). 수량 = 평가액 / 당일 종가이므로 받는 금액 = 평가액 × dps / 종가 × (1 - 배당세). 현금으로 쌓이고 다음 리밸런싱에서 투자될 수 있다.
 * [events] 의 dps 는 백테스트 가격(수정주가) 단위여야 한다. 적립한 세후 금액(자본 대비 비율)을 돌려준다. 종가가 없어 못 받은 건은 [skipped] 에 사유를 쌓는다.
 */
internal fun accrueDividends(
    book: Book, events: List<Pair<String, Double>>, prices: Map<String, Double>, taxRate: Double, d: LocalDate, skipped: MutableList<String>,
): Double {
    var income = 0.0
    for ((sym, dps) in events) {
        val held = book.value[sym] ?: continue
        val price = prices[sym]
        if (!dps.isFinite() || dps <= 0) continue
        if (price == null || price.isNaN() || price <= 0) {
            skipped += "배당 $sym 배당락일 $d: 종가가 없어(거래정지·결측) 보유분 배당을 건너뜀"
            continue
        }
        val net = held * dps / price * (1.0 - taxRate)
        book.cash += net
        income += net
    }
    return income
}
