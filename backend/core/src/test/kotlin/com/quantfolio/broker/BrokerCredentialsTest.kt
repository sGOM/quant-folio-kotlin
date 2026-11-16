package com.quantfolio.broker

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Clock
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BrokerCredentialsTest {
    private val props = QuantfolioProperties(
        kis = QuantfolioProperties.Kis(appKey = "server-kis", appSecret = "server-kis-secret", accountNo = "50012345-01"),
        toss = QuantfolioProperties.Toss(appKey = "server-toss", appSecret = "server-toss-secret", accountNo = "12"),
    )
    private val http = object : BrokerHttp {
        override fun send(
            method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?,
        ): BrokerResponse = error("credential resolution must not call the broker")
    }

    @Test
    fun noOwnerDisablesOrderAndQuoteCredentialsEvenWhenServerKeysAreSet() {
        val credentials = BrokerCredentials(props, StringRedisTemplate(), http, Clock.systemUTC())
        val user = user(7)
        assertNull(credentials.resolve(user))
        assertNull(credentials.quoteClient(user))
        assertThrows<BrokerError> { credentials.forUser(user) }
    }

    @Test
    fun nonOwnerCannotUseConfiguredCredentials() {
        val credentials = BrokerCredentials(props.copy(tradingOwnerUserId = 7), StringRedisTemplate(), http, Clock.systemUTC())
        val user = user(8)
        assertNull(credentials.resolve(user))
        assertNull(credentials.quoteClient(user))
        assertThrows<BrokerError> { credentials.forUser(user) }
    }

    @Test
    fun orderAndQuoteClientsIgnoreLegacyDbCredentials() {
        val credentials = BrokerCredentials(props.copy(tradingOwnerUserId = 7), StringRedisTemplate(), http, Clock.systemUTC())
        val user = user(7)
        assertEquals(ResolvedCredentials("kis", "server-kis", "server-kis-secret", "50012345-01"), credentials.resolve(user))
        assertEquals(BrokerAccountKey.kis("50012345-01", true), credentials.forUser(user).orderAccountKey)
        assertEquals(BrokerAccountKey.toss("server-toss", "12"), credentials.quoteClient(user)?.orderAccountKey)
        user.broker = "toss"
        assertEquals(ResolvedCredentials("toss", "server-toss", "server-toss-secret", "12"), credentials.resolve(user))
        assertEquals(BrokerAccountKey.toss("server-toss", "12"), credentials.forUser(user).orderAccountKey)
    }

    @Test
    fun savedDatabaseCredentialsCannotReplaceMissingServerCredentials() {
        val credentials = BrokerCredentials(QuantfolioProperties(tradingOwnerUserId = 7), StringRedisTemplate(), http, Clock.systemUTC())
        val user = user(7)
        assertNull(credentials.resolve(user))
        assertNull(credentials.quoteClient(user))
        assertThrows<BrokerError> { credentials.forUser(user) }
    }

    private fun user(id: Int) = User(
        email = "reader@example.com", passwordHash = "unused", id = id,
        kisAppKey = "legacy-kis", kisAppSecret = "legacy-kis-secret", kisAccountNo = "12345678-01",
        tossAppKey = "legacy-toss", tossAppSecret = "legacy-toss-secret", tossAccountNo = "34",
    )
}
