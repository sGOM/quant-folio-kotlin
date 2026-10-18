package com.quantfolio.market.dart

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.HttpBytes
import com.quantfolio.market.source.SourceAuthError
import com.quantfolio.market.source.SourceQuotaError
import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.ByteArrayOutputStream
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OpenDartClientTest {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T23:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val cooldowns = Cooldowns(clock)
    private val key = "TESTKEY123"
    private fun client(apiKey: String = key) =
        OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = apiKey)), cooldowns, clock)

    private fun status(code: String) = http.onText({ it.url.endsWith("list.json") }, body = """{"status":"$code","message":"m"}""")

    @Test
    fun disabledWithoutKey() {
        val c = client("")
        assertFalse(c.enabled())
        assertNull(c.get("list.json", emptyMap()))
        assertNull(c.corpCodeMap())
        assertTrue(http.calls.isEmpty())
    }

    @Test
    fun okReturnsJsonAndSendsKey() {
        http.onText({ it.url == "https://opendart.fss.or.kr/api/list.json" }, body = """{"status":"000","list":[{"a":1}]}""")
        val data = client().get("list.json", mapOf("corp_code" to "001"))!!
        assertEquals(1, data.path("list").size())
        assertEquals(mapOf("crtfc_key" to key, "corp_code" to "001"), http.calls.single().query)
    }

    @Test
    fun noDataIsNull() {
        status("013")
        assertNull(client().get("list.json", emptyMap()))
    }

    @Test
    fun statusCauses() {
        mapOf("010" to SourceAuthError::class, "014" to SourceRequestError::class, "100" to SourceRequestError::class,
            "800" to SourceUnavailableError::class, "999" to SourceUnavailableError::class).forEach { (code, cls) ->
            val c = OpenDartClient(http, QuantfolioProperties(opendart = QuantfolioProperties.OpenDart(apiKey = key)), Cooldowns(clock), clock)
            status(code)
            val e = runCatching { c.get("list.json", emptyMap()) }.exceptionOrNull()
            assertTrue(cls.isInstance(e), "$code → $e")
        }
    }

    @Test
    fun quotaRaisesWithRetryAfterUntilMidnight() {
        status("020")
        val e = assertThrows<SourceQuotaError> { client().get("list.json", emptyMap()) }
        assertEquals(3600.0, e.retryAfterSeconds)
        assertEquals(3600.0, cooldowns.remaining("dart"))
        assertThrows<SourceQuotaError> { client().get("list.json", emptyMap()) } // 쿨다운 중이라 요청 안 함
        assertEquals(1, http.calls.size)
    }

    @Test
    fun keyMaskedInErrors() {
        http.on({ true }) { throw java.io.IOException("refused https://opendart.fss.or.kr/api/x?crtfc_key=$key&y=1") }
        val e = assertThrows<SourceUnavailableError> { client().get("list.json", emptyMap()) }
        assertFalse(key in e.message!!)
        assertTrue("crtfc_key=***" in e.message!!)
    }

    private fun corpZip(xml: String): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z -> z.putNextEntry(ZipEntry("CORPCODE.xml")); z.write(xml.toByteArray()); z.closeEntry() }
        return out.toByteArray()
    }

    @Test
    fun corpCodeMapParsesListedOnly() {
        http.on({ it.url.endsWith("corpCode.xml") }) {
            HttpBytes(200, corpZip("<result><list><corp_code>00126380</corp_code><stock_code>5930</stock_code></list><list><corp_code>0999</corp_code><stock_code> </stock_code></list></result>"))
        }
        assertEquals(mapOf("005930" to "00126380"), client().corpCodeMap())
    }

    @Test
    fun corpCodeMapEmptyIsSchema() {
        http.on({ it.url.endsWith("corpCode.xml") }) { HttpBytes(200, corpZip("<result></result>")) }
        assertThrows<SourceSchemaError> { client().corpCodeMap() }
    }
}
