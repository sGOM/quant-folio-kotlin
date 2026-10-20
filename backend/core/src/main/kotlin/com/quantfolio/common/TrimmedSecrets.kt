package com.quantfolio.common

import org.springframework.boot.EnvironmentPostProcessor
import org.springframework.boot.SpringApplication
import org.springframework.boot.env.ConfigTreePropertySource
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * 시크릿 파일(configtree) 값을 앞뒤 공백 제거한 값으로 바꾼다 — 원본 `fh.read().strip()` 과 같게.
 * Spring 기본은 끝 개행 하나만 떼서, 빈 줄·공백이 붙은 기존 키 파일을 그대로 옮기면 Fernet 키가 깨져
 * DB 의 증권사 자격증명을 전부 못 푼다. configtree import 가 끝난 뒤에 돌아야 하므로 최저 우선순위.
 */
class TrimmedSecrets : EnvironmentPostProcessor, Ordered {
    override fun getOrder() = Ordered.LOWEST_PRECEDENCE

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val sources = environment.propertySources
        for (src in sources.filterIsInstance<ConfigTreePropertySource>()) {
            val trimmed = src.propertyNames.associateWith { src.getProperty(it).toString().trim() }
            sources.replace(src.name, MapPropertySource(src.name, trimmed))
        }
    }
}
