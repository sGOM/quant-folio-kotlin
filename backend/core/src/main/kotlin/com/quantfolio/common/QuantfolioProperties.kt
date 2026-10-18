package com.quantfolio.common

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration
import java.math.BigDecimal

/**
 * 앱 설정. 값의 출처는 `quantfolio-core.yml` — 원본과 같은 환경변수 이름(.env 호환)과
 * `/run/secrets/<name>` 시크릿 파일(configtree)을 매핑한다. 시크릿 파일이 환경변수보다 우선.
 */
@ConfigurationProperties("quantfolio")
data class QuantfolioProperties(
    /** "dev" | "prod" */
    val appEnv: String = "dev",
    val secretKey: String = "",
    /** 증권사 자격증명 Fernet 키(base64 32바이트) */
    val credentialEncKey: String = "",
    val sessionTtlMinutes: Long = 60L * 24 * 14,
    val cookie: Cookie = Cookie(),
    val kis: Kis = Kis(),
    val toss: Toss = Toss(),
    val krx: Krx = Krx(),
    val opendart: OpenDart = OpenDart(),
    val telegram: Telegram = Telegram(),
    val s3Backup: S3Backup = S3Backup(),
    val frontendOrigin: String = "http://localhost:3000",
    val debugErrors: Boolean = false,
) {
    init {
        validate()
    }

    /** 부팅 시 안전측 강제. 실패하면 프로세스가 뜨지 않는다. */
    fun validate() {
        check(!(kis.env == "prod" && !kis.prodApproved)) {
            "KIS_ENV=prod(실전)인데 KIS_PROD_APPROVED 가 승인(true)되지 않았습니다. 실전 전환은 명시적 2단계 승인이 필요합니다."
        }
        if (appEnv == "prod") {
            check(secretKey.isNotBlank() && credentialEncKey.isNotBlank()) { "운영(prod) 환경에서 SECRET_KEY·CREDENTIAL_ENC_KEY 가 필요합니다." }
            check(cookie.secure) { "운영 환경에서는 COOKIE_SECURE=true 여야 합니다." }
        }
    }

    /** prod 에서는 상세 에러 노출 금지. */
    val exposeErrorDetails: Boolean get() = debugErrors && appEnv != "prod"

    data class Cookie(
        val secure: Boolean = false,
        /** "lax" | "strict" | "none" */
        val samesite: String = "lax",
        val domain: String? = null,
    )

    data class Kis(
        /** "vts"(모의) | "prod"(실전) */
        val env: String = "vts",
        val baseUrlVts: String = "https://openapivts.koreainvestment.com:29443",
        val baseUrlProd: String = "https://openapi.koreainvestment.com:9443",
        val wsUrlVts: String = "wss://ops.koreainvestment.com:31000",
        val wsUrlProd: String = "wss://ops.koreainvestment.com:21000",
        val appKey: String = "",
        val appSecret: String = "",
        /** 'CANO-PRDT' 형식 (예: 50012345-01) */
        val accountNo: String = "",
        val htsId: String? = null,
        val prodApproved: Boolean = false,
        val dailyOrderNotionalCap: BigDecimal? = null,
    ) {
        val isPaperTrading: Boolean get() = env != "prod"
        val baseUrl: String get() = if (isPaperTrading) baseUrlVts else baseUrlProd
        val wsUrl: String get() = if (isPaperTrading) wsUrlVts else wsUrlProd
    }

    data class Toss(
        val baseUrl: String = "https://openapi.tossinvest.com",
        val appKey: String = "",
        val appSecret: String = "",
        val accountNo: String = "",
    )

    data class Krx(val id: String = "", val pw: String = "")

    data class OpenDart(val apiKey: String = "", val baseUrl: String = "https://opendart.fss.or.kr/api") {
        val enabled: Boolean get() = apiKey.isNotBlank()
    }

    data class Telegram(val botToken: String = "", val chatId: String = "") {
        val enabled: Boolean get() = botToken.isNotBlank() && chatId.isNotBlank()
    }

    data class S3Backup(
        val bucket: String = "",
        val endpointUrl: String = "",
        val region: String = "auto",
        val prefix: String = "quantfolio-db-backups/",
        val accessKeyId: String = "",
        val secretAccessKey: String = "",
    ) {
        val enabled: Boolean get() = bucket.isNotBlank() && accessKeyId.isNotBlank() && secretAccessKey.isNotBlank()
    }
}

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(QuantfolioProperties::class)
class QuantfolioPropertiesConfig
