package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.test.FakeKrxTransport
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Clock
import java.time.LocalDate
import kotlin.test.assertEquals

class KrxStockApiTest {
    private val clock = Clock.systemUTC()
    private val transport = FakeKrxTransport()
    private val cooldowns = Cooldowns(clock)
    private val mdc = KrxMdc(transport, KrxSession(transport, QuantfolioProperties(), cooldowns, clock), cooldowns) {}
    private val api = KrxStockApi(mdc)

    private fun bld(code: String) = "dbms/MDC/STAT/standard/$code"

    @Test
    fun marketOhlcvByTickerCleansNumbers() {
        transport.onBld(
            bld("MDCSTAT01501"),
            """{"OutBlock_1":[
              {"ISU_SRT_CD":"005930","TDD_OPNPRC":"71,000","TDD_HGPRC":"72,000","TDD_LWPRC":"70,500","TDD_CLSPRC":"71,500",
               "ACC_TRDVOL":"12,345,678","ACC_TRDVAL":"880,000,000,000","FLUC_RT":"-1.23","MKTCAP":"426,000,000,000,000"},
              {"ISU_SRT_CD":"000020","TDD_OPNPRC":"-","TDD_HGPRC":"","TDD_LWPRC":"-","TDD_CLSPRC":"-",
               "ACC_TRDVOL":"0","ACC_TRDVAL":"0","FLUC_RT":"-","MKTCAP":"1,000"}
            ]}""",
        )
        val rows = api.marketOhlcvByTicker("20261016", "KOSPI")
        assertEquals(TickerOhlcv("005930", 71000, 72000, 70500, 71500, 12345678, 880_000_000_000, -1.23, 426_000_000_000_000), rows[0])
        assertEquals(TickerOhlcv("000020", 0, 0, 0, 0, 0, 0, 0.0, 1000), rows[1])
        assertEquals(mapOf("mktId" to "STK", "trdDd" to "20261016", "bld" to bld("MDCSTAT01501")), transport.calls.single().form)
    }

    @Test
    fun int32ColumnOverflowFailsLikeNumpy() {
        transport.onBld(
            bld("MDCSTAT01501"),
            """{"OutBlock_1":[{"ISU_SRT_CD":"000001","TDD_OPNPRC":"1","TDD_HGPRC":"1","TDD_LWPRC":"1","TDD_CLSPRC":"1",
               "ACC_TRDVOL":"3,000,000,000","ACC_TRDVAL":"1","FLUC_RT":"0","MKTCAP":"1"}]}""",
        )
        assertThrows<ArithmeticException> { api.marketOhlcvByTicker("20261016", "KOSDAQ") }
    }

    @Test
    fun marketCapSortedDescending() {
        transport.onBld(
            bld("MDCSTAT01501"),
            """{"OutBlock_1":[
              {"ISU_SRT_CD":"000001","TDD_CLSPRC":"100","MKTCAP":"1,000","ACC_TRDVOL":"1","ACC_TRDVAL":"2","LIST_SHRS":"10"},
              {"ISU_SRT_CD":"000002","TDD_CLSPRC":"200","MKTCAP":"9,000","ACC_TRDVOL":"","ACC_TRDVAL":"-","LIST_SHRS":"45"}
            ]}""",
        )
        val rows = api.marketCapByTicker("20261016", "ALL")
        assertEquals(listOf("000002", "000001"), rows.map { it.symbol })
        assertEquals(TickerCap("000002", 200, 9000, 0, 0, 45), rows[0])
        assertEquals("ALL", transport.calls.single().form["mktId"])
    }

    @Test
    fun fundamentalDashAndEmptyAreZero() {
        transport.onBld(
            bld("MDCSTAT03501"),
            """{"output":[
              {"ISU_SRT_CD":"005930","BPS":"50,123","PER":"12.5","PBR":"1.42","EPS":"5,777","DVD_YLD":"2.03","DPS":"1,444"},
              {"ISU_SRT_CD":"000660","BPS":"-","PER":"-","PBR":"","EPS":"-","DVD_YLD":"-","DPS":""}
            ]}""",
        )
        val rows = api.fundamentalByTicker("20261016", "KOSPI")
        assertEquals(TickerFundamental("005930", 50123, 12.5, 1.42, 5777, 2.03, 1444), rows[0])
        assertEquals(TickerFundamental("000660", 0, 0.0, 0.0, 0, 0.0, 0), rows[1])
    }

    private fun kospiIndex(vararg dates: String) = """{"output":[${dates.joinToString(",") {
        """{"TRD_DD":"$it","OPNPRC_IDX":"2,500.10","HGPRC_IDX":"2,510.00","LWPRC_IDX":"2,490.00","CLSPRC_IDX":"2,505.55",
            "ACC_TRDVOL":"1,000","ACC_TRDVAL":"2,000","MKTCAP":"3,000"}"""
    }}]}"""

    @Test
    fun indexOhlcvSortedByDate() {
        transport.onBld(bld("MDCSTAT00301"), kospiIndex("2026/10/16", "2026/10/15"))
        val rows = api.indexOhlcvByDate("20261015", "20261016", "1028")
        assertEquals(listOf(LocalDate.of(2026, 10, 15), LocalDate.of(2026, 10, 16)), rows.map { it.date })
        assertEquals(IndexDaily(LocalDate.of(2026, 10, 15), 2500.10, 2510.0, 2490.0, 2505.55, 1000, 2000, 3000), rows[0])
        val form = transport.calls.single().form
        assertEquals("028" to "1", form["indIdx2"] to form["indIdx"])
    }

    @Test
    fun nearestBusinessDayPrevAndNext() {
        transport.on({ it.form["bld"] == bld("MDCSTAT00301") && it.form["endDd"] == "20261018" }) {
            HttpResult(200, kospiIndex("2026/10/16", "2026/10/15"))
        }
        transport.on({ it.form["bld"] == bld("MDCSTAT00301") && it.form["strtDd"] == "20261018" }) {
            HttpResult(200, kospiIndex("2026/10/20", "2026/10/19"))
        }
        assertEquals("20261016", api.nearestBusinessDay("20261018"))
        assertEquals("20261019", api.nearestBusinessDay("20261018", prev = false))
        val prevCall = transport.calls.first()
        assertEquals("20261011" to "001", prevCall.form["strtDd"] to prevCall.form["indIdx2"])
    }

    @Test
    fun priceChangeAddsDelisted() {
        // 영업일 보정: 10/17(토)→next 10/19, 10/31→prev 10/30
        transport.on({ it.form["bld"] == bld("MDCSTAT00301") && it.form["strtDd"] == "20261017" }) { HttpResult(200, kospiIndex("2026/10/19")) }
        transport.on({ it.form["bld"] == bld("MDCSTAT00301") && it.form["endDd"] == "20261031" }) { HttpResult(200, kospiIndex("2026/10/30")) }
        fun row(code: String, name: String, open: String, close: String) =
            """{"ISU_ABBRV":"$name","ISU_SRT_CD":"$code","BAS_PRC":"$open","TDD_CLSPRC":"$close","CMPPREVDD_PRC":"1,000","FLUC_RT":"10.5","ACC_TRDVOL":"5","ACC_TRDVAL":"6"}"""
        transport.on({ it.form["bld"] == bld("MDCSTAT01602") && it.form["endDd"] == "20261030" }) {
            HttpResult(200, """{"OutBlock_1":[${row("000001", "살아있음", "10,000", "11,000")}]}""")
        }
        transport.on({ it.form["bld"] == bld("MDCSTAT01602") && it.form["endDd"] == "20261019" }) {
            HttpResult(200, """{"OutBlock_1":[${row("000001", "살아있음", "10,000", "10,000")},${row("000002", "상폐됨", "5,000", "5,000")}]}""")
        }
        val rows = api.priceChangeByTicker("20261017", "20261031", "KOSPI")
        assertEquals(TickerPriceChange("000001", "살아있음", 10000, 11000, 1000, 10.5, 5, 6), rows[0])
        assertEquals(TickerPriceChange("000002", "상폐됨", 5000, 0, -5000, -100.0, 0, 0), rows[1])
        val main = transport.calls.first { it.form["bld"] == bld("MDCSTAT01602") }
        assertEquals("2", main.form["adjStkPrc"])
        assertEquals("20261019", main.form["strtDd"])
    }

    @Test
    fun netPurchasesUsesColumnOrder() {
        transport.onBld(
            bld("MDCSTAT02401"),
            """{"output":[{"ISU_SRT_CD":"5930","ISU_NM":"삼성전자","A":"1,000","B":"3,000","C":"2,000","D":"10/0","E":"30","F":"20"}]}""",
        )
        val rows = api.netPurchasesByTicker("20261001", "20261016", "KOSPI", "외국인")
        assertEquals(TickerNetPurchase("005930", "삼성전자", 1000, 3000, 2000, 100, 30, 20), rows.single())
        assertEquals("9000", transport.calls.single().form["invstTpCd"])
    }

    @Test
    fun indexTickersFilteredByBaseDateAndCached() {
        transport.on({ it.form["bld"] == bld("MDCSTAT00401") }) { call ->
            val body = when (call.form["idxIndMidclssCd"]) {
                "02" -> """{"output":[
                    {"IDX_IND_CD":"028","IDX_NM":"코스피 200","BAS_TM_CONTN":"1990.01.03","IND_TP_CD":"1"},
                    {"IDX_IND_CD":"001","IDX_NM":"코스피","BAS_TM_CONTN":"1980.01.04","IND_TP_CD":"1"},
                    {"IDX_IND_CD":"999","IDX_NM":"미래지수","BAS_TM_CONTN":"2030.01.01","IND_TP_CD":"1"}]}"""
                else -> """{"output":[]}"""
            }
            HttpResult(200, body)
        }
        assertEquals(listOf("1001", "1028"), api.indexTickers("20261016", "KOSPI"))
        assertEquals("코스피 200", api.indexTickerName("1028"))
        assertEquals(4, transport.calls.size) // 4개 분류 1회 로드 후 캐시
    }
}
