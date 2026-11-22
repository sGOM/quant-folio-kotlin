package com.quantfolio.market.kis

import com.quantfolio.common.MarketClock
import com.quantfolio.domain.market.KisStockMasterSnapshot
import com.quantfolio.domain.market.KisStockMasterSnapshotRepository
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpErrors
import com.quantfolio.market.source.SourceSchemaError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.time.Clock
import java.time.Duration
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

data class MasterRow(val symbol: String, val name: String, val raw: Map<String, String>)

/** 시장("KOSPI"·"KOSDAQ")의 KIS 종목마스터 행. */
fun interface MasterListing {
    fun fetch(market: String): List<MasterRow>
}

/**
 * KIS 종목마스터(원본 `data/kis_master.py`) — KRX·FDR·DART 어디에도 없는 매매 상태 플래그(관리종목·정리매매 등)를
 * KIS 공개 CDN 의 시장 전체 zip 에서 받는다. 매일 18:40 KST 스냅샷.
 * 파일은 cp949 고정폭: 앞부분(단축코드 9·표준코드 12·이름 가변) + 뒤 고정폭 필드. 필드 폭·이름은 KIS 공식 샘플 그대로.
 */
@Component
class KisStockMaster(
    private val http: ExternalHttp,
    private val cooldowns: Cooldowns,
    private val repo: KisStockMasterSnapshotRepository,
    private val clock: Clock,
    private val tx: org.springframework.transaction.support.TransactionTemplate,
) : MasterListing {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun fetch(market: String): List<MasterRow> {
        val name = FILE_NAMES[market] ?: throw IllegalArgumentException("지원하지 않는 시장: $market")
        val bytes = try {
            val r = http.get(BASE_URL.format(name), timeout = Duration.ofSeconds(30))
            HttpErrors.check(SOURCE, r, "$market 종목마스터")
            r.body
        } catch (e: Exception) {
            val exc = HttpErrors.transport(SOURCE, e, "$market 종목마스터")
            cooldowns.noteFailure(exc)
            log.warn("KIS 종목마스터({}) 다운로드 실패: {}", market, exc.message)
            throw exc
        }
        return parse(extractMst(bytes, market), market)
    }

    /** 두 시장을 tradeDate(기본 KST 오늘)로 적재. 한 시장 실패는 건너뛰고, 둘 다 실패면 대표 예외. 적재 건수 반환. */
    // 두 시장을 한 트랜잭션으로(원본: 한 세션·커밋 1회) — 두 번째 시장 저장 실패가 첫 시장 교체만 남겨 그날 스냅샷을 반쯤 지우지 않게.
    fun snapshot(tradeDate: LocalDate? = null): Int = tx.execute { snapshotInTx(tradeDate) }!!

    private fun snapshotInTx(tradeDate: LocalDate?): Int {
        val day = tradeDate ?: MarketClock.todayKst(clock)
        val errors = mutableListOf<DataSourceError>()
        var saved = 0
        for (market in FILE_NAMES.keys) {
            val rows = try {
                fetch(market)
            } catch (e: Exception) {
                errors += e as? DataSourceError ?: SourceSchemaError(SOURCE, "$market 처리 중 예상치 못한 오류: ${e.message}")
                log.warn("KIS 종목마스터({}) 적재 실패 — 스킵: {}", market, e.message)
                continue
            }
            repo.deleteByMarketAndTradeDate(market, day)
            repo.saveAll(rows.map { KisStockMasterSnapshot(day, it.symbol, market, it.name, it.raw) })
            saved += rows.size
        }
        if (saved == 0 && errors.isNotEmpty()) throw DataSourceErrors.representative(errors)
        log.info("KIS 종목마스터 스냅샷 적재: trade_date={} {}종목(성공 시장 {}/2)", day, saved, 2 - errors.size)
        return saved
    }

    /** 종목의 최신 스냅샷(trade_date·market·name + raw 필드 평탄화). 없으면 null. */
    fun latest(symbol: String): Map<String, Any?>? {
        val row = repo.findFirstBySymbolOrderByTradeDateDesc(symbol) ?: return null
        return mapOf("trade_date" to row.tradeDate, "market" to row.market, "name" to row.name) + row.raw
    }

    private fun extractMst(zip: ByteArray, market: String): String {
        try {
            ZipInputStream(ByteArrayInputStream(zip)).use { z ->
                var sawEntry = false
                generateSequence { z.nextEntry }.forEach { entry ->
                    sawEntry = true
                    if (entry.name.endsWith(".mst")) return String(z.readAllBytes(), CP949)
                }
                throw SourceSchemaError(SOURCE, if (sawEntry) "$market zip 안에 .mst 파일이 없다" else "$market zip 파싱 실패: 항목 없음")
            }
        } catch (e: ZipException) {
            throw SourceSchemaError(SOURCE, "$market zip 파싱 실패: ${e.message}")
        }
    }

    private fun parse(text: String, market: String): List<MasterRow> {
        val (widths, columns) = layout(market)
        val tail = widths.sum()
        val rows = mutableListOf<MasterRow>()
        var dataLines = 0
        for (line in text.lines()) {
            if (line.isBlank()) continue
            if (line.length <= tail) {
                throw SourceSchemaError(SOURCE, "$market 마스터 행 길이가 예상보다 짧다(len=${line.length}, 기대 tail=$tail): ${line.take(60)}")
            }
            dataLines++
            val head = line.substring(0, line.length - tail)
            val symbol = head.take(9).trim()
            val std = head.substring(9.coerceAtMost(head.length), 21.coerceAtMost(head.length)).trim()
            val name = head.drop(21).trim()
            if (symbol.isEmpty() || name.isEmpty()) continue
            var pos = line.length - tail
            val raw = LinkedHashMap<String, String>()
            for (i in widths.indices) {
                raw[columns[i]] = line.substring(pos, pos + widths[i]).trim() // pandas read_fwf: 공백 제거, 빈값 ''
                pos += widths[i]
            }
            raw["표준코드"] = std
            rows += MasterRow(symbol.padStart(6, '0'), name, raw)
        }
        if (dataLines == 0) throw SourceSchemaError(SOURCE, "$market 마스터 파일에 데이터 행이 없다")
        if (rows.isEmpty()) throw SourceSchemaError(SOURCE, "$market 마스터에 유효한 종목 행이 없다")
        return rows
    }

    companion object {
        const val SOURCE = "kis_master"
        private const val BASE_URL = "https://new.real.download.dws.co.kr/common/master/%s.mst.zip"
        private val FILE_NAMES = linkedMapOf("KOSPI" to "kospi_code", "KOSDAQ" to "kosdaq_code")
        private val CP949: Charset = Charset.forName("MS949")

        private val KOSPI_WIDTHS = listOf(
            2, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 5, 5, 1, 1, 1, 2, 1, 1,
            1, 2, 2, 2, 3, 1, 3, 12, 12, 8, 15, 21, 2, 7, 1, 1, 1, 1, 1, 9, 9, 9, 5, 9, 8, 9, 3, 1, 1, 1,
        )
        private val KOSPI_COLUMNS = listOf(
            "그룹코드", "시가총액규모", "지수업종대분류", "지수업종중분류", "지수업종소분류", "제조업", "저유동성", "지배구조지수종목", "KOSPI200섹터업종",
            "KOSPI100", "KOSPI50", "KRX", "ETP", "ELW발행", "KRX100", "KRX자동차", "KRX반도체", "KRX바이오", "KRX은행", "SPAC", "KRX에너지화학",
            "KRX철강", "단기과열", "KRX미디어통신", "KRX건설", "Non1", "KRX증권", "KRX선박", "KRX섹터_보험", "KRX섹터_운송", "SRI", "기준가",
            "매매수량단위", "시간외수량단위", "거래정지", "정리매매", "관리종목", "시장경고", "경고예고", "불성실공시", "우회상장", "락구분", "액면변경",
            "증자구분", "증거금비율", "신용가능", "신용기간", "전일거래량", "액면가", "상장일자", "상장주수", "자본금", "결산월", "공모가", "우선주",
            "공매도과열", "이상급등", "KRX300", "KOSPI", "매출액", "영업이익", "경상이익", "당기순이익", "ROE", "기준년월", "시가총액", "그룹사코드",
            "회사신용한도초과", "담보대출가능", "대주가능",
        )
        private val KOSDAQ_WIDTHS = listOf(
            2, 1, 4, 4, 4, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 9, 5, 5, 1, 1, 1, 2, 1, 1, 1, 2, 2, 2, 3,
            1, 3, 12, 12, 8, 15, 21, 2, 7, 1, 1, 1, 1, 9, 9, 9, 5, 9, 8, 9, 3, 1, 1, 1,
        )
        private val KOSDAQ_COLUMNS = listOf(
            "증권그룹구분코드", "시가총액 규모 구분 코드 유가", "지수업종 대분류 코드", "지수 업종 중분류 코드", "지수업종 소분류 코드", "벤처기업 여부 (Y/N)",
            "저유동성종목 여부", "KRX 종목 여부", "ETP 상품구분코드", "KRX100 종목 여부 (Y/N)", "KRX 자동차 여부", "KRX 반도체 여부", "KRX 바이오 여부",
            "KRX 은행 여부", "기업인수목적회사여부", "KRX 에너지 화학 여부", "KRX 철강 여부", "단기과열종목구분코드", "KRX 미디어 통신 여부", "KRX 건설 여부",
            "(코스닥)투자주의환기종목여부", "KRX 증권 구분", "KRX 선박 구분", "KRX섹터지수 보험여부", "KRX섹터지수 운송여부", "KOSDAQ150지수여부 (Y,N)",
            "주식 기준가", "정규 시장 매매 수량 단위", "시간외 시장 매매 수량 단위", "거래정지 여부", "정리매매 여부", "관리 종목 여부", "시장 경고 구분 코드",
            "시장 경고위험 예고 여부", "불성실 공시 여부", "우회 상장 여부", "락구분 코드", "액면가 변경 구분 코드", "증자 구분 코드", "증거금 비율",
            "신용주문 가능 여부", "신용기간", "전일 거래량", "주식 액면가", "주식 상장 일자", "상장 주수(천)", "자본금", "결산 월", "공모 가격",
            "우선주 구분 코드", "공매도과열종목여부", "이상급등종목여부", "KRX300 종목 여부 (Y/N)", "매출액", "영업이익", "경상이익", "단기순이익",
            "ROE(자기자본이익률)", "기준년월", "전일기준 시가총액 (억)", "그룹사 코드", "회사신용한도초과여부", "담보대출가능여부", "대주가능여부",
        )

        fun layout(market: String): Pair<List<Int>, List<String>> = when (market) {
            "KOSPI" -> KOSPI_WIDTHS to KOSPI_COLUMNS
            "KOSDAQ" -> KOSDAQ_WIDTHS to KOSDAQ_COLUMNS
            else -> throw IllegalArgumentException("지원하지 않는 시장: $market")
        }
    }
}

/** 관리종목·정리매매 신규 매수 차단 사유(원본 `management_block_reason`). 스냅샷이 10일 넘게 낡았으면 판단하지 않는다(오탐 방지). */
object ManagementBlock {
    private const val MAX_STALE_DAYS = 10L
    private val MGMT_FIELD = mapOf("KOSPI" to "관리종목", "KOSDAQ" to "관리 종목 여부")
    private val LIQ_FIELD = mapOf("KOSPI" to "정리매매", "KOSDAQ" to "정리매매 여부")

    fun reason(snapshot: Map<String, Any?>?, today: LocalDate): String? {
        if (snapshot == null) return null
        val tradeDate = snapshot["trade_date"] as? LocalDate ?: return null
        if (ChronoUnit.DAYS.between(tradeDate, today) > MAX_STALE_DAYS) return null
        val market = snapshot["market"]?.toString().orEmpty()
        MGMT_FIELD[market]?.let { if (snapshot[it] == "Y") return "관리종목 지정 종목(기준일 $tradeDate) — 신규 매수 차단" }
        LIQ_FIELD[market]?.let { if (snapshot[it] == "Y") return "정리매매 종목(기준일 $tradeDate) — 신규 매수 차단" }
        return null
    }
}
