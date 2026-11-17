package com.quantfolio.web.analysis

import com.quantfolio.backtest.FillQuality
import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.backtest.SlippageCalibration
import com.quantfolio.backtest.Tracking
import com.quantfolio.common.MarketClock
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.pyFloat
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMax
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.slf4j.LoggerFactory
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import kotlin.math.abs

private const val NO_FILLS = "해당 기간 실측 체결이 없습니다(모의투자/실거래 체결 후 이용 가능)."

/**
 * 실측(모의투자) NAV ↔ 백테스트 기대곡선 괴리(원본 `routes/tracking.py`). 재생은 항상 첫 체결부터, date_from 은 표시만 자른다.
 * 원본은 tz 있는 가격 인덱스와 tz 없는 체결일을 섞어 비교해 시세가 있으면 TypeError(500)였다 — 의도대로 날짜끼리 비교한다(migration-notes).
 */
@RestController
class TrackingController(
    private val strategies: StrategyRepository,
    private val backtests: BacktestRepository,
    private val reports: FillQualityReport,
    private val ticks: PriceTickStore,
) {
    @GetMapping("/api/strategies/{id}/tracking")
    fun tracking(
        @CurrentUser user: User,
        @PathVariable id: Int,
        @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate?,
        @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate?,
        @RequestParam(name = "backtest_id", required = false) backtestId: Int?,
        @RequestParam(name = "initial_capital", required = false) @DecimalMin(value = "0", inclusive = false) initialCapital: Double?,
    ): Map<String, Any?> {
        val strategy = strategies.owned(user, id)
        val dTo = dateTo ?: MarketClock.todayKst()
        val dFrom = dateFrom ?: LocalDate.of(1970, 1, 1)
        if (dateFrom != null && dateFrom.isAfter(dTo)) throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "시작일이 종료일보다 늦습니다.")

        val all = reports.filledOrders(user.id!!, id).flatMap { o ->
            o.fills.mapNotNull { f ->
                if (f.qty <= 0 || f.price <= 0) return@mapNotNull null
                val d = MarketClock.kstDate(f.executedAt)
                if (d.isAfter(dTo)) null else Tracking.Execution(d, o.symbol, o.side, f.qty, f.price, f.fee)
            }
        }
        if (all.isEmpty()) throw ApiException(HttpStatus.NOT_FOUND, NO_FILLS)
        val window = all.filter { !it.date.isBefore(dFrom) }
        if (window.isEmpty()) throw ApiException(HttpStatus.NOT_FOUND, NO_FILLS)
        val effFrom = window.minOf { it.date }
        val effTo = maxOf(window.maxOf { it.date }, dTo)
        val replayFrom = all.minOf { it.date }

        val panel = FillQualityReport.dailyClosePanel(
            all.map { it.symbol }.toSortedSet().associateWith { ticks.bars(it, MarketClock.kstStart(replayFrom), MarketClock.kstEnd(effTo)) },
        )
        val capital = initialCapital ?: Cfg.num(Cfg.or(strategy.config, "cash", 10_000_000.0))
        val (realized, notes0) = Tracking.reconstructRealizedCurve(all, panel, capital, dFrom)
        val notes = notes0.toMutableList()
        if (replayFrom.isBefore(effFrom)) {
            val prior = all.count { it.date.isBefore(effFrom) }
            notes += "조회 구간 이전 체결 ${prior}건을 반영해 표시 구간 시작 시점의 초기 보유·현금을 산출했습니다(표시 구간 자체는 그대로 유지)."
        }

        val bt = if (backtestId != null) {
            backtests.findById(backtestId).filter { it.strategyId == id }.orElseThrow { ApiException(HttpStatus.NOT_FOUND, "백테스트를 찾을 수 없습니다.") }
        } else {
            backtests.findByStrategyIdOrderByIdDesc(id).firstOrNull()
        }
        val btCurve = backtestCurve(bt?.result)
        val cmp = Tracking.compare(realized, btCurve)
        return linkedMapOf(
            "strategy_id" to id,
            "backtest_id" to bt?.id,
            "window" to linkedMapOf("date_from" to effFrom.toString(), "date_to" to effTo.toString()),
            "initial_capital" to capital,
            "realized_curve" to realized,
            "backtest_curve" to btCurve,
            "realized_index" to cmp["realized_index"],
            "backtest_index" to cmp["backtest_index"],
            "metrics" to cmp["metrics"],
            "sample" to linkedMapOf(
                "n_executions" to window.size, "n_symbols" to window.map { it.symbol }.toSet().size,
                "n_realized_days" to realized.size, "n_backtest_days" to btCurve.size,
            ),
            "warning" to if (btCurve.isEmpty()) "비교할 백테스트 곡선이 없습니다 — 실측 곡선만 표시합니다." else null,
            "notes" to notes,
        )
    }

    /** 저장된 백테스트 결과의 equity_curve → [{t, v}]. 형식이 맞지 않는 점은 건너뛴다. */
    @Suppress("UNCHECKED_CAST")
    private fun backtestCurve(result: Map<String, Any?>?): List<Map<String, Any?>> =
        ((result?.get("equity_curve") as? List<Any?>).orEmpty()).mapNotNull { p ->
            val m = p as? Map<String, Any?> ?: return@mapNotNull null
            val t = m["t"] ?: return@mapNotNull null
            val v = (m["v"] as? Number)?.toDouble() ?: return@mapNotNull null
            linkedMapOf<String, Any?>("t" to t.toString(), "v" to v)
        }
}

data class SlippageCalibrationApply(@field:DecimalMin("0") @field:DecimalMax("1000") val proposedBps: Double)

/**
 * 실거래–백테스트 체결 정합 실측과 슬리피지 캘리브레이션 승인(원본 `routes/fill_quality.py`).
 * 캘리브레이션은 **사람 승인으로만** 반영 — 서버가 최신 리포트로 제안을 재계산해 요청값과 대조한다(오래된·조작된 값 차단).
 */
@RestController
class FillQualityController(private val strategies: StrategyRepository, private val reports: FillQualityReport) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/api/backtest/fill-quality")
    fun fillQuality(
        @CurrentUser user: User,
        @RequestParam(defaultValue = "90") @Min(1) @Max(1095) days: Int,
        @RequestParam(name = "date_from", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateFrom: LocalDate?,
        @RequestParam(name = "date_to", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) dateTo: LocalDate?,
        @RequestParam(name = "strategy_id", required = false) strategyId: Int?,
        @RequestParam(name = "annual_turnover", required = false) @DecimalMin("0") annualTurnover: Double?,
        @RequestParam(required = false) @DecimalMin(value = "0", inclusive = false) capital: Double?,
        @RequestParam(name = "min_sample", defaultValue = "30") @Min(1) @Max(1000) minSample: Int,
        @RequestParam(defaultValue = "false") detail: Boolean,
    ): Map<String, Any?> {
        val dTo = dateTo ?: MarketClock.todayKst()
        val dFrom = dateFrom ?: dTo.minusDays(days.toLong())
        if (dFrom.isAfter(dTo)) throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "시작일이 종료일보다 늦습니다.")
        strategyId?.let { strategies.owned(user, it) }
        val report = reports.report(user.id!!, dFrom, dTo, strategyId, annualTurnover, capital, minSample, detail)
        report["calibration"] = SlippageCalibration.propose(report, minSample)?.let {
            linkedMapOf(
                "current_bps" to it.currentBps, "proposed_bps" to it.proposedBps, "observed_median_bps" to it.observedMedianBps,
                "sample_size" to it.sampleSize, "clamped" to it.clamped, "reason" to it.reason,
            )
        }
        return report
    }

    @PostMapping("/api/backtest/slippage-calibration/{id}/apply")
    fun apply(@CurrentUser user: User, @PathVariable id: Int, @Valid @RequestBody payload: SlippageCalibrationApply): Map<String, Any?> {
        val strategy = strategies.owned(user, id)
        val dTo = MarketClock.todayKst()
        val proposal = SlippageCalibration.propose(reports.report(user.id!!, dTo.minusDays(90), dTo, id))
            ?: throw ApiException(HttpStatus.CONFLICT, "현재 반영할 슬리피지 캘리브레이션 제안이 없습니다(표본 부족 또는 유의 변화 없음).")
        if (abs(proposal.proposedBps - payload.proposedBps) > MATCH_TOL_BPS) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "제안값 불일치: 최신 제안은 ${pyFloat(proposal.proposedBps)}bp 입니다(요청 ${pyFloat(payload.proposedBps)}bp). 다시 확인 후 승인하세요.",
            )
        }
        val old = Cfg.num(Cfg.or(strategy.config, "slippage_bps", FillQuality.DEFAULT_SLIP_BPS))
        strategy.config = LinkedHashMap(strategy.config).apply { put("slippage_bps", proposal.proposedBps) }
        strategies.save(strategy)
        log.info(
            "슬리피지 캘리브레이션 반영: 전략 {} slippage_bps {}→{} (승인자={}, 실측 중앙값 {}bp, 표본 {}, 클램프={})",
            id, old, proposal.proposedBps, user.email, proposal.observedMedianBps, proposal.sampleSize, proposal.clamped,
        )
        return linkedMapOf(
            "strategy_id" to id, "previous_bps" to old, "applied_bps" to proposal.proposedBps, "observed_median_bps" to proposal.observedMedianBps,
            "sample_size" to proposal.sampleSize, "clamped" to proposal.clamped, "reason" to proposal.reason,
        )
    }

    private companion object {
        const val MATCH_TOL_BPS = 0.5
    }
}
