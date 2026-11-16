package com.quantfolio.domain.trading

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

/** 주문 상태. 이름이 곧 DB·이벤트 문자열(원본 `OrderStatus` 값)이라 소문자다. */
@Suppress("EnumEntryName")
enum class OrderStatus { pending, submitted, partial, filled, cancelled, rejected }

@Entity
@Table(name = "orders")
class Order(
    @Column(nullable = false) var userId: Int,
    var strategyId: Int? = null,
    @Column(nullable = false) var symbol: String,
    /** "buy" | "sell" */
    @Column(nullable = false) var side: String,
    @Column(nullable = false) var qty: BigDecimal,
    var price: BigDecimal? = null,
    @Column(nullable = false) var orderType: String = "limit",
    var kisOrderId: String? = null,
    var kisOrderOrgNo: String? = null,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var status: OrderStatus = OrderStatus.pending,
    var reason: String? = null,
    @Column(nullable = false, unique = true) var idempotencyKey: String,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
    @Column(updatable = false, length = 64) val brokerAccountKey: String? = null,
    /**
     * 종결 시각 — 더 체결될 수 없고 기록된 체결이 최종. null = 아직 정합 대상. 정합의 조건부 update 로만 쓴다
     * (`updatable = false`: 낡은 엔티티 save 가 종결을 지우거나 덮지 않게).
     */
    @Column(updatable = false) val closedAt: Instant? = null,
)

@Entity
@Table(name = "executions")
class Execution(
    @Column(nullable = false) var orderId: Int,
    var strategyId: Int? = null,
    @Column(nullable = false) var filledQty: BigDecimal,
    @Column(nullable = false) var filledPrice: BigDecimal,
    @Column(nullable = false) var fee: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var executedAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
    /** 계좌별 포지션에 실제 반영한 수량. 계좌별 포지션 도입 이전의 체결은 null. */
    @Column(updatable = false, precision = 18, scale = 4) val accountPositionQty: BigDecimal? = null,
)

@Entity
@Table(name = "positions")
class Position(
    @Column(nullable = false) var userId: Int,
    var strategyId: Int? = null,
    @Column(nullable = false) var symbol: String,
    @Column(nullable = false) var qty: BigDecimal = BigDecimal.ZERO,
    @Column(nullable = false) var avgPrice: BigDecimal = BigDecimal.ZERO,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
    @Column(updatable = false, length = 64) val brokerAccountKey: String? = null,
) {
    @UpdateTimestamp @Column(nullable = false) var updatedAt: Instant? = null
}

@Entity
@Table(name = "risk_limits")
class RiskLimit(
    @Column(nullable = false) var userId: Int,
    var strategyId: Int? = null,
    var maxPositionSize: BigDecimal? = null,
    var dailyLossLimit: BigDecimal? = null,
    var stopLossPct: BigDecimal? = null,
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface OrderRepository : JpaRepository<Order, Int> {
    fun existsByIdempotencyKey(key: String): Boolean
}

interface ExecutionRepository : JpaRepository<Execution, Int>

interface PositionRepository : JpaRepository<Position, Int> {
    /** strategyId 가 null 이면 strategy_id IS NULL 로 찾는다(Spring Data 파생 쿼리 규칙). */
    fun findByUserIdAndBrokerAccountKeyAndStrategyIdAndSymbol(userId: Int, brokerAccountKey: String, strategyId: Int?, symbol: String): Position?
}

interface RiskLimitRepository : JpaRepository<RiskLimit, Int>
