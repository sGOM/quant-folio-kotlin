package com.quantfolio.worker

import com.quantfolio.market.official.SnapshotSource
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDate

/**
 * 선별 백테스트(algorithm 폴더)용 밸류 입력 1회 내보내기. `quantfolio.valuation-export.out=<파일>`
 * `quantfolio.valuation-export.dates=2017-06-01,2017-07-03,...` 로 워커를 띄우면 날짜마다 백테스트 밸류 경로와 같은
 * [SnapshotSource.fundamentals] (KOSPI+KOSDAQ) 를 불러 KOSPI 행의 PER·PBR·DIV 를 CSV(`date,symbol,per,pbr,div`, null 은 빈 칸)로 쓴다.
 * 날짜 하나라도 실패하면(미적재 포함) 아무것도 쓰지 않는다 — 일부 날짜만 있는 파일은 읽는 쪽에서 결측으로 뭉개진다.
 */
@Component
@ConditionalOnProperty("quantfolio.valuation-export.out")
class ValuationExportRunner(
    private val snapshots: SnapshotSource,
    @Value("\${quantfolio.valuation-export.out}") private val out: String,
    @Value("\${quantfolio.valuation-export.dates}") private val dates: String,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        val log = LoggerFactory.getLogger(javaClass)
        // 여기서 예외가 새면 워커 기동이 실패해 재기동이 반복된다 - 로그만 남기고 계속 간다.
        try {
            val days = dates.split(',').map { LocalDate.parse(it.trim()) }.sorted()
            val sb = StringBuilder("date,symbol,per,pbr,div\n")
            var rows = 0
            for (d in days) {
                for (r in snapshots.fundamentals(d, listOf("KOSPI", "KOSDAQ")).filter { it.market == "KOSPI" }.sortedBy { it.symbol }) {
                    sb.append(d).append(',').append(r.symbol).append(',').append(r.per ?: "").append(',')
                        .append(r.pbr ?: "").append(',').append(r.div ?: "").append('\n')
                    rows++
                }
            }
            val target = Path.of(out)
            val tmp = Path.of("$out.tmp")
            Files.writeString(tmp, sb)
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            log.info("수동 밸류 내보내기 결과: rows={} dates={}", rows, days.size)
        } catch (e: Exception) {
            log.error("수동 밸류 내보내기 실패", e)
        }
    }
}
