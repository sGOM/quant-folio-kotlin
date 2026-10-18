package com.quantfolio.common

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

/** 시각 의존 로직은 [Clock] 을 주입받는다(테스트에서 고정 시각으로 교체). 거래일은 [MarketClock] 으로 변환. */
@Configuration(proxyBeanMethods = false)
class ClockConfig {
    @Bean
    @ConditionalOnMissingBean
    fun clock(): Clock = Clock.systemUTC()
}
