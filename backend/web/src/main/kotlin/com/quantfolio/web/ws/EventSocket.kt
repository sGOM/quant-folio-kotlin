package com.quantfolio.web.ws

import com.quantfolio.common.RedisKeys
import com.quantfolio.web.auth.SESSION_COOKIE
import com.quantfolio.web.auth.SessionStore
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Configuration
import org.springframework.data.redis.connection.MessageListener
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.http.server.ServerHttpRequest
import org.springframework.http.server.ServerHttpResponse
import org.springframework.stereotype.Component
import org.springframework.web.socket.CloseStatus
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.WebSocketHandler
import org.springframework.web.socket.WebSocketSession
import org.springframework.web.socket.config.annotation.EnableWebSocket
import org.springframework.web.socket.config.annotation.WebSocketConfigurer
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator
import org.springframework.web.socket.handler.TextWebSocketHandler
import org.springframework.web.socket.server.HandshakeInterceptor
import tools.jackson.databind.json.JsonMapper
import java.util.concurrent.ConcurrentHashMap

/**
 * 실시간 이벤트 WebSocket `/ws`(원본 `routes/ws.py`): 엔진이 사용자 채널(`engine:events:{uid}`)에 발행한 이벤트를 중계.
 * 인증은 세션 쿠키. 실패하면 **accept 후 close code 4401** — 프론트가 이 코드로 재연결을 멈추고 로그인으로 보낸다
 * (원본은 accept 전에 닫아 핸드셰이크 403 이 됐고 프론트의 4401 분기가 실제로 동작하지 않았다, migration-notes).
 */
@Component
class EventSocket(
    private val sessions: SessionStore,
    private val listeners: RedisMessageListenerContainer,
    private val json: JsonMapper,
) : TextWebSocketHandler() {
    private val log = LoggerFactory.getLogger(javaClass)
    private val subscriptions = ConcurrentHashMap<String, Pair<MessageListener, ChannelTopic>>()

    /** 핸드셰이크의 쿠키에서 user_id 를 꺼내 세션 속성에 둔다(무효면 비워 둔다 → 연결 직후 4401). */
    val auth = object : HandshakeInterceptor {
        override fun beforeHandshake(req: ServerHttpRequest, res: ServerHttpResponse, handler: WebSocketHandler, attrs: MutableMap<String, Any>): Boolean {
            val sid = req.headers["Cookie"].orEmpty().flatMap { it.split(";") }.map { it.trim() }
                .firstOrNull { it.startsWith("$SESSION_COOKIE=") }?.substringAfter('=')
            sessions.userId(sid)?.let { attrs[USER_ID] = it }
            return true
        }

        override fun afterHandshake(req: ServerHttpRequest, res: ServerHttpResponse, handler: WebSocketHandler, ex: Exception?) = Unit
    }

    override fun afterConnectionEstablished(session: WebSocketSession) {
        val userId = session.attributes[USER_ID] as Int?
        if (userId == null) {
            session.close(CloseStatus(4401))
            return
        }
        val out = ConcurrentWebSocketSessionDecorator(session, 10_000, 1 shl 20)
        val topic = ChannelTopic(RedisKeys.engineEvents(userId))
        val listener = MessageListener { message, _ ->
            val text = String(message.body)
            val valid = runCatching { json.readTree(text) }.isSuccess
            if (valid && out.isOpen) runCatching { out.sendMessage(TextMessage(text)) }.onFailure { log.warn("WS 중계 오류: {}", it.message) }
        }
        listeners.addMessageListener(listener, topic)
        subscriptions[session.id] = listener to topic
        out.sendMessage(TextMessage(json.writeValueAsString(linkedMapOf("type" to "connected", "user_id" to userId))))
    }

    override fun afterConnectionClosed(session: WebSocketSession, status: CloseStatus) {
        subscriptions.remove(session.id)?.let { (l, t) -> listeners.removeMessageListener(l, t) }
    }

    private companion object {
        const val USER_ID = "user_id"
    }
}

@Configuration(proxyBeanMethods = false)
@EnableWebSocket
class WebSocketConfig(private val socket: EventSocket) : WebSocketConfigurer {
    // 원본처럼 Origin 검사 없음 — 쿠키가 SameSite=lax 라 타 사이트 핸드셰이크엔 세션이 실리지 않는다.
    override fun registerWebSocketHandlers(registry: WebSocketHandlerRegistry) {
        registry.addHandler(socket, "/ws").addInterceptors(socket.auth).setAllowedOriginPatterns("*")
    }
}
