package com.quantfolio.web.strategy

import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.strategy.StrategyConfigParser
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

data class StrategyCreate(
    @field:Size(min = 1, max = 255) val name: String,
    @field:Size(max = 2000) val description: String? = null,
    @field:NotNull val config: Map<String, Any?>?,
)

data class StrategyUpdate(val name: String? = null, val description: String? = null, val config: Map<String, Any?>? = null)

data class StrategyOut(
    val id: Int,
    val name: String,
    val description: String?,
    val config: Map<String, Any?>,
    val status: StrategyStatus,
    val isFavorite: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

fun Strategy.toOut() = StrategyOut(id!!, name, description, config, status, isFavorite, sortOrder, createdAt, updatedAt)

data class ReorderRequest(@field:NotEmpty val orderedIds: List<Int>)

/**
 * 전략 CRUD·즐겨찾기·정렬(원본 `routes/strategies.py`, 공유·좋아요·복사·대표 백테스트는 소유자 결정으로 삭제). 남의 전략은 존재 자체를 숨긴다(404, IDOR 방지).
 * 설정은 저장 전에 반드시 [StrategyConfigParser] 로 검증·기본값 채움(pydantic `model_dump()` 와 같은 값).
 */
@RestController
@RequestMapping("/api/strategies")
class StrategiesController(
    private val strategies: StrategyRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
) {
    /** 저장 후 DB 에서 다시 읽어 응답(원본 `db.refresh`) — config 는 jsonb 키 순서, 시각은 DB 값이라 이후 GET 과 같다. */
    private fun save(s: Strategy) = strategies.findById(strategies.saveAndFlush(s).id!!).get().toOut()

    @GetMapping
    fun list(@CurrentUser user: User): List<StrategyOut> =
        strategies.findByUserId(user.id!!)
            .sortedWith(compareByDescending<Strategy> { it.isFavorite }.thenBy { it.sortOrder }.thenByDescending { it.id })
            .map { it.toOut() }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@CurrentUser user: User, @Valid @RequestBody payload: StrategyCreate): StrategyOut =
        save(Strategy(user.id!!, payload.name, payload.description?.ifEmpty { null }, StrategyConfigParser.parse(payload.config), StrategyStatus.draft))

    /** 배열 순서대로 sort_order 0..n. 남의 것·없는 ID 는 무시. */
    @PatchMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun reorder(@CurrentUser user: User, @Valid @RequestBody payload: ReorderRequest) {
        val mine = strategies.findByUserId(user.id!!).associateBy { it.id }
        payload.orderedIds.forEachIndexed { order, id -> mine[id]?.sortOrder = order }
    }

    @PostMapping("/{id}/favorite")
    fun favorite(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isFavorite = true })

    @DeleteMapping("/{id}/favorite")
    fun unfavorite(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isFavorite = false })

    @GetMapping("/{id}")
    fun get(@CurrentUser user: User, @PathVariable id: Int) = strategies.owned(user, id).toOut()

    /** 부분 갱신(전달된 필드만). 설명은 공백이면 null. */
    @PatchMapping("/{id}")
    fun update(@CurrentUser user: User, @PathVariable id: Int, @RequestBody payload: StrategyUpdate): StrategyOut {
        val s = strategies.owned(user, id)
        payload.name?.let { s.name = it }
        payload.description?.let { s.description = it.trim().ifEmpty { null } }
        payload.config?.let { s.config = StrategyConfigParser.parse(it) }
        return save(s)
    }

    /** 가동 중·엔진 활성·미청산 포지션이면 409 — 아무도 관리하지 않는 "유령 포지션"이 남지 않게. */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@CurrentUser user: User, @PathVariable id: Int) {
        val s = strategies.owned(user, id)
        if (s.status == StrategyStatus.live) throw ApiException(HttpStatus.CONFLICT, "가동 중인 전략은 삭제할 수 없습니다. 먼저 중지하세요.")
        if (redis.opsForSet().isMember(RedisKeys.ACTIVE_STRATEGIES, id.toString()) == true) {
            throw ApiException(HttpStatus.CONFLICT, "엔진에서 실행 중인 전략입니다. 먼저 중지하세요.")
        }
        val open = jdbc.queryForObject(
            "select exists(select 1 from positions where user_id = :u and strategy_id = :s and qty > 0)",
            MapSqlParameterSource("u", user.id).addValue("s", id), Boolean::class.java,
        ) == true
        if (open) throw ApiException(HttpStatus.CONFLICT, "미청산 포지션이 있어 삭제할 수 없습니다. 먼저 청산하거나 다른 전략으로 이관하세요.")
        strategies.delete(s)
    }
}
