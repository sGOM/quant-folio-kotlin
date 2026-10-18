package com.quantfolio.security

import com.quantfolio.common.QuantfolioProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 증권사 자격증명 암·복호화(`CREDENTIAL_ENC_KEY`). 평문 저장·로깅 금지.
 * 키가 비면 dev 에선 프로세스 한정 임시키를 만든다(재시작하면 기존 암호문을 못 읽는다). prod 는 부팅 검증에서 이미 거부된다.
 */
@Component
class CredentialCipher(props: QuantfolioProperties) {
    private val fernet: Fernet = Fernet(
        props.credentialEncKey.ifBlank {
            log.warn("CREDENTIAL_ENC_KEY 미설정 — dev 임시 키 생성. 운영에서는 반드시 주입하세요.")
            Fernet.generateKey()
        },
    )

    fun encrypt(plaintext: String): String = fernet.encrypt(plaintext)

    fun decrypt(ciphertext: String): String = fernet.decrypt(ciphertext)

    private companion object {
        val log = LoggerFactory.getLogger(CredentialCipher::class.java)
    }
}
