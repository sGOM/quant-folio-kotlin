package com.quantfolio.worker

import org.junit.jupiter.api.Test
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.scheduling.support.CronExpression
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.test.assertEquals

class WorkerScheduleTest {
    private val kst = ZoneId.of("Asia/Seoul")

    private fun firings(method: String, day: LocalDate): List<LocalTime> {
        val crons = WorkerSchedule::class.java.getMethod(method).getAnnotationsByType(Scheduled::class.java).map { CronExpression.parse(it.cron) }
        val start = ZonedDateTime.of(day, LocalTime.MIDNIGHT, kst).minusSeconds(1)
        return crons.flatMap { c -> generateSequence(c.next(start)) { c.next(it) }.takeWhile { it.toLocalDate() == day }.map { it.toLocalTime() }.toList() }.sorted()
    }

    @Test
    fun `official daily ingest runs every 30 minutes from 07 to 20 on weekdays only`() {
        val expected = generateSequence(LocalTime.of(7, 0)) { it.plusMinutes(30) }.takeWhile { it <= LocalTime.of(20, 0) }.toList()
        assertEquals(expected, firings("ingestOfficialDaily", LocalDate.of(2026, 11, 24))) // 화
        assertEquals(emptyList(), firings("ingestOfficialDaily", LocalDate.of(2026, 11, 28))) // 토
    }

    @Test
    fun `dart backfill runs once a day off hours`() {
        assertEquals(listOf(LocalTime.of(5, 0)), firings("backfillDart", LocalDate.of(2026, 11, 24)))
    }
}
