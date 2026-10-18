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

    private fun flyway(url: String) = Flyway.configure()
        .dataSource(url, pg.username, pg.password)
        .locations("classpath:db/migration")
        .baselineOnMigrate(true)
        .baselineVersion("1")
        .load()

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
        }

        flyway(url).migrate()

        val history = query(url, "select version, type from flyway_schema_history") { it.getString(1) to it.getString(2) }
        assertEquals(listOf("1" to "BASELINE"), history)
        assertEquals(listOf(1), query(url, "select count(*) from users") { it.getInt(1) })
    }
}
