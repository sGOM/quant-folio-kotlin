package com.quantfolio.web.auth

import com.quantfolio.common.QuantfolioProperties
import com.quantfolio.domain.user.User
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class RegisterRequest(
    @field:NotBlank @field:Email(message = "올바른 이메일 주소가 아닙니다.") val email: String,
    @field:Size(min = 8, max = 128, message = "비밀번호는 8~128자여야 합니다.") val password: String,
)

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
    /** 통합 시세(토스) 자격증명 보유 — 사용자 DB 값 우선, 없으면 서버 .env 폴백(원본 resolve_quote_client). */
    val hasTossQuote: Boolean,
)

fun User.toOut(props: QuantfolioProperties) = UserOut(
    id = id!!,
    email = email,
    displayName = displayName,
    broker = broker,
    kisAccountNo = kisAccountNo,
    hasKisCredentials = !kisAppKey.isNullOrEmpty(),
    hasTossQuote = (!tossAppKey.isNullOrEmpty() && !tossAppSecret.isNullOrEmpty()) ||
        (props.toss.appKey.isNotEmpty() && props.toss.appSecret.isNotEmpty()),
)
