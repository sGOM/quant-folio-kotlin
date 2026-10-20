package com.quantfolio.engine

import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import kotlin.test.assertEquals

@SpringBootTest
@Import(ContainersConfig::class)
class EngineApplicationTest(@Autowired private val env: Environment) {
    @Test
    fun loadsWithCoreConfig() {
        // 공용 설정(quantfolio-core.yml)을 import 해야 운영 DB 에서 baseline·스키마 검증이 동작한다.
        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("true", env.getProperty("spring.flyway.baseline-on-migrate"))
        assertEquals("true", env.getProperty("spring.main.keep-alive")) // 없으면 가상 스레드뿐이라 기동 직후 종료
        // 운영에선 web 만 마이그레이션 — 테스트 설정이 다시 켜므로 main 설정 파일을 직접 본다
        val main = org.springframework.boot.env.YamlPropertySourceLoader().load("main", org.springframework.core.io.ClassPathResource("application.yml")).first()
        assertEquals("false", main.getProperty("spring.flyway.enabled").toString())
        assertEquals("engine", env.getProperty("spring.application.name"))
    }
}
