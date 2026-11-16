package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.broker.BrokerOrderRow
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.MarketClock
import com.quantfolio.common.RedisKeys
import com.quantfolio.common.withLockOrElse
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.user.UserRepository
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.math.RoundingMode
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap

/**
 * 미체결(submitted/partial)·접수불명(주문번호 없는 pending/submitted/partial) 주문을 실제 체결로 정합(원본 `engine/reconcile.py`).
 *
 * - 이미 기록된 체결(executions 합)을 빼고 **증분만** 기록 — 몇 번을 돌려도 중복 가산이 없다.
 * - 체결 소스: ① 체결조회 ② 주문일 주문 목록 ③ KIS 모의 잔고 — 매수는 미기록 체결 배정, 매도는 주문일이 지난 뒤 "잔고 == 장부 순보유" 판정만
 *   (원본은 모의투자에서 ①②가 늘 빈 응답이라고 관찰했다 — 2026-11-16 실측에서 ①은 원본 TR(8001R)이 빈 응답이었고 0081R 로 바꾼 뒤 행이 오며, ②도 행이 온다.
 *   ③은 조회가 0 일 때의 방어선으로 그대로 둔다).
 * - 실시간 체결통보는 수량을 직접 쓰지 않고 그 주문 하나의 정합([reconcileOrder])을 트리거한다(`FillNoticeApplier`).
 * - 접수불명은 주문번호가 없어 목록의 유일 후보로 번호를 회수하거나 잔고 교차확인으로만 밝히고, 못 밝히면 critical 알림(자동 재주문은 이중 매수라 절대 안 함).
 *   전송 중인 주문을 오판하지 않게 5분 유예 후에만 대상.
 * - 주문일이 지난 주문은 확실한 근거(목록 행·체결조회 누적·종료 상태·모의 잔고·증인 있는 목록에 없음)로 체결이 다 기록됐다고 확정될 때만 종결(`closed_at`).
 *   정정 주문에 체결이 있으면 자동 반영·종결 없이 사람에게. 근거 없이 남으면 [PAST_DAY_RECHECK_SECONDS] 마다 다시 보고, 재확인에도 남은 주문번호 있는 주문은 사람에게.
 * - 주문 단위 분산 락으로 다른 경로(수동 스크립트·실시간 체결통보)와 직렬화. 증권사 조회는 락 밖, 락 안은 DB 반영만.
 */
@Component
class Reconciler(
    private val orders: OrderRepository,
    private val users: UserRepository,
    private val brokers: BrokerCredentials,
    private val fills: Fills,
    private val alerts: AlertPublisher,
    private val redis: StringRedisTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
    private val props: QuantfolioProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("engine.reconcile")

    /**
     * 주문일이 지났는데 진전 없이 끝난 주문 id → 다음 확인 시각. 메모리에만 둔다 — 재시작으로 비면 한 번 더 확인할 뿐이다.
     * 스케줄러와 수동 호출이 동시에 부를 수 있어 동시성 맵.
     */
    private val nextCheck = ConcurrentHashMap<Int, Instant>()

    fun reconcileOpenOrders(userId: Int? = null): Map<String, Int> {
        val p = MapSqlParameterSource("grace", Timestamp.from(clock.instant().minus(Duration.ofSeconds(PENDING_GRACE_SECONDS)))).addValue("u", userId)
        val userFilter = if (userId != null) " and user_id = :u" else ""
        val open = jdbc.queryForList("select id from orders where status in ('submitted', 'partial') and kis_order_id is not null and kis_order_id <> '' and closed_at is null$userFilter order by id", p, Int::class.java)
        // OrderExecutor 가 새 주문을 막는 접수불명 집합과 같게 — 잔고로 일부 회수돼 partial 이 된 주문이 정합·알림 없이 신규 주문만 막는 상태가 되지 않게.
        val stale = jdbc.queryForList("select id from orders where status in ('pending', 'submitted', 'partial') and coalesce(trim(kis_order_id), '') = '' and closed_at is null and created_at < :grace$userFilter order by id", p, Int::class.java)
        val ids = LinkedHashSet<Int>().apply { addAll(open.filterNotNull()); addAll(stale.filterNotNull()) }
        // 대상에서 빠진(종결된) 주문을 지운다. 사용자 한정 호출은 다른 사용자 주문이 대상인지 모르므로 전체 pass 에서만.
        if (userId == null) nextCheck.keys.retainAll(ids)
        if (ids.isEmpty()) return linkedMapOf("checked" to 0, "filled" to 0, "partial" to 0, "errors" to 0)
        val pass = ReconcilePass()
        val now = clock.instant()
        for (id in ids) {
            if (nextCheck[id]?.let { now.isBefore(it) } == true) continue // 재확인 시각 전 — 증권사 조회 없이 건너뛴다
            // 한 주문의 예외가 pass 를 끊으면 뒤 주문이 매 pass 굶는다. 인터럽트는 종료 신호라 삼키지 않는다.
            try {
                pass.processOne(id)
            } catch (e: Exception) {
                if (e is InterruptedException || Thread.currentThread().isInterrupted) throw e
                log.error("주문 정합 실패 order={}", id, e)
                pass.count("errors")
            }
        }
        return pass.stats
    }

    /**
     * 주문 하나만 지금 정합한다 — 실시간 체결통보(`FillNoticeApplier`)가 부른다. 대상은 [reconcileOpenOrders] 의 주문번호 있는 집합
     * (submitted·partial, 주문번호 있음, 미종결)과 같고, 아니면(전량 기록·종결·접수불명) 증권사를 조회하지 않는다. 처리는 주기 정합과 같은 [ReconcilePass.processOne]:
     * 증권사 누적을 락 밖에서 읽고 락 안에서 기기록 대비 증분만 기록하며, 락이 바쁘면 기다리지 않고 넘긴다(다음 주기 정합이 잡는다).
     */
    fun reconcileOrder(orderId: Int): Map<String, Int> {
        val pass = ReconcilePass()
        val open = jdbc.queryForObject(
            "select count(*) from orders where id = :id and status in ('submitted', 'partial') and kis_order_id is not null and kis_order_id <> '' and closed_at is null",
            MapSqlParameterSource("id", orderId), Int::class.java,
        )!! > 0
        if (open) pass.processOne(orderId)
        return pass.stats
    }

    /**
     * 사람이 증권사에서 직접 확인한 이 주문의 최종 누적 체결([filledQty]주, 그 평균 체결가 [avgPrice])로 장부를 맞추고 주문을 정리한다 —
     * 엔진 제어 메시지 `resolve_order`(원본에 없는 경로) — `{"action":"resolve_order","order_id":123,"symbol":"005930","filled_qty":3,"avg_price":"71200"}`.
     * 자동 정합이 근거를 못 얻어 사람에게 넘긴 주문을 DB 직접 수정 없이 닫는 수단. 되돌리는 수단은 DB 직접 수정뿐이라 [symbol] 로 `order_id` 오입력을 거른다.
     *
     * 정합 락 안에서 주문을 다시 읽고 아래를 모두 만족할 때만 반영하고, 하나라도 어기면 아무것도 바꾸지 않고 warning `order_resolve_rejected`(사유)를 낸다:
     * 종목 일치([symbol] 을 trim 한 값과 주문의 종목이 정확히 같음 — 대소문자·0 채움 정규화 없음, 거부 사유 판정의 맨 앞), 미종결·pending/submitted/partial, 주문일(KST)이 지남(자동 종결과 같은 원칙), 0 ≤ [filledQty] ≤ 주문수량, [filledQty] ≥ 기록(기록을 줄이지 않는다),
     * 증분 > 0 이면 [avgPrice] > 0·주문 계좌 키 있음·증분 단가(자동 정합과 같은 산식) > 0. 이미 전량 기록됐는데 미완료 상태인 주문도 거부한다(상태만 바꾸지 않는다).
     * 락이 바쁘면 거부(다시 보내야 한다).
     *
     * 반영은 한 트랜잭션: 증분 기록([Fills.record] — executions·주문 상태·포지션. 포지션은 주문의 brokerAccountKey 계좌로 갱신)과,
     * [filledQty] < 주문수량이면 자동 종결과 같은 조건부 update. 계좌 검증(현재 연결 계좌와 대조)은 하지 않는다 — 사람이 증권사에서 확인한 값이다.
     * 증권사 조회·주문 전송은 없다. 성공은 warning `order_resolved_manually`(감사 기록). 주문이 없으면 알릴 사용자를 몰라 경고 로그만. 알림 dedup 없음.
     * @return 반영했으면 true
     */
    fun resolveManually(orderId: Int, symbol: String, filledQty: Int, avgPrice: BigDecimal?): Boolean {
        val seen = orders.findById(orderId).orElse(null)
        if (seen == null) {
            log.warn("수동 정리 거부 — 주문 없음 order={}", orderId)
            return false
        }
        val input = "누적 ${grouped(filledQty)}주, 평균가 ${avgPrice?.toPlainString() ?: "없음"}"
        var resolved = false
        // 알림(경고 + 텔레그램일 수 있음)은 락을 놓은 뒤에 낸다.
        val alert: () -> Unit = redis.withLockOrElse(
            RedisKeys.reconcileLock(orderId), Duration.ofSeconds(RedisKeys.RECONCILE_LOCK_TTL_SECONDS),
            busy = { rejectManually(seen, input, "다른 경로(정합·체결통보·주문 실행)가 이 주문을 처리 중입니다 — 잠시 뒤 다시 보내세요") },
        ) {
            val order = orders.findById(orderId).orElse(null)
                ?: return@withLockOrElse { log.warn("수동 정리 거부 — 확인 중 주문이 사라짐 order={}", orderId) }
            val target = order.qty.toInt()
            val already = fills.recordedQty(orderId)
            val delta = filledQty - already
            val orderDay = MarketClock.kstDate(order.createdAt)
            val rejection = when {
                // 종목이 다르면 애초에 다른 주문을 가리킨 것이다 — 아래 어떤 사유보다 먼저
                order.symbol != symbol.trim() -> "입력한 종목: ${symbol.trim()}, 주문의 종목: ${order.symbol} — 종목이 달라 다른 주문을 가리킨 것입니다. order_id 를 확인하세요"
                order.closedAt != null -> "이미 종결된 주문입니다(상태 ${order.status.name})"
                order.status !in listOf(OrderStatus.pending, OrderStatus.submitted, OrderStatus.partial) ->
                    "상태가 ${order.status.name} 라 정리 대상이 아닙니다(pending·submitted·partial 만)"
                !orderDay.isBefore(MarketClock.todayKst(clock)) -> "주문일(KST $orderDay)이 아직 지나지 않았습니다 — 증권사에서 더 체결될 수 있어 주문일 뒤에만 받습니다"
                filledQty < 0 || filledQty > target -> "입력한 누적 체결 ${grouped(filledQty)}주가 0 이상 주문수량 ${grouped(target)}주 이하가 아닙니다"
                filledQty < already -> "입력한 누적 체결 ${grouped(filledQty)}주가 이미 기록된 체결 ${grouped(already)}주보다 적습니다 — 기록을 줄이지 않습니다"
                delta == 0 && filledQty == target ->
                    "주문수량 전량(${grouped(target)}주)이 이미 기록돼 있는데 상태가 ${order.status.name} 입니다 — 이 경로는 상태만 바꾸지 않습니다. 장부를 직접 확인하세요"
                delta > 0 && (avgPrice == null || avgPrice.signum() <= 0) -> "증분 ${grouped(delta)}주를 기록하려면 0 보다 큰 avg_price(누적 평균 체결가)가 필요합니다"
                delta > 0 && order.brokerAccountKey == null -> "주문에 증권사 계좌 키가 없어(계좌 미상 주문) 체결을 기록할 수 없습니다"
                else -> null
            }
            if (rejection != null) return@withLockOrElse rejectManually(order, input, rejection)
            val price = if (delta > 0) incrementalPrice(orderId, filledQty, avgPrice!!, delta) else null
            if (price != null && price.signum() <= 0) {
                return@withLockOrElse rejectManually(
                    order, input, "증분 단가가 0 이하(${price.toPlainString()})입니다 — 평균가 × 누적 수량이 이미 기록된 체결 금액 이하입니다. 입력값을 확인하세요",
                )
            }
            var fill: Fills.RecordedFill? = null
            val conflict = tx.execute { st ->
                if (price != null) {
                    fill = fills.record(order, delta, price, filledQty >= target, already)
                        ?: return@execute "확인 중 다른 경로가 체결을 기록했습니다 — 최신 기록을 확인한 뒤 다시 보내세요"
                    orders.flush() // 아래 조건부 update 가 record 가 바꾼 상태를 보게 — JdbcTemplate 문장은 JPA 변경을 flush 하지 않는다
                }
                if (filledQty < target && !closeIfUnchanged(order, filledQty)) {
                    st.setRollbackOnly() // 기록한 증분도 되돌린다 — 기록만 되고 종결은 안 된 반쪽 상태를 남기지 않는다
                    return@execute "확인 중 주문 상태나 기록된 체결이 바뀌어 종결하지 못했습니다 — 다시 확인한 뒤 보내세요"
                }
                null
            }
            if (conflict != null) return@withLockOrElse rejectManually(order, input, conflict)
            resolved = true
            log.info("수동 정리 order={} {} {} 기록 {}→{}/{}주 증분 단가 {}", orderId, order.symbol, order.side, already, filledQty, target, price)
            val recordedFill = fill
            return@withLockOrElse {
                if (recordedFill != null) fills.publishExecution(order, delta, price!!, recordedFill, source = "manual")
                val result = if (filledQty < target) {
                    "미체결 잔량 ${grouped(target - filledQty)}주는 더 체결되지 않는 것으로 종결했습니다(상태 ${if (filledQty == 0) "cancelled" else "partial"})"
                } else {
                    "전량 체결(filled)로 기록했습니다"
                }
                val recordedPart = if (price != null) "이번에 기록한 체결 ${grouped(delta)}주 @ ${price.toPlainString()}" else "새로 기록한 체결 없음"
                alerts.publish(
                    order.userId, order.strategyId ?: 0, AlertSeverity.warning,
                    "주문 #${order.id} (${order.symbol} ${order.side} ${grouped(target)}주)을 운영자가 입력한 증권사 확인값으로 정리했습니다 — " +
                        "최종 누적 ${grouped(filledQty)}주(이전 기록 ${grouped(already)}주), $recordedPart. $result. 엔진은 이 값을 증권사에서 검증하지 않았습니다.",
                    "order_resolved_manually",
                )
            }
        }
        alert()
        if (resolved) nextCheck.remove(orderId)
        return resolved
    }

    /** 수동 정리 거부 — 아무것도 바꾸지 않았다는 사실과 사유를 알린다(락 밖에서 부를 람다). */
    private fun rejectManually(order: Order, input: String, reason: String): () -> Unit = {
        log.warn("수동 정리 거부 order={} ({}): {}", order.id, input, reason)
        alerts.publish(
            order.userId, order.strategyId ?: 0, AlertSeverity.warning,
            "주문 #${order.id} (${order.symbol} ${order.side})의 수동 정리 요청($input)을 거부했습니다 — $reason. 아무것도 바꾸지 않았습니다.",
            "order_resolve_rejected",
        )
    }

    private data class BalanceSnapshot(
        /** null = 잔고 조회 실패(보유 없음과 다르다). */
        val holdings: Map<String, Pair<Int, BigDecimal?>>?,
        val recordedSales: Map<String, BigDecimal>,
    )

    private data class RecordedHoldings(val net: BigDecimal, val sold: BigDecimal)

    /**
     * 잔고 교차확인 결과. [NONE] 만 "더 배정할 수량이 없다"는 확정(종결 근거가 될 수 있다).
     * [HELD](잔고 추정 대상 아님·보류)는 기존처럼 접수불명 알림 근거지만 종결 근거가 아니고, [UNDETERMINED](조회 실패·단가 불명)는 둘 다 아니다.
     */
    private enum class BalanceResult { ASSIGNED, NONE, HELD, UNDETERMINED }

    /** 한 번의 정합 실행. 사용자별 브로커·잔고·(사용자, 주문일)별 주문 목록을 실행 동안만 캐시하고 통계를 모은다. */
    private inner class ReconcilePass {
        val stats = linkedMapOf("checked" to 0, "filled" to 0, "partial" to 0, "errors" to 0)
        private val brokerCache = HashMap<Int, BrokerClient?>()
        private val balanceCache = HashMap<Int, BalanceSnapshot>()
        private val orderListCache = HashMap<Pair<Int, LocalDate>, List<BrokerOrderRow>?>()
        /** 목록이 null 인 키 중 조회 실패인 것(나머지는 미지원) — 미해결 알림이 사유를 구분해 말하게. */
        private val failedLists = HashSet<Pair<Int, LocalDate>>()

        // 지금 처리 중인 주문 하나의 상태(processOne 이 초기화) — 재확인 주기와 미해결 알림에 쓴다.
        /** 종결·주문번호 회수·체결 기록·상태 보정 중 하나라도 했다(또는 다른 경로가 주문을 바꿨다). */
        private var progressed = false
        /** 더 구체적인 critical 알림(체결 불일치·외부 정정·계좌 미확인)을 냈다 — 미해결 알림을 겹쳐 내지 않는다. */
        private var escalated = false
        /** 이번 확인에서 실제로 관측한 사실(미해결 알림 사유). */
        private val notes = ArrayList<String>()

        fun count(key: String) {
            stats[key] = stats.getValue(key) + 1
        }

        // getOrPut 은 저장된 null 을 "없음"으로 보고 다시 만든다 — 실패한 사용자는 주문마다가 아니라 사용자당 한 번만 센다(원본 `if uid not in brokers`).
        private fun broker(uid: Int): BrokerClient? {
            if (uid in brokerCache) return brokerCache[uid]
            val user = users.findById(uid).orElse(null)
            val b = if (user == null) {
                null
            } else {
                try {
                    brokers.forUser(user)
                } catch (e: BrokerError) {
                    log.warn("브로커 생성 실패 user={}: {}", uid, e.message)
                    count("errors") // 자격증명 만료가 조용히 넘어가지 않게
                    null
                }
            }
            brokerCache[uid] = b
            return b
        }

        private fun balance(uid: Int, b: BrokerClient, accountKey: String) = balanceCache.getOrPut(uid) {
            val recordedSales = salesBySymbol(uid, accountKey)
            val holdings = try {
                b.getBalance().positionsNormalized().associate { it.symbol to (it.qty to it.avgPrice) }
            } catch (e: Exception) {
                // BrokerError 가 아닌 조회 실패(IOException 등)도 판단 불가 — 캐시하지 않으면 같은 pass 의 주문마다 다시 친다.
                if (e is InterruptedException || Thread.currentThread().isInterrupted) throw e
                log.warn("잔고 조회 실패 user={}: {}", uid, e.message)
                count("errors") // 사용자당 한 번 — 실패도 캐시해 같은 pass 의 다음 주문이 다시 치지 않는다
                null
            }
            BalanceSnapshot(holdings, recordedSales)
        }

        /** (사용자, 주문일)의 증권사 주문 목록(락 밖). null = 미지원 또는 조회 실패 — 실패도 캐시해 errors 는 사용자·주문일당 한 번. */
        private fun orderList(uid: Int, b: BrokerClient, day: LocalDate): List<BrokerOrderRow>? {
            val key = uid to day
            if (key in orderListCache) return orderListCache[key]
            val rows = try {
                b.dailyOrders(day)
            } catch (e: Exception) {
                if (e is InterruptedException || Thread.currentThread().isInterrupted) throw e
                log.warn("주문 목록 조회 실패 user={} day={}: {}", uid, day, e.message)
                count("errors")
                failedLists += key
                null
            }
            orderListCache[key] = rows
            return rows
        }

        /**
         * 접수불명 주문에 붙일 수 있는 증권사 주문번호(락 밖): 종목·매매방향·주문수량이 같고, 주문 시각이 주문 행 생성 시각의
         * [-ATTACH_BEFORE_SECONDS, +ATTACH_AFTER_SECONDS] 안이며, 같은 사용자·계좌의 같은 주문일 다른 주문에 붙어 있지 않은 것. 목록 미지원·조회 실패면 빈 목록.
         * 정정·취소 행(원주문번호 있음)은 엔진이 낸 주문이 아니라 후보가 아니다.
         */
        private fun attachCandidates(order: Order, b: BrokerClient, day: LocalDate): List<String> {
            val rows = orderList(order.userId, b, day) ?: return emptyList()
            val matches = rows.filter { r ->
                r.originalOrderNo == null && r.symbol == order.symbol && r.side == order.side && BigDecimal(r.orderQty).compareTo(order.qty) == 0 &&
                    r.orderTime?.let { t ->
                        Duration.between(order.createdAt, day.atTime(t).atZone(MarketClock.KST).toInstant()).toMillis() in
                            -ATTACH_BEFORE_SECONDS * 1000..ATTACH_AFTER_SECONDS * 1000
                    } == true
            }.map { it.orderNo }
            if (matches.isEmpty()) return matches
            val taken = jdbc.queryForList(
                """select kis_order_id from orders where user_id = :u and broker_account_key = :a and kis_order_id in (:nos) and id <> :id
                   and created_at >= :dayStart and created_at < :dayEnd""",
                mapOf("u" to order.userId, "a" to order.brokerAccountKey, "nos" to matches, "id" to order.id) + dayBounds(day), String::class.java,
            ).toSet()
            return matches.filter { it !in taken }
        }

        private fun apply(order: Order, delta: Int, price: BigDecimal, fully: Boolean, already: Int, fromBalance: Boolean = false): Boolean {
            val (allowed, recorded) = tx.execute {
                // 잔고 조회 중 계좌가 바뀌었는지 확인하고, 기록을 커밋할 때까지 계좌 변경을 막는다.
                val allowed = !fromBalance || paperBalanceAllowed(order, lock = true)
                allowed to if (allowed) fills.record(order, delta, price, fully, already) else null
            }
            if (recorded != null) {
                progressed = true
                count(if (fully) "filled" else "partial")
                fills.publishExecution(order, delta, price, recorded)
                log.info("체결 정합: {} {} +{}주 @ {} → {}", order.side, order.symbol, delta, price, order.status)
            }
            return allowed
        }

        /**
         * 잔고 교차확인에 쓸 스냅샷(증권사 조회라 락 밖에서 부른다). 잔고 추정 대상이 아니면 null — [balanceCrossCheck] 가 HELD 로 본다.
         * 매수는 체결 배정, 매도는 주문일이 지난 뒤([pastDay]) "잔고 == 장부 순보유" 종결 판정에만 쓴다(매도 체결은 추정하지 않는다).
         */
        private fun balanceEvidence(order: Order, b: BrokerClient, pastDay: Boolean): BalanceSnapshot? {
            val eligible = order.side == "buy" || (order.side == "sell" && pastDay)
            if (!props.kis.isPaperTrading || !eligible) return null
            val accountKey = order.brokerAccountKey ?: return null
            // 계좌 확인을 먼저 — 이미 읽은 잔고(캐시)에 종목이 없다는 것도 확인을 통과해야 "배정할 수량 없음" 확정이 된다.
            if (!paperBalanceAllowed(order)) return null
            return balance(order.userId, b, accountKey)
        }

        /**
         * 잔고 보유수량으로 이 주문의 체결분 배정 — 같은 종목 다른 주문이 이미 설명한 수량은 뺀다.
         * 기록 순보유·매도는 잔고 조회 뒤(락 안)에 읽는다 — 그 사이 기록된 체결은 배정 가능 수량을 줄이는 쪽으로만 들어간다.
         *
         * 매도는 체결을 추정하지 않는다(잔고에 매도 단가가 없고 원본도 매수만 추정). 잔고 보유수량(없으면 0)이 장부 순보유와 같을 때만
         * "이 종목에 기록되지 않은 체결이 없다"([BalanceResult.NONE] — 종결 근거), 다르면 HELD.
         * 알려진 구멍: 같은 종목에 기록되지 않은 매수와 매도가 같은 수량으로 있으면 잔고 == 장부라 구분하지 못한다
         * (매수 경로도 같은 구멍 — 매수 뒤 사람이 앱에서 매도하면 "배정할 수량 없음"으로 보인다).
         */
        private fun balanceCrossCheck(order: Order, snapshot: BalanceSnapshot?, target: Int, already: Int): BalanceResult {
            val accountKey = order.brokerAccountKey ?: return BalanceResult.HELD
            if (snapshot == null) return BalanceResult.HELD
            val holdings = snapshot.holdings ?: return BalanceResult.UNDETERMINED.also { notes += "잔고 조회가 실패했습니다" }
            val position = holdings[order.symbol]
            if (position == null && order.side == "buy") return BalanceResult.NONE
            val recorded = recordedHoldings(order.userId, order.symbol, accountKey)
            if (recorded.net.signum() < 0) {
                log.warn("기록 순보유 수량 음수로 잔고 정합 보류 user={} symbol={} net={}", order.userId, order.symbol, recorded.net)
                notes += "장부 순보유가 음수(${grouped(recorded.net)}주)라 잔고와 대조하지 않았습니다"
                return BalanceResult.HELD
            }
            // 잔고를 읽은 뒤 반영된 매도를 빼면 이전 잔고의 주식이 새 매수처럼 보일 수 있다.
            if (recorded.sold.compareTo(snapshot.recordedSales[order.symbol] ?: BigDecimal.ZERO) != 0) {
                notes += "잔고 조회 중 매도 기록이 바뀌어 잔고와 대조하지 않았습니다"
                return BalanceResult.HELD
            }
            val held = position?.first ?: 0
            if (order.side == "sell") {
                val diff = BigDecimal(held).compareTo(recorded.net)
                if (diff < 0) notes += "잔고 ${grouped(held)}주가 장부 ${grouped(recorded.net)}주보다 적습니다 — 기록되지 않은 매도 체결일 수 있습니다"
                if (diff > 0) notes += "잔고 ${grouped(held)}주가 장부 ${grouped(recorded.net)}주보다 많습니다"
                return if (diff == 0) BalanceResult.NONE else BalanceResult.HELD
            }
            val available = BigDecimal(held) - recorded.net
            if (available.signum() <= 0) return BalanceResult.NONE
            val delta = minOf(target - already, available.toInt())
            if (delta <= 0) return BalanceResult.HELD
            val price = position?.second?.takeIf { it.signum() > 0 } ?: unknownPrice(order)
                ?: return BalanceResult.UNDETERMINED.also { notes += "잔고에 장부로 설명되지 않는 ${grouped(available)}주가 있으나 단가를 알 수 없습니다" }
            return if (apply(order, delta, price, already + delta >= target, already, fromBalance = true)) BalanceResult.ASSIGNED else BalanceResult.HELD
        }

        /** 평균가가 없을 때의 주문가 폴백. 주문가도 없으면 단가를 모르는 체결이라 기록하지 않고 errors 로 센다(null 반환). */
        private fun unknownPrice(order: Order): BigDecimal? {
            val price = order.price
            if (price == null) {
                log.warn("체결 단가를 알 수 없어 정합 보류(평균가·주문가 없음) order={}", order.id)
                count("errors")
            }
            return price
        }

        private fun alertUnconfirmed(order: Order) {
            log.warn("접수 불명 주문 미해소 — order={} {} {} {}주(주문번호 없음)", order.id, order.symbol, order.side, order.qty)
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.critical,
                "주문 접수 불명 상태 지속 — ${order.symbol} ${order.side} ${grouped(order.qty.toInt())}주(주문 #${order.id}). " +
                    "증권사 주문번호가 없어 체결조회로 확인할 수 없습니다. 증권사 계좌에서 실제 체결 여부를 직접 확인하세요(이중 주문 위험 때문에 엔진은 재주문하지 않습니다).",
                "order_unconfirmed", dedupWindowHours = UNCONFIRMED_ALERT_DEDUP_HOURS,
            )
        }

        private fun alertUnverifiedAccount(order: Order) {
            escalated = true
            log.warn("주문 계좌 미확인 또는 불일치로 정합 보류 order={} user={}", order.id, order.userId)
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.critical,
                "주문 #${order.id} (${order.symbol})의 제출 계좌를 현재 연결과 대조할 수 없어 체결 확인을 보류했습니다. 증권사에서 해당 주문을 직접 확인하세요.",
                "order_account_unverified", dedupWindowHours = UNCONFIRMED_ALERT_DEDUP_HOURS,
            )
        }

        private fun alertFillMismatch(order: Order, brokerFilled: Int, recorded: Int) {
            escalated = true
            log.warn("증권사 총 체결이 기록보다 적어 종결 보류 order={} broker={} recorded={}", order.id, brokerFilled, recorded)
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.critical,
                "주문 #${order.id} (${order.symbol} ${order.side})의 증권사 총 체결 ${grouped(brokerFilled)}주가 기록된 체결 ${grouped(recorded)}주보다 적습니다. " +
                    "기록을 줄이지 않고 종결을 보류했습니다 — 장부를 직접 확인하세요.",
                "order_fill_mismatch", dedupWindowHours = UNCONFIRMED_ALERT_DEDUP_HOURS,
            )
        }

        /** [evidence] = 종결 근거를 사실대로 말하는 구절(잔고 근거·목록 근거). */
        private fun alertClosedUnconfirmed(order: Order, recorded: Int, evidence: String) {
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.warning,
                "접수불명 주문 #${order.id} (${order.symbol} ${order.side} ${grouped(order.qty.toInt())}주)을 미체결로 종결했습니다 — " +
                    "주문일(${MarketClock.kstDate(order.createdAt)})이 지났고 $evidence(기록된 체결 ${grouped(recorded)}주는 그대로). 이 종목의 신규 주문 보류가 풀립니다.",
                "order_closed_unconfirmed",
            )
        }

        private fun alertModifiedExternally(order: Order, recorded: Int, rows: List<BrokerOrderRow>) {
            escalated = true
            val filled = rows.sumOf { it.filledQty }
            log.warn("정정 주문에 체결이 있어 자동 반영·종결 보류 order={} odno={} 정정={} 체결={}", order.id, order.kisOrderId, rows.map { it.orderNo }, filled)
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.critical,
                "주문 #${order.id} (${order.symbol} ${order.side}, 주문번호 ${order.kisOrderId})을 원주문으로 하는 정정·취소 주문 " +
                    "${rows.joinToString { "${it.orderNo}(체결 ${grouped(it.filledQty)}주)" }}이 증권사 주문 목록에 있고, 그 체결 합은 ${grouped(filled)}주입니다. " +
                    "엔진 장부에 자동 반영하지 않았고(기록된 체결 ${grouped(recorded)}주) 종결도 보류했습니다 — 증권사에서 직접 확인하세요.",
                "order_modified_externally", dedupWindowHours = UNCONFIRMED_ALERT_DEDUP_HOURS,
            )
        }

        private fun alertUnresolved(order: Order, recorded: Int, facts: List<String>) {
            log.warn("주문일이 지난 주문이 재확인에도 미해결 order={} facts={}", order.id, facts)
            val seen = if (facts.isEmpty()) "종결 근거를 얻지 못했습니다" else facts.joinToString("; ")
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.critical,
                "주문 #${order.id} (${order.symbol} ${order.side} ${grouped(order.qty.toInt())}주, 주문일 ${MarketClock.kstDate(order.createdAt)})이 " +
                    "주문일이 지났는데도 종결되지 않았습니다(기록된 체결 ${grouped(recorded)}주). 이번 확인에서 관측한 것: $seen. " +
                    "증권사에서 직접 확인한 뒤 수동 종결이 필요합니다.",
                "order_unresolved", dedupWindowHours = UNRESOLVED_ALERT_DEDUP_HOURS,
            )
        }

        private fun alertOrderNumberRecovered(order: Order, orderNo: String) {
            alerts.publish(
                order.userId, order.strategyId ?: 0, AlertSeverity.warning,
                "접수불명이던 주문 #${order.id} (${order.symbol} ${order.side} ${grouped(order.qty.toInt())}주)의 주문번호($orderNo)를 증권사 주문 목록에서 찾아 붙였습니다. " +
                    "이후 체결은 주문번호로 정합합니다.",
                "order_number_recovered",
            )
        }

        /** 락 안: 종결([closeIfUnchanged]) — 했으면 진전으로 센다. */
        private fun close(order: Order, recorded: Int): Boolean {
            val closed = closeIfUnchanged(order, recorded)
            if (closed) {
                progressed = true
                log.info("주문 종결(주문일 경과·체결 확정) order={} {} {} 기록 {}/{}주", order.id, order.symbol, order.side, recorded, order.qty.toInt())
            }
            return closed
        }

        /**
         * 락 안: 잔고 근거 종결. 매도는 잔고 조회 뒤 계좌가 바뀌지 않았는지를 매수 기록([apply])과 같은 사용자 행 잠금 아래에서 다시 확인하고 종결한다.
         * 매수 종결은 기존 그대로(재확인 없음).
         */
        private fun closeOnBalance(order: Order, recorded: Int): Boolean =
            if (order.side != "sell") close(order, recorded) else tx.execute { paperBalanceAllowed(order, lock = true) && close(order, recorded) } == true

        /**
         * 목록에서 [orderNo] 의 후손 행 — 원주문번호를 따라가면 [orderNo] 에 닿는 정정·취소 행(정정의 정정 포함), 목록 순서대로.
         * 문자열 일치로만 잇는다(0 떼기 등 정규화 없음). KIS 모의는 orgn_odno 를 원주문 odno 와 같은 10자리 표기로 준다(2026-11-16 실측) —
         * 표기가 다른 응답이 오면 사슬이 이어지지 않아 후손이 없는 것으로 보이고 원주문 행만으로 판단한다(이 확인을 넣기 전과 같은 동작, 그게 한계).
         * 각 행은 한 번만 담으므로 순환(잘못된 데이터)에도 끝난다.
         */
        private fun descendants(rows: List<BrokerOrderRow>, orderNo: String): List<BrokerOrderRow> {
            val reached = hashSetOf(orderNo)
            val taken = BooleanArray(rows.size)
            do {
                var grew = false
                rows.forEachIndexed { i, r ->
                    if (!taken[i] && r.originalOrderNo?.let { it in reached } == true) {
                        taken[i] = true
                        reached += r.orderNo
                        grew = true
                    }
                }
            } while (grew)
            return rows.filterIndexed { i, _ -> taken[i] }
        }

        /**
         * 락 안: 접수불명 주문이 주문일 목록([rows], 완결)에 없다고 볼 수 있는가. 둘 다일 때만 참:
         * - 증인: 같은 사용자·계좌·주문일의 다른 주문에 붙은 번호의 원주문 행이 있다 — 목록이 이 계좌의 주문을 실제로 돌려준다
         *   (빈 목록·증인 없는 목록은 "주문 없음"의 근거가 아니다 — 원본은 모의투자에서 이 조회가 늘 빈 응답이라고 관찰했다. 2026-11-16 모의 실측에서는 행이 왔다).
         * - 같은 종목·방향의 주인 없는 행(번호도, 있으면 원주문번호도 어떤 주문에도 안 붙음)이 하나도 없다. 수량·시각은 보지 않는다 —
         *   사람이 정정했거나 회수 창을 벗어난 이 주문일 수 있다.
         * 주문에 붙은 번호는 늘기만 한다(번호를 지우거나 계좌 키를 바꾸는 경로가 없다 — 계좌 키는 null 에서 채우기만). 그래서 이 조회와 종결 update
         * 사이에 다른 주문에 번호가 붙으면 주인 없는 행이 줄 뿐이고, 여기서 본 "주인 없는 행"은 종결을 막는 쪽으로만 낡는다.
         */
        private fun listShowsNoSuchOrder(order: Order, rows: List<BrokerOrderRow>): Boolean {
            val attached = jdbc.queryForList(
                """select kis_order_id from orders where user_id = :u and broker_account_key = :a and id <> :id and coalesce(trim(kis_order_id), '') <> ''
                   and created_at >= :dayStart and created_at < :dayEnd""",
                mapOf("u" to order.userId, "a" to order.brokerAccountKey, "id" to order.id) + dayBounds(MarketClock.kstDate(order.createdAt)), String::class.java,
            ).toHashSet()
            val witnessed = rows.any { it.originalOrderNo == null && it.orderNo in attached }
            val ownerless = rows.any { r ->
                r.symbol == order.symbol && r.side == order.side && r.orderNo !in attached && r.originalOrderNo?.let { it in attached } != true
            }
            if (witnessed && ownerless) log.info("주문 목록에 같은 종목·방향의 주인 없는 행이 있어 종결 보류 order={}", order.id)
            return witnessed && !ownerless
        }

        /** 주문일(KST) 하루의 [시작, 끝) 경계 쿼리 인자 — 주문번호는 날짜가 다르면 다시 쓰일 수 있다. */
        private fun dayBounds(day: LocalDate) = mapOf(
            "dayStart" to Timestamp.from(day.atStartOfDay(MarketClock.KST).toInstant()),
            "dayEnd" to Timestamp.from(day.plusDays(1).atStartOfDay(MarketClock.KST).toInstant()),
        )

        /** 락 안: 주문번호 회수. 여전히 번호가 없고 그 번호가 같은 사용자·계좌의 같은 주문일 다른 주문에 없을 때만 한 문장으로 붙인다. 체결 반영은 다음 pass 의 주문번호 경로. */
        private fun attachOrderNumber(order: Order, orderNo: String): (() -> Unit)? {
            val attached = jdbc.update(
                """update orders set kis_order_id = :no, status = case when status = 'pending' then 'submitted' else status end
                   where id = :id and closed_at is null and coalesce(trim(kis_order_id), '') = '' and broker_account_key = :a
                     and status in ('pending', 'submitted', 'partial') and status = :old
                     and not exists (select 1 from orders o where o.user_id = :u and o.broker_account_key = :a and o.kis_order_id = :no and o.id <> :id
                                      and o.created_at >= :dayStart and o.created_at < :dayEnd)""",
                mapOf("no" to orderNo, "id" to order.id, "a" to order.brokerAccountKey, "old" to order.status.name, "u" to order.userId) +
                    dayBounds(MarketClock.kstDate(order.createdAt)),
            ) == 1
            if (!attached) {
                log.info("주문번호 회수 보류(조회 중 주문이 바뀜) order={} odno={}", order.id, orderNo)
                return null
            }
            log.info("접수불명 주문에 주문번호 회수 order={} odno={}", order.id, orderNo)
            progressed = true
            return { alertOrderNumberRecovered(order, orderNo) }
        }

        /**
         * 증권사 근거(체결조회·잔고)를 락 밖에서 얻고, 락 안에서는 주문 행·기록 수량을 다시 읽어 DB 반영만 한다.
         * 락을 쥔 채 증권사를 기다리면 그 사이 도착한 체결통보가 트리거한 정합이 락을 못 잡아 반영을 다음 주기로 미룬다.
         */
        fun processOne(id: Int) {
            progressed = false
            escalated = false
            notes.clear()
            val seen = orders.findById(id).orElse(null)
            if (seen == null) {
                nextCheck.remove(id)
                return
            }
            val b = seen.brokerAccountKey?.let { broker(seen.userId) }
            val verified = seen.brokerAccountKey != null && (b == null || b.orderAccountKey == seen.brokerAccountKey)
            val odno = seen.kisOrderId
            val orderDay = MarketClock.kstDate(seen.createdAt)
            // 근거를 얻기 전에 판정 — 종결에는 주문일이 끝난 뒤(더 체결될 수 없을 때) 얻은 근거만 쓴다.
            val pastDay = orderDay.isBefore(MarketClock.todayKst(clock))
            var kisFilled = -1
            var avg: BigDecimal? = null
            var terminal = false
            var snapshot: BalanceSnapshot? = null
            var row: BrokerOrderRow? = null
            var rowAmbiguous = false
            var candidates = emptyList<String>()
            var modified = emptyList<BrokerOrderRow>()
            var dayList: List<BrokerOrderRow>? = null
            if (verified && b != null) {
                if (!odno.isNullOrEmpty()) {
                    try {
                        val info = b.getOrderExecution(odno, seen.symbol, orderDay)
                        kisFilled = info.filledQty
                        avg = info.avgPrice
                        terminal = info.terminal // 조회가 성공했을 때만 의미가 있다
                        notes += "체결조회 누적 ${grouped(kisFilled)}주${if (terminal) "(증권사 종료 상태)" else ""}"
                    } catch (e: BrokerError) {
                        log.debug("체결 재조회 실패 order={}: {}", seen.id, e.message)
                        count("errors")
                        notes += "체결조회가 실패했습니다"
                    }
                    if (pastDay && kisFilled < seen.qty.toInt()) {
                        val list = orderList(seen.userId, b, orderDay)
                        // 정정·취소 행(원주문번호 있음)은 이 주문번호의 행이 아니다.
                        val rows = list?.filter { it.orderNo == odno && it.originalOrderNo == null }.orEmpty()
                        if (rows.size == 1 && rows[0].symbol == seen.symbol && rows[0].side == seen.side) {
                            row = rows[0]
                        } else if (rows.isNotEmpty()) {
                            log.warn("주문 목록의 같은 주문번호 행이 {}건이거나 종목·방향이 달라 판단 보류 order={} odno={}", rows.size, seen.id, odno)
                            rowAmbiguous = true
                            count("errors")
                            notes += "주문 목록의 같은 주문번호 행이 ${rows.size}건이거나 종목·방향이 다릅니다"
                        } else if (list != null) {
                            notes += "증권사 주문 목록에 이 주문번호의 행이 없습니다"
                        } else {
                            notes += if ((seen.userId to orderDay) in failedLists) "주문 목록 조회가 실패했습니다" else "증권사가 주문 목록 조회를 지원하지 않습니다"
                        }
                        // 사람이 앱에서 정정하면 그 뒤 체결은 정정 주문번호의 행에 잡힌다 — 원주문 행만 보면 체결 누락을 종결로 확정한다.
                        val descendants = list?.let { descendants(it, odno) }.orEmpty()
                        if (descendants.sumOf { it.filledQty } > 0) modified = descendants
                        // 목록 행의 총 체결은 체결조회와 같은 누적 근거 — 더 큰 쪽으로 증분 반영한다.
                        row?.takeIf { it.filledQty > kisFilled }?.let { kisFilled = it.filledQty; avg = it.avgPrice }
                    }
                } else {
                    candidates = attachCandidates(seen, b, orderDay)
                    if (pastDay) dayList = orderList(seen.userId, b, orderDay) // attachCandidates 가 캐시한 것 — 추가 조회 없음
                }
                // 붙일 번호·목록 행(판단 보류 포함)·정정 체결·종료 상태가 있으면 잔고를 보지 않는다 — 증권사가 이 주문의 체결을 직접 말하고 있다.
                if (kisFilled <= 0 && candidates.size != 1 && row == null && !rowAmbiguous && modified.isEmpty() && !terminal) {
                    snapshot = balanceEvidence(seen, b, pastDay)
                }
            } else if (verified) {
                notes += "증권사 클라이언트를 만들지 못했습니다"
            }
            // 알림(critical 은 텔레그램 HTTP)은 락을 놓은 뒤에 낸다.
            var lockBusy = false
            val alert = redis.withLockOrElse(RedisKeys.reconcileLock(id), Duration.ofSeconds(RedisKeys.RECONCILE_LOCK_TTL_SECONDS), busy = { lockBusy = true; null }) {
                val order = orders.findById(id).orElse(null)
                if (order == null || order.closedAt != null) {
                    progressed = true // 다른 경로가 종결했다
                    return@withLockOrElse null
                }
                count("checked")
                if (!verified) return@withLockOrElse { alertUnverifiedAccount(order) }
                if (b == null) return@withLockOrElse null
                // 근거는 이 주문번호·계좌로 얻은 것이다 — 그 사이 바뀌었으면 이번 pass 는 건너뛰고 다음 정합에 맡긴다.
                if (order.kisOrderId != odno || order.brokerAccountKey != seen.brokerAccountKey) {
                    log.info("정합 조회 중 주문 식별 정보가 바뀌어 이번 정합 보류 order={}", id)
                    progressed = true // 바뀐 주문은 다음 pass 에 바로 다시 본다
                    return@withLockOrElse null
                }
                // 후보가 정확히 하나일 때만 붙이고 그 pass 는 거기서 끝(잔고 교차확인을 같은 pass 에 돌리지 않는다).
                if (candidates.size == 1) return@withLockOrElse attachOrderNumber(order, candidates.single())
                applyEvidence(order, seen.status, kisFilled, avg, terminal, snapshot, pastDay, row, rowAmbiguous, candidates.size, modified, dayList)
            }
            alert?.invoke()
            // 재확인 주기: 오늘 주문은 매 pass, 락이 바빴던 pass 는 진전 없음으로 치지 않는다(다음 pass 에 바로 다시).
            if (lockBusy || !pastDay) return
            if (progressed) {
                nextCheck.remove(id)
                return
            }
            val recheck = nextCheck.put(id, clock.instant().plusSeconds(PAST_DAY_RECHECK_SECONDS)) != null
            // 주문번호 없는 주문은 기존 order_unconfirmed 흐름(락 안에서 냈다), 더 구체적인 critical 을 냈으면 겹쳐 내지 않는다.
            if (recheck && !odno.isNullOrEmpty() && !escalated) alertUnresolved(seen, fills.recordedQty(id), notes.toList())
        }

        /**
         * 락 안: 락 밖에서 얻은 근거를 지금 기록된 수량 대비 증분으로 반영하고, 주문일이 지난 주문은 확실한 근거가 있을 때만 종결한다. 내야 할 알림을 돌려준다.
         * [row] 는 주문일 목록의 이 주문번호 행(종목·방향 일치, 정확히 하나), [candidates] 는 접수불명 주문의 목록 후보 수.
         * [terminal] 은 체결조회가 종료 상태로 보고한 누적([kisFilled])이 최종이라는 뜻(토스), [modified] 는 체결이 있는 정정·취소 후손 행들,
         * [dayList] 는 주문일이 지난 접수불명 주문의 완결된 주문일 목록(미지원·조회 실패면 null).
         */
        private fun applyEvidence(
            order: Order, seenStatus: OrderStatus, kisFilled: Int, avg: BigDecimal?, terminal: Boolean, snapshot: BalanceSnapshot?,
            pastDay: Boolean, row: BrokerOrderRow?, rowAmbiguous: Boolean, candidates: Int,
            modified: List<BrokerOrderRow>, dayList: List<BrokerOrderRow>?,
        ): (() -> Unit)? {
            val target = order.qty.toInt()
            val already = fills.recordedQty(order.id!!)
            if (order.kisOrderId.isNullOrEmpty()) {
                val result = balanceCrossCheck(order, snapshot, target, already)
                // 잔고가 더 배정할 수량이 없다고 확정했을 때만 종결. 목록에 같은 조건의 주문이 여럿이면 접수됐을 수 있어 종결하지 않는다.
                if (result == BalanceResult.NONE && pastDay && candidates < 2 && closeOnBalance(order, already)) {
                    val evidence = if (order.side == "sell") "잔고 보유수량이 장부 순보유와 같습니다" else "잔고에 미기록 체결이 없습니다"
                    return { alertClosedUnconfirmed(order, already, evidence) }
                }
                // 목록 근거: 잔고가 체결을 배정했거나(그 pass 는 종결하지 않는다) 잔고가 판단 불가(조회 실패·단가 불명 — 설명되지 않는 보유일 수 있다)면 쓰지 않는다.
                if (pastDay && dayList != null && result != BalanceResult.ASSIGNED && result != BalanceResult.UNDETERMINED &&
                    listShowsNoSuchOrder(order, dayList) && close(order, already)
                ) {
                    return { alertClosedUnconfirmed(order, already, "증권사 주문 목록에 이 주문이 없습니다") }
                }
                // 판단 불가·배정은 알리지 않는다
                return if (result == BalanceResult.NONE || result == BalanceResult.HELD) ({ alertUnconfirmed(order) }) else null
            }
            // 다른 주문번호(정정)에 이 주문의 체결이 있다 — 장부를 자동으로 고치지 않는다(원주문번호의 근거도 이번엔 반영하지 않는다). 종결 없이 사람에게.
            if (modified.isNotEmpty()) {
                count("errors")
                return { alertModifiedExternally(order, already, modified) }
            }
            var balance: BalanceResult? = null
            if (kisFilled > 0) {
                val delta = kisFilled - already
                val fully = kisFilled >= target
                if (delta > 0) {
                    val cumulativeAverage = avg?.takeIf { it.signum() > 0 }
                    val price = if (cumulativeAverage != null) {
                        val incrementalPrice = incrementalPrice(order.id!!, kisFilled, cumulativeAverage, delta)
                        if (incrementalPrice.signum() <= 0) {
                            log.warn("누적 체결금액 불일치 — order={} filled={} average={}", order.id, kisFilled, cumulativeAverage)
                            count("errors")
                            return null
                        }
                        incrementalPrice
                    } else {
                        unknownPrice(order) ?: return null
                    }
                    apply(order, delta, price, fully, already)
                } else {
                    val status = if (fully) OrderStatus.filled else OrderStatus.partial
                    if (order.status != status) {
                        // 근거는 조회 전 상태(seenStatus)에서 얻은 것이다 — 그 뒤 체결통보가 만든 filled 를 낡은 누적으로 되돌리지 않게,
                        // 조회 전 상태 그대로일 때만 바꾼다(락 만료 뒤 경합도 이 조건이 막는다).
                        val changed = jdbc.update(
                            "update orders set status = :new where id = :id and status = :old",
                            mapOf("new" to status.name, "id" to order.id, "old" to seenStatus.name),
                        )
                        if (changed == 1) {
                            order.status = status // 아래 종결의 "읽은 상태" 조건이 지금 DB 상태를 보게
                            progressed = true
                        }
                    }
                }
            } else {
                balance = balanceCrossCheck(order, snapshot, target, already)
            }
            if (!pastDay || rowAmbiguous) return null
            val recorded = fills.recordedQty(order.id!!)
            if (row != null) {
                // 목록 행이 이 주문의 최종 누적이다: 기록과 같으면 종결, 기록보다 적으면 기록을 줄이지 않고 사람에게.
                if (row.filledQty < recorded) {
                    count("errors")
                    return { alertFillMismatch(order, row.filledQty, recorded) }
                }
                if (row.filledQty == recorded) close(order, recorded)
                return null
            }
            if (terminal) {
                // 증권사가 종료 상태로 보고한 누적이 최종(0 포함): 기록과 같으면 종결, 기록보다 적으면 기록을 줄이지 않고 사람에게.
                if (kisFilled < recorded) {
                    count("errors")
                    return { alertFillMismatch(order, kisFilled, recorded) }
                }
                if (kisFilled == recorded) close(order, recorded)
                return null
            }
            // 주문일이 지난 뒤 받은 체결조회의 0 아닌 누적이 다 기록됐거나(0 은 "행 없음"과 구분되지 않아 근거가 아니다),
            // 모의 잔고가 더 배정할 수량이 없다(매수)·잔고 == 장부 순보유(매도)로 확정했으면 종결.
            if (kisFilled > 0 && recorded == kisFilled) close(order, recorded) else if (balance == BalanceResult.NONE) closeOnBalance(order, recorded)
            return null
        }
    }

    private fun paperBalanceAllowed(order: Order, lock: Boolean = false): Boolean {
        val accountKey = order.brokerAccountKey ?: return false
        val rowLock = if (lock) " for share" else ""
        val allowed = jdbc.queryForList(
            "select not kis_balance_reconcile_blocked and coalesce(nullif(broker, ''), 'kis') = 'kis' from users where id = :u$rowLock",
            mapOf("u" to order.userId), Boolean::class.java,
        ).singleOrNull() == true
        // 기존 보유분과 체결을 구분할 수 없는 계좌 변경·계좌 미상 이력은 조회/통보로만 정합한다.
        // 잠금 경로에서는 앞의 사용자 행 잠금이 잡힌 다음 별도 문장으로 최신 주문을 읽는다.
        return allowed && jdbc.queryForObject(
            "select not exists(select 1 from orders where user_id = :u and broker_account_key is distinct from :a)",
            mapOf("u" to order.userId, "a" to accountKey), Boolean::class.java,
        ) == true
    }

    /**
     * 락 안: 종결 — 더 체결될 수 없고 기록된 체결([recorded])이 최종. executions·포지션·qty 는 건드리지 않는다(체결 0 이면 cancelled, 아니면 partial).
     * 락 안에서 읽은 상태·기록 수량 그대로일 때만 바꾸는 한 문장 — 락 만료 뒤 다른 경로가 기록했으면 종결하지 않는다. 자동 정합·수동 정리가 같이 쓴다.
     */
    private fun closeIfUnchanged(order: Order, recorded: Int): Boolean {
        if (recorded >= order.qty.toInt()) return false
        return jdbc.update(
            """update orders set status = :new, closed_at = :t
               where id = :id and closed_at is null and status in ('pending', 'submitted', 'partial') and status = :old
                 and (select coalesce(sum(filled_qty), 0) from executions where order_id = :id) = :q""",
            mapOf(
                "new" to (if (recorded == 0) OrderStatus.cancelled else OrderStatus.partial).name, "t" to Timestamp.from(clock.instant()),
                "id" to order.id, "old" to order.status.name, "q" to recorded,
            ),
        ) == 1
    }

    /**
     * 누적 평균가 × 누적 수량에서 이 주문의 기기록 금액을 뺀 증분([delta]주)의 단가 — executions.filled_price(numeric(18,4)) 정밀도.
     * 0 이하일 수 있고 그 판단(보류·거부)은 호출자가 한다. 자동 정합·수동 정리가 같이 쓴다.
     */
    private fun incrementalPrice(orderId: Int, cumulativeQty: Int, cumulativeAverage: BigDecimal, delta: Int): BigDecimal =
        (cumulativeAverage.multiply(BigDecimal(cumulativeQty)) - orderRecordedNotional(orderId)).divide(BigDecimal(delta), 4, RoundingMode.HALF_UP)

    private fun orderRecordedNotional(orderId: Int): BigDecimal = jdbc.queryForObject(
        "select coalesce(sum(filled_qty * filled_price), 0) from executions where order_id = :id",
        MapSqlParameterSource("id", orderId), BigDecimal::class.java,
    )!!

    private fun recordedHoldings(userId: Int, symbol: String, accountKey: String): RecordedHoldings = jdbc.queryForObject(
        """select coalesce(sum(case when o.side = 'sell' then -e.filled_qty else e.filled_qty end), 0) as net,
                  coalesce(sum(e.filled_qty) filter (where o.side = 'sell'), 0) as sold
           from executions e join orders o on e.order_id = o.id
           where o.user_id = :u and o.symbol = :s and o.broker_account_key = :a""",
        mapOf("u" to userId, "s" to symbol, "a" to accountKey),
    ) { row, _ -> RecordedHoldings(row.getBigDecimal("net"), row.getBigDecimal("sold")) }

    private fun salesBySymbol(userId: Int, accountKey: String): Map<String, BigDecimal> = jdbc.query(
        """select o.symbol, sum(e.filled_qty) as sold from executions e join orders o on e.order_id = o.id
           where o.user_id = :u and o.broker_account_key = :a and o.side = 'sell' group by o.symbol""",
        mapOf("u" to userId, "a" to accountKey),
    ) { row, _ -> row.getString("symbol") to row.getBigDecimal("sold") }.toMap()

    private companion object {
        const val PENDING_GRACE_SECONDS = 300L
        const val UNCONFIRMED_ALERT_DEDUP_HOURS = 6.0

        /**
         * 주문일이 지났는데 한 pass 에서 진전이 없던 주문의 다음 확인까지 간격(초). 측정값이 아니다 — 주문일이 지난 뒤의 증권사 근거는
         * 더 바뀌지 않고 재시도는 일시 장애만 넘기면 된다는 판단. 짧으면 증권사 조회(유량제한 공유)가 늘고, 길면 장애 뒤 종결·알림이 늦어진다.
         */
        const val PAST_DAY_RECHECK_SECONDS = 3600L

        /** `order_unresolved` dedup(시간). 측정값이 아니다 — 미해결 주문을 하루 한 번 다시 알린다. */
        const val UNRESOLVED_ALERT_DEDUP_HOURS = 24.0

        /**
         * 접수불명 주문에 목록 행을 붙일 때 허용하는 (증권사 주문 시각 − 주문 행 생성 시각) 범위(초). 주문 행은 POST 전에 만들어지므로 앞쪽은
         * 초 단위로 잘린 증권사 시각과 서버 시계 차이만, 뒤쪽은 재시도를 포함한 POST 의 코드 상한(주문 락 TTL 의 HTTP 상한 합)보다 넉넉하게 잡았다.
         * 측정값이 아니다 — 창을 넓히면 다른 주문을 붙일 수 있고, 좁히면 회수하지 못할 뿐(기존 동작).
         */
        const val ATTACH_BEFORE_SECONDS = 2L
        const val ATTACH_AFTER_SECONDS = 120L
    }
}
