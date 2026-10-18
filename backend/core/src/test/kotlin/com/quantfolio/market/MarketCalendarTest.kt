package com.quantfolio.market

import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.krx.HttpResult
import com.quantfolio.market.krx.KrxMdc
import com.quantfolio.market.krx.KrxSession
import com.quantfolio.market.krx.KrxStockApi
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.test.FakeKrxTransport
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZonedDateTime
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MarketCalendarTest {
    private val clock = Clock.systemUTC()
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val api = KrxStockApi(KrxMdc(transport, KrxSession(transport, QuantfolioProperties(), cooldowns, clock), cooldowns) {})
    private val calendar = MarketCalendar(api)

    private fun index(vararg dates: String) = """{"output":[${dates.joinToString(",") {
        """{"TRD_DD":"$it","OPNPRC_IDX":"1","HGPRC_IDX":"1","LWPRC_IDX":"1","CLSPRC_IDX":"1","ACC_TRDVOL":"1","ACC_TRDVAL":"1","MKTCAP":"1"}"""
    }}]}"""

    /** 코스피 지수 응답: endDd 가 주어진 날이면 prevLast 를, strtDd 가 다음날이면 after 를 돌려준다. */
    private fun kospi(day: String, prevLast: String, after: List<String>) {
        transport.on({ it.form["endDd"] == day }) { HttpResult(200, index(prevLast)) }
        val next = LocalDate.parse(day, java.time.format.DateTimeFormatter.BASIC_ISO_DATE).plusDays(1)
        transport.on({ it.form["strtDd"] == next.format(java.time.format.DateTimeFormatter.BASIC_ISO_DATE) }) {
            HttpResult(200, index(*after.toTypedArray()))
        }
    }

    @Test
    fun weekendIsClosedWithoutRequest() {
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 17)))
        assertTrue(transport.calls.isEmpty())
    }

    @Test
    fun publishedDayIsBusinessDay() {
        kospi("20261016", "2026/10/16", emptyList())
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 16)))
    }

    @Test
    fun holidayWithLaterDataIsClosed() {
        kospi("20261009", "2026/10/08", listOf("2026/10/12"))
        assertFalse(calendar.isBusinessDay(LocalDate.of(2026, 10, 9)))
    }

    @Test
    fun unpublishedTodayIsAssumedOpen() {
        kospi("20261019", "2026/10/16", emptyList())
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 19)))
    }

    @Test
    fun lookupFailureAssumesOpen() {
        transport.failWith { true }
        assertTrue(calendar.isBusinessDay(LocalDate.of(2026, 10, 20)))
    }

    @Test
    fun resultIsCached() {
        kospi("20261016", "2026/10/16", emptyList())
        calendar.isBusinessDay(LocalDate.of(2026, 10, 16))
        val n = transport.calls.size
        calendar.isBusinessDay(LocalDate.of(2026, 10, 16))
        assertEquals(n, transport.calls.size)
    }

    @Test
    fun marketHoursInclusive() {
        kospi("20261016", "2026/10/16", emptyList())
        fun at(h: Int, m: Int) = ZonedDateTime.of(2026, 10, 16, h, m, 0, 0, MarketClock.KST)
        assertFalse(calendar.isMarketOpen(at(8, 59)))
        assertTrue(calendar.isMarketOpen(at(9, 0)))
        assertTrue(calendar.isMarketOpen(at(15, 30)))
        assertFalse(calendar.isMarketOpen(at(15, 31)))
    }

    @Test
    fun estimatedTradingDays() {
        val days = MarketCalendar.estimatedTradingDays(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31))
        assertEquals(365 * 5.0 / 7 - 365 * (15.0 / 365), days, 1e-9)
        assertEquals(0.0, MarketCalendar.estimatedTradingDays(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 1, 1)))
    }
}
