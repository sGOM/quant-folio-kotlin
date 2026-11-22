package com.quantfolio.metrics

import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.market.kofia.KofiaClient
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.store.closeSeries
import com.quantfolio.market.symbols.SymbolCatalog
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.LocalDate

/** 종목 기술지표 계산(외부 일봉 조회 → [TechIndicators]). 조회 실패는 빈 지표(원본 동일). */
@Component
class TechIndicatorLoader(private val prices: PriceHistory) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(code: String, start: LocalDate, end: LocalDate): TechIndicators = try {
        val bars = prices.load(code, start, end)
        TechIndicators.of(bars.closeSeries())
    } catch (e: MarketDataNotLoaded) {
        throw e // 로컬 전용 소스의 미적재는 빈 지표(중립)로 삼키지 않는다
    } catch (e: Exception) {
        log.debug("기술지표 계산 실패 ({}): {}", code, e.message)
        TechIndicators()
    }
}

/**
 * 종목 지표 화면(원본 `metrics/stocks.compute_stocks`): 시총·거래대금 필터 → 상위 200종목 → 기술지표 → 멀티팩터 점수.
 * 생존편향: 현재 상장 종목 기준(원본 한계 그대로).
 */
@Component
class StockMetricsService(
    private val snapshots: SnapshotSource,
    private val common: MetricsCommon,
    private val tech: TechIndicatorLoader,
    private val catalog: SymbolCatalog,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compute(market: String, asOf: LocalDate): StocksOut {
        val mkts = MetricsCommon.mkts(market)
        val prev = common.prevBusinessDay(asOf)
        fun start(b: Int) = MetricsCommon.approxStart(asOf, b)
        val fund = snapshots.fundamentals(asOf, mkts).associateBy { it.symbol }
        val caps = snapshots.marketCap(asOf, mkts)
        val pc1 = snapshots.priceChange(prev, asOf, mkts).associateBy { it.symbol }
        val pc21 = snapshots.priceChange(start(21), asOf, mkts).associateBy { it.symbol }
        val pc63 = snapshots.priceChange(start(63), asOf, mkts).associateBy { it.symbol }
        val pc126 = snapshots.priceChange(start(126), asOf, mkts).associateBy { it.symbol }
        val pc121 = snapshots.priceChange(start(252), start(21), mkts).associateBy { it.symbol }
        if (caps.isEmpty()) {
            log.warn("시가총액 데이터 없음: as_of={} market={}", asOf, market)
            return StocksOut(asOf, 0, emptyList())
        }
        val fallbackMarket = if (market != "ALL") market else "KOSPI"
        fun pct(p: StockPeriod?) = p?.changePct?.let { it / 100.0 }
        val hasAvgVal = pc21.isNotEmpty()
        var candidates = caps.filter { c ->
            (c.marketCap ?: 0) >= MIN_MCAP && (!hasAvgVal || ((pc21[c.symbol]?.tradingValue?.toDouble() ?: 0.0) / 20.0) >= MIN_VALUE)
        }
        if (candidates.size > MAX_CANDIDATES) candidates = candidates.sortedByDescending { it.marketCap ?: Long.MIN_VALUE }.take(MAX_CANDIDATES)
        val histStart = MetricsCommon.approxStart(asOf, 270, buffer = 30)
        val techByCode = candidates.associate { it.symbol to tech.load(it.symbol, histStart, asOf) }
        val kept = candidates.filter { (techByCode.getValue(it.symbol).validBdays) >= 252 }

        val codes = kept.map { it.symbol }
        fun colOf(f: (String) -> Double?) = DoubleArray(codes.size) { f(codes[it]) ?: Double.NaN }
        val frame = FactorFrame(
            codes,
            mapOf(
                "PER" to colOf { fund[it]?.per }, "PBR" to colOf { fund[it]?.pbr }, "DIV" to colOf { fund[it]?.div },
                "mom_1m" to colOf { pct(pc21[it]) }, "mom_3m" to colOf { pct(pc63[it]) }, "mom_6m" to colOf { pct(pc126[it]) },
                "high_52w_ratio" to colOf { techByCode.getValue(it).high52wRatio },
                "vol_ann" to colOf { techByCode.getValue(it).volAnn }, "mdd_252" to colOf { techByCode.getValue(it).mdd252 },
            ),
        )
        val scores = FactorScores.compute(frame, null, "none")
        val seedNames = runCatching { catalog.nameMap() }.getOrDefault(emptyMap())
        val krxNames = krxNames(pc1.values, pc21.values, pc63.values, pc126.values)
        val items = kept.mapIndexed { i, c ->
            val t = techByCode.getValue(c.symbol)
            val p1 = pc1[c.symbol]
            val price = p1?.close ?: if (pc1.isEmpty() && c.marketCap != null && c.shares != null && c.shares != 0L) Math.rint(c.marketCap.toDouble() / c.shares) else null
            fun sc(k: String) = scores.getValue(k)[i].takeIf { !it.isNaN() }
            StockMetric(
                code = c.symbol, name = seedNames[c.symbol] ?: krxNames[c.symbol] ?: c.symbol,
                market = when {
                    c.market == null -> fallbackMarket
                    c.market.isBlank() -> market
                    else -> c.market.trim()
                },
                price = price?.let { Math.rint(it).toLong() }, changeRate = pct(p1), marketCap = c.marketCap,
                avgValue20 = if (hasAvgVal) pc21[c.symbol]?.tradingValue?.let { it / 20.0 } else null,
                per = fund[c.symbol]?.per, pbr = fund[c.symbol]?.pbr, div = fund[c.symbol]?.div,
                mom1m = pct(pc21[c.symbol]), mom3m = pct(pc63[c.symbol]), mom6m = pct(pc126[c.symbol]), mom121 = pct(pc121[c.symbol]),
                high52wRatio = t.high52wRatio, rsi14 = t.rsi14, volAnn = t.volAnn, mdd252 = t.mdd252,
                trendAligned = t.trendAligned, aboveSma200 = t.aboveSma200,
                score = sc("score"), scoreValue = sc("score_value"), scoreMomentum = sc("score_momentum"), scoreLowvol = sc("score_lowvol"),
            )
        }
        log.info("종목 지표 계산 완료: {}건", items.size)
        return StocksOut(asOf, items.size, items)
    }

    companion object {
        const val MAX_CANDIDATES = 200
        const val MIN_VALUE = 500_000_000.0
        const val MIN_MCAP = 100_000_000_000L
    }
}

/** 업종지수 지표 화면(원본 `metrics/sectors.compute_sectors`). 기준시장 지수(1001·2001)는 목록에서 뺀다. */
@Component
class SectorMetricsService(private val snapshots: SnapshotSource) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compute(market: String, asOf: LocalDate): SectorsOut {
        val histStart = MetricsCommon.approxStart(asOf, 252, buffer = 30)
        val refReturns = mapOf("KOSPI" to REF_KOSPI, "KOSDAQ" to REF_KOSDAQ).mapValues { (_, ticker) ->
            val bars = snapshots.indexOhlcv(histStart, asOf, ticker) // 실패는 전파(원본도 감싸지 않음) — 결측으로 바꿔 캐시하지 않는다
            if (bars != null && bars.size >= 126) {
                val c = bars.map { it.close ?: Double.NaN }
                com.quantfolio.quant.Num.safe(c.last() / c[c.size - 126] - 1)
            } else {
                null
            }
        }
        val items = mutableListOf<SectorMetric>()
        val tickerErrors = mutableListOf<DataSourceError>()
        for (mkt in MetricsCommon.mkts(market)) {
            val tickers = try {
                snapshots.indexTickers(asOf, mkt)
            } catch (e: DataSourceError) {
                log.warn("업종지수 목록 조회 실패로 시장 건너뜀 ({}): {}", mkt, e.message)
                tickerErrors += e
                continue
            }
            for (ticker in tickers) {
                if (ticker == REF_KOSPI || ticker == REF_KOSDAQ) continue
                try {
                    val bars = try {
                        snapshots.indexOhlcv(histStart, asOf, ticker)
                    } catch (e: DataSourceError) {
                        log.warn("업종지수 건너뜀 ({}): {}", ticker, e.message)
                        null
                    } ?: continue
                    if (bars.size < 21) continue
                    SectorMetric.of(snapshots.indexName(asOf, ticker), mkt, bars, refReturns[mkt])?.let { items += it }
                } catch (e: Exception) {
                    log.warn("섹터 계산 오류 (ticker={} {}): {}", ticker, mkt, e.message)
                }
            }
        }
        if (tickerErrors.isNotEmpty() && items.isEmpty()) throw DataSourceErrors.representative(tickerErrors)
        return SectorsOut(asOf, items)
    }

    private companion object {
        const val REF_KOSPI = "1001"
        const val REF_KOSDAQ = "2001"
    }
}

/** 시장 취약성(원본 `metrics/vulnerability.compute_vulnerability`): KOFIA 신용잔고·반대매매 500일. */
@Component
class VulnerabilityService(private val kofia: KofiaClient) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun compute(asOf: LocalDate, lookbackDays: Long = 500): VulnerabilityOut {
        val start = asOf.minusDays(lookbackDays)
        val credit = kofia.creditBalance(start, asOf).mapNotNull { it.total }
        val forced = kofia.marketFunds(start, asOf).mapNotNull { it.forcedSaleRatio }
        if (credit.isEmpty()) log.warn("취약성 평가: 신용융자 시계열 없음({}~{})", start, asOf)
        val r = VulnerabilityScoring.score(credit, forced)
        return VulnerabilityOut(asOf, r.score, r.exposureScale, r.signals, r.subscores)
    }
}
