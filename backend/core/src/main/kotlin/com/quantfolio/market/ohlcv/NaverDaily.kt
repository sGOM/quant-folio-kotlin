package com.quantfolio.market.ohlcv

import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.store.Bar
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 네이버 차트 일봉(수정주가). 원본의 실효 소스 두 경로를 그대로 재현한다.
 * - [fdr]: FinanceDataReader `NaverDailyReader` — 최근 6000일을 받아 날짜로 자른다.
 * - [pykrx]: pykrx `naver.get_market_ohlcv_by_date` — 시작일까지 거슬러 갈 만큼(경과일+2) 받는다.
 * 같은 엔드포인트라 값은 같고, 6000일보다 오래된 구간은 pykrx 경로만 닿는다.
 */
@Component
class NaverDaily(private val http: ExternalHttp) {
    fun fdr(code: String, start: LocalDate, end: LocalDate): List<Bar> {
        val r = http.get(FDR_URL, linkedMapOf("timeframe" to "day", "count" to "6000", "requestType" to "0", "symbol" to code))
        return parse(r.text(Charsets.UTF_8)).filter { !it.first.isBefore(start) && !it.first.isAfter(end) }.map { it.second }
    }

    fun pykrx(code: String, start: LocalDate, end: LocalDate, today: LocalDate): List<Bar> {
        val elapsed = ChronoUnit.DAYS.between(start, today) + 2
        val r = http.get(PYKRX_URL, linkedMapOf("symbol" to code, "timeframe" to "day", "count" to elapsed.toString(), "requestType" to "0"))
        return parse(r.text(Charsets.UTF_8)).filter { !it.first.isBefore(start) && !it.first.isAfter(end) }.map { it.second }
    }

    /** `<item data="yyyyMMdd|시|고|저|종|거래량" />` → 날짜순 일봉(시각 = 거래일 00:00 UTC). */
    private fun parse(text: String): List<Pair<LocalDate, Bar>> =
        ITEM.findAll(text).map { m ->
            val f = m.groupValues[1].split("|")
            val day = LocalDate.parse(f[0], YMD)
            day to Bar(day.atStartOfDay().toInstant(ZoneOffset.UTC), f[1].toDouble(), f[2].toDouble(), f[3].toDouble(), f[4].toDouble(), f[5].toDouble().toLong())
        }.sortedBy { it.first }.toList()

    companion object {
        const val FDR_URL = "https://fchart.stock.naver.com/sise.nhn"
        const val PYKRX_URL = "http://fchart.stock.naver.com/sise.nhn"
        private val ITEM = Regex("<item data=\"(.*?)\" />", RegexOption.DOT_MATCHES_ALL)
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
