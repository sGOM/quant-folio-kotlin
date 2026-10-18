package com.quantfolio.domain.alert

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** 발행된 모든 알림의 영속 사본. userId null = 전역/운영 알림. */
@Entity
@Table(name = "alerts")
class Alert(
    var userId: Int? = null,
    @Column(nullable = false) var strategyId: Int,
    /** "warning" | "critical" */
    @Column(nullable = false) var severity: String,
    var code: String? = null,
    @Column(nullable = false) var message: String,
    @Column(nullable = false) var isRead: Boolean = false,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface AlertRepository : JpaRepository<Alert, Int>
