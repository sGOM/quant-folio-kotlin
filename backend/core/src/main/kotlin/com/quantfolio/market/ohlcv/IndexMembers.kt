package com.quantfolio.market.ohlcv

import java.time.LocalDate

/** 지수 구성종목 조회(테스트에서 교체할 수 있게 분리). [com.quantfolio.market.krx.KrxIndexService] 가 구현한다. */
fun interface IndexMembers {
    fun indexMembers(asOf: LocalDate, index: String): List<String>
}
