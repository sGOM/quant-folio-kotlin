package com.quantfolio.worker

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments
import java.time.LocalDate

class BackfillRunnerTest {
    @Test
    fun `unexpected exception does not fail worker startup`() {
        val jobs = Mockito.mock(WorkerJobs::class.java) { throw IllegalStateException("db down") }
        BackfillRunner(jobs, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 5), 10).run(DefaultApplicationArguments())
    }
}
