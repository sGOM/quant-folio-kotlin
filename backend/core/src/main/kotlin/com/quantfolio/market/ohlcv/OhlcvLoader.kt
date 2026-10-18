package com.quantfolio.market.ohlcv

import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset

/** 종목 일봉 조회(외부 소스). 테스트에서 교체 가능. */
fun interface PriceHistory {
    fun load(symbol: String, start: LocalDate, end: LocalDate): List<Bar>
}

/**
 * 종목 일봉 적재(원본 `data/loader.py`). price_ticks 가 백테스트·차트의 단일 출처이고, 외부는 부족분만 채운다.
 */
@Component
class OhlcvLoader(private val naver: NaverDaily, private val ticks: PriceTickStore, private val clock: Clock) : PriceHistory {
    private val log = LoggerFactory.getLogger(javaClass)

    /** FDR 경로 → 비면 pykrx 경로. 둘 다 비면 예외. 각 경로의 예외는 경고 후 다음으로(원본 동일). */
    override fun load(symbol: String, start: LocalDate, end: LocalDate): List<Bar> {
        val fdr = runCatching { naver.fdr(symbol, start, end) }
            .onFailure { log.warn("FinanceDataReader 실패({}): {}", symbol, it.message) }.getOrNull()
        val bars = if (fdr.isNullOrEmpty()) {
            runCatching { naver.pykrx(symbol, start, end, LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)) }
                .onFailure { log.warn("pykrx 실패({}): {}", symbol, it.message) }.getOrNull()
        } else {
            fdr
        }
        if (bars.isNullOrEmpty()) throw IllegalStateException("$symbol 시세를 가져오지 못했습니다 ($start~$end).")
        return bars
    }

    /**
     * [start, end] 를 덮도록 부족분만 보충한다. 데이터가 없으면 전체, 있으면 머리(더 과거로 확장된 구간)·꼬리(새 거래일)만.
     * 주말·연휴로 며칠 비는 건 결측이 아니므로 5일 유예를 둔다. 조회 실패는 경고만 — 호출자는 있는 만큼 쓴다.
     */
    fun ensureCoverage(symbol: String, start: LocalDate, end: LocalDate) {
        val span = ticks.span(symbol, utc(start), utc(end))
        val gaps = mutableListOf<Pair<LocalDate, LocalDate>>()
        if (span == null) {
            gaps += start to end
        } else {
            val first = span.first.atZone(ZoneOffset.UTC).toLocalDate()
            val last = span.second.atZone(ZoneOffset.UTC).toLocalDate()
            if (first.isAfter(start.plusDays(GRACE_DAYS))) gaps += start to first
            if (last.isBefore(end.minusDays(GRACE_DAYS))) gaps += last to end
        }
        for ((from, to) in gaps) {
            val bars = try {
                load(symbol, from, to)
            } catch (e: Exception) {
                log.warn("{} 구간 보충 실패({}~{}): {}", symbol, from, to, e.message)
                continue
            }
            ticks.upsert(symbol, bars)
        }
    }

    private fun utc(d: LocalDate) = d.atStartOfDay().toInstant(ZoneOffset.UTC)

    private companion object {
        const val GRACE_DAYS = 5L
    }
}
