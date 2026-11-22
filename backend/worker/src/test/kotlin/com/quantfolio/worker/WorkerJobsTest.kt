package com.quantfolio.worker

import com.quantfolio.backtest.FillQualityReport
import com.quantfolio.alert.AlertPublisher
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.Alert
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.market.kis.KisStockMaster
import com.quantfolio.market.official.DividendIngest
import com.quantfolio.market.official.DividendIngestReport
import com.quantfolio.market.official.IngestReport
import com.quantfolio.market.official.OfficialIngest
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.news.NewsIngestor
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyList
import org.mockito.ArgumentMatchers.eq
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willThrow
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.zip.GZIPInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(properties = ["quantfolio.scheduling.enabled=false", "quantfolio.backup-dir=build/test-backups"])
@Import(ContainersConfig::class)
class WorkerJobsTest(
    @Autowired private val jobs: WorkerJobs,
    @Autowired private val backup: DbBackup,
    @Autowired private val alerts: AlertRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val redis: StringRedisTemplate,
) {
    @MockitoBean lateinit var official: OfficialIngest
    @MockitoBean lateinit var dividendIngest: DividendIngest
    @MockitoBean lateinit var kisMaster: KisStockMaster
    @MockitoBean lateinit var fills: FillQualityReport
    @MockitoBean lateinit var news: NewsIngestor
    @MockitoSpyBean lateinit var alertPublisher: AlertPublisher

    private fun <T> anyK(c: Class<T>): T = any(c) ?: when (c) { LocalDate::class.java -> LocalDate.MIN as T; else -> throw IllegalStateException() }

    private fun <T> eqK(v: T): T = eq(v) ?: v

    private fun latest(code: String) = alerts.findAll().filter { it.code == code }.maxByOrNull { it.id!! }

    @BeforeEach
    fun clean() {
        alerts.findAll().filter { it.userId == null }.forEach { alerts.delete(it) }
    }

    @Test
    fun officialDailyAlertsOnFailures() {
        given(official.daily(anyK(LocalDate::class.java))).willReturn(IngestReport(1, 5, false, listOf("2026-01-05: 503")))
        val r = jobs.ingestOfficialDaily()
        assertEquals(1, r["done"])
        assertEquals("KRX Open API 일일 적재 실패 1건: 2026-01-05: 503", latest("official_ingest_failure")!!.message)
    }

    @Test
    fun officialDailyIsQuietWithoutFailures() {
        given(official.daily(anyK(LocalDate::class.java))).willReturn(IngestReport(1, 5, false, emptyList()))
        jobs.ingestOfficialDaily()
        assertEquals(null, latest("official_ingest_failure"))
    }

    @Test
    fun dividendIngestReportsAndAlertsThenRethrowsOnFailure() {
        given(dividendIngest.run()).willReturn(DividendIngestReport(8, 71_663, 100, 60_000, 2))
        assertEquals(60_000, jobs.ingestDividends()["stored"])
        assertEquals(null, latest("dividend_ingest_failure"))
        given(dividendIngest.run()).willThrow(SourceAuthError("datagokr", "키 거부"))
        assertThrows<SourceAuthError> { jobs.ingestDividends() }
        assertTrue(latest("dividend_ingest_failure")!!.message.startsWith("배당 이력 적재 실패"))
    }

    @Test
    fun outageJobsAlertThenRethrow() {
        given(kisMaster.snapshot(null)).willThrow(IllegalStateException("zip 실패"))
        assertThrows<IllegalStateException> { jobs.snapshotKisStockMaster() }
        assertEquals("KIS 종목마스터 적재 실패: zip 실패", latest("kis_master_outage")!!.message)
    }

    @Test
    fun fillQualityDriftAlertsRedAndProposals() {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x"))
        val red = strategies.save(Strategy(u.id!!, "red", config = mapOf("type" to "rsi")))
        val prop = strategies.save(Strategy(u.id!!, "prop", config = mapOf("type" to "rsi")))
        for (s in listOf(red, prop)) orders.save(Order(u.id!!, s.id, "005930", "buy", BigDecimal.ONE, null, status = OrderStatus.filled, idempotencyKey = UUID.randomUUID().toString()))
        fun report(m1: String, median: Double, mean: Double) = mutableMapOf<String, Any?>(
            "grades" to mapOf("m1_exec" to m1, "m3_total" to "GREEN"),
            "m1_exec" to mapOf("all" to mapOf("n" to 40, "median" to median, "mean" to mean)),
            "assumptions" to mapOf("backtest_slip_bps" to 5.0),
            "annualized_drag" to mapOf("drag_diff_pct_per_yr" to 1.234),
        )
        given(fills.report(eq(u.id!!), anyK(LocalDate::class.java), anyK(LocalDate::class.java), eq(red.id), any(), any(), anyInt(), eq(false))).willReturn(report("RED", 20.0, 18.25))
        given(fills.report(eq(u.id!!), anyK(LocalDate::class.java), anyK(LocalDate::class.java), eq(prop.id), any(), any(), anyInt(), eq(false))).willReturn(report("GREEN", 6.6, 6.0))
        val r = jobs.checkFillQualityDrift()
        assertTrue(red.id in (r["alerted"] as List<*>))
        val critical = alerts.findAll().single { it.code == "fill_quality_drift" && it.strategyId == red.id }
        assertEquals(
            "전략 ${red.id} 체결 정합 이탈 — M1(실행 슬리피지)=RED M3(총 정합)=GREEN 실측 18.2bp vs 가정 5.0bp(최근 90일, 연환산 드래그차 1.23%p/yr). 제안 slippage_bps=8.0(실측 중앙값 20.0bp, 표본 40)",
            critical.message,
        )
        assertEquals(AlertSeverity.critical, critical.severity)
        val warn = alerts.findAll().single { it.code == "slippage_calibration_proposed" && it.strategyId == prop.id }
        assertEquals("전략 ${prop.id} 슬리피지 캘리브레이션 제안 — 현재 5.0bp → 제안 slippage_bps=6.6(실측 중앙값 6.6bp, 표본 40). 승인 시 config 에 반영됩니다.", warn.message)
    }

    @Test
    fun alertRetentionAndNewsFailure() {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x"))
        val now = Instant.now()
        val readOld = alerts.save(Alert(u.id, 1, AlertSeverity.warning, null, "r", isRead = true, createdAt = now.minus(91, ChronoUnit.DAYS)))
        val unreadMid = alerts.save(Alert(u.id, 1, AlertSeverity.warning, null, "u", isRead = false, createdAt = now.minus(91, ChronoUnit.DAYS)))
        val unreadOld = alerts.save(Alert(u.id, 1, AlertSeverity.warning, null, "o", isRead = false, createdAt = now.minus(181, ChronoUnit.DAYS)))
        jobs.cleanupOldAlerts()
        assertFalse(alerts.existsById(readOld.id!!))
        assertTrue(alerts.existsById(unreadMid.id!!))
        assertFalse(alerts.existsById(unreadOld.id!!))

        given(news.ingest()).willReturn(mapOf("feeds_ok" to 0, "feeds_failed" to listOf("a"), "fetched" to 0, "inserted" to 0))
        given(news.purge(NewsIngestor.RETENTION_DAYS)).willReturn(3)
        assertEquals(3, jobs.ingestNews()["purged"])
        assertTrue(latest("news_ingest_failure")!!.message.startsWith("경제 뉴스 수집이 전체 실패했습니다"))
    }

    @Test
    fun backupWritesGzipPrunesAndRecordsSuccess() {
        val dir = Path.of("build/test-backups")
        dir.toFile().deleteRecursively() // 이전 실행 잔여 백업이 보존기한을 넘기면 함께 정리돼 removed 가 달라진다
        Files.createDirectories(dir)
        val stale = Files.writeString(dir.resolve("quant_20000101_000000.sql.gz"), "old")
        Files.setLastModifiedTime(stale, FileTime.from(Instant.now().minus(15, ChronoUnit.DAYS)))
        backup.dumper = Dumper { _, out -> out.write("-- dump".toByteArray()) }
        val r = backup.backup()
        assertEquals(true, r["ok"])
        val path = Path.of(r["path"] as String)
        assertEquals("-- dump", GZIPInputStream(Files.newInputStream(path)).use { String(it.readAllBytes()) })
        assertEquals(listOf("quant_20000101_000000.sql.gz"), r["removed"])
        assertTrue(redis.opsForValue().get(RedisKeys.BACKUP_LAST_SUCCESS)!!.contains("+09:00"))
        assertEquals(true, backup.checkFreshness()["ok"])

        backup.dumper = Dumper { _, _ -> throw IllegalStateException("pg_dump 종료코드 1: denied") }
        val failed = backup.backup()
        assertEquals(false, failed["ok"])
        assertEquals("야간 DB 백업 실패: pg_dump 종료코드 1: denied", latest("db_backup_failed")!!.message)
        assertTrue(Files.list(dir).use { s -> s.noneMatch { it.toString().endsWith(".tmp") } })

        redis.delete(RedisKeys.BACKUP_LAST_SUCCESS)
        assertEquals(false, backup.checkFreshness()["ok"])
        assertEquals("DB 백업이 최신이 아닙니다: 백업 성공 기록이 없습니다(worker/beat 미기동 가능성)", latest("db_backup_stale")!!.message)
    }

    @Test
    fun jdbcUrlIsParsedForPgDump() {
        val c = backup.conn()
        assertTrue(c.host.isNotEmpty() && c.port.toInt() > 0 && c.dbname.isNotEmpty())
    }

    @Test
    fun dartBackfillPassesBudgetAndAlertsOnFailures() {
        given(official.dartBackfill(eq(2015), eq(100))).willReturn(IngestReport(3, 100, true, listOf("000001 2024 11011: 한도")))
        val r = jobs.dartBackfill(2015, 100)
        assertEquals(3, r["done"])
        assertEquals(true, r["stoppedByBudget"])
        assertEquals("OpenDART 재무 백필 실패 1건: 000001 2024 11011: 한도", latest("dart_backfill_failure")!!.message)
    }

    @Test
    fun dartBackfillIsSkippedWhileAnotherRunHoldsTheLock() {
        redis.opsForValue().set(RedisKeys.DART_BACKFILL_LOCK, "other")
        try {
            val r = jobs.dartBackfill(2015, 100)
            assertEquals(true, r["busy"])
            org.mockito.Mockito.verify(official, org.mockito.Mockito.never()).dartBackfill(anyInt(), anyInt())
        } finally {
            redis.delete(RedisKeys.DART_BACKFILL_LOCK)
        }
        given(official.dartBackfill(eq(2015), eq(100))).willReturn(IngestReport(0, 0, false, emptyList()))
        assertEquals(0, jobs.dartBackfill(2015, 100)["done"])
        assertFalse(redis.hasKey(RedisKeys.DART_BACKFILL_LOCK)) // 끝나면 해제
    }
}
