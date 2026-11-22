package com.quantfolio.backtest

import com.quantfolio.common.MarketClock
import com.quantfolio.factor.FactorScores
import com.quantfolio.factor.ResidualMomentum
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.krx.KrxIndexService
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.official.DividendFeed
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.McapUniverse
import com.quantfolio.market.ohlcv.OhlcvLoader
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.IndexConstituentStore
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.metrics.PanicService
import com.quantfolio.metrics.UniverseScores
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDate

/** 입력·설정이 백테스트에 맞지 않음 — HTTP 422(원본 ValueError·HTTPException 422). */
class BacktestInputError(message: String) : RuntimeException(message)

/** 백테스트 실행 중 예상 못 한 실패 — HTTP 500 `백테스트 실패: {원인}`(원본 그대로 원인 문구 노출). */
class BacktestFailed(cause: Throwable) : RuntimeException("백테스트 실패: ${cause.message}", cause)

/**
 * 전략 백테스트 실행(원본 `routes/backtests.py` 의 데이터 준비 + 엔진 호출). 시세는 price_ticks 가 단일 출처이고
 * 모자라면 외부에서 보충한다. 기간 경계는 KST(시작 00:00, 끝 23:59:59).
 */
@Component
class BacktestRunner(
    private val loader: OhlcvLoader,
    private val ticks: PriceTickStore,
    private val snapshots: SnapshotSource,
    private val dart: DartFinancials,
    private val krx: KrxIndexService,
    private val mcap: McapUniverse,
    private val constituents: IndexConstituentStore,
    private val panic: PanicService,
    private val scores: UniverseScores,
    private val dividendFeed: DividendFeed,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(config: Map<String, Any?>, start: LocalDate, end: LocalDate): Map<String, Any?> =
        if (config["type"] == "rebalance") rebalance(config, start, end) else single(config, start, end)

    private fun bars(symbol: String, start: LocalDate, end: LocalDate): List<Bar> {
        loader.ensureCoverage(symbol, start, end)
        return ticks.bars(symbol, MarketClock.kstStart(start), MarketClock.kstEnd(end))
    }

    private fun series(bars: List<Bar>, f: (Bar) -> Double) =
        Series(bars.map { it.date }, DoubleArray(bars.size) { f(bars[it]) })

    private fun single(config: Map<String, Any?>, start: LocalDate, end: LocalDate): Map<String, Any?> {
        val b = bars(config["symbol"].toString(), start, end)
        if (b.isEmpty()) throw BacktestInputError("해당 기간 시세가 없습니다.")
        val close = series(b) { it.close }
        val data = if (Signals.requiresOhlc(config)) {
            PriceData.ohlcv(series(b) { it.open }, series(b) { it.high }, series(b) { it.low }, close, series(b) { it.volume.toDouble() })
        } else {
            PriceData.close(close)
        }
        return engine { SingleBacktest.run(data, config) }
    }

    private fun <T> engine(block: () -> T): T = try {
        block()
    } catch (e: IllegalArgumentException) {
        throw BacktestInputError(e.message ?: "")
    } catch (e: BacktestInputError) {
        throw e
    } catch (e: DataSourceError) {
        throw BacktestFailed(e)
    } catch (e: Exception) {
        log.error("백테스트 실행 오류", e)
        throw BacktestFailed(e)
    }

    private fun rebalance(config: Map<String, Any?>, start: LocalDate, end: LocalDate): Map<String, Any?> {
        val warmup = start.minusDays(500)
        val selection = Cfg.map(config, "selection")
        @Suppress("UNCHECKED_CAST")
        val rule = selection["universe_rule"] as? Map<String, Any?>
        val (pitUnion, pool) = pitPool(rule, warmup, end)
        @Suppress("UNCHECKED_CAST")
        val universe = pitUnion ?: (config["universe"] as? List<String>).orEmpty()
        if (universe.isEmpty()) throw BacktestInputError("universe 가 비어 있습니다.")

        val closes = linkedMapOf<String, Series>()
        val volumes = linkedMapOf<String, Series>()
        val wantVolume = Cfg.truthy(config["adv_participation_cap"])
        for (sym in universe) {
            val b = bars(sym, warmup, end)
            if (b.isEmpty()) continue
            closes[sym] = series(b) { it.close }
            if (wantVolume) volumes[sym] = series(b) { it.volume.toDouble() }
        }
        if (closes.isEmpty()) throw BacktestInputError("universe 종목의 시세를 확보하지 못했습니다.")
        val panel = Panel.fromSeries(closes)
        val volume = if (wantVolume) {
            if (volumes.isEmpty()) log.warn("ADV 캡 설정됨이나 거래량 데이터를 확보하지 못했다(미적용).")
            volumes.takeIf { it.isNotEmpty() }?.let { Panel.fromSeries(it) }
        } else {
            null
        }

        val rf = Cfg.map(config, "regime_filter")
        val regime = if (Cfg.truthy(rf["enabled"])) {
            indexClose(REGIME_TICKER[Cfg.get(rf, "index", "KOSPI")] ?: "1001", warmup, end, "레짐 필터 기준지수 적재 실패(오버레이 미적용)")
        } else {
            null
        }
        val benchmark = indexClose(BENCHMARK_TICKER[Cfg.get(config, "benchmark_index", "KOSPI200")] ?: "1028", warmup, end, "벤치마크 지수 적재 실패(상대지표 미산출)")
        val provider = if (Cfg.get(selection, "method", "momentum") == "score") fundamentalsProvider(config, panel, benchmark) else null
        val po = Cfg.map(config, "panic_overlay")
        val panicDays = if (Cfg.truthy(po["enabled"])) {
            try {
                panic.series(Cfg.get(po, "market", "KOSPI").toString(), warmup, end).ifEmpty { null }
            } catch (e: Exception) {
                log.warn("패닉 오버레이 시계열 적재 실패(오버레이 미적용): {}", e.message)
                null
            }
        } else {
            null
        }

        val dividends = withDividends(closes.keys.toList(), start, end)
        val result = LinkedHashMap(
            engine {
                PortfolioBacktest.run(
                    closePanel = panel, config = withSectorMap(config) + dividends.config, simStart = start, simEnd = end, fundamentals = provider,
                    regime = regime, pool = pool, benchmark = benchmark, panic = panicDays, volume = volume,
                )
            },
        )
        factorCoverageWarnings(config, universe, end).takeIf { it.isNotEmpty() }?.let { result["factor_warnings"] = it }
        dividends.warnings.takeIf { it.isNotEmpty() }?.let { result["dividend_warnings"] = it }
        result["assumptions"] = dividends.assumptions + "벤치마크는 배당 제외"
        result["universe_fingerprint"] = universeFingerprint(universe, rule)
        return result
    }


    private class DividendSetup(val config: Map<String, Any?>, val warnings: List<String>, val assumptions: List<String>)

    /**
     * `_dividends` 를 config 에 싣는다(`withSectorMap` 과 같은 방식). 배당 전체 적재 전이면 가격수익률만 낸다 —
     * 조용히 넘어가지 않도록 가정 문구에 남긴다. 조회 실패는 백테스트를 막지 않고 경고로 남긴다.
     */
    private fun withDividends(symbols: List<String>, start: LocalDate, end: LocalDate): DividendSetup {
        if (!dividendFeed.ingested()) return DividendSetup(emptyMap(), emptyList(), listOf("배당 미반영: 배당 데이터 미적재(가격수익률)"))
        val inputs = try {
            dividendFeed.forBacktest(symbols, start, end)
        } catch (e: Exception) {
            log.warn("배당 조회 실패(배당 미반영): {}", e.message)
            return DividendSetup(emptyMap(), listOf("배당 조회 실패: ${e.message}"), listOf("배당 미반영: 배당 조회 실패(가격수익률)"))
        }
        val warnings = inputs.warnings.take(MAX_DIVIDEND_WARNINGS) +
            (if (inputs.warnings.size > MAX_DIVIDEND_WARNINGS) listOf("배당 경고 ${inputs.warnings.size - MAX_DIVIDEND_WARNINGS}건 생략") else emptyList())
        return DividendSetup(
            if (inputs.events.isEmpty()) emptyMap() else mapOf("_dividends" to inputs.events), warnings,
            listOf("현금배당 반영: 배당락일 종가에 세후(dividend_tax) 현금 적립, 재투자 없음"),
        )
    }

    /**
     * 원본 `portfolio._with_sector_map` — 섹터 한도(max_sector_pct)용 종목→업종 매핑을 백테스트 전체에 1회 조회해 config 에 싣는다.
     * 기준일은 원본 `date.today()`(컨테이너 UTC 날짜, migration-notes #8·#12). 실패(ERROR)와 매핑 없음(WARNING)은 로그를 나누고 둘 다 캡 미적용.
     */
    private fun withSectorMap(config: Map<String, Any?>): Map<String, Any?> {
        if (!Cfg.truthy(Cfg.map(config, "risk_layer")["max_sector_pct"]) || Cfg.truthy(config["_sector_map"])) return config
        val smap = try {
            krx.sectorMap(MarketClock.todayUtcLegacy(clock))
        } catch (e: DataSourceError) {
            log.error("섹터 집중 한도용 업종 매핑 조회 실패 — 섹터 캡 미적용: {}", e.message)
            return config
        }
        if (smap.isEmpty()) {
            log.warn("섹터 한도 설정됨(max_sector_pct)이나 업종 매핑 미확보 — 섹터 캡 미적용.")
            return config
        }
        log.info("섹터 집중 한도용 업종 매핑 로드: {}종목", smap.size)
        return config + ("_sector_map" to smap)
    }

    private fun indexClose(ticker: String, from: LocalDate, to: LocalDate, failLog: String): Series? = try {
        snapshots.indexOhlcv(from, to, ticker)?.takeIf { it.isNotEmpty() }?.let { b ->
            Series(b.map { it.date }, DoubleArray(b.size) { b[it].close ?: Double.NaN })
        }
    } catch (e: Exception) {
        log.warn("{}: {}", failLog, e.message)
        null
    }

    /**
     * universe_rule.source 가 지수명이면 월별 실제 구성종목(PIT)으로 (합집합, 리밸런싱일별 후보풀). "fixed" 면 (null, null).
     * 지수 소스는 index_constituents 로컬 캐시만 읽고(없으면 MarketDataNotLoaded), KOSPI_MCAP 은 시총 상위 pick 종목이다.
     */
    private fun pitPool(rule: Map<String, Any?>?, start: LocalDate, end: LocalDate): Pair<List<String>?, PoolProvider?> {
        val source = Cfg.get(rule, "source", "fixed").toString()
        if (source == "fixed") return null to null
        if (source == "KOSPI_MCAP") return mcapPool(rule, start, end)
        val minCapWon = rule?.get("min_market_cap")?.takeIf { Cfg.truthy(it) }?.let { Cfg.num(it).toLong() * 100_000_000L } ?: 0L
        val byMonth = LinkedHashMap<Pair<Int, Int>, List<String>>()
        var d = start.withDayOfMonth(1)
        while (!d.isAfter(end.withDayOfMonth(1))) {
            var members = localMembers(source, d)
            if (minCapWon > 0 && members.isNotEmpty()) {
                val caps = krx.marketCaps(d)
                if (caps.isNotEmpty()) members = members.filter { (caps[it] ?: 0L) >= minCapWon }
            }
            byMonth[d.year to d.monthValue] = members
            d = d.plusMonths(1)
        }
        val union = byMonth.values.flatten().toSortedSet().toList()
        if (union.isEmpty()) {
            log.error("PIT 후보풀이 비었다(source={}, {}~{}, {}개월 전부 0종목) — 로컬 index_constituents 적재 확인. 이 상태로 만든 백테스트 결과는 신뢰할 수 없다.", source, start, end, byMonth.size)
        }
        return union to { day -> byMonth[day.year to day.monthValue].orEmpty() }
    }

    /** KOSPI_MCAP: 결정일마다 직전 거래일 시총 상위 pick(월 단위 아님). min_market_cap 은 이 소스에서 적용하지 않는다. */
    private fun mcapPool(rule: Map<String, Any?>?, start: LocalDate, end: LocalDate): Pair<List<String>?, PoolProvider?> {
        if (Cfg.truthy(rule?.get("min_market_cap"))) log.warn("KOSPI_MCAP 소스는 min_market_cap 을 적용하지 않는다(무시됨).")
        val pool = mcap.pool(start, end, (rule?.get("pick") as? Number)?.toInt() ?: 20)
        if (pool.union.isEmpty()) log.error("KOSPI_MCAP 후보풀이 비었다({}~{}) — 이 상태로 만든 백테스트 결과는 신뢰할 수 없다.", start, end)
        return pool.union to pool.members
    }

    private fun localMembers(index: String, day: LocalDate): List<String> =
        constituents.read(index, day).ifEmpty { throw MarketDataNotLoaded("index_constituents", "$index $day", listOf(day to day)) }

    /**
     * score 선정용 시점별 펀더멘털: 밸류(KRX) + 퀄리티(OpenDART, PIT) [+ 중립화 축 시총·업종] [+ flow·잔차 모멘텀·PEAD].
     * 각 조회 실패는 그 컬럼만 빠진다(스코어러가 중립 처리) — 원본과 같은 저하 규칙.
     */
    private fun fundamentalsProvider(config: Map<String, Any?>, panel: Panel, benchmark: Series?): FundamentalsProvider {
        val sel = Cfg.map(config, "selection")
        val useTtm = Cfg.get(config, "financial_period", "annual") == "ttm"
        val neutralize = Cfg.get(sel, "neutralize", "none").toString()
        @Suppress("UNCHECKED_CAST")
        val fw = (sel["factor_weights"] as? Map<String, Any?>).orEmpty()
        fun weight(k: String) = (fw[k] as? Number)?.toDouble() ?: 0.0
        val closes = panel.symbols.associateWith { panel.column(it) }
        return { asOf, codes ->
            val norm = codes.map { it.padStart(6, '0') }
            val rows = LinkedHashMap<String, MutableMap<String, Any?>>()
            var any = false
            fun put(code: String, k: String, v: Any?) {
                rows.getOrPut(code) { linkedMapOf() }[k] = v
                any = true
            }
            runCatching { snapshots.fundamentals(asOf, listOf("KOSPI", "KOSDAQ")) }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { raw ->
                val byCode = raw.associateBy { it.symbol }
                for (c in norm) {
                    put(c, "PER", byCode[c]?.per)
                    put(c, "PBR", byCode[c]?.pbr)
                    put(c, "DIV", byCode[c]?.div)
                }
            }
            val quality = runCatching { dart.metricsBySymbol(norm, asOf, useTtm) }.getOrDefault(emptyMap())
            if (quality.isNotEmpty()) {
                for (k in FactorScores.QUALITY_COLS) {
                    if (quality.values.none { k in it }) continue
                    for (c in norm) put(c, k, quality[c]?.get(k))
                }
            }
            if (neutralize == "size" || neutralize == "size_sector") {
                val caps = try {
                    krx.marketCaps(asOf)
                } catch (e: DataSourceError) {
                    log.error("사이즈 중립화 생략 — 시가총액 조회 실패: {}", e.message)
                    emptyMap()
                }
                if (caps.isNotEmpty()) for (c in norm) put(c, "market_cap", caps[c]?.toDouble())
            }
            if (neutralize == "sector" || neutralize == "size_sector") {
                val smap = try {
                    krx.sectorMap(asOf)
                } catch (e: DataSourceError) {
                    log.error("섹터 중립화 생략 — 업종분류 조회 실패: {}", e.message)
                    emptyMap()
                }
                if (smap.isNotEmpty()) for (c in norm) put(c, "sector", smap[c])
            }
            if (weight("flow") > 0) {
                // 실패는 전파(명시 요청한 팩터가 조용히 빠지면 안 된다). 빈 결과(순매수 실적 없음)만 컬럼 생략.
                val flow = scores.flowNorm(norm, asOf, Cfg.int(Cfg.get(sel, "flow_window", 90)), Cfg.get(sel, "flow_denom", "mcap").toString())
                if (flow.isNotEmpty()) for (c in norm) put(c, "flow_norm", flow[c])
            }
            if (weight("residual_momentum") > 0 && benchmark != null) {
                val rm = runCatching {
                    ResidualMomentum.compute(
                        closes, benchmark, asOf, norm, Cfg.int(Cfg.get(sel, "resid_mom_reg_window", 36)),
                        Cfg.int(Cfg.get(sel, "resid_mom_window", 11)), Cfg.int(Cfg.get(sel, "resid_mom_skip", 1)),
                    )
                }.getOrNull()
                if (!rm.isNullOrEmpty()) for (c in norm) put(c, "resid_mom", rm[c])
            }
            if (weight("pead") > 0) {
                val pead = runCatching { dart.peadSueBySymbol(norm, asOf, Cfg.int(Cfg.get(sel, "pead_lookback_q", 8))) }.getOrNull()
                if (!pead.isNullOrEmpty()) for (c in norm) put(c, "pead_sue", pead[c])
            }
            if (any) rows else null
        }
    }

    /** quality·growth 가중치가 있는데 OpenDART 커버리지가 30% 미만이면 경고 문구. 조회 실패는 경고하지 않는다(오탐 방지). */
    private fun factorCoverageWarnings(config: Map<String, Any?>, universe: List<String>, asOf: LocalDate): List<String> {
        @Suppress("UNCHECKED_CAST")
        val fw = (Cfg.map(config, "selection")["factor_weights"] as? Map<String, Any?>).orEmpty()
        val need = listOf("quality", "growth").filter { ((fw[it] as? Number)?.toDouble() ?: 0.0) > 0 }
        if (need.isEmpty() || universe.isEmpty()) return emptyList()
        val sample = universe.take(40)
        val metrics = try {
            dart.metricsBySymbol(sample, asOf)
        } catch (e: Exception) {
            return emptyList()
        }
        val coverage = sample.count { !metrics[it].isNullOrEmpty() }.toDouble() / sample.size
        if (coverage >= 0.3) return emptyList()
        return listOf(
            "OpenDART 재무 커버리지 ${pyFixed(coverage * 100, 0)}% (표본 ${sample.size}종목) — " +
                "가중치를 준 ${need.joinToString("·")} 팩터가 대부분 중립(0) 처리되어 점수에 거의 반영되지 " +
                "않습니다. OPENDART API 키·응답을 확인하거나 해당 가중치를 재검토하세요.",
        )
    }

    companion object {
        private val REGIME_TICKER = mapOf("KOSPI" to "1001", "KOSDAQ" to "2001")
        private const val MAX_DIVIDEND_WARNINGS = 20
        private val BENCHMARK_TICKER = mapOf("KOSPI200" to "1028", "KOSPI" to "1001", "KOSDAQ" to "2001")

        /** DSR 동질 시행 판별 키 — 정렬 종목 + universe_rule 의 sha256 앞 16자. 원본 Python 과 바이트 동일해야 이력이 이어진다. */
        fun universeFingerprint(universe: List<String>, rule: Map<String, Any?>?): String {
            val payload = pyJsonDumps(mapOf("universe" to universe.sorted(), "universe_rule" to (rule ?: emptyMap<String, Any?>())), sortKeys = true)
            return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
