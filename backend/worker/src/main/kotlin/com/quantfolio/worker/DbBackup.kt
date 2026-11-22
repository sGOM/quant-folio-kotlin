package com.quantfolio.worker

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.alert.pyIsoformat
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertSeverity
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.io.OutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/** pg_dump 접속 정보(비밀번호는 환경변수로만 넘긴다 — 프로세스 목록·로그 노출 방지). */
data class DbConn(val host: String, val port: String, val user: String, val password: String, val dbname: String) {
    override fun toString() = "DbConn($user@$host:$port/$dbname)"
}

/** 덤프를 out 에 쓴다. 테스트에서 교체 가능. */
fun interface Dumper {
    fun dump(conn: DbConn, out: OutputStream)
}

/** 기본 pg_dump 실행 — `pg_dump | gzip` 을 셸 없이(stdout 을 받아 호출자가 gzip). */
object PgDump : Dumper {
    override fun dump(conn: DbConn, out: OutputStream) {
        val pb = ProcessBuilder("pg_dump", "-h", conn.host, "-p", conn.port, "-U", conn.user, "-d", conn.dbname)
        pb.environment()["PGPASSWORD"] = conn.password
        val proc = pb.start()
        // stderr 를 따로 비워야 파이프가 차서 pg_dump 가 멈추지 않는다
        val stderr = java.util.concurrent.CompletableFuture.supplyAsync { proc.errorStream.readAllBytes() }
        proc.inputStream.use { it.transferTo(out) }
        val code = proc.waitFor()
        if (code != 0) throw IllegalStateException("pg_dump 종료코드 $code: ${String(stderr.get()).trim()}")
    }
}

/**
 * 야간 DB 백업(원본 `worker.backup_database`·`check_backup_freshness`). pg_dump+gzip → `/backups/quant_YYYYMMDD_HHMMSS.sql.gz`
 * (tmp 에 쓰고 이름 변경), 14일 보존, 성공 시각을 Redis 에 기록. 실패는 critical 알림(결과로 표현하고 예외는 던지지 않는다).
 */
@Component
class DbBackup(
    private val alerts: AlertPublisher,
    private val redis: StringRedisTemplate,
    private val clock: Clock,
    @Value("\${spring.datasource.url}") private val jdbcUrl: String,
    @Value("\${spring.datasource.username:quant}") private val dbUser: String,
    @Value("\${spring.datasource.password:}") private val dbPassword: String,
    @Value("\${quantfolio.backup-dir:/backups}") private val backupDir: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    var dumper: Dumper = PgDump

    /** `jdbc:postgresql://host:port/db?...` → 접속 정보. */
    fun conn(): DbConn {
        val u = URI(jdbcUrl.removePrefix("jdbc:"))
        return DbConn(u.host ?: "db", (if (u.port > 0) u.port else 5432).toString(), dbUser.ifEmpty { "quant" }, dbPassword, u.path.removePrefix("/").ifEmpty { "quant" })
    }

    fun backup(): Map<String, Any?> {
        val dir = Path.of(backupDir)
        Files.createDirectories(dir)
        val ts = clock.instant().atZone(MarketClock.KST).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"))
        val out = dir.resolve("quant_$ts.sql.gz")
        val tmp = dir.resolve("quant_$ts.sql.gz.tmp")
        val size = try {
            GZIPOutputStream(Files.newOutputStream(tmp)).use { dumper.dump(conn(), it) }
            Files.move(tmp, out, StandardCopyOption.REPLACE_EXISTING)
            Files.size(out)
        } catch (e: Exception) {
            Files.deleteIfExists(tmp)
            log.error("DB 백업 실패: {}", e.message)
            alerts.publish(null, 0, AlertSeverity.critical, "야간 DB 백업 실패: ${e.message}", "db_backup_failed")
            return linkedMapOf("ok" to false, "error" to e.message)
        }
        val removed = prune(dir)
        redis.opsForValue().set(RedisKeys.BACKUP_LAST_SUCCESS, pyIsoformat(clock.instant().atZone(MarketClock.KST)))
        val result = linkedMapOf<String, Any?>("ok" to true, "path" to out.toString(), "size_bytes" to size, "removed" to removed)
        log.info("DB 백업 완료: {}", result)
        return result
    }

    /** 보존기간(14일)을 넘긴 `quant_*.sql.gz` 삭제(mtime 기준). */
    private fun prune(dir: Path): List<String> {
        val cutoff = clock.instant().minus(Duration.ofDays(RETENTION_DAYS))
        return Files.list(dir).use { s ->
            s.filter { val n = it.fileName.toString(); n.startsWith("quant_") && n.endsWith(".sql.gz") }
                .filter { Files.getLastModifiedTime(it).toInstant().isBefore(cutoff) }
                .toList()
        }.map { Files.deleteIfExists(it); it.fileName.toString() }
    }

    /** 백업 신선도 — 성공 기록이 없거나 26시간 넘게 갱신 안 됐으면 critical(워커·beat 사망 같은 상위 침묵 실패를 잡는다). */
    fun checkFreshness(): Map<String, Any?> {
        val raw = redis.opsForValue().get(RedisKeys.BACKUP_LAST_SUCCESS)
        val ageHours = raw?.let { Duration.between(java.time.OffsetDateTime.parse(it).toInstant(), clock.instant()).toMillis() / 3_600_000.0 }
        val stale = ageHours == null || ageHours > STALE_AFTER_HOURS
        if (stale) {
            val reason = if (ageHours == null) "백업 성공 기록이 없습니다(worker/beat 미기동 가능성)" else "마지막 백업 성공 이후 ${com.quantfolio.quant.pyFixed(ageHours, 1)}시간 경과"
            log.error("DB 백업 신선도 점검 실패: {}", reason)
            alerts.publish(null, 0, AlertSeverity.critical, "DB 백업이 최신이 아닙니다: $reason", "db_backup_stale", dedupWindowHours = 20.0)
        }
        return linkedMapOf("ok" to !stale, "last_success_at" to raw, "age_hours" to ageHours)
    }

    private companion object {
        const val RETENTION_DAYS = 14L
        const val STALE_AFTER_HOURS = 26.0
    }
}
