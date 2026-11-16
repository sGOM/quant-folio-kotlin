package com.quantfolio.quant

import java.time.LocalDate

/**
 * 날짜 × 종목 실수 패널(결측 NaN). 백테스트는 날짜 d 의 결정에 **[upTo] 로 자른 뷰만** 넘긴다 —
 * 뷰는 d 이후 행을 아예 갖지 않으므로 미래참조가 구조적으로 불가능하다(CLAUDE.md 절대 금지 1).
 *
 * 내부는 종목별 DoubleArray 를 공유하고, 뷰는 보이는 행 수([visible])만 다르다(복사 없음).
 */
class Panel private constructor(
    private val allDates: List<LocalDate>,
    val symbols: List<String>,
    private val columns: Map<String, DoubleArray>,
    private val start: Int,
    private val visible: Int,
) {
    constructor(dates: List<LocalDate>, symbols: List<String>, columns: Map<String, DoubleArray>) :
        this(dates, symbols, columns, 0, dates.size) {
        for (i in 1 until dates.size) require(dates[i].isAfter(dates[i - 1])) { "날짜는 오름차순·중복 없음이어야 한다: ${dates[i - 1]}, ${dates[i]}" }
        for (s in symbols) require(columns[s]?.size == dates.size) { "종목 $s 의 길이가 날짜 수와 다르다" }
    }

    val dates: List<LocalDate> get() = allDates.subList(start, start + visible)

    fun hasSymbol(symbol: String) = symbol in columns

    fun column(symbol: String): Series {
        val src = columns[symbol] ?: throw NoSuchElementException("패널에 없는 종목: $symbol")
        return Series(dates, src.copyOfRange(start, start + visible))
    }

    /** 그 날짜의 종목→값. 패널(뷰)에 없는 날짜면 null. */
    fun row(date: LocalDate): Map<String, Double>? {
        val i = indexOf(date) ?: return null
        return symbols.associateWith { columns.getValue(it)[i] }
    }

    /** d 이하 행만 보이는 뷰. 결정 시점 d 의 계산에는 반드시 이것을 쓴다. */
    fun upTo(d: LocalDate): Panel {
        var n = 0
        while (n < visible && !allDates[start + n].isAfter(d)) n++
        return Panel(allDates, symbols, columns, start, n)
    }

    private fun indexOf(date: LocalDate): Int? {
        val i = allDates.binarySearch(date, fromIndex = start, toIndex = start + visible)
        return if (i >= 0) i else null
    }

    companion object {
        /** 종목별 시계열을 날짜 합집합으로 정렬해 패널로(빈 칸 NaN). pandas `pd.DataFrame(dict_of_series)` 와 같은 정렬. */
        fun fromSeries(series: Map<String, Series>): Panel {
            val dates = series.values.flatMap { it.index }.toSortedSet().toList()
            val pos = dates.withIndex().associate { (i, d) -> d to i }
            val cols = series.mapValues { (_, s) ->
                DoubleArray(dates.size) { Double.NaN }.also { arr -> s.index.forEachIndexed { k, d -> arr[pos.getValue(d)] = s.values[k] } }
            }
            return Panel(dates, series.keys.toList(), cols)
        }
    }
}
