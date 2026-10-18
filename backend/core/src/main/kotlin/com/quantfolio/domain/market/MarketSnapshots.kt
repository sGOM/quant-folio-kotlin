package com.quantfolio.domain.market

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate

/** 업종분류 PIT 스냅샷(분기초 적재). KRX 는 '현재' 분류만 주므로 시점별 분류는 여기서만 얻는다. */
@Entity
@Table(name = "sector_map_snapshots")
class SectorMapSnapshot(
    @Column(nullable = false) var symbol: String,
    @Column(nullable = false) var sector: String,
    @Column(nullable = false) var snapshotDate: LocalDate,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

/** KIS 종목마스터 일별 스냅샷. raw 는 시장별 60~70개 필드를 무손실 보관. */
@Entity
@Table(name = "kis_stock_master_snapshots")
class KisStockMasterSnapshot(
    @Column(nullable = false) var tradeDate: LocalDate,
    @Column(nullable = false) var symbol: String,
    @Column(nullable = false) var market: String,
    @Column(nullable = false) var name: String,
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false) var raw: Map<String, Any?>,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
)

interface SectorMapSnapshotRepository : JpaRepository<SectorMapSnapshot, Int> {
    @Query("select max(s.snapshotDate) from SectorMapSnapshot s where s.snapshotDate <= :asOf")
    fun latestDateOnOrBefore(asOf: LocalDate): LocalDate?

    fun findBySnapshotDate(snapshotDate: LocalDate): List<SectorMapSnapshot>

    fun countBySnapshotDate(snapshotDate: LocalDate): Long

    @Transactional
    @Modifying
    @Query("delete from SectorMapSnapshot s where s.snapshotDate = :snapshotDate")
    fun deleteBySnapshotDate(snapshotDate: LocalDate): Int
}

interface KisStockMasterSnapshotRepository : JpaRepository<KisStockMasterSnapshot, Int>
