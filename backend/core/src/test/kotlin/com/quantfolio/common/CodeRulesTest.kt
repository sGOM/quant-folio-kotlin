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
    }

    companion object {
        private val SYSTEM_ZONE_DATE = Regex("""\b(LocalDate|LocalDateTime|LocalTime|ZonedDateTime|OffsetDateTime|YearMonth|Year)\.now\(\)|systemDefault""")
        private fun redisLiteral(namespaces: Set<String>) = Regex("\"(${namespaces.joinToString("|")}):")
    }
}
