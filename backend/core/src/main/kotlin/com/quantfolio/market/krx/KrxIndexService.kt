package com.quantfolio.market.krx

import com.quantfolio.domain.market.SectorMapSnapshot
import com.quantfolio.domain.market.SectorMapSnapshotRepository
import com.quantfolio.market.MarketCalendar
import com.quantfolio.market.ohlcv.IndexMembers
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.IndexConstituentStore
import com.quantfolio.market.store.StoreCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class ListedStock(val code: String, val name: String, val market: String)

/** KRX 전종목 목록(테스트에서 교체할 수 있게 분리). */
fun interface ListedStocks {
    fun allListedStocks(): List<ListedStock>
}

data class EtfLeverage(
    val asOf: LocalDate,
    val totalMktcap: Double,
    val leveragedMktcap: Double,
    val singleStockMktcap: Double,
    val leveragedRatio: Double?,
    val singleStockRatio: Double?,
    val leveragedCount: Int,
    val singleStockCount: Int,
)

/**
 * KRX 직접 조회(원본 `services/data/krx_index.py`) — 지수 구성종목(PIT 유니버스), 전종목 목록, 시가총액, 업종분류, ETF 레버리지.
 *
 * 공통 규칙: KRX 미인증(자격증명 없음)은 실패가 아니라 빈 결과. 날짜를 며칠 거슬러 휴장일을 건너뛰되,
 * 실패가 있고 쿨다운이 걸렸으면 더 시도하지 않는다. 한 번도 응답을 못 받고 실패만 있으면 대표 예외.
 * **성공했을 때만 캐시한다** — 실패를 캐시하면 섹터 한도 같은 안전장치가 조용히 무력화된다.
 */
@Component
class KrxIndexService(
    private val mdc: KrxMdc,
    private val session: KrxSession,
    private val cooldowns: Cooldowns,
    private val constituents: IndexConstituentStore,
    private val ledger: FetchLedger,
    private val cache: StoreCache,
    private val sectors: SectorMapSnapshotRepository,
    private val calendar: MarketCalendar,
    private val clock: Clock,
) : IndexMembers, ListedStocks {
    private val log = LoggerFactory.getLogger(javaClass)
    private val membersCache = ConcurrentHashMap<Pair<String, LocalDate>, List<String>>()
    private val capsCache = ConcurrentHashMap<LocalDate, Map<String, Long>>()

    @Volatile private var stocksCache: List<ListedStock>? = null

    @Volatile private var sectorCache: Map<String, String>? = null

    /** 지수 구성종목(PIT). 확정 기록이 있으면 로컬, 없으면 KRX 에서 최대 7일 거슬러 조회 후 저장. */
    override fun indexMembers(asOf: LocalDate, index: String): List<String> {
        val params = INDEX_PARAMS[index] ?: throw IllegalArgumentException("지원하지 않는 지수: $index (지원: ${INDEX_PARAMS.keys})")
        membersCache[index to asOf]?.let { return it }
        val storeKey = CacheKeys.of(index, asOf)
        if (ledger.get("index_members", storeKey)?.final == true) {
            return constituents.read(index, asOf).also { membersCache[index to asOf] = it }
        }
        if (session.current() == null) {
            log.debug("KRX 미인증 — {} 구성종목 조회 건너뜀", index)
            return emptyList()
        }
        var codes = emptyList<String>()
        val errors = mutableListOf<DataSourceError>()
        var ok = false
        for (back in 0L until 7L) {
            if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
            val dd = asOf.minusDays(back).format(YMD)
            val payload = mapOf(
                "bld" to BLD_INDEX_CONSTITUENTS, "locale" to "ko_KR", "trdDd" to dd, "money" to "1", "csvxls_isNo" to "false",
            ) + params
            val rows = try {
                mdc.rows(payload, "output", "$index 구성종목", Duration.ofSeconds(15))
            } catch (e: DataSourceError) {
                log.warn("KRX {} 구성종목 조회 실패({}): {}", index, dd, e.message)
                errors += e
                continue
            }
            ok = true
            codes = rows.mapNotNull { r -> r["ISU_SRT_CD"]?.toString()?.takeIf { it.isNotEmpty() }?.padStart(6, '0') }
            if (codes.isNotEmpty()) break
        }
        if (!ok && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (codes.isNotEmpty()) {
            constituents.write(index, asOf, codes)
            ledger.put("index_members", storeKey, codes.size, cache.isFinalDate(asOf))
            membersCache[index to asOf] = codes
        }
        return codes
    }

    fun indexMembers(asOf: LocalDate): List<String> = indexMembers(asOf, "KOSPI200")

    fun membershipUnion(dates: List<LocalDate>, index: String = "KOSPI200"): Map<String, List<String>> =
        dates.associate { it.format(YMD) to indexMembers(it, index) }

    override fun allListedStocks(): List<ListedStock> {
        stocksCache?.let { return it }
        if (session.current() == null) {
            log.debug("KRX 미인증 — 전종목 목록 조회 건너뜀")
            return emptyList()
        }
        val payload = mapOf("bld" to BLD_STOCK_FINDER, "locale" to "ko_KR", "mktsel" to "ALL", "typeNo" to "0", "searchText" to "")
        val out = mdc.rows(payload, "block1", "전종목목록").mapNotNull { r ->
            val code = r["short_code"]?.toString()?.trim().orEmpty()
            val name = r["codeName"]?.toString()?.trim().orEmpty()
            if (code.isEmpty() || name.isEmpty()) return@mapNotNull null
            ListedStock(code.padStart(6, '0'), name, MARKET_BY_CODE[r["marketCode"]?.toString()?.trim()].orEmpty())
        }
        if (out.isNotEmpty()) {
            stocksCache = out
            log.info("KRX 전종목 목록 로드: {}개", out.size)
        }
        return out
    }

    /** 시점별 시가총액(원). 유동성 필터용. */
    fun marketCaps(asOf: LocalDate): Map<String, Long> {
        capsCache[asOf]?.let { return it }
        if (session.current() == null) return emptyMap()
        val caps = linkedMapOf<String, Long>()
        val errors = mutableListOf<DataSourceError>()
        var ok = false
        for (back in 0L until 7L) {
            val dd = asOf.minusDays(back).format(YMD)
            for (mkt in listOf("STK", "KSQ")) {
                if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
                val payload = mapOf("bld" to BLD_MARKET_CAP, "locale" to "ko_KR", "mktId" to mkt, "trdDd" to dd, "money" to "1", "csvxls_isNo" to "false")
                val rows = try {
                    mdc.rows(payload, "OutBlock_1", "시가총액($mkt)")
                } catch (e: DataSourceError) {
                    log.warn("KRX 시가총액 조회 실패({} {}): {}", mkt, dd, e.message)
                    errors += e
                    continue
                }
                ok = true
                for (r in rows) {
                    val code = r["ISU_SRT_CD"]?.toString()?.trim().orEmpty().padStart(6, '0')
                    val raw = r["MKTCAP"]?.toString().orEmpty().replace(",", "").trim()
                    if (code.isNotEmpty() && raw.isNotEmpty() && raw.all { it.isDigit() }) caps[code] = raw.toLong()
                }
            }
            if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
            if (caps.isNotEmpty()) break
        }
        if (!ok && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (caps.isNotEmpty()) capsCache[asOf] = caps
        return caps
    }

    /**
     * 종목 → 업종. asOf 가 있으면 그 시점 이하 최신 PIT 스냅샷을 우선 쓴다(KRX 는 '현재' 분류만 준다).
     * 스냅샷이 없으면 현재 분류로 폴백 — 스냅샷 도입 이전 구간은 소급할 수 없다.
     */
    fun sectorMap(asOf: LocalDate? = null): Map<String, String> {
        if (asOf != null) {
            val snap = pitSnapshot(asOf)
            if (snap.isNotEmpty()) return snap
            log.warn("업종분류 PIT 스냅샷 미확보({} 이전 스냅샷 없음) — 현재 KRX 분류로 폴백", asOf)
        }
        sectorCache?.let { return it }
        if (session.current() == null) return emptyMap()
        val base = asOf ?: todayUtc()
        val mapping = linkedMapOf<String, String>()
        val errors = mutableListOf<DataSourceError>()
        var ok = false
        for (back in 0L until 10L) {
            val dd = base.minusDays(back).format(YMD)
            for (mkt in listOf("STK", "KSQ")) {
                if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
                val payload = mapOf("bld" to BLD_SECTOR, "locale" to "ko_KR", "mktId" to mkt, "trdDd" to dd, "money" to "1", "csvxls_isNo" to "false")
                val rows = try {
                    mdc.rows(payload, "block1", "업종분류($mkt)")
                } catch (e: DataSourceError) {
                    log.warn("KRX 업종분류 조회 실패({} {}): {}", mkt, dd, e.message)
                    errors += e
                    continue
                }
                ok = true
                for (r in rows) {
                    val code = r["ISU_SRT_CD"]?.toString()?.trim().orEmpty().padStart(6, '0')
                    val industry = r["IDX_IND_NM"]?.toString()?.trim().orEmpty()
                    if (code != "000000" && industry.isNotEmpty()) mapping[code] = industry
                }
            }
            if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
            if (mapping.isNotEmpty()) break
        }
        if (!ok && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (mapping.isNotEmpty()) {
            sectorCache = mapping
            log.info("KRX 업종분류 로드: {}종목", mapping.size)
        }
        return mapping
    }

    /** 현재 업종분류를 snapshotDate 로 적재(분기 배치). 이미 있으면 스킵, force 면 교체. 적재 건수 반환. */
    @Transactional
    fun snapshotSectorMap(asOf: LocalDate? = null, force: Boolean = false): Int {
        val day = asOf ?: todayUtc()
        val existing = sectors.countBySnapshotDate(day)
        if (existing > 0 && !force) {
            log.info("업종분류 스냅샷({}) 이미 존재({}건) — 스킵", day, existing)
            return 0
        }
        val mapping = sectorMap()
        if (mapping.isEmpty()) {
            log.warn("업종분류 미인증/빈 응답 — 스냅샷({}) 적재 건너뜀", day)
            return 0
        }
        if (existing > 0) sectors.deleteBySnapshotDate(day)
        sectors.saveAll(mapping.map { (code, sector) -> SectorMapSnapshot(code, sector, day) })
        log.info("업종분류 스냅샷 적재: snapshot_date={} {}종목", day, mapping.size)
        return mapping.size
    }

    /** ETF 시장의 레버리지·단일종목 레버리지 시가총액 비중(과열 지표). 미인증·무자료면 null. */
    fun etfLeverageExposure(asOf: LocalDate): EtfLeverage? {
        if (session.current() == null) return null
        var snapped = asOf
        repeat(10) { if (!calendar.isBusinessDay(snapped)) snapped = snapped.minusDays(1) }
        var rows = emptyList<Map<String, Any?>>()
        val errors = mutableListOf<DataSourceError>()
        var ok = false
        var used = snapped
        for (back in 0L until 7L) {
            if (errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0) break
            used = snapped.minusDays(back)
            val payload = mapOf("bld" to BLD_ETF_ALL, "locale" to "ko_KR", "trdDd" to used.format(YMD), "share" to "1", "money" to "1", "csvxls_isNo" to "false")
            rows = try {
                mdc.rows(payload, "output", "ETF 잔고")
            } catch (e: DataSourceError) {
                log.warn("KRX ETF 조회 실패({}): {}", used, e.message)
                errors += e
                continue
            }
            ok = true
            if (rows.isNotEmpty()) break
        }
        if (!ok && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        if (rows.isEmpty()) return null
        var total = 0.0
        var lev = 0.0
        var single = 0.0
        var levN = 0
        var singleN = 0
        for (r in rows) {
            val name = r["ISU_ABBRV"]?.toString().orEmpty()
            val cap = r["MKTCAP"]?.toString().orEmpty().replace(",", "").trim().toDoubleOrNull() ?: 0.0
            total += cap
            if ("레버리지" !in name) continue
            lev += cap
            levN++
            if ("단일종목" in name) {
                single += cap
                singleN++
            }
        }
        return EtfLeverage(
            used, total, lev, single,
            leveragedRatio = if (total != 0.0) lev / total else null,
            singleStockRatio = if (total != 0.0) single / total else null,
            leveragedCount = levN, singleStockCount = singleN,
        )
    }

    private fun pitSnapshot(asOf: LocalDate): Map<String, String> = try {
        val latest = sectors.latestDateOnOrBefore(asOf)
        if (latest == null) emptyMap() else sectors.findBySnapshotDate(latest).associate { it.symbol to it.sector }
    } catch (e: Exception) {
        log.warn("업종분류 PIT 스냅샷 조회 실패({}): {}", asOf, e.message)
        emptyMap()
    }

    /** 원본 `date.today()` — 컨테이너 UTC 날짜(보존, docs/migration-notes.md #8). */
    private fun todayUtc(): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)

    companion object {
        val INDEX_PARAMS = mapOf(
            "KOSPI200" to mapOf("indIdx" to "1", "indIdx2" to "028"),
            "KOSPI100" to mapOf("indIdx" to "1", "indIdx2" to "034"),
            "KRX300" to mapOf("indIdx" to "5", "indIdx2" to "300"),
        )
        private val MARKET_BY_CODE = mapOf("STK" to "KOSPI", "KSQ" to "KOSDAQ", "KNX" to "KONEX")
        private const val BLD_INDEX_CONSTITUENTS = "dbms/MDC/STAT/standard/MDCSTAT00601"
        private const val BLD_STOCK_FINDER = "dbms/comm/finder/finder_stkisu"
        private const val BLD_MARKET_CAP = "dbms/MDC/STAT/standard/MDCSTAT01501"
        private const val BLD_SECTOR = "dbms/MDC/STAT/standard/MDCSTAT03901"
        private const val BLD_ETF_ALL = "dbms/MDC/STAT/standard/MDCSTAT04301"
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
