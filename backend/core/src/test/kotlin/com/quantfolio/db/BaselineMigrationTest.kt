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
                "alerts", "backtests", "dart_corp_codes", "dart_financials", "dividends", "executions", "external_fetches", "index_constituents",
                "index_ohlcv", "index_ohlcv_coverage", "kis_stock_master_snapshots", "news_article_symbols",
                "news_articles", "orders", "positions", "price_ticks", "risk_limits", "sector_map_snapshots",
                "stock_daily_snapshots", "stock_period_stats", "strategies", "users",
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

        flyway(url, target = "7").migrate() // V8 은 계좌 키 없는 행을 지운다 — 아래 v8 테스트

        val history = query(url, "select version, type from flyway_schema_history order by installed_rank") { it.getString(1) to it.getString(2) }
        assertEquals(listOf("1" to "BASELINE", "2" to "SQL", "3" to "SQL", "4" to "SQL", "5" to "SQL", "6" to "SQL", "7" to "SQL"), history)
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

        flyway(url, target = "6").migrate()

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

    /** V7 은 데이터가 있는 V6 DB 에서 공유·좋아요·복사·대표 백테스트·닉네임만 지우고 사용자·전략·백테스트 행은 남긴다. */
    @Test
    fun v7DropsStrategySharingButKeepsRows() {
        val url = freshDb("bl_v7")
        flyway(url, target = "6").migrate()
        exec(
            url,
            "insert into users(email, password_hash, display_name) values ('owner@t.dev', 'h1', '소유자'), ('other@t.dev', 'h2', null)",
            """insert into strategies(user_id, name, config, status, is_shared, shared_at, is_favorite, sort_order)
               select id, '원본', '{}', 'draft', true, now(), true, 3 from users where email = 'owner@t.dev'""",
            """insert into strategies(user_id, name, config, status, copied_from_id)
               select u.id, '사본', '{}', 'draft', s.id from users u, strategies s where u.email = 'other@t.dev'""",
            """insert into backtests(strategy_id, period_start, period_end)
               select id, '2024-01-01', '2024-12-31' from strategies where name = '원본'""",
            "update strategies set featured_backtest_id = (select id from backtests) where name = '원본'",
            """insert into strategy_likes(strategy_id, user_id)
               select s.id, u.id from strategies s, users u where s.name = '원본' and u.email = 'other@t.dev'""",
        )

        flyway(url).migrate()

        assertEquals(0, query(url, "select count(*) from pg_tables where tablename = 'strategy_likes'") { it.getInt(1) }.single())
        assertEquals(0, query(url, "select count(*) from pg_class where relname = 'strategy_likes_id_seq'") { it.getInt(1) }.single())
        assertEquals(
            listOf("id", "user_id", "name", "config", "status", "created_at", "updated_at", "is_favorite", "sort_order", "description"),
            query(url, "select column_name from information_schema.columns where table_name = 'strategies' order by ordinal_position") { it.getString(1) },
        )
        assertEquals(
            listOf("id", "email", "password_hash", "created_at", "updated_at", "broker", "kis_balance_reconcile_blocked"),
            query(url, "select column_name from information_schema.columns where table_name = 'users' order by ordinal_position") { it.getString(1) },
        )
        assertEquals(
            listOf("strategies_pkey", "strategies_user_id_fkey"),
            query(url, "select conname from pg_constraint where conrelid = 'public.strategies'::regclass order by 1") { it.getString(1) },
        )
        assertEquals(listOf("owner@t.dev", "other@t.dev"), query(url, "select email from users order by id") { it.getString(1) })
        assertEquals(listOf("원본|true|3", "사본|false|0"), query(url, "select name, is_favorite, sort_order from strategies order by id") {
            "${it.getString(1)}|${it.getBoolean(2)}|${it.getInt(3)}"
        })
        assertEquals(1, query(url, "select count(*) from backtests") { it.getInt(1) }.single())
    }

    /**
     * V8 은 계좌 키 없는 주문(체결은 CASCADE)·포지션만 지우고 키 있는 행과 다른 테이블은 남긴다.
     * 그 주문이 막던 KIS 모의 잔고 추정은 사용자 표시로 옮겨 계속 막는다. 계좌 키 없는 포지션용 부분 유니크 인덱스는 지운다.
     */
    @Test
    fun v8DeletesUnkeyedTradesOnly() {
        val url = freshDb("bl_v8")
        flyway(url, target = "7").migrate()
        exec(
            url,
            "insert into users(email, password_hash) values ('owner@t.dev', 'h1'), ('other@t.dev', 'h2')",
            "insert into strategies(user_id, name) select id, 's' from users where email = 'owner@t.dev'",
            """insert into orders(user_id, strategy_id, symbol, side, qty, order_type, status, idempotency_key, created_at, broker_account_key)
               select u.id, s.id, '005930', 'buy', 1, 'market', 'filled', k.key, now(), k.acct
               from users u, strategies s, (values ('unkeyed', null), ('keyed', 'kis:vts:1')) k(key, acct) where u.email = 'owner@t.dev'""",
            """insert into orders(user_id, symbol, side, qty, order_type, status, idempotency_key, created_at, broker_account_key)
               select id, '005930', 'buy', 1, 'market', 'filled', 'other-keyed', now(), 'kis:vts:2' from users where email = 'other@t.dev'""",
            "insert into executions(order_id, strategy_id, filled_qty, filled_price) select id, strategy_id, 1, 1000 from orders",
            """insert into positions(user_id, strategy_id, symbol, qty, avg_price, broker_account_key)
               select u.id, s.id, '005930', 1, 1000, k.acct from users u, strategies s, (values (null), ('kis:vts:1')) k(acct) where u.email = 'owner@t.dev'""",
            "insert into alerts(user_id, strategy_id, severity, message) select id, 0, 'info', 'kept' from users where email = 'owner@t.dev'",
        )

        flyway(url).migrate()

        assertEquals(listOf("keyed", "other-keyed"), query(url, "select idempotency_key from orders order by id") { it.getString(1) })
        assertEquals(listOf("keyed", "other-keyed"), query(url, "select o.idempotency_key from executions e join orders o on o.id = e.order_id order by e.id") { it.getString(1) })
        assertEquals(listOf("kis:vts:1"), query(url, "select broker_account_key from positions") { it.getString(1) })
        assertEquals(listOf("owner@t.dev|true", "other@t.dev|false"), query(url, "select email, kis_balance_reconcile_blocked from users order by id") {
            "${it.getString(1)}|${it.getBoolean(2)}"
        })
        assertEquals(1, query(url, "select count(*) from strategies") { it.getInt(1) }.single())
        assertEquals(1, query(url, "select count(*) from alerts") { it.getInt(1) }.single())
        assertEquals(0, query(url, "select count(*) from pg_indexes where indexname = 'uq_positions_legacy_user_strategy_symbol'") { it.getInt(1) }.single())

        // 빈 DB 에서는 아무 일도 하지 않는다(신규 설치·테스트).
        val empty = freshDb("bl_v8_empty")
        flyway(empty).migrate()
        assertEquals(listOf(false), query(empty, "select count(*) > 0 from orders") { it.getBoolean(1) })
    }

    /** V11 은 지수 소스 전략을 KOSPI_MCAP + pool_size 로 옮기고 pick·나머지 설정은 그대로 둔다. fixed 는 건드리지 않는다. */
    @Test
    fun v11MovesIndexSourcesToMcapWithPoolSize() {
        val url = freshDb("bl_v11")
        flyway(url, target = "10").migrate()
        fun cfg(source: String, pick: Int) =
            """{"type":"rebalance","universe":[],"selection":{"method":"momentum","top_n":5,"universe_rule":{"type":"momentum","source":"$source","lookback":120,"pick":$pick,"min_market_cap":null}}}"""
        exec(
            url,
            "insert into users(email, password_hash) values ('owner@t.dev', 'h1')",
            """insert into strategies(user_id, name, config, status)
               select id, n, c::jsonb, 'draft' from users, (values
                 ('k200', '${cfg("KOSPI200", 40)}'), ('k100', '${cfg("KOSPI100", 100)}'),
                 ('krx300', '${cfg("KRX300", 20)}'), ('fixed', '${cfg("fixed", 20)}')) v(n, c)""",
            """insert into strategies(user_id, name, config, status)
               select id, 'plain', '{"type":"sma_crossover","symbol":"005930"}'::jsonb, 'draft' from users""",
        )

        flyway(url).migrate()

        fun field(name: String, key: String) = query(url, "select config->'selection'->'universe_rule'->>'$key' from strategies where name = '$name'") { it.getString(1) }.single()
        assertEquals(listOf("KOSPI_MCAP", "200", "40"), listOf("source", "pool_size", "pick").map { field("k200", it) })
        assertEquals(listOf("KOSPI_MCAP", "100", "100"), listOf("source", "pool_size", "pick").map { field("k100", it) })
        assertEquals(listOf("KOSPI_MCAP", "300", "20"), listOf("source", "pool_size", "pick").map { field("krx300", it) })
        assertEquals(listOf("fixed", null, "20"), listOf("source", "pool_size", "pick").map { field("fixed", it) })
        assertEquals("120", field("k200", "lookback"))
        assertEquals(1, query(url, "select count(*) from strategies where name = 'plain' and config = '{\"type\": \"sma_crossover\", \"symbol\": \"005930\"}'::jsonb") { it.getInt(1) }.single())
    }

    /** V12 는 flow 팩터 키(가중치·창·분모)만 지우고 나머지 selection 키는 그대로 둔다. */
    @Test
    fun v12StripsFlowKeys() {
        val url = freshDb("bl_v12")
        flyway(url, target = "11").migrate()
        val flowCfg = """{"type":"rebalance","universe":[],"selection":{"method":"score","top_n":5,"flow_window":90,"flow_denom":"mcap","factor_weights":{"momentum":0.5,"value":0.5,"flow":0.0}}}"""
        exec(
            url,
            "insert into users(email, password_hash) values ('owner@t.dev', 'h1')",
            """insert into strategies(user_id, name, config, status)
               select id, 'flowy', '$flowCfg'::jsonb, 'draft' from users""",
            """insert into strategies(user_id, name, config, status)
               select id, 'plain', '{"type":"sma_crossover","symbol":"005930"}'::jsonb, 'draft' from users""",
        )

        flyway(url).migrate()

        val expected = """{"type":"rebalance","universe":[],"selection":{"method":"score","top_n":5,"factor_weights":{"momentum":0.5,"value":0.5}}}"""
        assertEquals(1, query(url, "select count(*) from strategies where name = 'flowy' and config = '$expected'::jsonb") { it.getInt(1) }.single())
        assertEquals(1, query(url, "select count(*) from strategies where name = 'plain' and config = '{\"type\": \"sma_crossover\", \"symbol\": \"005930\"}'::jsonb") { it.getInt(1) }.single())
    }
}
