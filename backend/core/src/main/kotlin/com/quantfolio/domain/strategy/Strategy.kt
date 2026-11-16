package com.quantfolio.domain.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** 전략 상태. 이름이 곧 DB·API 문자열(원본 `StrategyStatus` 값)이라 소문자다. */
@Suppress("EnumEntryName")
enum class StrategyStatus { draft, backtested, live }

@Entity
@Table(name = "strategies")
class Strategy(
    @Column(nullable = false) var userId: Int,
    @Column(nullable = false) var name: String,
    var description: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) var config: Map<String, Any?> = emptyMap(),
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: StrategyStatus = StrategyStatus.draft,
    var featuredBacktestId: Int? = null,
    @Column(nullable = false) var isShared: Boolean = false,
    var sharedAt: Instant? = null,
    @Column(nullable = false) var isFavorite: Boolean = false,
    @Column(nullable = false) var sortOrder: Int = 0,
    var copiedFromId: Int? = null,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
) {
    @UpdateTimestamp @Column(nullable = false) var updatedAt: Instant? = null
}

@Entity
@Table(name = "strategy_likes")
class StrategyLike(
    @Column(nullable = false) var strategyId: Int,
    @Column(nullable = false) var userId: Int,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface StrategyRepository : JpaRepository<Strategy, Int> {
    fun findByUserId(userId: Int): List<Strategy>

    /** status 만 바꾼다(원본 dirty tracking 의 UPDATE status, updated_at). 오래 전에 읽은 엔티티를 save 로 병합하면 그사이 수정이 덮인다. */
    @org.springframework.data.jpa.repository.Modifying
    @org.springframework.data.jpa.repository.Query("update Strategy s set s.status = 'backtested', s.updatedAt = CURRENT_TIMESTAMP where s.id = :id and s.status = 'draft'")
    fun markBacktested(id: Int): Int
}

interface StrategyLikeRepository : JpaRepository<StrategyLike, Int>
