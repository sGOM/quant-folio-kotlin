package com.quantfolio.common

import java.time.Clock
import java.time.LocalDate
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
}
