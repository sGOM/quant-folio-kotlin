package com.quantfolio.test

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

/** 테스트 전체가 공유하는 싱글턴 컨테이너. 운영과 같은 이미지를 쓴다. */
object Containers {
    val postgres: PostgreSQLContainer by lazy {
        PostgreSQLContainer(
            DockerImageName.parse("timescale/timescaledb:latest-pg16").asCompatibleSubstituteFor("postgres"),
        ).apply { start() }
    }

    val redis: GenericContainer<*> by lazy {
        GenericContainer(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379).apply { start() }
    }
}

@TestConfiguration(proxyBeanMethods = false)
class ContainersConfig {
    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer = Containers.postgres

    @Bean
    @ServiceConnection(name = "redis")
    fun redis(): GenericContainer<*> = Containers.redis
}
