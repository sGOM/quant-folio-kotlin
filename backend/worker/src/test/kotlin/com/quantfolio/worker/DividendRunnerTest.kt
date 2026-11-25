package com.quantfolio.worker

import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.boot.DefaultApplicationArguments

class DividendRunnerTest {
    @Test
    fun `runs ingest once`() {
        val jobs = Mockito.mock(WorkerJobs::class.java)
        Mockito.`when`(jobs.ingestDividends()).thenReturn(mapOf("stored" to 1))
        DividendRunner(jobs).run(DefaultApplicationArguments())
        Mockito.verify(jobs, Mockito.times(1)).ingestDividends()
    }

    @Test
    fun `unexpected exception does not fail worker startup`() {
        val jobs = Mockito.mock(WorkerJobs::class.java) { throw IllegalStateException("db down") }
        DividendRunner(jobs).run(DefaultApplicationArguments())
    }
}
