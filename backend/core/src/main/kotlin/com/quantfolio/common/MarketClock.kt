package com.quantfolio.common

import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
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

    /**
     * 원본 `date.today()` — 컨테이너(UTC) 날짜라 KST 00~09시엔 전날이다. **새 코드에 쓰지 말 것.**
     * 원본 동작 보존을 위해 일부러 남긴 곳만 쓴다(docs/migration-notes.md #1·#6~#8). 거래일은 [todayKst].
     */
    fun todayUtcLegacy(clock: Clock): LocalDate = LocalDate.ofInstant(clock.instant(), ZoneOffset.UTC)
}
