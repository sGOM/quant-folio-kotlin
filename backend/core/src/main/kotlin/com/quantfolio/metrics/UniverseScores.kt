package com.quantfolio.metrics

import com.quantfolio.factor.FactorCoverage
import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.factor.ResidualMomentum
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.krx.KrxIndexService
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.store.closeSeries
import com.quantfolio.quant.Series
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 팩터 점수가 쓰는 재무·시총·업종 조회(테스트에서 교체 가능). */
interface FactorInputs {
    fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean): Map<String, Map<String, Any?>>
    fun peadSue(codes: List<String>, asOf: LocalDate, lookbackQ: Int): Map<String, Double>
    fun marketCaps(asOf: LocalDate): Map<String, Long>
    fun sectorMap(asOf: LocalDate): Map<String, String>
    fun dartEnabled(): Boolean
}

@Component
class LiveFactorInputs(private val dart: DartFinancials, private val krx: KrxIndexService) : FactorInputs {
    override fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean) = dart.metricsBySymbol(codes, asOf, useTtm)
    override fun peadSue(codes: List<String>, asOf: LocalDate, lookbackQ: Int) = dart.peadSueBySymbol(codes, asOf, lookbackQ)
    override fun marketCaps(asOf: LocalDate) = krx.marketCaps(asOf)
    override fun sectorMap(asOf: LocalDate) = krx.sectorMap(asOf)
    override fun dartEnabled() = dart.enabled()
}

/** 리밸런싱 점수 파라미터(원본 compute_universe_scores 인자, 기본값 동일). */
data class UniverseScoreParams(
    val factorWeights: Map<String, Double>? = null,
    val neutralize: String = "none",
    val financialPeriod: String = "annual",
    val flowWindow: Int = 90,
    val flowDenom: String = "mcap",
    val residRegWindow: Int = 36,
    val residWindow: Int = 11,
    val residSkip: Int = 1,
    val peadLookbackQ: Int = 8,
    /** true 면 가중치가 걸린 value·quality·growth 의 입력 커버리지가 [FactorCoverage.MIN] 미만일 때 [MarketDataNotLoaded](실매매 경로). */
    val requireCoverage: Boolean = false,
)

/**
 * 리밸런싱 selection.method="score" 전용 종합점수(원본 `metrics/factors.compute_universe_scores`).
 * asOf 는 호출자가 정한 직전 확정 영업일이어야 한다 — 여기서 '오늘'로 보정하지 않는다(미래참조 방지).
 * 가중치가 걸린 팩터가 전량 조회 실패면 예외로 리밸런싱을 건너뛴다(조용히 중립 처리하면 §47 사고 재현).
 * `requireCoverage`(엔진)면 더 엄격하게, 입력 커버리지 50% 미만에서 [com.quantfolio.factor.FactorCoverage] 가 `MarketDataNotLoaded` 를 던진다.
 */
@Component
class UniverseScores(
    private val snapshots: SnapshotSource,
    private val tech: TechIndicatorLoader,
    private val inputs: FactorInputs,
    private val prices: PriceHistory,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mkts = listOf("KOSPI", "KOSDAQ") // universe 종목의 소속 시장을 미리 알 수 없어 양쪽 조회

    fun compute(symbols: List<String>, asOf: LocalDate, p: UniverseScoreParams = UniverseScoreParams()): Map<String, Double> {
        if (symbols.isEmpty()) return emptyMap()
        val codes = symbols.map { it.trim().padStart(6, '0') }
        val fund = snapshots.fundamentals(asOf, mkts).associateBy { it.symbol }
        val pc21 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 21), asOf, mkts).associateBy { it.symbol }
        val pc63 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 63), asOf, mkts).associateBy { it.symbol }
        val pc126 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 126), asOf, mkts).associateBy { it.symbol }
        val w = p.factorWeights?.takeIf { it.isNotEmpty() } ?: FactorScores.DEFAULT_WEIGHTS // 원본 `or` — 빈 맵도 기본 가중치
        fun weighted(k: String) = (w[k] ?: 0.0) > 0
        if (weighted("momentum") && pc21.isEmpty() && pc63.isEmpty() && pc126.isEmpty()) {
            throw IllegalStateException("모멘텀 팩터 데이터 전량 조회 실패(등락률 21/63/126일 모두 빈 응답) — 왜곡된 목표 산출 방지 위해 리밸런싱 건너뜀(다음 주기 재시도)")
        }
        val cols = linkedMapOf<String, DoubleArray>()
        fun put(name: String, f: (String) -> Double?) {
            cols[name] = DoubleArray(codes.size) { f(codes[it]) ?: Double.NaN }
        }
        if (p.requireCoverage && weighted("value")) {
            FactorCoverage.require("value", asOf, codes.count { c -> fund[c]?.let { it.per != null || it.pbr != null || it.div != null } == true }, codes.size)
        }
        put("PER") { fund[it]?.per }
        put("PBR") { fund[it]?.pbr }
        put("DIV") { fund[it]?.div }
        put("mom_1m") { pc21[it]?.changePct?.div(100.0) }
        put("mom_3m") { pc63[it]?.changePct?.div(100.0) }
        put("mom_6m") { pc126[it]?.changePct?.div(100.0) }

        val histStart = MetricsCommon.approxStart(asOf, 270, buffer = 30)
        val t = codes.associateWith { tech.load(it, histStart, asOf) }
        put("high_52w_ratio") { t.getValue(it).high52wRatio }
        put("vol_ann") { t.getValue(it).volAnn }
        put("mdd_252") { t.getValue(it).mdd252 }

        val quality = if (!weighted("quality") && !weighted("growth")) {
            emptyMap() // 가중치가 0 이면 점수에 쓰이지 않는다 — 재무를 조회하지 않는다
        } else {
            try {
                inputs.qualityMetrics(codes, asOf, p.financialPeriod == "ttm")
            } catch (e: DataSourceError) {
                log.error("OpenDART 퀄리티 팩터 조회 실패 — 중립 처리: {}", e.message)
                emptyMap()
            }
        }
        if (p.requireCoverage) {
            if (weighted("quality")) FactorCoverage.require("quality", asOf, codes.count { c -> FactorScores.QUALITY_INPUTS.any { quality[c]?.get(it) != null } }, codes.size)
            if (weighted("growth")) FactorCoverage.require("growth", asOf, codes.count { c -> FactorScores.GROWTH_INPUTS.any { quality[c]?.get(it) != null } }, codes.size)
        }
        if (quality.isNotEmpty()) {
            for (k in FactorScores.QUALITY_COLS) {
                if (quality.values.any { k in it }) put(k) { (quality[it]?.get(k) as? Number)?.toDouble() }
            }
        }
        var sector: List<String?>? = null
        if (p.neutralize == "size" || p.neutralize == "size_sector") {
            val caps = try {
                inputs.marketCaps(asOf)
            } catch (e: DataSourceError) {
                log.error("사이즈 중립화용 시가총액 조회 실패 — 중립화 생략: {}", e.message)
                emptyMap()
            }
            if (caps.isNotEmpty()) put("market_cap") { caps[it]?.toDouble() }
        }
        if (p.neutralize == "sector" || p.neutralize == "size_sector") {
            val smap = try {
                inputs.sectorMap(asOf)
            } catch (e: DataSourceError) {
                log.error("섹터 중립화용 업종분류 조회 실패 — 중립화 생략: {}", e.message)
                emptyMap()
            }
            if (smap.isNotEmpty()) sector = codes.map { smap[it] }
        }
        if (weighted("flow")) {
            val flow = guarded("수급(flow)") { flowNorm(codes, asOf, p.flowWindow, p.flowDenom) }
            if (flow.isEmpty()) throw IllegalStateException("수급(flow) 팩터 데이터 전량 조회 실패(외국인+기관 순매수 빈 응답) — 왜곡된 목표 산출 방지 위해 리밸런싱 건너뜀(다음 주기 재시도)")
            put("flow_norm") { flow[it] }
        }
        if (weighted("residual_momentum")) {
            val rm = guarded("잔차 모멘텀") { residualMomentum(codes, asOf, p.residRegWindow, p.residWindow, p.residSkip) }
            if (rm.isEmpty()) throw IllegalStateException("잔차 모멘텀 팩터 데이터 전량 조회 실패(종가/KOSPI200 지수 빈 응답) — 왜곡된 목표 산출 방지 위해 리밸런싱 건너뜀(다음 주기 재시도)")
            put("resid_mom") { rm[it] }
        }
        if (weighted("pead")) {
            val pead = guarded("PEAD(SUE)") { inputs.peadSue(codes, asOf, p.peadLookbackQ) }
            if (pead.isEmpty()) throw IllegalStateException("PEAD(SUE) 팩터 데이터 전량 조회 실패(정기공시/재무 빈 응답) — 왜곡된 목표 산출 방지 위해 리밸런싱 건너뜀(다음 주기 재시도)")
            put("pead_sue") { pead[it] }
        }
        val score = FactorScores.compute(FactorFrame(codes, cols, sector), p.factorWeights, p.neutralize).getValue("score")
        return codes.indices.filter { !score[it].isNaN() }.associate { codes[it] to score[it] }
    }

    private fun guarded(label: String, block: () -> Map<String, Double>): Map<String, Double> = try {
        block()
    } catch (e: MarketDataNotLoaded) {
        throw e // 미적재는 중립 처리하지 않고 호출자가 멈춘다
    } catch (e: Exception) {
        log.warn("{} 팩터 조회 실패 — 중립 처리: {}", label, e.message)
        emptyMap()
    }

    /** 외국인+기관 누적 순매수 ÷ 시총(mcap) 또는 구간 거래대금(value). 순매수가 비면 분모 조회도 하지 않는다. */
    fun flowNorm(codes: List<String>, asOf: LocalDate, window: Int = 90, denom: String = "mcap"): Map<String, Double> {
        if (codes.isEmpty()) return emptyMap()
        val start = MetricsCommon.approxStart(asOf, window)
        val net = snapshots.netPurchases(start, asOf, mkts).associate { it.symbol to it.netBuyValue }
        if (net.isEmpty()) return emptyMap()
        val den: Map<String, Double?> = if (denom == "value") {
            snapshots.priceChange(start, asOf, mkts).associate { it.symbol to it.tradingValue?.toDouble() }
        } else {
            snapshots.marketCap(asOf, mkts).associate { it.symbol to it.marketCap?.toDouble() }
        }
        val out = linkedMapOf<String, Double>()
        for (c in codes.map { it.trim().padStart(6, '0') }) {
            val nv = net[c] ?: continue
            if (nv.isNaN()) continue
            val dv = den[c] ?: continue
            if (dv.isNaN() || dv <= 0) continue
            out[c] = nv / dv
        }
        return out
    }

    /** 종가·KOSPI200(1028) 지수로 잔차 모멘텀. 지수 조회 실패는 전파, 종목별 실패는 그 종목만 제외. */
    fun residualMomentum(codes: List<String>, asOf: LocalDate, regWindow: Int = 36, momWindow: Int = 11, skip: Int = 1): Map<String, Double> {
        if (codes.isEmpty()) return emptyMap()
        val norm = codes.map { it.trim().padStart(6, '0') }
        val start = MetricsCommon.approxStart(asOf, (regWindow + 3) * 22, buffer = 45)
        val idx = snapshots.indexOhlcv(start, asOf, "1028")
        if (idx.isNullOrEmpty()) return emptyMap()
        val market = Series(idx.map { it.date }, DoubleArray(idx.size) { idx[it].close ?: Double.NaN })
        val closes = linkedMapOf<String, Series>()
        for (c in norm) {
            val bars = try {
                prices.load(c, start, asOf)
            } catch (e: MarketDataNotLoaded) {
                throw e
            } catch (e: Exception) {
                null
            }
            if (bars.isNullOrEmpty()) continue
            closes[c] = bars.closeSeries()
        }
        if (closes.isEmpty()) return emptyMap()
        return ResidualMomentum.compute(closes, market, asOf, norm, regWindow, momWindow, skip)
    }
}
