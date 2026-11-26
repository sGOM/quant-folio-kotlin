package com.quantfolio.worker

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 수동 OpenDART 백필 트리거. `quantfolio.dart-backfill.from-year=2015 [quantfolio.dart-backfill.call-budget=10000]` 로 워커를 띄우면
 * 기동 직후 한 번 실행한다(예산이 소진되면 보고 후 계속 가동 - 다음 실행이 원장으로 이어받는다).
 */
@Component
@ConditionalOnProperty("quantfolio.dart-backfill.from-year")
class DartBackfillRunner(
    private val jobs: WorkerJobs,
    @Value("\${quantfolio.dart-backfill.from-year}") private val fromYear: Int,
    @Value("\${quantfolio.dart-backfill.call-budget:10000}") private val callBudget: Int,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val log = LoggerFactory.getLogger(javaClass)
        // 여기서 예외가 새면 워커 기동이 실패해 재기동이 반복되고 백업 등 다른 작업도 멈춘다 - 로그만 남기고 계속 간다.
        try {
            log.info("수동 OpenDART 백필 결과: {}", jobs.dartBackfill(fromYear, callBudget))
        } catch (e: Exception) {
            log.error("수동 OpenDART 백필 실패 [{}~]", fromYear, e)
        }
    }
}
