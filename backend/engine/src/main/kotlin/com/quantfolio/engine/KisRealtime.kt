package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerHttp
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.alert.AlertSeverity
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.time.Duration
import java.util.concurrent.CompletionStage
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * KIS WebSocket 한 연결 — 텍스트 프레임을 큐로 넘기고, 닫히면 null 을 넣어 수신 루프를 끝낸다. internal: 모의투자 확인 도구(test `KisProbe`)가 재사용한다.
 * 연결마다 자기 HttpClient 를 갖고 [close]·핸드셰이크 실패 때 닫는다 — 안 닫으면 클라이언트마다 selector 스레드가 GC 될 때까지 남는다.
 */
internal class KisSocket(url: String) : WebSocket.Listener {
    private val frames = LinkedBlockingQueue<Any>()
    private val buf = StringBuilder()
    private val serverClosed = CountDownLatch(1)
    private val client = HttpClient.newHttpClient()
    val ws: WebSocket = try {
        client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10)).buildAsync(URI.create(url), this).join()
    } catch (e: Exception) {
        client.shutdownNow()
        throw e
    }

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
        serverClosed.countDown()
        return null
    }

    override fun onError(webSocket: WebSocket, error: Throwable) {
        frames.add(error)
        serverClosed.countDown()
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
    /**
     * close 프레임을 보내고 서버의 close(또는 끊김)를 [CLOSE_WAIT_SECONDS] 까지 기다린 뒤 소켓·클라이언트를 버린다.
     * JDK WebSocket 은 sendClose 만으로는 TCP 를 닫지 않고(FIN 도 안 보낸다) 서버 쪽 close·EOF 를 읽어야 닫는다 — 그래서 끝에 abort 로 닫는다.
     * 인터럽트되면 기다리지 않고 바로 닫는다(인터럽트 상태는 되살린다).
     */
    fun close() {
        try {
            ws.sendClose(WebSocket.NORMAL_CLOSURE, "").get(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
            serverClosed.await(CLOSE_WAIT_SECONDS, TimeUnit.SECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (e: Exception) {
            // 전송 실패·시간 초과 — 아래에서 닫는다
        } finally {
            ws.abort()
            client.shutdownNow()
        }
    }

    private companion object {
        val CLOSED = Any()

        /** close 전송·서버 close 대기 각각의 상한(초). 측정값 아님 — 원본 websockets 의 close_timeout 기본값은 10초. */
        const val CLOSE_WAIT_SECONDS = 2L
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

    /** 체결통보 구독(계좌 전체 1구독). HTS ID(secrets/hts_id.txt) 미설정이면 no-op. */
    @Synchronized
    fun ensureNotices(userId: Int, appKey: String, appSecret: String) {
        val hts = props.kis.htsId
        if (hts.isNullOrEmpty()) {
            log.info("HTS ID 미설정(secrets/hts_id.txt) — 체결통보 리스너 비활성(user={}). 실계정 전환 전 설정 필요.", userId)
            return
        }
        if (noticeFeeds.containsKey(userId)) return
        val stop = CountDownLatch(1)
        val t = Thread.ofVirtual().name("fill-notice-$userId").start {
            // 처리 스레드는 재연결을 넘어 리스너와 수명이 같다 — 재연결 중에도 대기 요청을 처리하고, 정지(감독 루프 종료) 때 닫힌다.
            notices.Worker(userId).use { worker ->
                supervise(stop, onFailure = { e -> log.warn("체결통보 WS 오류 user={}: {}", userId, e.message) }) { runNotices(userId, appKey, appSecret, hts, stop, worker) }
            }
        }
        noticeFeeds[userId] = Feed(stop, t, emptySet())
        log.info("체결통보 리스너 시작 user={}", userId)
    }

    @Synchronized
    fun removeNotices(userId: Int) {
        noticeFeeds.remove(userId)?.let { it.stop.countDown(); it.thread.interrupt() }
    }

    @Suppress("UNCHECKED_CAST")
    private fun runNotices(userId: Int, appKey: String, appSecret: String, hts: String, stop: CountDownLatch, worker: FillNoticeApplier.Worker) {
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
                worker.offer(KisFrames.parseFillNotice(plain) ?: continue)
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
