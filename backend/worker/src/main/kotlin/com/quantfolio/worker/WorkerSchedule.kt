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

    // KRX Open API 공개 시각은 미실측(Task 1 에서 확정) - 그래서 한 번이 아니라 평일 07:00~20:00 30분마다 부른다. 목표일(직전 평일)이 끝나면
    // daily() 는 원장만 읽고 API 를 부르지 않는다(5개 서비스 확정 날은 건너뜀). 최근 휴장 0행 같은 미확정 날은 7일 확정 전까지 매번 다시 받는다.
    @Scheduled(cron = "0 0,30 7-19 * * MON-FRI", zone = KST)
    @Scheduled(cron = "0 0 20 * * MON-FRI", zone = KST)
    fun ingestOfficialDaily() = run("ingest_official_daily") { jobs.ingestOfficialDaily() }

    // 매일 05:00 KST - 사용자 조회가 적은 시간대, 03:00 백업 뒤. 초기 적재는 수만 호출이라 며칠에 걸쳐 이어받고, 끝나면 원장 조회만 한다.
    // 예산 10,000 은 OpenDART 일 한도(공식 문서 기준 20,000, 미실측)의 절반 - 나머지는 웹의 원격 조회용으로 남긴다.
    @Scheduled(cron = "0 0 5 * * *", zone = KST)
    fun backfillDart() = run("backfill_dart") { jobs.dartBackfill(2015, DART_DAILY_BUDGET) }

    @Scheduled(cron = "0 0 7 * * SUN", zone = KST)
    fun ingestDividends() = run("ingest_dividends") { jobs.ingestDividends() }

    @Scheduled(cron = "0 40 18 * * *", zone = KST)
    fun snapshotKisStockMaster() = run("snapshot_kis_stock_master") { jobs.snapshotKisStockMaster() }

    @Scheduled(cron = "0 0 9 * * MON", zone = KST)
    fun checkFillQualityDrift() = run("check_fill_quality_drift") { jobs.checkFillQualityDrift() }

    @Scheduled(cron = "0 0 3 * * *", zone = KST)
    fun backupDatabase() = run("backup_database") { backup.backup() }

    @Scheduled(cron = "0 10 * * * *", zone = KST)
    fun ingestNews() = run("ingest_news") { jobs.ingestNews() }

    @Scheduled(cron = "0 0 4 * * *", zone = KST)
    fun cleanupOldAlerts() = run("cleanup_old_alerts") { jobs.cleanupOldAlerts() }

    private companion object {
        const val KST = "Asia/Seoul"
        const val DART_DAILY_BUDGET = 10_000
    }
}

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty("quantfolio.scheduling.enabled", havingValue = "true", matchIfMissing = true)
class SchedulingConfig
