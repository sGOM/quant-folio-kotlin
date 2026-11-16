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
    api("org.apache.commons:commons-math3:3.6.1")
    api("tools.jackson.module:jackson-module-kotlin")
    api("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    testFixturesApi("org.springframework.boot:spring-boot-testcontainers")
    testFixturesApi("org.testcontainers:testcontainers-junit-jupiter")
    testFixturesApi("org.testcontainers:testcontainers-postgresql")
    testFixturesApi("org.springframework.boot:spring-boot-starter-test")
    testFixturesApi("org.jetbrains.kotlin:kotlin-test-junit5")
}

// CodeRulesTest 는 전 모듈 main 소스를 grep 한다 — 다른 모듈만 바뀌어도 다시 돌게 입력으로 잡는다.
tasks.test {
    inputs.files(fileTree(rootDir) { include("*/src/main/**/*.kt") }).withPropertyName("allMainSources")
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}
