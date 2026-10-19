package com.quantfolio.worker

import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.core.env.Environment
import kotlin.test.assertEquals

@SpringBootTest
@Import(ContainersConfig::class)
class WorkerApplicationTest(@Autowired private val env: Environment) {
    @Test
    fun loadsWithCoreConfig() {
        // 공용 설정(quantfolio-core.yml)을 import 해야 운영 DB 에서 baseline·스키마 검증이 동작한다.
        assertEquals("validate", env.getProperty("spring.jpa.hibernate.ddl-auto"))
        assertEquals("true", env.getProperty("spring.flyway.baseline-on-migrate"))
        assertEquals("true", env.getProperty("spring.main.keep-alive")) // 없으면 가상 스레드뿐이라 기동 직후 종료
        assertEquals("worker", env.getProperty("spring.application.name"))
    }
}
