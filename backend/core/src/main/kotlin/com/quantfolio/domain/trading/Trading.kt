package com.quantfolio.domain.trading

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

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
    /** "pending" | "submitted" | "partial" | "filled" | "cancelled" | "rejected" */
    @Column(nullable = false) var status: String = "pending",
    var reason: String? = null,
    @Column(nullable = false, unique = true) var idempotencyKey: String,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
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

interface OrderRepository : JpaRepository<Order, Int>

interface ExecutionRepository : JpaRepository<Execution, Int>

interface PositionRepository : JpaRepository<Position, Int>

interface RiskLimitRepository : JpaRepository<RiskLimit, Int>
