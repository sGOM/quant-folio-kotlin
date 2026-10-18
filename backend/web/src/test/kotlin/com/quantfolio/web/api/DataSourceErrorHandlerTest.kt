package com.quantfolio.web.api

import com.quantfolio.market.source.SourceRequestError
import com.quantfolio.market.source.SourceSchemaError
import com.quantfolio.market.source.SourceUnavailableError
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

@RestController
class SourceProbeController {
    @GetMapping("/probe/src/unavailable") fun unavailable(): Nothing = throw SourceUnavailableError("krx", "점검 중")
    @GetMapping("/probe/src/request") fun request(): Nothing = throw SourceRequestError("dart", "잘못된 인자")
    @GetMapping("/probe/src/schema") fun schema(): Nothing = throw SourceSchemaError("kofia", "형식 변경")
}

@TestConfiguration
@Import(SourceProbeController::class)
class SourceProbeConfig

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class, SourceProbeConfig::class)
class DataSourceErrorHandlerTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun unavailableIs503WithCause() {
        mvc.get("/probe/src/unavailable").andExpect {
            status { isServiceUnavailable() }
            content {
                json(
                    """{"detail":"외부 데이터 소스를 사용할 수 없습니다.","source":"krx","cause":"SourceUnavailableError","retryable":true}""",
                    true,
                )
            }
        }
    }

    @Test
    fun ourBadRequestIs500NotServiceUnavailable() {
        mvc.get("/probe/src/request").andExpect {
            status { isInternalServerError() }
            content {
                json(
                    """{"detail":"외부 데이터 소스 요청이 잘못됐습니다.","source":"dart","cause":"SourceRequestError","retryable":false}""",
                    true,
                )
            }
        }
    }

    @Test
    fun unparseableResponseIs502() {
        mvc.get("/probe/src/schema").andExpect {
            status { isBadGateway() }
            jsonPath("$.detail") { value("외부 데이터 소스 응답을 해석할 수 없습니다.") }
            jsonPath("$.cause") { value("SourceSchemaError") }
        }
    }
}
