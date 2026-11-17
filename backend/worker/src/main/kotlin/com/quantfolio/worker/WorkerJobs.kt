package com.quantfolio.worker

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.backtest.SlippageCalibration
import com.quantfolio.common.MarketClock
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.market.krx.KrxIndexService
import com.quantfolio.market.krx.SnapshotSource
import com.quantfolio.market.ohlcv.OhlcvLoader
import com.quantfolio.metrics.MetricsCommon
import com.quantfolio.news.NewsIngestor
import com.quantfolio.quant.pyFixed
import com.quantfolio.quant.pyFloat
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset

/**
 * 배치 작업 본체(원본 `worker/tasks.py`). 스케줄은 [WorkerSchedule]. 각 작업은 결과 맵을 돌려주고,
 * 사람이 알아야 하는 실패는 [AlertPublisher] 로 넘긴다(dedup 창은 원본 값 그대로).
 */
@Component
class WorkerJobs(
    private val universe: UniverseBuilder,
    private val loader: OhlcvLoader,
    private val snapshots: SnapshotSource,
    private val kisMaster: KisStockMaster,
    private val krx: KrxIndexService,
    private val fills: FillQualityReport,
    private val news: NewsIngestor,
    private val alerts: AlertPublisher,
    private val jdbc: NamedParameterJdbcTemplate,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 원본 `date.today()` — 컨테이너 UTC 날짜(보존, migration-notes #1 과 같은 성격). */
    private fun utcToday() = MarketClock.todayUtcLegacy(clock)

    private fun pct(ratio: Double) = pyFixed(ratio * 100, 0) + "%"

    /** KOSPI200 + 등록 전략 종목의 일봉을 price_ticks 에 증분 적재(18:30). 실패율 10% 초과면 warning. */
    fun ingestDailyOhlcv(): Map<String, Any?> {
        val end = utcToday()
        val start = end.minusDays(LOOKBACK_DAYS)
        val codes = universe.build()
        val failed = mutableListOf<String>()
        for (sym in codes) {
            try {
                loader.ensureCoverage(sym, start, end)
            } catch (e: Exception) {
                failed += sym
                log.warn("일봉 적재 실패({}): {}", sym, e.message)
            }
        }
        val result = linkedMapOf<String, Any?>("universe" to codes.size, "ok" to codes.size - failed.size, "failed" to failed)
        log.info("일봉 로컬 적재 완료: {}", result)
        if (codes.isNotEmpty() && failed.size.toDouble() / codes.size > FAILURE_ALERT_RATIO) {
            alerts.publish(
                null, 0, AlertSeverity.warning,
                "야간 일봉 적재 실패율 ${failed.size}/${codes.size}(${pct(failed.size.toDouble() / codes.size)})가 임계치를 초과했습니다: " +
                    failed.take(10).joinToString(", ") + (if (failed.size > 10) " 외" else ""),
                "ohlcv_ingest_failure_rate", dedupWindowHours = 20.0,
            )
        }
        return result
    }

    /**
     * 전날(KST) 확정분 선적재(18:50): 펀더멘털·시총·전종목 OHLCV(시장별)·지수 OHLCV(400거래일). 한 단계 실패는 계속 진행,
     * 실패율 10% 초과면 warning — 7단계뿐이라 1건 실패도 임계를 넘는다(하루치 데이터 종류 하나 전체라 의도된 동작).
     */
    fun ingestDailySnapshots(): Map<String, Any?> {
        val day = MarketClock.todayKst(clock).minusDays(1)
        val ymd = day.toString().replace("-", "")
        val mkts = listOf("KOSPI", "KOSDAQ")
        val histStart = MetricsCommon.approxStart(day, 400, buffer = 30)
        val steps = mutableListOf<Pair<String, () -> Any?>>(
            "펀더멘털" to { snapshots.fundamentals(day, mkts) },
            "시가총액" to { snapshots.marketCap(day, mkts) },
        )
        for (m in mkts) steps += "전종목OHLCV($m)" to { snapshots.marketOhlcvSnapshot(day, m) }
        for (code in listOf("1001", "2001", "1028")) steps += "지수OHLCV($code)" to { snapshots.indexOhlcv(histStart, day, code) }
        var ok = 0
        val failedNames = mutableListOf<String>()
        for ((name, call) in steps) {
            try {
                call()
                ok++
            } catch (e: Exception) {
                failedNames += name
                log.warn("선적재 실패 [{}] {}: {}", ymd, name, e.message)
            }
        }
        log.info("일별 스냅샷 선적재 완료 [{}] ok={} failed={}", ymd, ok, failedNames.size)
        val total = ok + failedNames.size
        if (total > 0 && failedNames.size.toDouble() / total > FAILURE_ALERT_RATIO) {
            runCatching {
                alerts.publish(
                    null, 0, AlertSeverity.warning,
                    "야간 스냅샷 선적재 실패율 ${failedNames.size}/$total(${pct(failedNames.size.toDouble() / total)})가 임계치를 초과했습니다: ${failedNames.joinToString(", ")}",
                    "snapshot_ingest_failure_rate", dedupWindowHours = 20.0,
                )
            }.onFailure { log.warn("스냅샷 선적재 실패 알림 발행 중 오류: {}", it.message) }
        }
        return linkedMapOf("date" to ymd, "ok" to ok, "failed" to failedNames.size)
    }

    /** 실패하면 warning(dedup 기본 24h — 원본 값 그대로) 후 예외 전파 — 스케줄러 로그에도 남는다. */
    private fun <T> withOutageAlert(code: String, label: String, dedupHours: Double = 24.0, block: () -> T): T = try {
        block()
    } catch (e: Exception) {
        alerts.publish(null, 0, AlertSeverity.warning, "$label: ${e.message}", code, dedupWindowHours = dedupHours)
        throw e
    }

    fun snapshotKisStockMaster(): Map<String, Any?> =
        linkedMapOf<String, Any?>("snapshot_symbols" to withOutageAlert("kis_master_outage", "KIS 종목마스터 적재 실패") { kisMaster.snapshot() })
            .also { log.info("KIS 종목마스터 스냅샷 적재 완료: {}", it) }

    fun snapshotSectorMap(): Map<String, Any?> =
        linkedMapOf<String, Any?>("snapshot_symbols" to withOutageAlert("sector_map_outage", "업종분류 분기 스냅샷 적재 실패") { krx.snapshotSectorMap() })
            .also { log.info("업종분류 분기 스냅샷 적재 완료: {}", it) }

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
        const val LOOKBACK_DAYS = 500L
        const val FAILURE_ALERT_RATIO = 0.10
        const val FILL_QUALITY_WINDOW_DAYS = 90L
    }
}
