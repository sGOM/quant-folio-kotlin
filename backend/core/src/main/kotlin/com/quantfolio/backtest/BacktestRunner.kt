package com.quantfolio.backtest

import com.quantfolio.common.MarketClock
import com.quantfolio.factor.FactorCoverage
import com.quantfolio.factor.FactorScores
import com.quantfolio.factor.ResidualMomentum
import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.official.LocalSectorMap
import com.quantfolio.market.official.DividendFeed
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.McapUniverse
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.ohlcv.LocalOhlcvCoverage
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.PriceTickStore
import com.quantfolio.metrics.PanicService
import com.quantfolio.quant.Cfg
import com.quantfolio.quant.Panel
import com.quantfolio.quant.Series
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.stereotype.Component
import java.security.MessageDigest
import java.time.LocalDate

/** 입력·설정이 백테스트에 맞지 않음 — HTTP 422(원본 ValueError·HTTPException 422). */
class BacktestInputError(message: String) : RuntimeException(message)

/** 백테스트 실행 중 예상 못 한 실패 — HTTP 500 `백테스트 실패: {원인}`(원본 그대로 원인 문구 노출). */
class BacktestFailed(cause: Throwable) : RuntimeException("백테스트 실패: ${cause.message}", cause)

/**
 * 전략 백테스트 실행(원본 `routes/backtests.py` 의 데이터 준비 + 엔진 호출). **로컬 데이터만** 읽는다 — 시세는 price_ticks,
 * 시총·펀더멘털·지수는 로컬 DB, 재무는 로컬 DART. 원격(KRX·Naver·DART)으로 내려가지 않고 비면 [MarketDataNotLoaded](HTTP 409)로
 * 멈춘다(조용한 저하 금지). 기간 경계는 KST(시작 00:00, 끝 23:59:59).
 */
@Component
class BacktestRunner(
    private val coverage: LocalOhlcvCoverage,
    private val ticks: PriceTickStore,
    @Qualifier("local") private val snapshots: SnapshotSource,
    private val dart: DartFinancials,
    private val sectorMaps: LocalSectorMap,
    private val mcap: McapUniverse,
    private val panic: PanicService,
    private val dividendFeed: DividendFeed,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(config: Map<String, Any?>, start: LocalDate, end: LocalDate): Map<String, Any?> =
        if (config["type"] == "rebalance") rebalance(config, start, end) else single(config, start, end)

    private fun bars(symbol: String, start: LocalDate, end: LocalDate): List<Bar> {
        coverage.require(symbol, start, end)
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
    } catch (e: MarketDataNotLoaded) {
        throw e // 미적재는 실행 실패(500)가 아니라 409 로 그대로 알린다
    } catch (e: DataSourceError) {
        throw BacktestFailed(e)
    } catch (e: Exception) {
        log.error("백테스트 실행 오류", e)
        throw BacktestFailed(e)
    }

    private fun rebalance(config: Map<String, Any?>, start: LocalDate, end: LocalDate): Map<String, Any?> {
        val warmup = start.minusDays(500)
        val selection = Cfg.map(config, "selection")
        rejectUnsupportedFactors(selection)
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
            } catch (e: MarketDataNotLoaded) {
                throw e
            } catch (e: Exception) {
                log.warn("패닉 오버레이 시계열 적재 실패(오버레이 미적용): {}", e.message)
                null
            }
        } else {
            null
        }

        val dividends = withDividends(closes.keys.toList(), start, end)
        val sectors = if (Cfg.truthy(Cfg.map(config, "risk_layer")["max_sector_pct"]) && !Cfg.truthy(config["_sector_map"])) SectorSetup() else null
        val result = LinkedHashMap(
            engine {
                PortfolioBacktest.run(
                    closePanel = panel, config = config + dividends.config, simStart = start, simEnd = end, fundamentals = provider,
                    regime = regime, pool = pool, benchmark = benchmark, panic = panicDays, volume = volume, sectors = sectors?.let { it::at },
                )
            },
        )
        (dividends.warnings + (result["dividend_warnings"] as? List<*> ?: emptyList<Any?>())).takeIf { it.isNotEmpty() }?.let { result["dividend_warnings"] = it }
        result["assumptions"] = dividends.assumptions + "벤치마크는 배당 제외" + listOfNotNull(sectors?.assumption())
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
            listOf(
                "현금배당 반영: 배당락일 종가에 세후(dividend_tax) 현금 적립(적립 현금은 다음 리밸런싱에서 투자될 수 있다)",
                "배당락일 적립 금액은 사후 확정값이다(2023년 이전 결산배당은 락일 뒤에 결의)",
            ),
        )
    }

    /**
     * 섹터 한도(max_sector_pct)용 결정일별 업종 매핑 — 결정일 이하 최신 스냅샷(같은 스냅샷일은 1회만 읽는다).
     * 원본 `_with_sector_map` 은 오늘 분류 하나를 구간 전체에 써 미래를 봤다(migration-notes #12). 과거 업종 이력은 출처가 없어
     * 스냅샷이 없는(또는 빈) 결정일은 캡 없이 진행하고, 그 사실을 결과 가정([assumption])에 적어 캡이 조용히 빠지지 않게 한다.
     */
    private inner class SectorSetup {
        private val maps = HashMap<LocalDate, Map<String, String>>()
        private var firstApplied: LocalDate? = null
        private var skipped = false

        fun at(d: LocalDate): Map<String, String?>? {
            val map = sectorMaps.latestOnOrBefore(d)?.let { snap -> maps.getOrPut(snap) { sectorMaps.mapAt(snap) } }?.takeIf { it.isNotEmpty() }
            if (map == null) skipped = true else if (firstApplied == null) firstApplied = d
            return map
        }

        fun assumption(): String? = when {
            firstApplied == null -> "섹터 한도(max_sector_pct) 미적용: 구간에 업종 스냅샷이 없다(과거 업종 이력 없음 — 오늘 분류로 대신하지 않는다)"
            skipped -> "섹터 한도(max_sector_pct)는 업종 스냅샷이 있는 $firstApplied 결정일부터 적용(그 전 결정일은 미적용)"
            else -> null
        }
    }

    private fun indexClose(ticker: String, from: LocalDate, to: LocalDate, failLog: String): Series? = try {
        snapshots.indexOhlcv(from, to, ticker)?.takeIf { it.isNotEmpty() }?.let { b ->
            Series(b.map { it.date }, DoubleArray(b.size) { b[it].close ?: Double.NaN })
        }
    } catch (e: MarketDataNotLoaded) {
        throw e
    } catch (e: Exception) {
        log.warn("{}: {}", failLog, e.message)
        null
    }

    /** universe_rule.source 가 KOSPI_MCAP 이면 (합집합, 결정일별 후보풀). "fixed" 면 (null, null). */
    private fun pitPool(rule: Map<String, Any?>?, start: LocalDate, end: LocalDate): Pair<List<String>?, PoolProvider?> =
        if (Cfg.get(rule, "source", "fixed").toString() == "fixed") null to null else mcapPool(rule, start, end)

    /**
     * KOSPI_MCAP: 결정일마다 직전 거래일 시총 상위 (pool_size ?: pick)(월 단위 아님). 그 위에 모멘텀 pick 좁히기는
     * [PortfolioBacktest] 가 적용한다. min_market_cap 은 이 소스에서 적용하지 않는다.
     */
    private fun mcapPool(rule: Map<String, Any?>?, start: LocalDate, end: LocalDate): Pair<List<String>?, PoolProvider?> {
        if (Cfg.truthy(rule?.get("min_market_cap"))) log.warn("KOSPI_MCAP 소스는 min_market_cap 을 적용하지 않는다(무시됨).")
        val size = (rule?.get("pool_size") as? Number)?.toInt() ?: (rule?.get("pick") as? Number)?.toInt() ?: 20
        val pool = mcap.pool(start, end, size)
        if (pool.union.isEmpty()) log.error("KOSPI_MCAP 후보풀이 비었다({}~{}) — 이 상태로 만든 백테스트 결과는 신뢰할 수 없다.", start, end)
        return pool.union to pool.members
    }

    /** 1단계 공식 경로에 입력이 없는 팩터(pead: 공시 달력)는 가중치를 주면 즉시 거절한다. */
    private fun rejectUnsupportedFactors(selection: Map<String, Any?>) {
        if (Cfg.get(selection, "method", "momentum") != "score") return
        val w = factorWeights(selection)
        for (k in listOf("pead")) {
            if ((w[k] ?: 0.0) > 0) {
                throw BacktestInputError("$k 팩터는 1단계(공식 경로)에서 지원하지 않습니다: 입력 데이터(공시 달력)를 공식 출처에서 구할 수 없습니다. 가중치를 0 으로 두세요.")
            }
        }
    }

    /**
     * [FactorScores.compute] 와 같은 유효 가중치: 비었으면 기본값 전체, 아니면 없는 키만 기본값.
     * 엔진은 `Cfg.num`(숫자만)으로 읽으므로 숫자가 아닌 값("0.5" 등)은 거절·커버리지 검사를 피해 가지 않게 여기서 입력 오류로 막는다.
     */
    private fun factorWeights(selection: Map<String, Any?>): Map<String, Double> {
        @Suppress("UNCHECKED_CAST")
        val fw = (selection["factor_weights"] as? Map<String, Any?>).orEmpty()
        val given = fw.mapValues { (k, v) -> (v as? Number)?.toDouble() ?: throw BacktestInputError("factor_weights.$k 는 숫자여야 합니다: $v") }
        return if (given.isEmpty()) FactorScores.DEFAULT_WEIGHTS else FactorScores.DEFAULT_WEIGHTS + given
    }

    /**
     * score 선정용 시점별 펀더멘털(로컬 전용): 밸류(로컬 시총·DART 로 계산) + 퀄리티(로컬 DART, PIT) [+ 중립화 축 시총·업종] [+ 잔차 모멘텀].
     * 가중치가 있는 팩터는 리밸런싱일마다 후보의 [FactorCoverage.MIN] 이상이 입력을 가져야 한다 — 미달이면 [MarketDataNotLoaded]
     * (후보의 절반 이상 점수가 없으면 순위가 결측 패턴에 좌우된다. 임계값은 측정 후 조정 가능). 후보가 없는 날은 검사하지 않는다.
     * 미적재([MarketDataNotLoaded])는 숨기지 않고 전파하고, 잔차 모멘텀 계산 실패만 그 컬럼 결측으로 저하한다.
     */
    private fun fundamentalsProvider(config: Map<String, Any?>, panel: Panel, benchmark: Series?): FundamentalsProvider {
        val sel = Cfg.map(config, "selection")
        val useTtm = Cfg.get(config, "financial_period", "annual") == "ttm"
        val neutralize = Cfg.get(sel, "neutralize", "none").toString()
        val fw = factorWeights(sel)
        fun weight(k: String) = fw[k] ?: 0.0
        val closes = panel.symbols.associateWith { panel.column(it) }
        return { asOf, codes ->
            val norm = codes.map { it.padStart(6, '0') }
            val rows = LinkedHashMap<String, MutableMap<String, Any?>>()
            var any = false
            fun put(code: String, k: String, v: Any?) {
                rows.getOrPut(code) { linkedMapOf() }[k] = v
                any = true
            }
            fun requireCoverage(factor: String, have: Int) = FactorCoverage.require(factor, asOf, have, norm.size)
            if (weight("value") > 0) {
                val byCode = snapshots.fundamentals(asOf, listOf("KOSPI", "KOSDAQ")).associateBy { it.symbol }
                for (c in norm) {
                    put(c, "PER", byCode[c]?.per)
                    put(c, "PBR", byCode[c]?.pbr)
                    put(c, "DIV", byCode[c]?.div)
                }
                requireCoverage("value", norm.count { c -> FactorScores.VALUE_INPUTS.any { rows[c]?.get(it) != null } })
            }
            if (weight("quality") > 0 || weight("growth") > 0) {
                val quality = dart.metricsBySymbol(norm, asOf, useTtm, remote = false)
                if (weight("quality") > 0) requireCoverage("quality", norm.count { c -> FactorScores.QUALITY_INPUTS.any { quality[c]?.get(it) != null } })
                if (weight("growth") > 0) requireCoverage("growth", norm.count { c -> FactorScores.GROWTH_INPUTS.any { quality[c]?.get(it) != null } })
                for (k in FactorScores.QUALITY_COLS) {
                    if (quality.values.none { k in it }) continue
                    for (c in norm) put(c, k, quality[c]?.get(k))
                }
            }
            if (neutralize == "size" || neutralize == "size_sector") {
                val caps = snapshots.marketCap(asOf, listOf("KOSPI", "KOSDAQ")).associate { it.symbol to it.marketCap }
                if (caps.isNotEmpty()) for (c in norm) put(c, "market_cap", caps[c]?.toDouble())
            }
            if (neutralize == "sector" || neutralize == "size_sector") {
                val smap = sectorMaps.sectorMap(asOf) // 로컬 KIS 마스터 스냅샷 — 없으면 MarketDataNotLoaded
                if (smap.isNotEmpty()) for (c in norm) put(c, "sector", smap[c])
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
            if (any) rows else null
        }
    }

    companion object {
        private val REGIME_TICKER = mapOf("KOSPI" to "1001", "KOSDAQ" to "2001")
        private const val MAX_DIVIDEND_WARNINGS = 20
        private val BENCHMARK_TICKER = mapOf("KOSPI200" to "1028", "KOSPI" to "1001", "KOSDAQ" to "2001")

        /**
         * DSR 동질 시행 판별 키 — 정렬 종목 + universe_rule 의 sha256 앞 16자. 원본 Python 과 바이트 동일해야 이력이 이어진다.
         * 2단계에서 생긴 `pool_size` 가 null 이면 뺀다 — 스키마 덤프가 모든 규칙에 넣어, 빼지 않으면 이전 시행과 지문이 갈린다.
         */
        fun universeFingerprint(universe: List<String>, rule: Map<String, Any?>?): String {
            val r = rule.orEmpty().filterNot { (k, v) -> k == "pool_size" && v == null }
            val payload = pyJsonDumps(mapOf("universe" to universe.sorted(), "universe_rule" to r), sortKeys = true)
            return MessageDigest.getInstance("SHA-256").digest(payload.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)
        }
    }
}
