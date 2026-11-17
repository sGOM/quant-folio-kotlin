package com.quantfolio.engine

import com.quantfolio.alert.AlertPublisher
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerHttp
import com.quantfolio.broker.BrokerResponse
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.trading.Order
import com.quantfolio.domain.trading.OrderRepository
import com.quantfolio.domain.trading.OrderStatus
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.transaction.support.TransactionTemplate
import java.io.BufferedInputStream
import java.io.InputStream
import java.io.OutputStream
import java.math.BigDecimal
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.time.Clock
import java.util.Base64
import java.util.Collections
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit.SECONDS
import java.util.concurrent.atomic.AtomicReference
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * 체결통보 리스너의 수신 루프와 정합 처리 분리 — 로컬 가짜 KIS WebSocket 서버로 실제 `KisRealtime` 수신 루프를 돌린다.
 * 정합은 막을 수 있는 가짜(`reconcileOrder` 대체)라 증권사 조회가 없다. 주문 매칭은 실제 DB 로 한다.
 */
@SpringBootTest
@Import(ContainersConfig::class)
class FillNoticeListenerTest(
    @Autowired private val users: UserRepository,
    @Autowired private val strategies: StrategyRepository,
    @Autowired private val orders: OrderRepository,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val ctx: ApplicationContext,
) {
    @MockitoBean lateinit var brokers: BrokerCredentials // FillNoticeApplyTest 와 같은 컨텍스트를 쓰려고 같게 둔다

    private fun order(uid: Int, sid: Int): Order = orders.save(
        Order(uid, sid, "005930", "buy", BigDecimal(300), BigDecimal("4265"), status = OrderStatus.submitted, kisOrderId = "L" + UUID.randomUUID().toString().take(8),
            idempotencyKey = UUID.randomUUID().toString(), brokerAccountKey = TEST_KIS_ACCOUNT_KEY),
    )

    /** 사용자 하나와 그 사용자의 오늘 주문 [n] 건(주문번호 있음·submitted). */
    private fun ordersOfNewUser(n: Int): Pair<Int, List<Order>> {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!!
        val sid = strategies.save(Strategy(uid, "s", config = mapOf("type" to "rsi"))).id!!
        return uid to List(n) { order(uid, sid) }
    }

    /** [reconcile] 이 정합을 대신하는 리스너를 띄워 [body] 를 돌리고 끝나면 정리한다. */
    private fun listen(uid: Int, reconcile: (Int) -> Map<String, Int>, body: (FakeKisWs, KisRealtime) -> Unit) {
        val clock = ctx.getBean(Clock::class.java)
        val base = ctx.getBean(QuantfolioProperties::class.java)
        val stub = object : Reconciler(
            orders, users, brokers, ctx.getBean(Fills::class.java), ctx.getBean(AlertPublisher::class.java), redis,
            ctx.getBean(NamedParameterJdbcTemplate::class.java), ctx.getBean(TransactionTemplate::class.java), base, clock,
        ) {
            override fun reconcileOrder(orderId: Int) = reconcile(orderId)
        }
        val applier = FillNoticeApplier(stub, ctx.getBean(NamedParameterJdbcTemplate::class.java), base, clock)
        FakeKisWs().use { ws ->
            val props = base.copy(kis = base.kis.copy(env = "vts", htsId = "HTS", wsUrlVts = ws.url))
            val approval = object : BrokerHttp {
                override fun send(method: String, url: String, query: Map<String, String>, headers: Map<String, String>, json: String?, form: Map<String, String>?) =
                    if (url.endsWith("/oauth2/Approval")) BrokerResponse(200, """{"approval_key":"AK"}""") else error("예상하지 않은 요청: $url")
            }
            val realtime = KisRealtime(approval, redis, props, ctx.getBean(AlertPublisher::class.java), applier)
            realtime.ensureNotices(uid, "ak", "as")
            try {
                ws.awaitSubscribed()
                body(ws, realtime)
            } finally {
                realtime.removeNotices(uid)
            }
        }
    }

    @Test
    fun pingPongIsAnsweredWhileNoticeReconcileBlocksAndStopEndsTheWorker() {
        val (uid, list) = ordersOfNewUser(1)
        val entered = CountDownLatch(1)
        val interrupted = CountDownLatch(1)
        val worker = AtomicReference<Thread>()
        listen(uid, reconcile = {
            worker.set(Thread.currentThread())
            entered.countDown()
            try {
                CountDownLatch(1).await() // 정합(증권사 조회)이 끝나지 않는다
            } catch (e: InterruptedException) {
                interrupted.countDown()
                throw e
            }
            emptyMap()
        }) { ws, realtime ->
            ws.notice(list[0].kisOrderId!!)
            assertTrue(entered.await(10, SECONDS), "통보가 정합을 트리거해야 한다")

            assertTrue(ws.pingPong(), "정합이 막힌 동안에도 PINGPONG 에 응답해야 한다")

            realtime.removeNotices(uid)
            assertTrue(interrupted.await(10, SECONDS), "리스너 정지가 처리 중인 정합을 인터럽트해야 한다")
            worker.get().join(10_000)
            assertFalse(worker.get().isAlive, "리스너 정지 뒤 통보 처리 스레드가 끝나야 한다")
        }
    }

    /** 같은 주문 통보가 몰려도 정합은 "실행 중 1 + 대기 1" — 조회를 시작한 뒤 온 통보 때문에 한 번 더 돌고, 그 뒤엔 다른 주문 차례. */
    @Test
    fun burstOfNoticesForOneOrderCoalescesToRunningPlusOnePending() {
        val (uid, list) = ordersOfNewUser(2)
        val (a, b) = list
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        listen(uid, reconcile = { id ->
            calls += id
            if (calls.size == 1) { entered.countDown(); release.await(10, SECONDS) }
            if (id == b.id) done.countDown()
            emptyMap()
        }) { ws, _ ->
            ws.notice(a.kisOrderId!!)
            assertTrue(entered.await(10, SECONDS))
            repeat(5) { ws.notice(a.kisOrderId!!) } // 실측: 같은 초에 3~6건
            ws.notice(b.kisOrderId!!)
            ws.pingPong() // 수신 루프가 앞 프레임을 다 넘긴 뒤에 풀어 준다(응답이 없으면 그냥 진행 — 아래 단언이 실패를 드러낸다)
            release.countDown()
            assertTrue(done.await(20, SECONDS), "다른 주문의 통보도 처리돼야 한다")
            assertEquals(listOf(a.id, a.id, b.id), calls.toList())
        }
    }

    /** 한 주문의 통보가 정합마다 계속 와도(늘 대기 1 이 있다) 다른 주문의 통보가 굶지 않는다 — 대기는 도착 순서대로. */
    @Test
    fun continuousNoticesForOneOrderDoNotStarveAnotherOrder() {
        val (uid, list) = ordersOfNewUser(2)
        val (a, b) = list
        val calls = Collections.synchronizedList(mutableListOf<Int>())
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val ws = AtomicReference<FakeKisWs>()
        listen(uid, reconcile = { id ->
            calls += id
            val runsOfA = calls.count { it == a.id }
            if (id == a.id && runsOfA <= 3) { ws.get().notice(a.kisOrderId!!); ws.get().pingPong() } // 정합 중에 같은 주문 통보가 또 온다
            if (runsOfA == 1 && id == a.id) { entered.countDown(); release.await(10, SECONDS) }
            if (id == a.id && runsOfA == 4) done.countDown()
            emptyMap()
        }) { server, _ ->
            ws.set(server)
            server.notice(a.kisOrderId!!)
            assertTrue(entered.await(10, SECONDS))
            server.notice(b.kisOrderId!!)
            server.pingPong()
            release.countDown()
            assertTrue(done.await(20, SECONDS))
            assertEquals(listOf(a.id, a.id, b.id, a.id, a.id), calls.toList())
        }
    }
}

/**
 * 테스트용 최소 KIS WebSocket 서버(연결 하나) — 핸드셰이크, 서버→클라이언트 텍스트 프레임, 클라이언트 프레임(마스킹) 수신만.
 * 클라이언트(`KisSocket`)의 구독 메시지를 받으면 key/iv 를 담은 구독 응답을 보낸다.
 */
class FakeKisWs : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    val url = "ws://127.0.0.1:${server.localPort}/tryitout"
    private val frames = LinkedBlockingQueue<Pair<Int, String>>() // (opcode, payload)
    private val subscribed = CountDownLatch(1)
    /** 서버 쪽에서 본 연결이 끝났다(클라이언트가 소켓을 닫아 EOF·리셋). close 프레임엔 응답하지 않는다 — 클라이언트가 스스로 닫아야 한다. */
    val ended = CountDownLatch(1)
    @Volatile private var socket: Socket? = null
    private lateinit var out: OutputStream

    init {
        Thread.ofVirtual().name("fake-kis-ws").start {
            runCatching { serve(server.accept().also { socket = it }) }
            ended.countDown()
        }
    }

    private fun serve(s: Socket) {
        val input = BufferedInputStream(s.getInputStream())
        val headers = generateSequence { readLine(input).takeIf { it.isNotEmpty() } }.toList()
        val key = headers.first { it.lowercase().startsWith("sec-websocket-key:") }.substringAfter(":").trim()
        val accept = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").toByteArray()))
        out = s.getOutputStream()
        synchronized(this) {
            out.write("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: $accept\r\n\r\n".toByteArray())
            out.flush()
        }
        while (true) {
            val b0 = input.read()
            if (b0 < 0) return
            val b1 = input.read()
            var len = (b1 and 0x7F).toLong()
            if (len == 126L) len = ((input.read() shl 8) or input.read()).toLong()
            else if (len == 127L) len = (0 until 8).fold(0L) { acc, _ -> (acc shl 8) or input.read().toLong() }
            val mask = if (b1 and 0x80 != 0) input.readNBytes(4) else null
            val payload = input.readNBytes(len.toInt())
            if (mask != null) for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            val opcode = b0 and 0x0F
            val text = String(payload, Charsets.UTF_8)
            if (opcode == 1 && "H0STCNI9" in text) {
                send("""{"header":{"tr_id":"H0STCNI9"},"body":{"rt_cd":"0","msg_cd":"OPSP0000","msg1":"SUBSCRIBE SUCCESS","output":{"iv":"$IV","key":"$KEY"}}}""")
                subscribed.countDown()
                continue
            }
            frames.add(opcode to text)
        }
    }

    fun receivedClose() = frames.any { it.first == 8 }

    private fun readLine(input: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0 || c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }

    fun awaitSubscribed() = check(subscribed.await(10, SECONDS)) { "체결통보 구독 메시지가 오지 않았다" }

    fun send(text: String) {
        val p = text.toByteArray(Charsets.UTF_8)
        synchronized(this) {
            out.write(0x81)
            if (p.size < 126) out.write(p.size) else { out.write(126); out.write(p.size shr 8); out.write(p.size and 0xFF) }
            out.write(p)
            out.flush()
        }
    }

    /** 체결 통보(CNTG_YN=2) 프레임 — 계좌는 테스트 KIS 계좌, 종목 005930. */
    fun notice(odno: String, qty: Int = 1) {
        val plain = MutableList(23) { "" }.apply {
            this[1] = TEST_KIS_ACCOUNT; this[2] = odno; this[4] = "02"; this[8] = "005930"; this[9] = qty.toString(); this[10] = "4265"
            this[12] = "0"; this[13] = "2"; this[14] = "2"; this[16] = "300"
        }.joinToString("^")
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(KEY.toByteArray(), "AES"), IvParameterSpec(IV.toByteArray()))
        send("1|H0STCNI9|001|" + Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray())))
    }

    /** PINGPONG 을 보내고 같은 내용의 pong 이 5초 안에 오면 true. 수신 루프는 프레임을 순서대로 처리하므로 앞서 보낸 프레임은 그때 이미 처리됐다. */
    fun pingPong(): Boolean {
        val ping = """{"header":{"tr_id":"PINGPONG","datetime":"${UUID.randomUUID().toString().take(8)}"}}"""
        send(ping)
        val deadline = System.nanoTime() + 5_000_000_000L
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return false
            val f = frames.poll(left, java.util.concurrent.TimeUnit.NANOSECONDS) ?: return false
            if (f.first == 0xA && f.second == ping) return true
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        runCatching { server.close() }
    }

    companion object {
        const val KEY = "abcdefghijklmnopqrstuvwxyz012345"
        const val IV = "0123456789abcdef"
    }
}
