package com.quantfolio.web.engine

import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.common.MarketClock
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

data class LegacyAccountLinkRequest(
    @field:Pattern(regexp = "[0-9A-Z.\\-]{1,20}") val symbol: String,
    @field:Size(max = 20) val orderIds: Set<Int>? = null,
    val apply: Boolean = false,
)

data class LegacyAccountLinkItem(val id: Int, val result: String)
data class LegacyAccountLinkResult(val orders: List<LegacyAccountLinkItem>, val positions: List<LegacyAccountLinkItem>)

@RestController
@RequestMapping("/api/trading")
class LegacyAccountLinksController(private val links: LegacyAccountLinks) {
    @PostMapping("/legacy-account-links")
    fun link(@CurrentUser user: User, @Valid @RequestBody request: LegacyAccountLinkRequest): LegacyAccountLinkResult =
        links.review(user, request)
}

/** Explicit maintenance operation. It never creates executions, sends orders or guesses an account from holdings alone. */
@Service
class LegacyAccountLinks(
    private val brokers: BrokerCredentials,
    private val users: UserRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class OrderRow(
        val id: Int, val account: String?, val strategy: Int?, val number: String?, val organization: String?,
        val side: String, val qty: BigDecimal, val status: String, val created: Instant,
    ) {
        val day get() = MarketClock.kstDate(created)
    }

    private data class ExecutionRow(
        val id: Int, val order: Int, val strategy: Int?, val qty: BigDecimal, val price: BigDecimal, val positionQty: BigDecimal?,
    )

    private data class PositionRow(val id: Int, val account: String?, val strategy: Int?, val qty: BigDecimal, val average: BigDecimal)
    private data class Snapshot(val orders: List<OrderRow>, val executions: List<ExecutionRow>, val positions: List<PositionRow>)

    fun review(user: User, request: LegacyAccountLinkRequest): LegacyAccountLinkResult {
        val uid = user.id!!
        val broker = client(user)
        val account = broker.orderAccountKey ?: throw ApiException(HttpStatus.BAD_REQUEST, "주문 계좌를 확인할 수 없습니다.")
        val before = snapshot(uid, request.symbol)
        val candidates = before.orders.filter { it.account == null && (request.orderIds == null || it.id in request.orderIds) }
        if (candidates.size > MAX_LOOKUPS) throw ApiException(HttpStatus.BAD_REQUEST, "한 번에 확인할 주문을 최대 $MAX_LOOKUPS 건으로 지정하세요.")
        val fillsByOrder = before.executions.groupBy { it.order }
        val orderResults = candidates.map { order ->
            LegacyAccountLinkItem(order.id, checkOrder(broker, request.symbol, order, before.orders, fillsByOrder[order.id].orEmpty()))
        }
        val verified = orderResults.filter { it.result == VERIFIED }.map { it.id }.toSet()
        val positionResults = before.positions.filter { it.account == null }.map { position ->
            LegacyAccountLinkItem(position.id, checkPosition(broker, account, request.symbol, position, before, verified, fillsByOrder))
        }
        val position = positionResults.singleOrNull { it.result == VERIFIED }
        if (!request.apply || (verified.isEmpty() && position == null)) return LegacyAccountLinkResult(orderResults, positionResults)

        // Network calls finish before any row is locked. Re-read the connection and the ledger under locks before writing.
        tx.executeWithoutResult {
            val locked = jdbc.queryForList("select id from users where id = :u for no key update", mapOf("u" to uid), Int::class.java)
            val current = if (locked.isNotEmpty()) users.findById(uid).orElse(null) else null
            if (current == null || client(current).orderAccountKey != account || snapshot(uid, request.symbol, lock = true) != before) {
                throw ApiException(HttpStatus.CONFLICT, "계좌 또는 장부가 변경됐습니다. 다시 확인하세요.")
            }
            if (verified.isNotEmpty()) {
                check(jdbc.update("update orders set broker_account_key = :a where user_id = :u and id in (:ids) and broker_account_key is null",
                    mapOf("a" to account, "u" to uid, "ids" to verified)) == verified.size)
            }
            if (position != null) {
                check(jdbc.update("update positions set broker_account_key = :a where user_id = :u and id = :id and broker_account_key is null",
                    mapOf("a" to account, "u" to uid, "id" to position.id)) == 1)
                val ids = before.executions.map { it.id }
                check(jdbc.update("update executions set account_position_qty = filled_qty where id in (:ids) and account_position_qty is null",
                    mapOf("ids" to ids)) == ids.size)
            }
        }
        log.info("기존 계좌 연결 user={} orders={} position={}", uid, verified, position?.id)
        return LegacyAccountLinkResult(orderResults.map(::linked), positionResults.map(::linked))
    }

    private fun client(user: User): BrokerClient = try {
        brokers.forUser(user)
    } catch (_: Exception) {
        throw ApiException(HttpStatus.BAD_REQUEST, "현재 증권사 연결을 확인할 수 없습니다.")
    }

    private fun checkOrder(broker: BrokerClient, symbol: String, order: OrderRow, allOrders: List<OrderRow>, fills: List<ExecutionRow>): String {
        if (order.status != "filled" || order.number.isNullOrBlank() || !validFullFill(order, fills)) return "incomplete_local_order"
        if (allOrders.count { it.number == order.number && it.organization == order.organization && it.day == order.day } != 1) {
            return "ambiguous_order_number"
        }
        val evidence = try {
            broker.legacyFillEvidence(order.number, order.organization, symbol, order.day)
        } catch (_: Exception) {
            return "broker_evidence_unavailable"
        } ?: return "broker_evidence_missing"
        val elapsed = Duration.between(order.created, evidence.orderedAt).toMillis()
        val amount = fills.sumOf { it.qty * it.price }
        return if (evidence.orderId == order.number && evidence.symbol == symbol && evidence.side == order.side &&
            elapsed in -2_000L..60_000L && evidence.orderQty.same(order.qty) && evidence.filledQty.same(order.qty) &&
            evidence.filledAmount.same(amount)) VERIFIED else "broker_evidence_mismatch"
    }

    private fun checkPosition(
        broker: BrokerClient, account: String, symbol: String, position: PositionRow,
        before: Snapshot, verified: Set<Int>, fillsByOrder: Map<Int, List<ExecutionRow>>,
    ): String {
        if (position.qty.signum() <= 0 || position.strategy == null || before.positions.size != 1) return "ambiguous_position"
        val orders = before.orders.filter { it.status != "rejected" || it.id in fillsByOrder }
        if (orders.isEmpty() || orders.any {
                it.status != "filled" || it.strategy != position.strategy || (it.account != account && it.id !in verified) ||
                    !validFullFill(it, fillsByOrder[it.id].orEmpty())
            } || before.executions.any { it.positionQty != null }) return "unverified_position_history"

        val sides = orders.associate { it.id to it.side }
        var qty = BigDecimal.ZERO
        var average = BigDecimal.ZERO
        for (fill in before.executions) {
            if (sides.getValue(fill.order) == "buy") {
                val next = qty + fill.qty
                average = (qty * average + fill.qty * fill.price).divide(next, 4, RoundingMode.HALF_UP)
                qty = next
            } else {
                qty -= fill.qty
                if (qty.signum() < 0) return "unverified_position_history"
                if (qty.signum() == 0) average = BigDecimal.ZERO
            }
        }
        if (!qty.same(position.qty) || !average.same(position.average)) return "local_position_mismatch"
        val balance = try {
            broker.legacyBalanceEvidence().positions.singleOrNull { (it["pdno"] ?: it["symbol"]) == symbol }
        } catch (_: Exception) {
            return "broker_balance_unavailable"
        } ?: return "broker_balance_missing_or_ambiguous"
        val held = (balance["hldg_qty"] ?: balance["quantity"])?.toString()?.toBigDecimalOrNull()
        val cost = (balance["pchs_avg_pric"] ?: balance["averagePurchasePrice"])?.toString()?.toBigDecimalOrNull()
        return if (held?.same(qty) == true && cost?.same(average) == true) VERIFIED else "broker_balance_mismatch"
    }

    private fun validFullFill(order: OrderRow, fills: List<ExecutionRow>): Boolean =
        order.side in setOf("buy", "sell") && order.qty.signum() > 0 && fills.isNotEmpty() &&
            fills.all { it.strategy == order.strategy && it.qty.signum() > 0 && it.price.signum() > 0 } && fills.sumOf { it.qty }.same(order.qty)

    private fun snapshot(userId: Int, symbol: String, lock: Boolean = false): Snapshot {
        val p = mapOf("u" to userId, "s" to symbol)
        val rowLock = if (lock) " for update" else ""
        val orders = jdbc.query(
            """select id, broker_account_key, strategy_id, kis_order_id, kis_order_org_no, side, qty, status, created_at
               from orders where user_id = :u and symbol = :s order by id$rowLock""", p,
        ) { rs, _ ->
            OrderRow(rs.getInt(1), rs.getString(2), rs.getObject(3) as Int?, rs.getString(4), rs.getString(5), rs.getString(6),
                rs.getBigDecimal(7), rs.getString(8), rs.getTimestamp(9).toInstant())
        }
        val executions = jdbc.query(
            """select e.id, e.order_id, e.strategy_id, e.filled_qty, e.filled_price, e.account_position_qty
               from executions e join orders o on o.id = e.order_id where o.user_id = :u and o.symbol = :s
               order by e.executed_at, e.id${if (lock) " for update of e" else ""}""", p,
        ) { rs, _ -> ExecutionRow(rs.getInt(1), rs.getInt(2), rs.getObject(3) as Int?, rs.getBigDecimal(4), rs.getBigDecimal(5), rs.getBigDecimal(6)) }
        val positions = jdbc.query(
            "select id, broker_account_key, strategy_id, qty, avg_price from positions where user_id = :u and symbol = :s order by id$rowLock", p,
        ) { rs, _ -> PositionRow(rs.getInt(1), rs.getString(2), rs.getObject(3) as Int?, rs.getBigDecimal(4), rs.getBigDecimal(5)) }
        return Snapshot(orders, executions, positions)
    }

    private fun linked(item: LegacyAccountLinkItem) = if (item.result == VERIFIED) item.copy(result = "linked") else item
    private fun BigDecimal.same(other: BigDecimal) = compareTo(other) == 0

    private companion object {
        const val MAX_LOOKUPS = 20
        const val VERIFIED = "verified"
    }
}
