package com.quantfolio.news

import com.quantfolio.common.MarketClock
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import javax.xml.parsers.DocumentBuilderFactory

data class FeedItem(val title: String, val url: String, val description: String, val publishedAt: Instant?)

/**
 * 언론사 RSS/Atom 파싱·요약·종목 매칭(원본 `services/news.py` 의 순수 함수). 표시는 요약+원문 링크(본문 전재 금지).
 * 원본 의미를 그대로 둔다: XML 이 엔티티를 한 번 풀고 HTML unescape 가 한 번 더 푼다, 링크는 마지막 link 가 이긴다,
 * 본문·날짜는 처음 비지 않은 값, tz 없는 시각은 KST.
 */
object NewsFeeds {
    const val MAX_ITEMS_PER_FEED = 50
    const val SUMMARY_MAX_CHARS = 300

    private val TAG = Regex("<[^>]+>")
    private val WS = Regex("(?U)\\s+")

    /** HTML 태그·엔티티·연속 공백 제거. */
    fun stripHtml(text: String?): String = WS.replace(TAG.replace(htmlUnescape(text ?: ""), " "), " ").trim()

    /** RSS description 추출 요약. 비었거나 제목 반복이면 null, 300자 초과는 말줄임. */
    fun summarize(title: String, description: String): String? {
        var text = stripHtml(description)
        if (text.isEmpty() || text == stripHtml(title)) return null
        if (text.codePointCount(0, text.length) > SUMMARY_MAX_CHARS) {
            val end = text.offsetByCodePoints(0, SUMMARY_MAX_CHARS - 1)
            text = text.substring(0, end).trimEnd() + "…"
        }
        return text
    }

    fun parseFeed(xml: String): List<FeedItem> = parseFeed(xml.toByteArray(Charsets.UTF_8), declared = false)

    /** RSS 2.0(channel/item)·Atom(feed/entry). 제목·링크 없는 항목은 버리고 피드당 50건까지. */
    fun parseFeed(bytes: ByteArray, declared: Boolean = true): List<FeedItem> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) // 외부 엔티티(XXE) 차단
            isExpandEntityReferences = false
        }
        val doc = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
        val out = mutableListOf<FeedItem>()
        fun walk(node: Element): Boolean {
            if (localName(node) in setOf("item", "entry")) {
                var title = ""
                var url = ""
                var description = ""
                var published: String? = null
                for (child in elements(node)) {
                    val text = leadingText(child).trim()
                    when (localName(child)) {
                        "title" -> title = stripHtml(text)
                        "link" -> url = text.ifEmpty { child.getAttribute("href").trim() }
                        "description", "summary", "content" -> if (description.isEmpty()) description = text
                        "pubDate", "published", "updated", "date" -> if (published.isNullOrEmpty()) published = text
                    }
                }
                if (title.isNotEmpty() && url.isNotEmpty()) out += FeedItem(title.take(500), url.take(1000), description, parseDatetime(published))
                if (out.size >= MAX_ITEMS_PER_FEED) return false
            }
            for (c in elements(node)) if (!walk(c)) return false
            return true
        }
        walk(doc.documentElement)
        return out
    }

    private fun localName(e: Element) = e.localName ?: e.tagName.substringAfter(':')

    private fun elements(e: Element): List<Element> {
        val nodes = e.childNodes
        return (0 until nodes.length).map { nodes.item(it) }.filterIsInstance<Element>()
    }

    /** ElementTree `elem.text` — 첫 하위 요소 앞까지의 텍스트(CDATA 포함). */
    private fun leadingText(e: Element): String {
        val sb = StringBuilder()
        var n = e.firstChild
        while (n != null && n.nodeType != Node.ELEMENT_NODE) {
            if (n.nodeType == Node.TEXT_NODE || n.nodeType == Node.CDATA_SECTION_NODE) sb.append(n.nodeValue)
            n = n.nextSibling
        }
        return sb.toString()
    }

    private val RFC822 = Regex("""^(?:[A-Za-z]{3},\s*)?(\d{1,2})\s+([A-Za-z]{3})[a-z]*\s+(\d{2,4})\s+(\d{1,2}):(\d{2})(?::(\d{2}))?\s*(\S+)?\s*$""")
    private val MONTHS = listOf("jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec")
    private val TZ_NAMES = mapOf(
        "UT" to 0, "UTC" to 0, "GMT" to 0, "Z" to 0, "EST" to -500, "EDT" to -400, "CST" to -600, "CDT" to -500,
        "MST" to -700, "MDT" to -600, "PST" to -800, "PDT" to -700,
    )

    /** RSS pubDate(RFC 822)·Atom(ISO 8601) → UTC. 파싱 불가면 null, tz 없으면(또는 -0000) KST 로 본다. */
    fun parseDatetime(raw: String?): Instant? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null
        RFC822.matchEntire(s)?.let { m ->
            val (d, mon, y, hh, mm, ss, tz) = m.destructured
            val month = MONTHS.indexOf(mon.lowercase()) + 1
            if (month == 0) return null
            var year = y.toInt()
            if (y.length == 2) year += if (year > 68) 1900 else 2000
            val local = runCatching { LocalDateTime.of(year, month, d.toInt(), hh.toInt(), mm.toInt(), ss.ifEmpty { "0" }.toInt()) }.getOrNull() ?: return null
            val offset: ZoneOffset? = when {
                tz.isEmpty() || tz == "-0000" -> null
                tz.matches(Regex("[+-]\\d{4}")) -> ZoneOffset.ofHoursMinutes(tz.substring(1, 3).toInt() * (if (tz[0] == '-') -1 else 1), tz.substring(3, 5).toInt() * (if (tz[0] == '-') -1 else 1))
                TZ_NAMES.containsKey(tz.uppercase()) -> TZ_NAMES.getValue(tz.uppercase()).let { ZoneOffset.ofHours(it / 100) }
                else -> null
            }
            return if (offset == null) local.atZone(MarketClock.KST).toInstant() else local.toInstant(offset)
        }
        val iso = s.replace("Z", "+00:00")
        return try {
            OffsetDateTime.parse(iso).toInstant()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(iso).atZone(MarketClock.KST).toInstant()
            } catch (_: DateTimeParseException) {
                try {
                    LocalDate.parse(iso, DateTimeFormatter.ISO_LOCAL_DATE).atStartOfDay(MarketClock.KST).toInstant()
                } catch (_: DateTimeParseException) {
                    null
                }
            }
        }
    }

    /**
     * 제목+요약에 언급된 종목코드. 한글명은 부분일치, 영문·숫자만인 이름은 단어 경계(“SK”가 “RISK”에 걸리지 않게), 1글자 이름 제외,
     * 더 긴 매칭 이름의 부분문자열인 이름은 버린다(“삼성전자우” 기사에 “삼성전자”를 함께 태그하지 않게).
     */
    fun matchSymbols(text: String, nameMap: Map<String, String>): List<String> {
        if (text.isEmpty()) return emptyList()
        val matched = LinkedHashMap<String, String>()
        for ((code, name) in nameMap) {
            if (name.length < 2) continue
            val hit = if (name.all { it.code < 128 }) {
                Regex("(?<![A-Za-z0-9])" + Regex.escape(name) + "(?![A-Za-z0-9])").containsMatchIn(text)
            } else {
                name in text
            }
            if (hit) matched[name] = code
        }
        val names = matched.keys.toList()
        return names.filter { n -> names.none { it != n && n in it } }.map { matched.getValue(it) }.toSortedSet().toList()
    }

    /** 이름 엔티티 → 코드포인트(소스에 보이지 않는 문자를 두지 않으려고 숫자로). */
    private val NAMED: Map<String, Int> = mapOf(
        "amp" to 0x26, "lt" to 0x3C, "gt" to 0x3E, "quot" to 0x22, "apos" to 0x27, "nbsp" to 0xA0, "middot" to 0xB7,
        "hellip" to 0x2026, "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C, "rdquo" to 0x201D, "ndash" to 0x2013,
        "mdash" to 0x2014, "bull" to 0x2022, "copy" to 0xA9, "reg" to 0xAE, "trade" to 0x2122, "laquo" to 0xAB,
        "raquo" to 0xBB, "times" to 0xD7, "divide" to 0xF7, "deg" to 0xB0, "plusmn" to 0xB1, "para" to 0xB6,
        "sect" to 0xA7, "cent" to 0xA2, "pound" to 0xA3, "yen" to 0xA5, "euro" to 0x20AC, "larr" to 0x2190,
        "rarr" to 0x2192, "uarr" to 0x2191, "darr" to 0x2193, "prime" to 0x2032, "lsaquo" to 0x2039, "rsaquo" to 0x203A,
        "sbquo" to 0x201A, "bdquo" to 0x201E, "thinsp" to 0x2009, "ensp" to 0x2002, "emsp" to 0x2003, "zwj" to 0x200D,
        "zwnj" to 0x200C,
    )
    private val ENTITY = Regex("&(#[0-9]+|#[xX][0-9a-fA-F]+|[A-Za-z][A-Za-z0-9]*);")

    /**
     * Python `html.unescape` 의 실사용 부분: 숫자 엔티티 전부 + 뉴스에 나오는 이름 엔티티. 표에 없는 이름은 그대로 둔다
     * (원본은 HTML5 이름 2,231개 — 드문 엔티티는 표기가 다를 수 있다, migration-notes).
     */
    fun htmlUnescape(s: String): String = ENTITY.replace(s) { m ->
        val body = m.groupValues[1]
        when {
            body.startsWith("#x") || body.startsWith("#X") -> codePoint(body.substring(2).toIntOrNull(16)) ?: m.value
            body.startsWith("#") -> codePoint(body.substring(1).toIntOrNull()) ?: m.value
            else -> codePoint(NAMED[body]) ?: m.value
        }
    }

    private fun codePoint(cp: Int?): String? = cp?.takeIf { it in 1..0x10FFFF && it !in 0xD800..0xDFFF }?.let { String(Character.toChars(it)) }
}
