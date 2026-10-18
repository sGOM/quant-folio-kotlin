package com.quantfolio.market.krx

import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.convertValue
import java.time.Duration
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * KRX 데이터 포털 `getJsonData.cmd` 요청. 두 경로가 있다.
 * - [rows]: 원본 `krx_index._krx_rows` — 응답을 원인별로 분류하고 쿨다운을 건다.
 * - [pykrx]: 원본 pykrx `KrxWebIo.read` — 분류 없이 결과만. 호출자가 실패를 Unavailable 로 감싼다(원본 metrics/fetch).
 */
@Component
class KrxMdc(
    private val transport: KrxTransport,
    private val session: KrxSession,
    private val cooldowns: Cooldowns,
    private val sleeper: (Duration) -> Unit = { Thread.sleep(it) },
) {
    private val json = JsonMapper.builder().build()

    fun rows(payload: Map<String, String>, key: String, label: String, timeout: Duration = Duration.ofSeconds(20)): List<Map<String, Any?>> {
        val remaining = cooldowns.remaining(KrxSession.SOURCE)
        if (remaining > 0) throw SourceAuthError(KrxSession.SOURCE, "$label 조회 생략 — 쿨다운 중(${remaining.toLong()}초 남음)")

        val result = try {
            transport.postForm(JSON_URL, payload, headers(KrxSession.USER_AGENT), timeout)
        } catch (e: Exception) {
            throw fail(SourceUnavailableError(KrxSession.SOURCE, "$label 연결 실패: ${e.message}"))
        }
        if (result.status >= 500) throw fail(SourceUnavailableError(KrxSession.SOURCE, "$label 서버 오류: HTTP ${result.status}"))
        if (result.status >= 400) throw fail(SourceRequestError(KrxSession.SOURCE, "$label 요청 오류: HTTP ${result.status}"))

        val data = runCatching { json.readTree(result.body) }.getOrNull()
        if (data == null || !(data.isObject || data.isArray)) {
            throw fail(SourceAuthError(KrxSession.SOURCE, "$label 응답이 JSON 이 아니다(차단 추정)"))
        }
        if (!data.isObject || !data.has(key)) {
            throw fail(SourceSchemaError(KrxSession.SOURCE, "$label 응답에 '$key' 키가 없다: ${result.body.take(120)}"))
        }
        val node = data.get(key)
        if (node.isNull || (node.isArray && node.isEmpty)) return emptyList()
        if (!node.isArray || node.any { !it.isObject }) {
            throw fail(SourceSchemaError(KrxSession.SOURCE, "$label 응답의 '$key' 가 dict 리스트가 아니다: ${node.toString().take(120)}"))
        }
        return toRows(node)
    }

    /** strtDd·endDd 가 있으면 730일 단위로 쪼개 요청하고 조각 사이 1초 쉰다(원본: 초당 2년). */
    fun pykrx(bld: String, params: Map<String, String>, key: String): List<Map<String, Any?>> {
        runCatching { session.current() } // 로그인 쿠키 확보 시도. 실패하면 비인증 요청(원본 pykrx 와 동일).
        val start = params["strtDd"]
        val end = params["endDd"]
        if (start == null || end == null) return toRows(read(bld, params).path(key))

        var from = LocalDate.parse(start, YMD)
        val to = LocalDate.parse(end, YMD)
        var merged: MutableList<Map<String, Any?>>? = null
        while (from.plusDays(CHUNK_DAYS).isBefore(to)) {
            val chunkEnd = from.plusDays(CHUNK_DAYS)
            val node = read(bld, params + mapOf("strtDd" to from.format(YMD), "endDd" to chunkEnd.format(YMD)))
            merged = append(merged, node, key)
            from = chunkEnd.plusDays(1)
            sleeper(Duration.ofSeconds(1))
        }
        if (!from.isAfter(to)) {
            val node = read(bld, params + mapOf("strtDd" to from.format(YMD), "endDd" to to.format(YMD)))
            merged = append(merged, node, key)
        }
        return merged ?: emptyList()
    }

    /** 원본은 두 번째 조각부터 항상 `result["output"]` 에 이어붙인다 — 키가 output 이 아니면 KeyError(보존). */
    private fun append(acc: MutableList<Map<String, Any?>>?, node: JsonNode, key: String): MutableList<Map<String, Any?>> {
        if (acc == null) return toRows(node.path(key)).toMutableList()
        check(key == "output" && node.has("output")) { "KeyError: 'output' (pykrx 조각 병합)" }
        acc += toRows(node.path("output"))
        return acc
    }

    private fun read(bld: String, params: Map<String, String>): JsonNode {
        val result = transport.postForm(JSON_URL, params + ("bld" to bld), headers("Mozilla/5.0"), Duration.ofSeconds(30))
        return json.readTree(result.body)
    }

    private fun toRows(node: JsonNode): List<Map<String, Any?>> =
        if (node.isArray) (0 until node.size()).map { json.convertValue<Map<String, Any?>>(node.get(it)) } else emptyList()

    private fun headers(userAgent: String) = mapOf(
        "User-Agent" to userAgent,
        "Referer" to "https://data.krx.co.kr/contents/MDC/MDI/outerLoader/index.cmd",
        "X-Requested-With" to "XMLHttpRequest",
    )

    private fun fail(e: DataSourceError): DataSourceError {
        cooldowns.noteFailure(e)
        return e
    }

    companion object {
        const val JSON_URL = "https://data.krx.co.kr/comm/bldAttendant/getJsonData.cmd"
        private const val CHUNK_DAYS = 730L
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
    }
}
