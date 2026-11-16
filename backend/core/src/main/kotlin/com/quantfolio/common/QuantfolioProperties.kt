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
    /** 파일 캐시 디렉터리(패닉 브레드스 등, 원본 backend/.cache). */
    val cacheDir: String = ".cache",
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
        /**
         * 실시간 시세·체결통보 WebSocket. 원본 기본값 `wss://…:31000`·`wss://…:21000` 은 2026-11-16 실측에서 두 포트 모두 TLS 핸드셰이크가
         * 실패했다(서버가 TLS 로 응답하지 않음). 모의 `ws://…:31000/tryitout` 은 JDK 연결로 구독 성공·통보 수신을 확인했다.
         * 실전 `ws://…:21000/tryitout` 은 접속을 측정하지 못했다 — 공식 설정 파일(`ws://…:21000`)·공식 예제(`/tryitout`)를 따른 값이다.
         * 이 두 값은 환경변수로 바꿀 수 없다(`quantfolio-core.yml` 에 매핑 없음 — 원본 `KIS_WS_URL_*` 는 읽지 않는다).
         */
        val wsUrlVts: String = "ws://ops.koreainvestment.com:31000/tryitout",
        val wsUrlProd: String = "ws://ops.koreainvestment.com:21000/tryitout",
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
