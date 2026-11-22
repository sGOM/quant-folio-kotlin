package com.quantfolio.market.symbols

import com.quantfolio.market.kis.MasterListing
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

data class SymbolInfo(val code: String, val name: String, val nameEn: String, val market: String)

/**
 * 종목 검색 카탈로그(원본 `services/symbols.py`). 이름의 신뢰 소스 우선순위: 내장 seed → KIS 종목마스터(KOSPI·KOSDAQ).
 * 마스터가 하나도 안 되면 seed 만 돌려주고 **캐시하지 않는다**(다음 호출에서 다시 시도).
 */
@Component
class SymbolCatalog(private val master: MasterListing) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile private var cache: List<SymbolInfo>? = null

    fun catalog(): List<SymbolInfo> {
        cache?.let { return it }
        val (catalog, externalOk) = build()
        if (!externalOk) {
            log.warn("종목 카탈로그 KIS 마스터 실패 — seed({}) 만 사용, 캐시 보류", catalog.size)
            return catalog
        }
        cache = catalog
        return catalog
    }

    fun nameMap(): Map<String, String> = catalog().associate { it.code to it.name }

    /** 코드 일치 0 · 코드 접두 1 · 이름/영문 접두 2 · 이름/영문 포함 3 · 코드 포함 4, (점수, 코드) 순. */
    fun search(query: String, limit: Int = 20): List<SymbolInfo> {
        val q = query.trim().lowercase()
        if (q.isEmpty()) return emptyList()
        return catalog().mapNotNull { item ->
            val code = item.code.lowercase()
            val name = item.name.lowercase()
            val en = item.nameEn.lowercase()
            val score = when {
                code == q -> 0
                code.startsWith(q) -> 1
                name.startsWith(q) || (en.isNotEmpty() && en.startsWith(q)) -> 2
                q in name || (en.isNotEmpty() && q in en) -> 3
                q in code -> 4
                else -> return@mapNotNull null
            }
            score to item
        }.sortedWith(compareBy({ it.first }, { it.second.code })).take(limit).map { it.second }
    }

    private fun build(): Pair<List<SymbolInfo>, Boolean> {
        val byCode = LinkedHashMap<String, SymbolInfo>()
        SEED.forEach { (code, name, en, market) -> byCode[code] = SymbolInfo(code, name, en, market) }
        var externalOk = false
        for (market in MARKETS) {
            try {
                val rows = master.fetch(market)
                rows.forEach { row ->
                    val code = row.symbol.trim()
                    if (code.length != 6 || !code.all { it.isDigit() } || row.name.isEmpty()) return@forEach
                    val existing = byCode[code]
                    if (existing == null) byCode[code] = SymbolInfo(code, row.name, "", market)
                    else if (existing.market.isEmpty()) byCode[code] = existing.copy(market = market)
                }
                if (rows.isNotEmpty()) externalOk = true
                log.info("종목 카탈로그: KIS 마스터 {} {}개 병합", market, rows.size)
            } catch (e: Exception) {
                log.warn("종목 카탈로그: KIS 마스터 {} 조회 실패 — 건너뜀: {}", market, e.message)
            }
        }
        return byCode.values.toList() to externalOk
    }

    private data class Seed(val code: String, val name: String, val nameEn: String, val market: String)

    private companion object {
        val MARKETS = listOf("KOSPI", "KOSDAQ")

        /** 외부 소스 장애 시에도 검색이 동작하도록 내장한 주요 종목(원본 seed 그대로). */
        val SEED = listOf(
            Seed("005930", "삼성전자", "Samsung Electronics", "KOSPI"), Seed("000660", "SK하이닉스", "SK Hynix", "KOSPI"),
            Seed("373220", "LG에너지솔루션", "LG Energy Solution", "KOSPI"), Seed("207940", "삼성바이오로직스", "Samsung Biologics", "KOSPI"),
            Seed("005380", "현대차", "Hyundai Motor", "KOSPI"), Seed("000270", "기아", "Kia", "KOSPI"),
            Seed("005935", "삼성전자우", "Samsung Electronics Pref", "KOSPI"), Seed("068270", "셀트리온", "Celltrion", "KOSPI"),
            Seed("105560", "KB금융", "KB Financial Group", "KOSPI"), Seed("055550", "신한지주", "Shinhan Financial Group", "KOSPI"),
            Seed("035420", "NAVER", "Naver", "KOSPI"), Seed("035720", "카카오", "Kakao", "KOSPI"),
            Seed("012330", "현대모비스", "Hyundai Mobis", "KOSPI"), Seed("028260", "삼성물산", "Samsung C&T", "KOSPI"),
            Seed("006400", "삼성SDI", "Samsung SDI", "KOSPI"), Seed("051910", "LG화학", "LG Chem", "KOSPI"),
            Seed("003670", "포스코퓨처엠", "POSCO Future M", "KOSPI"), Seed("005490", "POSCO홀딩스", "POSCO Holdings", "KOSPI"),
            Seed("015760", "한국전력", "KEPCO", "KOSPI"), Seed("017670", "SK텔레콤", "SK Telecom", "KOSPI"),
            Seed("030200", "KT", "KT", "KOSPI"), Seed("033780", "KT&G", "KT&G", "KOSPI"),
            Seed("066570", "LG전자", "LG Electronics", "KOSPI"), Seed("003550", "LG", "LG Corp", "KOSPI"),
            Seed("034730", "SK", "SK Inc", "KOSPI"), Seed("096770", "SK이노베이션", "SK Innovation", "KOSPI"),
            Seed("009150", "삼성전기", "Samsung Electro-Mechanics", "KOSPI"), Seed("032830", "삼성생명", "Samsung Life Insurance", "KOSPI"),
            Seed("086790", "하나금융지주", "Hana Financial Group", "KOSPI"), Seed("316140", "우리금융지주", "Woori Financial Group", "KOSPI"),
            Seed("000810", "삼성화재", "Samsung Fire & Marine", "KOSPI"), Seed("011200", "HMM", "HMM", "KOSPI"),
            Seed("010130", "고려아연", "Korea Zinc", "KOSPI"), Seed("009830", "한화솔루션", "Hanwha Solutions", "KOSPI"),
            Seed("011170", "롯데케미칼", "Lotte Chemical", "KOSPI"), Seed("259960", "크래프톤", "Krafton", "KOSPI"),
            Seed("036570", "엔씨소프트", "NCSoft", "KOSPI"), Seed("251270", "넷마블", "Netmarble", "KOSPI"),
            Seed("018260", "삼성에스디에스", "Samsung SDS", "KOSPI"), Seed("010950", "S-Oil", "S-Oil", "KOSPI"),
            Seed("090430", "아모레퍼시픽", "Amorepacific", "KOSPI"), Seed("051900", "LG생활건강", "LG H&H", "KOSPI"),
            Seed("024110", "기업은행", "Industrial Bank of Korea", "KOSPI"), Seed("267260", "HD현대일렉트릭", "HD Hyundai Electric", "KOSPI"),
            Seed("329180", "HD현대중공업", "HD Hyundai Heavy Industries", "KOSPI"), Seed("042660", "한화오션", "Hanwha Ocean", "KOSPI"),
            Seed("064350", "현대로템", "Hyundai Rotem", "KOSPI"), Seed("012450", "한화에어로스페이스", "Hanwha Aerospace", "KOSPI"),
            Seed("011070", "LG이노텍", "LG Innotek", "KOSPI"), Seed("097950", "CJ제일제당", "CJ CheilJedang", "KOSPI"),
            Seed("034020", "두산에너빌리티", "Doosan Enerbility", "KOSPI"),
            Seed("009540", "HD한국조선해양", "HD Korea Shipbuilding & Offshore Engineering", "KOSPI"),
            Seed("161390", "한국타이어앤테크놀로지", "Hankook Tire & Technology", "KOSPI"), Seed("267250", "HD현대", "HD Hyundai", "KOSPI"),
            Seed("010140", "삼성중공업", "Samsung Heavy Industries", "KOSPI"), Seed("352820", "하이브", "HYBE", "KOSPI"),
            Seed("323410", "카카오뱅크", "KakaoBank", "KOSPI"), Seed("247540", "에코프로비엠", "Ecopro BM", "KOSDAQ"),
            Seed("086520", "에코프로", "Ecopro", "KOSDAQ"), Seed("091990", "셀트리온헬스케어", "Celltrion Healthcare", "KOSDAQ"),
            Seed("196170", "알테오젠", "Alteogen", "KOSDAQ"), Seed("028300", "HLB", "HLB", "KOSDAQ"),
            Seed("066970", "엘앤에프", "L&F", "KOSDAQ"), Seed("058470", "리노공업", "Leeno Industrial", "KOSDAQ"),
            Seed("357780", "솔브레인", "Soulbrain", "KOSDAQ"), Seed("293490", "카카오게임즈", "Kakao Games", "KOSDAQ"),
            Seed("263750", "펄어비스", "Pearl Abyss", "KOSDAQ"), Seed("041510", "에스엠", "SM Entertainment", "KOSDAQ"),
            Seed("035900", "JYP Ent.", "JYP Entertainment", "KOSDAQ"), Seed("253450", "스튜디오드래곤", "Studio Dragon", "KOSDAQ"),
        )
    }
}
