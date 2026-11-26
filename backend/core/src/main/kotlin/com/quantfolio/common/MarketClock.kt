package com.quantfolio.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * KRX 거래일 기준 시계. 컨테이너 TZ 는 UTC 라 `LocalDate.now()` 는 KST 자정~09시 사이에 전날을 돌려준다.
 * 거래일이 필요하면 반드시 여기서 얻는다.
 */
object MarketClock {
    val KST: ZoneId = ZoneId.of("Asia/Seoul")

    fun nowKst(clock: Clock = Clock.systemUTC()): ZonedDateTime = ZonedDateTime.now(clock).withZoneSameInstant(KST)

    fun todayKst(clock: Clock = Clock.systemUTC()): LocalDate = nowKst(clock).toLocalDate()

    /** 시각 → KST 달력 날짜(거래일 기준). */
    fun kstDate(t: Instant): LocalDate = t.atZone(KST).toLocalDate()

    /** KST 하루 시작(00:00) 시각. */
    fun kstStart(d: LocalDate): Instant = d.atStartOfDay(KST).toInstant()

    /** KST 하루 끝(23:59:59) 시각. */
    fun kstEnd(d: LocalDate): Instant = d.atTime(LocalTime.of(23, 59, 59)).atZone(KST).toInstant()

}
