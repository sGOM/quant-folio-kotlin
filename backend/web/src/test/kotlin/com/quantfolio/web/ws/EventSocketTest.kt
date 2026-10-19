package com.quantfolio.web.ws

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.Passwords
import com.quantfolio.test.ContainersConfig
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHttpHeaders
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.net.URI
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(ContainersConfig::class)
class EventSocketTest(
    @Autowired private val users: UserRepository,
    @Autowired private val sessions: SessionStore,
    @Autowired private val redis: StringRedisTemplate,
) {
    @LocalServerPort var port: Int = 0

    private class Recorder : TextWebSocketHandler() {
        val messages = LinkedBlockingQueue<String>()
        val closed = CompletableFuture<CloseStatus>()
        override fun handleTextMessage(session: WebSocketSession, message: TextMessage) { messages.add(message.payload) }
        override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) { closed.complete(status) }
    }

    private fun connect(cookie: String?): Pair<WebSocketSession, Recorder> {
        val r = Recorder()
        val headers = WebSocketHttpHeaders().apply { cookie?.let { add("Cookie", "$SESSION_COOKIE=$it") } }
        val s = StandardWebSocketClient().execute(r, headers, URI("ws://localhost:$port/ws")).get(5, TimeUnit.SECONDS)
        return s to r
    }

    @Test
    fun unauthenticatedIsClosedWith4401() {
        assertEquals(4401, connect(null).second.closed.get(5, TimeUnit.SECONDS).code)
        assertEquals(4401, connect("bogus").second.closed.get(5, TimeUnit.SECONDS).code)
    }

    @Test
    fun relaysOnlyOwnUserEventsAndSkipsInvalidJson() {
        val u = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = Passwords.hash("password-1")))
        val (session, rec) = connect(sessions.create(u.id!!))
        try {
            assertEquals("""{"type":"connected","user_id":${u.id}}""", rec.messages.poll(5, TimeUnit.SECONDS))
            redis.convertAndSend(RedisKeys.engineEvents(u.id!! + 100_000), """{"type":"order","x":1}""")
            redis.convertAndSend(RedisKeys.engineEvents(u.id!!), "not json")
            redis.convertAndSend(RedisKeys.engineEvents(u.id!!), """{"type": "execution", "symbol": "005930"}""")
            assertEquals("""{"type": "execution", "symbol": "005930"}""", rec.messages.poll(5, TimeUnit.SECONDS))
            assertEquals(null, rec.messages.poll(300, TimeUnit.MILLISECONDS))
        } finally {
            session.close()
        }
    }
}
