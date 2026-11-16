package com.quantfolio.broker

import java.security.MessageDigest
import java.util.HexFormat

/** 주문·조회에 쓰는 계좌의 비교 키. 주문에는 원문 계좌번호나 인증 비밀을 저장하지 않는다. */
object BrokerAccountKey {
    internal fun kisNumber(account: String?): String? = account?.replace("-", "")?.trim()?.takeIf { it.length >= 10 }?.take(10)

    fun kis(account: String?, paper: Boolean): String? = kisNumber(account)?.let {
        digest("kis", if (paper) "vts" else "prod", it)
    }

    fun kisNotice(account: String?, paper: Boolean): String? {
        val number = account?.replace("-", "")?.trim()
        if (number == null || number.length != 10 || number.any { it !in '0'..'9' }) return null
        return kis(number, paper)
    }

    // accountSeq는 토큰으로 접근 가능한 계좌 목록에서 얻으므로 클라이언트의 범위도 포함한다.
    internal fun toss(clientId: String, account: String?): String? = account?.takeIf { it.isNotBlank() }?.let {
        digest("toss", clientId, it)
    }

    private fun digest(vararg parts: String): String {
        val value = parts.joinToString("") { "${it.length}:$it" }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)))
    }
}
