package com.quantfolio.domain.backtest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.Instant

@Entity
@Table(name = "backtests")
class Backtest(
    @Column(nullable = false) var strategyId: Int,
    @Column(nullable = false) var periodStart: Instant,
    @Column(nullable = false) var periodEnd: Instant,
    var totalReturn: BigDecimal? = null,
    var mdd: BigDecimal? = null,
    var sharpe: BigDecimal? = null,
    @JdbcTypeCode(SqlTypes.JSON) var result: Map<String, Any?>? = null,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface BacktestRepository : JpaRepository<Backtest, Int>
