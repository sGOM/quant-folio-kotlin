package com.quantfolio.broker

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.security.CredentialCipher
import org.springframework.stereotype.Component

/** 복호화된 유효 자격증명. kis=app_key/app_secret/계좌(CANO-PRDT), toss=client_id/client_secret/accountSeq. */
data class ResolvedCredentials(val broker: String, val appKey: String, val appSecret: String, val accountNo: String?) {
    override fun toString() = "ResolvedCredentials(broker=$broker, accountNo=$accountNo)" // 키·시크릿은 로그에 남기지 않는다
}

/**
 * 사용자 자격증명 해석(원본 `broker/factory.resolve_credentials`). DB 등록값(암호화) 우선, 없으면 `.env` 기본값 폴백(단일 운영자 편의).
 */
@Component
class BrokerCredentials(private val cipher: CredentialCipher, private val props: QuantfolioProperties) {
    fun resolve(user: User): ResolvedCredentials? {
        val broker = user.broker.ifEmpty { "kis" }
        val key = user.kisAppKey
        val secret = user.kisAppSecret
        if (!key.isNullOrEmpty() && !secret.isNullOrEmpty()) {
            return ResolvedCredentials(broker, cipher.decrypt(key), cipher.decrypt(secret), user.kisAccountNo)
        }
        return when {
            broker == "kis" && props.kis.appKey.isNotEmpty() && props.kis.appSecret.isNotEmpty() ->
                ResolvedCredentials(broker, props.kis.appKey, props.kis.appSecret, props.kis.accountNo.ifEmpty { null })
            broker == "toss" && props.toss.appKey.isNotEmpty() && props.toss.appSecret.isNotEmpty() ->
                ResolvedCredentials(broker, props.toss.appKey, props.toss.appSecret, props.toss.accountNo.ifEmpty { null })
            else -> null
        }
    }

    fun has(user: User): Boolean = resolve(user) != null
}
