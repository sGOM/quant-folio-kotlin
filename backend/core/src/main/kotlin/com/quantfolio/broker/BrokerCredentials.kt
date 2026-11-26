package com.quantfolio.broker

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import java.time.Clock

/** 서버 자격증명. kis=app_key/app_secret/계좌(CANO-PRDT), toss=client_id/client_secret/accountSeq. */
data class ResolvedCredentials(val broker: String, val appKey: String, val appSecret: String, val accountNo: String?) {
    override fun toString() = "ResolvedCredentials(broker=$broker, accountNo=$accountNo)" // 키·시크릿은 로그에 남기지 않는다
}

/** 소유자의 증권사 클라이언트는 서버 설정으로만 생성한다. */
@Component
class BrokerCredentials(
    private val props: QuantfolioProperties,
    redis: StringRedisTemplate,
    http: BrokerHttp,
    clock: Clock,
) {
    private val support = BrokerSupport(http, redis, props, clock, JsonMapper.builder().build())

    fun isOwner(userId: Int?): Boolean = userId != null && userId == props.tradingOwnerUserId

    fun resolve(user: User): ResolvedCredentials? = resolve(user.id, user.broker.ifEmpty { "kis" })

    private fun resolve(userId: Int?, broker: String): ResolvedCredentials? {
        if (!isOwner(userId)) return null
        val (key, secret, account) = when (broker) {
            "kis" -> with(props.kis) { Triple(appKey, appSecret, accountNo) }
            "toss" -> with(props.toss) { Triple(appKey, appSecret, accountNo) }
            else -> return null
        }
        if (key.isBlank() || secret.isBlank()) return null
        return ResolvedCredentials(broker, key, secret, account.ifBlank { null })
    }

    fun has(user: User): Boolean = resolve(user) != null

    fun hasTossQuote(user: User): Boolean = resolve(user.id, "toss") != null

    /** 소유자 주문 브로커. */
    fun forUser(user: User): BrokerClient {
        if (!isOwner(user.id)) throw BrokerError("증권사 연결은 지정된 거래 소유자만 사용할 수 있습니다.")
        val c = resolve(user) ?: throw BrokerError("증권사 API 자격증명이 서버에 설정되지 않았습니다.")
        return if (c.broker == "kis") KisClient(c.appKey, c.appSecret, c.accountNo, support)
        else TossClient(c.appKey, c.appSecret, c.accountNo, support)
    }

    /** 소유자 KIS 자격증명이 설정돼 있으면 그 클라이언트(휴장일 조회 등 계좌 무관 호출용), 없으면 null. */
    fun ownerKis(): KisClient? = with(props.kis) {
        if (appKey.isBlank() || appSecret.isBlank()) null else KisClient(appKey, appSecret, accountNo.ifBlank { null }, support)
    }

    /** 통합 시세(국내+해외)용 토스 클라이언트. */
    fun quoteClient(user: User): BrokerClient? =
        resolve(user.id, "toss")?.let { TossClient(it.appKey, it.appSecret, it.accountNo, support) }
}
