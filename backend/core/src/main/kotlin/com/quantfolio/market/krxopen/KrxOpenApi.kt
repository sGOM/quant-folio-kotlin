package com.quantfolio.market.krxopen

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpErrors
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceSchemaError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

data class OpenDailyRow(
    val day: LocalDate, val symbol: String, val market: String,
    val open: Double?, val high: Double?, val low: Double?, val close: Double?, val prevDiff: Double?,
    val volume: Long?, val tradingValue: Long?, val marketCap: Long?, val shares: Long?,
)

/** [stockKind] 는 주권 종류 원문(보통주·우선주 …) — 해석은 호출자 몫. */
data class OpenBaseRow(val symbol: String, val name: String, val market: String, val stockKind: String)

data class OpenIndexRow(val day: LocalDate, val name: String, val open: Double?, val high: Double?, val low: Double?, val close: Double?,
    val volume: Long? = null, val tradingValue: Long? = null,
)

/**
 * KRX Open API(공식, AUTH_KEY) 전송·파싱. 숫자는 쉼표 문자열이고 값 없음은 "-" → null(0 은 값이다).
 * 필수 키가 응답에 없으면 SourceSchemaError — 전 행이 조용히 null 이 되는 것을 막는다.
 * 키는 예외·로그에 남기지 않는다. IP 차단 중에도 401 이 오므로 Auth 로 분류해 쿨다운에 건다.
 */
@Component
class KrxOpenApi(
    private val http: ExternalHttp,
    private val props: QuantfolioProperties,
    private val cooldowns: Cooldowns,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    fun stockDaily(day: LocalDate, market: String): List<OpenDailyRow> =
        fetch(service("sto", market, "bydd_trd"), day).map { n ->
            OpenDailyRow(
                day = parseDay(req(n, DAY)), symbol = req(n, DAILY_SYMBOL).padStart(6, '0'), market = req(n, DAILY_MARKET),
                open = num(req(n, OPEN)), high = num(req(n, HIGH)), low = num(req(n, LOW)), close = num(req(n, CLOSE)),
                prevDiff = num(req(n, PREV_DIFF)), volume = long(req(n, VOLUME)), tradingValue = long(req(n, VALUE)),
                marketCap = long(req(n, MKTCAP)), shares = long(req(n, SHARES)),
            )
        }

    fun stockBase(day: LocalDate, market: String): List<OpenBaseRow> =
        fetch(service("sto", market, "isu_base_info"), day).map { n ->
            OpenBaseRow(req(n, BASE_SYMBOL).padStart(6, '0'), req(n, BASE_NAME), req(n, BASE_MARKET), req(n, BASE_KIND))
        }

    /** family: KRX | KOSPI | KOSDAQ */
    fun indexDaily(day: LocalDate, family: String): List<OpenIndexRow> {
        val svc = when (family) {
            "KRX" -> "idx/krx_dd_trd"
            "KOSPI" -> "idx/kospi_dd_trd"
            "KOSDAQ" -> "idx/kosdaq_dd_trd"
            else -> throw IllegalArgumentException("지수 계열은 KRX|KOSPI|KOSDAQ: $family")
        }
        return fetch(svc, day).map { n ->
            OpenIndexRow(parseDay(req(n, DAY)), req(n, IDX_NAME), num(req(n, IDX_OPEN)), num(req(n, IDX_HIGH)), num(req(n, IDX_LOW)), num(req(n, IDX_CLOSE)),
                optLong(n, VOLUME), optLong(n, VALUE),
            )
        }
    }

    private fun service(group: String, market: String, suffix: String): String {
        val prefix = when (market) {
            "KOSPI" -> "stk"
            "KOSDAQ" -> "ksq"
            else -> throw IllegalArgumentException("시장은 KOSPI|KOSDAQ: $market")
        }
        return "$group/${prefix}_$suffix"
    }

    private fun fetch(svc: String, day: LocalDate): List<JsonNode> {
        val key = props.krxOpen.apiKey
        if (key.isBlank()) throw SourceAuthError(SOURCE, "KRX Open API 키(krx_openapi_key)가 설정되지 않았다")
        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceQuotaError(SOURCE, "쿨다운 중 — 조회 생략(${remaining.toLong()}초 남음)")
        val data = try {
            val r = http.get("${props.krxOpen.baseUrl}/$svc", mapOf("basDd" to day.format(BAS_DD)), mapOf("AUTH_KEY" to key), TIMEOUT)
            HttpErrors.check(SOURCE, r, svc)
            try {
                json.readTree(r.body)
            } catch (e: Exception) {
                throw SourceSchemaError(SOURCE, "$svc 응답이 JSON 이 아니다")
            }
        } catch (e: Exception) {
            throw fail(HttpErrors.transport(SOURCE, e, svc), key)
        }
        val block = data.path(OUT_BLOCK)
        if (!block.isArray) throw fail(SourceSchemaError(SOURCE, "$svc 응답에 $OUT_BLOCK 배열이 없다"), key)
        return (0 until block.size()).map { block.get(it) }
    }

    private fun fail(e: DataSourceError, key: String): DataSourceError {
        val masked = e.copyWithDetail(e.detail.replace(key, "***"))
        cooldowns.noteFailure(masked)
        log.warn("KRX Open API 호출 실패: {}", masked.message)
        return masked
    }

    private fun req(n: JsonNode, field: String): String {
        val v = n.get(field)
        if (v == null || v.isNull) throw SourceSchemaError(SOURCE, "응답 행에 필수 키 $field 가 없다")
        return v.asString("").trim()
    }

    /** 필수 키가 아닌 정수 — 키가 없거나 null 이면 null. */
    private fun optLong(n: JsonNode, field: String): Long? = n.get(field)?.takeIf { !it.isNull }?.let { long(it.asString("").trim()) }

    private fun parseDay(s: String): LocalDate =
        try { LocalDate.parse(s, BAS_DD) } catch (e: Exception) { throw SourceSchemaError(SOURCE, "날짜 형식 불일치: $s") }

    private fun clean(s: String): String? = s.replace(",", "").trim().takeIf { it.isNotEmpty() && it != "-" }

    private fun num(s: String): Double? = clean(s)?.let { it.toDoubleOrNull() ?: throw SourceSchemaError(SOURCE, "숫자 형식 불일치: $s") }

    private fun long(s: String): Long? = clean(s)?.let { it.toLongOrNull() ?: throw SourceSchemaError(SOURCE, "정수 형식 불일치: $s") }

    companion object {
        const val SOURCE = "krx_open"
        private val TIMEOUT: Duration = Duration.ofSeconds(20)
        private val BAS_DD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        // 필드 키는 2026-11-23 실측과 일치(docs/migration-notes.md "공식 경로 실측"). 일별매매 ISU_CD 는 6자리, 기본정보 ISU_CD 는 ISIN 이라 ISU_SRT_CD 를 쓴다.
        private const val OUT_BLOCK = "OutBlock_1"
        private const val DAY = "BAS_DD"
        private const val DAILY_SYMBOL = "ISU_CD"
        private const val DAILY_MARKET = "MKT_NM"
        private const val CLOSE = "TDD_CLSPRC"
        private const val PREV_DIFF = "CMPPREVDD_PRC"
        private const val OPEN = "TDD_OPNPRC"
        private const val HIGH = "TDD_HGPRC"
        private const val LOW = "TDD_LWPRC"
        private const val VOLUME = "ACC_TRDVOL"
        private const val VALUE = "ACC_TRDVAL"
        private const val MKTCAP = "MKTCAP"
        private const val SHARES = "LIST_SHRS"
        private const val BASE_SYMBOL = "ISU_SRT_CD"
        private const val BASE_NAME = "ISU_ABBRV"
        private const val BASE_MARKET = "MKT_TP_NM"
        private const val BASE_KIND = "KIND_STKCERT_TP_NM"
        private const val IDX_NAME = "IDX_NM"
        private const val IDX_CLOSE = "CLSPRC_IDX"
        private const val IDX_OPEN = "OPNPRC_IDX"
        private const val IDX_HIGH = "HGPRC_IDX"
        private const val IDX_LOW = "LWPRC_IDX"
    }
}
