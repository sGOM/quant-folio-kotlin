package com.quantfolio.market.official

import com.quantfolio.market.krx.SnapshotSource
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
 * 거래일이 아닌 날(휴장)은 KrxSnapshots 처럼 빈 결과·null.
 */
@Component
@Qualifier("local")
class LocalSnapshots(
    private val daily: StockDailyStore,
    private val indexes: IndexOhlcvStore,
    private val days: LocalTradingDays,
) : SnapshotSource {
    // 이후 Task(Valuation)가 대체한다.
    override fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily> =
        throw MarketDataNotLoaded("valuation_pending", "fundamentals|${markets.joinToString(",")}", listOf(asOf to asOf))

    override fun marketCap(asOf: LocalDate, markets: List<String>): List<StockDaily> {
        if (asOf !in days.expected(asOf, asOf)) return emptyList()
        return markets.flatMap { requireDay(asOf, it) }.map {
            StockDaily(it.symbol, market = it.market, marketCap = it.marketCap, volume = it.volume, tradingValue = it.tradingValue, shares = it.shares)
        }
    }

    /**
     * 기간 등락률. 시가는 기간 첫 거래일 종가(KRX 기간 등락률 정의), 종가는 마지막 거래일 종가, 거래량·대금은 기간 합.
     * 종목명은 로컬에 없어 null. 첫·마지막 거래일 양쪽에 행이 있는 종목만.
     */
    override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>): List<StockPeriod> {
        val expected = days.expected(start, end)
        if (expected.isEmpty()) return emptyList()
        val perDay = expected.associateWith { d -> markets.flatMap { daily.read(d, listOf(it)) } }
        val missing = expected.filter { d -> markets.any { m -> perDay.getValue(d).none { it.market == m } } }
        if (missing.isNotEmpty()) throw MarketDataNotLoaded("stock_daily", markets.joinToString(","), contiguousRanges(missing, expected))
        val first = perDay.getValue(expected.first()).associateBy { it.symbol }
        return perDay.getValue(expected.last()).mapNotNull { l ->
            val open = first[l.symbol]?.close ?: return@mapNotNull null
            val close = l.close ?: return@mapNotNull null
            val rows = perDay.values.flatMap { r -> r.filter { it.symbol == l.symbol } }
            StockPeriod(
                l.symbol, market = l.market, open = open, close = close,
                changePct = if (open > 0) (close / open - 1) * 100 else null,
                volume = rows.mapNotNull { it.volume }.takeIf { it.isNotEmpty() }?.sum(),
                tradingValue = rows.mapNotNull { it.tradingValue }.takeIf { it.isNotEmpty() }?.sum(),
            )
        }
    }

    override fun netPurchases(start: LocalDate, end: LocalDate, markets: List<String>, investors: List<String>): List<StockPeriod> =
        throw MarketDataNotLoaded("unsupported_in_phase1", "net_purchases")

    override fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>? {
        if (day !in days.expected(day, day)) return null
        return requireDay(day, market).map {
            StockDaily(it.symbol, market = it.market, open = it.open, high = it.high, low = it.low, close = it.close,
                volume = it.volume, tradingValue = it.tradingValue, changePct = it.changePct)
        }
    }

    override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? {
        val bars = indexes.read(ticker, start, end)
        val expected = days.expected(start, end)
        val missing = expected.toSet() - bars.map { it.date }.toSet()
        if (missing.isNotEmpty()) throw MarketDataNotLoaded("index_ohlcv", ticker, contiguousRanges(missing, expected))
        return bars.ifEmpty { null }
    }

    override fun indexTickers(day: LocalDate, market: String): List<String> =
        throw MarketDataNotLoaded("unsupported_in_phase1", "index_tickers|$market")

    override fun indexName(day: LocalDate, ticker: String): String =
        throw MarketDataNotLoaded("unsupported_in_phase1", "index_name|$ticker")

    /** 거래일 하루·한 시장의 행. 없으면 미적재. */
    private fun requireDay(day: LocalDate, market: String): List<StockDaily> =
        daily.read(day, listOf(market)).ifEmpty { throw MarketDataNotLoaded("stock_daily", market, listOf(day to day)) }
}
