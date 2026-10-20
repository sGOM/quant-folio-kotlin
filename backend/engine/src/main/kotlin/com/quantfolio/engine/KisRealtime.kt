package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerHttp
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
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

/** 복호화·파싱된 체결통보 1건(side 는 참고용 — 매칭에 쓰지 않는다). */
data class FillNotice(val odno: String, val symbol: String, val qty: Int, val price: BigDecimal, val side: String)

/**
 * KIS 실시간 프레임(원본 `kis_ws.py`·`fill_notice.py`). 데이터 프레임은 `암호화여부|tr_id|건수|필드^필드…`.
 * 체결통보 필드 인덱스는 KIS 공식 샘플 순서 — **실계정 프레임으로 종단 검증되지 않았다**(원본 주석의 미검증 가정 그대로).
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
        val price = runCatching { BigDecimal(f[2]) }.getOrNull() ?: return null
        return if (price.signum() > 0) f[0] to price else null
    }

    /** AES-256-CBC + PKCS7(Java 의 PKCS5Padding 이 블록 16 에선 같다). key/iv 는 구독 응답 문자열. */
    fun aesDecrypt(key: String, iv: String, cipherB64: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(c.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }

    /** 체결통보 평문(^ 구분). 체결수량 0(접수·거부·취소 통보)이면 null. */
    fun parseFillNotice(plain: String): FillNotice? {
        val f = plain.split("^")
        if (f.size < 23) {
            LoggerFactory.getLogger("engine.fill_notice").warn("체결통보 필드 수 부족({}) — 무시", f.size)
            return null
        }
        val odno = f[2].trim()
        val symbol = f[8].trim()
        if (odno.isEmpty() || symbol.isEmpty()) return null
        val qty = f[9].ifEmpty { "0" }.toIntOrNull() ?: 0
        if (qty <= 0) return null
        val price = runCatching { BigDecimal(f[10].ifEmpty { "0" }) }.getOrDefault(BigDecimal.ZERO)
        return FillNotice(odno, symbol, qty, price, if (f[4] == "01") "sell" else "buy")
    }
}

/**
 * 체결통보 1건 반영(원본 `apply_fill_notice`). executor·reconcile 과 함께 3중 경로라, 이미 기록된 누적 대비 **델타만** 기록하고
 * read-then-write 구간을 reconcile 과 **같은 주문 락 키**로 감싼다(키가 다르면 서로 배제하지 못해 이중 가산된다).
 * 락을 못 잡으면 재시도 없이 스킵(중복보다 안전) — 단 모의투자 매도는 자동 복구되지 않아 warning 으로 남긴다.
 * 미검증 가정: 통보의 체결수량을 "이 주문의 누적"으로 본다(증분으로 밝혀지면 이 로직을 고쳐야 한다).
 */
@Component
class FillNoticeApplier(
    private val orders: OrderRepository,
    private val fills: Fills,
    private val alerts: AlertPublisher,
    private val redis: StringRedisTemplate,
    private val jdbc: NamedParameterJdbcTemplate,
    private val tx: TransactionTemplate,
) {
    private val log = LoggerFactory.getLogger("engine.fill_notice")

    fun apply(userId: Int, n: FillNotice): Boolean {
        val id = jdbc.queryForList("select id from orders where user_id = :u and kis_order_id = :o", MapSqlParameterSource("u", userId).addValue("o", n.odno), Int::class.java).firstOrNull()
        val order = id?.let { orders.findById(it).orElse(null) }
        if (order == null) {
            log.warn("체결통보 매칭 실패(주문 없음) user={} odno={} symbol={}", userId, n.odno, n.symbol)
            return false
        }
        val lock = RedisKeys.reconcileLock(order.id!!)
        if (redis.opsForValue().setIfAbsent(lock, "1", Duration.ofSeconds(RedisKeys.RECONCILE_LOCK_TTL_SECONDS)) != true) {
            log.warn("체결통보 스킵(다른 프로세스가 같은 주문 정합 중) order={} odno={} 누적통보={}", order.id, n.odno, n.qty)
            return false
        }
        val delta: Int
        val price: BigDecimal
        try {
            val already = jdbc.queryForObject("select coalesce(sum(filled_qty), 0) from executions where order_id = :o", MapSqlParameterSource("o", order.id), BigDecimal::class.java)!!.toInt()
            delta = n.qty - already
            if (delta <= 0) {
                log.debug("체결통보 중복/이미 반영 — 스킵 odno={} 누적통보={} 기록됨={}", n.odno, n.qty, already)
                return false
            }
            price = if (n.price.signum() > 0) n.price else order.price!!
            tx.executeWithoutResult { fills.record(order, delta, price, already + delta >= order.qty.toInt()) }
        } finally {
            redis.delete(lock)
        }
        alerts.publishEvent(
            linkedMapOf(
                "type" to "execution", "user_id" to userId, "order_id" to order.id, "symbol" to order.symbol, "side" to order.side, "qty" to delta,
                "price" to price.toDouble(), "status" to order.status, "kis_order_id" to order.kisOrderId, "source" to "fill_notice",
            ),
        )
        log.info("체결통보 반영: 주문={} +{}주 @ {} → {}", order.kisOrderId, delta, price, order.status)
        return true
    }
}

/** KIS WebSocket 한 연결 — 텍스트 프레임을 큐로 넘기고, 닫히면 null 을 넣어 수신 루프를 끝낸다. */
private class KisSocket(url: String) : WebSocket.Listener {
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
    private val json = JsonMapper.builder().build()

    private class Feed(val stop: CountDownLatch, val thread: Thread, val symbols: Set<String>)

    private val priceFeeds = ConcurrentHashMap<Int, Feed>()
    private val noticeFeeds = ConcurrentHashMap<Int, Feed>()
    private val failCounts = ConcurrentHashMap<Int, Int>()

    /** 실시간 시세용 approval_key. */
    fun approvalKey(appKey: String, appSecret: String): String {
        val r = http.send("POST", "${props.kis.baseUrl}/oauth2/Approval", json = json.writeValueAsString(linkedMapOf("grant_type" to "client_credentials", "appkey" to appKey, "secretkey" to appSecret)))
        if (r.status !in 200..299) throw IllegalStateException("approval_key 발급 실패: HTTP ${r.status}")
        return json.readValue<Map<String, Any?>>(r.body)["approval_key"].toString()
    }

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
            alerts.publish(userId, 0, "warning", "실시간 시세 WS 연속 ${n}회 재연결 실패 — REST 폴백으로 매매는 지속되나 시세 반영이 지연될 수 있음. 마지막 원인: ${e.message}", "price_feed_outage", 6.0)
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
                    if (out["key"] != null && out["iv"] != null) {
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
