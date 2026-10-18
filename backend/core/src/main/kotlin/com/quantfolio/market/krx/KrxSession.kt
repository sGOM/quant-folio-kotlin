package com.quantfolio.market.krx

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.market.source.Cooldowns
import com.quantfolio.market.source.DataSourceError
import com.quantfolio.market.source.SourceAuthError
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * KRX 데이터 포털 로그인 세션(원본 pykrx `KRXSession` + `krx_index._session`).
 *
 * **미설정과 실패를 가른다** — 자격증명이 없으면 null(물어보지 않은 것), 로그인 실패·쿨다운 중이면 [SourceAuthError].
 * 쿨다운 검사를 "유효 세션 재사용"보다 **먼저** 한다. 뒤에 두면 차단 중에도 살아 있는 쿠키로 요청이 계속 나간다(§44-1).
 */
@Component
class KrxSession(
    private val transport: KrxTransport,
    private val props: QuantfolioProperties,
    private val cooldowns: Cooldowns,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val json = JsonMapper.builder().build()

    @Volatile
    private var expiresAt: Instant? = null

    /** null = 자격증명 미설정, true = 유효한 로그인 세션. 실패하면 [SourceAuthError]. */
    @Synchronized
    fun current(): Boolean? {
        if (props.krx.id.isBlank() || props.krx.pw.isBlank()) return null

        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceAuthError(SOURCE, "로그인 쿨다운 중 — 재시도 생략(${remaining.toLong()}초 남음)")

        val expiry = expiresAt
        if (expiry != null && clock.instant().isBefore(expiry.minus(VALIDITY_BUFFER))) return true

        val ok = try {
            login()
        } catch (e: Exception) {
            throw fail(SourceAuthError(SOURCE, "인증 세션 생성 실패: ${e.message}"))
        }
        if (!ok) throw fail(SourceAuthError(SOURCE, "인증 세션 생성 실패(빈 세션)"))
        expiresAt = clock.instant().plus(VALIDITY)
        cooldowns.clear(SOURCE)
        return true
    }

    /** preflight 용 — 예외를 던지지 않는다. */
    fun hasAuth(): Boolean = try {
        current() != null
    } catch (_: DataSourceError) {
        false
    }

    /** PIT 유니버스 백테스트처럼 KRX 인증이 필수인 곳의 preflight. 원인별 메시지로 거부한다. */
    fun requireAuth() {
        if (hasAuth()) return
        if (props.krx.id.isBlank() || props.krx.pw.isBlank()) {
            throw SourceAuthError(SOURCE, "KRX_ID/KRX_PW 가 설정되지 않았다 — PIT 유니버스 백테스트에는 필수다")
        }
        val remaining = cooldowns.remaining(SOURCE)
        if (remaining > 0) throw SourceAuthError(SOURCE, "KRX 로그인 실패 후 쿨다운 중(${remaining.toLong()}초 남음)")
        throw SourceAuthError(SOURCE, "KRX 로그인에 실패했다 — 자격증명 유효성·차단 여부를 확인하라")
    }

    /** warmup GET 2회 → 로그인 POST. 중복 로그인(CD011)이면 skipDup=Y 로 재전송. CD001 이 성공. */
    private fun login(): Boolean {
        expiresAt = null
        transport.clearCookies()
        transport.get(LOGIN_PAGE, mapOf("User-Agent" to USER_AGENT))
        transport.get(LOGIN_JSP, mapOf("User-Agent" to USER_AGENT, "Referer" to LOGIN_PAGE))
        val form = linkedMapOf(
            "mbrNm" to "", "telNo" to "", "di" to "", "certType" to "",
            "mbrId" to props.krx.id, "pw" to props.krx.pw,
        )
        val headers = mapOf("User-Agent" to USER_AGENT, "Referer" to LOGIN_PAGE)
        var code = errorCode(transport.postForm(LOGIN_URL, form, headers, LOGIN_TIMEOUT))
        if (code == "CD010") log.warn("KRX 비밀번호 변경이 필요합니다(https://www.krx.co.kr).")
        if (code == "CD011") {
            form["skipDup"] = "Y"
            code = errorCode(transport.postForm(LOGIN_URL, form, headers, LOGIN_TIMEOUT))
        }
        return code == "CD001"
    }

    private fun errorCode(result: HttpResult): String =
        json.readTree(result.body).path("_error_code").asString("")

    private fun fail(e: SourceAuthError): SourceAuthError {
        cooldowns.noteFailure(e)
        log.warn("{}", e.message)
        return e
    }

    companion object {
        const val SOURCE = "krx"
        const val LOGIN_PAGE = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001.cmd"
        const val LOGIN_JSP = "https://data.krx.co.kr/contents/MDC/COMS/client/view/login.jsp?site=mdc"
        const val LOGIN_URL = "https://data.krx.co.kr/contents/MDC/COMS/client/MDCCOMS001D1.cmd"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        private val VALIDITY: Duration = Duration.ofHours(1)
        private val VALIDITY_BUFFER: Duration = Duration.ofSeconds(300)
        private val LOGIN_TIMEOUT: Duration = Duration.ofSeconds(15)
    }
}
