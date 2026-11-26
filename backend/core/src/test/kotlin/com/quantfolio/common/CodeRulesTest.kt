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
        val hits = mainCodeLines().filter { (_, _, l) -> SYSTEM_ZONE_DATE.containsMatchIn(l) }
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

    /** db·redis·caddy 이미지는 버전 태그로 고정하고, Testcontainers 도 같은 태그를 쓴다 — 확장 버전이 다르면 CI 가 운영과 다른 DB 로 돈다(deploy.md). */
    @Test
    fun infraImagesPinnedAndSharedWithTests() {
        val compose = backend.resolve("../docker-compose.yml").readLines().map { it.trim() }
            .filter { it.startsWith("image:") }.map { it.removePrefix("image:").trim() }
        assertTrue(compose.isNotEmpty() && compose.all { Regex(""":\d+\.\d+\.\d+""").containsMatchIn(it) }, "compose 이미지 태그 미고정: $compose")
        val tc = Regex("""DockerImageName\.parse\("([^"]+)"\)""")
            .findAll(backend.resolve("core/src/testFixtures/kotlin/com/quantfolio/test/Containers.kt").readText()).map { it.groupValues[1] }.toList()
        assertTrue(tc.isNotEmpty() && tc.all { it in compose }, "Testcontainers 이미지가 compose 와 다름: $tc vs $compose")
    }

    /** 백업 신선도 감시는 엔진에서만 — 워커 안에서 부르면 워커가 멈출 때 감시도 멈춘다(worker.md). */
    @Test
    fun backupFreshnessCalledOnlyFromEngine() {
        val hits = mainCodeLines().filter { (f, _, l) -> "BackupFreshness" in l && !f.startsWith("core/") }
        assertTrue(hits.isNotEmpty() && hits.all { it.first.startsWith("engine/") }, "백업 감시는 엔진에서만:\n${report(hits)}")
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

    /** KRX MDC·네이버 호스트는 main 코드·설정 어디에도 없어야 한다(MDC 약관상 자동 수집 금지·IP 차단 이력, 재유입 방지). Open API 호스트 data-dbg 는 허용. */
    @Test
    fun `main sources contain no MDC or Naver hosts`() {
        val configs = Files.walk(backend).use { s ->
            s.filter { it.extension in setOf("yml", "yaml", "properties") && "/src/main/resources/" in it.invariantSeparatorsPathString }.sorted().toList()
        }
        assertTrue(configs.isNotEmpty(), "main 설정 파일을 못 찾음")
        val configLines = configs.flatMap { f -> f.readLines().mapIndexed { i, l -> Triple(backend.relativize(f).invariantSeparatorsPathString, i + 1, l) } }
        val hits = (mainCodeLines() + configLines).filter { (_, _, l) -> FORBIDDEN_HOST.containsMatchIn(l) }
        assertTrue(hits.isEmpty(), "KRX MDC(data.krx.co.kr)·네이버(naver.com) 호스트 금지 - 공식 Open API·로컬 저장소만:\n${report(hits)}")
    }

    /** 아래 전략 경로 규칙들이 빈 목록 위에서 조용히 통과하지 않게, 목록의 파일이 실제로 있는지 확인한다. */
    @Test
    fun strategyPathFilesExist() {
        val found = mainCodeLines().map { it.first.substringAfterLast('/') }.toSet()
        assertTrue(STRATEGY_PATH_FILES.all { it in found }, "목록의 파일을 못 찾음(이름 변경?): ${STRATEGY_PATH_FILES - found}")
    }

    /**
     * 전략 경로의 SnapshotSource 는 `@Qualifier("local")` 이어야 한다(구현은 로컬뿐이지만 원격 구현이 다시 생겨도 조용히 들어오지 않게).
     * 한정자는 같은 줄 또는 바로 윗줄.
     */
    @Test
    fun strategyPathSnapshotSourceIsLocal() {
        val hits = unqualifiedSnapshotSources(strategyLines())
        assertTrue(hits.isEmpty(), "전략 경로 SnapshotSource 는 @Qualifier(\"local\") 필수:\n${report(hits)}")
    }

    /**
     * 전략 경로의 `catch (Exception/Throwable)`·`runCatching` 은 MarketDataNotLoaded 를 삼키면 안 된다(미적재가 조용히 결측·중립이 된다).
     * 통과 조건: (1) 같은 줄에서 앞서 MDNL catch, (2) 40줄 안의 직전 MDNL catch 줄부터 이 줄 직전까지 중괄호가 정확히 1 열려 있고
     * 이 줄이 `}` 로 시작 — 즉 MDNL 블록을 바로 닫는 형제 catch(별개 try 의 MDNL 은 이미 닫혀 있어 걸러진다),
     * (3) 뒤 6줄 안에 `is MarketDataNotLoaded` 재던짐. 그 밖은 [CATCH_ALL_ALLOWLIST] 에 파일별 허용 건수와 이유가 있어야 하고 건수가 정확히 같아야 한다.
     */
    @Test
    fun strategyPathCatchAllKeepsMarketDataNotLoaded() {
        val hits = unguardedCatchAlls(strategyLines()).groupBy { it.first.substringAfterLast('/') }
        val problems = (hits.keys + CATCH_ALL_ALLOWLIST.keys).sorted().mapNotNull { f ->
            val got = hits[f].orEmpty()
            val allowed = CATCH_ALL_ALLOWLIST[f]?.first ?: 0
            if (got.size == allowed) null else "$f: 허용 $allowed 건, 실제 ${got.size} 건\n${report(got)}"
        }
        assertTrue(problems.isEmpty(), "MarketDataNotLoaded 를 삼킬 수 있는 catch-all — MDNL 분기를 먼저 두거나 재던지거나 allowlist 에 이유와 함께 등록:\n${problems.joinToString("\n")}")
    }

    private fun strategyLines() = mainCodeLines().filter { it.first.substringAfterLast('/') in STRATEGY_PATH_FILES }

    private fun lines(vararg l: String) = l.mapIndexed { i, t -> Triple("X.kt", i + 1, t) }

    private fun unqualifiedSnapshotSources(all: List<Triple<String, Int, String>>) = all.filterIndexed { i, (f, _, l) ->
        SNAPSHOT_DECL.containsMatchIn(l) && !LOCAL_QUALIFIER.containsMatchIn(l) &&
            !(i > 0 && all[i - 1].first == f && LOCAL_QUALIFIER.containsMatchIn(all[i - 1].third))
    }

    /** 첫 줄(`} catch (e: MDNL) {`)의 선행 `}` 를 뺀 깊이 1 에서 시작해, 마지막 줄까지 깊이가 1 아래로 내려간 적 없고 1 로 끝나면 MDNL 블록이 아직 열려 있다. */
    private fun blockStillOpen(block: List<String>): Boolean {
        var depth = 0
        block.forEachIndexed { i, line ->
            val t = if (i == 0) line.replaceFirst("}", "") else line
            depth += t.count { it == '{' } - t.count { it == '}' }
            if (depth < 1) return false
        }
        return depth == 1
    }

    private fun unguardedCatchAlls(all: List<Triple<String, Int, String>>): List<Triple<String, Int, String>> {
        val byFile = all.groupBy { it.first }
        return all.filter { (f, n, l) ->
            val m = CATCH_ALL.find(l) ?: return@filter false
            val file = byFile.getValue(f)
            val k = file.indexOfFirst { it.second == n }
            val sameLine = MDNL_CATCH.find(l)?.let { it.range.first < m.range.first } == true
            val j = (k - 1 downTo maxOf(0, k - 40)).firstOrNull { MDNL_CATCH.containsMatchIn(file[it].third) }
            val sibling = j != null && l.trimStart().startsWith("}") && blockStillOpen(file.subList(j, k).map { it.third })
            val rethrown = (k..minOf(file.size - 1, k + 6)).any { RETHROW_MDNL.containsMatchIn(file[it].third) }
            !(sameLine || sibling || rethrown)
        }
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
        // 전략 경로 SnapshotSource 한정자: 선언 건수 중 한정자 없는 것
        assertEquals(
            listOf(1, 0, 0, 0, 0),
            listOf(
                listOf("class A(private val snapshots: SnapshotSource)"),
                listOf("class A(@Qualifier(\"local\") private val snapshots: SnapshotSource)"),
                listOf("    @Qualifier(\"local\")", "    private val snapshots: SnapshotSource,"),
                listOf("import com.quantfolio.market.official.SnapshotSource"),
                listOf("val x = UniverseScores(local, y)"),
            ).map { unqualifiedSnapshotSources(lines(*it.toTypedArray())).size },
        )
        assertEquals(
            listOf(true, true, true, true, false),
            listOf(
                "\"https://data.krx.co.kr/x\"", "\"https://fchart.stock.naver.com/c\"", "\"https://api.stock.naver.com/x\"",
                "  url: https://finance.naver.com/item", "\"https://data-dbg.krx.co.kr/svc\"",
            )
                .map { FORBIDDEN_HOST.containsMatchIn(it) },
        )
        // catch-all 이 MDNL 을 삼키는가(위반 건수)
        val guards = listOf(
            listOf("try { f() } catch (e: Exception) { null }"),
            listOf("val x = runCatching { f() }.getOrNull()"),
            listOf("try { f() } catch (e: MarketDataNotLoaded) { throw e } catch (e: Exception) { null }"),
            listOf("try {", "  f()", "} catch (e: MarketDataNotLoaded) {", "  throw e", "} catch (e: Exception) {", "  null", "}"),
            listOf("try {", "  f()", "} catch (e: Exception) {", "  if (e is MarketDataNotLoaded) throw e", "  null", "}"),
            // 앞 try 는 이미 닫혔다 — 그 MDNL 분기는 이 catch-all 을 보호하지 못한다.
            listOf("try {", "  f()", "} catch (e: MarketDataNotLoaded) {", "  throw e", "}", "try {", "  g()", "} catch (e: Exception) {", "  null", "}"),
            // catch-all 이 MDNL 보다 앞이면 MDNL 분기는 도달 불가.
            listOf("try {", "  f()", "} catch (e: Exception) {", "  null", "} catch (e: MarketDataNotLoaded) {", "  throw e", "}"),
        )
        assertEquals(listOf(1, 1, 0, 0, 0, 1, 1), guards.map { unguardedCatchAlls(lines(*it.toTypedArray())).size })
    }

    companion object {
        /** 로컬 시장데이터만 읽는 경로: 전략(백테스트·리밸런싱·유니버스·패닉·달력·심볼·배치) + UI 계산(지표·스크리너·추천 — 미적재 409). */
        private val STRATEGY_PATH_FILES = setOf(
            "BacktestRunner.kt", "PortfolioBacktest.kt", "RebalanceRunner.kt", "Runners.kt", "PanicService.kt", "MarketCalendar.kt",
            "SymbolCatalog.kt", "LocalOhlcvCoverage.kt", "WorkerJobs.kt", "LocalMarketData.kt",
            "MetricsController.kt", "MetricsServices.kt", "ScreenerRecommend.kt",
        )
        private val SNAPSHOT_DECL = Regex("""\w+\s*:\s*SnapshotSource\b""")
        private val LOCAL_QUALIFIER = Regex("""@Qualifier\("local"\)""")
        private val CATCH_ALL = Regex("""catch\s*\(\s*\w+\s*:\s*(Exception|Throwable|RuntimeException)\s*\)|\brunCatching\b""")
        private val MDNL_CATCH = Regex("""catch\s*\(\s*\w+\s*:\s*MarketDataNotLoaded\s*\)""")
        private val RETHROW_MDNL = Regex("""is\s+MarketDataNotLoaded""")

        /** 파일 → (허용 건수, 이유). 그 try/runCatching 안에서 MDNL 이 나올 수 없음을 코드로 확인한 곳만. 건수가 달라지면 실패 — 다시 본다. */
        private val CATCH_ALL_ALLOWLIST: Map<String, Pair<Int, String>> = mapOf(
            "BacktestRunner.kt" to (2 to "배당 조회(DividendFeed: 배당·가격틱 읽기, MDNL 안 던짐) + 잔차모멘텀 runCatching(적재된 closes 위 순수 계산)"),
            "RebalanceRunner.kt" to (3 to "ISO 시각·JSON 파싱 runCatching 2건 + 브로커 현재가 조회 1건 - 시장데이터 미접근"),
            "Runners.kt" to (2 to "Redis 헬스 기록 + 브로커 현재가 정지 확인 - 시장데이터 미접근"),
            "PanicService.kt" to (5 to "로컬 파일 캐시 로드·저장 4건(스냅샷 미접근) + UI compute 의 S9 newLowRatio 1건(원본처럼 S9 만 null, PanicOut 이 결측 표시)"),
            "MarketCalendar.kt" to (1 to "KIS 휴장일 REST 조회 - 로컬 DB 미접근"),
            "SymbolCatalog.kt" to (1 to "KIS 마스터 REST 조회 - 로컬 DB 미접근"),
            "MetricsServices.kt" to (1 to "종목명 catalog.nameMap() - KIS 마스터 REST 조회, 로컬 DB 미접근"),
            "ScreenerRecommend.kt" to (2 to "종목명 catalog.nameMap() 2건 - KIS 마스터 REST 조회, 로컬 DB 미접근"),
            "WorkerJobs.kt" to (2 to "withOutageAlert 는 알림 후 throw e 로 전부 재던짐 + 체결 정합 리포트(orders/fills DB 만)"),
        )
        private val FORBIDDEN_HOST = Regex("""data\.krx\.co\.kr|naver\.com""")
        private const val BLOCKED_COLUMN = "kis_balance_reconcile_blocked"
        private val BLOCKED_RELEASE = Regex("""kis_balance_reconcile_blocked\s*:?=\s*false""", RegexOption.IGNORE_CASE)
        private val SYSTEM_ZONE_DATE = Regex("""\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\.now\(\)|systemDefault""")
        private fun redisLiteral(namespaces: Set<String>) = Regex("\"(${namespaces.joinToString("|")}):")
    }
}
