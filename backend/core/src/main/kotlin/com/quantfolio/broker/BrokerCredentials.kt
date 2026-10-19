package com.quantfolio.broker

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.security.CredentialCipher
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** 복호화된 유효 자격증명. kis=app_key/app_secret/계좌(CANO-PRDT), toss=client_id/client_secret/accountSeq. */
data class ResolvedCredentials(val broker: String, val appKey: String, val appSecret: String, val accountNo: String?) {
    override fun toString() = "ResolvedCredentials(broker=$broker, accountNo=$accountNo)" // 키·시크릿은 로그에 남기지 않는다
}

/**
 * 사용자 자격증명 해석과 증권사 클라이언트 생성(원본 `broker/factory.py`). DB 등록값(암호화) 우선, 없으면 `.env` 폴백(단일 운영자 편의).
 */
@Component
class BrokerCredentials(
    private val cipher: CredentialCipher,
    private val props: QuantfolioProperties,
    redis: StringRedisTemplate,
    http: BrokerHttp,
    clock: Clock,
) {
    private val support = BrokerSupport(http, redis, props, clock, JsonMapper.builder().build())

    fun resolve(user: User): ResolvedCredentials? {
        val broker = user.broker.ifEmpty { "kis" }
        val key = user.kisAppKey
        val secret = user.kisAppSecret
        if (!key.isNullOrEmpty() && !secret.isNullOrEmpty()) {
            return ResolvedCredentials(broker, cipher.decrypt(key), cipher.decrypt(secret), user.kisAccountNo)
        }
        return env(broker)?.let { (k, s, a) -> ResolvedCredentials(broker, k, s, a) }
    }

    private fun env(broker: String): Triple<String, String, String?>? = when {
        broker == "kis" && props.kis.appKey.isNotEmpty() && props.kis.appSecret.isNotEmpty() -> Triple(props.kis.appKey, props.kis.appSecret, props.kis.accountNo.ifEmpty { null })
        broker == "toss" && props.toss.appKey.isNotEmpty() && props.toss.appSecret.isNotEmpty() -> Triple(props.toss.appKey, props.toss.appSecret, props.toss.accountNo.ifEmpty { null })
        else -> null
    }

    fun has(user: User): Boolean = resolve(user) != null

    fun make(broker: String, appKey: String, appSecret: String, accountNo: String?): BrokerClient = when (broker) {
        "kis" -> KisClient(appKey, appSecret, accountNo, support)
        "toss" -> TossClient(appKey, appSecret, accountNo, support)
        else -> throw BrokerError("지원하지 않는 브로커: $broker")
    }

    /** 사용자 주문 브로커. 자격증명이 전혀 없으면 BrokerError. */
    fun forUser(user: User): BrokerClient {
        val c = resolve(user) ?: throw BrokerError("증권사 API 자격증명이 등록되지 않았습니다.")
        return make(c.broker, c.appKey, c.appSecret, c.accountNo)
    }

    /** 통합 시세(국내+해외)용 토스 클라이언트 — 사용자 toss_* → .env TOSS_*. 없으면 null(호출자는 주문 브로커로 폴백). */
    fun quoteClient(user: User): BrokerClient? {
        val key = user.tossAppKey
        val secret = user.tossAppSecret
        if (!key.isNullOrEmpty() && !secret.isNullOrEmpty()) return TossClient(cipher.decrypt(key), cipher.decrypt(secret), user.tossAccountNo, support)
        return env("toss")?.let { (k, s, a) -> TossClient(k, s, a, support) }
    }
}
