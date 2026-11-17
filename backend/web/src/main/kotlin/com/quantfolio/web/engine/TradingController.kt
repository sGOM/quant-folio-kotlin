package com.quantfolio.web.engine

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.domain.user.User
import com.quantfolio.web.auth.CurrentUser
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant

data class PositionOut(val symbol: String, val qty: Double, val avgPrice: Double?)

data class OrderOut(val id: Int, val symbol: String, val side: String, val qty: Double, val price: Double?, val status: String, val reason: String?, val createdAt: Instant)

/**
 * 매매 조회(원본 `routes/trading.py`). 잔고의 신뢰 소스는 **증권사 실계좌** — 로컬 DB 는 이 시스템이 낸 주문만 알아서
 * 수동 매매·다른 계좌 보유를 놓친다. 잔고 조회 실패 시 확인된 계좌의 로컬 포지션만 보여준다.
 */
@RestController
@RequestMapping("/api/trading")
class TradingController(private val jdbc: NamedParameterJdbcTemplate, private val brokers: BrokerCredentials) {
    private val log = LoggerFactory.getLogger(javaClass)

    @GetMapping("/positions")
    fun positions(@CurrentUser user: User): List<PositionOut> {
        val broker = try {
            brokers.forUser(user)
        } catch (e: BrokerError) {
            return emptyList()
        }
        val balance = try {
            broker.getBalance()
        } catch (e: BrokerError) {
            log.warn("실계좌 잔고 조회 실패 — 로컬 포지션으로 폴백 user={}: {}", user.id, e.message)
            return broker.orderAccountKey?.let { dbPositions(user, it) }.orEmpty()
        }
        return balance.positionsNormalized().map { PositionOut(it.symbol, it.qty.toDouble(), it.avgPrice?.toDouble()) }
    }

    private fun dbPositions(user: User, accountKey: String): List<PositionOut> =
        jdbc.query("select symbol, qty, avg_price from positions where user_id = :u and broker_account_key = :a and qty > 0",
            mapOf("u" to user.id, "a" to accountKey)) { rs, _ ->
            PositionOut(rs.getString(1), rs.getBigDecimal(2).toDouble(), rs.getBigDecimal(3).toDouble())
        }

    /** 최근 주문(감사 로그) 최신순, limit 최대 200. */
    @GetMapping("/orders")
    fun orders(@CurrentUser user: User, @RequestParam(defaultValue = "50") limit: Int): List<OrderOut> =
        jdbc.query(
            "select id, symbol, side, qty, price, status, reason, created_at from orders where user_id = :u order by created_at desc limit :n",
            MapSqlParameterSource("u", user.id).addValue("n", minOf(limit, 200)),
        ) { rs, _ ->
            OrderOut(
                rs.getInt(1), rs.getString(2), rs.getString(3), rs.getBigDecimal(4).toDouble(), (rs.getObject(5) as BigDecimal?)?.toDouble(),
                rs.getString(6), rs.getString(7), rs.getTimestamp(8).toInstant(),
            )
        }
}
