package com.quantfolio.market.dart

import java.time.LocalDate

/**
 * 재무제표 원계정 → 파생지표(원본 `opendart.py` 의 순수 계산부). 삼성전자·현대차·NAVER 실측 수치로 검증된 규칙이다.
 * 계정은 **account_id(IFRS 표준 태그) 우선**, 없을 때만 계정명으로 폴백한다(구형 공시는 id 가 비어 있다).
 * 값의 단위는 원. 결측은 null(0 이 아니다).
 */
object DartMetrics {
    const val REPORT_Q1 = "11013"
    const val REPORT_HALF = "11012"
    const val REPORT_Q3 = "11014"
    const val REPORT_ANNUAL = "11011"

    val FLOW_FIELDS = listOf("op_income", "net_income", "fcf", "cfo", "revenue", "gross_profit")
    val STOCK_FIELDS = listOf("assets", "liabilities", "equity", "current_assets", "current_liabilities")
    private val KEYS = listOf(
        "roe", "debt_ratio", "op_income", "net_income", "fcf", "roa", "cfo", "assets", "liabilities", "equity",
        "current_assets", "current_liabilities", "revenue", "gross_profit",
    )

    /** 순이익 계정명 폴백에서 제외할 말 — 지배기업 귀속분·법인세차감전 등은 전체 순이익이 아니다. */
    private val NET_INCOME_EXCLUDE = listOf("귀속", "지배기업", "차감전", "비지배")

    private val PERIODIC_REPORT = Regex("(분기보고서|반기보고서|사업보고서)\\s*\\((\\d{4})\\.(\\d{2})\\)")
    private val MONTH_TO_REPORT = mapOf("03" to REPORT_Q1, "06" to REPORT_HALF, "09" to REPORT_Q3, "12" to REPORT_ANNUAL)

    fun toNumber(raw: Any?): Double? {
        val s = raw?.toString()?.trim()?.replace(",", "") ?: return null
        if (s == "" || s == "-") return null
        return s.toDoubleOrNull()
    }

    /** 해당 재무제표(sj_div)에서 계정 하나. id 일치·접두 일치는 즉시 반환, 이름 폴백은 첫 후보만 기억. */
    private fun pick(
        accounts: List<Map<String, Any?>>,
        sjDiv: String,
        accountId: String? = null,
        idPrefix: String? = null,
        nameContains: String? = null,
        nameExclude: List<String> = emptyList(),
    ): Double? {
        var fallback: Double? = null
        var fallbackSet = false
        for (row in accounts) {
            if (row["sj_div"] != sjDiv) continue
            val rid = row["account_id"]?.toString()?.trim().orEmpty()
            val nm = row["account_nm"]?.toString().orEmpty()
            if (accountId != null && rid == accountId) return toNumber(row["thstrm_amount"])
            if (idPrefix != null && rid.startsWith(idPrefix)) return toNumber(row["thstrm_amount"])
            if (nameContains != null && nameContains in nm && nameExclude.none { it in nm } && !fallbackSet) {
                // 원본은 `fallback is None` 일 때만 채운다 — 값이 None 인 행이면 다음 후보로 넘어간다.
                fallback = toNumber(row["thstrm_amount"])
                fallbackSet = fallback != null
            }
        }
        return fallback
    }

    private fun ratios(netIncome: Double?, equity: Double?, liabilities: Double?, assets: Double?): Triple<Double?, Double?, Double?> {
        val roe = if (netIncome != null && equity != null && equity > 0) netIncome / equity else null
        val debtRatio = if (liabilities != null && equity != null && equity > 0) liabilities / equity else null
        val roa = if (netIncome != null && assets != null && assets > 0) netIncome / assets else null
        return Triple(roe, debtRatio, roa)
    }

    fun deriveMetrics(accounts: List<Map<String, Any?>>): Map<String, Double?> {
        if (accounts.isEmpty()) return KEYS.associateWith { null }
        val equity = pick(accounts, "BS", accountId = "ifrs-full_Equity", nameContains = "자본총계")
        val liabilities = pick(accounts, "BS", accountId = "ifrs-full_Liabilities", nameContains = "부채총계")
        val assets = pick(accounts, "BS", accountId = "ifrs-full_Assets", nameContains = "자산총계")
        val opIncome = pick(accounts, "IS", accountId = "dart_OperatingIncomeLoss", nameContains = "영업이익")
            ?: pick(accounts, "CIS", accountId = "dart_OperatingIncomeLoss", nameContains = "영업이익")
        val netIncome = pick(accounts, "IS", accountId = "ifrs-full_ProfitLoss", nameContains = "당기순이익", nameExclude = NET_INCOME_EXCLUDE)
            ?: pick(accounts, "CIS", accountId = "ifrs-full_ProfitLoss", nameContains = "당기순이익", nameExclude = NET_INCOME_EXCLUDE)
        val ocf = pick(accounts, "CF", accountId = "ifrs-full_CashFlowsFromUsedInOperatingActivities", nameContains = "영업활동현금흐름")
        val capexParts = listOf(
            pick(accounts, "CF", idPrefix = "ifrs-full_PurchaseOfPropertyPlantAndEquipment", nameContains = "유형자산의 취득"),
            pick(accounts, "CF", idPrefix = "ifrs-full_PurchaseOfIntangibleAssets", nameContains = "무형자산의 취득"),
        ).filterNotNull()
        val capex = if (capexParts.isEmpty()) null else capexParts.sum()
        val currentAssets = pick(accounts, "BS", accountId = "ifrs-full_CurrentAssets", nameContains = "유동자산", nameExclude = listOf("비유동"))
        val currentLiabilities = pick(accounts, "BS", accountId = "ifrs-full_CurrentLiabilities", nameContains = "유동부채", nameExclude = listOf("비유동"))
        val revenue = pick(accounts, "IS", accountId = "ifrs-full_Revenue", nameContains = "매출액")
            ?: pick(accounts, "CIS", accountId = "ifrs-full_Revenue", nameContains = "매출액")
        val grossProfit = pick(accounts, "IS", accountId = "ifrs-full_GrossProfit", nameContains = "매출총이익")
            ?: pick(accounts, "CIS", accountId = "ifrs-full_GrossProfit", nameContains = "매출총이익")
        val (roe, debtRatio, roa) = ratios(netIncome, equity, liabilities, assets)
        val fcf = if (ocf != null && capex != null) ocf - capex else null
        return linkedMapOf(
            "roe" to roe, "debt_ratio" to debtRatio, "op_income" to opIncome, "net_income" to netIncome, "fcf" to fcf,
            "roa" to roa, "cfo" to ocf, "assets" to assets, "liabilities" to liabilities, "equity" to equity,
            "current_assets" to currentAssets, "current_liabilities" to currentLiabilities, "revenue" to revenue, "gross_profit" to grossProfit,
        )
    }

    /** Piotroski F-Score(0~8, 증자 항목 제외). 계산 가능한 항목이 5개 미만이면 null. */
    fun piotroskiFScore(cur: Map<String, Double?>, prev: Map<String, Double?>): Int? {
        var score = 0
        var computable = 0
        fun ratio(a: Double?, b: Double?) = if (a == null || b == null || b == 0.0) null else a / b
        fun test(ok: Boolean?) {
            if (ok == null) return
            computable++
            if (ok) score++
        }
        val roaT = cur["roa"]
        val roaP = prev["roa"]
        val cfoT = cur["cfo"]
        test(roaT?.let { it > 0 })
        test(cfoT?.let { it > 0 })
        test(if (roaT != null && roaP != null) roaT > roaP else null)
        val cfoTa = ratio(cfoT, cur["assets"])
        test(if (cfoTa != null && roaT != null) cfoTa > roaT else null)
        val levT = ratio(cur["liabilities"], cur["assets"])
        val levP = ratio(prev["liabilities"], prev["assets"])
        test(if (levT != null && levP != null) levT < levP else null)
        val crT = ratio(cur["current_assets"], cur["current_liabilities"])
        val crP = ratio(prev["current_assets"], prev["current_liabilities"])
        test(if (crT != null && crP != null) crT > crP else null)
        val gmT = ratio(cur["gross_profit"], cur["revenue"])
        val gmP = ratio(prev["gross_profit"], prev["revenue"])
        test(if (gmT != null && gmP != null) gmT > gmP else null)
        val atT = ratio(cur["revenue"], cur["assets"])
        val atP = ratio(prev["revenue"], prev["assets"])
        test(if (atT != null && atP != null) atT > atP else null)
        return if (computable < 5) null else score
    }

    /** as-of 시점에 공시가 끝났다고 볼 수 있는 가장 최근 보고서(분기 PIT). 마감 경계: 3/31→4/1, 5/16, 8/16, 11/16. */
    fun latestReportPeriod(asOf: LocalDate): Pair<Int, String> {
        val y = asOf.year
        val md = asOf.monthValue * 100 + asOf.dayOfMonth
        return when {
            md >= 1116 -> y to REPORT_Q3
            md >= 816 -> y to REPORT_HALF
            md >= 516 -> y to REPORT_Q1
            md >= 401 -> (y - 1) to REPORT_ANNUAL
            else -> (y - 2) to REPORT_ANNUAL
        }
    }

    /** 사업보고서는 이듬해 3월 말 공시 — 4월부터 직전 연도 연간 실적을 쓸 수 있다. */
    fun announcementLaggedYear(asOf: LocalDate): Int = if (asOf.monthValue >= 4) asOf.year - 1 else asOf.year - 2

    /** TTM: 손익(flow) = 전년연간 − 전년동기 + 당해동기, 재무상태(stock) = 당해 시점값. */
    fun combineTtm(cur: Map<String, Double?>, prevSame: Map<String, Double?>, prevAnnual: Map<String, Double?>): Map<String, Double?> {
        val result = linkedMapOf<String, Double?>()
        for (f in FLOW_FIELDS) {
            val c = cur[f]
            val s = prevSame[f]
            val a = prevAnnual[f]
            result[f] = if (c != null && s != null && a != null) a - s + c else null
        }
        for (f in STOCK_FIELDS) result[f] = cur[f]
        val (roe, debtRatio, roa) = ratios(result["net_income"], result["equity"], result["liabilities"], result["assets"])
        result["roe"] = roe
        result["debt_ratio"] = debtRatio
        result["roa"] = roa
        return result
    }

    fun countLosses(netIncomes: List<Double?>): Int? {
        val vals = netIncomes.filterNotNull()
        return if (vals.isEmpty()) null else vals.count { it <= 0 }
    }

    fun yoyGrowth(cur: Double?, prev: Double?): Double? = if (cur == null || prev == null || prev <= 0) null else cur / prev - 1.0

    fun turnaroundFlag(netCur: Double?, netPrev: Double?): Double? =
        if (netCur == null || netPrev == null) null else if (netPrev <= 0 && netCur > 0) 1.0 else 0.0

    /** 정기보고서 이름(정정 포함) + 접수일 → (접수일, 사업연도, 보고서코드). 정기보고서가 아니면 null. */
    fun parsePeriodicReport(reportNm: String?, rceptDt: String?): Triple<LocalDate, Int, String>? {
        val m = PERIODIC_REPORT.find(reportNm.orEmpty()) ?: return null
        val reprt = MONTH_TO_REPORT[m.groupValues[3]] ?: return null
        val s = rceptDt?.trim().orEmpty()
        if (s.length != 8 || !s.all { it.isDigit() }) return null
        val d = runCatching { LocalDate.of(s.take(4).toInt(), s.substring(4, 6).toInt(), s.substring(6, 8).toInt()) }.getOrNull() ?: return null
        return Triple(d, m.groupValues[2].toInt(), reprt)
    }
}
