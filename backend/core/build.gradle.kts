plugins {
    `java-library`
    `java-test-fixtures`
    kotlin("plugin.jpa")
}

dependencies {
    api("org.springframework.boot:spring-boot-starter-data-jpa")
    api("org.springframework.boot:spring-boot-starter-data-redis")
    api("org.springframework.boot:spring-boot-starter-flyway")
    api("org.springframework.boot:spring-boot-starter-jdbc")
    api("org.springframework.boot:spring-boot-starter-validation")
    api("org.springframework.security:spring-security-crypto")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
