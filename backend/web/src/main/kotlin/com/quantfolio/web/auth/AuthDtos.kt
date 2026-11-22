package com.quantfolio.web.auth

import com.quantfolio.broker.BrokerCredentials
import com.quantfolio.domain.user.User

/** 외부 응답용 사용자 정보 — 자격증명 원문·암호문은 절대 담지 않는다. */
data class UserOut(
    val id: Int,
    val email: String,
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
        broker = broker.ifEmpty { "kis" },
        kisAccountNo = credentials?.accountNo,
        hasKisCredentials = credentials != null,
        hasTossQuote = brokers.hasTossQuote(this),
    )
}
