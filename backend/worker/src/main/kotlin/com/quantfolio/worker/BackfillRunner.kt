package com.quantfolio.worker

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * 수동 백필 트리거. `quantfolio.backfill.from=2024-01-02 quantfolio.backfill.to=2024-12-30 [quantfolio.backfill.call-budget=2000]`
 * 로 워커를 띄우면 기동 직후 한 번 실행한다(예산이 소진되면 보고 후 계속 가동 — 다음 실행이 원장으로 이어받는다).
 */
@Component
@ConditionalOnProperty("quantfolio.backfill.from")
class BackfillRunner(
    private val jobs: WorkerJobs,
    @Value("\${quantfolio.backfill.from}") private val from: LocalDate,
    @Value("\${quantfolio.backfill.to}") private val to: LocalDate,
    @Value("\${quantfolio.backfill.call-budget:2000}") private val callBudget: Int,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        LoggerFactory.getLogger(javaClass).info("수동 백필 결과: {}", jobs.backfillOfficial(from, to, callBudget))
    }
}
