package com.quantfolio.domain.alert

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/** 알림 등급. 이름이 곧 DB·이벤트 문자열이라 소문자다. */
@Suppress("EnumEntryName")
enum class AlertSeverity { warning, critical }

/** 발행된 모든 알림의 영속 사본. userId null = 전역/운영 알림. */
@Entity
@Table(name = "alerts")
class Alert(
    var userId: Int? = null,
    @Column(nullable = false) var strategyId: Int,
    @Enumerated(EnumType.STRING) @Column(nullable = false) var severity: AlertSeverity,
    var code: String? = null,
    @Column(nullable = false) var message: String,
    @Column(nullable = false) var isRead: Boolean = false,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface AlertRepository : JpaRepository<Alert, Int>
