package com.quantfolio.engine

import com.quantfolio.broker.BrokerAccountKey
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import java.sql.Timestamp
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * 체결통보 1건 처리 — 그 주문의 정합을 지금 트리거한다(원본 `apply_fill_notice` 는 통보 수량을 장부에 직접 쓴다 — docs/migration-notes.md).
 *
 * 통보의 CNTG_QTY 는 체결 건별이고(2026-11-16 모의 실측) 체결 고유번호가 없다. 그래서 통보 수량을 장부에 더하면 정합(REST 누적)이 이미 반영한
 * 체결의 늦은 통보를 가를 키가 없어 이중 기록이 되고, 누적으로 보고 빼면 두 번째 체결부터 누락된다. 통보는 "이 주문을 지금 정합하라"는 신호로만 쓰고,
 * 수량·단가는 [Reconciler.reconcileOrder] 가 단건 체결조회 누적에서 읽어 기기록 대비 증분만 기록한다(주기 정합과 같은 경로·같은 주문 락).
 * 통보 직후의 조회가 그 체결을 아직 포함하지 않으면 이번엔 기록되지 않고 다음 주기 정합(`EngineRuntime` 60초 루프)이 잡는다 — 조회 지연은 측정하지 못했다.
 *
 * 매칭: 오늘(KST)·사용자·통보 계좌 키·주문번호·종목. 주문번호 없는 주문(접수불명)·계좌 불일치·오늘이 아닌 주문은 매칭하지 않는다.
 * 수신 루프는 이 처리(대기·증권사 조회)를 직접 하지 않고 리스너마다 하나인 [Worker] 에 넘긴다 — 처리 중에도 다음 프레임·PINGPONG 을 바로 처리한다.
 */
@Component
class FillNoticeApplier(
    private val reconciler: Reconciler,
    private val jdbc: NamedParameterJdbcTemplate,
    private val props: QuantfolioProperties,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger("engine.fill_notice")

    private companion object {
        /**
         * 전송 중 주문의 주문번호 커밋을 기다릴 때의 재시도 횟수·간격(ms). 대기 상한 = 횟수 × 간격 — 그 동안 [Worker] 가 멈춰 같은 리스너의 다른 통보도 기다린다. 측정값 아님.
         */
        const val INFLIGHT_RETRIES = 20
        const val INFLIGHT_RETRY_MS = 250L

        /** [Worker] 하나에 대기시킬 수 있는 주문(합치기 키) 수 상한 — 차면 새 키의 통보를 버린다. 측정값 아님. */
        const val MAX_WAITING_NOTICES = 1000
    }

    /**
     * 체결통보 리스너 하나의 처리 스레드(가상 스레드 1개) — 수신 루프는 [offer] 로 넘기기만 하고, 매칭·정합([apply])은 여기서 한 번에 하나씩 한다.
     *
     * 합치기: 키는 (통보 계좌, 주문번호, 종목) — 매칭 전이라 주문 id 를 모른다(사용자는 리스너 단위로 같다). 같은 키가 아직 대기 중(처리 시작 전)이면 새 통보는
     * 아무것도 추가하지 않고, 처리를 시작한 뒤 온 통보는 다시 대기시킨다 — 그 조회가 새 체결을 포함한다는 보장이 없어서다. 그래서 주문당 "실행 중 1 + 대기 1".
     * 스레드가 하나라 같은 리스너의 정합은 동시에 돌지 않고(정합 락이 바쁘면 `reconcileOrder` 는 그냥 넘긴다), 대기는 도착 순서라 한 주문의 연속 통보가
     * 다른 주문을 굶기지 않는다. 대기가 [MAX_WAITING_NOTICES] 키에 차면 새 키의 통보를 버린다(경고 로그).
     * 버린 통보와 [close] 때 남은 대기는 주기 정합(`EngineRuntime` 60초 루프)이 잡는다 — 통보는 정합 트리거일 뿐 장부에 쓰지 않는다.
     */
    inner class Worker(private val userId: Int) : AutoCloseable {
        private val queue = LinkedBlockingQueue<Triple<String?, String, String>>(MAX_WAITING_NOTICES)
        private val waiting = ConcurrentHashMap<Triple<String?, String, String>, FillNotice>()
        @Volatile private var closed = false
        private val thread = Thread.ofVirtual().name("fill-notice-apply-$userId").start { loop() }

        /** 수신 루프가 부른다 — 기다리지 않는다. */
        fun offer(n: FillNotice) {
            if (closed) return
            val key = Triple(n.accountNo, n.odno, n.symbol)
            if (waiting.putIfAbsent(key, n) != null) return // 처리 시작 전 대기가 있다 — 그 처리가 이 통보의 체결도 조회한다
            if (!queue.offer(key)) {
                waiting.remove(key)
                log.warn("체결통보 대기 상한({}건) — 통보 버림(주기 정합이 잡는다) user={} odno={}", MAX_WAITING_NOTICES, userId, n.odno)
            }
        }

        private fun loop() {
            while (!closed) {
                val key = try {
                    queue.take()
                } catch (e: InterruptedException) {
                    return
                }
                val n = waiting.remove(key) ?: continue // 지운 뒤에 온 같은 키의 통보는 다시 대기한다
                try {
                    this@FillNoticeApplier.apply(userId, n)
                } catch (e: InterruptedException) {
                    return
                } catch (e: Exception) {
                    if (Thread.currentThread().isInterrupted) return
                    log.error("체결통보 반영 실패 user={} odno={}", userId, n.odno, e)
                }
            }
        }

        /** 리스너 정지 — 처리 중인 정합을 인터럽트하고 스레드가 끝날 때까지 기다린다. 남은 대기는 버린다. */
        override fun close() {
            closed = true
            thread.interrupt()
            try {
                thread.join()
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt() // 기다림만 그만둔다 — 스레드는 closed·인터럽트로 끝난다
            }
            if (waiting.isNotEmpty()) log.info("체결통보 리스너 정지 — 대기 중 정합 요청 {}건 버림(주기 정합이 잡는다) user={}", waiting.size, userId)
        }
    }

    /** @return 주문에 매칭돼 정합을 트리거했으면 true(기록 여부와 무관 — 기록은 증권사 누적에 달렸다). */
    fun apply(userId: Int, n: FillNotice): Boolean {
        val accountKey = BrokerAccountKey.kisNotice(n.accountNo, props.kis.isPaperTrading) ?: return false
        val day = MarketClock.todayKst(clock)
        val start = Timestamp.from(MarketClock.kstStart(day))
        val end = Timestamp.from(day.plusDays(1).atStartOfDay(MarketClock.KST).toInstant())
        val p = mapOf("u" to userId, "a" to accountKey, "o" to n.odno, "s" to n.symbol, "side" to n.side, "start" to start, "end" to end)
        fun matchId() = jdbc.queryForList(
            """select id from orders where user_id = :u and broker_account_key = :a and kis_order_id = :o and symbol = :s
               and created_at >= :start and created_at < :end limit 2""",
            p, Int::class.java,
        ).singleOrNull()
        // 실행기는 주문번호 없는 pending 을 먼저 커밋하고 POST 응답 뒤 주문번호를 커밋한다 — 시장가는 그 사이에 통보가 올 수 있다.
        // 최근 생성분만: 접수불명으로 몇 시간 남은 주문 때문에 같은 종목·방향의 무관한 통보가 매번 기다리지 않게. 락 TTL 은 실행기의 이 구간 HTTP 상한에서 유도한 값이다.
        val recent = Timestamp.from(clock.instant().minusSeconds(RedisKeys.ORDER_LOCK_TTL_SECONDS))
        fun inFlight() = jdbc.queryForObject(
            """select count(*) from orders where user_id = :u and broker_account_key = :a and symbol = :s and side = :side
               and status = 'pending' and coalesce(trim(kis_order_id), '') = '' and created_at >= :start and created_at < :end
               and created_at >= :recent""",
            p + ("recent" to recent), Int::class.java,
        )!! > 0
        var id = matchId()
        var waits = 0
        while (id == null && waits < INFLIGHT_RETRIES && inFlight()) { // 전송 중 주문이 없으면(HTS 직접 주문 등) 기다리지 않는다
            Thread.sleep(INFLIGHT_RETRY_MS)
            waits++
            id = matchId()
        }
        if (id == null) {
            log.warn("체결통보 매칭 실패(주문 없음) user={} odno={} symbol={}", userId, n.odno, n.symbol)
            return false
        }
        val stats = reconciler.reconcileOrder(id)
        log.info("체결통보로 주문 정합 order={} odno={} 통보 건별수량={} 결과={}", id, n.odno, n.qty, stats)
        return true
    }
}
