package com.quantfolio.domain.user

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.UpdateTimestamp
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

@Entity
@Table(name = "users")
class User(
    @Column(nullable = false, unique = true) var email: String,
    @Column(nullable = false) var passwordHash: String,
    var displayName: String? = null,
    /** "kis" | "toss" */
    @Column(nullable = false) var broker: String = "kis",
    /** 이전 DB 자격증명(Fernet 암호문)과 계좌 기록. 연결에는 서버 설정을 쓴다. */
    var kisAppKey: String? = null,
    var kisAppSecret: String? = null,
    var kisAccountNo: String? = null,
    var tossAppKey: String? = null,
    var tossAppSecret: String? = null,
    var tossAccountNo: String? = null,
    @Column(nullable = false, updatable = false) var createdAt: Instant = Instant.now(),
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) var id: Int? = null,
) {
    @UpdateTimestamp @Column(nullable = false) var updatedAt: Instant? = null
}

interface UserRepository : JpaRepository<User, Int> {
    fun findByEmail(email: String): User?
}
