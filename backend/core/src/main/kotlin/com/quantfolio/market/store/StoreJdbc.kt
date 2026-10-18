package com.quantfolio.market.store

import java.math.BigDecimal
import java.sql.ResultSet

/**
 * 저장 타입 변환 — 원본 `store/coerce.py`. 결측·NaN·±Inf 는 null. 이상값 하나가 그 날짜 전체 적재를 막으면 안 된다.
 * 숫자는 `BigDecimal(double.toString())` — 원본 `Decimal(str(v))` 와 같은 최단 표현.
 */
internal fun num(v: Double?): BigDecimal? = v?.takeIf { it.isFinite() }?.let { BigDecimal(it.toString()) }

/** 종목코드 6자리 0-패딩(원본 `str(ticker).zfill(6)`). */
internal fun padSymbol(symbol: String): String = symbol.padStart(6, '0')

internal fun ResultSet.double(col: String): Double? = getBigDecimal(col)?.toDouble()

internal fun ResultSet.long(col: String): Long? = getLong(col).takeUnless { wasNull() }

/** 같은 종목이 여러 번 오면 마지막 값을 채택한다(시장전환일에 KOSPI·KOSDAQ 응답에 함께 실리는 경우, 원본 §55). */
internal fun <T> lastPerSymbol(rows: List<T>, symbol: (T) -> String): List<T> =
    rows.associateBy { padSymbol(symbol(it)) }.values.toList()
