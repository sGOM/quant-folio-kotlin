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

    @Scheduled(cron = "0 30 18 * * *", zone = KST)
    fun ingestDailyOhlcv() = run("ingest_daily_ohlcv") { jobs.ingestDailyOhlcv() }

    @Scheduled(cron = "0 40 18 * * *", zone = KST)
    fun snapshotKisStockMaster() = run("snapshot_kis_stock_master") { jobs.snapshotKisStockMaster() }

    @Scheduled(cron = "0 50 18 * * *", zone = KST)
    fun ingestDailySnapshots() = run("ingest_daily_snapshots") { jobs.ingestDailySnapshots() }

    @Scheduled(cron = "0 0 9 * * MON", zone = KST)
    fun checkFillQualityDrift() = run("check_fill_quality_drift") { jobs.checkFillQualityDrift() }

    @Scheduled(cron = "0 0 19 1 1,4,7,10 *", zone = KST)
    fun snapshotSectorMap() = run("snapshot_sector_map") { jobs.snapshotSectorMap() }

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
