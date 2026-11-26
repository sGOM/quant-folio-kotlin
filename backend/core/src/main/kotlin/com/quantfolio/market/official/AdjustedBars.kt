package com.quantfolio.market.official

import com.quantfolio.market.store.Bar
import com.quantfolio.market.store.StockDaily
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 원주가 + KRX 전일대비(기준가 기준) → 마지막 날 원주가에 맞춘 역산 수정주가.
 * 마지막 날 이전 전체가 최신 기준으로 바뀌는 건 네이버 수정주가와 같은 알려진 예외다(백테스트 문서 참고).
 */
object AdjustedBars {
    /**
     * 거래정지일 행은 거래량 0, 시·고·저 0, 종가 = 기준가다. 0 시가봉은 ATR·스토캐스틱·돌파 신호를 오염시키므로
     * o=h=l=종가로 편다(봉은 버리지 않는다 — 커버리지가 빠진 날로 센다). 원본과 알려진 차이. price_ticks 적재·수정주가 재계산 모두 이 함수를 지난다.
     */
    fun flatIfHalted(r: StockDaily): StockDaily {
        val c = r.close
        val o = r.open; val h = r.high; val l = r.low
        return if (r.volume == 0L && c != null && o != null && h != null && l != null && o <= 0 && h <= 0 && l <= 0) r.copy(open = c, high = c, low = c) else r
    }

    /** [rows] 는 한 종목의 날짜 오름차순, [days] 는 같은 길이. OHLCV 중 null 인 날은 건너뛴다(0 으로 메우지 않음). */
    fun build(rows: List<StockDaily>, days: List<LocalDate>): List<Bar> {
        require(rows.size == days.size) { "rows/days 길이 불일치: ${rows.size} vs ${days.size}" }
        val f = DoubleArray(rows.size) { 1.0 }
        for (t in rows.size - 1 downTo 1) {
            val close = rows[t].close
            val prev = rows[t - 1].close
            val base = if (close != null && rows[t].prevDiff != null) close - rows[t].prevDiff!! else null
            // 기준가를 못 구하면(전일대비 없음·기준가 ≤ 0·전일 종가 없음) 새 체인: 인자를 그대로 이어받는다.
            f[t - 1] = if (base != null && base > 0 && prev != null && prev > 0) f[t] * base / prev else f[t]
        }
        return rows.indices.mapNotNull { i ->
            val r = flatIfHalted(rows[i])
            val v = r.volume; val o = r.open; val h = r.high; val l = r.low; val c = r.close
            if (o == null || h == null || l == null || c == null || v == null) null
            else Bar(days[i].atStartOfDay().toInstant(ZoneOffset.UTC), o * f[i], h * f[i], l * f[i], c * f[i], v)
        }
    }
}
