package com.quantfolio.market.official

import com.quantfolio.common.MarketClock
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.krxopen.KrxOpenApi
import com.quantfolio.market.krxopen.OpenDailyRow
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * [daysDone] 는 이번 실행에서 확정까지 끝낸 날(DART 백필은 원격으로 받은 종목·연도·보고서 조합 수).
 * [failed] 는 사람이 봐야 할 실패 사유. 비어 있어도 [stoppedByBudget] 이면 아직 남은 일이 있다.
 */
data class IngestReport(val daysDone: Int, val calls: Int, val stoppedByBudget: Boolean, val failed: List<String>)

/**
 * KRX Open API → 로컬 테이블 적재(백필·일일). 하루 단위로 **전부 받은 뒤** 쓰고 원장은 마지막에 남긴다 —
 * 중간에 실패하면 아무것도 기록하지 않아 다음 실행이 그날을 처음부터 다시 한다. 날짜는 오름차순.
 * 원장 키: source `krx_open`, key `<서비스>|<yyyyMMdd>|<시장>` ([LocalTradingDays] 가 읽는 것과 같다).
 */
@Component
class OfficialIngest(
    private val api: KrxOpenApi,
    private val daily: StockDailyStore,
    private val indexes: IndexOhlcvStore,
    private val ticks: PriceTickStore,
    private val ledger: FetchLedger,
    private val dart: DartFinancials,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Run(val budget: Int) {
        var calls = 0
        var done = 0
        var stopped = false
        val failed = mutableListOf<String>()

        /** 수정주가를 다시 계산해야 하는 종목(오름차순 정렬 집합). 실행 끝에 한꺼번에 처리한다. */
        val dirty = sortedSetOf<String>()
        fun report() = IngestReport(done, calls, stopped, failed)
    }

    fun backfill(from: LocalDate, to: LocalDate, callBudget: Int): IngestReport {
        // 제공 시작일 앞의 평일은 0행이 "휴장"으로 영구 확정되므로 요청 자체를 거부한다.
        require(!from.isBefore(API_START)) { "KRX Open API 는 $API_START 부터 제공한다: from=$from" }
        return runDays(weekdays(from, to), callBudget) { d ->
            val loaded = daily.kindMarkets(d.withDayOfMonth(1), d.withDayOfMonth(d.lengthOfMonth()))
            MARKETS.filter { it !in loaded }
        }
    }

    /** [day] 와 그 앞 [CATCH_UP_DAYS]일 안의 미확정 평일을 마저 받는다(상한이 있어 무한히 거슬러 가지 않는다). */
    fun daily(day: LocalDate): IngestReport =
        runDays(weekdays(day.minusDays(CATCH_UP_DAYS), day), Int.MAX_VALUE) { d ->
            val loaded = daily.kindMarkets(d.minusDays(BASE_MAX_AGE_DAYS), d)
            MARKETS.filter { it !in loaded }
        }

    private fun weekdays(from: LocalDate, to: LocalDate): List<LocalDate> =
        generateSequence(from) { it.plusDays(1) }.takeWhile { !it.isAfter(to) }
            .filter { it.dayOfWeek != DayOfWeek.SATURDAY && it.dayOfWeek != DayOfWeek.SUNDAY }.toList()

    /** baseMarkets: 그날 기본정보를 받아야 하는 시장(그 달·최근 30일에 주권 종류가 아직 없는 시장). */
    private fun runDays(days: List<LocalDate>, budget: Int, baseMarkets: (LocalDate) -> List<String>): IngestReport {
        val run = Run(budget)
        try {
            for (day in days) {
                if (isDone(day)) continue
                val base = baseMarkets(day)
                if (run.calls.toLong() + DAY_CALLS + base.size > budget) {
                    run.stopped = true
                    break
                }
                try {
                    if (ingestDay(day, base, run)) run.done++
                } catch (e: DataSourceError) {
                    run.failed += "$day: ${e.message}"
                    log.warn("공식 적재 실패 {}: {}", day, e.message)
                    if (e is SourceAuthError || e is SourceQuotaError) break // 뒤 날짜도 같은 이유로 실패한다
                }
            }
        } finally {
            rebuildDirty(run)
        }
        return run.report()
    }

    private fun rebuildDirty(run: Run) {
        if (run.dirty.isEmpty()) return
        val gaps = marketGaps()
        for (symbol in run.dirty) rebuild(symbol, gaps)
    }

    private fun key(service: String, day: LocalDate, market: String) = "$service|${day.format(YMD)}|$market"

    private fun isDone(day: LocalDate) = LEDGER_KEYS.all { (service, market) -> ledger.get(SOURCE, key(service, day, market))?.final == true }

    /** @return 5개 호출이 모두 확정(휴장 확정 포함)이면 true. 예외가 나면 쓴 것이 없다. */
    private fun ingestDay(day: LocalDate, baseMarkets: List<String>, run: Run): Boolean {
        val stocks = MARKETS.associateWith { run.calls++; api.stockDaily(day, it) }
        val idx = INDEX_CODES.keys.associateWith { run.calls++; api.indexDaily(day, it) }
        for ((family, rows) in idx) {
            val expected = INDEX_CODES.getValue(family)
            // 행은 왔는데 기대 지수가 하나도 없으면 이름이 바뀐 것이다 — 조용히 0건 저장하면 그날이 휴장처럼 보인다.
            if (expected.isNotEmpty() && rows.isNotEmpty() && rows.none { it.name.trim() in expected }) {
                throw SourceSchemaError(SOURCE, "지수 계열 $family 응답에 기대 지수(${expected.keys})가 없다: ${rows.take(3).map { it.name }}")
            }
        }
        // 기대 지수 일부만 있으면(이름 변경 의심) 그 계열은 미확정으로 둔다.
        val partial = idx.filter { (family, rows) ->
            val expected = INDEX_CODES.getValue(family)
            expected.isNotEmpty() && rows.isNotEmpty() && !rows.map { it.name.trim() }.containsAll(expected.keys)
        }.keys
        val counts = LEDGER_KEYS.associateWith { (service, market) ->
            when (service) {
                in STOCK_SERVICES.values -> stocks.getValue(market).size
                else -> idx.getValue(market).size
            }
        }
        val holiday = counts.values.all { it == 0 }
        val oldEnough = !day.isAfter(MarketClock.todayKst(clock).minusDays(HOLIDAY_SETTLE_DAYS))

        if (!holiday) {
            val dailyRows = stocks.flatMap { (market, rows) -> rows.map { it.toStockDaily(market) } }
            daily.write(day, dailyRows)
            for ((family, rows) in idx) for (r in rows) {
                val code = INDEX_CODES.getValue(family)[r.name.trim()] ?: continue
                indexes.write(code, listOf(IndexBar(day, r.open, r.high, r.low, r.close)), r.name.trim())
            }
            appendPrices(day, dailyRows, run)
            if (dailyRows.isNotEmpty()) ingestBase(day, dailyRows, baseMarkets, run)
        }
        val finals = counts.mapValues { (k, n) ->
            (n > 0 && !(INDEX_SERVICES[k.second] == k.first && k.second in partial)) || (holiday && oldEnough)
        }
        for ((k, n) in counts) ledger.put(SOURCE, key(k.first, day, k.second), n, final = finals.getValue(k))
        return finals.values.all { it }
    }

    private fun OpenDailyRow.toStockDaily(market: String) = StockDaily(
        symbol = symbol, market = market, open = open, high = high, low = low, close = close, prevDiff = prevDiff,
        volume = volume, tradingValue = tradingValue, marketCap = marketCap, shares = shares,
    )

    /** 주권 종류. 시장별로 받고, 실패해도 그날 시세 적재는 유지하며(그 시장은 다음 처리일에 다시) 사유만 보고한다. */
    private fun ingestBase(day: LocalDate, dailyRows: List<StockDaily>, markets: List<String>, run: Run) {
        val present = dailyRows.map { it.symbol }.toSet()
        for (market in markets) {
            try {
                run.calls++
                val rows = api.stockBase(day, market)
                daily.write(day, rows.filter { it.symbol in present }.map { StockDaily(it.symbol, market, stockKind = it.stockKind) })
                ledger.put(SOURCE, key(BASE_SERVICES.getValue(market), day, market), rows.size, final = rows.isNotEmpty())
            } catch (e: DataSourceError) {
                run.failed += "$day $market 기본정보: ${e.message}"
                log.warn("기본정보 적재 실패 {} {}: {}", day, market, e.message)
            }
        }
    }

    /**
     * 그날 봉을 붙인다. 직전 저장 봉이 시장의 바로 앞 거래일일 때만 기준가(종가-전일대비)와 전일 저장 종가를 비교해
     * 어긋나면(액면분할 등 권리락) dirty 로 표시한다. 어긋남 = r(종가/기준가) 와 원주가비(종가/전일 저장 종가)의 상대차
     * > [ACTION_TOLERANCE]. 가격이 원 단위 정수라 정상일에는 두 비가 정확히 같고 부동소수 잡음은 약 1e-15 다 —
     * 1e-6 은 그 잡음 위, 100만원대 종목의 1원 차이까지 잡는 하한이다.
     * 직전 봉이 바로 앞 거래일이 아니거나(공백·정지) 이 날짜보다 늦은 봉이 이미 있으면 비교할 수 없으므로 무조건 dirty.
     */
    private fun appendPrices(day: LocalDate, rows: List<StockDaily>, run: Run) {
        val time = day.atStartOfDay().toInstant(ZoneOffset.UTC)
        val symbols = rows.map { it.symbol }
        val prev = ticks.lastBars(symbols, time)
        val later = ticks.symbolsWithBarsAfter(symbols, time)
        val prevTime = previousTradingDay(day)?.atStartOfDay()?.toInstant(ZoneOffset.UTC)
        val bars = mutableListOf<Pair<String, Bar>>()
        for (r in rows) {
            val o = r.open; val h = r.high; val l = r.low; val c = r.close; val v = r.volume
            if (o == null || h == null || l == null || c == null || v == null) continue
            val p = prev[r.symbol]
            val dirty = when {
                r.symbol in later -> true
                p == null -> false
                p.first != prevTime -> true
                else -> {
                    val base = r.prevDiff?.let { c - it }
                    base != null && base > 0 && p.second > 0 && abs((c / base) / (c / p.second) - 1) > ACTION_TOLERANCE
                }
            }
            if (dirty) run.dirty += r.symbol
            bars += r.symbol to Bar(time, o, h, l, c, v)
        }
        ticks.upsertMany(bars)
    }

    /** 원장상 day 의 바로 앞 거래일. 앞 평일이 원장에 없으면(실패·미적재) null — 공백이다. 0행으로 기록된 평일은 휴장으로 건너뛴다. */
    private fun previousTradingDay(day: LocalDate): LocalDate? {
        var d = day
        repeat(PREV_DAY_LOOKBACK) {
            d = d.minusDays(1)
            if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return@repeat
            val e = ledger.get(SOURCE, stockKey(d)) ?: return null
            if (e.rowCount > 0) return d
        }
        return null
    }

    private fun stockKey(day: LocalDate) = key(STOCK_SERVICES.getValue("KOSPI"), day, "KOSPI")

    /** 앞 거래일과의 사이에 원장에 없는 평일(못 받은 날)이 끼어 있는 거래일들 — 그 날의 전일대비로 공백 너머를 잇지 않는다. */
    private fun marketGaps(): Set<LocalDate> =
        daily.tradeDates().zipWithNext()
            .filter { (a, b) -> weekdays(a.plusDays(1), b.minusDays(1)).any { ledger.get(SOURCE, stockKey(it)) == null } }
            .map { it.second }.toSet()

    /** 저장된 원주가·전일대비로 이 종목의 수정주가 전체를 다시 만들어 price_ticks 를 바꾼다. */
    fun rebuildAdjusted(symbol: String) = rebuild(symbol, marketGaps())

    private fun rebuild(symbol: String, gaps: Set<LocalDate>) {
        val rows = daily.readSymbol(symbol)
        val input = rows.map { (d, r) -> if (d in gaps) r.copy(prevDiff = null) else r }
        ticks.replaceFrom(symbol, AdjustedBars.build(input, rows.map { it.first }))
    }

    /**
     * KOSPI 보통주 전 종목의 정기보고서 4종을 [fromYear] 부터 원격으로 받아 로컬에 쌓는다(이미 확정된 조합은 건너뜀).
     * 한도(020)·인증 오류면 즉시 멈추고 보고한다 — 다음 실행이 원장으로 이어받는다.
     */
    fun dartBackfill(fromYear: Int = 2015, callBudget: Int): IngestReport {
        val run = Run(callBudget)
        run.calls++
        val corpMap = try {
            dart.corpCodeMap()
        } catch (e: DataSourceError) {
            run.failed += "기업코드표: ${e.message}"
            return run.report()
        } ?: return run.also { it.failed += "OpenDART 미설정 — 기업코드표를 받지 못했다" }.report()
        val thisYear = MarketClock.todayKst(clock).year
        for (symbol in daily.symbolsWithKind("KOSPI", COMMON_STOCK)) {
            val corp = corpMap[symbol] ?: continue
            for (year in fromYear..thisYear) for (reprt in DART_REPORTS) {
                if (year == thisYear && reprt == ANNUAL_REPORT) continue // 사업보고서는 이듬해에 나온다
                try {
                    for (fs in DART_FS) {
                        val known = dart.accountsFinal(corp, year, reprt, fs)
                        if (!known) {
                            if (run.calls >= callBudget) return run.also { it.stopped = true }.report()
                            run.calls++
                            run.done++
                        }
                        // 연결재무가 없으면 별도로 넘어간다(DartFinancials.firstAvailable 과 같은 순서).
                        if (!dart.cachedAccounts(corp, year, reprt, fs, remote = !known).isNullOrEmpty()) break
                    }
                } catch (e: DataSourceError) {
                    run.failed += "$symbol $year $reprt: ${e.message}"
                    if (e is SourceQuotaError || e is SourceAuthError) return run.also { it.stopped = true }.report()
                }
            }
        }
        return run.report()
    }

    companion object {
        const val SOURCE = KrxOpenApi.SOURCE

        /** 보고서 종류 — 사업보고서·반기·1분기·3분기. */
        private val DART_REPORTS = listOf("11011", "11012", "11013", "11014")
        private const val ANNUAL_REPORT = "11011"
        private val DART_FS = listOf(DartFinancials.FS_CONSOLIDATED, DartFinancials.FS_SEPARATE)

        /** 실측 후 확정(Task 1 라이브 측정). 한 곳만 고친다. */
        const val COMMON_STOCK = "보통주"

        /** 지수 계열 → (지수명 → index_ohlcv 코드). 코드는 코드베이스 다른 곳이 쓰는 것과 같다. KRX 계열은 저장하지 않는다. */
        val INDEX_CODES: Map<String, Map<String, String>> = linkedMapOf(
            "KRX" to emptyMap(),
            "KOSPI" to mapOf("코스피" to "1001", "코스피 200" to "1028"),
            "KOSDAQ" to mapOf("코스닥" to "2001"),
        )

        private val MARKETS = listOf("KOSPI", "KOSDAQ")
        private val STOCK_SERVICES = mapOf("KOSPI" to "stk_bydd_trd", "KOSDAQ" to "ksq_bydd_trd")
        private val BASE_SERVICES = mapOf("KOSPI" to "stk_isu_base_info", "KOSDAQ" to "ksq_isu_base_info")
        private val INDEX_SERVICES = mapOf("KRX" to "krx_dd_trd", "KOSPI" to "kospi_dd_trd", "KOSDAQ" to "kosdaq_dd_trd")

        /** (서비스, 시장) 5개 — 한 날의 완료 기준. */
        private val LEDGER_KEYS: List<Pair<String, String>> =
            MARKETS.map { STOCK_SERVICES.getValue(it) to it } + INDEX_SERVICES.map { (family, svc) -> svc to family }

        private const val DAY_CALLS = 5
        private const val PREV_DAY_LOOKBACK = 10

        /** KRX Open API 일별 데이터 제공 시작일. */
        val API_START: LocalDate = LocalDate.of(2010, 1, 4)
        private const val CATCH_UP_DAYS = 14L
        private const val BASE_MAX_AGE_DAYS = 30L
        private const val HOLIDAY_SETTLE_DAYS = 7L
        private const val ACTION_TOLERANCE = 1e-6
        private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
