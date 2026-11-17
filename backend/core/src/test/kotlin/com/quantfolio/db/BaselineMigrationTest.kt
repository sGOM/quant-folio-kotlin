package com.quantfolio.db

import com.quantfolio.test.Containers
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Test
import java.sql.DriverManager
import kotlin.test.assertEquals

class BaselineMigrationTest {
    private val pg = Containers.postgres

    private fun freshDb(name: String): String {
        DriverManager.getConnection(pg.jdbcUrl, pg.username, pg.password).use {
            it.createStatement().execute("DROP DATABASE IF EXISTS $name")
            it.createStatement().execute("CREATE DATABASE $name")
        }
        return pg.jdbcUrl.replaceAfterLast("/", name)
    }

    private fun flyway(url: String, target: String = "latest") = Flyway.configure()
        .dataSource(url, pg.username, pg.password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .target(target)
        .load()

    private fun exec(url: String, vararg sql: String) =
        DriverManager.getConnection(url, pg.username, pg.password).use { c -> sql.forEach { c.createStatement().execute(it) } }

    private fun <T> query(url: String, sql: String, map: (java.sql.ResultSet) -> T): List<T> =
        DriverManager.getConnection(url, pg.username, pg.password).use { c ->
            c.createStatement().executeQuery(sql).use { rs -> buildList { while (rs.next()) add(map(rs)) } }
        }

    @Test
    fun emptyDbGetsFullSchema() {
        val url = freshDb("bl_empty")
        flyway(url).migrate()

        val tables = query(url, "select tablename from pg_tables where schemaname='public' and tablename <> 'flyway_schema_history' order by 1") { it.getString(1) }
        assertEquals(
            listOf(
                "alerts", "backtests", "dart_financials", "executions", "external_fetches", "index_constituents",
                "index_ohlcv", "index_ohlcv_coverage", "kis_stock_master_snapshots", "news_article_symbols",
                "news_articles", "orders", "positions", "price_ticks", "risk_limits", "sector_map_snapshots",
                "stock_daily_snapshots", "stock_period_stats", "strategies", "strategy_likes", "users",
            ),
            tables,
        )
        val hypertables = query(url, "select hypertable_name from timescaledb_information.hypertables order by 1") { it.getString(1) }
        assertEquals(listOf("price_ticks", "stock_daily_snapshots"), hypertables)
    }

    @Test
    fun existingDbIsBaselinedNotReapplied() {
        val url = freshDb("bl_existing")
        val v1 = javaClass.getResource("/db/migration/V1__baseline.sql")!!.readText()
        DriverManager.getConnection(url, pg.username, pg.password).use { c ->
            c.createStatement().execute(v1)
            c.createStatement().execute("insert into users(email, password_hash) values ('a@b.c', 'x')")
            c.createStatement().execute("""insert into orders(user_id, symbol, side, qty, order_type, status, idempotency_key, created_at)
                select id, '005930', 'buy', 1, 'market', 'submitted', 'legacy-order', now() from users""")
            c.createStatement().execute("""insert into positions(user_id, symbol, qty, avg_price)
                select id, '005930', 10, 900 from users""")
        }

        flyway(url).migrate()

        val history = query(url, "select version, type from flyway_schema_history order by installed_rank") { it.getString(1) to it.getString(2) }
        assertEquals(listOf("1" to "BASELINE", "2" to "SQL", "3" to "SQL", "4" to "SQL", "5" to "SQL", "6" to "SQL"), history)
        assertEquals(listOf(false), query(url, "select kis_balance_reconcile_blocked from users") { it.getBoolean(1) })
        assertEquals(listOf<String?>(null), query(url, "select broker_account_key from orders where idempotency_key = 'legacy-order'") { it.getString(1) })
        assertEquals(listOf<String?>(null), query(url, "select closed_at from orders where idempotency_key = 'legacy-order'") { it.getString(1) }) // 기존 주문은 미종결
        assertEquals(listOf(Triple(null, "10.0000", "900.0000")), query(url, "select broker_account_key, qty, avg_price from positions") {
            Triple(it.getString(1), it.getBigDecimal(2).toPlainString(), it.getBigDecimal(3).toPlainString())
        })
    }

    /** V6 는 데이터가 있는 V5 DB 에서 자격증명 컬럼·V2 트리거만 지우고, V2 가 세운 잔고 추정 차단 표시와 나머지 값은 남긴다. */
    @Test
    fun v6DropsCredentialColumnsButKeepsAccountChangeFlag() {
        val url = freshDb("bl_v6")
        flyway(url, target = "5").migrate()
        exec(
            url,
            """insert into users(email, password_hash, display_name, broker, kis_app_key, kis_app_secret, kis_account_no,
                   toss_app_key, toss_app_secret, toss_account_no)
               values ('owner@t.dev', 'h1', '소유자', 'kis', 'enc-k', 'enc-s', '12345678-01', 'enc-tk', 'enc-ts', '7'),
                      ('other@t.dev', 'h2', null, 'toss', null, null, null, null, null, null)""",
            """insert into orders(user_id, symbol, side, qty, order_type, status, idempotency_key, created_at, broker_account_key)
               select id, '005930', 'buy', 1, 'market', 'filled', 'v6-order', now(), 'kis:vts:1234567801' from users where email = 'owner@t.dev'""",
            "update users set kis_account_no = '12345678-02' where email = 'owner@t.dev'", // V2 트리거가 표시를 세운다
        )
        assertEquals(listOf(true, false), query(url, "select kis_balance_reconcile_blocked from users order by id") { it.getBoolean(1) })

        flyway(url).migrate()

        assertEquals(
            listOf("id", "email", "password_hash", "created_at", "updated_at", "broker", "display_name", "kis_balance_reconcile_blocked"),
            query(url, "select column_name from information_schema.columns where table_name = 'users' order by ordinal_position") { it.getString(1) },
        )
        assertEquals(
            listOf("owner@t.dev|h1|소유자|kis|true", "other@t.dev|h2|null|toss|false"),
            query(url, "select email, password_hash, display_name, broker, kis_balance_reconcile_blocked from users order by id") {
                "${it.getString(1)}|${it.getString(2)}|${it.getString(3)}|${it.getString(4)}|${it.getBoolean(5)}"
            },
        )
        assertEquals(listOf("kis:vts:1234567801"), query(url, "select broker_account_key from orders") { it.getString(1) })
        assertEquals(0, query(url, "select count(*) from pg_trigger where tgname = 'block_kis_balance_after_account_change'") { it.getInt(1) }.single())
        assertEquals(0, query(url, "select count(*) from pg_proc where proname = 'block_kis_balance_after_account_change'") { it.getInt(1) }.single())
        exec(url, "update users set broker = 'kis' where email = 'other@t.dev'") // 트리거 없이 갱신된다
    }
}
