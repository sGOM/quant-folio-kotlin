package com.quantfolio.market.store

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** 원장(`FetchLedger`) 키 — DART 재무 등이 조회 인자로 만든다. */
object CacheKeys {
    private val YMD = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** 조회 인자 → 결정적 문자열. 목록형 인자는 호출 순서가 달라도 같은 조회이므로 정렬한다. */
    fun of(vararg parts: Any?): String = parts.joinToString("|") { p ->
        when (p) {
            is Collection<*> -> p.map { it.toString() }.sorted().joinToString(",")
            is LocalDate -> p.format(YMD)
            else -> p.toString()
        }
    }
}
