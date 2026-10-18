package com.quantfolio.market

import com.quantfolio.common.MarketClock
import com.quantfolio.market.krx.KrxStockApi
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Collections

/**
 * KRX 장 운영시간·휴장일(원본 `services/market.py`). 정규장 09:00~15:30 KST.
 *
 * 공휴일은 지수 데이터로 best-effort 판정한다. 데이터 기반이라 '당일 데이터 미발행'(장 시작 전·미래)을 휴장으로
 * 오판할 수 있어, 대상일 **이후** 데이터가 있는지로 '실제 휴장'과 '미발행'을 가른다. 판정 불가면 평일은 영업일로 본다
 * (실제 휴장이면 시세·주문 단계에서 자연 차단된다).
 */
@Component
class MarketCalendar(private val api: KrxStockApi, private val clock: Clock = Clock.systemUTC()) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache: MutableMap<LocalDate, Boolean> = Collections.synchronizedMap(
        object : LinkedHashMap<LocalDate, Boolean>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<LocalDate, Boolean>) = size > CACHE_SIZE
        },
    )

    fun isBusinessDay(d: LocalDate): Boolean {
        if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return false
        cache[d]?.let { return it }
        val result = try {
            val ymd = d.format(YMD)
            when {
                api.nearestBusinessDay(ymd) == ymd -> true
                api.indexOhlcvByDate(d.plusDays(1).format(YMD), d.plusDays(14).format(YMD), "1001").isNotEmpty() -> false
                else -> {
                    log.warn("영업일 조회: {} 이후 지수 데이터 없음(미발행 추정) — 평일이므로 영업일로 간주", d)
                    true
                }
            }
        } catch (e: Exception) {
            log.debug("영업일 조회 실패({}), 주말여부만 적용: {}", d, e.message)
            true
        }
        cache[d] = result
        return result
    }

    fun isMarketOpen(now: ZonedDateTime = MarketClock.nowKst(clock)): Boolean {
        val kst = now.withZoneSameInstant(MarketClock.KST)
        if (!isBusinessDay(kst.toLocalDate())) return false
        val t = kst.toLocalTime()
        return !t.isBefore(MARKET_OPEN) && !t.isAfter(MARKET_CLOSE)
    }

    companion object {
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val MARKET_OPEN: LocalTime = LocalTime.of(9, 0)
        private val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
        private const val CACHE_SIZE = 512
        private const val KR_HOLIDAYS_PER_YEAR = 15.0

        /** 예상 거래일 수(달력일×5/7 − 연 15일 휴장 비례). 커버리지 부분응답 판정용 근사. */
        fun estimatedTradingDays(start: LocalDate, end: LocalDate): Double {
            val calendarDays = ChronoUnit.DAYS.between(start, end) + 1
            if (calendarDays <= 0) return 0.0
            return maxOf(calendarDays * 5.0 / 7 - calendarDays * (KR_HOLIDAYS_PER_YEAR / 365), 0.0)
        }
    }
}
