package com.quantfolio.web.strategy

import com.quantfolio.broker.BrokerClient
import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.broker.BrokerError
import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.common.RedisKeys
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyLike
import com.quantfolio.domain.strategy.StrategyLikeRepository
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.strategy.StrategyStatus
import com.quantfolio.domain.user.User
import com.quantfolio.domain.user.UserRepository
import com.quantfolio.security.CredentialCipher
import com.quantfolio.strategy.StrategyConfigParser
import com.quantfolio.web.api.ApiException
import com.quantfolio.web.api.owned
import com.quantfolio.web.auth.CurrentUser
import com.quantfolio.web.auth.UserOut
import com.quantfolio.web.auth.toOut
import jakarta.validation.Valid
import jakarta.validation.constraints.NotEmpty
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
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
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
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
    val featuredBacktestId: Int?,
    val isShared: Boolean,
    val isFavorite: Boolean,
    val sortOrder: Int,
    val createdAt: Instant,
    val updatedAt: Instant?,
)

fun Strategy.toOut() = StrategyOut(id!!, name, description, config, status, featuredBacktestId, isShared, isFavorite, sortOrder, createdAt, updatedAt)

data class BacktestSummary(val id: Int, val totalReturn: Double?, val mdd: Double?, val sharpe: Double?, val periodStart: Instant, val periodEnd: Instant)

data class SharedStrategyOut(
    val id: Int,
    val name: String,
    val description: String?,
    val config: Map<String, Any?>,
    val authorName: String,
    val likeCount: Int,
    val likedByMe: Boolean,
    val isMine: Boolean,
    val backtest: BacktestSummary?,
    val createdAt: Instant,
)

data class ReorderRequest(@field:NotEmpty val orderedIds: List<Int>)

data class LikeOut(val likeCount: Int, val likedByMe: Boolean)

data class FeaturedBacktestIn(val backtestId: Int? = null)

/**
 * 전략 CRUD·공유·좋아요(원본 `routes/strategies.py`). 남의 전략은 존재 자체를 숨긴다(404, IDOR 방지).
 * 설정은 저장 전에 반드시 [StrategyConfigParser] 로 검증·기본값 채움(pydantic `model_dump()` 와 같은 값).
 */
@RestController
@RequestMapping("/api/strategies")
class StrategiesController(
    private val strategies: StrategyRepository,
    private val likes: StrategyLikeRepository,
    private val backtests: BacktestRepository,
    private val jdbc: NamedParameterJdbcTemplate,
    private val redis: StringRedisTemplate,
    private val json: JsonMapper,
) {
    private fun likeCount(id: Int): Int = jdbc.queryForObject("select count(*) from strategy_likes where strategy_id = :id", MapSqlParameterSource("id", id), Int::class.java) ?: 0

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

    @GetMapping("/shared")
    fun shared(
        @CurrentUser user: User,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) symbol: String?,
        @RequestParam(defaultValue = "likes") @Pattern(regexp = "likes|name|recent") sort: String,
    ): List<SharedStrategyOut> {
        val p = MapSqlParameterSource("me", user.id)
        var where = "s.is_shared"
        if (!q.isNullOrEmpty()) {
            where += " and s.name ilike :q"
            p.addValue("q", "%$q%")
        }
        if (!symbol.isNullOrEmpty()) {
            where += " and (s.config @> cast(:c1 as jsonb) or s.config @> cast(:c2 as jsonb))"
            p.addValue("c1", json.writeValueAsString(mapOf("symbol" to symbol)))
            p.addValue("c2", json.writeValueAsString(mapOf("universe" to listOf(symbol))))
        }
        val order = when (sort) {
            "name" -> "s.name asc"
            "recent" -> "s.shared_at desc nulls last"
            else -> "count(l.id) desc, s.shared_at desc nulls last"
        }
        val sql = """
            select s.id, s.name, s.description, s.config::text as config, s.user_id, s.created_at, u.display_name,
                   count(l.id) as like_count, coalesce(bool_or(l.user_id = :me), false) as liked_by_me,
                   b.id as bt_id, b.total_return, b.mdd, b.sharpe, b.period_start, b.period_end
            from strategies s
            join users u on u.id = s.user_id
            left join strategy_likes l on l.strategy_id = s.id
            left join backtests b on b.id = s.featured_backtest_id
            where $where
            group by s.id, u.display_name, b.id
            order by $order
        """.trimIndent()
        return jdbc.query(sql, p) { rs, _ ->
            val btId = rs.getObject("bt_id") as Int?
            SharedStrategyOut(
                id = rs.getInt("id"), name = rs.getString("name"), description = rs.getString("description"),
                config = json.readValue(rs.getString("config")), authorName = rs.getString("display_name") ?: "익명",
                likeCount = rs.getInt("like_count"), likedByMe = rs.getBoolean("liked_by_me"), isMine = rs.getInt("user_id") == user.id,
                backtest = btId?.let {
                    BacktestSummary(
                        it, (rs.getObject("total_return") as BigDecimal?)?.toDouble(), (rs.getObject("mdd") as BigDecimal?)?.toDouble(),
                        (rs.getObject("sharpe") as BigDecimal?)?.toDouble(), rs.getTimestamp("period_start").toInstant(), rs.getTimestamp("period_end").toInstant(),
                    )
                },
                createdAt = rs.getTimestamp("created_at").toInstant(),
            )
        }
    }

    /** 배열 순서대로 sort_order 0..n. 남의 것·없는 ID 는 무시. */
    @PatchMapping("/reorder")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    fun reorder(@CurrentUser user: User, @Valid @RequestBody payload: ReorderRequest) {
        val mine = strategies.findByUserId(user.id!!).associateBy { it.id }
        payload.orderedIds.forEachIndexed { order, id -> mine[id]?.sortOrder = order }
    }

    @PostMapping("/{id}/share")
    fun share(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isShared = true; sharedAt = Instant.now() })

    @DeleteMapping("/{id}/share")
    fun unshare(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isShared = false; sharedAt = null })

    @PostMapping("/{id}/favorite")
    fun favorite(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isFavorite = true })

    @DeleteMapping("/{id}/favorite")
    fun unfavorite(@CurrentUser user: User, @PathVariable id: Int) = save(strategies.owned(user, id).apply { isFavorite = false })

    /** 공유 전략(또는 내 전략)의 설정만 복제 — 공유·좋아요·즐겨찾기는 초기화, draft. */
    @PostMapping("/{id}/copy")
    @ResponseStatus(HttpStatus.CREATED)
    fun copy(@CurrentUser user: User, @PathVariable id: Int): StrategyOut {
        val src = strategies.findById(id).filter { it.isShared || it.userId == user.id }
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다.") }
        return save(Strategy(user.id!!, "${src.name} (사본)", src.description, LinkedHashMap(src.config), StrategyStatus.draft, copiedFromId = src.id))
    }

    /** 공유 전략 좋아요(인당 1회, 멱등). 내 전략엔 불가. */
    @PostMapping("/{id}/like")
    fun like(@CurrentUser user: User, @PathVariable id: Int): LikeOut {
        val s = strategies.findById(id).filter { it.isShared }.orElseThrow { ApiException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다.") }
        if (s.userId == user.id) throw ApiException(HttpStatus.BAD_REQUEST, "자신의 전략에는 좋아요를 누를 수 없습니다.")
        val exists = jdbc.queryForObject(
            "select exists(select 1 from strategy_likes where strategy_id = :s and user_id = :u)",
            MapSqlParameterSource("s", id).addValue("u", user.id), Boolean::class.java,
        ) == true
        if (!exists) likes.save(StrategyLike(id, user.id!!))
        return LikeOut(likeCount(id), true)
    }

    @DeleteMapping("/{id}/like")
    fun unlike(@CurrentUser user: User, @PathVariable id: Int): LikeOut {
        jdbc.update("delete from strategy_likes where strategy_id = :s and user_id = :u", MapSqlParameterSource("s", id).addValue("u", user.id))
        return LikeOut(likeCount(id), false)
    }

    /** 대표 백테스트 지정/해제. 지정하는 백테스트는 이 전략 소속이어야 한다. */
    @PutMapping("/{id}/featured-backtest")
    fun featured(@CurrentUser user: User, @PathVariable id: Int, @RequestBody payload: FeaturedBacktestIn): StrategyOut {
        val s = strategies.owned(user, id)
        payload.backtestId?.let { bid ->
            backtests.findById(bid).filter { it.strategyId == id }.orElseThrow { ApiException(HttpStatus.NOT_FOUND, "백테스트를 찾을 수 없습니다.") }
        }
        s.featuredBacktestId = payload.backtestId
        return save(s)
    }

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

data class KisCredentialsIn(
    @field:Pattern(regexp = "kis|toss") val broker: String = "kis",
    @field:Size(min = 1) val kisAppKey: String,
    @field:Size(min = 1) val kisAppSecret: String,
    @field:Size(min = 1, max = 32) val kisAccountNo: String,
)

data class KisHealth(val broker: String, val env: String, val isPaperTrading: Boolean, val tokenIssued: Boolean, val message: String)

data class QuoteOut(
    val symbol: String, val price: Double, val change: Double, val changeRate: Double, val volume: Long,
    val high: Double, val low: Double, val open: Double, val currency: String,
)

/** 증권사 자격증명 등록·연동 검증·현재가(원본 `routes/kis.py`). 키·시크릿은 암호화 저장. */
@RestController
@RequestMapping("/api/kis")
class KisController(
    private val users: UserRepository,
    private val cipher: CredentialCipher,
    private val props: QuantfolioProperties,
    private val brokers: BrokerCredentials,
) {
    private val log = org.slf4j.LoggerFactory.getLogger(javaClass)

    private fun client(user: User): BrokerClient = try {
        brokers.forUser(user)
    } catch (e: BrokerError) {
        throw ApiException(HttpStatus.BAD_REQUEST, e.message ?: "")
    }

    /** 등록된 자격증명으로 토큰을 발급해 연동을 검증. 토스는 모의투자가 없어 항상 prod. */
    @GetMapping("/health")
    fun health(@CurrentUser user: User): KisHealth {
        val c = client(user)
        val (env, paper) = if (user.broker == "toss") "prod" to false else props.kis.env to props.kis.isPaperTrading
        return try {
            c.verifyConnection()
            KisHealth(user.broker, env, paper, true, "토큰 발급 성공 — 연동 정상")
        } catch (e: BrokerError) {
            log.warn("증권사 연동 검증 실패(broker={}): {}", user.broker, e.message)
            KisHealth(user.broker, env, paper, false, "연동 실패: ${e.message}")
        }
    }

    /** 현재가. 통합 시세(토스) 자격증명이 있으면 토스로 국내·해외 통합, 없으면 주문 브로커(국내 한정). */
    @GetMapping("/quote/{symbol}")
    fun quote(@CurrentUser user: User, @PathVariable symbol: String): QuoteOut {
        val c = brokers.quoteClient(user) ?: client(user)
        val q = try {
            c.getQuote(symbol)
        } catch (e: BrokerError) {
            throw ApiException(HttpStatus.BAD_GATEWAY, e.message ?: "")
        }
        return QuoteOut(q.symbol, q.price.toDouble(), q.change.toDouble(), q.changeRate.toDouble(), q.volume, q.high.toDouble(), q.low.toDouble(), q.open.toDouble(), q.currency)
    }

    @PutMapping("/credentials")
    @Transactional
    fun credentials(@CurrentUser user: User, @Valid @RequestBody payload: KisCredentialsIn): UserOut {
        val current = users.findForCredentialUpdate(user.id!!) ?: throw ApiException(HttpStatus.NOT_FOUND, "사용자를 찾을 수 없습니다.")
        current.broker = payload.broker
        current.kisAppKey = cipher.encrypt(payload.kisAppKey)
        current.kisAppSecret = cipher.encrypt(payload.kisAppSecret)
        current.kisAccountNo = payload.kisAccountNo
        return users.save(current).toOut(props)
    }

    /** 통합 시세(국내+해외)용 토스 자격증명. 주문 브로커와 독립 — payload.broker 는 무시. */
    @PutMapping("/toss-quote")
    fun tossQuote(@CurrentUser user: User, @Valid @RequestBody payload: KisCredentialsIn): UserOut {
        user.tossAppKey = cipher.encrypt(payload.kisAppKey)
        user.tossAppSecret = cipher.encrypt(payload.kisAppSecret)
        user.tossAccountNo = payload.kisAccountNo
        return users.save(user).toOut(props)
    }
}
