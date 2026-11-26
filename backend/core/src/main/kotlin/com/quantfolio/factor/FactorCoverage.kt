package com.quantfolio.factor

import com.quantfolio.market.official.MarketDataNotLoaded
import java.time.LocalDate

/**
 * 가중치가 걸린 팩터의 입력 커버리지 관문 — 백테스트(`BacktestRunner`)와 실매매 점수(`UniverseScores`)가 같이 쓴다.
 * 후보의 [MIN] 미만만 입력을 가지면 점수가 결측 패턴에 좌우되므로 중립 처리하지 않고 [MarketDataNotLoaded].
 * 임계값은 측정 후 조정 가능하다.
 */
object FactorCoverage {
    const val MIN = 0.5

    /** [have] / [total] 이 [MIN] 미만이면 미적재. 후보가 없는 날([total] == 0)은 미적재가 아니라 검사하지 않는다. */
    fun require(factor: String, asOf: LocalDate, have: Int, total: Int) {
        if (total == 0) return
        val coverage = have.toDouble() / total
        if (coverage < MIN) {
            throw MarketDataNotLoaded("factor_coverage", "$factor $asOf coverage=${(coverage * 100).toInt()}% ($have/$total, 최소 ${(MIN * 100).toInt()}%)")
        }
    }
}
