package com.quantfolio.backtest

import org.apache.commons.math3.distribution.NormalDistribution
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** MinTRL(Bailey & López de Prado 2012) — 원본에 없는 추가 계산이라 정답지는 scipy 로 직접 계산한 값. */
class MinTrlTest {
    private val curve = (0 until 120).map { i -> mapOf<String, Any?>("t" to "d$i", "v" to 1e7 * (1 + 0.001 * i + 0.01 * sin(i.toDouble()))) }

    @Test
    fun `matches scipy`() {
        // algorithm/screen_eval.py 와 같은 식, scipy skew/kurtosis(bias=False), sr = 1.2/√252
        assertEquals(474.415754022698, DeflatedSharpe.minTrl(mapOf("sharpe" to 1.2, "equity_curve" to curve))!!, 1e-9)
    }

    @Test
    fun `psr at min trl equals confidence`() {
        val target = mapOf("sharpe" to 1.2, "equity_curve" to curve)
        val n = DeflatedSharpe.minTrl(target, alpha = 0.1)!!
        val a = DeflatedSharpe.analyze(target, emptyList())
        val sr = a["sharpe_daily"] as Double
        val se = sqrt(1 - (a["skew"] as Double) * sr + ((a["kurt"] as Double) - 1) / 4 * sr * sr)
        assertEquals(0.9, NormalDistribution(0.0, 1.0).cumulativeProbability(sr * sqrt(n - 1) / se), 1e-12)
    }

    @Test
    fun `null when sharpe not positive or sample short`() {
        assertNull(DeflatedSharpe.minTrl(mapOf("sharpe" to 0.0, "equity_curve" to curve)))
        assertNull(DeflatedSharpe.minTrl(mapOf("sharpe" to -0.5, "equity_curve" to curve)))
        assertNull(DeflatedSharpe.minTrl(mapOf("sharpe" to 1.2, "equity_curve" to curve.take(10))))
        assertNull(DeflatedSharpe.minTrl(mapOf("equity_curve" to curve)))
    }
}
