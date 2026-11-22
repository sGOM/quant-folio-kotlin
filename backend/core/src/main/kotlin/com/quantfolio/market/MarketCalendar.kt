package com.quantfolio.market

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.common.MarketClock
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit

/** 오늘 이전 날짜가 거래일인가 — 로컬 적재 데이터 기준. */
fun interface PastTradingDays {
    fun isTradingDay(day: LocalDate): Boolean
}

/** KIS 국내휴장일조회: [from] 이후 날짜별 개장일 여부. 실패하면 예외. */
fun interface KisHolidays {
    fun holidays(from: LocalDate): Map<LocalDate, Boolean>
}

/** 소유자 KIS 자격증명으로 [KisHolidays] 를 조회한다. 자격증명이 없으면 예외 — 달력이 평일 규칙으로 돌아간다. */
@Component
class OwnerKisHolidays(private val credentials: BrokerCredentials) : KisHolidays {
    override fun holidays(from: LocalDate): Map<LocalDate, Boolean> =
        (credentials.ownerKis() ?: error("KIS 자격증명이 설정되지 않았습니다")).holidays(from)
}

/**
 * KRX 장 운영시간·휴장일(원본 `services/market.py`). 정규장 09:00~15:30 KST.
 *
 * 과거 날짜는 로컬 `index_ohlcv`(KOSPI 1001) 날짜([PastTradingDays]), 오늘·미래는 KIS 국내휴장일조회([KisHolidays]).
 * KIS 결과는 하루 캐시한다(호출 권장 하루 1회). 조회가 실패하거나(vts 미지원 포함) 목록에 없는 날은 평일 규칙으로 돌려 장 개장 판단을 막지 않고
 * 경고만 남긴다 — 실제 휴장이면 시세·주문 단계에서 자연 차단된다. 실패 후 [RETRY_AFTER] 동안은 다시 부르지 않는다.
 */
@Component
class MarketCalendar(
    private val past: PastTradingDays,
    private val kis: KisHolidays,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private class Cached(val day: LocalDate, val map: Map<LocalDate, Boolean>)

    @Volatile private var cached: Cached? = null
    @Volatile private var failedAt: Instant? = null

    fun isBusinessDay(d: LocalDate): Boolean {
        if (d.dayOfWeek == DayOfWeek.SATURDAY || d.dayOfWeek == DayOfWeek.SUNDAY) return false
        val today = MarketClock.todayKst(clock)
        if (d.isBefore(today)) return past.isTradingDay(d)
        return kisHolidays(today)[d] ?: true
    }

    @Synchronized
    private fun kisHolidays(today: LocalDate): Map<LocalDate, Boolean> {
        cached?.takeIf { it.day == today }?.let { return it.map }
        val now = clock.instant()
        if (failedAt?.let { Duration.between(it, now) < RETRY_AFTER } == true) return emptyMap()
        return try {
            kis.holidays(today).also { cached = Cached(today, it); failedAt = null }
        } catch (e: Exception) {
            failedAt = now
            log.warn("KIS 휴장일 조회 실패 — 평일 규칙으로 판단: {}", e.message)
            emptyMap()
        }
    }

    fun isMarketOpen(now: ZonedDateTime = MarketClock.nowKst(clock)): Boolean {
        val kst = now.withZoneSameInstant(MarketClock.KST)
        if (!isBusinessDay(kst.toLocalDate())) return false
        val t = kst.toLocalTime()
        return !t.isBefore(MARKET_OPEN) && !t.isAfter(MARKET_CLOSE)
    }

    companion object {
        private val MARKET_OPEN: LocalTime = LocalTime.of(9, 0)
        private val MARKET_CLOSE: LocalTime = LocalTime.of(15, 30)
        private val RETRY_AFTER: Duration = Duration.ofMinutes(10)
        private const val KR_HOLIDAYS_PER_YEAR = 15.0

        /** 예상 거래일 수(달력일×5/7 − 연 15일 휴장 비례). 커버리지 부분응답 판정용 근사. */
        fun estimatedTradingDays(start: LocalDate, end: LocalDate): Double {
            val calendarDays = ChronoUnit.DAYS.between(start, end) + 1
            if (calendarDays <= 0) return 0.0
            return maxOf(calendarDays * 5.0 / 7 - calendarDays * (KR_HOLIDAYS_PER_YEAR / 365), 0.0)
        }
    }
}
