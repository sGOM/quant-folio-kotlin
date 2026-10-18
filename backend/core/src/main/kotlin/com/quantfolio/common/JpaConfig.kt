package com.quantfolio.common

import org.springframework.boot.persistence.autoconfigure.EntityScan
import org.springframework.context.annotation.Configuration
import org.springframework.data.jpa.repository.config.EnableJpaRepositories

/** 세 앱의 진입점 패키지(`com.quantfolio.{web,engine,worker}`)와 무관하게 core 도메인을 스캔한다. */
@Configuration(proxyBeanMethods = false)
@EntityScan("com.quantfolio.domain")
@EnableJpaRepositories("com.quantfolio.domain")
class JpaConfig
