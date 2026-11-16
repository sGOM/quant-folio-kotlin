package com.quantfolio.web.broker

import com.quantfolio.broker.Balance
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.Quote
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.trading.Position
import com.quantfolio.domain.trading.PositionRepository
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import jakarta.servlet.http.Cookie
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.BDDMockito.given
import org.mockito.BDDMockito.willReturn
import org.mockito.Mockito.mock
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.math.BigDecimal
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(ContainersConfig::class)
class BrokerRoutesTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val positions: PositionRepository,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials
    @MockitoSpyBean lateinit var props: QuantfolioProperties

    private fun me(broker: String = "kis"): Pair<User, Cookie> {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x", broker = broker))
        willReturn(u.id).given(props).tradingOwnerUserId // 단일 소유자 서비스: 소유자 외 세션은 401
        return u to Cookie(SESSION_COOKIE, sessions.create(u.id!!))
    }

    private fun anyUser(): User = any(User::class.java) ?: User(email = "", passwordHash = "")

    @Test
    fun healthReportsTokenIssuanceAndMissingCredentials() {
        val (_, c) = me()
        given(brokers.forUser(anyUser())).willThrow(BrokerError("증권사 API 자격증명이 등록되지 않았습니다."))
        mvc.get("/api/kis/health") { cookie(c) }.andExpect {
            status { isBadRequest() }
            jsonPath("$.detail") { value("증권사 API 자격증명이 등록되지 않았습니다.") }
        }
        val client = mock(BrokerClient::class.java)
        willReturn(client).given(brokers).forUser(anyUser()) // 직전 throw 스텁을 호출하지 않는 doReturn 형식
        given(client.verifyConnection()).willReturn(true)
        mvc.get("/api/kis/health") { cookie(c) }.andExpect {
            jsonPath("$.token_issued") { value(true) }
            jsonPath("$.env") { value("vts") }
            jsonPath("$.is_paper_trading") { value(true) }
            jsonPath("$.message") { value("토큰 발급 성공 — 연동 정상") }
        }
        given(client.verifyConnection()).willThrow(BrokerError("토큰 발급 실패: HTTP 403"))
        val (_, toss) = me("toss")
        mvc.get("/api/kis/health") { cookie(toss) }.andExpect {
            jsonPath("$.token_issued") { value(false) }
            jsonPath("$.env") { value("prod") }
            jsonPath("$.is_paper_trading") { value(false) }
            jsonPath("$.message") { value("연동 실패: 토큰 발급 실패: HTTP 403") }
        }
    }

    @Test
    fun quotePrefersUnifiedTossClientAndMapsBrokerErrorTo502() {
        val (_, c) = me()
        val toss = mock(BrokerClient::class.java)
        given(brokers.quoteClient(anyUser())).willReturn(toss)
        given(toss.getQuote("AAPL")).willReturn(Quote("AAPL", BigDecimal("190.5"), currency = "USD"))
        mvc.get("/api/kis/quote/AAPL") { cookie(c) }.andExpect {
            jsonPath("$.price") { value(190.5) }
            jsonPath("$.currency") { value("USD") }
            jsonPath("$.volume") { value(0) }
        }
        given(toss.getQuote("XXX")).willThrow(BrokerError("시세 응답에 'XXX' 매칭 없음"))
        mvc.get("/api/kis/quote/XXX") { cookie(c) }.andExpect {
            status { isBadGateway() }
            jsonPath("$.detail") { value("시세 응답에 'XXX' 매칭 없음") }
        }
    }

    @Test
    fun positionsPreferBrokerBalanceAndFallBackToDb() {
        val (u, c) = me()
        val account = "current-account"
        positions.save(Position(u.id!!, null, "000660", BigDecimal("5"), BigDecimal("120000"), brokerAccountKey = account))
        positions.save(Position(u.id!!, null, "000660", BigDecimal("10"), BigDecimal("80000"), brokerAccountKey = "old-account"))
        positions.save(Position(u.id!!, null, "000660", BigDecimal("20"), BigDecimal("70000")))
        val client = mock(BrokerClient::class.java)
        given(client.orderAccountKey).willReturn(account)
        given(brokers.forUser(anyUser())).willReturn(client)
        given(client.getBalance()).willReturn(Balance(listOf(mapOf("pdno" to "005930", "hldg_qty" to "7", "pchs_avg_pric" to "0"))))
        mvc.get("/api/trading/positions") { cookie(c) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].symbol") { value("005930") }
            jsonPath("$[0].avg_price") { value(null) } // 평단 0/미제공은 null(0 sentinel 금지)
        }
        given(client.getBalance()).willThrow(BrokerError("잔고 조회 실패"))
        mvc.get("/api/trading/positions") { cookie(c) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].symbol") { value("000660") }
            jsonPath("$[0].qty") { value(5.0) }
            jsonPath("$[0].avg_price") { value(120000.0) }
        }
    }
}
