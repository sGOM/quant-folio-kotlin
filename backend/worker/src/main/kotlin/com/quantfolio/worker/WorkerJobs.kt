package com.quantfolio.worker

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.backtest.SlippageCalibration
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.common.withLockOrElse
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.market.official.DividendIngest
import com.quantfolio.market.official.OfficialIngest
import com.quantfolio.news.NewsIngestor
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyFloat
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * 배치 작업 본체(원본 `worker/tasks.py`). 스케줄은 [WorkerSchedule]. 각 작업은 결과 맵을 돌려주고,
 * 사람이 알아야 하는 실패는 [AlertPublisher] 로 넘긴다(dedup 창은 원본 값 그대로).
 */
@Component
class WorkerJobs(
    private val official: OfficialIngest,
    private val dividendIngest: DividendIngest,
    private val kisMaster: KisStockMaster,
    private val fills: FillQualityReport,
    private val news: NewsIngestor,
    private val alerts: AlertPublisher,
    private val jdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 원본 `date.today()` — 컨테이너 UTC 날짜(보존, migration-notes #1 과 같은 성격). */
    private fun utcToday() = MarketClock.todayUtcLegacy(clock)

    private fun pct(ratio: Double) = pyFixed(ratio * 100, 0) + "%"

    /**
     * KRX Open API 일일 적재(평일 07:00~20:00 KST 30분마다 - 끝난 날은 원장만 읽고 넘어간다): 직전 평일 + 미확정 최근일. 실패가 있으면 warning(20h dedup).
     * 휴장일은 0행이라 7일 뒤에야 확정된다 — 그 전엔 매일 재조회하므로 실패 알림은 '실패' 항목만 센다.
     */
    fun ingestOfficialDaily(): Map<String, Any?> {
        var day = MarketClock.todayKst(clock).minusDays(1)
        while (day.dayOfWeek == DayOfWeek.SATURDAY || day.dayOfWeek == DayOfWeek.SUNDAY) day = day.minusDays(1)
        val r = official.daily(day)
        log.info("공식 일일 적재 [{}] {}", day, r)
        if (r.failed.isNotEmpty()) {
            alerts.publish(
                null, 0, AlertSeverity.warning,
                "KRX Open API 일일 적재 실패 ${r.failed.size}건: " + r.failed.take(5).joinToString("; "),
                "official_ingest_failure", dedupWindowHours = 20.0,
            )
        }
        return linkedMapOf("date" to day.toString(), "done" to r.daysDone, "calls" to r.calls, "failed" to r.failed)
    }

    /**
     * OpenDART 정기보고서 로컬 적재(매일 05:00 KST 주기 작업 + `quantfolio.dart-backfill.*` 수동 실행). 엔진·백테스트는 로컬 DART 만 읽으므로
     * 이 작업이 돌지 않으면 quality·growth 점수 입력이 비어 커버리지 관문이 막는다. 이미 확정된 조합은 호출 없이 넘어가고
     * 한도(020)·인증 오류면 즉시 멈춘다 - 다음 실행이 원장으로 이어받는다. 주기·수동이 겹치지 않게 Redis 락.
     */
    fun dartBackfill(fromYear: Int, callBudget: Int): Map<String, Any?> =
        redis.withLockOrElse(RedisKeys.DART_BACKFILL_LOCK, Duration.ofSeconds(RedisKeys.DART_BACKFILL_LOCK_TTL_SECONDS), busy = {
            log.info("OpenDART 백필 건너뜀 - 다른 실행이 진행 중")
            linkedMapOf<String, Any?>("busy" to true)
        }) {
            val r = official.dartBackfill(fromYear, callBudget)
            log.info("OpenDART 백필 [{}~] {}", fromYear, r)
            if (r.failed.isNotEmpty()) {
                alerts.publish(
                    null, 0, AlertSeverity.warning,
                    "OpenDART 재무 백필 실패 ${r.failed.size}건: " + r.failed.take(5).joinToString("; "),
                    "dart_backfill_failure", dedupWindowHours = 20.0,
                )
            }
            linkedMapOf<String, Any?>("done" to r.daysDone, "calls" to r.calls, "stoppedByBudget" to r.stoppedByBudget, "failed" to r.failed)
        }

    /** 수동 백필(`quantfolio.backfill.*` 설정으로 [BackfillRunner] 가 부른다). */
    fun backfillOfficial(from: LocalDate, to: LocalDate, callBudget: Int): Map<String, Any?> {
        val r = official.backfill(from, to, callBudget)
        log.info("공식 백필 [{}~{}] {}", from, to, r)
        return linkedMapOf("done" to r.daysDone, "calls" to r.calls, "stoppedByBudget" to r.stoppedByBudget, "failed" to r.failed)
    }

    /** 실패하면 warning(dedup 기본 24h — 원본 값 그대로) 후 예외 전파 — 스케줄러 로그에도 남는다. */
    private fun <T> withOutageAlert(code: String, label: String, dedupHours: Double = 24.0, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        alerts.publish(null, 0, AlertSeverity.warning, "$label: ${e.message}", code, dedupWindowHours = dedupHours)
        throw e
    }

    /** 주식배당정보 전체 이력 적재(주 1회). 실패하면 warning(6일 dedup — 주간 재발이 창에 걸리지 않게) 후 예외 전파. */
    fun ingestDividends(): Map<String, Any?> {
        val r = withOutageAlert("dividend_ingest_failure", "배당 이력 적재 실패", dedupHours = 144.0) { dividendIngest.run() }
        return linkedMapOf("pages" to r.pages, "fetched" to r.fetched, "skipped" to r.skipped, "stored" to r.stored, "conflicts" to r.conflicts)
    }

    fun snapshotKisStockMaster(): Map<String, Any?> =
        linkedMapOf<String, Any?>("snapshot_symbols" to withOutageAlert("kis_master_outage", "KIS 종목마스터 적재 실패") { kisMaster.snapshot() })
            .also { log.info("KIS 종목마스터 스냅샷 적재 완료: {}", it) }

    /**
     * 체결 정합 주간 점검(월 09:00): 최근 90일 체결이 있는 (사용자, 전략)마다 리포트를 다시 계산해 RED 면 critical,
     * RED 가 아니어도 캘리브레이션 제안이 있으면 warning. 표본 부족은 등급이 INSUFFICIENT 라 자연히 조용하다.
     */
    @Suppress("UNCHECKED_CAST")
    fun checkFillQualityDrift(): Map<String, Any?> {
        val dTo = utcToday()
        val dFrom = dTo.minusDays(FILL_QUALITY_WINDOW_DAYS)
        val pairs = jdbc.query(
            "select distinct user_id, strategy_id from orders where strategy_id is not null and status in ('partial', 'filled') and created_at >= :f",
            MapSqlParameterSource("f", Timestamp.from(dFrom.atStartOfDay().toInstant(ZoneOffset.UTC))),
        ) { rs, _ -> rs.getInt(1) to rs.getInt(2) }
        val alerted = mutableListOf<Int>()
        val proposed = mutableListOf<Int>()
        for ((userId, strategyId) in pairs) {
            val report = try {
                fills.report(userId, dFrom, dTo, strategyId)
            } catch (e: Exception) {
                log.warn("체결 정합 점검 실패(전략 {}): {}", strategyId, e.message)
                continue
            }
            val grades = report["grades"] as? Map<String, Any?> ?: emptyMap()
            val proposal = SlippageCalibration.propose(report)
            val propTxt = proposal?.let { " 제안 slippage_bps=${pyFloat(it.proposedBps)}(실측 중앙값 ${pyFixed(it.observedMedianBps, 1)}bp, 표본 ${it.sampleSize})" } ?: ""
            if (grades["m1_exec"] == "RED" || grades["m3_total"] == "RED") {
                val m1Mean = ((report["m1_exec"] as? Map<String, Any?>)?.get("all") as? Map<String, Any?>)?.get("mean") as Double?
                val assumption = (report["assumptions"] as? Map<String, Any?>)?.get("backtest_slip_bps") as Double?
                val drag = (report["annualized_drag"] as? Map<String, Any?>)?.get("drag_diff_pct_per_yr") as Double?
                val mean = m1Mean?.let { pyFloat(BigDecimal(it).setScale(1, RoundingMode.HALF_EVEN).toDouble()) } ?: "-"
                val msg = "전략 $strategyId 체결 정합 이탈 — M1(실행 슬리피지)=${grades["m1_exec"]} M3(총 정합)=${grades["m3_total"]} 실측 ${mean}bp " +
                    "vs 가정 ${assumption?.let { pyFloat(it) } ?: "None"}bp(최근 ${FILL_QUALITY_WINDOW_DAYS}일" +
                    (drag?.let { ", 연환산 드래그차 ${pyFixed(it, 2)}%p/yr" } ?: "") + ").$propTxt"
                alerts.publish(userId, strategyId, AlertSeverity.critical, msg, "fill_quality_drift")
                alerted += strategyId
                if (proposal != null) proposed += strategyId
            } else if (proposal != null) {
                alerts.publish(
                    userId, strategyId, AlertSeverity.warning,
                    "전략 $strategyId 슬리피지 캘리브레이션 제안 — 현재 ${pyFixed(proposal.currentBps, 1)}bp →$propTxt. 승인 시 config 에 반영됩니다.",
                    "slippage_calibration_proposed",
                )
                proposed += strategyId
            }
        }
        val result = linkedMapOf<String, Any?>("checked" to pairs.size, "alerted" to alerted, "proposed" to proposed)
        log.info("체결 정합 정기 점검 완료: {}", result)
        return result
    }

    /** 알림 보존정책: 읽음 90일·미확인 180일 초과 삭제(04:00). 실패는 warning 후 전파. */
    fun cleanupOldAlerts(): Map<String, Any?> {
        val now = clock.instant()
        val deleted = withOutageAlert("alert_cleanup_failed", "alerts 보존정책 정리 실패", dedupHours = 20.0) { // 원본 20h — 매일 04:00 재발이 24h 창에 걸리지 않게
            jdbc.update(
                "delete from alerts where (is_read and created_at < :r) or (not is_read and created_at < :u)",
                MapSqlParameterSource("r", Timestamp.from(now.minus(Duration.ofDays(90)))).addValue("u", Timestamp.from(now.minus(Duration.ofDays(180)))),
            )
        }
        log.info("alerts 보존정책 정리 완료: {}건 삭제", deleted)
        return mapOf("deleted" to deleted)
    }

    /** 뉴스 수집(매시 10분) + 60일 보존 정리. 모든 피드 실패면 warning(6h dedup). */
    fun ingestNews(): Map<String, Any?> {
        val result = LinkedHashMap<String, Any?>(news.ingest())
        result["purged"] = news.purge()
        if (NewsIngestor.FEEDS.isNotEmpty() && result["feeds_ok"] == 0) {
            alerts.publish(
                null, 0, AlertSeverity.warning,
                "경제 뉴스 수집이 전체 실패했습니다(모든 피드 응답 없음). 네트워크 또는 피드 URL(NewsIngestor.FEEDS)을 확인하세요.",
                "news_ingest_failure", dedupWindowHours = 6.0,
            )
        }
        return result
    }

    private companion object {
        const val FAILURE_ALERT_RATIO = 0.10
        const val FILL_QUALITY_WINDOW_DAYS = 90L
    }
}
