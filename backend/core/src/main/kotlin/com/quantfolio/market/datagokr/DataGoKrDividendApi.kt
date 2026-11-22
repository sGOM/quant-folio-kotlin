package com.quantfolio.market.datagokr

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpErrors
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import java.net.URLEncoder
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 현금배당 1건(주당 일반 현금배당금 > 0). [dps] 는 기준일 당시 주식 수 기준 원 — 이후 분할은 반영돼 있지 않다. */
data class CashDividend(val symbol: String, val recordDate: LocalDate, val dps: Double)

/** 한 페이지. [skipped] 는 현금배당이 아니거나 금액이 0 이하라 거른 행 수. */
data class DividendPage(val totalCount: Int, val rawCount: Int, val dividends: List<CashDividend>, val skipped: Int)

/**
 * 공공데이터포털 금융위원회 주식배당정보(15043284) 전송·파싱. 필터 없이 호출하면 전 종목 전 이력이 페이지로 나온다
 * (`basDt` 를 주면 0행이라 보내지 않는다 — 2026-11 실측). 키는 예외·로그에 남기지 않는다.
 * 공지일 필드가 없어 announcedOn 은 알 수 없다(호출자가 null 로 저장한다).
 */
@Component
class DataGoKrDividendApi(
    private val http: ExternalHttp,
    private val props: QuantfolioProperties,
    private val cooldowns: Cooldowns,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    fun page(pageNo: Int): DividendPage {
        val cfg = props.dataGoKr
        if (cfg.apiKey.isBlank()) throw SourceAuthError(SOURCE, "공공데이터포털 키(datagokr_api_key)가 설정되지 않았다")
        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceQuotaError(SOURCE, "쿨다운 중 — 조회 생략(${remaining.toLong()}초 남음)")
        try {
            val r = http.get(
                "${cfg.baseUrl}/getDiviInfo_V2",
                mapOf("serviceKey" to cfg.apiKey, "resultType" to "json", "numOfRows" to cfg.pageSize.toString(), "pageNo" to pageNo.toString()),
                emptyMap(), TIMEOUT,
            )
            val root = try {
                json.readTree(r.body)
            } catch (e: Exception) {
                if (r.status in 200..299) throw SourceSchemaError(SOURCE, "응답이 JSON 이 아니다")
                HttpErrors.check(SOURCE, r, LABEL)
                throw SourceSchemaError(SOURCE, "$LABEL HTTP ${r.status} 응답이 JSON 이 아니다")
            }
            gatewayError(root)?.let { throw it }
            if (r.status !in 200..299) HttpErrors.check(SOURCE, r, LABEL)
            return parse(root)
        } catch (e: Exception) {
            throw fail(HttpErrors.transport(SOURCE, e, LABEL), cfg.apiKey)
        }
    }

    /** 게이트웨이 오류 본문(`OpenAPI_ServiceResponse`)의 코드 분류. 정상 응답이면 null. */
    private fun gatewayError(root: JsonNode): DataSourceError? {
        val h = root.path("OpenAPI_ServiceResponse").path("cmmMsgHeader")
        if (h.isMissingNode) return null
        return byCode(h.path("returnReasonCode").asString(""), h.path("errMsg").asString(""))
    }

    private fun byCode(code: String, msg: String): DataSourceError {
        val detail = "$LABEL 오류 코드 $code $msg"
        return when (code) {
            "20", "21", "30", "31", "32", "33" -> SourceAuthError(SOURCE, detail)
            "22" -> SourceQuotaError(SOURCE, detail)
            "01", "02", "04", "05" -> SourceUnavailableError(SOURCE, detail)
            "10", "11", "12" -> SourceRequestError(SOURCE, detail)
            else -> SourceSchemaError(SOURCE, detail)
        }
    }

    private fun parse(root: JsonNode): DividendPage {
        val resp = root.path("response")
        if (resp.isMissingNode) throw SourceSchemaError(SOURCE, "응답에 response 가 없다")
        val code = resp.path("header").path("resultCode").asString("")
        if (code != "00") throw byCode(code, resp.path("header").path("resultMsg").asString(""))
        val body = resp.path("body")
        val total = body.path("totalCount").let { if (it.isNumber) it.asInt() else it.asString("").trim().toIntOrNull() }
            ?: throw SourceSchemaError(SOURCE, "body.totalCount 가 없다")
        val items = body.path("items").let { if (it.isObject) it.path("item") else null } // 0행이면 items 가 빈 문자열
        val nodes = when {
            items == null || items.isMissingNode || items.isNull -> emptyList()
            items.isArray -> (0 until items.size()).map { items.get(it) }
            items.isObject -> listOf(items) // 1행이면 배열이 아니라 객체
            else -> throw SourceSchemaError(SOURCE, "items.item 형식 불일치")
        }
        var skipped = 0
        val out = ArrayList<CashDividend>(nodes.size)
        for (n in nodes) {
            val isin = req(n, "isinCd")
            val kind = req(n, "stckDvdnRcd")
            val amt = req(n, "stckGenrDvdnAmt")
            val dps = amt.replace(",", "").trim().takeIf { it.isNotEmpty() && it != "-" }?.let {
                it.toDoubleOrNull() ?: throw SourceSchemaError(SOURCE, "배당금 형식 불일치: $it")
            }
            val symbol = isin.takeIf { it.length == 12 }?.substring(3, 9)
            if (kind != CASH || dps == null || !(dps > 0) || symbol == null || !symbol.all { it.isLetterOrDigit() }) {
                skipped++
                continue
            }
            val record = req(n, "dvdnBasDt").let {
                try { LocalDate.parse(it, YMD) } catch (e: Exception) { throw SourceSchemaError(SOURCE, "날짜 형식 불일치: $it") }
            }
            out += CashDividend(symbol, record, dps)
        }
        return DividendPage(total, nodes.size, out, skipped)
    }

    private fun req(n: JsonNode, field: String): String {
        val v = n.get(field)
        if (v == null || v.isNull) throw SourceSchemaError(SOURCE, "응답 행에 필수 키 $field 가 없다")
        return v.asString("").trim()
    }

    private fun fail(e: DataSourceError, key: String): DataSourceError {
        val masked = e.copyWithDetail(e.detail.replace(key, "***").replace(URLEncoder.encode(key, Charsets.UTF_8), "***"))
        cooldowns.noteFailure(masked)
        log.warn("공공데이터포털 배당 호출 실패: {}", masked.message)
        return masked
    }

    companion object {
        const val SOURCE = "datagokr"
        private const val LABEL = "getDiviInfo_V2"
        private const val CASH = "02" // 주식배당구분코드: 현금배당
        private val TIMEOUT: Duration = Duration.ofSeconds(60)
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
