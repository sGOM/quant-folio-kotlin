package com.quantfolio.web.api

import com.quantfolio.test.ContainersConfig
import jakarta.validation.Valid
import jakarta.validation.constraints.Min
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class Sample(val fooBar: Int, val maybe: String?)
data class Numbers(val nan: Double, val inf: Double, val ok: Double)
data class When(val at: Instant)
data class Body(@field:Min(0) val n: Int)

@RestController
class ContractProbeController {
    @GetMapping("/probe/conflict") fun conflict(): Nothing = throw ApiException(HttpStatus.CONFLICT, "x")
    @GetMapping("/probe/boom") fun boom(): Nothing = throw IllegalStateException("secret internals")
    @GetMapping("/probe/sample") fun sample() = Sample(1, null)
    @GetMapping("/probe/numbers") fun numbers() = Numbers(Double.NaN, Double.POSITIVE_INFINITY, 1.5)
    @GetMapping("/probe/when") fun at() = When(Instant.parse("2026-10-19T01:02:03.456Z"))
    @PostMapping("/probe/body") fun body(@Valid @RequestBody b: Body) = b
}

@TestConfiguration
@Import(ContractProbeController::class)
class ProbeConfig

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class, ProbeConfig::class)
class ApiContractTest(@Autowired private val mvc: MockMvc) {
    @Test
    fun apiExceptionHasDetail() {
        mvc.get("/probe/conflict").andExpect { status { isConflict() }; content { json("""{"detail":"x"}""", true) } }
    }

    @Test
    fun validationIs422WithLocMsg() {
        mvc.post("/probe/body") { contentType = MediaType.APPLICATION_JSON; content = """{"n":-1}""" }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].loc[0]") { value("body") }
            jsonPath("$.detail[0].loc[1]") { value("n") }
            jsonPath("$.detail[0].msg") { exists() }
            jsonPath("$.detail[0].type") { exists() }
        }
    }

    @Test
    fun malformedJsonIs422() {
        mvc.post("/probe/body") { contentType = MediaType.APPLICATION_JSON; content = "{nope" }.andExpect {
            status { isUnprocessableContent() }
            jsonPath("$.detail[0].msg") { exists() }
        }
    }

    @Test
    fun unhandledIs500Generic() {
        mvc.get("/probe/boom").andExpect {
            status { isInternalServerError() }
            content { json("""{"detail":"Internal Server Error"}""", true) }
        }
    }

    @Test
    fun snakeCaseAndNulls() {
        mvc.get("/probe/sample").andExpect { content { json("""{"foo_bar":1,"maybe":null}""", true) } }
    }

    @Test
    fun nanBecomesNull() {
        mvc.get("/probe/numbers").andExpect { content { json("""{"nan":null,"inf":null,"ok":1.5}""", true) } }
    }

    @Test
    fun instantIsIso() {
        mvc.get("/probe/when").andExpect { content { json("""{"at":"2026-10-19T01:02:03.456Z"}""", true) } }
    }

    @Test
    fun unknownPathIs404Detail() {
        mvc.get("/nope").andExpect { status { isNotFound() }; content { json("""{"detail":"Not Found"}""", true) } }
    }

    @Test
    fun healthShape() {
        mvc.get("/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("ok") }
            jsonPath("$.redis") { value(true) }
            jsonPath("$.kis_env") { value("vts") }
            jsonPath("$.paper_trading") { value(true) }
        }
    }
}
