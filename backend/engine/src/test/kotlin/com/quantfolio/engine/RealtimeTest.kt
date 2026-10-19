package com.quantfolio.engine

import com.quantfolio.test.Oracle
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** 시장 CB 상태기계(원본 `engine/halt.py`)와 KIS 실시간 프레임 파싱(`kis_ws.py`·`fill_notice.py`). */
class RealtimeTest {
    @Test
    fun haltMonitorNeedsSampleAndCoolsDownBeforeResuming() {
        val m = MarketHaltMonitor()
        listOf("a", "b", "c").forEach { m.observe(it, true, 0.0) }
        assertEquals(MarketState.NORMAL, m.evaluate(0.0)) // 표본 3 < 5 — 판정 보류
        listOf("d", "e").forEach { m.observe(it, false, 0.0) } // 3/5 = 60%
        assertEquals(MarketState.HALTED, m.evaluate(1.0))
        assertFalse(m.canTrade(1.0))
        listOf("a", "b", "c").forEach { m.observe(it, false, 10.0) }
        assertEquals(MarketState.COOLDOWN, m.evaluate(10.0))
        assertEquals(MarketState.COOLDOWN, m.evaluate(189.0))
        m.observe("a", true, 100.0) // 표본은 여전히 5 지만 비율 20% — 재발 아님
        assertEquals(MarketState.NORMAL, m.evaluate(190.0)) // 180초 유예 후 재개
        assertTrue(m.canTrade(190.0))
        // 오래된 관측(120초 초과)은 무시 → 표본 부족으로 판정 보류
        val n = MarketHaltMonitor()
        listOf("a", "b", "c", "d", "e").forEach { n.observe(it, true, 0.0) }
        assertEquals(MarketState.NORMAL, n.evaluate(121.0))
        assertEquals(0.0 to 0, n.haltedRatio(121.0))
    }

    @Test
    fun priceFramesAndSubscribeMessages() {
        assertEquals("005930" to BigDecimal("70100"), KisFrames.parsePrice("0|H0STCNT0|001|005930^093000^70100^5^100"))
        assertNull(KisFrames.parsePrice("0|H0STCNT0|001|005930^093000^0"))
        assertNull(KisFrames.parsePrice("0|H0STASP0|001|005930^093000^70100"))
        assertNull(KisFrames.parsePrice("0|H0STCNT0|001|005930^x"))
        assertNull(KisFrames.parsePrice("0|H0STCNT0|001|005930^093000^abc"))
        assertEquals(
            """{"header": {"approval_key": "AK", "custtype": "P", "tr_type": "2", "content-type": "utf-8"}, "body": {"input": {"tr_id": "H0STCNT0", "tr_key": "005930"}}}""",
            KisFrames.subscribe("AK", "H0STCNT0", "005930", subscribe = false),
        )
        assertTrue(KisFrames.isPingPong("""{"header":{"tr_id":"PINGPONG","datetime":"20261019"}}"""))
        assertFalse(KisFrames.isPingPong("""{"header":{"tr_id":"H0STCNT0"}}"""))
    }

    @Test
    fun fillNoticeDecryptAndParseMatchPython() {
        val o = Oracle.load("kis_ws")
        val key = o.get("key").asString()
        val iv = o.get("iv").asString()
        for (c in o.get("cases")) {
            val plain = KisFrames.aesDecrypt(key, iv, c.get("cipher").asString())
            assertEquals(c.get("decrypted").asString(), plain)
            val n = KisFrames.parseFillNotice(plain)
            val e = c.get("notice")
            if (e.isNull) {
                assertNull(n, plain)
            } else {
                assertEquals(FillNotice(e.get("odno").asString(), e.get("symbol").asString(), e.get("qty").asInt(), BigDecimal(e.get("price").asString()), e.get("side").asString()), n)
            }
        }
    }
}
