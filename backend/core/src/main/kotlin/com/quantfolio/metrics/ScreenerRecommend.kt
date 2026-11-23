package com.quantfolio.metrics

import com.fasterxml.jackson.annotation.JsonProperty
import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.ohlcv.IndexMembers
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.quant.Stats
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

data class TurnaroundCandidate(
    val code: String,
    val name: String,
    val market: String,
    val marketCap: Long,
    @JsonProperty("avg_value_20") val avgValue20: Double,
    val turnoverSurge: Double? = null,
    val netIncome: Double? = null,
    val debtRatio: Double? = null,
    val turnaround: Double? = null,
    val netGrowth: Double? = null,
    val fScore: Int? = null,
    @JsonProperty("loss_years_3") val lossYears3: Int? = null,
    val per: Double? = null,
    val pbr: Double? = null,
    val score: Double,
)

data class TurnaroundScreenOut(
    val asOf: LocalDate,
    val market: String,
    val scanned: Int,
    val count: Int,
    val opendartEnabled: Boolean,
    val financialFilterApplied: Boolean = true,
    val items: List<TurnaroundCandidate>,
)

data class RecommendMember(
    val code: String,
    val name: String,
    val price: Long? = null,
    val changeRate: Double? = null,
    val marketCap: Long? = null,
    @JsonProperty("avg_value_20") val avgValue20: Double,
    val per: Double? = null,
    val pbr: Double? = null,
    val div: Double? = null,
    @JsonProperty("mom_3m") val mom3m: Double? = null,
    @JsonProperty("mom_6m") val mom6m: Double? = null,
    val volAnn: Double? = null,
    @JsonProperty("mdd_252") val mdd252: Double? = null,
    val trendAligned: Boolean? = null,
    @JsonProperty("above_sma200") val aboveSma200: Boolean? = null,
    val scoreMomentum: Double? = null,
    val scoreValue: Double? = null,
    val scoreLowvol: Double? = null,
    val scoreQuality: Double? = null,
    val scoreGrowth: Double? = null,
)

data class RecommendOut(val asOf: LocalDate, val index: String, val count: Int, val opendartEnabled: Boolean, val items: List<RecommendMember>)

/** 종목 → KRX 종목명. 앞 프레임 우선, 공백 이름은 건너뛴다. */
internal fun krxNames(vararg frames: Collection<StockPeriod>): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (f in frames) for (p in f) {
        val n = p.name?.trim()
        if (p.symbol !in out && !n.isNullOrEmpty()) out[p.symbol] = n
    }
    return out
}

private fun num(v: Any?): Double? = (v as? Number)?.toDouble()?.takeIf { it.isFinite() }

/**
 * 소형주 턴어라운드 스크리너(원본 `services/screener.py`): 시총 하위 pct → 유동성·거래대금 급증 → (OpenDART) 부채·만성적자 하드 필터
 * → 흑자전환·실적개선·F-Score·수급 가중 점수 상위 topN. OpenDART 조회 실패면 재무 필터 없이 반환하고 그 사실을 표시한다.
 */
@Component
class ScreenerService(
    @Qualifier("local") private val snapshots: SnapshotSource,
    private val inputs: FactorInputs,
    private val catalog: SymbolCatalog,
    private val common: MetricsCommon,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun screenTurnaround(
        asOf: LocalDate? = null,
        market: String = "ALL",
        topN: Int = 20,
        smallcapPct: Double = 0.20,
        minValue: Double = 300_000_000.0,
        surge: Double = 1.5,
        maxDebt: Double = 1.5,
        maxCandidates: Int = 60,
    ): TurnaroundScreenOut {
        val day = asOf ?: common.lastBusinessDay()
        val mkts = MetricsCommon.mkts(market)
        val caps = snapshots.marketCap(day, mkts)
        if (caps.isEmpty()) return TurnaroundScreenOut(day, market, 0, 0, inputs.dartEnabled(), items = emptyList())
        val threshold = quantile(caps.mapNotNull { it.marketCap?.toDouble() }, smallcapPct)
        val small = caps.filter { (it.marketCap?.toDouble() ?: Double.NaN) <= threshold }
        val pc5 = snapshots.priceChange(MetricsCommon.approxStart(day, 5, buffer = 4), day, mkts)
        val pc60 = snapshots.priceChange(MetricsCommon.approxStart(day, 60), day, mkts)
        val avg5 = pc5.associate { it.symbol to (it.tradingValue?.toDouble() ?: Double.NaN) / 5.0 }
        val avg60 = pc60.associate { it.symbol to (it.tradingValue?.toDouble() ?: Double.NaN) / 60.0 }
        data class Row(val code: String, val market: String?, val cap: Long, val avg: Double, val surge: Double)
        val liquid = small.map { c ->
            val a60 = avg60[c.symbol] ?: Double.NaN
            val a5 = avg5[c.symbol] ?: Double.NaN
            Row(c.symbol, c.market, c.marketCap!!, a60, a5 / (if (a60 == 0.0) Double.NaN else a60))
        }.filter { r -> (r.avg.takeIf { !it.isNaN() } ?: 0.0) >= minValue && (r.surge.takeIf { !it.isNaN() } ?: 0.0) >= surge }
            .sortedByDescending { it.surge }.take(maxCandidates)
        val fund = snapshots.fundamentals(day, mkts).associateBy { it.symbol }
        var filterApplied = true
        val quality = if (liquid.isEmpty()) emptyMap() else try {
            inputs.qualityMetrics(liquid.map { it.code }, day, false)
        } catch (e: DataSourceError) {
            log.error("스크리너 OpenDART 조회 실패 — 재무 하드 필터를 적용하지 못한 채 반환한다: {}", e.message)
            filterApplied = false
            emptyMap()
        }
        val seed = runCatching { catalog.nameMap() }.getOrDefault(emptyMap())
        val names = krxNames(pc5, pc60)
        val enabled = inputs.dartEnabled()
        val items = liquid.mapNotNull { r ->
            val fin = quality[r.code] ?: emptyMap()
            val debt = num(fin["debt_ratio"])
            val loss3 = num(fin["loss_years_3"])
            val net = num(fin["net_income"])
            val turn = num(fin["turnaround"])
            val netg = num(fin["net_growth"])
            val fscore = num(fin["f_score"])
            if (enabled && fin.isNotEmpty()) {
                if (debt != null && debt > maxDebt) return@mapNotNull null
                if (loss3 != null && loss3 >= 3) return@mapNotNull null
            }
            val surgeV = r.surge.takeIf { it.isFinite() }
            TurnaroundCandidate(
                code = r.code, name = seed[r.code] ?: names[r.code] ?: r.code,
                market = r.market?.trim()?.takeIf { it.isNotEmpty() } ?: market,
                marketCap = r.cap, avgValue20 = r.avg.takeIf { it.isFinite() && it != 0.0 } ?: 0.0, turnoverSurge = surgeV,
                netIncome = net, debtRatio = debt, turnaround = turn, netGrowth = netg, fScore = fscore?.toInt(), lossYears3 = loss3?.toInt(),
                per = fund[r.code]?.per?.takeIf { it.isFinite() }, pbr = fund[r.code]?.pbr?.takeIf { it.isFinite() },
                score = BigDecimal(candidateScore(surgeV, turn, netg, fscore, net)).setScale(4, RoundingMode.HALF_EVEN).toDouble(),
            )
        }.sortedByDescending { it.score }.take(topN)
        return TurnaroundScreenOut(day, market, liquid.size, items.size, enabled, filterApplied, items)
    }

    private fun candidateScore(surge: Double?, turn: Double?, netg: Double?, fscore: Double?, net: Double?): Double {
        var s = 0.0
        if (turn != null && turn != 0.0) s += 2.0
        if (net != null && net > 0) s += 0.5
        if (netg != null) s += netg.coerceIn(-1.0, 2.0)
        if (fscore != null) s += fscore / 8.0
        if (surge != null) s += minOf(surge, 3.0) / 3.0
        return s
    }

    /** pandas Series.quantile(linear). */
    private fun quantile(v: List<Double>, q: Double): Double = if (v.isEmpty()) Double.NaN else Stats.quantileSorted(v.sorted(), q)
}

/**
 * KOSPI200 팩터 추천(원본 `services/recommend.py`). 가중치와 무관한 팩터별 세부점수(score_*)를 KOSPI200 크로스섹션 z 로 내려주고,
 * 가중합·Top-N 은 프론트가 사용자 비중으로 즉시 계산한다. 구성종목·시총이 없으면 빈 결과.
 */
@Component
class RecommendService(
    private val members: IndexMembers,
    @Qualifier("local") private val snapshots: SnapshotSource,
    private val tech: TechIndicatorLoader,
    private val inputs: FactorInputs,
    private val catalog: SymbolCatalog,
    private val common: MetricsCommon,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun kospi200Scored(asOf: LocalDate): RecommendOut {
        val enabled = inputs.dartEnabled()
        val mem = members.indexMembers(asOf, INDEX).map { it.padStart(6, '0') }
        if (mem.isEmpty()) {
            log.warn("KOSPI200 구성종목을 확보하지 못함(미인증/무자료) — 빈 추천 반환")
            return RecommendOut(asOf, INDEX, 0, enabled, emptyList())
        }
        val mkts = listOf("KOSPI")
        val fund = snapshots.fundamentals(asOf, mkts).associateBy { it.symbol }
        val caps = snapshots.marketCap(asOf, mkts)
        if (caps.isEmpty()) {
            log.warn("KOSPI 시가총액 데이터 없음(as_of={}) — 빈 추천 반환", asOf)
            return RecommendOut(asOf, INDEX, 0, enabled, emptyList())
        }
        val pc1 = snapshots.priceChange(common.prevBusinessDay(asOf), asOf, mkts)
        val pc21 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 21), asOf, mkts)
        val pc63 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 63), asOf, mkts)
        val pc126 = snapshots.priceChange(MetricsCommon.approxStart(asOf, 126), asOf, mkts)
        val memberSet = mem.toSet()
        val rows = caps.filter { it.symbol in memberSet }
        val codes = rows.map { it.symbol }
        val p1 = pc1.associateBy { it.symbol }
        val p21 = pc21.associateBy { it.symbol }
        val p63 = pc63.associateBy { it.symbol }
        val p126 = pc126.associateBy { it.symbol }
        val histStart = MetricsCommon.approxStart(asOf, 270, buffer = 30)
        val t = codes.associateWith { tech.load(it, histStart, asOf) }
        val quality = try {
            inputs.qualityMetrics(mem, asOf, false)
        } catch (e: DataSourceError) {
            log.error("추천 OpenDART 퀄리티/성장 팩터 조회 실패 — 중립 처리: {}", e.message)
            emptyMap()
        }
        val cols = linkedMapOf<String, DoubleArray>()
        fun put(k: String, f: (String) -> Double?) {
            cols[k] = DoubleArray(codes.size) { f(codes[it]) ?: Double.NaN }
        }
        put("PER") { fund[it]?.per }
        put("PBR") { fund[it]?.pbr }
        put("DIV") { fund[it]?.div }
        put("mom_1m") { p21[it]?.changePct?.div(100.0) }
        put("mom_3m") { p63[it]?.changePct?.div(100.0) }
        put("mom_6m") { p126[it]?.changePct?.div(100.0) }
        put("high_52w_ratio") { t.getValue(it).high52wRatio }
        put("vol_ann") { t.getValue(it).volAnn }
        put("mdd_252") { t.getValue(it).mdd252 }
        if (quality.isNotEmpty()) {
            for (k in FactorScores.QUALITY_COLS) {
                if (quality.values.any { k in it }) put(k) { num(quality[it]?.get(k)) }
            }
        }
        val scored = FactorScores.compute(FactorFrame(codes, cols), null, "none")
        val seed = runCatching { catalog.nameMap() }.getOrDefault(emptyMap())
        val names = krxNames(pc1, pc21, pc63, pc126)
        val items = rows.mapIndexed { i, c ->
            val code = c.symbol
            val shares = c.shares?.takeIf { it != 0L }
            val price = p1[code]?.close?.takeIf { !it.isNaN() } ?: if (c.marketCap != null && shares != null) Math.rint(c.marketCap.toDouble() / shares) else null
            val tv = p21[code]?.tradingValue?.let { it / 20.0 }
            fun sc(k: String) = scored.getValue(k)[i].takeIf { !it.isNaN() }
            val ti = t.getValue(code)
            RecommendMember(
                code = code, name = seed[code] ?: names[code] ?: code,
                price = price?.let { Math.rint(it).toLong() }, changeRate = p1[code]?.changePct?.div(100.0), marketCap = c.marketCap,
                avgValue20 = tv?.takeIf { it != 0.0 } ?: 0.0,
                per = fund[code]?.per, pbr = fund[code]?.pbr, div = fund[code]?.div,
                mom3m = p63[code]?.changePct?.div(100.0), mom6m = p126[code]?.changePct?.div(100.0),
                volAnn = ti.volAnn, mdd252 = ti.mdd252, trendAligned = ti.trendAligned, aboveSma200 = ti.aboveSma200,
                scoreMomentum = sc("score_momentum"), scoreValue = sc("score_value"), scoreLowvol = sc("score_lowvol"),
                scoreQuality = sc("score_quality"), scoreGrowth = sc("score_growth"),
            )
        }.sortedByDescending { it.marketCap ?: -1 }
        log.info("KOSPI200 추천 스코어링 완료: {}종목(as_of={})", items.size, asOf)
        return RecommendOut(asOf, INDEX, items.size, enabled, items)
    }

    private companion object {
        const val INDEX = "KOSPI200"
    }
}
