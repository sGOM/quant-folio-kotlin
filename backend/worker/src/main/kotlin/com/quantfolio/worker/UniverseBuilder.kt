package com.quantfolio.worker

import com.quantfolio.common.MarketClock
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.market.ohlcv.IndexMembers
import com.quantfolio.market.source.DataSourceError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock

/** 야간 일봉 적재 대상(원본 `data/ingest.build_universe`) = 지수 구성종목 ∪ 등록 전략이 쓰는 종목. */
@Component
class UniverseBuilder(private val members: IndexMembers, private val strategies: StrategyRepository, private val clock: Clock) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun build(index: String = "KOSPI200"): List<String> {
        val codes = sortedSetOf<String>()
        try {
            // 원본 date.today() — 컨테이너 UTC 날짜(보존)
            codes += members.indexMembers(MarketClock.todayUtcLegacy(clock), index)
        } catch (e: DataSourceError) {
            log.error("{} 구성종목 조회 실패(그 부분만 제외): {}", index, e.message)
        }
        for (strategy in strategies.findAll()) {
            val cfg = strategy.config
            if (cfg["type"] == "rebalance") {
                (cfg["universe"] as? List<*>)?.forEach { if (it != null) codes += it.toString() }
            } else {
                (cfg["symbol"] as? String)?.takeIf { it.isNotEmpty() }?.let { codes += it }
            }
        }
        return codes.toList()
    }
}
