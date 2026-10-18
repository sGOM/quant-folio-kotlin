package com.quantfolio.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QuantfolioPropertiesTest {
    @Test
    fun defaultsArePaperTrading() {
        val p = QuantfolioProperties()
        assertTrue(p.kis.isPaperTrading)
        assertEquals("https://openapivts.koreainvestment.com:29443", p.kis.baseUrl)
    }

    @Test
    fun prodWithoutApprovalIsRejected() {
        // 실전 전환은 명시적 2단계 승인 필요(원본 _ensure_prod_approval).
        assertThrows<IllegalStateException> {
            QuantfolioProperties(kis = QuantfolioProperties.Kis(env = "prod", prodApproved = false)).validate()
        }
    }

    @Test
    fun prodAppRequiresSecureCookie() {
        assertThrows<IllegalStateException> {
            QuantfolioProperties(appEnv = "prod", secretKey = "s", credentialEncKey = "k").validate()
        }
    }
}
