package com.quantfolio.common

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.boot.SpringApplication
import org.springframework.boot.WebApplicationType
import org.springframework.context.annotation.Configuration
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertEquals

/**
 * 시크릿 파일은 `openssl ... > f`·`print()` 로 만들어 끝에 개행이 붙는다. configtree 가 그 개행을 떼지 않으면
 * 증권사 API 키에 공백이 섞여 인증이 실패한다(컷오버 사고) — 실제 import 경로로 확인.
 */
class SecretsConfigTreeTest {
    @Configuration
    class Empty

    @Test
    fun configTreeTrimsTrailingNewline(@TempDir dir: Path) {
        Files.writeString(dir.resolve("kis_app_key"), " abc=\n\n") // 원본은 strip() — 빈 줄·공백도 무시했다
        val app = SpringApplication(Empty::class.java).apply { setWebApplicationType(WebApplicationType.NONE) }
        app.run("--spring.config.import=configtree:${dir.toString().replace(java.io.File.separatorChar, '/')}/", "--spring.main.banner-mode=off").use {
            assertEquals("abc=", it.environment.getProperty("kis_app_key"))
        }
    }
}
