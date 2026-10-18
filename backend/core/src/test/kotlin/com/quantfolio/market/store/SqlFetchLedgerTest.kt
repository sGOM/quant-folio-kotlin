package com.quantfolio.market.store

import com.quantfolio.domain.TestApp
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class SqlFetchLedgerTest(@Autowired private val ledger: SqlFetchLedger) {
    @Test
    fun putGetOverwrite() {
        val key = UUID.randomUUID().toString()
        assertNull(ledger.get("fundamentals", key))
        ledger.put("fundamentals", key, 0, false)
        ledger.put("fundamentals", key, 2500, true)
        assertEquals(LedgerEntry(2500, true), ledger.get("fundamentals", key))
    }
}
