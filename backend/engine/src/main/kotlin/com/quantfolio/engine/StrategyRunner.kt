package com.quantfolio.engine

import com.quantfolio.backtest.PriceData
import com.quantfolio.backtest.Signals
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.market.store.Bar
import com.quantfolio.quant.Series
import com.quantfolio.quant.pyFixed
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import java.math.BigDecimal
import java.math.MathContext
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 단일종목 live 전략 실행기(원본 `engine/runner.py`): 과거 일봉 시드 → 30초마다 현재가로 오늘 봉 갱신 → 신호 평가 → 주문.
 * 우선순위: 리스크 손절(RiskLimit) → config 익절/손절/트레일링 → 신호. 신호는 백테스트와 같은 [Signals].
 * 현재가는 실시간 캐시 우선, 없으면 REST. 0 이하·결측은 "유효 시세 없음"으로 그 틱을 건너뛴다(0 으로 흘리면 −100% 폭락으로 오독).
 */
class StrategyRunner(strategyId: Int, ctx: RunnerContext) : BaseRunner(strategyId, ctx) {
    override val log = LoggerFactory.getLogger("engine.runner")!!
    override val label = "전략"
    override val tickWord = "tick"
    override val pollIntervalSec = 30L
    override val tickTimeoutSec = 120L

    private var bars: List<Bar> = emptyList()
    private var ohlc = false
    private lateinit var symbol: String

    override fun onLoad() {
        symbol = cfg["symbol"].toString()
        ohlc = Signals.requiresOhlc(cfg)
        seed()
    }

    override fun logStart() = log.info("전략 {} 실행 시작 ({})", strategyId, symbol)

    /** 지표용 과거 일봉 시드 — price_ticks 가 모자라면 외부에서 적재. */
    private fun seed() {
        val needBars = Signals.minPeriods(cfg)
        val end = ctx.clock.instant()
        val start = end.minus(Duration.ofDays(needBars * 4L * 2))
        bars = ctx.ticks.bars(symbol, start, end)
        if (bars.size < needBars + 1) {
            try {
                ctx.ticks.upsert(symbol, ctx.loader.load(symbol, start.atZone(ZoneOffset.UTC).toLocalDate(), end.atZone(ZoneOffset.UTC).toLocalDate()))
                bars = ctx.ticks.bars(symbol, start, end)
            } catch (e: Exception) {
                log.warn("{} 시드 적재 실패: {}", symbol, e.message)
            }
        }
    }

    private fun currentPrice(): BigDecimal? {
        ctx.redis.opsForValue().get(RedisKeys.price(symbol))?.let { cached ->
            val px = runCatching { BigDecimal(cached) }.getOrNull()
            if (px != null) {
                if (px.signum() > 0) return px
                log.warn("{} 가격 캐시 값이 비정상({}) — REST 폴백", symbol, px)
            }
        }
        val q = try {
            broker.getQuote(symbol)
        } catch (e: Exception) {
            log.warn("{} 현재가 조회 실패: {}", symbol, e.message)
            return null
        }
        if (q.price.signum() <= 0) {
            log.warn("{} 현재가가 0 이하({}) — 유효 시세 없음으로 보고 이번 틱 건너뜀", symbol, q.price)
            return null
        }
        return q.price
    }

    /** 시드 + 오늘 봉(현재가 반영). 오늘은 원본 `date.today()` — 컨테이너 UTC 날짜(보존). */
    private fun dataWithToday(px: Double): PriceData {
        val today = LocalDate.ofInstant(ctx.clock.instant(), ZoneOffset.UTC)
        val rows = bars.associateBy { it.time.atZone(ZoneOffset.UTC).toLocalDate() }.toSortedMap()
        val prev = rows[today]
        rows[today] = if (ohlc && prev != null) {
            Bar(prev.time, prev.open, maxOf(prev.high, px), minOf(prev.low, px), px, prev.volume)
        } else {
            Bar(today.atStartOfDay().toInstant(ZoneOffset.UTC), px, px, px, px, 0)
        }
        val idx = rows.keys.toList()
        fun col(f: (Bar) -> Double) = Series(idx, DoubleArray(idx.size) { f(rows.getValue(idx[it])) })
        return if (ohlc) PriceData.ohlcv(col { it.open }, col { it.high }, col { it.low }, col { it.close }, col { it.volume.toDouble() }) else PriceData.close(col { it.close })
    }

    override fun tickOnce() {
        if (!ctx.calendar.isMarketOpen(MarketClock.nowKst(ctx.clock))) return
        val price = currentPrice() ?: return
        val data = dataWithToday(price.toDouble())
        val sig = Signals.latestSignal(data, cfg)
        val barTs = LocalDate.ofInstant(ctx.clock.instant(), ZoneOffset.UTC).toString()
        val (acquired, _) = withPositionLock(symbol) { decide(price, data, sig, barTs) }
        if (!acquired) log.info("포지션 락 경합 — 이번 tick 건너뜀 ({})", symbol)
    }

    private fun decide(price: BigDecimal, data: PriceData, sig: String?, barTs: String) {
        val held = holdingQty(symbol)
        if (held.signum() > 0 && ctx.risk.checkStopLoss(userId!!, strategyId, symbol, price)) {
            clearTrail()
            doSell(price, "$barTs:stop", stopLossReason(price))
            return
        }
        if (held.signum() > 0) {
            configExit(price)?.let { (kind, reason) ->
                clearTrail()
                doSell(price, "$barTs:$kind", reason)
                return
            }
        } else {
            clearTrail()
        }
        if (sig == "buy" && held.signum() <= 0) {
            val daily = ctx.risk.checkDailyLoss(userId!!, strategyId, mapOf(symbol to price))
            if (!daily.approved) {
                log.info("매수 차단: {}", daily.reason)
                return
            }
            val decision = ctx.risk.evaluateBuy(userId!!, strategyId, symbol, price, BigDecimal(cfg["cash"]?.toString() ?: "10000000"))
            if (decision.approved) {
                val reason = signalReason(data, "buy") +
                    " · 진입 ${grouped(decision.qty)}주 @ ${won(price.toDouble())}(약 ${won(decision.qty * price.toDouble())}원)"
                place(symbol, "buy", decision.qty, price, barTs, reason)
            } else {
                log.info("매수 보류: {}", decision.reason)
            }
        } else if (sig == "sell" && held.signum() > 0) {
            clearTrail()
            doSell(price, barTs, signalReason(data, "sell"))
        }
    }

    private fun clearTrail() {
        ctx.redis.delete(RedisKeys.trail(strategyId, symbol))
    }

    private fun avgPrice(): BigDecimal? = ctx.jdbc.query(
        "select qty, avg_price from positions where user_id = :u and strategy_id = :s and symbol = :sym",
        MapSqlParameterSource("u", userId).addValue("s", strategyId).addValue("sym", symbol),
    ) { rs, _ -> rs.getBigDecimal(1) to rs.getBigDecimal(2) }.firstOrNull()?.takeIf { it.first.signum() > 0 && it.second.signum() > 0 }?.second

    private fun pct(v: Any?) = v?.let { BigDecimal(it.toString()) }

    /** config 의 익절%/손절%/트레일링 — 평단 대비, 트레일링은 보유 중 고점(Redis) 대비. (종류, 감사 사유) 또는 null. */
    private fun configExit(price: BigDecimal): Pair<String, String>? {
        val avg = avgPrice() ?: return null
        val sl = pct(cfg["stop_loss_pct"])
        val tp = pct(cfg["take_profit_pct"])
        val trail = pct(cfg["trailing_stop_pct"])
        val mc = MathContext.DECIMAL128
        if (tp != null && (price - avg).divide(avg, mc) >= tp) {
            val gain = (price - avg).divide(avg, mc).toDouble()
            log.info("익절 도달: {} +{}", symbol, pyFixed(gain, 4))
            return "tp" to "익절: 현재가 ${won(price.toDouble())}가 평균단가 ${won(avg.toDouble())} 대비 +${pyFixed(gain * 100, 2)}%로 익절 기준 +${pyFixed(tp.toDouble() * 100, 1)}% 도달"
        }
        if (sl != null && (avg - price).divide(avg, mc) >= sl) {
            val drop = (avg - price).divide(avg, mc).toDouble()
            log.info("손절 도달: {} -{}", symbol, pyFixed(drop, 4))
            return "sl" to "손절: 현재가 ${won(price.toDouble())}가 평균단가 ${won(avg.toDouble())} 대비 −${pyFixed(drop * 100, 2)}%로 손절 기준 −${pyFixed(sl.toDouble() * 100, 1)}% 이하로 하락"
        }
        if (trail != null) {
            val key = RedisKeys.trail(strategyId, symbol)
            var peak = ctx.redis.opsForValue().get(key)?.let { BigDecimal(it) } ?: avg
            if (price > peak) peak = price
            ctx.redis.opsForValue().set(key, peak.toPlainString(), Duration.ofDays(7))
            if (peak.signum() > 0 && (peak - price).divide(peak, mc) >= trail) {
                val fall = (peak - price).divide(peak, mc).toDouble()
                log.info("트레일링 스탑 도달: {} 고점 {} 대비 -{}", symbol, peak, pyFixed(fall, 4))
                return "trail" to "트레일링 스탑: 현재가 ${won(price.toDouble())}가 보유 중 고점 ${won(peak.toDouble())} 대비 −${pyFixed(fall * 100, 2)}%로 기준 −${pyFixed(trail.toDouble() * 100, 1)}% 이하로 하락"
            }
        }
        return null
    }

    private fun stopLossReason(price: BigDecimal): String {
        val avg = avgPrice() ?: return "리스크 손절 한도 도달(RiskLimit stop_loss_pct 이하로 하락)"
        val drop = (avg - price).divide(avg, MathContext.DECIMAL128).toDouble()
        return "리스크 손절 한도 도달: 현재가 ${won(price.toDouble())}가 평균단가 ${won(avg.toDouble())} 대비 −${pyFixed(drop * 100, 2)}% 하락(RiskLimit 손절선 이하)"
    }

    /** 신호 설명 문장. 실패해도 매매를 막지 않는다. */
    private fun signalReason(data: PriceData, side: String): String = try {
        Signals.explain(data, cfg, side)
    } catch (e: Exception) {
        "${cfg["type"] ?: "전략"} ${if (side == "buy") "매수" else "매도"} 신호"
    }

    private fun doSell(price: BigDecimal, barTs: String, reason: String?) {
        val d = ctx.risk.evaluateSell(userId!!, symbol, strategyId)
        if (!d.approved) return
        place(symbol, "sell", d.qty, price, barTs, reason)
    }
}
