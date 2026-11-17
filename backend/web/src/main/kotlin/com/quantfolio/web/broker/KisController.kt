package com.quantfolio.web.broker

import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.auth.CurrentUser
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class KisHealth(val broker: String, val env: String, val isPaperTrading: Boolean, val tokenIssued: Boolean, val message: String)

data class QuoteOut(
    val symbol: String, val price: Double, val change: Double, val changeRate: Double, val volume: Long,
    val high: Double, val low: Double, val open: Double, val currency: String,
)

/** 소유자의 증권사 연결 확인·현재가 조회(원본 `routes/kis.py`). */
@RestController
@RequestMapping("/api/kis")
class KisController(
    private val props: QuantfolioProperties,
    private val brokers: BrokerCredentials,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun client(user: User): BrokerClient = try {
        brokers.forUser(user)
    } catch (e: BrokerError) {
        throw ApiException(HttpStatus.BAD_REQUEST, e.message ?: "")
    }

    /** 서버 자격증명으로 토큰을 발급해 연동을 검증. 토스는 모의투자가 없어 항상 prod. */
    @GetMapping("/health")
    fun health(@CurrentUser user: User): KisHealth {
        val c = client(user)
        val broker = user.broker.ifEmpty { "kis" }
        val (env, paper) = if (broker == "toss") "prod" to false else props.kis.env to props.kis.isPaperTrading
        return try {
            c.verifyConnection()
            KisHealth(broker, env, paper, true, "토큰 발급 성공 — 연동 정상")
        } catch (e: BrokerError) {
            log.warn("증권사 연동 검증 실패(broker={}): {}", broker, e.message)
            KisHealth(broker, env, paper, false, "연동 실패: ${e.message}")
        }
    }

    /** 현재가. 통합 시세(토스) 자격증명이 있으면 토스로 국내·해외 통합, 없으면 주문 브로커(국내 한정). */
    @GetMapping("/quote/{symbol}")
    fun quote(@CurrentUser user: User, @PathVariable symbol: String): QuoteOut {
        val c = brokers.quoteClient(user) ?: client(user)
        val q = try {
            c.getQuote(symbol)
        } catch (e: BrokerError) {
            throw ApiException(HttpStatus.BAD_GATEWAY, e.message ?: "")
        }
        return QuoteOut(q.symbol, q.price.toDouble(), q.change.toDouble(), q.changeRate.toDouble(), q.volume, q.high.toDouble(), q.low.toDouble(), q.open.toDouble(), q.currency)
    }
}
