package com.quantfolio.market.official

import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockPeriod
import java.time.LocalDate

/** 지표·팩터 계산이 쓰는 시장 스냅샷 조회(테스트에서 교체 가능). 구현은 로컬 DB 전용 [LocalSnapshots]. */
interface SnapshotSource {
    fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily>
    fun marketCap(asOf: LocalDate, markets: List<String>): List<StockDaily>
    fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>): List<StockPeriod>
    fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>?
    fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>?

    /**
     * [day] 에 행이 있는 그 시장의 업종지수 (키, 그날 원문 이름), 키 오름차순. 기준지수(1001·2001)는 없다.
     * 폐지·이름 변경된 옛 키는 그날 행이 없어 빠지고, [day] 가 미적재면 빈 목록이다.
     */
    fun sectorIndexes(market: String, day: LocalDate): List<Pair<String, String>>
}
