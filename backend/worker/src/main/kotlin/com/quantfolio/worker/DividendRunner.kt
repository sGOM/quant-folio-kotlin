package com.quantfolio.worker

import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component

/**
 * 수동 배당 이력 적재 트리거. `quantfolio.dividends.run-once=true` 로 워커를 띄우면 기동 직후 [WorkerJobs.ingestDividends] 를 한 번 실행한다
 * (주간 스케줄을 기다리지 않고 비어 있는 배당 테이블을 채울 때). 끝나면 속성을 지운다 — 남겨 두면 재기동마다 data.go.kr 을 또 부른다.
 */
@Component
@ConditionalOnProperty("quantfolio.dividends.run-once", havingValue = "true")
class DividendRunner(private val jobs: WorkerJobs) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val log = LoggerFactory.getLogger(javaClass)
        // 여기서 예외가 새면 워커 기동이 실패해 재기동이 반복된다 - 로그만 남기고 계속 간다.
        try {
            log.info("수동 배당 적재 결과: {}", jobs.ingestDividends())
        } catch (e: Exception) {
            log.error("수동 배당 적재 실패", e)
        }
    }
}
