"""뉴스 수집 파싱 정답지 — strip_html·summarize·parse_feed(RSS/Atom)·날짜 파싱·종목 매칭."""
from app.services import news
from oracle_lib import save

RSS = """<?xml version="1.0" encoding="UTF-8"?>
<rss version="2.0" xmlns:dc="http://purl.org/dc/elements/1.1/">
<channel><title>채널</title>
<item><title><![CDATA[삼성전자, <b>사상 최대</b> 실적 &amp; 배당]]></title><link>https://n.example/a?x=1&amp;y=2</link>
<description><![CDATA[<p>삼성전자우와 SK하이닉스가&nbsp;강세 &hellip; &lt;b&gt;굵게&lt;/b&gt;</p>]]></description>
<pubDate>Mon, 13 Oct 2026 09:30:00 +0900</pubDate></item>
<item><title>제목만</title><link>https://n.example/b</link><description>제목만</description><pubDate>Tue, 14 Oct 2026 01:00:00 GMT</pubDate></item>
<item><title>RISK 관리와 SK 텔레콤</title><link>https://n.example/c</link><description></description><dc:date>2026-10-15T08:00:00</dc:date></item>
<item><title>   </title><link>https://n.example/notitle</link></item>
<item><title>링크 없음</title></item>
<item><title>날짜 이상</title><link>https://n.example/d</link><pubDate>not a date</pubDate><description>DESC_LONG</description></item>
</channel></rss>"""
RSS = RSS.replace("DESC_LONG", "가" * 120 + " 나다라 " * 60)

ATOM = """<?xml version="1.0" encoding="utf-8"?>
<feed xmlns="http://www.w3.org/2005/Atom">
<entry><title type="html">LG에너지솔루션 &amp;amp; LG</title>
<link rel="alternate" href="https://atom.example/1"/><link rel="self" href="https://atom.example/1/self"/>
<summary>첫 요약</summary><content>두번째 본문</content>
<updated>2026-10-16T10:00:00Z</updated><published>2026-10-16T09:00:00+09:00</published></entry>
<entry><title>NAVER 와 카카오</title><link href="https://atom.example/2"/><updated>2026-10-17T12:30:45.123+00:00</updated></entry>
</feed>"""

many = "<rss><channel>" + "".join(f"<item><title>t{i}</title><link>https://m/{i}</link></item>" for i in range(60)) + "</channel></rss>"

NAME_MAP = {"005930": "삼성전자", "005935": "삼성전자우", "000660": "SK하이닉스", "003600": "SK", "017670": "SK텔레콤",
            "373220": "LG에너지솔루션", "003550": "LG", "035420": "NAVER", "035720": "카카오", "999990": "가", "012345": "RISK2"}

def enc_items(items):
    return [{**i, "published_at": i["published_at"].isoformat() if i["published_at"] else None} for i in items]

dates = ["Mon, 13 Oct 2026 09:30:00 +0900", "Tue, 14 Oct 2026 01:00:00 GMT", "13 Oct 2026 09:30:00 -0000", "2026-10-15T08:00:00",
         "2026-10-16T10:00:00Z", "2026-10-17T12:30:45.123+00:00", "garbage", "", None, "2026-10-18"]
strips = ["<p>a&nbsp;b</p>", "&lt;b&gt;x&lt;/b&gt;", "  여러   공백\n\t줄 ", "&amp;amp;", "&#x41;&#66;", "a < b > c", "&middot;&hellip;&rsquo;"]
save("news", {
    "rss": RSS, "atom": ATOM, "many": many,
    "parsed": {"rss": enc_items(news.parse_feed(RSS)), "atom": enc_items(news.parse_feed(ATOM)), "many": len(news.parse_feed(many))},
    "dates": [{"raw": d, "out": (lambda r: r.isoformat() if r else None)(news._parse_datetime(d))} for d in dates],
    "strip": [{"raw": s, "out": news.strip_html(s)} for s in strips],
    "summaries": [{"title": t, "desc": d, "out": news.summarize(t, d)} for t, d in [("제목", "제목"), ("제목", ""), ("<b>제목</b>", "제목"), ("t", "가" * 400), ("t", "x" * 298 + "  yy")]],
    "name_map": NAME_MAP,
    "matches": [{"text": t, "out": news.match_symbols(t, NAME_MAP)} for t in [
        "삼성전자우와 SK하이닉스가 강세", "RISK 관리와 SK 텔레콤", "SK텔레콤, LG에너지솔루션", "LG 와 NAVER(035420)", "가나다 RISK2", ""]],
})
print("ok")
