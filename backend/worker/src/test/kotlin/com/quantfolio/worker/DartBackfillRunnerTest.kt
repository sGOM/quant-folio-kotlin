package com.quantfolio.worker

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments

class DartBackfillRunnerTest {
    @Test
    fun `unexpected exception does not fail worker startup`() {
        val jobs = Mockito.mock(WorkerJobs::class.java) { throw IllegalStateException("db down") }
        DartBackfillRunner(jobs, 2015, 10).run(DefaultApplicationArguments())
    }
}
