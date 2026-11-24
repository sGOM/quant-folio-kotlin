package com.quantfolio.web.backtest

import com.quantfolio.backtest.BacktestRunner
import com.quantfolio.backtest.DeflatedSharpe
import com.quantfolio.backtest.Pbo
import com.quantfolio.common.MarketClock
import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotNull
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

data class BacktestRequest(@field:NotNull val periodStart: LocalDate?, @field:NotNull val periodEnd: LocalDate?)

data class BacktestOut(
    val id: Int,
    val strategyId: Int,
    val periodStart: Instant,
    val periodEnd: Instant,
    val totalReturn: Double?,
    val mdd: Double?,
    val sharpe: Double?,
    val result: Map<String, Any?>?,
    val createdAt: Instant,
)

fun Backtest.toOut() = BacktestOut(id!!, strategyId, periodStart, periodEnd, totalReturn?.toDouble(), mdd?.toDouble(), sharpe?.toDouble(), result, createdAt)

/** 백테스트 실행·조회(원본 `routes/backtests.py`). 결과는 저장되고, 첫 백테스트에서 전략은 draft → backtested. */
@RestController
@RequestMapping("/api")
class BacktestsController(
    private val strategies: StrategyRepository,
    private val backtests: BacktestRepository,
    private val runner: BacktestRunner,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
) {
    private fun ownedBacktest(user: User, id: Int): Backtest {
        val bt = backtests.findById(id).orElse(null)
        if (bt == null || strategies.findById(bt.strategyId).map { it.userId != user.id }.orElse(true)) {
            throw ApiException(HttpStatus.NOT_FOUND, "백테스트를 찾을 수 없습니다.")
        }
        return bt
    }

    /** numeric(12,6) 컬럼 — Postgres 처럼 소수 6자리 half-away-from-zero. 원본은 저장 후 refresh 한 반올림 값을 응답한다. */
    private fun num(v: Any?) = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }?.let { BigDecimal.valueOf(it).setScale(6, java.math.RoundingMode.HALF_UP) }

    @PostMapping("/strategies/{id}/backtest")
    @ResponseStatus(HttpStatus.CREATED)
    fun run(@CurrentUser user: User, @PathVariable id: Int, @Valid @RequestBody req: BacktestRequest): BacktestOut {
        val strategy = strategies.owned(user, id)
        val start = req.periodStart!!
        val end = req.periodEnd!!
        val result = runner.run(strategy.config, start, end)
        // 백테스트 행과 전략 상태 전환은 한 커밋(원본과 같음) — 따로면 상태 갱신 실패 시 저장된 실행이 500 으로 보이고 재시도가 DSR 시행 수를 부풀린다.
        val bt = tx.execute {
            val saved = backtests.saveAndFlush(
                Backtest(
                    strategy.id!!, MarketClock.kstStart(start), MarketClock.kstEnd(end),
                    num(result["total_return"]), num(result["mdd"]), num(result["sharpe"]), result,
                ),
            )
            strategies.markBacktested(strategy.id!!) // 실행 전에 읽은 엔티티를 save 하면 백테스트 도중의 이름·설정 수정을 덮는다
            saved
        }!!
        return backtests.findById(bt.id!!).get().toOut()
    }

    @GetMapping("/strategies/{id}/backtests")
    fun list(@CurrentUser user: User, @PathVariable id: Int): List<BacktestOut> {
        strategies.owned(user, id)
        return backtests.findByStrategyIdOrderByIdDesc(id).map { it.toOut() }
    }

    @GetMapping("/backtests/{id}")
    fun get(@CurrentUser user: User, @PathVariable id: Int) = ownedBacktest(user, id).toOut()

    /**
     * Deflated Sharpe(과최적화 방어). 동질 시행 = 같은 전략·같은 기간의 이력, 유니버스 지문이 있으면 지문까지 같은 것만.
     * `pbo` 는 원본에 없는 추가 필드 — 같은 동질 시행의 공통 월 수익률로 [Pbo.cscv](시행 < 2·월 < 8 이면 null).
     */
    @GetMapping("/backtests/{id}/dsr")
    fun dsr(@CurrentUser user: User, @PathVariable id: Int): Map<String, Any?> {
        val bt = ownedBacktest(user, id)
        val target = bt.result?.takeIf { it.isNotEmpty() } ?: throw ApiException(HttpStatus.UNPROCESSABLE_CONTENT, "백테스트 결과 데이터가 없습니다.")
        val candidates = backtests.findByStrategyIdAndPeriodStartAndPeriodEnd(bt.strategyId, bt.periodStart, bt.periodEnd).mapNotNull { it.result?.takeIf { r -> r.isNotEmpty() } }
        val fp = target["universe_fingerprint"]
        val homogeneous = if (fp != null && fp != "") candidates.filter { it["universe_fingerprint"] == fp } else candidates
        return linkedMapOf<String, Any?>("backtest_id" to bt.id, "strategy_id" to bt.strategyId, "period_start" to iso(bt.periodStart), "period_end" to iso(bt.periodEnd)) +
            DeflatedSharpe.analyze(target, homogeneous) + ("pbo" to Pbo.cscv(Pbo.monthlyMatrix(homogeneous)))
    }

    /** 원본 DSR 라우트는 응답 모델 없이 jsonable_encoder(= isoformat, `+00:00`) — `Instant` 그대로면 Jackson 이 `Z` 로 쓴다. */
    private fun iso(t: java.time.Instant) = com.quantfolio.alert.pyIsoformat(t.atZone(java.time.ZoneOffset.UTC))
}
