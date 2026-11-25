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
 * `quantfolio.backfill.rebuild-adjusted=true` 면 백필 대신 전 종목 수정주가 재계산만 한다(from·to 는 무시되지만 켜는 조건이라 필요).
 */
@Component
@ConditionalOnProperty("quantfolio.backfill.from")
class BackfillRunner(
    private val jobs: WorkerJobs,
    @Value("\${quantfolio.backfill.from}") private val from: LocalDate,
    @Value("\${quantfolio.backfill.to}") private val to: LocalDate,
    @Value("\${quantfolio.backfill.call-budget:2000}") private val callBudget: Int,
    @Value("\${quantfolio.backfill.indexes-only:false}") private val indexesOnly: Boolean = false,
    @Value("\${quantfolio.backfill.rebuild-adjusted:false}") private val rebuildAdjusted: Boolean = false,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val log = LoggerFactory.getLogger(javaClass)
        // 여기서 예외가 새면 워커 기동이 실패해 재기동이 반복되고 백업 등 다른 작업도 멈춘다 — 로그만 남기고 계속 간다.
        try {
            log.info("수동 백필 결과: {}", when {
                rebuildAdjusted -> jobs.rebuildAllAdjusted()
                indexesOnly -> jobs.backfillOfficialIndexes(from, to, callBudget)
                else -> jobs.backfillOfficial(from, to, callBudget)
            })
        } catch (e: Exception) {
            log.error("수동 백필 실패 [{}~{}]", from, to, e)
        }
    }
}
