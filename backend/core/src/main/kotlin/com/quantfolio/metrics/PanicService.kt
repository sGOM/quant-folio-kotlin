package com.quantfolio.metrics

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.MetricsCommon
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.source.DataSourceError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

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
