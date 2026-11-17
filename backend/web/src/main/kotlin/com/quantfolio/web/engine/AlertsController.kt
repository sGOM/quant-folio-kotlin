package com.quantfolio.web.engine

import com.quantfolio.domain.alert.Alert
import com.quantfolio.domain.alert.AlertRepository
import com.quantfolio.domain.alert.AlertSeverity
import com.quantfolio.domain.user.User
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.constraints.Max
import jakarta.validation.constraints.Min
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class AlertOut(val id: Int, val userId: Int?, val strategyId: Int, val severity: AlertSeverity, val code: String?, val message: String, val isRead: Boolean, val createdAt: Instant)

fun Alert.toOut() = AlertOut(id!!, userId, strategyId, severity, code, message, isRead, createdAt)

data class AlertListOut(val items: List<AlertOut>, val unreadCount: Int, val hasMore: Boolean)

/** 알림함(원본 `routes/alerts.py`). 본인 알림 + 전역(user_id null) 알림만 보인다. */
@RestController
@RequestMapping("/api/alerts")
class AlertsController(private val alerts: AlertRepository, private val jdbc: NamedParameterJdbcTemplate) {
    private val visible = "(user_id = :u or user_id is null)"

    @GetMapping
    fun list(
        @CurrentUser user: User,
        @RequestParam(name = "unread_only", defaultValue = "false") unreadOnly: Boolean,
        @RequestParam(defaultValue = "50") @Min(1) @Max(200) limit: Int,
        @RequestParam(defaultValue = "0") @Min(0) offset: Int,
    ): AlertListOut {
        val p = MapSqlParameterSource("u", user.id).addValue("n", limit + 1).addValue("o", offset)
        val ids = jdbc.queryForList(
            "select id from alerts where $visible${if (unreadOnly) " and not is_read" else ""} order by created_at desc limit :n offset :o",
            p, Int::class.java,
        )
        val byId = alerts.findAllById(ids.filterNotNull()).associateBy { it.id }
        val rows = ids.mapNotNull { byId[it] }
        val unread = jdbc.queryForObject("select count(*) from alerts where $visible and not is_read", p, Int::class.java) ?: 0
        return AlertListOut(rows.take(limit).map { it.toOut() }, unread, rows.size > limit)
    }

    @PostMapping("/{id}/read")
    fun read(@CurrentUser user: User, @PathVariable id: Int): AlertOut {
        val a = alerts.findById(id).orElse(null)
        if (a == null || (a.userId != null && a.userId != user.id)) throw ApiException(HttpStatus.NOT_FOUND, "알림을 찾을 수 없습니다.")
        a.isRead = true
        return alerts.save(a).toOut()
    }

    @PostMapping("/read-all")
    fun readAll(@CurrentUser user: User): Map<String, Int> =
        mapOf("updated" to jdbc.update("update alerts set is_read = true where $visible and not is_read", MapSqlParameterSource("u", user.id)))
}
