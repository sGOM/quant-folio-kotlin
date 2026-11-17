package com.quantfolio.engine

import com.quantfolio.broker.BrokerHttp
import com.quantfolio.quant.pyJsonDumps
import org.slf4j.LoggerFactory
import tools.jackson.databind.json.JsonMapper
import tools.jackson.module.kotlin.readValue
import java.math.BigDecimal
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 복호화·파싱된 체결통보 1건. [qty] 는 이 체결 한 건의 수량(CNTG_QTY — 주문 누적이 아니다, 2026-11-16 모의 실측), [price] 는 그 체결 단가.
 * 둘 다 장부에 쓰지 않는다(로그용) — 반영은 [FillNoticeApplier] 가 트리거한 정합이 증권사 누적으로 한다. side 도 참고용(매칭에 쓰지 않는다).
 */
data class FillNotice(val odno: String, val symbol: String, val qty: Int, val price: BigDecimal, val side: String, val accountNo: String? = null) {
    override fun toString() = "FillNotice(odno=$odno, symbol=$symbol, qty=$qty, price=$price, side=$side)"
}

/**
 * KIS 실시간 프레임(원본 `kis_ws.py`·`fill_notice.py`). 데이터 프레임은 `암호화여부|tr_id|건수|필드^필드…`.
 * 체결통보 필드 인덱스는 KIS 공식 샘플 순서 — 2026-11-16 모의투자(H0STCNI9) 실프레임 23필드로 아래 인덱스를 확인했다. 실전(H0STCNI0) 프레임은 확인하지 못했다.
 */
object KisFrames {
    private val json = JsonMapper.builder().build()

    /** 구독/해지 메시지(원본 json.dumps 문자열). */
    fun subscribe(approvalKey: String, trId: String, trKey: String, subscribe: Boolean = true): String = pyJsonDumps(
        linkedMapOf(
            "header" to linkedMapOf("approval_key" to approvalKey, "custtype" to "P", "tr_type" to if (subscribe) "1" else "2", "content-type" to "utf-8"),
            "body" to linkedMapOf("input" to linkedMapOf("tr_id" to trId, "tr_key" to trKey)),
        ),
    )

    /** 실시간 접속용 approval_key(`/oauth2/Approval`). */
    fun approvalKey(http: BrokerHttp, baseUrl: String, appKey: String, appSecret: String): String {
        val r = http.send("POST", "$baseUrl/oauth2/Approval", json = json.writeValueAsString(linkedMapOf("grant_type" to "client_credentials", "appkey" to appKey, "secretkey" to appSecret)))
        if (r.status !in 200..299) throw IllegalStateException("approval_key 발급 실패: HTTP ${r.status}")
        // 원본 `resp.json()["approval_key"]` 은 없으면 KeyError — "null" 로 구독하면 실패가 성공처럼 보여 피드 장애 경보가 안 난다
        return json.readValue<Map<String, Any?>>(r.body)["approval_key"]?.toString() ?: throw IllegalStateException("approval_key 응답에 키 없음")
    }

    @Suppress("UNCHECKED_CAST")
    fun control(raw: String): Map<String, Any?>? = runCatching { json.readValue<Map<String, Any?>>(raw) }.getOrNull()

    @Suppress("UNCHECKED_CAST")
    fun isPingPong(raw: String) = (control(raw)?.get("header") as? Map<String, Any?>)?.get("tr_id") == "PINGPONG"

    /** 실시간 체결가(H0STCNT0) → (종목, 가격). 0 이하·형식 오류는 null. */
    fun parsePrice(raw: String): Pair<String, BigDecimal>? {
        val parts = raw.split("|")
        if (parts.size < 4 || parts[1] != "H0STCNT0") return null
        val f = parts[3].split("^")
        if (f.size < 3) return null
        val price = runCatching { BigDecimal(f[2].trim()) }.getOrNull() ?: return null
        return if (price.signum() > 0) f[0] to price else null
    }

    /** AES-256-CBC + PKCS7(Java 의 PKCS5Padding 이 블록 16 에선 같다). key/iv 는 구독 응답 문자열. */
    fun aesDecrypt(key: String, iv: String, cipherB64: String): String {
        val c = Cipher.getInstance("AES/CBC/PKCS5Padding")
        c.init(Cipher.DECRYPT_MODE, SecretKeySpec(key.toByteArray(), "AES"), IvParameterSpec(iv.toByteArray()))
        return String(c.doFinal(Base64.getDecoder().decode(cipherB64)), Charsets.UTF_8)
    }

    /**
     * 체결통보 평문(^ 구분). 체결 통보(CNTG_YN(13)=2)이고 거부(RFUS_YN(12)=1)가 아닐 때만 체결이다 — 1 은 주문·정정·취소·거부 접수
     * 통보로 CNTG_QTY 가 주문수량이다(KIS 공식 샘플). 판별 불가(빈 값)도 체결로 보지 않는다. 체결수량 0 이어도 null.
     * 원본(`fill_notice.py:91-115`)은 13·12 를 보지 않고 수량 > 0 이면 체결로 쓴다.
     * 2026-11-16 모의 실측(23필드): 2=주문번호, 3=원주문번호(정정·취소 통보만), 9=CNTG_QTY(체결 건별 — 300주 시장가 매수 1,1,2,7,…, 합 = 총 체결),
     * 10=CNTG_UNPR, 12=RFUS_YN, 13=CNTG_YN(접수·정정·취소 1, 체결 2), 14=ACPT_YN, 16=ODER_QTY(부분 취소 뒤엔 줄어든 수량). 체결 고유번호는 없고,
     * 같은 초·같은 수량·단가의 별개 체결 통보가 연달아 온다 — 내용으로 중복과 구분할 수 없다.
     */
    fun parseFillNotice(plain: String): FillNotice? {
        val f = plain.split("^")
        if (f.size < 23) {
            LoggerFactory.getLogger("engine.fill_notice").warn("체결통보 필드 수 부족({}) — 무시", f.size)
            return null
        }
        // 모든 통보를 남긴다(고객ID·계좌·계좌명 제외) — 통보 수량은 장부에 쓰지 않으므로 체결 추적은 이 로그와 정합 로그로 한다.
        LoggerFactory.getLogger("engine.fill_notice").info(
            "체결통보 수신 odno={} CNTG_YN={} RFUS_YN={} ACPT_YN={} CNTG_QTY={} ODER_QTY={} CNTG_UNPR={} STCK_CNTG_HOUR={}",
            f[2].trim(), f[13].trim(), f[12].trim(), f[14].trim(), f[9].trim(), f[16].trim(), f[10].trim(), f[11].trim(),
        )
        if (f[13].trim() != "2" || f[12].trim() == "1") return null
        val odno = f[2].trim()
        val symbol = f[8].trim()
        if (odno.isEmpty() || symbol.isEmpty()) return null
        val qty = f[9].ifEmpty { "0" }.trim().toIntOrNull() ?: 0 // 원본 int()/Decimal() 은 앞뒤 공백을 받는다
        if (qty <= 0) return null
        val price = runCatching { BigDecimal(f[10].ifEmpty { "0" }.trim()) }.getOrDefault(BigDecimal.ZERO)
        return FillNotice(odno, symbol, qty, price, if (f[4] == "01") "sell" else "buy", f[1].trim().ifEmpty { null })
    }
}
