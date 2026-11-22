package com.quantfolio.market.official

import com.quantfolio.market.dart.DartFinancials
import com.quantfolio.market.dart.DartMetrics
import com.quantfolio.market.store.DartFinancialStore
import com.quantfolio.market.store.DividendStore
import com.quantfolio.market.store.StockDaily
import com.quantfolio.market.store.StockDailyStore
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * KRX 가 주던 PER·PBR·배당수익률을 로컬 데이터로 직접 계산한다(백테스트가 KRX 를 부르지 않게).
 *
 * - PER = 시가총액 / 지배주주 당기순이익, PBR = 시가총액 / 지배주주 자본. 적자·자본잠식이면 null(0 아님).
 * - 재무 = 접수일 <= asOf 인 최신 사업보고서(11011), 연결 우선·별도 폴백. 접수일 > asOf 인 보고서는 못 본다(미래참조 금지).
 * - 배당수익률(%) = 최근 365일 주당 현금배당 합 / 종가 * 100. 알려진 배당이 없으면 null, 합이 0 이면 0.0.
 * - 시총·종가 = asOf 이하 가장 최근 스냅샷 행.
 */
@Component
class Valuation(
    private val daily: StockDailyStore,
    private val dart: DartFinancials,
    private val dartStore: DartFinancialStore,
    private val dividends: DividendStore,
) {
    /** 한 종목의 재무 조회 결과. 미적재와 '쓸 보고서 없음'은 다른 사건이다. */
    private sealed interface Fin {
        data class Loaded(val netIncome: Double?, val equity: Double?) : Fin
        data class NotLoaded(val error: MarketDataNotLoaded) : Fin
        data object Absent : Fin
    }

    /**
     * per·pbr·div 만 채운 행(+market), [symbols] 순서대로. asOf 이하(14일 이내) 스냅샷이 없는 종목은 세 값 모두 null.
     * 조회한 종목 전부가 DART 미적재면 [MarketDataNotLoaded] 를 그대로 올린다(일부만이면 그 종목만 null).
     */
    fun compute(asOf: LocalDate, symbols: List<String>): List<StockDaily> {
        val codes = symbols.map { it.padStart(6, '0') }.distinct()
        val snaps = daily.latestUpTo(codes, asOf, SNAPSHOT_LOOKBACK_DAYS).associateBy { it.symbol }
        if (snaps.isEmpty()) return codes.map { StockDaily(it) }
        val corps = dart.corpCodeMap() ?: throw MarketDataNotLoaded("dart_corp_map", "corp_code")
        val fins = codes.filter { it in snaps }.associateWith { c -> corps[c]?.let { financials(it, asOf) } ?: Fin.Absent }
        val queried = fins.values.filter { it !is Fin.Absent }
        if (queried.isNotEmpty() && queried.all { it is Fin.NotLoaded }) throw (queried.first() as Fin.NotLoaded).error
        val dps = dpsBySymbol(snaps.keys.toList(), asOf)
        return codes.map { c ->
            val s = snaps[c] ?: return@map StockDaily(c)
            val f = fins[c] as? Fin.Loaded
            val cap = s.marketCap?.toDouble()
            StockDaily(
                c, market = s.market,
                per = ratio(cap, f?.netIncome),
                pbr = ratio(cap, f?.equity),
                div = dps[c]?.let { d -> s.close?.takeIf { it > 0 }?.let { d / it * 100 } },
            )
        }
    }

    /** 분모가 양수일 때만. 적자·자본잠식·결측은 null. */
    private fun ratio(cap: Double?, denominator: Double?): Double? =
        if (cap != null && denominator != null && denominator > 0) cap / denominator else null

    private fun financials(corp: String, asOf: LocalDate): Fin {
        val lagged = DartMetrics.announcementLaggedYear(asOf)
        for (year in listOf(asOf.year - 1, asOf.year - 2)) {
            for (fs in listOf(DartFinancials.FS_CONSOLIDATED, DartFinancials.FS_SEPARATE)) {
                val accounts = try {
                    dart.cachedAccounts(corp, year, DartMetrics.REPORT_ANNUAL, fs, remote = false)
                } catch (e: MarketDataNotLoaded) {
                    // 공시가 끝났을 연도(<= lagged)의 공백은 진짜 미적재다 — 더 오래된 보고서로 조용히 내려가지 않는다.
                    if (year <= lagged) return Fin.NotLoaded(e)
                    break
                }
                if (accounts.isNullOrEmpty()) continue
                val received = dartStore.receiptDate(corp, year, DartMetrics.REPORT_ANNUAL, fs)
                // 접수일을 해석 못 한 행은 기존 PIT 규칙(4월부터 직전 연도)으로 판정한다.
                val known = received?.let { !it.isAfter(asOf) } ?: (year <= lagged)
                if (!known) break // 연결이 있으면 별도는 보지 않는다(firstAvailable 과 같다). 더 오래된 연도로.
                val (ni, eq) = DartMetrics.controllingNetIncomeEquity(accounts, separate = fs == DartFinancials.FS_SEPARATE)
                return Fin.Loaded(ni, eq)
            }
        }
        return Fin.Absent
    }

    /** 종목별 DPS. 알려진 배당이 한 건도 없으면 키가 없다(= unknown), 합이 0 이면 0.0. */
    private fun dpsBySymbol(codes: List<String>, asOf: LocalDate): Map<String, Double> =
        dividends.read(codes, asOf.minusDays(DIV_WINDOW_DAYS - 1), asOf)
            // ponytail: 공시일 없는 배당은 기준일 + 90일 뒤에야 알려진 것으로 본다. 2023 이전 결산배당은 기준일(12/31) 뒤에
            // 결정돼서, 공시일 없이 기준일 직후부터 쓰면 미래참조다. 상한은 실제 공시일을 못 구한 만큼의 지연이고, 공시일 적재가 늘면 줄어든다.
            .filter { e -> e.announcedOn?.let { !it.isAfter(asOf) } ?: !e.recordDate.plusDays(UNANNOUNCED_LAG_DAYS).isAfter(asOf) }
            .groupBy { it.symbol }
            .mapValues { (_, es) -> es.sumOf { it.dps } }

    companion object {
        private const val SNAPSHOT_LOOKBACK_DAYS = 14L
        private const val DIV_WINDOW_DAYS = 365L
        private const val UNANNOUNCED_LAG_DAYS = 90L
    }
}
