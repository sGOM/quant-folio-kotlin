package com.quantfolio.worker

import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component

/**
 * 배치 스케줄(원본 `worker/celery_app.py` beat_schedule 와 같은 시각, KST). 작업 예외는 여기서 로그로 끝낸다 —
 * 사람이 알아야 하는 실패는 각 작업이 이미 알림으로 넘겼다. `quantfolio.scheduling.enabled=false` 면 꺼진다(테스트).
 */
@Component
@ConditionalOnProperty("quantfolio.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class WorkerSchedule(private val jobs: WorkerJobs, private val backup: DbBackup) {
    private val log = LoggerFactory.getLogger(javaClass)

    private fun run(name: String, block: () -> Any?) {
        try {
            log.info("배치 시작: {}", name)
            log.info("배치 종료: {} {}", name, block())
        } catch (e: Exception) {
            log.error("배치 실패: {}", name, e)
        }
    }

    // 공개 시각은 Task 1 실측(차단 해제 후) 으로 확인한다. 그 전까지 보수적으로 다음 날 10:00.
    @Scheduled(cron = "0 0 10 * * *", zone = KST)
    fun ingestOfficialDaily() = run("ingest_official_daily") { jobs.ingestOfficialDaily() }

    @Scheduled(cron = "0 0 7 * * SUN", zone = KST)
    fun ingestDividends() = run("ingest_dividends") { jobs.ingestDividends() }

    @Scheduled(cron = "0 40 18 * * *", zone = KST)
    fun snapshotKisStockMaster() = run("snapshot_kis_stock_master") { jobs.snapshotKisStockMaster() }

    @Scheduled(cron = "0 0 9 * * MON", zone = KST)
    fun checkFillQualityDrift() = run("check_fill_quality_drift") { jobs.checkFillQualityDrift() }

    @Scheduled(cron = "0 0 3 * * *", zone = KST)
    fun backupDatabase() = run("backup_database") { backup.backup() }

    @Scheduled(cron = "0 0 9 * * *", zone = KST)
    fun checkBackupFreshness() = run("check_backup_freshness") { backup.checkFreshness() }

    @Scheduled(cron = "0 10 * * * *", zone = KST)
    fun ingestNews() = run("ingest_news") { jobs.ingestNews() }

    @Scheduled(cron = "0 0 4 * * *", zone = KST)
    fun cleanupOldAlerts() = run("cleanup_old_alerts") { jobs.cleanupOldAlerts() }

    private companion object {
        const val KST = "Asia/Seoul"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("quantfolio.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig
