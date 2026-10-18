package com.quantfolio.market.krx

import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 원본이 쓰던 pykrx 1.2.8 함수의 재현 — 같은 bld·파라미터를 보내고 같은 정리 규칙으로 숫자를 만든다.
 * 실패는 그대로 예외로 올린다(호출자 [KrxSnapshots] 가 원본처럼 Unavailable 로 감싼다).
 * 숫자 정리 규칙이 함수마다 미묘하게 다르다 — pykrx 의 pandas replace 를 그대로 옮겼으니 임의로 통일하지 말 것.
 */
@Component
class KrxStockApi(private val mdc: KrxMdc) {
    @Volatile
    private var indexTable: List<IndexInfo>? = null

    private data class IndexInfo(val ticker: String, val name: String, val baseDate: String, val market: String)

    fun marketOhlcvByTicker(date: String, market: String): List<TickerOhlcv> =
        mdc.pykrx(bld("MDCSTAT01501"), mapOf("mktId" to mktId(market), "trdDd" to date), "OutBlock_1").map { r ->
            val v = { k: String -> ohlcvClean(r[k]) }
            TickerOhlcv(
                symbol = r["ISU_SRT_CD"].toString(),
                open = int32(v("TDD_OPNPRC")), high = int32(v("TDD_HGPRC")), low = int32(v("TDD_LWPRC")), close = int32(v("TDD_CLSPRC")),
                volume = int32(v("ACC_TRDVOL")), tradingValue = v("ACC_TRDVAL").toLong(),
                changePct = float32(v("FLUC_RT")), marketCap = v("MKTCAP").toLong(),
            )
        }

    fun marketCapByTicker(date: String, market: String): List<TickerCap> =
        mdc.pykrx(bld("MDCSTAT01501"), mapOf("mktId" to mktId(market), "trdDd" to date), "OutBlock_1").map { r ->
            // pykrx: `\W` 제거(부호·소수점까지 지워진다) → 빈값 0 → int64
            val v = { k: String -> r[k].toString().replace(NON_WORD, "").ifEmpty { "0" }.toLong() }
            TickerCap(r["ISU_SRT_CD"].toString(), v("TDD_CLSPRC"), v("MKTCAP"), v("ACC_TRDVOL"), v("ACC_TRDVAL"), v("LIST_SHRS"))
        }.sortedByDescending { it.marketCap }

    fun fundamentalByTicker(date: String, market: String): List<TickerFundamental> =
        mdc.pykrx(bld("MDCSTAT03501"), mapOf("mktId" to mktId(market), "trdDd" to date), "output").map { r ->
            // pykrx: 끝 '-' → '0', 빈값 → '0', ',' 제거
            val v = { k: String -> r[k].toString().replace(TRAILING_DASH, "0").ifEmpty { "0" }.replace(",", "") }
            TickerFundamental(
                r["ISU_SRT_CD"].toString(),
                bps = int32(v("BPS")), per = v("PER").toDouble(), pbr = v("PBR").toDouble(),
                eps = int32(v("EPS")), div = v("DVD_YLD").toDouble(), dps = int32(v("DPS")),
            )
        }

    /** 시작일은 다음 영업일, 종료일은 이전 영업일로 보정. 기간 결과에 없는 시작일 종목(기간 중 상폐)을 보강한다. */
    fun priceChangeByTicker(from: String, to: String, market: String): List<TickerPriceChange> {
        val start = nearestBusinessDay(from, prev = false)
        val end = nearestBusinessDay(to)
        val period = priceChangeRaw(start, end, market)
        if (period.isEmpty()) return period
        val present = period.map { it.symbol }.toSet()
        val delisted = priceChangeRaw(start, start, market).filter { it.symbol !in present }.map {
            it.copy(close = 0, change = -it.open, changePct = -100.0, volume = 0, tradingValue = 0)
        }
        return period + delisted
    }

    private fun priceChangeRaw(from: String, to: String, market: String): List<TickerPriceChange> =
        mdc.pykrx(
            bld("MDCSTAT01602"),
            mapOf("mktId" to mktId(market), "adjStkPrc" to "2", "strtDd" to from, "endDd" to to),
            "OutBlock_1",
        ).map { r ->
            // pykrx: `[^-.\w]` 제거 → 끝 '-' 삭제 → 빈값 '0'
            val v = { k: String -> r[k].toString().replace(NON_NUMERIC_KEEP_DASH, "").replace(TRAILING_DASH, "").ifEmpty { "0" } }
            TickerPriceChange(
                symbol = r["ISU_SRT_CD"].toString(), name = r["ISU_ABBRV"].toString(),
                open = int32(v("BAS_PRC")), close = int32(v("TDD_CLSPRC")), change = int32(v("CMPPREVDD_PRC")),
                changePct = v("FLUC_RT").toDouble(), volume = v("ACC_TRDVOL").toLong(), tradingValue = v("ACC_TRDVAL").toLong(),
            )
        }

    /** 응답 컬럼은 이름이 아니라 **순서**로 해석한다(원본 `df.columns = [...]`). */
    fun netPurchasesByTicker(from: String, to: String, market: String, investor: String): List<TickerNetPurchase> {
        val code = INVESTOR_CODES[investor] ?: throw IllegalArgumentException("알 수 없는 투자자군: $investor")
        return mdc.pykrx(
            bld("MDCSTAT02401"),
            mapOf("strtDd" to from, "endDd" to to, "mktId" to mktId(market), "invstTpCd" to code.toString()),
            "output",
        ).map { r ->
            val cols = r.values.map { it.toString().replace("/", "").replace(",", "") }
            TickerNetPurchase(
                symbol = cols[0].padStart(6, '0'), name = cols[1],
                sellVolume = cols[2].toLong(), buyVolume = cols[3].toLong(), netVolume = cols[4].toLong(),
                sellValue = cols[5].toLong(), buyValue = cols[6].toLong(), netValue = cols[7].toLong(),
            )
        }
    }

    /** ticker = 그룹 1자리 + 지수코드(예: 1028 = 코스피200). 날짜순. */
    fun indexOhlcvByDate(from: String, to: String, ticker: String): List<IndexDaily> =
        mdc.pykrx(
            bld("MDCSTAT00301"),
            mapOf("indIdx2" to ticker.substring(1), "indIdx" to ticker.take(1), "strtDd" to from, "endDd" to to),
            "output",
        ).map { r ->
            val v = { k: String -> ohlcvClean(r[k]) }
            IndexDaily(
                date = LocalDate.parse(v("TRD_DD"), YMD),
                open = v("OPNPRC_IDX").toDouble(), high = v("HGPRC_IDX").toDouble(), low = v("LWPRC_IDX").toDouble(),
                close = v("CLSPRC_IDX").toDouble(), volume = v("ACC_TRDVOL").toLong(), tradingValue = v("ACC_TRDVAL").toLong(),
                marketCap = v("MKTCAP").toLong(),
            )
        }.sortedBy { it.date }

    /** 일주일 안의 가장 가까운 영업일(코스피 지수 발행일 기준). prev=false 면 이후 방향. 데이터가 없으면 예외(원본 IndexError). */
    fun nearestBusinessDay(date: String, prev: Boolean = true): String {
        val d = LocalDate.parse(date, YMD)
        return if (prev) {
            indexOhlcvByDate(d.minusDays(7).format(YMD), date, "1001").last().date.format(YMD)
        } else {
            indexOhlcvByDate(date, d.plusDays(7).format(YMD), "1001").first().date.format(YMD)
        }
    }

    /** market: KRX | KOSPI | KOSDAQ | 테마. 기준시점이 date 이하인 지수만, 티커 오름차순. */
    fun indexTickers(date: String, market: String): List<String> =
        indexes().filter { it.market == market && it.baseDate <= date }.map { it.ticker }

    fun indexTickerName(ticker: String): String =
        indexes().firstOrNull { it.ticker == ticker }?.name ?: throw NoSuchElementException("지수 티커 없음: $ticker")

    /** 지수 기본정보는 프로세스당 1회 로드(원본 pykrx `IndexTicker` 싱글턴). */
    private fun indexes(): List<IndexInfo> = indexTable ?: synchronized(this) {
        indexTable ?: INDEX_GROUPS.flatMap { (code, market) ->
            mdc.pykrx(bld("MDCSTAT00401"), mapOf("idxIndMidclssCd" to code), "output").map { r ->
                IndexInfo(
                    ticker = r["IND_TP_CD"].toString() + r["IDX_IND_CD"].toString(),
                    name = r["IDX_NM"].toString(), baseDate = r["BAS_TM_CONTN"].toString(), market = market,
                )
            }
        }.sortedBy { it.ticker }.also { indexTable = it }
    }

    private fun bld(code: String) = "dbms/MDC/STAT/standard/$code"

    /** `[^-\w.]` 제거 → 끝 '-' → '0' → 빈값 → '0' (전종목시세·지수시세 공통). */
    private fun ohlcvClean(raw: Any?): String =
        raw.toString().replace(NON_NUMERIC_KEEP_DASH, "").replace(TRAILING_DASH, "0").ifEmpty { "0" }

    /** numpy int32 캐스팅처럼 범위를 넘으면 실패한다(원본은 그 시장 조회가 통째로 실패). */
    private fun int32(s: String): Long {
        val v = s.toBigDecimal().toBigInteger()
        if (v.bitLength() > 31) throw ArithmeticException("int32 범위 초과: $s")
        return v.toLong()
    }

    /** numpy float32 정밀도로 내렸다가 최단 표현으로 되돌린다. */
    private fun float32(s: String): Double = s.toFloat().toString().toDouble()

    private fun mktId(market: String) = MARKET_IDS[market] ?: throw IllegalArgumentException("알 수 없는 시장: $market")

    companion object {
        val MARKET_IDS = mapOf("ALL" to "ALL", "KOSPI" to "STK", "KOSDAQ" to "KSQ", "KONEX" to "KNX")
        val INVESTOR_CODES = mapOf(
            "금융투자" to 1000, "보험" to 2000, "투신" to 3000, "사모" to 3100, "은행" to 4000, "기타금융" to 5000,
            "연기금" to 6000, "기관합계" to 7050, "기타법인" to 7100, "개인" to 8000, "외국인" to 9000, "기타외국인" to 9001, "전체" to 9999,
        )
        private val INDEX_GROUPS = listOf("01" to "KRX", "02" to "KOSPI", "03" to "KOSDAQ", "04" to "테마")
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val NON_NUMERIC_KEEP_DASH = Regex("(?U)[^-\\w.]")
        private val NON_WORD = Regex("(?U)\\W")
        private val TRAILING_DASH = Regex("-$")
    }
}
