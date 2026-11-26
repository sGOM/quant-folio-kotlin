package com.quantfolio.worker

import com.quantfolio.market.official.MarketDataNotLoaded
import com.quantfolio.market.official.SnapshotSource
import com.quantfolio.market.store.StockDaily
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.DefaultApplicationArguments
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class ValuationExportRunnerTest {
    private val d1 = LocalDate.of(2017, 6, 1)
    private val d2 = LocalDate.of(2017, 7, 3)

    private fun source(rows: (LocalDate) -> List<StockDaily>): SnapshotSource = object : SnapshotSource {
        override fun fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily> {
            assertEquals(listOf("KOSPI", "KOSDAQ"), markets) // 백테스트 밸류 경로와 같은 호출
            return rows(asOf)
        }
        override fun marketCap(asOf: LocalDate, markets: List<String>) = error("unused")
        override fun priceChange(start: LocalDate, end: LocalDate, markets: List<String>) = error("unused")
        override fun marketOhlcvSnapshot(day: LocalDate, market: String) = error("unused")
        override fun indexOhlcv(start: LocalDate, end: LocalDate, ticker: String) = error("unused")
        override fun sectorIndexes(market: String, day: LocalDate) = error("unused")
    }

    @Test
    fun `writes kospi rows sorted with blank nulls`(@TempDir dir: Path) {
        val out = dir.resolve("valuation.csv")
        val src = source { d ->
            if (d == d1) {
                listOf(
                    StockDaily("000660", market = "KOSPI", per = 12.5, pbr = 1.5, div = 1.2),
                    StockDaily("005930", market = "KOSPI", per = null, pbr = 1.1, div = 2.0),
                    StockDaily("035720", market = "KOSDAQ", per = 30.0, pbr = 3.0, div = 0.0),
                )
            } else {
                listOf(StockDaily("005930", market = "KOSPI", per = 9.0, pbr = null, div = null))
            }
        }
        ValuationExportRunner(src, out.toString(), "2017-07-03,2017-06-01").run(DefaultApplicationArguments())
        assertEquals(
            "date,symbol,per,pbr,div\n" +
                "2017-06-01,000660,12.5,1.5,1.2\n" +
                "2017-06-01,005930,,1.1,2.0\n" +
                "2017-07-03,005930,9.0,,\n",
            Files.readString(out),
        )
    }

    @Test
    fun `export writes nothing when any date fails`(@TempDir dir: Path) {
        val out = dir.resolve("valuation.csv")
        val src = source { d ->
            if (d == d2) throw MarketDataNotLoaded("dart_financials", "00126380")
            listOf(StockDaily("005930", market = "KOSPI", per = 9.0))
        }
        ValuationExportRunner(src, out.toString(), "2017-06-01,2017-07-03").run(DefaultApplicationArguments())
        assertFalse(Files.exists(out))
        assertFalse(Files.exists(dir.resolve("valuation.csv.tmp")))
    }

    @Test
    fun `unexpected exception does not fail worker startup`(@TempDir dir: Path) {
        ValuationExportRunner(source { error("db down") }, dir.resolve("v.csv").toString(), "2017-06-01").run(DefaultApplicationArguments())
    }
}
