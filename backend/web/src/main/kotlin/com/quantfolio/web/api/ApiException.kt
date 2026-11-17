package com.quantfolio.web.api

import com.quantfolio.domain.backtest.Backtest
import com.quantfolio.domain.backtest.BacktestRepository
import com.quantfolio.domain.strategy.Strategy
import com.quantfolio.domain.strategy.StrategyRepository
import com.quantfolio.domain.user.User
import org.springframework.http.HttpStatus

/** 라우트가 던지는 HTTP 오류. 응답은 `{"detail": detail}` (원본 FastAPI HTTPException 과 같은 형태). */
open class ApiException(val status: HttpStatus, val detail: String) : RuntimeException(detail)

/** 사용자 소유 전략. 없거나 남의 것이면 404 — 존재 여부를 노출하지 않는다. */
fun StrategyRepository.owned(user: User, id: Int): Strategy =
    findById(id).filter { it.userId == user.id }.orElseThrow { ApiException(HttpStatus.NOT_FOUND, "전략을 찾을 수 없습니다.") }

/** 전략 소속 백테스트. 없거나 다른 전략의 것이면 404. */
fun BacktestRepository.ofStrategy(strategyId: Int, backtestId: Int): Backtest =
    findById(backtestId).filter { it.strategyId == strategyId }.orElseThrow { ApiException(HttpStatus.NOT_FOUND, "백테스트를 찾을 수 없습니다.") }
