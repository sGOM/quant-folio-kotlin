package com.quantfolio.market.official

import com.quantfolio.domain.TestApp
import com.quantfolio.market.store.IndexBar
import com.quantfolio.market.store.IndexOhlcvStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.InMemoryFetchLedger
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.time.LocalDate
import kotlin.test.assertEquals

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class McapUniverseTest(
    @Autowired private val daily: StockDailyStore,
    @Autowired private val indexes: IndexOhlcvStore,
) {
    private val ledger = InMemoryFetchLedger()
    private val universe = McapUniverse(daily, LocalTradingDays(ledger))

    // 공유 DB 의 다른 테스트와 겹치지 않는 먼 미래의 평일 5일.
    private val days = generateSequence(LocalDate.of(2330, 3, 1)) { it.plusDays(1) }.filter { it.dayOfWeek.value <= 5 }.take(5).toList()
    private val d0 = days[0]
    private val d1 = days[1]
    private val d2 = days[2]

    @BeforeEach
    fun clean() {
        indexes.delete("1001")
        days.forEach { daily.delete(it) }
        ledger.trading(days)
    }

    private fun row(sym: String, cap: Long?, kind: String? = null, market: String = "KOSPI") = StockDaily(sym, market = market, marketCap = cap, stockKind = kind)

    @Test
    fun `uses previous trading day caps not same day`() {
        daily.write(d0, listOf(row("000001", 10, "보통주"), row("000002", 20, "보통주")))
        daily.write(d1, listOf(row("000001", 99), row("000002", 1)))
        assertEquals(listOf("000002", "000001"), universe.members(d1, 2))
    }

    @Test
    fun `preferred shares excluded`() {
        daily.write(d0, listOf(row("000001", 10, "보통주"), row("000002", 50, "우선주"), row("000003", 5, "보통주"), row("000004", 99, "보통주", "KOSDAQ")))
        daily.write(d1, listOf(row("000001", 10), row("000002", 50), row("000003", 5)))
        assertEquals(listOf("000001", "000003"), universe.members(d2, 5))
    }

    @Test
    fun `kind from an earlier base-info day is used when previous day row has none`() {
        daily.write(d0, listOf(row("000001", 10, "보통주"), row("000002", 50, "우선주")))
        daily.write(d1, listOf(row("000001", 10), row("000002", 50)))
        assertEquals(listOf("000001"), universe.members(d2, 5))
    }

    @Test
    fun `no known stock kind at all throws`() {
        daily.write(d0, listOf(row("000001", 10), row("000002", 50)))
        val e = assertThrows<MarketDataNotLoaded> { universe.members(d1, 5) }
        assertEquals("stock_kind", e.kind)
    }

    @Test
    fun `symbol without known kind is excluded`() {
        daily.write(d0, listOf(row("000001", 10, "보통주"), row("000002", 50)))
        assertEquals(listOf("000001"), universe.members(d1, 5))
    }

    @Test
    fun `missing previous day row throws`() {
        daily.write(d0, listOf(row("000001", 10, "보통주")))
        assertThrows<MarketDataNotLoaded> { universe.members(d2, 5) } // 직전 거래일 d1 행 없음
    }

    @Test
    fun `tie broken by code`() {
        daily.write(d0, listOf(row("000003", 10, "보통주"), row("000001", 10, "보통주"), row("000002", 10, "보통주")))
        assertEquals(listOf("000001", "000002"), universe.members(d1, 2))
    }

    @Test
    fun `lookahead - perturb caps after cut, members up to cut unchanged`() {
        daily.write(d0, listOf(row("000001", 10, "보통주"), row("000002", 20, "보통주"), row("000003", 30, "보통주")))
        daily.write(d1, listOf(row("000001", 10), row("000002", 20), row("000003", 30)))
        daily.write(d2, listOf(row("000001", 10), row("000002", 20), row("000003", 30)))
        val before = listOf(d1, d2).map { universe.members(it, 2) }
        // cut = d1 이후(d2 이후) 시총을 뒤집는다: d1·d2 의 후보는 직전일(d0·d1) 값만 보므로 그대로여야 한다.
        daily.write(d2, listOf(row("000001", 999_999), row("000002", 1), row("000003", 1)))
        daily.write(days[3], listOf(row("000001", 999_999), row("000002", 1), row("000003", 1)))
        assertEquals(before, listOf(d1, d2).map { universe.members(it, 2) })
    }

    private fun seed3(day: LocalDate, a: Long, b: Long, c: Long, kind: Boolean = false) = daily.write(
        day, listOf(row("000001", a, if (kind) "보통주" else null), row("000002", b, if (kind) "보통주" else null), row("000003", c, if (kind) "보통주" else null)),
    )

    @Test
    fun `pool provider recomputes per day even within the same month`() {
        seed3(d0, 30, 20, 10, kind = true)
        seed3(d1, 10, 20, 30) // 순위 역전
        seed3(d2, 10, 20, 30)
        val pool = universe.pool(d1, d2, 1)
        assertEquals(listOf("000001"), pool.members(d1)) // d0 시총 기준
        assertEquals(listOf("000003"), pool.members(d2)) // d1 시총 기준
    }

    @Test
    fun `pool union covers every day's members in range`() {
        seed3(d0, 30, 20, 10, kind = true)
        seed3(d1, 10, 20, 30)
        seed3(d2, 10, 20, 30)
        seed3(days[3], 10, 30, 20)
        val pool = universe.pool(d1, days[3], 1)
        // d1->d0 기준 000001, d2->d1 기준 000003, d3->d2 기준 000003
        assertEquals(listOf("000001", "000003"), pool.union)
        assertEquals(pool.union.toSet(), (listOf(d1, d2, days[3])).flatMap { pool.members(it) }.toSet())
    }

    @Test
    fun `pool lookahead - perturb caps after cut, pools up to cut unchanged`() {
        seed3(d0, 30, 20, 10, kind = true)
        seed3(d1, 30, 20, 10)
        seed3(d2, 30, 20, 10)
        seed3(days[3], 30, 20, 10)
        val before = universe.pool(d1, d2, 1)
        val b = listOf(d1, d2).map { before.members(it) }
        seed3(d2, 1, 1, 999) // cut = d2 이후 교란: d3 의 직전일 값이 바뀔 뿐, d2 까지는 같아야 한다
        seed3(days[3], 1, 1, 999)
        val after = universe.pool(d1, d2, 1)
        assertEquals(before.union, after.union)
        assertEquals(b, listOf(d1, d2).map { after.members(it) })
    }
}
