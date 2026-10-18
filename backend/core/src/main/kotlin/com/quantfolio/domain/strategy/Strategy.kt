package com.quantfolio.domain.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.annotations.UpdateTimestamp
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "strategies")
class Strategy(
    @Column(nullable = false) var userId: Int,
    @Column(nullable = false) var name: String,
    var description: String? = null,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) var config: Map<String, Any?> = emptyMap(),
    /** "draft" | "backtested" | "live" */
    @Column(nullable = false) var status: String = "draft",
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

interface StrategyRepository : JpaRepository<Strategy, Int>

interface StrategyLikeRepository : JpaRepository<StrategyLike, Int>
