package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerAccountKey
import com.quantfolio.broker.BrokerHttp
import com.quantfolio.common.MarketClock
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.sql.Timestamp
import java.time.Clock
import java.time.Duration
import java.util.Base64
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 복호화·파싱된 체결통보 1건. [qty] 는 이 체결 한 건의 수량(CNTG_QTY — 주문 누적이 아니다, 2026-11-16 모의 실측), [price] 는 그 체결 단가.
 * 둘 다 장부에 쓰지 않는다(로그용) — 반영은 [FillNoticeApplier] 가 트리거한 정합이 증권사 누적으로 한다. side 도 참고용(매칭에 쓰지 않는다).
 */
data class FillNotice(val odno: String, val symbol: String, val qty: Int, val price: BigDecimal, val side: String, val accountNo: String? = null) {
    override fun toString() = "FillNotice(odno=$odno, symbol=$symbol, qty=$qty, price=$price, side=$side)"
}

/**
 * KIS 실시간 프레임(원본 `kis_ws.py`·`fill_notice.py`). 데이터 프레임은 `암호화여부|tr_id|건수|필드^필드…`.
 * 체결통보 필드 인덱스는 KIS 공식 샘플 순서 — 2026-11-16 모의투자(H0STCNI9) 실프레임 23필드로 아래 인덱스를 확인했다. 실전(H0STCNI0) 프레임은 확인하지 못했다.
 */
object KisFrames {
    private val json = JsonMapper.builder().build()

    /** 구독/해지 메시지(원본 json.dumps 문자열). */
    fun subscribe(approvalKey: String, trId: String, trKey: String, subscribe: Boolean = true): String = pyJsonDumps(
        linkedMapOf(
            "header" to linkedMapOf("approval_key" to approvalKey, "custtype" to "P", "tr_type" to if (subscribe) "1" else "2", "content-type" to "utf-8"),
            "body" to linkedMapOf("input" to linkedMapOf("tr_id" to trId, "tr_key" to trKey)),
        ),
    )

    /** 실시간 접속용 approval_key(`/oauth2/Approval`). */
    fun approvalKey(http: BrokerHttp, baseUrl: String, appKey: String, appSecret: String): String {
        val r = http.send("POST", "$baseUrl/oauth2/Approval", json = json.writeValueAsString(linkedMapOf("grant_type" to "client_credentials", "appkey" to appKey, "secretkey" to appSecret)))
        if (r.status !in 200..299) throw IllegalStateException("approval_key 발급 실패: HTTP ${r.status}")
        // 원본 `resp.json()["approval_key"]` 은 없으면 KeyError — "null" 로 구독하면 실패가 성공처럼 보여 피드 장애 경보가 안 난다
        return json.readValue<Map<String, Any?>>(r.body)["approval_key"]?.toString() ?: throw IllegalStateException("approval_key 응답에 키 없음")
    }

    @Suppress("UNCHECKED_CAST")
    fun control(raw: String): Map<String, Any?>? = runCatching { json.readValue<Map<String, Any?>>(raw) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun isPingPong(raw: String) = (control(raw)?.get("header") as? Map<String, Any?>)?.get("tr_id") == "PINGPONG"

    /** 실시간 체결가(H0STCNT0) → (종목, 가격). 0 이하·형식 오류는 null. */
    fun parsePrice(raw: String): Pair<String, BigDecimal>? {
        val parts = raw.split("|")
        if (parts.size < 4 || parts[1] != "H0STCNT0") return null
        val f = parts[3].split("^")
        if (f.size < 3) return null
        val price = runCatching { BigDecimal(f[2].trim()) }.getOrNull() ?: return null
        return if (price.signum() > 0) f[0] to price else null
    }

    /** AES-256-CBC + PKCS7(Java 의 PKCS5Padding 이 블록 16 에선 같다). key/iv 는 구독 응답 문자열. */
    fun aesDecrypt(key: String, iv: String, cipherB64: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(c.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }

    /**
     * 체결통보 평문(^ 구분). 체결 통보(CNTG_YN(13)=2)이고 거부(RFUS_YN(12)=1)가 아닐 때만 체결이다 — 1 은 주문·정정·취소·거부 접수
     * 통보로 CNTG_QTY 가 주문수량이다(KIS 공식 샘플). 판별 불가(빈 값)도 체결로 보지 않는다. 체결수량 0 이어도 null.
     * 원본(`fill_notice.py:91-115`)은 13·12 를 보지 않고 수량 > 0 이면 체결로 쓴다.
     * 2026-11-16 모의 실측(23필드): 2=주문번호, 3=원주문번호(정정·취소 통보만), 9=CNTG_QTY(체결 건별 — 300주 시장가 매수 1,1,2,7,…, 합 = 총 체결),
     * 10=CNTG_UNPR, 12=RFUS_YN, 13=CNTG_YN(접수·정정·취소 1, 체결 2), 14=ACPT_YN, 16=ODER_QTY(부분 취소 뒤엔 줄어든 수량). 체결 고유번호는 없고,
     * 같은 초·같은 수량·단가의 별개 체결 통보가 연달아 온다 — 내용으로 중복과 구분할 수 없다.
     */
    fun parseFillNotice(plain: String): FillNotice? {
        val f = plain.split("^")
        if (f.size < 23) {
            LoggerFactory.getLogger("engine.fill_notice").warn("체결통보 필드 수 부족({}) — 무시", f.size)
            return null
        }
        // 모든 통보를 남긴다(고객ID·계좌·계좌명 제외) — 통보 수량은 장부에 쓰지 않으므로 체결 추적은 이 로그와 정합 로그로 한다.
        LoggerFactory.getLogger("engine.fill_notice").info(
            "체결통보 수신 odno={} CNTG_YN={} RFUS_YN={} ACPT_YN={} CNTG_QTY={} ODER_QTY={} CNTG_UNPR={} STCK_CNTG_HOUR={}",
            f[2].trim(), f[13].trim(), f[12].trim(), f[14].trim(), f[9].trim(), f[16].trim(), f[10].trim(), f[11].trim(),
        )
        if (f[13].trim() != "2" || f[12].trim() == "1") return null
        val odno = f[2].trim()
        val symbol = f[8].trim()
        if (odno.isEmpty() || symbol.isEmpty()) return null
        val qty = f[9].ifEmpty { "0" }.trim().toIntOrNull() ?: 0 // 원본 int()/Decimal() 은 앞뒤 공백을 받는다
        if (qty <= 0) return null
        val price = runCatching { BigDecimal(f[10].ifEmpty { "0" }.trim()) }.getOrDefault(BigDecimal.ZERO)
        return FillNotice(odno, symbol, qty, price, if (f[4] == "01") "sell" else "buy", f[1].trim().ifEmpty { null })
    }
}

/**
 * 체결통보 1건 처리 — 그 주문의 정합을 지금 트리거한다(원본 `apply_fill_notice` 는 통보 수량을 장부에 직접 쓴다 — docs/migration-notes.md).
 *
 * 통보의 CNTG_QTY 는 체결 건별이고(2026-11-16 모의 실측) 체결 고유번호가 없다. 그래서 통보 수량을 장부에 더하면 정합(REST 누적)이 이미 반영한
 * 체결의 늦은 통보를 가를 키가 없어 이중 기록이 되고, 누적으로 보고 빼면 두 번째 체결부터 누락된다. 통보는 "이 주문을 지금 정합하라"는 신호로만 쓰고,
 * 수량·단가는 [Reconciler.reconcileOrder] 가 단건 체결조회 누적에서 읽어 기기록 대비 증분만 기록한다(주기 정합과 같은 경로·같은 주문 락).
 * 통보 직후의 조회가 그 체결을 아직 포함하지 않으면 이번엔 기록되지 않고 다음 주기 정합(`EngineRuntime` 60초 루프)이 잡는다 — 조회 지연은 측정하지 못했다.
 *
 * 매칭: 오늘(KST)·사용자·통보 계좌 키·주문번호·종목. 주문번호 없는 주문(접수불명)·계좌 불일치·오늘이 아닌 주문은 매칭하지 않는다.
 * 통보 스레드(수신 루프)는 이 처리(대기·증권사 조회)가 끝날 때까지 다음 프레임·PINGPONG 을 처리하지 않는다.
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
         * 전송 중 주문의 주문번호 커밋을 기다릴 때의 재시도 횟수·간격(ms). 대기 상한 = 횟수 × 간격 — 통보를 받는 WS 스레드가 그 동안 멈춘다. 측정값 아님.
         */
        const val INFLIGHT_RETRIES = 20
        const val INFLIGHT_RETRY_MS = 250L
    }

    /** @return 주문에 매칭돼 정합을 트리거했으면 true(기록 여부와 무관 — 기록은 증권사 누적에 달렸다). */
    fun apply(userId: Int, n: FillNotice): Boolean {
        val accountKey = BrokerAccountKey.kisNotice(n.accountNo, props.kis.isPaperTrading) ?: return false
        val day = MarketClock.todayKst(clock)
        val start = Timestamp.from(day.atStartOfDay(MarketClock.KST).toInstant())
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

/** KIS WebSocket 한 연결 — 텍스트 프레임을 큐로 넘기고, 닫히면 null 을 넣어 수신 루프를 끝낸다. internal: 모의투자 확인 도구(test `KisProbe`)가 재사용한다. */
internal class KisSocket(url: String) : WebSocket.Listener {
    private val frames = LinkedBlockingQueue<Any>()
    private val buf = StringBuilder()
    val ws: WebSocket = HttpClient.newHttpClient().newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), this).join()

    override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
        buf.append(data)
        if (last) {
            frames.add(buf.toString())
            buf.setLength(0)
        }
        webSocket.request(1)
        return null
    }

    override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
        frames.add(CLOSED)
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        frames.add(error)
    }

    /** 다음 텍스트 프레임. 닫혔으면 null, 오류면 예외. stop 확인을 위해 1초마다 깨어난다. */
    fun next(stop: CountDownLatch): String? {
        while (stop.count > 0) {
            when (val f = frames.poll(1, TimeUnit.SECONDS)) {
                null -> continue
                CLOSED -> return null
                is Throwable -> throw f
                else -> return f as String
            }
        }
        return null
    }

    fun send(text: String) = ws.sendText(text, true).join()
    fun pong(text: String) = ws.sendPong(ByteBuffer.wrap(text.toByteArray())).join()
    fun close() = runCatching { ws.sendClose(WebSocket.NORMAL_CLOSURE, "").join() }

    private companion object {
        val CLOSED = Any()
    }
}

/**
 * 사용자별 KIS 실시간 연결 관리(원본 `price_feed.py`·`fill_notice.FillNoticeManager`). 각 연결은 가상 스레드 감독 루프 —
 * 끊기면 지수 백오프(5→120초)로 재연결하고, 시세 피드가 연속 3회 실패하면 1회 경보(REST 폴백으로 매매는 계속된다).
 */
@Component
class KisRealtime(
    private val http: BrokerHttp,
    private val redis: StringRedisTemplate,
    private val props: QuantfolioProperties,
    private val alerts: AlertPublisher,
    private val notices: FillNoticeApplier,
) {
    private val log = LoggerFactory.getLogger("engine.price_feed")

    private class Feed(val stop: CountDownLatch, val thread: Thread, val symbols: Set<String>)

    private val priceFeeds = ConcurrentHashMap<Int, Feed>()
    private val noticeFeeds = ConcurrentHashMap<Int, Feed>()
    private val failCounts = ConcurrentHashMap<Int, Int>()

    /** 실시간 시세용 approval_key. */
    fun approvalKey(appKey: String, appSecret: String): String = KisFrames.approvalKey(http, props.kis.baseUrl, appKey, appSecret)

    /** 사용자의 시세 피드를 원하는 종목 집합으로 맞춘다(같으면 무시, 다르면 재구독, 비면 정리). */
    // 제어 처리 스레드와 체결통보 재동기화 루프가 동시에 부른다 — 확인 후 생성(check-then-act)이 겹치면 추적 안 되는 WS 리스너가 남는다.
    @Synchronized
    fun ensurePrices(userId: Int, appKey: String, appSecret: String, symbols: Set<String>) {
        if (priceFeeds[userId]?.symbols == symbols) return
        removePrices(userId)
        if (symbols.isEmpty()) return
        val stop = CountDownLatch(1)
        val t = Thread.ofVirtual().name("price-feed-$userId").start {
            supervise(stop, onFailure = { e -> priceFailure(userId, e) }, onSuccess = { failCounts[userId] = 0 }) { runPrices(appKey, appSecret, symbols, stop) }
        }
        priceFeeds[userId] = Feed(stop, t, symbols.toSet())
        log.info("PriceFeed 시작 user={} symbols={}", userId, symbols.sorted())
    }

    @Synchronized
    fun removePrices(userId: Int) {
        failCounts.remove(userId)
        priceFeeds.remove(userId)?.let { it.stop.countDown(); it.thread.interrupt() }
    }

    private fun priceFailure(userId: Int, e: Exception) {
        val n = (failCounts[userId] ?: 0) + 1
        failCounts[userId] = n
        if (n == RedisKeys.FAILURE_ALERT_THRESHOLD) {
            alerts.publish(userId, 0, AlertSeverity.warning, "실시간 시세 WS 연속 ${n}회 재연결 실패 — REST 폴백으로 매매는 지속되나 시세 반영이 지연될 수 있음. 마지막 원인: ${e.message}", "price_feed_outage", 6.0)
        }
    }

    private fun runPrices(appKey: String, appSecret: String, symbols: Set<String>, stop: CountDownLatch) {
        val key = approvalKey(appKey, appSecret)
        val s = KisSocket(props.kis.wsUrl)
        try {
            symbols.forEach { s.send(KisFrames.subscribe(key, "H0STCNT0", it)) }
            log.info("KIS WS 구독: {} (env={})", symbols.sorted(), props.kis.env)
            while (stop.count > 0) {
                val raw = s.next(stop) ?: break
                if (raw.startsWith("{")) {
                    if (KisFrames.isPingPong(raw)) s.pong(raw)
                    continue
                }
                KisFrames.parsePrice(raw)?.let { (sym, price) -> redis.opsForValue().set(RedisKeys.price(sym), price.toPlainString(), Duration.ofSeconds(PRICE_TTL)) }
            }
        } finally {
            s.close()
        }
    }

    fun noticeUsers(): Set<Int> = noticeFeeds.keys.toSet()

    /** 체결통보 구독(계좌 전체 1구독). KIS_HTS_ID 미설정이면 no-op. */
    @Synchronized
    fun ensureNotices(userId: Int, appKey: String, appSecret: String) {
        val hts = props.kis.htsId
        if (hts.isNullOrEmpty()) {
            log.info("KIS_HTS_ID 미설정 — 체결통보 리스너 비활성(user={}). 실계정 전환 전 설정 필요.", userId)
            return
        }
        if (noticeFeeds.containsKey(userId)) return
        val stop = CountDownLatch(1)
        val t = Thread.ofVirtual().name("fill-notice-$userId").start {
            supervise(stop, onFailure = { e -> log.warn("체결통보 WS 오류 user={}: {}", userId, e.message) }) { runNotices(userId, appKey, appSecret, hts, stop) }
        }
        noticeFeeds[userId] = Feed(stop, t, emptySet())
        log.info("체결통보 리스너 시작 user={}", userId)
    }

    @Synchronized
    fun removeNotices(userId: Int) {
        noticeFeeds.remove(userId)?.let { it.stop.countDown(); it.thread.interrupt() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runNotices(userId: Int, appKey: String, appSecret: String, hts: String, stop: CountDownLatch) {
        val approval = approvalKey(appKey, appSecret)
        val trId = if (props.kis.isPaperTrading) "H0STCNI9" else "H0STCNI0"
        val s = KisSocket(props.kis.wsUrl)
        try {
            s.send(KisFrames.subscribe(approval, trId, hts))
            log.info("체결통보 구독 user={} tr_id={} (env={})", userId, trId, props.kis.env)
            var key: String? = null
            var iv: String? = null
            while (stop.count > 0) {
                val raw = s.next(stop) ?: break
                if (raw.startsWith("{")) {
                    val msg = KisFrames.control(raw) ?: continue
                    if ((msg["header"] as? Map<String, Any?>)?.get("tr_id") == "PINGPONG") {
                        s.pong(raw)
                        continue
                    }
                    val out = ((msg["body"] as? Map<String, Any?>)?.get("output") as? Map<String, Any?>).orEmpty()
                    if (!out["key"]?.toString().isNullOrEmpty() && !out["iv"]?.toString().isNullOrEmpty()) { // 원본 truthiness — 빈 값으로 기존 키를 덮지 않는다
                        key = out["key"].toString()
                        iv = out["iv"].toString()
                    }
                    continue
                }
                if (key == null || iv == null) {
                    log.warn("체결통보 key/iv 미수신 — 데이터 프레임 무시 user={}", userId)
                    continue
                }
                val parts = raw.split("|")
                if (parts.size < 4 || parts[1] != trId) continue
                val plain = try {
                    KisFrames.aesDecrypt(key, iv, parts[3])
                } catch (e: Exception) {
                    log.error("체결통보 복호화 실패 user={}", userId, e)
                    continue
                }
                val n = KisFrames.parseFillNotice(plain) ?: continue
                try {
                    notices.apply(userId, n)
                } catch (e: Exception) {
                    log.error("체결통보 반영 실패 user={} odno={}", userId, n.odno, e)
                }
            }
        } finally {
            s.close()
        }
    }

    /** 감독 루프: 정상 종료면 3초 뒤, 오류면 지수 백오프(5→120초) 후 재연결. stop 이면 끝. */
    private fun supervise(stop: CountDownLatch, onFailure: (Exception) -> Unit, onSuccess: () -> Unit = {}, body: () -> Unit) {
        var backoff = 5L
        while (stop.count > 0) {
            val wait: Long = try {
                body()
                onSuccess()
                backoff = 5
                if (stop.count == 0L) 0 else 3
            } catch (e: InterruptedException) {
                return
            } catch (e: Exception) {
                log.warn("실시간 WS 오류, {}s 후 재연결: {}", backoff, e.message)
                onFailure(e)
                backoff.also { backoff = minOf(backoff * 2, 120) }
            }
            try {
                stop.await(wait, TimeUnit.SECONDS)
            } catch (e: InterruptedException) {
                return
            }
        }
    }

    @Synchronized
    fun shutdown() {
        priceFeeds.keys.toList().forEach { removePrices(it) }
        noticeFeeds.keys.toList().forEach { removeNotices(it) }
    }

    private companion object {
        const val PRICE_TTL = 120L
    }

}
