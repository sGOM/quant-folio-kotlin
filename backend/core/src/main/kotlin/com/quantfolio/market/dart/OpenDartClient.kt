package com.quantfolio.market.dart

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.DataSourceErrors
import com.quantfolio.market.source.ExternalHttp
import com.quantfolio.market.source.HttpErrors
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.w3c.dom.Element
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.io.ByteArrayInputStream
import java.time.Clock
import java.time.Duration
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * OpenDART(전자공시) 전송 계층(원본 `opendart._get`·`corp_code_map`).
 * **미설정(키 없음)·무자료(status 013)만 null**, 실제 실패는 원인별 예외. 셋을 모두 null 로 뭉개면 일일 한도(020) 소진 시
 * 전 종목이 조용히 '재무 없음'이 되어 백테스트가 빈 팩터 위에서 '성공'한다.
 * API 키는 예외·로그에 절대 남기지 않는다(`crtfc_key=***`).
 */
@Component
class OpenDartClient(
    private val http: ExternalHttp,
    private val props: QuantfolioProperties,
    private val cooldowns: Cooldowns,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    fun enabled(): Boolean = props.opendart.enabled

    fun get(path: String, params: Map<String, String>): JsonNode? {
        if (!enabled()) {
            log.debug("OpenDART 미활성(API 키 없음) — {} 조회 건너뜀", path)
            return null
        }
        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceQuotaError(SOURCE, "쿨다운 중 — 조회 생략(${remaining.toLong()}초 남음)")
        val data = try {
            val r = http.get("${props.opendart.baseUrl}/$path", linkedMapOf("crtfc_key" to props.opendart.apiKey) + params, timeout = TIMEOUT)
            HttpErrors.check(SOURCE, r, path)
            json.readTree(r.body)
        } catch (e: Exception) {
            throw fail(HttpErrors.transport(SOURCE, e, path), path)
        }
        return when (val status = data.path("status").asString("")) {
            STATUS_OK -> data
            STATUS_NO_DATA -> null
            else -> {
                val retryAfter = if (status == STATUS_DAILY_QUOTA) DataSourceErrors.secondsUntilMidnight(clock) else null
                val detail = "$path status=$status msg=${data.path("message").asString("")}"
                val exc = when (status) {
                    "010", "011", "012" -> SourceAuthError(SOURCE, detail, retryAfter)
                    "020", "021" -> SourceQuotaError(SOURCE, detail, retryAfter)
                    "014", "100", "101" -> SourceRequestError(SOURCE, detail, retryAfter)
                    else -> SourceUnavailableError(SOURCE, detail, retryAfter)
                }
                cooldowns.noteFailure(exc)
                log.warn("{}", mask(exc.message))
                throw exc
            }
        }
    }

    /** 종목코드(6자리) → 고유번호(corp_code). corpCode.xml zip. 상장사가 0개면 스키마 변경 의심. 키 없으면 null. */
    fun corpCodeMap(): Map<String, String>? {
        if (!enabled()) return null
        val xml = try {
            val r = http.get("${props.opendart.baseUrl}/corpCode.xml", mapOf("crtfc_key" to props.opendart.apiKey), timeout = TIMEOUT)
            HttpErrors.check(SOURCE, r, "corpCode")
            ZipInputStream(ByteArrayInputStream(r.body)).use { z ->
                z.nextEntry ?: throw SourceSchemaError(SOURCE, "corpCode zip 이 비었다")
                z.readAllBytes()
            }
        } catch (e: Exception) {
            throw fail(HttpErrors.transport(SOURCE, e, "corpCode"), "corpCode")
        }
        val mapping = linkedMapOf<String, String>()
        try {
            val items = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(xml)).getElementsByTagName("list")
            for (i in 0 until items.length) {
                val item = items.item(i) as Element
                val stock = item.getElementsByTagName("stock_code").item(0)?.textContent?.trim().orEmpty()
                val corp = item.getElementsByTagName("corp_code").item(0)?.textContent?.trim().orEmpty()
                if (stock.isNotEmpty() && corp.isNotEmpty()) mapping[stock.padStart(6, '0')] = corp
            }
        } catch (e: Exception) {
            throw SourceSchemaError(SOURCE, "corpCode XML 파싱 실패: ${e.message}")
        }
        if (mapping.isEmpty()) throw SourceSchemaError(SOURCE, "corpCode 에 상장사가 0개 — 응답 스키마 변경 의심")
        log.info("OpenDART corpCode 매핑 로드: {}개 상장사", mapping.size)
        return mapping
    }

    /** 단일회사 전체 재무제표(fnlttSinglAcntAll). 무자료·미설정이면 null. */
    fun accounts(corp: String, year: Int, reprt: String, fsDiv: String): List<Map<String, Any?>>? {
        val data = get("fnlttSinglAcntAll.json", linkedMapOf("corp_code" to corp, "bsns_year" to year.toString(), "reprt_code" to reprt, "fs_div" to fsDiv))
            ?: return null
        val list = data.path("list")
        return (0 until list.size()).map { json.convertValue<Map<String, Any?>>(list.get(it)) }
    }

    private fun fail(e: DataSourceError, label: String): DataSourceError {
        val masked = e.copyWithDetail(mask("$label — ${e.detail}"))
        cooldowns.noteFailure(masked)
        log.warn("OpenDART 호출 실패: {}", masked.message)
        return masked
    }

    private fun mask(message: String?): String {
        var text = message.orEmpty()
        if (props.opendart.apiKey.isNotEmpty()) text = text.replace(props.opendart.apiKey, "***")
        return text.replace(Regex("(crtfc_key=)[^&\\s]+"), "$1***")
    }

    companion object {
        const val SOURCE = "dart"
        private const val STATUS_OK = "000"
        private const val STATUS_NO_DATA = "013"
        private const val STATUS_DAILY_QUOTA = "020"
        private val TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
