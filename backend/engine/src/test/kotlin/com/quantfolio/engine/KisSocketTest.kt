package com.quantfolio.engine

import org.junit.jupiter.api.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.TimeUnit.SECONDS
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * `KisSocket` 이 닫을 때 소켓·HttpClient 를 남기지 않는지 — 2026-11-17 모의 실측에서 정지 뒤 KIS WS 소켓이 CLOSE_WAIT 로 남았다.
 * JDK WebSocket 은 sendClose 만으로는 TCP 를 닫지 않으므로, close 프레임에 응답하지 않는 서버([FakeKisWs])에서도 연결이 끝나야 한다.
 */
class KisSocketTest {
    private fun selectorThreads() = Thread.getAllStackTraces().keys.filter { it.name.startsWith("HttpClient-") && it.name.endsWith("-SelectorManager") && it.isAlive }.toSet()

    /** [before] 에 없던 HttpClient selector 스레드가 [seconds] 안에 모두 끝나면 빈 집합. */
    private fun leftoverSelectors(before: Set<Thread>, seconds: Int = 5): Set<Thread> {
        repeat(seconds * 10) {
            if ((selectorThreads() - before).isEmpty()) return emptySet()
            Thread.sleep(100)
        }
        return selectorThreads() - before
    }

    @Test
    fun closeEndsTheConnectionEvenIfServerNeverAnswersClose() {
        val before = selectorThreads()
        FakeKisWs().use { server ->
            val s = KisSocket(server.url)
            s.close()
            assertTrue(server.ended.await(10, SECONDS), "close 뒤 클라이언트가 소켓을 닫아야 한다(서버가 close 에 응답하지 않아도)")
            assertTrue(server.receivedClose(), "닫기 전에 close 프레임은 보낸다")
        }
        assertEquals(emptySet(), leftoverSelectors(before), "닫은 연결의 HttpClient selector 스레드가 남는다")
    }

    @Test
    fun failedHandshakesDoNotLeaveHttpClientsBehind() {
        val before = selectorThreads()
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            Thread.ofVirtual().start { while (true) (runCatching { server.accept() }.getOrNull() ?: break).close() } // 응답 없이 끊는다(실측 "header parser received no bytes")
            repeat(3) { assertFails { KisSocket("ws://127.0.0.1:${server.localPort}/tryitout") } }
        }
        assertEquals(emptySet(), leftoverSelectors(before), "핸드셰이크 실패한 연결의 HttpClient selector 스레드가 남는다")
    }
}
