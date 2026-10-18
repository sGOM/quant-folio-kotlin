package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.factor.FactorFrame
import com.quantfolio.factor.FactorScores
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.kofia.KofiaClient
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.ohlcv.PriceHistory
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.store.StockPeriod
import com.quantfolio.market.symbols.SymbolCatalog
import com.quantfolio.quant.Series
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

/** 종목 기술지표 계산(외부 일봉 조회 → [TechIndicators]). 조회 실패는 빈 지표(원본 동일). */
@Component
class TechIndicatorLoader(private val prices: PriceHistory) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun load(code: String, start: LocalDate, end: LocalDate): TechIndicators = try {
        val bars = prices.load(code, start, end)
        TechIndicators.of(Series(bars.map { it.time.atZone(java.time.ZoneOffset.UTC).toLocalDate() }, DoubleArray(bars.size) { bars[it].close }))
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
        val krxNames = listOf(pc1, pc21, pc63, pc126).flatMap { it.values }.filter { !it.name.isNullOrBlank() }
            .groupBy { it.symbol }.mapValues { it.value.first().name!!.trim() }
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
            val bars = runCatching { snapshots.indexOhlcv(histStart, asOf, ticker) }.getOrNull()
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

/**
 * 패닉 지표(원본 `metrics/panic.py`): 단일 시점 화면([compute])과 백테스트 오버레이용 롤링 시계열([series]).
 * 52주 신저가 비율(S9)과 시계열 브레드스는 원본처럼 파일 캐시(`{cacheDir}/panic_breadth_*.json`)에 누적한다.
 */
@Component
class PanicService(private val snapshots: SnapshotSource, private val common: MetricsCommon, props: QuantfolioProperties) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cacheDir: Path = Path.of(props.cacheDir)
    private val json = JsonMapper.builder().build()

    fun compute(market: String, asOf: LocalDate): PanicOut {
        val prev = common.prevBusinessDay(asOf)
        val histStart = MetricsCommon.approxStart(asOf, 90, buffer = 20)
        val items = mutableListOf<PanicMarket>()
        val unavailable = mutableListOf<String>()
        for (mkt in MetricsCommon.mkts(market)) {
            val ticker = INDEX_TICKER[mkt] ?: continue
            val item = try {
                oneMarket(mkt, ticker, asOf, prev, histStart)
            } catch (e: Exception) {
                log.warn("패닉 지표 계산 오류 (market={}): {}", mkt, e.message)
                null
            }
            if (item != null) items += item else unavailable += mkt
        }
        return PanicOut(asOf, items, unavailable, CAVEATS)
    }

    private fun oneMarket(mkt: String, ticker: String, asOf: LocalDate, prev: LocalDate, histStart: LocalDate): PanicMarket? {
        val bars = snapshots.indexOhlcv(histStart, asOf, ticker)
        if (bars == null || bars.size < 6) {
            log.warn("패닉 지표: 지수 OHLCV 부족 (market={})", mkt)
            return null
        }
        val sig = PanicScoring.indexSignals(bars)
        val pc = try {
            snapshots.priceChange(prev, asOf, listOf(mkt))
        } catch (e: Exception) {
            log.warn("패닉 지표: 브레드스 조회 예외 (market={}): {}", mkt, e.message)
            null
        }
        val breadth = PanicScoring.breadthSignals(pc?.map { it.changePct to it.volume?.toDouble() })
        sig.putAll(breadth)
        sig["nlr"] = try {
            newLowRatio(mkt, asOf)
        } catch (e: Exception) {
            log.warn("패닉 지표: S9 신저가 비율 계산 실패 (market={}): {}", mkt, e.message)
            null
        }
        val r = PanicScoring.scoreAndLabel(mkt, sig)
        val th = PanicScoring.TH.getValue(mkt)
        val tc = PanicScoring.TH_COMMON
        val w = PanicScoring.W
        val signals = listOf(
            PanicSignal("r1", "1일 급락", sig["r1"], r.sub["s1"], w.getValue("s1"), th.r1.first, th.r1.second),
            PanicSignal("r5", "5일 누적낙폭", sig["r5"], r.sub["s2"], w.getValue("s2"), th.r5.first, th.r5.second),
            PanicSignal("vr", "거래대금 폭증", sig["vr"], r.sub["s4"], w.getValue("s4"), tc.getValue("vr").first, tc.getValue("vr").second),
            PanicSignal("bdr", "하락종목 비율", sig["bdr"], r.sub["s5"], w.getValue("s5"), tc.getValue("bdr").first, tc.getValue("bdr").second),
            PanicSignal("nlr", "52주 신저가 비율", sig["nlr"], r.sub["s9"], w.getValue("s9"), tc.getValue("nlr").first, tc.getValue("nlr").second),
            PanicSignal("cr5", "급락종목 비율(-5%)", sig["cr5"], r.sub["s6_cr5"], w.getValue("s6"), tc.getValue("cr5").first, tc.getValue("cr5").second),
            PanicSignal("z", "변동성 z-score", sig["z"], r.sub["s7"], w.getValue("s7"), th.z.first, th.z.second),
            PanicSignal("clv", "종가위치(CLV)", sig["clv"], r.sub["s8"], w.getValue("s8"), tc.getValue("clv").first, tc.getValue("clv").second),
            PanicSignal("cr10", "폭락종목 비율(-10%)", sig["cr10"], r.sub["s6_cr10"], 0.0, tc.getValue("cr10").first, tc.getValue("cr10").second),
            PanicSignal("dd60", "60일 고점대비", sig["dd60"], null, 0.0, -0.03, -0.1),
        )
        return PanicMarket(
            mkt, r.score, r.level, r.gated, r.hardTrigger, r.priceSub, r.breadthSub, sig["dd60"],
            (breadth["universe"] ?: 0.0).toInt(), signals, CAVEATS,
        )
    }

    /** S9: 최근 252거래일 종가 캐시에서 오늘 종가가 기간 최저 이하인 종목 비율(60일 이상 관측 종목만). */
    private fun newLowRatio(mkt: String, asOf: LocalDate): Double? {
        val name = "${mkt}_closes"
        val cache = loadCache(name).toMutableMap()
        val today = asOf.format(YMD)
        if (today !in cache) {
            val snap = try {
                snapshots.marketOhlcvSnapshot(asOf, mkt)
            } catch (e: DataSourceError) {
                log.warn("S9 스냅샷 건너뜀 ({} {}): {}", mkt, today, e.message)
                null
            }
            if (snap.isNullOrEmpty()) {
                log.warn("패닉 지표: S9 스냅샷 조회 실패 (market={} {})", mkt, today)
                return null
            }
            cache[today] = snap.associate { it.symbol to (it.close ?: Double.NaN) }
            if (cache.size > S9_CACHE_KEEP) cache.keys.sorted().take(cache.size - S9_CACHE_KEEP).forEach { cache.remove(it) }
            saveCache(name, cache)
        }
        if (cache.size < S9_MIN_WINDOW) return null
        val dates = cache.keys.sorted().takeLast(S9_WINDOW)
        if (dates.last() != today) return null
        val todayCloses = cache.getValue(today)
        val symbols = dates.flatMap { cache.getValue(it).keys }.toSet()
        var eligible = 0
        var newLow = 0
        for (s in symbols) {
            val vals = dates.mapNotNull { cache.getValue(it)[s]?.takeIf { v -> !v.isNaN() } }
            if (vals.size < S9_MIN_WINDOW) continue
            eligible++
            val t = todayCloses[s]
            if (t != null && !t.isNaN() && t <= vals.min()) newLow++
        }
        if (eligible == 0) return null
        return newLow.toDouble() / eligible
    }

    /** 백테스트 패닉 오버레이용 일별 롤링 점수(S9 제외 — 원본 동일). 브레드스는 파일 캐시. */
    fun series(market: String, start: LocalDate, end: LocalDate): List<PanicDay> {
        val mkt = market.uppercase()
        val ticker = INDEX_TICKER[mkt] ?: return emptyList()
        val bars = snapshots.indexOhlcv(MetricsCommon.approxStart(start, 90, buffer = 30), end, ticker)
        if (bars == null || bars.size < 6) {
            log.warn("패닉 시계열: 지수 OHLCV 부족 (market={})", mkt)
            return emptyList()
        }
        val sorted = bars.sortedBy { it.date }.associateBy { it.date }.values.toList()
        val cache = loadBreadthCache(mkt).toMutableMap()
        var dirty = false
        val rows = mutableListOf<PanicDay>()
        for (i in 5 until sorted.size) {
            val d = sorted[i].date
            if (d.isBefore(start) || d.isAfter(end)) continue
            val window = sorted.subList(maxOf(0, i - SERIES_LOOKBACK + 1), i + 1)
            val sig = PanicScoring.indexSignals(window)
            if (sig.isEmpty()) continue
            val key = d.format(YMD)
            val breadth = cache[key] ?: run {
                val pc = try {
                    snapshots.priceChange(common.prevBusinessDay(d), d, listOf(mkt))
                } catch (e: Exception) {
                    log.warn("패닉 시계열: 브레드스 조회 실패 ({}): {}", key, e.message)
                    null
                }
                PanicScoring.breadthSignals(pc?.map { it.changePct to it.volume?.toDouble() }).also { b ->
                    if ((b["universe"] ?: 0.0) > 0) {
                        cache[key] = b
                        dirty = true
                        if (cache.size % 20 == 0) {
                            saveBreadthCache(mkt, cache)
                            dirty = false
                        }
                    }
                }
            }
            sig.putAll(breadth)
            val r = PanicScoring.scoreAndLabel(mkt, sig)
            rows += PanicDay(d, r.score, r.level, r.gated, r.hardTrigger, sig["close"], sig["low"], sig["dd60"])
        }
        if (dirty) saveBreadthCache(mkt, cache)
        return rows
    }

    private fun file(name: String): Path {
        Files.createDirectories(cacheDir)
        return cacheDir.resolve("panic_breadth_${name.uppercase()}.json")
    }

    private fun loadCache(name: String): Map<String, Map<String, Double>> = try {
        val f = file(name)
        if (!Files.exists(f)) emptyMap() else json.readValue<Map<String, Map<String, Double?>>>(Files.readString(f))
            .mapValues { (_, m) -> m.mapValues { it.value ?: Double.NaN } }
    } catch (e: Exception) {
        log.warn("패닉 브레드스 캐시 로드 실패 ({}): {}", name, e.message)
        emptyMap()
    }

    private fun saveCache(name: String, cache: Map<String, Map<String, Double>>) {
        runCatching { Files.writeString(file(name), json.writeValueAsString(cache.mapValues { (_, m) -> m.mapValues { if (it.value.isNaN()) null else it.value } })) }
            .onFailure { log.warn("패닉 브레드스 캐시 저장 실패 ({}): {}", name, it.message) }
    }

    private fun loadBreadthCache(mkt: String): Map<String, Map<String, Double?>> = try {
        val f = file(mkt)
        if (!Files.exists(f)) emptyMap() else json.readValue(Files.readString(f))
    } catch (e: Exception) {
        log.warn("패닉 브레드스 캐시 로드 실패 ({}): {}", mkt, e.message)
        emptyMap()
    }

    private fun saveBreadthCache(mkt: String, cache: Map<String, Map<String, Double?>>) {
        runCatching { Files.writeString(file(mkt), json.writeValueAsString(cache)) }
            .onFailure { log.warn("패닉 브레드스 캐시 저장 실패 ({}): {}", mkt, it.message) }
    }

    companion object {
        private val INDEX_TICKER = mapOf("KOSPI" to "1001", "KOSDAQ" to "2001")
        private const val S9_MIN_WINDOW = 60
        private const val S9_WINDOW = 252
        private const val S9_CACHE_KEEP = 260
        private const val SERIES_LOOKBACK = 65
        val CAVEATS = listOf(
            "일봉 종가 기준으로 판정합니다. 장중 급락 후 종가까지 회복(V자 반등)한 경우는 감지하지 못하며, 그날 장이 마감되어 종가가 확정된 뒤에야 판정됩니다.",
            "브레드스(하락·급락종목 비율)는 현재 상장 종목 기준으로 계산해 생존편향이 있습니다(과거 상장폐지 종목은 반영되지 않습니다).",
            "매매 신호가 아닙니다. 자본항복(capitulation)은 통계적으로 바닥 근처에서 발생하는 경향이 있어, 패닉 국면에 동반 매도하는 것은 오히려 최악의 타이밍일 수 있습니다.",
            "종목 단위 신저가 비율(브레드스 S9)은 매일 스냅샷을 누적하는 캐시 기반이라, 캐시가 충분히 쌓이기 전(약 60거래일 미만)에는 판정에서 제외됩니다. 또한 이 신호의 경계·패닉 임계값은 역사적 사례로 검증된 것이 아니라 잠정값입니다.",
        )
    }
}
