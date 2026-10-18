package com.quantfolio.market.dart

import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.FetchLedger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/**
 * OpenDART 재무의 로컬 우선 조회와 종목별 팩터 입력(원본 `opendart.py` 의 캐시·집계부).
 *
 * PIT 주의: 재무 지표는 공시일 이후부터만 쓴다. [metricsBySymbol] 은 연간이면 4월부터 직전 연도,
 * TTM 이면 분기 공시 마감 경계로 연도·보고서를 고른다.
 */
@Component
class DartFinancials(
    private val client: OpenDartClient,
    private val store: DartFinancialStore,
    private val ledger: FetchLedger,
    private val cooldowns: Cooldowns,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val accountsCache = ConcurrentHashMap<List<Any>, List<Map<String, Any?>>>()
    private val annualCache = ConcurrentHashMap<Pair<String, Int>, Map<String, Double?>>()
    private val periodCache = ConcurrentHashMap<Triple<String, Int, String>, Map<String, Double?>>()
    private val calendarCache = ConcurrentHashMap<Pair<String, Int>, List<Triple<LocalDate, Int, String>>>()

    @Volatile private var corpMap: Map<String, String>? = null

    /** OpenDART 키 설정 여부(없으면 재무 팩터는 중립). */
    fun enabled(): Boolean = client.enabled()

    /**
     * 메모리 → 원장(확정 0행이면 null) → 저장소 확정본 → 원격. 원격 결과는 저장하고 원장에 기록한다.
     * 무자료(013)는 확정일이 지났을 때만 '확정 무자료'로 굳힌다(정정공시로 나중에 생길 수 있다).
     */
    fun cachedAccounts(corp: String, year: Int, reprt: String, fsDiv: String): List<Map<String, Any?>>? {
        val key = listOf(corp, year, reprt, fsDiv)
        accountsCache[key]?.let { return it }
        val cacheKey = CacheKeys.of(corp, year, reprt, fsDiv)
        val entry = ledger.get(LEDGER_SOURCE, cacheKey)
        if (entry != null && entry.final && entry.rowCount == 0) return null
        val stored = store.read(corp, year, reprt, fsDiv)
        if (stored != null && stored.second) return stored.first.also { accountsCache[key] = it }

        val acc = client.accounts(corp, year, reprt, fsDiv)
        if (!acc.isNullOrEmpty()) {
            store.write(corp, year, reprt, fsDiv, acc)
            accountsCache[key] = acc
            ledger.put(LEDGER_SOURCE, cacheKey, acc.size, store.read(corp, year, reprt, fsDiv)?.second ?: false)
            return acc
        }
        if (!client.enabled()) return null
        // 원본 date.today() — 컨테이너 UTC(보존)
        val final = !LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC).isBefore(DartFinancialStore.confirmedDate(null, year))
        ledger.put(LEDGER_SOURCE, cacheKey, 0, final)
        return null
    }

    fun corpCodeMap(): Map<String, String>? = corpMap ?: client.corpCodeMap()?.also { corpMap = it }

    /** 연간(사업보고서) 지표 — 연결 우선, 없으면 별도. */
    fun annualMetrics(corp: String, year: Int): Map<String, Double?> {
        annualCache[corp to year]?.let { return it }
        val accounts = firstAvailable(corp, year, DartMetrics.REPORT_ANNUAL)
        return DartMetrics.deriveMetrics(accounts.orEmpty()).also { if (accounts != null) annualCache[corp to year] = it }
    }

    fun periodMetrics(corp: String, year: Int, reprt: String): Map<String, Double?> {
        val key = Triple(corp, year, reprt)
        periodCache[key]?.let { return it }
        val accounts = firstAvailable(corp, year, reprt)
        return DartMetrics.deriveMetrics(accounts.orEmpty()).also { if (accounts != null) periodCache[key] = it }
    }

    /** 최근 4분기 합산(TTM). 당해 분기가 아직 없으면 직전 연간, 합성이 불가하면 당해 분기 그대로. */
    fun ttmMetrics(corp: String, year: Int, reprt: String): Map<String, Double?> {
        if (reprt == DartMetrics.REPORT_ANNUAL) return periodMetrics(corp, year, DartMetrics.REPORT_ANNUAL)
        val cur = periodMetrics(corp, year, reprt)
        if (cur.values.all { it == null }) return periodMetrics(corp, year - 1, DartMetrics.REPORT_ANNUAL)
        val combined = DartMetrics.combineTtm(cur, periodMetrics(corp, year - 1, reprt), periodMetrics(corp, year - 1, DartMetrics.REPORT_ANNUAL))
        return if (combined.values.all { it == null }) cur else combined
    }

    /** 종목별 재무 지표 + 성장률·흑자전환·F-score·3년 적자 횟수. 전량 실패면 대표 예외, 일부 실패는 로그 후 부분 결과. */
    fun metricsBySymbol(codes: List<String>, asOf: LocalDate, useTtm: Boolean = false): Map<String, Map<String, Any?>> {
        if (!client.enabled() || codes.isEmpty()) return emptyMap()
        val map = corpCodeMap() ?: return emptyMap()
        val (year, reprt) = if (useTtm) DartMetrics.latestReportPeriod(asOf) else DartMetrics.announcementLaggedYear(asOf) to DartMetrics.REPORT_ANNUAL
        val out = linkedMapOf<String, Map<String, Any?>>()
        val errors = mutableListOf<DataSourceError>()
        var ok = 0
        for (raw in codes) {
            if (cooldowns.stopAggregate(OpenDartClient.SOURCE, errors, ok)) break
            val code = raw.trim().padStart(6, '0')
            val corp = map[code] ?: continue
            val (m, prev, prev2) = try {
                if (useTtm) {
                    Triple(ttmMetrics(corp, year, reprt), ttmMetrics(corp, year - 1, reprt), ttmMetrics(corp, year - 2, reprt))
                } else {
                    Triple(annualMetrics(corp, year), annualMetrics(corp, year - 1), annualMetrics(corp, year - 2))
                }
            } catch (e: DataSourceError) {
                errors += e
                continue
            }
            ok++
            val result: MutableMap<String, Any?> = LinkedHashMap(m)
            result["op_growth"] = DartMetrics.yoyGrowth(m["op_income"], prev["op_income"])
            result["net_growth"] = DartMetrics.yoyGrowth(m["net_income"], prev["net_income"])
            result["turnaround"] = DartMetrics.turnaroundFlag(m["net_income"], prev["net_income"])
            result["f_score"] = DartMetrics.piotroskiFScore(m, prev)
            result["loss_years_3"] = DartMetrics.countLosses(listOf(m["net_income"], prev["net_income"], prev2["net_income"]))
            if (result.values.any { it != null }) out[code] = result
        }
        if (ok == 0 && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (errors.isNotEmpty()) log.error("OpenDART 부분 실패 — 성공 {} / 실패 {} / 전체 {}(대표: {})", ok, errors.size, codes.size, errors.first().message)
        return out
    }

    /** 정기보고서 공시 달력: (최초 접수일, 사업연도, 보고서코드). 정정공시는 최초 접수일만 남긴다. */
    fun disclosureCalendar(corp: String, refYear: Int): List<Triple<LocalDate, Int, String>> {
        calendarCache[corp to refYear]?.let { return it }
        val data = client.get(
            "list.json",
            linkedMapOf("corp_code" to corp, "bgn_de" to "${refYear - 5}0101", "end_de" to "${refYear}1231", "pblntf_ty" to "A", "page_count" to "100"),
        )
        val earliest = linkedMapOf<Pair<Int, String>, LocalDate>()
        val list = data?.path("list")
        if (list != null) {
            for (i in 0 until list.size()) {
                val item = list.get(i)
                val (d, year, reprt) = DartMetrics.parsePeriodicReport(item.path("report_nm").asString(""), item.path("rcept_dt").asString(""))
                    ?: continue
                val k = year to reprt
                if (earliest[k]?.let { d.isBefore(it) } != false) earliest[k] = d
            }
        }
        val out = earliest.map { (k, d) -> Triple(d, k.first, k.second) }
            .sortedWith(compareBy<Triple<LocalDate, Int, String>> { it.first }.thenBy { it.second }.thenBy { it.third })
        if (data != null) calendarCache[corp to refYear] = out
        return out
    }

    /** PEAD 표준화 이익 서프라이즈(SUE) = 최근 분기 YoY 서프라이즈 / 최근 lookbackQ 분기 표본표준편차. */
    fun peadSueBySymbol(codes: List<String>, asOf: LocalDate, lookbackQ: Int = 8, minObs: Int = 6): Map<String, Double> {
        if (!client.enabled() || codes.isEmpty()) return emptyMap()
        val map = corpCodeMap() ?: return emptyMap()
        val out = linkedMapOf<String, Double>()
        val errors = mutableListOf<DataSourceError>()
        var ok = 0
        for (raw in codes) {
            if (cooldowns.stopAggregate(OpenDartClient.SOURCE, errors, ok)) break
            val code = raw.trim().padStart(6, '0')
            val corp = map[code] ?: continue
            val sue = try {
                peadSueOne(corp, asOf, lookbackQ, minObs)
            } catch (e: DataSourceError) {
                errors += e
                continue
            }
            ok++
            if (sue != null) out[code] = sue
        }
        if (ok == 0 && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (errors.isNotEmpty()) log.error("OpenDART PEAD 부분 실패 — 성공 {} / 실패 {} / 전체 {}", ok, errors.size, codes.size)
        return out
    }

    private fun peadSueOne(corp: String, asOf: LocalDate, lookbackQ: Int, minObs: Int): Double? {
        val disclosed = disclosureCalendar(corp, asOf.year).filter { !it.first.isAfter(asOf) }
        if (disclosed.size < minObs) return null
        val surprises = disclosed.mapNotNull { (_, year, reprt) ->
            val cur = singleQuarterNetIncome(corp, year, reprt)
            val prev = singleQuarterNetIncome(corp, year - 1, reprt)
            if (cur == null || prev == null) null else cur - prev
        }
        if (surprises.size < minObs) return null
        val window = surprises.takeLast(lookbackQ)
        if (window.size < 2) return null
        val mean = window.average()
        val sd = sqrt(window.sumOf { (it - mean) * (it - mean) } / (window.size - 1))
        if (!(sd > 0)) return null
        return surprises.last() / sd
    }

    /** 누적 보고서에서 단일 분기 순이익 = 당기 누적 − 직전 누적(1분기는 그대로). */
    private fun singleQuarterNetIncome(corp: String, year: Int, reprt: String): Double? {
        val cur = periodMetrics(corp, year, reprt)["net_income"] ?: return null
        if (reprt == DartMetrics.REPORT_Q1) return cur
        val pred = QUARTER_PRED[reprt] ?: return null
        val prev = periodMetrics(corp, year, pred)["net_income"] ?: return null
        return cur - prev
    }

    private fun firstAvailable(corp: String, year: Int, reprt: String): List<Map<String, Any?>>? =
        listOf(FS_CONSOLIDATED, FS_SEPARATE).firstNotNullOfOrNull { fs -> cachedAccounts(corp, year, reprt, fs)?.takeIf { it.isNotEmpty() } }

    companion object {
        const val FS_CONSOLIDATED = "CFS"
        const val FS_SEPARATE = "OFS"
        private const val LEDGER_SOURCE = "dart_accounts"
        private val QUARTER_PRED = mapOf(
            DartMetrics.REPORT_HALF to DartMetrics.REPORT_Q1,
            DartMetrics.REPORT_Q3 to DartMetrics.REPORT_HALF,
            DartMetrics.REPORT_ANNUAL to DartMetrics.REPORT_Q3,
        )
    }
}
