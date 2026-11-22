package com.quantfolio.engine

import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.official.LocalSectorMap
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.metrics.FactorInputs
import com.quantfolio.metrics.TechIndicatorLoader
import com.quantfolio.metrics.UniverseScores
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 실매매 엔진이 쓰는 시장데이터는 로컬 DB 뿐이다(원격 KRX MDC·Naver 로 내려가지 않는다). 비어 있으면 [MarketDataNotLoaded] —
 * 러너가 이를 받아 주문 없이 멈추고 critical 로 알린다. 지수·시총·업종·일봉·점수가 모두 여기를 거친다.
 */
@Component
class LocalMarketData(
    @Qualifier("local") val snapshots: SnapshotSource,
    val coverage: LocalOhlcvCoverage,
    private val ticks: PriceTickStore,
    private val sectorMaps: LocalSectorMap,
    dart: DartFinancials,
) {
    /** 로컬 일봉. 구간의 거래일이 빠졌으면 미적재(외부 보충 없음). */
    private val prices = PriceHistory { symbol, start, end ->
        coverage.require(symbol, start, end)
        ticks.bars(symbol, start.atStartOfDay().toInstant(ZoneOffset.UTC), end.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC))
    }

    private val inputs = object : FactorInputs {
        override fun qualityMetrics(codes: List<String>, asOf: LocalDate, useTtm: Boolean) = dart.metricsBySymbol(codes, asOf, useTtm, remote = false)
        override fun peadSue(codes: List<String>, asOf: LocalDate, lookbackQ: Int): Map<String, Double> = throw MarketDataNotLoaded("unsupported_in_phase1", "pead")
        override fun marketCaps(asOf: LocalDate): Map<String, Long> =
            snapshots.marketCap(asOf, listOf("KOSPI", "KOSDAQ")).mapNotNull { r -> r.marketCap?.let { r.symbol to it } }.toMap()
        override fun sectorMap(asOf: LocalDate) = sectorMaps.sectorMap(asOf)
        override fun dartEnabled() = dart.enabled()
    }

    /** 점수 계산(로컬 소스 전용). */
    val scores = UniverseScores(snapshots, TechIndicatorLoader(prices), inputs, prices)

    /** 종목 → 업종(로컬 PIT 스냅샷). 없으면 [MarketDataNotLoaded]. */
    fun sectorMap(asOf: LocalDate): Map<String, String> = sectorMaps.sectorMap(asOf)
}
