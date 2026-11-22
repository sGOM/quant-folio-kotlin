package com.quantfolio.factor

import com.quantfolio.quant.Stats
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * 종목 × 지표 표(원본 pandas DataFrame 대응). 숫자 결측은 NaN, 섹터 결측은 null. 행 순서 = [codes].
 * 없는 컬럼은 전부 NaN 으로 본다(원본 `df.get(name, nan_series)`).
 */
class FactorFrame(val codes: List<String>, private val numeric: Map<String, DoubleArray>, val sector: List<String?>? = null) {
    val size: Int get() = codes.size

    fun has(name: String) = name in numeric

    fun col(name: String): DoubleArray = numeric[name]?.copyOf() ?: DoubleArray(size) { Double.NaN }
}

/**
 * 멀티팩터 종합점수(원본 `metrics/factors._compute_stock_scores`). 카테고리별 윈저라이즈 z-score 평균 →
 * (선택) 사이즈·섹터 중립화 → 가중합. 결측 지표는 z 를 0(중립)으로 채우되, 모멘텀은 6개월 수익률이 없으면 점수 자체를 결측으로 둔다.
 * 반올림은 numpy round(half-even, 소수 4자리) — 순위 동점 처리가 여기서 결정되므로 바꾸지 말 것.
 */
object FactorScores {
    val DEFAULT_WEIGHTS = mapOf(
        "momentum" to 0.4, "value" to 0.3, "lowvol" to 0.3, "quality" to 0.0,
        "growth" to 0.0, "flow" to 0.0, "residual_momentum" to 0.0, "pead" to 0.0,
    )
    /** DART 재무 품질 컬럼. 조회 측(백테스트·실매매·추천)이 이 순서로 프레임에 채운다 — 순서가 결과 dict 순서. */
    val QUALITY_COLS = listOf("roe", "debt_ratio", "fcf", "f_score", "op_growth", "net_growth", "turnaround")

    /**
     * 카테고리 점수([compute])에 실제로 쓰이는 입력 컬럼. 입력 커버리지 검사(BacktestRunner)가 같은 정의를 쓴다 —
     * compute 의 value·quality·growth 계산을 바꾸면 여기도 함께 바꾼다. (QUALITY_COLS 에는 growth 입력이 섞여 있어 quality 입력이 아니다.)
     */
    val VALUE_INPUTS = listOf("PER", "PBR", "DIV")
    val QUALITY_INPUTS = listOf("roe", "debt_ratio", "fcf", "f_score")
    val GROWTH_INPUTS = listOf("op_growth", "net_growth", "turnaround")

    /** 백테스트가 fundamentals 에서 옮겨 담는 전 컬럼(원본 dict 순서). 팩터를 추가하면 여기와 compute 를 함께 고친다. */
    val FUNDAMENTAL_COLS = listOf("PER", "PBR", "DIV") + QUALITY_COLS + listOf("market_cap", "flow_norm", "resid_mom", "pead_sue")

    private val CATEGORIES = listOf("momentum", "value", "lowvol", "quality", "growth", "flow", "residual_momentum", "pead")

    /** 1%·99% 분위로 자른 뒤 모집단 표준편차(ddof=0) z-score. 유효값 3개 미만이면 전부 NaN, 표준편차 0 이면 전부 0. */
    fun winsorizeZ(s: DoubleArray, invert: Boolean = false): DoubleArray {
        val valid = s.filter { !it.isNaN() }.sorted()
        if (valid.size < 3) return DoubleArray(s.size) { Double.NaN }
        val lo = Stats.quantileSorted(valid, 0.01)
        val hi = Stats.quantileSorted(valid, 0.99)
        val clipped = DoubleArray(s.size) { if (s[it].isNaN()) Double.NaN else s[it].coerceIn(lo, hi) }
        val present = clipped.filter { !it.isNaN() }
        val mu = present.average()
        val std = Stats.std(present, 0)
        if (std == 0.0 || !std.isFinite()) return DoubleArray(s.size) { 0.0 }
        return DoubleArray(s.size) { val z = (clipped[it] - mu) / std; if (invert) -z else z }
    }

    private fun fill(a: DoubleArray, v: Double = 0.0) = DoubleArray(a.size) { if (a[it].isNaN()) v else a[it] }

    private fun meanOf(vararg cols: DoubleArray) = DoubleArray(cols[0].size) { i -> cols.sumOf { it[i] } / cols.size }

    /** @return 카테고리 점수 8개(`score_*`) + 종합 `score`, 행 순서 = frame.codes. */
    fun compute(frame: FactorFrame, weights: Map<String, Double>?, neutralize: String = "none"): Map<String, DoubleArray> {
        val w = if (weights.isNullOrEmpty()) DEFAULT_WEIGHTS else weights
        fun zc(name: String, invert: Boolean = false) = fill(winsorizeZ(frame.col(name), invert))

        val mom6 = frame.col("mom_6m")
        val momentum = meanOf(zc("mom_1m"), zc("mom_3m"), zc("mom_6m"), zc("high_52w_ratio"))
            .also { for (i in it.indices) if (mom6[i].isNaN()) it[i] = Double.NaN }
        val per = frame.col("PER").map { if (it > 0) it else Double.NaN }.toDoubleArray() // 적자·0 은 정의 불가
        val value = meanOf(fill(winsorizeZ(per, invert = true)), zc("PBR", invert = true), zc("DIV"))
        val lowvol = meanOf(zc("vol_ann", invert = true), zc("mdd_252"))
        val fcf = frame.col("fcf")
        val fcfPositive = DoubleArray(fcf.size) { if (fcf[it].isNaN()) Double.NaN else if (fcf[it] > 0) 1.0 else 0.0 }
        val quality = meanOf(zc("roe"), zc("debt_ratio", invert = true), fill(winsorizeZ(fcfPositive)), zc("f_score"))
        val growth = meanOf(*GROWTH_INPUTS.map { zc(it) }.toTypedArray())

        val scores = linkedMapOf(
            "score_momentum" to momentum, "score_value" to value, "score_lowvol" to lowvol, "score_quality" to quality,
            "score_growth" to growth, "score_flow" to zc("flow_norm"), "score_residual_momentum" to zc("resid_mom"), "score_pead" to zc("pead_sue"),
        )
        if (neutralize == "size" || neutralize == "size_sector") neutralizeSize(frame, scores)
        if (neutralize == "sector" || neutralize == "size_sector") neutralizeSector(frame, scores)

        val total = DoubleArray(frame.size) { i ->
            CATEGORIES.sumOf { c -> (w[c] ?: DEFAULT_WEIGHTS.getValue(c)) * scores.getValue("score_$c")[i] }
        }
        val out = LinkedHashMap<String, DoubleArray>()
        for ((k, v) in scores) out[k] = round4(v)
        out["score"] = round4(total)
        return out
    }

    private fun round4(a: DoubleArray) = DoubleArray(a.size) { if (a[it].isNaN()) Double.NaN else Math.rint(a[it] * 10_000) / 10_000 }

    /** 표준화한 log(시총) 에 대한 회귀 잔차로 바꾼다(소형주 쏠림 제거). 유효 종목 5개 미만이면 그대로. */
    private fun neutralizeSize(frame: FactorFrame, scores: MutableMap<String, DoubleArray>) {
        if (!frame.has("market_cap")) return
        val cap = frame.col("market_cap")
        val logcap = DoubleArray(cap.size) { if (cap[it] > 0) ln(cap[it]) else Double.NaN }
        val present = logcap.filter { !it.isNaN() }
        if (present.isEmpty()) return
        val mean = present.average()
        val sd = sqrt(present.sumOf { (it - mean) * (it - mean) } / present.size)
        if (!(sd > 0)) return
        val x = DoubleArray(cap.size) { (logcap[it] - mean) / sd }
        val valid = x.indices.filter { !x[it].isNaN() }
        if (valid.size < 5 || !(valid.sumOf { x[it] * x[it] } > 0)) return
        for ((name, s) in scores) {
            val m = valid.filter { !s[it].isNaN() }
            if (m.size < 5) continue
            val denom = m.sumOf { x[it] * x[it] }
            if (!(denom > 0)) continue
            val beta = m.sumOf { s[it] * x[it] } / denom
            val updated = s.copyOf()
            for (i in m) updated[i] = s[i] - beta * x[i]
            scores[name] = updated
        }
    }

    /** 섹터 평균을 뺀다(단독 섹터 종목은 그대로). 섹터가 있는 종목이 5개 미만이면 그대로. */
    private fun neutralizeSector(frame: FactorFrame, scores: MutableMap<String, DoubleArray>) {
        val sector = frame.sector ?: return
        val valid = sector.indices.filter { sector[it] != null }
        if (valid.size < 5) return
        for ((name, s) in scores) {
            val m = valid.filter { !s[it].isNaN() }
            if (m.size < 5) continue
            val groups = m.groupBy { sector[it]!! }
            val updated = s.copyOf()
            for ((_, idx) in groups) {
                if (idx.size <= 1) continue
                val mean = idx.sumOf { s[it] } / idx.size
                for (i in idx) updated[i] = s[i] - mean
            }
            scores[name] = updated
        }
    }
}
