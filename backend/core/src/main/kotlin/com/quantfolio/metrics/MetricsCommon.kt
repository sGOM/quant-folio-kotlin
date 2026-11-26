package com.quantfolio.metrics

import com.quantfolio.common.MarketClock
import com.quantfolio.market.MarketCalendar
import com.quantfolio.quant.Num
import com.quantfolio.quant.Series
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.ln
import kotlin.math.sqrt

/** 지표 계산 공통 헬퍼(원본 `metrics/common.py`). 영업일 계산만 상태(달력)가 필요하고 나머지는 순수 함수. */
@Component
class MetricsCommon(private val calendar: MarketCalendar, private val clock: Clock) {
    /** 전 영업일(KST 어제부터 최대 14일 거슬러). 원본 `date.today()` 는 컨테이너 UTC 라 KST 00~09시에 하루 일렀다(migration-notes #8). */
    fun lastBusinessDay(): LocalDate {
        val today = MarketClock.todayKst(clock)
        var d = today.minusDays(1)
        repeat(14) {
            if (calendar.isBusinessDay(d)) return d
            d = d.minusDays(1)
        }
        return today.minusDays(today.dayOfWeek.value - 1 + 7L) // 원본: weekday()(월=0) + 7
    }

    fun prevBusinessDay(d: LocalDate): LocalDate {
        var candidate = d.minusDays(1)
        repeat(10) {
            if (calendar.isBusinessDay(candidate)) return candidate
            candidate = candidate.minusDays(1)
        }
        return d.minusDays(3)
    }

    companion object {
        private val YMD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        /** 영업일 bdays 만큼 거슬러 가기 위한 대략의 달력 시작일(주말 보정 + 버퍼). */
        fun approxStart(asOf: LocalDate, bdays: Int, buffer: Int = 14): LocalDate = asOf.minusDays((bdays * 7 / 5 + buffer).toLong())

        fun ymd(d: LocalDate): String = d.format(YMD)

        /** 최대낙폭(음수). 행 < 2 면 null. NaN 은 건너뛴다. */
        fun computeMdd(close: Series): Double? {
            if (close.size < 2) return null
            val peak = close.expandingMax()
            val dd = (close - peak) / peak
            val valid = dd.values.filter { !it.isNaN() }
            return Num.safe(valid.minOrNull())
        }

        /** 연환산 변동성 = 로그수익률 표준편차(ddof=1) × √252. 행 < 20 또는 유효 수익률 < 10 이면 null. */
        fun computeVolAnn(close: Series): Double? {
            if (close.size < 20) return null
            val logRet = (close / close.shift(1)).map { ln(it) }.dropNa()
            if (logRet.size < 10) return null
            return Num.safe(logRet.std() * sqrt(252.0))
        }

        fun mkts(market: String): List<String> = if (market.uppercase() == "ALL") listOf("KOSPI", "KOSDAQ") else listOf(market.uppercase())

        /** warn→0, high→100 선형, [0,100] 클램프. 입력 결측·warn==high 면 null. */
        fun ramp(x: Double?, warn: Double, high: Double): Double? {
            if (x == null || x.isNaN() || high == warn) return null
            return ((x - warn) / (high - warn)).coerceIn(0.0, 1.0) * 100.0
        }

        /** 결측 점수를 뺀 가중평균. 유효 가중치 합 0 이면 null. */
        fun wavg(pairs: List<Pair<Double, Double?>>): Double? {
            val num = pairs.sumOf { (w, s) -> if (s != null) w * s else 0.0 }
            val den = pairs.sumOf { (w, s) -> if (s != null) w else 0.0 }
            return if (den > 0) num / den else null
        }
    }
}
