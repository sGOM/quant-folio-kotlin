# 공식 경로 시장데이터 2단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 화면 경로의 KRX MDC·네이버 의존을 없애고, 시장데이터 출처를 KRX Open API 로컬 적재 하나로 만든다.

**Architecture:**
- 일봉은 core 의 로컬 `PriceHistory` 빈 하나만 쓴다. 지표 스냅샷은 `LocalSnapshots` 하나만 쓴다.
- 유니버스 소스는 `fixed | KOSPI_MCAP` 둘이고, 풀 크기는 `pool_size` 로 지정한다.
- 업종지수는 Open API 지수 응답의 전 행을 적재한다.
- 끝으로 `market/krx/`·네이버 코드와 KRX 로그인 시크릿을 삭제하고, 호스트 문자열 금지 규칙으로 재유입을 막는다.

**Tech Stack:** Kotlin 2 / Spring Boot 3, JdbcTemplate, Flyway, JUnit5 + Testcontainers, Next.js(TypeScript)

**Spec:** `docs/superpowers/specs/2026-11-23-official-market-data-phase2-design.md`

## Global Constraints

- **미래참조 금지(CLAUDE.md):** 시총 유니버스는 결정일 **직전 거래일** 데이터만 쓴다. 추천 화면만 기준일 당일 시총이다(화면용, 전략 아님).
- **미적재 처리:** 미적재는 `MarketDataNotLoaded`(API 409)이다. `catch (e: Exception)` 로 삼키지 않는다(`CodeRulesTest.unguardedCatchAlls`).
- **원본 차이 기록:** 원본과 다르게 동작하는 변경은 모두 `docs/migration-notes.md` 에 남긴다.
- **정답지 테스트:** 정답지 JSON(`src/test/resources/oracle/*.json`)은 고치지 않는다. 의도적 차이로 빠지는 케이스는 테스트 안의 허용 목록에 사유와 함께 둔다.
- **Open API 이름:** 지수명 원문 `코스피`·`코스피 200`·`코스닥` 은 기존 코드 `1001`·`1028`·`2001` 을 유지한다.
- **커밋 금지:** 실데이터·`research/`·`secrets/*.txt` 는 커밋하지 않는다. `secrets/*.txt` 는 편집도 하지 않는다(훅이 막는다).
- **빌드:** `cd backend && ./gradlew build` (Docker 필요). 프론트는 `cd frontend && npx tsc --noEmit && npm test`.

## Review Focus

1. **`pool_size` 없는 기존 시총 설정**(리서치 T1: `pick` 100만 있음) → 풀 = `pick`, 지금과 같은 결과. 테스트: Task 2 `pool size falls back to pick`.
2. **이름이 바뀌거나 새로 생긴 업종지수**(첫 행이 구간 중간) → 첫 행 이전은 결측이 아니다. 첫 행 이후 빠진 날만 409. 테스트: Task 5 `index history before its first row is not missing`.
3. **최근 체결이 있는 날 체결 정합 리포트** → 미공개·미래 평일 때문에 전 종목이 탈락하지 않는다. 테스트: Task 1 `fill window end is clipped to last loaded trading day`.
4. **업종지수 백필을 다시 돌림(중단 후 재실행)** → 같은 날 행이 중복되지 않는다(upsert). 원장·주식 테이블은 건드리지 않는다. 테스트: Task 4 `index backfill rewrites only index rows and is idempotent`.
5. **옛 지수 소스 설정을 API 로 저장**(구 프론트 캐시, 수동 PATCH) → 스키마가 422 로 거절한다. 조용히 `fixed` 로 바뀌지 않는다. 테스트: Task 2 `removed index sources are rejected`.

---

### Task 1: 단일 로컬 `PriceHistory`, 네이버 삭제

**Files:**
- Create: `backend/core/src/main/kotlin/com/quantfolio/market/ohlcv/PriceHistory.kt` — `fun interface PriceHistory` 를 `OhlcvLoader.kt` 에서 옮긴다(패키지 유지).
- Create: `backend/core/src/main/kotlin/com/quantfolio/market/official/LocalPriceHistory.kt`
- Modify: `backend/core/src/main/kotlin/com/quantfolio/market/official/LocalTradingDays.kt` — `lastTradingDayUpTo` 추가
- Modify: `backend/core/src/main/kotlin/com/quantfolio/backtest/FillQualityReport.kt:111-125` (`closePanel`)
- Modify: `backend/engine/src/main/kotlin/com/quantfolio/engine/LocalMarketData.kt:31-34` — 람다 대신 `LocalPriceHistory` 주입
- Delete: `market/ohlcv/OhlcvLoader.kt`, `market/ohlcv/NaverDaily.kt`, 테스트 `market/ohlcv/OhlcvLoaderTest.kt`, `market/official/OhlcvLoaderLocalTest.kt`
  - `OhlcvLoaderLocalTest` 가 검증하던 로컬 경로 케이스는 `LocalPriceHistoryTest` 로 옮긴다.
- Test: `backend/core/src/test/kotlin/com/quantfolio/market/official/LocalPriceHistoryTest.kt`, `LocalTradingDaysTest.kt`, `backend/engine/src/test/.../RunnersTest.kt`(FillQuality 부분)

**Interfaces:**
- Produces:
  - `class LocalPriceHistory(ticks: PriceTickStore, coverage: LocalOhlcvCoverage) : PriceHistory` (`@Component`). 유일한 `PriceHistory` 빈이다.
  - `LocalTradingDays.lastTradingDayUpTo(day: LocalDate): LocalDate?` — `day` 포함, 거꾸로 `MAX_BACK` 일 안에서 원장상 행>0 인 가장 최근 날. 모르는 날은 건너뛴다(`previous` 와 다름).

- [ ] **Step 1: 실패 테스트 작성**
  - `LocalPriceHistoryTest`
    - `loads local bars and throws MarketDataNotLoaded for a missing trading day`: 거래일 3일 원장 + 봉 2개 → `assertThrows<MarketDataNotLoaded>`, 봉 3개면 3개 반환.
  - `LocalTradingDaysTest`
    - `lastTradingDayUpTo skips unknown and holiday days`: 월(행>0), 화(0행 final), 수(기록 없음) → `lastTradingDayUpTo(수) == 월`.
  - FillQuality(engine `RunnersTest` 또는 core 새 테스트 `FillQualityReportTest`)
    - `fill window end is clipped to last loaded trading day`: 체결일 = 마지막 적재 거래일 → 리포트 표본 수 > 0. 이전 구현은 +10일 평일이 미적재라 0.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :core:test --tests '*LocalPriceHistoryTest*' --tests '*LocalTradingDaysTest*'` → 컴파일 실패/FAIL
- [ ] **Step 3: 구현**
  - `LocalPriceHistory.load` = `coverage.require` 후 `ticks.bars`. 엔진 `LocalMarketData` 람다 본문과 같다.
  - `closePanel` 의 `end` = `minOf(maxFill+10, days.lastTradingDayUpTo(MarketClock.todayKst(clock)) ?: maxFill+10)`.
  - 구간 **안쪽** 결손은 기존 종목별 `catch` 가 그 종목을 건너뛰는 그대로 둔다(원본: 종목별 실패 건너뜀, warn 로그). 표본이 줄면 게이트는 "표본 부족"으로 막는 쪽이라 안전 방향이다.
- [ ] **Step 4: 통과 확인** — `./gradlew :core:test :engine:test :worker:test` → PASS
- [ ] **Step 5: 커밋** — `refactor(market): 일봉을 로컬 PriceHistory 하나로 - 네이버 로더 삭제, 체결 정합 구간 끝을 마지막 적재 거래일로`

### Task 2: 유니버스 소스 `fixed | KOSPI_MCAP` + `pool_size`, 전략 이전 V11

**Files:**
- Modify: `backend/core/src/main/kotlin/com/quantfolio/strategy/StrategySchemas.kt:121-129,240-250`
- Modify: `backend/core/src/main/kotlin/com/quantfolio/backtest/BacktestRunner.kt:214-258` — 지수 분기·`localMembers`·`localCaps` 삭제, `mcapPool` 풀 크기 변경
- Modify: `backend/engine/src/main/kotlin/com/quantfolio/engine/RebalanceRunner.kt:403-425` — 지수 분기·`alertPitFallback` 경로 삭제
- Modify: `backend/engine/src/main/kotlin/com/quantfolio/engine/Runners.kt` — `constituents` 필드 삭제(클래스 `IndexConstituentStore` 자체는 `KrxIndexService` 가 써서 Task 6 에서 삭제)
- Create: `backend/core/src/main/resources/db/migration/V11__universe_source_mcap.sql`
- Modify: `frontend/components/strategy-form/RebalanceFields.tsx:160-220`, `frontend/lib/api.ts:344`
- Test: `StrategyConfigOracleTest`(허용 목록), `BacktestRunnerLocalTest`, engine `RunnersTest`(382·387줄 지수 소스 케이스 삭제), `backend/core/src/test/kotlin/com/quantfolio/db/BaselineMigrationTest.kt`

**Interfaces:**
- Produces:
  - 스키마 `UniverseRule` 필드는 `type`, `source: fixed|KOSPI_MCAP`, `lookback`, `pick`(1~100), `pool_size: Int?`(1~300, nullable, 기본 null), `min_market_cap`.
  - `pool_size` 가 있고 `pick > pool_size` 면 `ValueError("universe_rule.pick 은 pool_size 이하여야 합니다.")`.
  - 풀 크기 = `pool_size ?: pick` (백테스트 `mcap.pool(start, end, size)`, 엔진 `ctx.mcap.members(today, size)`). 그 위에 기존 `Rebalance.dynamicUniverse`(pick)가 그대로 적용된다.

- [ ] **Step 1: 실패 테스트 작성**
  - `BacktestRunnerLocalTest`
    - `pool size 200 with pick 40 narrows the mcap pool by momentum`: 시총 순위 종목 6개, `pool_size` 4, `pick` 2 → 결정일 후보가 시총 상위 4 중 모멘텀 상위 2.
    - `pool size falls back to pick`: `pool_size` 없음, `pick` 3 → 풀 = 시총 상위 3.
  - 스키마 테스트(`StrategySourceTest` 또는 새 `UniverseRuleSchemaTest`)
    - `removed index sources are rejected`: source `KOSPI200` → `StrategyConfigException`.
    - `pick above pool size is rejected`.
  - `BaselineMigrationTest`
    - `v11 moves index sources to mcap with pool size and keeps pick`: V10 상태에 KOSPI200(pick 40)·KOSPI100(pick 100)·KRX300·fixed 전략 4개를 넣고 latest 까지 이전 → `{source: KOSPI_MCAP, pool_size: 200, pick: 40}`, `{…100, pick 100}`, `{…300}`, fixed 는 변화 없음.
- [ ] **Step 2: 실행해 실패 확인** — `./gradlew :core:test --tests '*BacktestRunnerLocalTest*' --tests '*BaselineMigrationTest*'` → FAIL
- [ ] **Step 3: 구현**
  - **스키마·러너:** 위 Interfaces 대로.
  - **V11:** `jsonb_set` 으로 `config->'selection'->'universe_rule'` 의 `source`·`pool_size` 만 바꾼다. 매핑은 `KOSPI200→200`, `KOSPI100→100`, `KRX300→300`.
  - **`StrategyConfigOracleTest`:** 지수 소스 케이스는 허용 목록에 "2단계: 지수 소스 폐지(migration-notes)"로 둔다.
  - **프론트:** 드롭다운은 `fixed` 와 `KOSPI_MCAP`("KOSPI 시총 상위(시점별)")이다.
    - `KOSPI_MCAP` 이면 `NumField label="후보 풀 크기" min={1} max={300}`(기본 200)을 보인다.
    - 안내문은 "각 리밸런싱 직전 거래일 시가총액 상위 N(보통주)"로 바꾼다.
    - `api.ts` 의 `source` 타입을 `"fixed" | "KOSPI_MCAP"` 로 하고 `pool_size?: number` 를 추가한다.
- [ ] **Step 4: 통과 확인** — `./gradlew build` → PASS, `cd frontend && npx tsc --noEmit && npm test` → PASS
- [ ] **Step 5: 커밋** — `feat(strategy): 지수 구성종목 소스 폐지, 시총 풀 크기 pool_size - 기존 전략 V11 이전`

### Task 3: flow 팩터 삭제, V12

**Files:**
- Modify:
  - `factor/FactorScores.kt:27,43`
  - `metrics/UniverseScores.kt:138-175`
  - `strategy/StrategySchemas.kt:104-118,153-154`
  - `backtest/BacktestRunner.kt:259-270`(flow 거절 분기만 삭제, pead 유지)
  - `engine/RebalanceRunner.kt:309`
- Create: `backend/core/src/main/resources/db/migration/V12__drop_flow_factor.sql`
- Test: `UniverseScoresTest`, `BaselineMigrationTest`, 정답지 테스트 허용 목록(`factors.json`·`strategy_config.json`·`fingerprint.json`·`screener.json` 의 flow 케이스)

**Interfaces:**
- Produces: 팩터 키는 `momentum, value, lowvol, quality, growth, residual_momentum, pead` 이다. 가중치 합 검증 메시지에서도 flow 를 뺀다.

- [ ] **Step 1: 실패 테스트** — `BaselineMigrationTest.v12 strips flow keys`: `factor_weights.flow`, `selection.flow_window`, `selection.flow_denom` 가 사라지고 나머지 키는 같다.
- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*BaselineMigrationTest*'` → FAIL
- [ ] **Step 3: 구현** — V12 는 `config #- '{selection,factor_weights,flow}' #- '{selection,flow_window}' #- '{selection,flow_denom}'` 이다. 코드에서 flow 경로를 지우고, 정답지 flow 케이스는 허용 목록에 둔다.
- [ ] **Step 4: 통과 확인** — `./gradlew build` → PASS
- [ ] **Step 5: 커밋** — `refactor(factor): flow 팩터 삭제 - 공식 소스 없음, 쓰는 전략 0개`

### Task 4: 업종지수 적재·백필

**Files:**
- Modify: `backend/core/src/main/kotlin/com/quantfolio/market/krxopen/KrxOpenApi.kt:28,60-69` — `OpenIndexRow` 에 `volume: Long?`, `tradingValue: Long?`(`ACC_TRDVOL`·`ACC_TRDVAL`, 없으면 null 허용 — 필수 키 아님)
- Modify: `backend/core/src/main/kotlin/com/quantfolio/market/official/OfficialIngest.kt:160-166,319-325`
- Modify: `backend/worker/src/main/kotlin/com/quantfolio/worker/BackfillRunner.kt`, `WorkerJobs.kt`
- Create: `backend/core/src/main/resources/db/migration/V13__index_code_width.sql` — `index_ohlcv`·`index_ohlcv_coverage` 의 `index_code` 를 `varchar(64)` 로
- Test: `OfficialIngestTest`, `KrxOpenApiTest`, `BackfillRunnerTest`

**Interfaces:**
- Produces:
  - `OfficialIngest.indexKey(family: String, name: String): String`(companion)
    - 기존 3개 이름은 `1001`·`1028`·`2001` 이다.
    - 그 밖은 `"KOSPI:" + name.trim()` / `"KOSDAQ:" + name.trim()` 이다. KRX 계열은 저장하지 않는다(null 반환 → 건너뜀).
  - 일일 적재: 지수 응답의 **모든 행**을 `indexes.write(indexKey(..), listOf(IndexBar(day, o, h, l, c, volume, tradingValue)), name)` 로 저장한다. 기대 지수 검사(이름 변경 탐지)는 그대로 둔다.
  - `OfficialIngest.backfillIndexes(from: LocalDate, to: LocalDate, callBudget: Int): IngestReport`
    - `LocalTradingDays.isTradingDay(d) == true` 인 날만 `KOSPI`·`KOSDAQ` 지수를 다시 받아 위처럼 쓴다.
    - 원장·주식·수정주가는 건드리지 않는다. 예산을 넘으면 멈추고 `IngestReport` 에 마지막 날을 남긴다.
  - 설정 `quantfolio.backfill.indexes-only: Boolean = false`. true 면 `BackfillRunner` 가 `jobs.backfillOfficialIndexes(from, to, callBudget)` 를 부른다.

- [ ] **Step 1: 실패 테스트**
  - `OfficialIngestTest`
    - `daily ingest stores every index row under its key with volume and value`: KOSPI 응답 `코스피`, `코스피 200`, `전기·전자`(거래대금 5) → `index_ohlcv` 에 `1001`, `1028`, `KOSPI:전기·전자`, 마지막 행 `tradingValue == 5`.
    - `index backfill rewrites only index rows and is idempotent`: 같은 범위 2회 → 행 수 같음, 원장 행 수·`stock_daily_snapshots` 변화 없음, 비거래일은 호출 0회.
  - `BackfillRunnerTest`
    - `indexes-only runs the index backfill`
- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*OfficialIngestTest*' :worker:test --tests '*BackfillRunnerTest*'` → FAIL
- [ ] **Step 3: 구현** — 위 Interfaces 대로.
- [ ] **Step 4: 통과 확인** — `./gradlew build` → PASS
- [ ] **Step 5: 커밋** — `feat(market): 업종지수 전 행·거래대금 적재와 지수 전용 백필`

### Task 5: 화면을 로컬 스냅샷으로 — 섹터·종목·스크리너, `KrxSnapshots` 삭제

**Files:**
- Create: `backend/core/src/main/kotlin/com/quantfolio/market/official/SnapshotSource.kt` — 인터페이스를 `market/krx/KrxSnapshots.kt` 에서 옮긴다.
- Modify: `market/official/LocalSnapshots.kt:93-105`
- Modify: `market/store/IndexOhlcvStore.kt` — `keys(prefix)` 추가
- Modify: `metrics/MetricsServices.kt:118-160`(`SectorMetricsService`) — 지수 목록·이름 로컬, MDNL 재던짐
- Modify:
  - `StockMetricsService`·`ScreenerService`·`SectorMetricsService`·`UniverseScores` 의 `SnapshotSource` 주입에 `@Qualifier("local")` 을 단다. 원격 `@Primary` 가 사라지므로 필수는 아니지만 규칙과 일관되게 한다.
  - `LiveFactorInputs.marketCaps` 는 `snapshots.marketCap(asOf, listOf("KOSPI","KOSDAQ"))` 로 바꾼다(엔진 `LocalMarketData.inputs` 와 같다).
- Delete: `market/krx/KrxSnapshots.kt`, `KrxStockApi.kt`, 해당 테스트
- 모든 `import com.quantfolio.market.krx.SnapshotSource` → `com.quantfolio.market.official.SnapshotSource`(`CodeRulesTest` 의 문자열 포함)
- Test: `LocalSnapshotsTest`, `MetricsServicesTest`

**Interfaces:**
- Consumes: Task 4 `indexKey` 형식(`KOSPI:`/`KOSDAQ:` 접두어).
- Produces:
  - `SnapshotSource` 메서드: `fundamentals`, `marketCap`, `priceChange`, `marketOhlcvSnapshot`, `indexOhlcv`, 그리고 새 `sectorIndexes(market: String): List<Pair<String, String>>`. 값은 (키, 원문 이름)이고 키 오름차순이며 `1001`·`2001` 은 제외한다.
  - `netPurchases`·`indexTickers`·`indexName` 은 삭제한다.
  - `IndexOhlcvStore.keys(prefix: String): List<Pair<String, String?>>`
  - `LocalSnapshots.indexOhlcv(start, end, ticker)`: 결측 판정 구간 = `[max(start, 첫 행 날짜), end]` 이다. 행이 하나도 없으면 전 구간 미적재다.

- [ ] **Step 1: 실패 테스트**
  - `LocalSnapshotsTest`
    - `index history before its first row is not missing`: 구간 중간에 첫 행 → 예외 없음. 첫 행 뒤 거래일 1개 빠짐 → `MarketDataNotLoaded`.
    - `sectorIndexes lists stored sector keys without market reference`
  - `MetricsServicesTest`
    - `sector metrics propagate MarketDataNotLoaded instead of returning an empty list`
- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*LocalSnapshotsTest*' --tests '*MetricsServicesTest*'` → FAIL
- [ ] **Step 3: 구현** — `SectorMetricsService` 지수 루프의 `catch (e: Exception)` 앞에 `catch (e: MarketDataNotLoaded) { throw e }` 를 둔다.
- [ ] **Step 4: 통과 확인** — `./gradlew build` → PASS
- [ ] **Step 5: 커밋** — `feat(metrics): 섹터·종목·스크리너 화면을 로컬 스냅샷으로, KrxSnapshots 삭제`

### Task 6: 추천을 시총 상위 200으로, MDC 잔여 삭제, 호스트 금지 규칙

**Files:**
- Modify: `market/official/McapUniverse.kt` — `membersOn` 추가
- Modify: `metrics/ScreenerRecommend.kt:172-200` — `IndexMembers` 대신 `McapUniverse`, `INDEX = "MCAP200"`, `kospi200Scored` → `mcap200Scored`
- Modify: `web/.../query/RecommendController.kt` — `@GetMapping("/api/recommend/mcap200")`
- Modify: `common/RedisKeys.kt:105` — `"recommend:mcap200:$asOf"`(캐시라 원본 문자열 보존 대상 아님 — 원본 스택 퇴역)
- Modify: `frontend/lib/api.ts:797,1433`, `frontend/app/recommend/page.tsx:91-92,142-145,199` — 경로·함수명 `getMcap200Recommend`·문구 "시총 상위 200"
- Delete: `market/krx/` 남은 전부, `market/ohlcv/IndexMembers.kt`, `market/store/IndexConstituentStore.kt`, `worker/UniverseBuilder.kt` + 각 테스트, `test/FakeKrxTransport.kt`
- Modify: `QuantfolioProperties.kt:21,88`(`krx` 삭제), `quantfolio-core.yml:44-46`, `docker-compose.yml:14-15,149-152`, `.github/workflows/e2e-smoke.yml:34`(`krx_id krx_pw` 제거), `secrets/README.md` 해당 2줄
- Modify: `backend/core/src/test/kotlin/com/quantfolio/common/CodeRulesTest.kt`
- Test: `ScreenerRecommendOracleTest`, `McapUniverseTest`, `CodeRulesTest`, web `QueryControllersTest`

**Interfaces:**
- Consumes: Task 5 `SnapshotSource`(official 패키지).
- Produces:
  - `McapUniverse.membersOn(day: LocalDate, n: Int): List<String>` — `day` 당일 시총 행에 `top` 과 같은 정렬·주권 종류 규칙을 쓴다. 당일 행이 없으면 `MarketDataNotLoaded`.
  - `RecommendService.mcap200Scored(asOf: LocalDate): RecommendOut`

- [ ] **Step 1: 실패 테스트**
  - `McapUniverseTest`
    - `membersOn ranks the given day itself`: 당일·전일 시총 순위가 다른 데이터 → 당일 순위.
  - `CodeRulesTest`
    - `main sources contain no MDC or Naver hosts`: `*/src/main/**/*.kt` 에 `data.krx.co.kr`·`stock.naver.com` 0건(`data-dbg.krx.co.kr` 는 허용). 자기검증으로 `"https://data.krx.co.kr/x"` 는 걸리고 `"https://data-dbg.krx.co.kr/svc"` 는 안 걸린다.
  - 정답지 `ScreenerRecommendOracleTest`
    - 추천 케이스는 구성종목 입력만 시총 상위로 바뀐다. 계산 정답 비교는 유지하고, 구성종목 소스 차이만 허용 목록에 둔다.
- [ ] **Step 2: 실패 확인** — `./gradlew :core:test --tests '*McapUniverseTest*' --tests '*CodeRulesTest*'` → FAIL
- [ ] **Step 3: 구현 + 삭제**
  - `STRATEGY_PATH_FILES` 주석의 `OhlcvLoader` 언급과, 삭제된 패키지 import 검사 문자열을 정리한다.
- [ ] **Step 4: 통과 확인**
  - `./gradlew build` → PASS
  - `grep -rn 'market.krx\|KrxSession\|NaverDaily' backend/*/src` → 0건
  - `cd frontend && npx tsc --noEmit && npm test` → PASS
- [ ] **Step 5: 커밋** — `feat(recommend): 추천을 시총 상위 200으로, KRX MDC·로그인 시크릿 삭제, MDC·네이버 호스트 금지 규칙`

### Task 7: 문서·원본 차이 기록

**Files:**
- Modify: `.claude/rules/market-data.md`
  - "KRX MDC" 절과 `ohlcv/` 행을 삭제한다.
  - 함정의 MDC 로그인·`CD010` 줄을 삭제한다.
  - 약관·IP 차단 교훈은 한 줄로 남긴다.
  - `SnapshotSource` 위치와 `@Primary` 서술을 고친다.
  - 업종지수 키 규칙을 추가한다.
- Modify: `.claude/rules/strategy-config.md` — 소스 목록, `pool_size`, flow 삭제
- Modify: `docs/migration-notes.md` — 원본 차이 4건: 지수 소스 폐지(+V11), 추천 대상 시총 200, 섹터 지수 출처 Open API(이름 키), flow 삭제(+V12)
- Modify: `docs/superpowers/specs/2026-11-23-official-market-data-phase2-design.md` — 남은 구간 비교 결과는 백필 후 사람이 덧붙인다. 이 작업에선 건드리지 않는다.

- [ ] **Step 1: 문서 수정** 후 `node .claude/hooks/md-lint.mjs` → 0건
- [ ] **Step 2: 전체 확인** — `cd backend && ./gradlew build` → PASS
- [ ] **Step 3: 커밋** — `docs: 공식 경로 2단계 - MDC 절 삭제, 원본 차이 기록`

---

## 배포 후 확인 (qf-paper, 백필·검증 3종 완료 뒤 — 사람이 승인)

1. 이미지 재빌드·기동한다. Flyway V11~V13 적용을 확인한다(`select version from flyway_schema_history order by installed_rank desc limit 3`).
2. 업종지수 백필을 실행한다. 범위는 오늘−400일 ~ 오늘이고 설정은 `quantfolio.backfill.indexes-only=true`, 예산 1,000이다.
3. 화면 4종(`/api/metrics/stocks`·`/sectors`·`/api/screener/turnaround`·`/api/recommend/mcap200`)이 200을 내는지 확인한다.
4. 호출 시간대 `external_fetches` 를 소스별로 보고, MDC·네이버 소스 기록이 없는지 확인한다.
5. `compare_index.py` 로 남은 구간을 비교해 설계 문서 "B 의 대가" 절에 덧붙인다.
6. `research/hypotheses.md` 의 리서치 설정에 `pool_size: 100` 을 명시한다(비공개, 커밋 안 함).
