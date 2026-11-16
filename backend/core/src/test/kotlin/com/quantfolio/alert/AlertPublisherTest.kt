package com.quantfolio.alert

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.TestApp
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.test.ContainersConfig
import com.quantfolio.test.FakeExternalHttp
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.connection.RedisConnectionFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.listener.ChannelTopic
import org.springframework.data.redis.listener.RedisMessageListenerContainer
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@SpringBootTest(classes = [TestApp::class])
@Import(ContainersConfig::class)
class AlertPublisherTest(
    @Autowired private val alerts: AlertRepository,
    @Autowired private val users: UserRepository,
    @Autowired private val jdbc: NamedParameterJdbcTemplate,
    @Autowired private val redis: StringRedisTemplate,
    @Autowired private val cf: RedisConnectionFactory,
    @Autowired private val txManager: org.springframework.transaction.PlatformTransactionManager,
) {
    private val clock = Clock.fixed(Instant.parse("2026-10-19T01:02:03.456789Z"), ZoneOffset.UTC)
    private val http = FakeExternalHttp().apply { onText({ "api.telegram.org" in it.url }, body = "{}") }

    /** json.dumps(ensure_ascii) 처럼 비 ASCII 를 \uXXXX 로. */
    private fun esc(s: String) = s.map { if (it.code > 0x7e) "\\u%04x".format(it.code) else it.toString() }.joinToString("")

    private fun publisher(telegram: Boolean) = AlertPublisher(
        jdbc, redis, http,
        QuantfolioProperties(telegram = if (telegram) QuantfolioProperties.Telegram("TOKEN", "CHAT") else QuantfolioProperties.Telegram()),
        clock, txManager,
    )

    private fun listen(channel: String): Pair<RedisMessageListenerContainer, LinkedBlockingQueue<String>> {
        val q = LinkedBlockingQueue<String>()
        val c = RedisMessageListenerContainer().apply {
            setConnectionFactory(cf)
            addMessageListener({ m, _ -> q.add(String(m.body)) }, ChannelTopic(channel))
            afterPropertiesSet()
            start()
        }
        val deadline = System.currentTimeMillis() + 5_000
        while (!c.isListening && System.currentTimeMillis() < deadline) Thread.sleep(20)
        return c to q
    }

    @Test
    fun persistsPublishesAndSendsCriticalToTelegram() {
        val uid = users.save(User(email = "${UUID.randomUUID()}@t.dev", passwordHash = "x")).id!! // alerts.user_id 는 users FK
        val (c, q) = listen(RedisKeys.engineEvents(uid))
        try {
            val code = "t-" + UUID.randomUUID()
            publisher(telegram = true).publish(uid, 7, AlertSeverity.critical, "러너 연속 실패", code)
            assertEquals(
                """{"type": "alert", "user_id": $uid, "strategy_id": 7, "severity": "critical", "message": "${esc("러너 연속 실패")}", "ts": "2026-10-19T10:02:03.456789+09:00", "code": "$code"}""",
                q.poll(5, TimeUnit.SECONDS),
            )
            assertEquals(1, alerts.findAll().count { it.code == code && it.userId == uid && it.strategyId == 7 })
            val tg = http.calls.single { "api.telegram.org" in it.url }
            assertEquals("https://api.telegram.org/botTOKEN/sendMessage", tg.url)
            val newline = "\\" + "n" // json.dumps 가 줄바꿈을 \n 두 글자로 이스케이프
            assertEquals("""{"chat_id": "CHAT", "text": "[QuantFolio] ${esc("전략")} 7($code)$newline${esc("러너 연속 실패")}"}""", tg.body)
        } finally {
            c.stop()
        }
    }

    @Test
    fun dedupWindowSkipsDbAndTelegramButGlobalAlertsNeverHitUserChannel() {
        val code = "d-" + UUID.randomUUID()
        val p = publisher(telegram = true)
        p.publish(null, 0, AlertSeverity.critical, "백업 오래됨", code, dedupWindowHours = 20.0)
        p.publish(null, 0, AlertSeverity.critical, "백업 오래됨", code, dedupWindowHours = 20.0)
        assertEquals(1, alerts.findAll().count { it.code == code })
        assertEquals(1, http.calls.count { "api.telegram.org" in it.url })
        alerts.findAll().filter { it.code == code }.forEach { it.isRead = true; alerts.save(it) }
        p.publish(null, 0, AlertSeverity.warning, "다시", code, dedupWindowHours = 20.0) // 읽음 처리 후엔 다시 적재
        assertEquals(2, alerts.findAll().count { it.code == code })
        assertEquals(1, http.calls.count { "api.telegram.org" in it.url }) // warning 은 텔레그램 없음
        assertTrue(alerts.findAll().filter { it.code == code }.all { it.userId == null })
    }

    /** 호출자 트랜잭션이 롤백돼도 알림은 남는다(원본은 별도 세션) — 접수불명 critical 이 체결 롤백과 함께 사라지면 안 된다. */
    @Test
    fun alertSurvivesCallerRollback() {
        val code = "rollback_" + java.util.UUID.randomUUID()
        org.springframework.transaction.support.TransactionTemplate(txManager).executeWithoutResult {
            publisher(false).publish(null, 0, AlertSeverity.critical, "롤백 시험", code)
            it.setRollbackOnly()
        }
        kotlin.test.assertEquals(1, alerts.findAll().count { a -> a.code == code })
    }
}
