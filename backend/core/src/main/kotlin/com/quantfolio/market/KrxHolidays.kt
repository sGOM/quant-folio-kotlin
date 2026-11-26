package com.quantfolio.market

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * KRX 가 공지한 평일 휴장일(설정 `quantfolio.market.krx-holidays`, `yyyy-MM-dd` 쉼표 구분). 사람이 매년 KRX 공지로 갱신한다.
 * KIS 모의(vts)는 휴장일 조회를 못 하고, Open API 는 휴장일과 미공개일이 같은 빈 응답이라 이 목록이 유일한 사전 근거다.
 * 형식이 틀리면 기동 시 실패한다(틱 도중이 아니라).
 */
@Component
class KrxHolidays(@Value("\${quantfolio.market.krx-holidays:}") raw: String = "") {
    val days: Set<LocalDate> = raw.split(',').map { it.trim() }.filter { it.isNotEmpty() }.mapTo(HashSet()) { LocalDate.parse(it) }

    operator fun contains(d: LocalDate) = d in days
}
