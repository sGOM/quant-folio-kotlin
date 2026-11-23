package com.quantfolio.worker

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments
import java.time.LocalDate

class BackfillRunnerTest {
    @Test
    fun `unexpected exception does not fail worker startup`() {
        val jobs = Mockito.mock(WorkerJobs::class.java) { throw IllegalStateException("db down") }
        BackfillRunner(jobs, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 5), 10, false).run(DefaultApplicationArguments())
    }

    @Test
    fun `indexes-only runs the index backfill`() {
        val jobs = Mockito.mock(WorkerJobs::class.java)
        val from = LocalDate.of(2024, 1, 2)
        val to = LocalDate.of(2024, 1, 5)
        BackfillRunner(jobs, from, to, 10, true).run(DefaultApplicationArguments())
        Mockito.verify(jobs).backfillOfficialIndexes(from, to, 10)
        Mockito.verify(jobs, Mockito.never()).backfillOfficial(from, to, 10)
    }
}
