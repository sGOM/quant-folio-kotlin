package com.quantfolio.market.kis

import com.quantfolio.domain.TestApp
import com.quantfolio.domain.market.KisStockMasterSnapshotRepository
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.HttpBytes
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class KisStockMasterTest(@Autowired private val repo: KisStockMasterSnapshotRepository) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:00:00Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp()
    private val master = KisStockMaster(http, Cooldowns(clock), repo, clock)
    private val cp949 = Charset.forName("MS949")

    /** 원본 고정폭 규격으로 한 줄을 만든다. values 에 없는 필드는 공백. */
    private fun line(market: String, code: String, std: String, name: String, values: Map<String, String> = emptyMap()): String {
        val (widths, columns) = KisStockMaster.layout(market)
        val tail = columns.indices.joinToString("") { i -> (values[columns[i]] ?: "").padEnd(widths[i]).take(widths[i]) }
        return code.padEnd(9) + std.padEnd(12) + name + tail
    }

    private fun zip(vararg lines: String, entry: String = "kospi_code.mst"): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            z.putNextEntry(ZipEntry(entry))
            z.write(lines.joinToString("\n").toByteArray(cp949))
            z.closeEntry()
        }
        return out.toByteArray()
    }

    private fun serve(market: String, bytes: ByteArray) =
        http.on({ it.url.endsWith(if (market == "KOSPI") "kospi_code.mst.zip" else "kosdaq_code.mst.zip") }) { HttpBytes(200, bytes) }

    @Test
    fun parsesFixedWidthWithKoreanName() {
        serve("KOSPI", zip(line("KOSPI", "5930", "KR7005930003", "삼성전자", mapOf("관리종목" to "N", "기준가" to "71000"))))
        val row = master.fetch("KOSPI").single()
        assertEquals("005930", row.symbol)
        assertEquals("삼성전자", row.name)
        assertEquals("N", row.raw["관리종목"])
        assertEquals("71000", row.raw["기준가"])
        assertEquals("", row.raw["거래정지"])
        assertEquals("KR7005930003", row.raw["표준코드"])
    }

    @Test
    fun shortLineIsSchema() {
        serve("KOSPI", zip("005930 짧은줄"))
        assertThrows<SourceSchemaError> { master.fetch("KOSPI") }
    }

    @Test
    fun zipWithoutMstIsSchema() {
        serve("KOSPI", zip("x", entry = "readme.txt"))
        assertThrows<SourceSchemaError> { master.fetch("KOSPI") }
    }

    @Test
    fun brokenZipIsSchema() {
        serve("KOSPI", "not a zip".toByteArray())
        assertThrows<SourceSchemaError> { master.fetch("KOSPI") }
    }

    @Test
    fun oneMarketFailureStillSaves() {
        serve("KOSPI", zip(line("KOSPI", "800001", "KR0", "코스피종목")))
        http.on({ it.url.endsWith("kosdaq_code.mst.zip") }) { HttpBytes(503, ByteArray(0)) }
        val day = LocalDate.of(2001, 2, 2)
        assertEquals(1, master.snapshot(day))
        assertEquals(listOf("800001"), repo.findAll().filter { it.tradeDate == day }.map { it.symbol })
    }

    @Test
    fun bothMarketsFailRaisesRepresentative() {
        http.on({ true }) { HttpBytes(503, ByteArray(0)) }
        assertThrows<SourceUnavailableError> { master.snapshot(LocalDate.of(2001, 2, 3)) }
    }

    @Test
    fun resnapshotReplacesAndLatestIsNewest() {
        val kosdaqLine = line("KOSDAQ", "810001", "KR1", "코스닥종목", mapOf("관리 종목 여부" to "Y"))
        serve("KOSPI", zip(line("KOSPI", "800002", "KR0", "a")))
        serve("KOSDAQ", zip(kosdaqLine, entry = "kosdaq_code.mst"))
        master.snapshot(LocalDate.of(2001, 3, 1))
        master.snapshot(LocalDate.of(2001, 3, 1))
        master.snapshot(LocalDate.of(2001, 3, 2))
        assertEquals(1, repo.findAll().count { it.symbol == "810001" && it.tradeDate == LocalDate.of(2001, 3, 1) })
        val latest = master.latest("810001")!!
        assertEquals(LocalDate.of(2001, 3, 2), latest["trade_date"])
        assertEquals("KOSDAQ", latest["market"])
        assertEquals("Y", latest["관리 종목 여부"])
    }

    @Test
    fun managementBlockMessages() {
        val today = LocalDate.of(2026, 10, 19)
        val d = LocalDate.of(2026, 10, 16)
        assertEquals(
            "관리종목 지정 종목(기준일 2026-10-16) — 신규 매수 차단",
            ManagementBlock.reason(mapOf("trade_date" to d, "market" to "KOSPI", "관리종목" to "Y"), today),
        )
        assertEquals(
            "정리매매 종목(기준일 2026-10-16) — 신규 매수 차단",
            ManagementBlock.reason(mapOf("trade_date" to d, "market" to "KOSDAQ", "정리매매 여부" to "Y"), today),
        )
        assertNull(ManagementBlock.reason(mapOf("trade_date" to d, "market" to "KOSPI", "관리종목" to "N"), today))
        assertNull(ManagementBlock.reason(null, today))
    }

    @Test
    fun staleSnapshotNoBlock() {
        val stale = mapOf("trade_date" to LocalDate.of(2026, 10, 8), "market" to "KOSPI", "관리종목" to "Y")
        assertNull(ManagementBlock.reason(stale, LocalDate.of(2026, 10, 19)))
    }
}
