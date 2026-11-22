package com.quantfolio.market.krx

import com.quantfolio.domain.market.SectorMapSnapshot
import com.quantfolio.domain.market.SectorMapSnapshotRepository
import com.quantfolio.market.ohlcv.IndexMembers
import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.store.CacheKeys
import com.quantfolio.market.store.FetchLedger
import com.quantfolio.market.store.IndexConstituentStore
import com.quantfolio.market.store.StoreCache
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.concurrent.ConcurrentHashMap

data class ListedStock(val code: String, val name: String, val market: String)

/** KRX 전종목 목록(테스트에서 교체할 수 있게 분리). */
fun interface ListedStocks {
    fun allListedStocks(): List<ListedStock>
}

/**
 * KRX 직접 조회(원본 `services/data/krx_index.py`) — 지수 구성종목(PIT 유니버스), 전종목 목록, 시가총액, 업종분류.
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
    private val clock: Clock,
) : IndexMembers, ListedStocks {
    private val log = LoggerFactory.getLogger(javaClass)
    private val membersCache = ConcurrentHashMap<Pair<String, LocalDate>, List<String>>()
    private val capsCache = ConcurrentHashMap<LocalDate, Map<String, Long>>()

    @Volatile private var stocksCache: List<ListedStock>? = null

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
        val att = Attempts()
        for (back in 0L until 7L) {
            if (att.cooling()) break
            val dd = asOf.minusDays(back).format(YMD)
            val payload = mapOf(
                "bld" to BLD_INDEX_CONSTITUENTS, "locale" to "ko_KR", "trdDd" to dd, "money" to "1", "csvxls_isNo" to "false",
            ) + params
            val rows = att.rows(payload, "output", "$index 구성종목", Duration.ofSeconds(15)) { e ->
                log.warn("KRX {} 구성종목 조회 실패({}): {}", index, dd, e.message)
            } ?: continue
            codes = rows.mapNotNull { r -> r["ISU_SRT_CD"]?.toString()?.takeIf { it.isNotEmpty() }?.padStart(6, '0') }
            if (codes.isNotEmpty()) break
        }
        att.throwIfAllFailed()
        if (codes.isNotEmpty()) {
            constituents.write(index, asOf, codes)
            ledger.put("index_members", storeKey, codes.size, cache.isFinalDate(asOf))
            membersCache[index to asOf] = codes
        }
        return codes
    }

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
        val att = Attempts()
        for (back in 0L until 7L) {
            val dd = asOf.minusDays(back).format(YMD)
            for (mkt in listOf("STK", "KSQ")) {
                if (att.cooling()) break
                val payload = mapOf("bld" to BLD_MARKET_CAP, "locale" to "ko_KR", "mktId" to mkt, "trdDd" to dd, "money" to "1", "csvxls_isNo" to "false")
                val rows = att.rows(payload, "OutBlock_1", "시가총액($mkt)") { e ->
                    log.warn("KRX 시가총액 조회 실패({} {}): {}", mkt, dd, e.message)
                } ?: continue
                for (r in rows) {
                    val code = r["ISU_SRT_CD"]?.toString()?.trim().orEmpty().padStart(6, '0')
                    val raw = r["MKTCAP"]?.toString().orEmpty().replace(",", "").trim()
                    if (code.isNotEmpty() && raw.isNotEmpty() && raw.all { it.isDigit() }) caps[code] = raw.toLong()
                }
            }
            if (att.cooling()) break
            if (caps.isNotEmpty()) break
        }
        att.throwIfAllFailed()
        if (caps.isNotEmpty()) capsCache[asOf] = caps
        return caps
    }

    /**
     * 종목 → 업종. asOf 이하 최신 PIT 스냅샷(`sector_map_snapshots`, KIS 종목마스터 스냅샷이 채운다)만 쓴다.
     * 스냅샷이 없으면 원격으로 대체하지 않고 [MarketDataNotLoaded] — 업종 없이 돌리면 섹터 캡이 조용히 무력화된다.
     */
    fun sectorMap(asOf: LocalDate): Map<String, String> {
        val latest = sectors.latestDateOnOrBefore(asOf) ?: throw MarketDataNotLoaded("sector_map", asOf.toString())
        return sectors.findBySnapshotDate(latest).associate { it.symbol to it.sector }
    }

    /**
     * 날짜(·시장)를 거슬러 가며 여러 번 조회할 때의 실패 누적. 실패가 쌓인 채 쿨다운이 걸리면 단락([cooling]),
     * 한 번도 성공 못 했으면 대표 예외([throwIfAllFailed]). 빈 응답도 성공으로 센다.
     */
    private inner class Attempts {
        private val errors = mutableListOf<DataSourceError>()
        private var ok = false

        fun cooling() = errors.isNotEmpty() && cooldowns.remaining(KrxSession.SOURCE) > 0

        /** 성공하면 행, 실패하면 [onFail] 로그 후 누적하고 null. */
        fun rows(
            payload: Map<String, String>, key: String, label: String, timeout: Duration = Duration.ofSeconds(20), onFail: (DataSourceError) -> Unit,
        ): List<Map<String, Any?>>? = try {
            mdc.rows(payload, key, label, timeout).also { ok = true }
        } catch (e: DataSourceError) {
            onFail(e)
            errors += e
            null
        }

        fun throwIfAllFailed() {
            if (!ok && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        }
    }

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
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
