package com.quantfolio.market.official

import com.quantfolio.market.datagokr.CashDividend
import com.quantfolio.market.datagokr.DataGoKrDividendApi
import com.quantfolio.market.store.DividendEvent
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.FetchLedger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** [stored] 는 (종목, 기준일) 중복을 합친 뒤 저장한 행 수, [conflicts] 는 같은 키에 금액이 달랐던 건수(마지막 값 채택). */
data class DividendIngestReport(val pages: Int, val fetched: Int, val skipped: Int, val stored: Int, val conflicts: Int)

/**
 * 공공데이터포털 주식배당정보 전체 이력 적재. 전 페이지를 **다 받은 뒤** 한 번에 쓰고 원장은 마지막에 남긴다 —
 * 한 페이지라도 실패하면 아무것도 쓰지 않고 예외를 올린다(일부만 받은 상태로 '적재 완료'가 되면
 * [Valuation] 이 배당 없는 종목을 0 으로 단정한다). 공지일을 모르므로 announcedOn 은 null.
 * 원장: source `datagokr`, key `dividends_full`.
 */
@Component
class DividendIngest(
    private val api: DataGoKrDividendApi,
    private val store: DividendStore,
    private val ledger: FetchLedger,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun run(): DividendIngestReport {
        val all = mutableListOf<CashDividend>()
        var fetched = 0
        var skipped = 0
        var pageNo = 1
        while (true) {
            val p = api.page(pageNo)
            all += p.dividends
            fetched += p.rawCount
            skipped += p.skipped
            if (p.rawCount == 0 || fetched >= p.totalCount) break
            pageNo++
        }
        val byKey = LinkedHashMap<Pair<String, java.time.LocalDate>, CashDividend>()
        var conflicts = 0
        for (d in all) {
            val key = d.symbol to d.recordDate
            val prev = byKey[key]
            if (prev != null && prev.dps != d.dps) {
                conflicts++
                log.warn("배당 중복 금액 불일치 {} {}: {} → {}(마지막 값 채택)", d.symbol, d.recordDate, prev.dps, d.dps)
            }
            byKey.remove(key) // 마지막 값이 이기도록 순서도 뒤로
            byKey[key] = d
        }
        store.write(byKey.values.map { DividendEvent(it.symbol, it.recordDate, it.dps, null, SOURCE) })
        ledger.put(SOURCE, LEDGER_KEY, byKey.size, true)
        val report = DividendIngestReport(pageNo, fetched, skipped, byKey.size, conflicts)
        log.info("배당 전체 적재 완료: {}", report)
        return report
    }

    companion object {
        const val SOURCE = "datagokr"
        const val LEDGER_KEY = "dividends_full"
    }
}
