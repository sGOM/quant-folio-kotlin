package com.quantfolio.market.official

import com.quantfolio.domain.market.SectorMapSnapshotRepository
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 종목 → 업종. asOf 이하 최신 PIT 스냅샷(`sector_map_snapshots`, KIS 종목마스터 스냅샷이 채운다)만 쓴다.
 * 스냅샷이 없으면 원격으로 대체하지 않고 [MarketDataNotLoaded] — 업종 없이 돌리면 섹터 캡이 조용히 무력화된다.
 */
@Component
class LocalSectorMap(private val sectors: SectorMapSnapshotRepository) {
    fun sectorMap(asOf: LocalDate): Map<String, String> {
        val latest = sectors.latestDateOnOrBefore(asOf) ?: throw MarketDataNotLoaded("sector_map", asOf.toString())
        return sectors.findBySnapshotDate(latest).associate { it.symbol to it.sector }
    }
}
