package com.quantfolio.common

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.readLines
import kotlin.io.path.readText
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * CLAUDE.md "필수 함정" 중 예외 없는 규칙을 전 모듈 main 소스에서 grep 으로 강제한다.
 * 문서만으로는 "거의 매번" 지켜질 뿐이다.
 */
class CodeRulesTest {
    private val backend = Path.of("..")

    /** (모듈 상대 경로, 줄 번호, 내용). 주석 줄은 뺀다 — 규칙을 설명하는 주석이 걸리지 않게. */
    private fun mainCodeLines(): List<Triple<String, Int, String>> =
        Files.walk(backend).use { s -> s.filter { it.extension == "kt" && "/src/main/" in it.invariantSeparatorsPathString }.sorted().toList() }
            .flatMap { f ->
                val rel = backend.relativize(f).invariantSeparatorsPathString
                f.readLines().mapIndexedNotNull { i, line ->
                    val t = line.trimStart()
                    if (t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")) null else Triple(rel, i + 1, line)
                }
            }

    private fun report(hits: List<Triple<String, Int, String>>) = hits.joinToString("\n") { (f, n, l) -> "$f:$n  ${l.trim()}" }

    @Test
    fun noSystemZoneDates() {
        // 원본 보존 예외: 프로세스 로컬 시각 기준이 원본 동작이다(DART 일일 한도 리셋 근사).
        val allowed = setOf("core/src/main/kotlin/com/quantfolio/market/source/DataSourceError.kt")
        val hits = mainCodeLines().filter { (f, _, l) -> f !in allowed && SYSTEM_ZONE_DATE.containsMatchIn(l) }
        assertTrue(hits.isEmpty(), "시스템 TZ(컨테이너 UTC) 날짜 — MarketClock.todayKst()/nowKst() 를 쓴다:\n${report(hits)}")
    }

    @Test
    fun redisKeyLiteralsOnlyInRedisKeys() {
        val keysFile = "core/src/main/kotlin/com/quantfolio/common/RedisKeys.kt"
        // 네임스페이스는 RedisKeys 자신에서 뽑는다 — 목록을 여기 복사하면 키가 늘 때 낡는다.
        val namespaces = Regex("\"([a-z_]+):").findAll(backend.resolve(keysFile).readText()).map { it.groupValues[1] }.toSortedSet()
        assertTrue("engine" in namespaces && "lock" in namespaces, "RedisKeys 네임스페이스 추출 실패: $namespaces")
        val literal = redisLiteral(namespaces)
        val hits = mainCodeLines().filter { (f, _, l) -> f != keysFile && literal.containsMatchIn(l) }
        assertTrue(hits.isEmpty(), "Redis 키·채널 리터럴은 RedisKeys 에만:\n${report(hits)}")
    }

    @Test
    fun scheduledHasZone() {
        // 정규화 이름(@org...annotation.Scheduled)도 잡는다. 인자를 여러 줄로 쓰면 오탐으로 실패한다 — 한 줄로 쓴다.
        val hits = mainCodeLines().filter { (_, _, l) -> Regex("""@[\w.]*Scheduled\(""").containsMatchIn(l) && "zone =" !in l }
        assertTrue(hits.isEmpty(), "@Scheduled 는 zone 필수(컨테이너 TZ 는 UTC):\n${report(hits)}")
    }

    /** `kis_balance_reconcile_blocked` 는 세우기만 하고 해제하지 않는다(trading-safety.md). 운영 코드는 읽는 곳(Reconciler)뿐이어야 한다. */
    @Test
    fun reconcileBlockedFlagNeverReleased() {
        val readerFile = "engine/src/main/kotlin/com/quantfolio/engine/Reconciler.kt"
        val users = mainCodeLines().filter { (_, _, l) -> BLOCKED_COLUMN in l }
        val strays = users.filter { (f, _, _) -> f != readerFile }
        assertTrue(strays.isEmpty(), "차단 컬럼을 읽는 곳은 Reconciler 뿐이어야 한다:\n${report(strays)}")
        val releases = users.filter { (_, _, l) -> BLOCKED_RELEASE.containsMatchIn(l) }
        assertTrue(releases.isEmpty(), "차단 표시 해제 금지:\n${report(releases)}")

        val migrations = Files.walk(backend.resolve("core/src/main/resources/db/migration")).use { s ->
            s.filter { it.extension == "sql" }.sorted().toList()
        }
        assertTrue(migrations.isNotEmpty(), "마이그레이션 SQL 을 못 찾음")
        val sqlHits = migrations.flatMap { f -> f.readLines().mapIndexedNotNull { i, l -> if (BLOCKED_RELEASE.containsMatchIn(l)) Triple(f.fileName.toString(), i + 1, l) else null } }
        assertTrue(sqlHits.isEmpty(), "마이그레이션에서 차단 표시 해제 금지:\n${report(sqlHits)}")
    }

    /**
     * 공식 경로 1단계: 전략 경로(백테스트·리밸런싱·유니버스·패닉·달력·심볼·배치)는 KRX MDC·네이버 클래스를 참조하지 않는다.
     * OhlcvLoader.kt 는 UI 경로용 Naver 보충(2단계에서 제거)이 남아 목록에서 뺐고, 전략 경로의 LocalOhlcvCoverage 를 LocalOhlcvCoverage.kt 로 분리해 대신 넣었다.
     */
    @Test
    fun strategyPathAvoidsMdcAndNaver() {
        val files = setOf(
            "BacktestRunner.kt", "PortfolioBacktest.kt", "RebalanceRunner.kt", "Runners.kt", "PanicService.kt", "MarketCalendar.kt",
            "SymbolCatalog.kt", "LocalOhlcvCoverage.kt", "WorkerJobs.kt", "LocalMarketData.kt",
        )
        val found = mainCodeLines().map { it.first.substringAfterLast('/') }.toSet()
        assertTrue(files.all { it in found }, "목록의 파일을 못 찾음(이름 변경?): ${files - found}")
        val hits = mainCodeLines().filter { (f, _, l) -> f.substringAfterLast('/') in files && FORBIDDEN_SOURCE.containsMatchIn(l) }
        assertTrue(hits.isEmpty(), "전략 경로는 KRX MDC·네이버를 쓰지 않는다(공식 경로 로컬 소스만):\n${report(hits)}")
    }

    /** 정규식이 아무것도 못 잡게 망가지면 위 테스트들이 조용히 통과한다 — 그걸 막는다. */
    @Test
    fun patternsCatchViolations() {
        assertEquals(
            listOf(true, true, true, false, false),
            listOf("LocalDate.now()", "ZoneId.systemDefault()", "Clock.systemDefaultZone()", "LocalDate.now(clock)", "Instant.now()")
                .map { SYSTEM_ZONE_DATE.containsMatchIn(it) },
        )
        val literal = redisLiteral(setOf("engine", "lock"))
        assertEquals(
            listOf(true, true, false, false),
            listOf("""redis.get("engine:heartbeat")""", "\"lock:order:\$k\"", "RedisKeys.ENGINE_HEARTBEAT", "\"engine\" to 1").map { literal.containsMatchIn(it) },
        )
        assertEquals(
            listOf(true, true, true, false, false, false),
            listOf(
                "UPDATE users SET kis_balance_reconcile_blocked = false",
                "SET kis_balance_reconcile_blocked=FALSE",
                "NEW.kis_balance_reconcile_blocked := false;",
                "ADD COLUMN kis_balance_reconcile_blocked boolean NOT NULL DEFAULT false;",
                "SET kis_balance_reconcile_blocked = true",
                "select not kis_balance_reconcile_blocked from users",
            ).map { BLOCKED_RELEASE.containsMatchIn(it) },
        )
    }

    companion object {
        private val FORBIDDEN_SOURCE = Regex("""\b(KrxSession|KrxMdc|KrxStockApi|KrxIndexService|KrxSnapshots|NaverDaily)\b""")
        private const val BLOCKED_COLUMN = "kis_balance_reconcile_blocked"
        private val BLOCKED_RELEASE = Regex("""kis_balance_reconcile_blocked\s*:?=\s*false""", RegexOption.IGNORE_CASE)
        private val SYSTEM_ZONE_DATE = Regex("""\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\.now\(\)|systemDefault""")
        private fun redisLiteral(namespaces: Set<String>) = Regex("\"(${namespaces.joinToString("|")}):")
    }
}
