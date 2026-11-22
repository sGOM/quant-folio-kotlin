package com.quantfolio.market.store

import com.quantfolio.domain.TestApp
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class DividendStoreTest(@Autowired private val store: DividendStore) {
    private val d = LocalDate.of(2102, 12, 31)

    @Test
    fun dividendUpsertBySymbolAndRecordDateKeepsLast() {
        store.write(listOf(DividendEvent("9990", d, 100.0, null, "dart")))
        store.write(listOf(DividendEvent("009990", d, 150.5, d.plusDays(1), "krx"), DividendEvent("009990", d, 200.0, null, "krx")))
        val got = store.read(listOf("009990"), d, d).single()
        assertEquals(200.0, got.dps)
        assertNull(got.announcedOn)
        assertEquals("009990", got.symbol)
        assertEquals("krx", got.source)
    }

    @Test
    fun readFiltersBySymbolsAndRange() {
        store.write(listOf(DividendEvent("009991", d, 1.0, null, "dart"), DividendEvent("009992", d, 2.0, null, "dart"),
            DividendEvent("009991", d.minusYears(1), 3.0, null, "dart")))
        assertEquals(listOf(1.0), store.read(listOf("009991"), d, d).map { it.dps })
        assertEquals(listOf(3.0, 1.0), store.read(listOf("009991"), d.minusYears(2), d).map { it.dps })
        assertEquals(emptyList(), store.read(emptyList(), d, d))
    }
}
