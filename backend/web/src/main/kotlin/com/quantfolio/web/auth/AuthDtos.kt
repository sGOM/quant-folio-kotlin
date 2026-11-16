package com.quantfolio.web.auth

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.domain.user.User
import jakarta.validation.constraints.Size

data class ProfileUpdate(
    @field:Size(max = 50, message = "닉네임은 50자 이하여야 합니다.") val displayName: String? = null,
)

/** 외부 응답용 사용자 정보 — 자격증명 원문·암호문은 절대 담지 않는다. */
data class UserOut(
    val id: Int,
    val email: String,
    /** 공유 전략 목록 닉네임. 미설정이면 null(화면에서 '익명'). */
    val displayName: String?,
    val broker: String,
    val kisAccountNo: String?,
    val hasKisCredentials: Boolean,
    /** 소유자의 서버 관리 통합 시세(토스) 자격증명 설정 여부. */
    val hasTossQuote: Boolean,
)

fun User.toOut(brokers: BrokerCredentials): UserOut {
    val credentials = brokers.resolve(this)
    return UserOut(
        id = id!!,
        email = email,
        displayName = displayName,
        broker = broker.ifEmpty { "kis" },
        kisAccountNo = credentials?.accountNo,
        hasKisCredentials = credentials != null,
        hasTossQuote = brokers.hasTossQuote(this),
    )
}
