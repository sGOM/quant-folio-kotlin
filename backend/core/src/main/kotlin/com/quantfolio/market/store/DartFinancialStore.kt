package com.quantfolio.market.store

import com.quantfolio.common.MarketClock
import java.time.Clock
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.LocalDate

/**
 * OpenDART 재무제표 원본(계정 목록) 저장. 정정공시가 접수일 이후에도 바뀔 수 있어
 * "접수일 + 90일"이 지나야 확정으로 본다.
 */
@Component
class DartFinancialStore(private val jdbc: NamedParameterJdbcTemplate, private val clock: Clock) {
    private val json = JsonMapper.builder().build()

    fun write(corpCode: String, bsnsYear: Int, reprtCode: String, fsDiv: String, accounts: List<Map<String, Any?>>) {
        if (accounts.isEmpty()) return
        val (rceptNo, rceptDt) = parseRcept(accounts)
        jdbc.update(
            """
            INSERT INTO dart_financials (corp_code, bsns_year, reprt_code, fs_div, accounts, rcept_no, rcept_dt, confirmed_at, fetched_at)
            VALUES (:corp, :year, :reprt, :fs, CAST(:accounts AS jsonb), :rceptNo, :rceptDt, :confirmed, now())
            ON CONFLICT (corp_code, bsns_year, reprt_code, fs_div) DO UPDATE SET
              accounts = EXCLUDED.accounts,
              rcept_no = COALESCE(EXCLUDED.rcept_no, dart_financials.rcept_no),
              rcept_dt = COALESCE(EXCLUDED.rcept_dt, dart_financials.rcept_dt),
              confirmed_at = GREATEST(EXCLUDED.confirmed_at, dart_financials.confirmed_at),
              fetched_at = EXCLUDED.fetched_at
            """,
            mapOf(
                "corp" to corpCode, "year" to bsnsYear, "reprt" to reprtCode, "fs" to fsDiv,
                "accounts" to json.writeValueAsString(accounts), "rceptNo" to rceptNo, "rceptDt" to rceptDt,
                "confirmed" to confirmedDate(rceptDt, bsnsYear),
            ),
        )
    }

    /** (계정 목록, 확정 여부). 없으면 null. */
    fun read(corpCode: String, bsnsYear: Int, reprtCode: String, fsDiv: String): Pair<List<Map<String, Any?>>, Boolean>? = jdbc.query(
        """
        SELECT accounts::text, confirmed_at FROM dart_financials
         WHERE corp_code = :corp AND bsns_year = :year AND reprt_code = :reprt AND fs_div = :fs
        """,
        mapOf("corp" to corpCode, "year" to bsnsYear, "reprt" to reprtCode, "fs" to fsDiv),
    ) { rs, _ ->
        val confirmed = rs.getObject(2, LocalDate::class.java)
        // 원본 동작 보존: date.today() = 컨테이너(UTC) 날짜 기준(docs/migration-notes.md).
        val final = confirmed != null && !MarketClock.todayUtcLegacy(clock).isBefore(confirmed)
        json.readValue<List<Map<String, Any?>>>(rs.getString(1)) to final
    }.firstOrNull()

    /** 저장된 보고서의 접수일. 행이 없거나 접수번호를 해석하지 못했으면 null. */
    fun receiptDate(corpCode: String, bsnsYear: Int, reprtCode: String, fsDiv: String): LocalDate? = jdbc.query(
        "SELECT rcept_dt FROM dart_financials WHERE corp_code = :corp AND bsns_year = :year AND reprt_code = :reprt AND fs_div = :fs",
        mapOf("corp" to corpCode, "year" to bsnsYear, "reprt" to reprtCode, "fs" to fsDiv),
    ) { rs, _ -> rs.getObject(1, LocalDate::class.java) }.firstOrNull()

    /** 종목코드(6자리) → corp_code 매핑 upsert. 원격 corpCode.xml 을 받을 때마다 호출된다. */
    fun writeCorpCodes(map: Map<String, String>) {
        if (map.isEmpty()) return
        jdbc.batchUpdate(
            """
            INSERT INTO dart_corp_codes (symbol, corp_code, updated_at) VALUES (:symbol, :corp, now())
            ON CONFLICT (symbol) DO UPDATE SET corp_code = EXCLUDED.corp_code, updated_at = now()
            """,
            map.map { (symbol, corp) -> mapOf("symbol" to symbol, "corp" to corp) }.toTypedArray(),
        )
    }

    fun readCorpCodes(): Map<String, String> =
        jdbc.query("SELECT symbol, corp_code FROM dart_corp_codes ORDER BY symbol", emptyMap<String, Any>()) { rs, _ ->
            rs.getString(1) to rs.getString(2)
        }.toMap()

    fun delete(corpCode: String, bsnsYear: Int, reprtCode: String, fsDiv: String) {
        jdbc.update(
            "DELETE FROM dart_financials WHERE corp_code = :corp AND bsns_year = :year AND reprt_code = :reprt AND fs_div = :fs",
            mapOf("corp" to corpCode, "year" to bsnsYear, "reprt" to reprtCode, "fs" to fsDiv),
        )
    }

    /** 계정 행의 접수번호(앞 8자리 = 접수일 yyyyMMdd)를 처음 해석되는 것으로 고른다. */
    private fun parseRcept(accounts: List<Map<String, Any?>>): Pair<String?, LocalDate?> {
        for (row in accounts) {
            val raw = row["rcept_no"]?.toString()?.trim().orEmpty()
            if (raw.length >= 8 && raw.take(8).all { it.isDigit() }) {
                val date = runCatching { LocalDate.of(raw.take(4).toInt(), raw.substring(4, 6).toInt(), raw.substring(6, 8).toInt()) }
                    .getOrNull() ?: continue
                return raw to date
            }
        }
        return null to null
    }

    companion object {
        private const val CONFIRM_LAG_DAYS = 90L

        /** 접수일 + 90일. 접수일을 모르면 사업연도 다음 해 말(보수적). */
        fun confirmedDate(rceptDt: LocalDate?, bsnsYear: Int): LocalDate =
            rceptDt?.plusDays(CONFIRM_LAG_DAYS) ?: LocalDate.of(bsnsYear + 1, 12, 31)
    }
}
