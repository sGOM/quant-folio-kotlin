package com.quantfolio.market.kofia

import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpErrors
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceSchemaError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 미수금·반대매매(원). 반대매매 비중의 분모는 **전일** 미수금(산술관계로 확정). */
data class MarketFunds(
    val asOf: LocalDate,
    val receivable: Double?,
    val forcedSaleAmount: Double?,
    val forcedSaleRatio: Double?,
    val raw: Map<String, Any?>,
)

data class CreditBalance(val asOf: LocalDate, val total: Double?, val partA: Double?, val partB: Double?, val raw: Map<String, Any?>)

/**
 * 금융투자협회 FreeSIS 증시자금(원본 `data/kofia.py`) — 시장 취약성(레버리지 축적·강제청산 압력) 관측용.
 * KRX 와 달리 인증 없이 2008년까지 소급된다. 응답에 컬럼 라벨이 없어 **확정된 컬럼에만 이름을 붙이고** 나머지는 raw 로 통과시킨다.
 * 반대매매 비중은 낙폭 규모를 구분하지 못한다 — 단독 게이트로 쓰지 말 것.
 *
 * 실패와 무자료를 가른다: 전송·스키마 실패는 예외, 정상 응답에 행이 없으면 빈 리스트.
 */
@Component
class KofiaClient(private val http: ExternalHttp, private val cooldowns: Cooldowns) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    fun creditBalance(start: LocalDate, end: LocalDate): List<CreditBalance> =
        rows(OBJ_CREDIT, start, end, "신용융자 잔고").mapNotNull { r ->
            val asOf = ymd(r["TMPV1"]) ?: return@mapNotNull null
            CreditBalance(asOf, num(r["TMPV2"]), num(r["TMPV3"]), num(r["TMPV4"]), r)
        }.sortedBy { it.asOf }

    fun marketFunds(start: LocalDate, end: LocalDate): List<MarketFunds> =
        rows(OBJ_MARKET_FUNDS, start, end, "증시자금").mapNotNull { r ->
            val asOf = ymd(r["TMPV1"]) ?: return@mapNotNull null
            MarketFunds(asOf, num(r["TMPV5"]), num(r["TMPV6"]), num(r["TMPV7"]), r)
        }.sortedBy { it.asOf }

    private fun rows(obj: String, start: LocalDate, end: LocalDate, label: String): List<Map<String, Any?>> {
        require(!start.isAfter(end)) { "start 가 end 보다 늦다: $start > $end" }
        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceQuotaError(SOURCE, "쿨다운 중 — 조회 생략(${remaining.toLong()}초 남음)")
        val body = json.writeValueAsString(
            mapOf(
                "dmSearch" to linkedMapOf(
                    "tmpV40" to "1", "tmpV41" to "1", "tmpV45" to start.format(YMD), "tmpV46" to end.format(YMD), "OBJ_NM" to obj,
                ),
            ),
        )
        val data: Map<String, Any?> = try {
            val r = http.postJson(URL, body, HEADERS, Duration.ofSeconds(20))
            HttpErrors.check(SOURCE, r, label)
            json.readValue(r.body)
        } catch (e: Exception) {
            val exc = HttpErrors.transport(SOURCE, e, label)
            cooldowns.noteFailure(exc)
            log.warn("FreeSIS {} 조회 실패({}~{}): {}", label, start, end, exc.message)
            throw exc
        }
        if ("ds1" !in data) throw SourceSchemaError(SOURCE, "$label 응답에 'ds1' 키가 없다: ${data.toString().take(120)}")
        @Suppress("UNCHECKED_CAST")
        return (data["ds1"] as? List<Map<String, Any?>>).orEmpty()
    }

    private fun num(v: Any?): Double? = when (v) {
        null, "" -> null
        is Number -> v.toDouble()
        else -> v.toString().toDoubleOrNull()
    }

    private fun ymd(v: Any?): LocalDate? {
        val s = v?.toString()?.trim().orEmpty()
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        return runCatching { LocalDate.parse(s, YMD) }.getOrNull()
    }

    companion object {
        const val SOURCE = "kofia"
        const val URL = "https://freesis.kofia.or.kr/meta/getMetaDataList.do"
        private const val OBJ_MARKET_FUNDS = "STATSCU0100000060BO"
        private const val OBJ_CREDIT = "STATSCU0100000070BO"
        private val HEADERS = mapOf(
            "Content-Type" to "application/json; charset=UTF-8",
            "User-Agent" to "Mozilla/5.0",
            "Referer" to "https://freesis.kofia.or.kr/",
        )
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
