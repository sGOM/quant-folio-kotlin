package com.quantfolio.market.krx

import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.store.StockPeriodStore
import com.quantfolio.market.store.StoreCache
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Collections

/**
 * KRX 시장 스냅샷의 로컬 우선 조회(원본 `metrics/fetch.py` 의 pykrx 부분).
 * 확정된 과거 일자는 최초 1회만 KRX 를 타고, 이후엔 `stock_daily_snapshots`·`stock_period_stats`·`index_ohlcv` 에서 읽는다.
 *
 * 시장별 루프: 전 시장 실패면 대표 예외, 일부 실패면 성공분을 돌려주되 확정으로 굳히지 않는다(다음 호출에서 보완).
 * 이전 판은 실패를 삼켜 전 시장 실패에도 빈 결과를 돌려줬고, 백테스트가 빈 패널 위에서 '성공'했다(§44-1·§47).
 */
/** 지표·팩터 계산이 쓰는 시장 스냅샷 조회(테스트에서 교체 가능). 구현은 [KrxSnapshots]. */
interface SnapshotSource {
    fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily>
    fun marketCap(asOf: LocalDate, markets: List<String>): List<StockDaily>
    fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>): List<StockPeriod>
    fun netPurchases(start: LocalDate, end: LocalDate, markets: List<String>, investors: List<String> = KrxSnapshots.FLOW_INVESTORS): List<StockPeriod>
    fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>?
    fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>?
    fun indexTickers(day: LocalDate, market: String): List<String>
    fun indexName(day: LocalDate, ticker: String): String
}

@Component
@Primary // 로컬 전용 LocalSnapshots(@Qualifier("local")) 가 생겨도 기존 주입처는 이쪽을 받는다
class KrxSnapshots(
    private val api: KrxStockApi,
    private val cache: StoreCache,
    private val daily: StockDailyStore,
    private val periods: StockPeriodStore,
    private val indexes: IndexOhlcvStore,
    private val cooldowns: Cooldowns,
    private val calendar: MarketCalendar,
) : SnapshotSource {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 펀더멘털 프로세스 내 LRU — 같은 as-of 를 반복 백테스트할 때 DB 왕복까지 아낀다. 빈 결과는 캐시하지 않는다. */
    private val fundCache: MutableMap<Pair<LocalDate, List<String>>, List<StockDaily>> = Collections.synchronizedMap(
        object : LinkedHashMap<Pair<LocalDate, List<String>>, List<StockDaily>>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<LocalDate, List<String>>, List<StockDaily>>) = size > FUND_CACHE_MAX
        },
    )

    /** 전 종목 PER·PBR·DIV + market. PER ≤ 0 은 적자(정의 불가)로 보고 null. */
    override fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily> {
        val key = asOf to markets.sorted()
        fundCache[key]?.let { return it }
        var complete = true
        val result = cache.cachedFetch(
            "fundamentals", CacheKeys.of(asOf, markets),
            readLocal = { daily.read(asOf, markets) },
            fetchRemote = {
                perMarket(markets, "펀더멘털", ymd(asOf), { it.symbol }) { mkt ->
                    api.fundamentalByTicker(ymd(asOf), mkt).map {
                        StockDaily(it.symbol, market = mkt, per = it.per.takeIf { p -> p > 0 }, pbr = it.pbr, div = it.div)
                    }
                }.also { complete = it.second }.first
            },
            writeLocal = { daily.write(asOf, it) },
            isFinal = { cache.isFinalDate(asOf) && complete },
            columns = { listOf(it.per, it.pbr, it.div, it.market) },
        ).map { StockDaily(it.symbol, market = it.market, per = it.per, pbr = it.pbr, div = it.div) }
        if (result.isNotEmpty()) fundCache[key] = result
        return result
    }

    /** 전 종목 시가총액·거래량·거래대금·상장주식수 + market. */
    override fun marketCap(asOf: LocalDate, markets: List<String>): List<StockDaily> {
        var complete = true
        return cache.cachedFetch(
            "market_cap", CacheKeys.of(asOf, markets),
            readLocal = { daily.read(asOf, markets) },
            fetchRemote = {
                perMarket(markets, "시가총액", ymd(asOf), { it.symbol }) { mkt ->
                    api.marketCapByTicker(ymd(asOf), mkt).map {
                        StockDaily(it.symbol, market = mkt, marketCap = it.marketCap, volume = it.volume, tradingValue = it.tradingValue, shares = it.shares)
                    }
                }.also { complete = it.second }.first
            },
            writeLocal = { daily.write(asOf, it) },
            isFinal = { cache.isFinalDate(asOf) && complete },
            columns = { listOf(it.marketCap, it.volume, it.tradingValue, it.shares, it.market) },
        ).map { StockDaily(it.symbol, market = it.market, marketCap = it.marketCap, volume = it.volume, tradingValue = it.tradingValue, shares = it.shares) }
    }

    /** 기간 등락률(시가·종가·등락률·거래량·대금·종목명). 투자자군 키는 빈 문자열. */
    override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>): List<StockPeriod> {
        var complete = true
        return cache.cachedFetch(
            "price_change", CacheKeys.of(ymd(start), ymd(end), markets),
            readLocal = { periods.read(start, end, "", markets) },
            fetchRemote = {
                perMarket(markets, "가격변동", "${ymd(start)}~${ymd(end)}", { it.symbol }) { mkt ->
                    api.priceChangeByTicker(ymd(start), ymd(end), mkt).map {
                        StockPeriod(it.symbol, market = mkt, name = it.name, changePct = it.changePct, open = it.open.toDouble(),
                            close = it.close.toDouble(), volume = it.volume, tradingValue = it.tradingValue)
                    }
                }.also { complete = it.second }.first
            },
            writeLocal = { periods.write(start, end, "", it) },
            isFinal = { cache.isFinalDate(end) && complete },
            columns = { listOf(it.open, it.close, it.changePct, it.volume, it.tradingValue, it.name, it.market) },
        )
    }

    /** 투자자군별 순매수대금을 종목별로 합산(수급 팩터). 시장은 마지막으로 본 시장. */
    override fun netPurchases(start: LocalDate, end: LocalDate, markets: List<String>, investors: List<String>): List<StockPeriod> {
        val investorsKey = investors.sorted().joinToString(",")
        var complete = true
        return cache.cachedFetch(
            "net_purchases", CacheKeys.of(ymd(start), ymd(end), markets, investors),
            readLocal = { periods.read(start, end, investorsKey, markets).map { StockPeriod(it.symbol, market = it.market, netBuyValue = it.netBuyValue) } },
            fetchRemote = {
                val sums = linkedMapOf<String, Double>()
                val owner = mutableMapOf<String, String>()
                val errors = mutableListOf<DataSourceError>()
                var ok = 0
                loop@ for (mkt in markets) {
                    for (investor in investors) {
                        if (cooldowns.stopAggregate(KrxSession.SOURCE, errors, ok)) {
                            log.warn("순매수 집계 단락 ({} {}) — 남은 조회 건너뜀", mkt, investor)
                            break
                        }
                        try {
                            val rows = api.netPurchasesByTicker(ymd(start), ymd(end), mkt, investor)
                            ok++
                            for (r in rows) {
                                sums[r.symbol] = (sums[r.symbol] ?: 0.0) + r.netValue
                                owner[r.symbol] = mkt
                            }
                        } catch (e: Exception) {
                            errors += wrap(e, "순매수 조회 실패($mkt $investor ${ymd(start)}~${ymd(end)})")
                        }
                    }
                }
                if (errors.isNotEmpty() && ok == 0) throw DataSourceErrors.representative(errors)
                complete = errors.isEmpty() && ok == markets.size * investors.size
                sums.map { (symbol, v) -> StockPeriod(symbol, market = owner[symbol], netBuyValue = v) }
            },
            writeLocal = { periods.write(start, end, investorsKey, it) },
            isFinal = { cache.isFinalDate(end) && complete },
            columns = { listOf(it.netBuyValue, it.market) },
        )
    }

    /** 그날 한 시장의 전 종목 OHLCV. 없으면 null. */
    override fun marketOhlcvSnapshot(day: LocalDate, market: String): List<StockDaily>? {
        val rows = cache.cachedFetch(
            "market_ohlcv", CacheKeys.of(ymd(day), market),
            readLocal = { daily.read(day, listOf(market)) },
            fetchRemote = {
                try {
                    api.marketOhlcvByTicker(ymd(day), market).map {
                        StockDaily(it.symbol, market = market, open = it.open.toDouble(), high = it.high.toDouble(), low = it.low.toDouble(),
                            close = it.close.toDouble(), volume = it.volume, tradingValue = it.tradingValue, changePct = it.changePct)
                    }
                } catch (e: Exception) {
                    throw wrap(e, "전종목 OHLCV 스냅샷 조회 실패($market ${ymd(day)})")
                }
            },
            writeLocal = { daily.write(day, it) },
            isFinal = { cache.isFinalDate(day) },
            columns = { listOf(it.open, it.high, it.low, it.close, it.volume, it.tradingValue, it.changePct, it.market) },
        ).map {
            StockDaily(it.symbol, market = it.market, open = it.open, high = it.high, low = it.low, close = it.close,
                volume = it.volume, tradingValue = it.tradingValue, changePct = it.changePct)
        }
        return rows.ifEmpty { null }
    }

    /** 지수 일봉(범위 캐시). 없으면 null. */
    override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String): List<IndexBar>? {
        val bars = cache.cachedRange(
            ticker, start, end,
            readLocal = { indexes.read(ticker, start, end) },
            fetchRemote = {
                try {
                    api.indexOhlcvByDate(ymd(start), ymd(end), ticker).map {
                        IndexBar(it.date, it.open, it.high, it.low, it.close, it.volume, it.tradingValue)
                    }
                } catch (e: Exception) {
                    throw wrap(e, "지수 OHLCV 조회 실패($ticker ${ymd(start)}~${ymd(end)})")
                }
            },
            writeLocal = { indexes.write(ticker, it, null) },
            readCoverage = { indexes.readCoverage(ticker) },
            mergeCoverage = { from, to, rows -> mergeCoverageGuarded(ticker, from, to, rows) },
        )
        return bars.ifEmpty { null }
    }

    override fun indexTickers(day: LocalDate, market: String): List<String> = try {
        api.indexTickers(ymd(day), market)
    } catch (e: Exception) {
        throw wrap(e, "업종지수 목록 조회 실패($market ${ymd(day)})")
    }

    /** 이름 조회 실패는 티커를 그대로 쓴다(표시용). */
    override fun indexName(day: LocalDate, ticker: String): String =
        runCatching { api.indexTickerName(ticker).trim().ifEmpty { ticker } }.getOrDefault(ticker)

    /**
     * 받은 행이 예상 거래일의 절반에 못 미치면 커버리지로 기록하지 않는다 — 소스가 앞부분만 준 부분 응답일 수 있고,
     * 기록하면 빠진 구간이 영구히 로컬 히트로 굳는다.
     */
    private fun mergeCoverageGuarded(ticker: String, from: LocalDate, to: LocalDate, rows: Int) {
        val expected = MarketCalendar.estimatedTradingDays(from, to)
        if (expected >= COVERAGE_MIN_EXPECTED_DAYS && rows < expected * COVERAGE_ROW_RATIO) {
            log.warn("지수 OHLCV 부분 응답 의심으로 커버리지 기록 건너뜀 ({} {}~{}): 수신 {}행, 예상 {}행 이상", ticker, from, to, rows, expected.toInt())
            return
        }
        indexes.mergeCoverage(ticker, from, to)
    }

    /**
     * 시장별 조회 골격. (합친 행, 전 시장 성공 여부). 같은 종목이 두 시장에 실리면(시장전환일) 마지막 값만, 그 위치에(pandas
     * `duplicated(keep="last")`, 원본 §55) — 저장소 쓰기만 정리하면 최초 응답(중복)과 이후 로컬 히트(정리됨)의 모양이 달라진다.
     */
    private fun <T : Any> perMarket(markets: List<String>, what: String, `when`: String, symbolOf: (T) -> String, fetchOne: (String) -> List<T>): Pair<List<T>, Boolean> {
        val rows = mutableListOf<T>()
        val errors = mutableListOf<DataSourceError>()
        var ok = 0
        for (mkt in markets) {
            if (cooldowns.stopAggregate(KrxSession.SOURCE, errors, ok)) {
                log.warn("{} 집계 단락 ({} {}) — 남은 시장 건너뜀", what, mkt, `when`)
                break
            }
            try {
                rows += fetchOne(mkt)
                ok++
            } catch (e: Exception) {
                errors += wrap(e, "$what 조회 실패($mkt $`when`)")
            }
        }
        if (errors.isNotEmpty() && ok == 0) throw DataSourceErrors.representative(errors)
        val lastAt = rows.withIndex().associate { (i, r) -> symbolOf(r) to i }
        return rows.filterIndexed { i, r -> lastAt[symbolOf(r)] == i } to (errors.isEmpty() && ok == markets.size)
    }

    /** pykrx 경로 예외 → Unavailable(원본: pykrx 는 임의 예외를 던진다). 이미 분류된 예외는 그대로. 쿨다운 기록. */
    private fun wrap(e: Exception, message: String): DataSourceError {
        val wrapped = e as? DataSourceError ?: SourceUnavailableError(KrxSession.SOURCE, "$message: ${e.message}")
        log.warn("{}", wrapped.message)
        cooldowns.noteFailure(wrapped)
        return wrapped
    }

    private fun ymd(d: LocalDate) = d.format(YMD)

    companion object {
        val FLOW_INVESTORS = listOf("외국인", "기관합계")
        private const val FUND_CACHE_MAX = 64
        private const val COVERAGE_MIN_EXPECTED_DAYS = 10
        private const val COVERAGE_ROW_RATIO = 0.5
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
