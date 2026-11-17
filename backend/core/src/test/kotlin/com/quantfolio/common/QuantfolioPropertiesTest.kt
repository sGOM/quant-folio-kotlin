package com.quantfolio.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.context.properties.bind.BindException
import org.springframework.boot.context.properties.bind.Binder
import org.springframework.boot.env.YamlPropertySourceLoader
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QuantfolioPropertiesTest {
    @Test
    fun defaultsArePaperTrading() {
        val p = QuantfolioProperties()
        assertTrue(p.kis.isPaperTrading)
        assertEquals("https://openapivts.koreainvestment.com:29443", p.kis.baseUrl)
    }

    @Test
    fun configuredTradingOwnerMustBePositive() {
        for (id in listOf(0, -1)) {
            assertThrows<IllegalStateException> { QuantfolioProperties(tradingOwnerUserId = id) }
        }
    }

    /** 실제 `quantfolio-core.yml` 매핑으로 바인딩 — `.env` 의 `TRADING_OWNER_USER_ID=`(빈 값)·미설정이 부팅 실패가 아니라 null(차단)이어야 한다. */
    @Test
    fun blankOrMissingTradingOwnerBindsToNull() {
        fun bind(owner: String?): Int? {
            val env = MockEnvironment()
            YamlPropertySourceLoader().load("core", ClassPathResource("quantfolio-core.yml")).forEach { env.propertySources.addLast(it) }
            if (owner != null) env.setProperty("TRADING_OWNER_USER_ID", owner)
            return Binder.get(env).bind("quantfolio", QuantfolioProperties::class.java).get().tradingOwnerUserId
        }
        assertNull(bind(null))
        assertNull(bind(""))
        assertEquals(7, bind("7"))
        assertEquals(7, bind(" 7 "))
        assertThrows<BindException> { bind("0") }
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
            QuantfolioProperties(appEnv = "prod").validate()
        }
    }
}
