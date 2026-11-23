package com.quantfolio.market.official

import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.market.store.StockPeriod
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 로컬 DB 만 읽는 [SnapshotSource]. 원격(KRX MDC)으로 절대 내려가지 않고, 비면 [MarketDataNotLoaded].
 * 거래일이 아닌 날(휴장)은 빈 결과·null.
 */
@Component
@Qualifier("local")
class LocalSnapshots(
    private val daily: StockDailyStore,
    private val indexes: IndexOhlcvStore,
    private val days: LocalTradingDays,
    private val valuation: Valuation,
) : SnapshotSource {
    /** 시장별 당일 종목을 [requireDay] 로 확인(적재 공백은 미적재)한 뒤 [Valuation] 으로 per·pbr·div 를 직접 계산. */
    override fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily> {
        if (asOf !in days.expected(asOf, asOf)) return emptyList()
        val symbols = markets.flatMap { requireDay(asOf, it) }.map { it.symbol }
        return valuation.compute(asOf, symbols)
    }

    override fun marketCap(asOf: LocalDate, markets: List<String>): List<StockDaily> {
        if (asOf !in days.expected(asOf, asOf)) return emptyList()
        return markets.flatMap { requireDay(asOf, it) }.map {
            StockDaily(it.symbol, market = it.market, marketCap = it.marketCap, volume = it.volume, tradingValue = it.tradingValue, shares = it.shares)
        }
    }

    /**
     * 기간 등락률. KRX 는 수정주가(adjStkPrc=2) 기준이라 원주가 비율이 아니라 일별 전일대비 수익률
     * r = close / (close - prevDiff) 를 (첫 거래일, 마지막 거래일] 에서 곱한다. prevDiff 가 null 이거나 기준가 <= 0 이면 그날은
     * 직전 거래일 원주가 비율로 폴백, 그것도 못 구하면 등락률 null. 시가=첫 거래일 종가, 종가=마지막 거래일 종가, 거래량·대금은 기간 합.
     * 첫날 행이 있고 마지막 날 행이 없는 종목(기간 중 상폐)은 KRX 처럼 종가 0·-100%·거래량/대금 0. 종목명은 로컬에 없어 null.
     */
    override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>): List<StockPeriod> {
        val expected = days.expected(start, end)
        if (expected.isEmpty()) return emptyList()
        val perDay = expected.associateWith { d -> markets.flatMap { daily.read(d, listOf(it)) } }
        val missing = expected.filter { d -> markets.any { m -> perDay.getValue(d).none { it.market == m } } }
        if (missing.isNotEmpty()) throw MarketDataNotLoaded("stock_daily", markets.joinToString(","), contiguousRanges(missing, expected))
        val bySymbol = linkedMapOf<String, MutableMap<LocalDate, StockDaily>>()
        for (d in expected) for (r in perDay.getValue(d)) bySymbol.getOrPut(r.symbol) { linkedMapOf() }[d] = r
        val firstDay = expected.first()
        val lastDay = expected.last()
        return bySymbol.mapNotNull { (symbol, rows) ->
            val f = rows[firstDay] ?: return@mapNotNull null
            val open = f.close ?: return@mapNotNull null
            val l = rows[lastDay]
            val volume = rows.values.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.sum()
            val value = rows.values.mapNotNull { it.tradingValue }.takeIf { it.isNotEmpty() }?.sum()
            if (l == null) {
                return@mapNotNull StockPeriod(symbol, market = f.market, open = open, close = 0.0, changePct = -100.0, volume = 0, tradingValue = 0)
            }
            val close = l.close ?: return@mapNotNull null
            StockPeriod(symbol, market = l.market, open = open, close = close, changePct = compounded(rows, expected), volume = volume, tradingValue = value)
        }
    }

    private fun compounded(rows: Map<LocalDate, StockDaily>, expected: List<LocalDate>): Double? {
        var product = 1.0
        for (i in 1 until expected.size) {
            val cur = rows[expected[i]] ?: return null
            val close = cur.close ?: return null
            val base = cur.prevDiff?.let { close - it }?.takeIf { it > 0 }
                ?: rows[expected[i - 1]]?.close?.takeIf { it > 0 }
                ?: return null
            product *= close / base
        }
        return (product - 1) * 100
    }

    override fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>? {
        if (day !in days.expected(day, day)) return null
        return requireDay(day, market).map {
            StockDaily(it.symbol, market = it.market, open = it.open, high = it.high, low = it.low, close = it.close,
                volume = it.volume, tradingValue = it.tradingValue, changePct = it.changePct)
        }
    }

    /**
     * 업종지수 키(`KOSPI:`·`KOSDAQ:` 접두어)는 결측 판정이 [max(start, 키의 첫 행), end] — 첫 행 이전은 "그때 없던 지수"다.
     * 기준지수 1001·1028·2001 은 전략 경로(벤치마크·국면·잔차모멘텀)가 쓰므로 전 구간을 본다(짧은 시계열을 조용히 주지 않는다).
     * 키에 행이 하나도 없으면 전 구간 미적재.
     */
    override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? {
        val first = if (SECTOR_PREFIXES.any { ticker.startsWith(it) }) indexes.span(ticker)?.first else null
        val bars = indexes.read(ticker, start, end)
        val expected = days.expected(maxOf(start, first ?: start), end)
        val missing = expected.toSet() - bars.map { it.date }.toSet()
        if (missing.isNotEmpty()) throw MarketDataNotLoaded("index_ohlcv", ticker, contiguousRanges(missing, expected))
        return bars.ifEmpty { null }
    }

    /** `index_ohlcv` 의 `<시장>:` 접두어 키(업종지수, `OfficialIngest.indexKey`). 기준지수 1001·2001(·1028)은 접두어가 없어 빠진다. */
    override fun sectorIndexes(market: String): List<Pair<String, String>> =
        indexes.keys("$market:").map { (key, name) -> key to (name ?: key.removePrefix("$market:")) }

    /** 거래일 하루·한 시장의 행. 없으면 미적재. */
    private fun requireDay(day: LocalDate, market: String): List<StockDaily> =
        daily.read(day, listOf(market)).ifEmpty { throw MarketDataNotLoaded("stock_daily", market, listOf(day to day)) }

    private companion object {
        /** `OfficialIngest.indexKey` 의 업종지수 접두어. */
        val SECTOR_PREFIXES = listOf("KOSPI:", "KOSDAQ:")
    }
}
