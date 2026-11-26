# 공식 경로 시장데이터 1단계 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 전략 경로(백테스트·실매매·panic·달력·worker 적재·종목 목록)를 KRX Open API·OpenDART·KIS 공식 경로로 옮기고, 로컬 DB 만 읽게 하며, 백테스트에 현금배당을 넣는다.

**Architecture:** worker 가 공식 API → `stock_daily_snapshots`(원천값)·`index_ohlcv`·`dart_financials`·`dividends` 에 적재하고 `price_ticks` 를 수정주가로 재구성한다.
기존 `SnapshotSource` 인터페이스에 로컬 전용 구현 `LocalSnapshots` 를 추가해 전략 경로에 주입한다. UI 경로(`MetricsServices`·`ScreenerRecommend`·`UniverseScores` 의 UI 용도)는 2단계까지 `KrxSnapshots` 그대로다.
유니버스 소스 `KOSPI_MCAP` 과 밸류 직접 계산을 추가한다.

**Tech Stack:** Kotlin, Spring Boot, JdbcTemplate, Flyway, JUnit5 + Testcontainers, 기존 `ExternalHttp`·`StoreCache`·`FetchLedger`·`Cooldowns`.

**Spec:** `docs/superpowers/specs/2026-11-22-official-market-data-phase1-design.md`

**순서:** Task 1(키 필요)을 기다리는 동안 Task 3·4·6·8 을 먼저 할 수 있다. Task 2·5·7·9 는 Task 1 의 실측값(필드 키, 주권 종류 원문, 배당 출처)을 쓴다.
업종은 KIS 마스터 최신 스냅샷이라 백테스트 과거 시점에도 현재 업종을 쓴다(기존 "업종 PIT 폴백" 미래참조 예외와 같은 성격) — Task 13 에서 migration-notes 에 기록.

## Global Constraints

- 1단계 범위 코드(아래 Task 13 목록)는 KRX MDC(`KrxSession`·`KrxMdc`·`KrxStockApi`·`KrxIndexService`·`KrxSnapshots`)와 네이버(`NaverDaily`)를 호출하지 않는다.
- 백테스트·엔진은 외부를 호출하지 않는다. 결측은 `MarketDataNotLoaded` 로 실패 — 0·중립 채움 금지.
- 미래참조 금지: 유니버스는 d **직전 거래일** 시총, 밸류·배당은 **접수/공시일 ≤ d**. `PortfolioLookaheadTest` 항상 초록.
- 원천값(원주가·전일대비·OHLC·거래량·시총·상장주식수) 보존. 수정주가는 파생값.
- KRX 데이터는 재배포 제한(약관) + 공개 저장소 → **테스트 픽스처는 합성값만**. 실응답 원문 커밋 금지.
- 시크릿: `secrets/krx_openapi_key.txt` → 속성 `quantfolio.krx-open.api-key`(configtree 이름 `krx_openapi_key`). 공공데이터포털을 쓰게 되면 `data_go_kr_key`.
- 날짜는 `MarketClock.todayKst()`. Redis 키는 `RedisKeys` 에만.
- 배당세 기본 0.154, 배당은 배당락일 종가 시점 적립(지급일 아님 — 의도한 단순화).
- 실매매: 리밸런싱 시점에 전 거래일 데이터 미적재 → 그날 리밸런싱 안 함 + critical 알림(fail-closed).

## Review Focus

1. 신규 상장 첫날·거래정지일(행 없음 또는 전일대비 null) — 수정주가 계산이 0 나눗셈·NaN 전파 없이 구간을 끊어야 한다 → Task 4 테스트.
2. 휴장일(0행) — "0행 확정 금지" 규칙과 충돌. 휴장일을 영구 재조회하거나, 반대로 실패 응답을 휴장으로 굳히면 안 된다 → Task 5 테스트.
3. 상장폐지 종목 — 상폐 후 날짜에 행이 없는 것은 결측이 아니라 정상. `MarketDataNotLoaded` 를 던지면 안 된다 → Task 6 테스트.
4. 같은 날 같은 종목에 보통주·우선주 — 시총 상위 N 에 우선주가 끼면 안 된다 → Task 7 테스트.
5. 배당 기준일이 휴장일·주말 — 배당락일은 기준일 **직전 거래일**이다(달력 기준) → Task 9 테스트.

---

### Task 1: 스파이크 — Open API·배당 출처 실측 (사용자 키 필요)

**선행:** 사용자가 `secrets/krx_openapi_key.txt` 생성 + 서비스 7개 승인. 없으면 이 Task 만 대기하고 Task 3·6 부터 진행한다.

**Files:** Modify `docs/migration-notes.md` (새 절 "공식 경로 실측 (2026-11)")

- [ ] **Step 1:** scratchpad 스크립트로 각 엔드포인트 1회씩 호출(`AUTH_KEY` 헤더, `basDd`).
  대상: `sto/stk_bydd_trd`·`ksq_bydd_trd`·`stk_isu_base_info`·`ksq_isu_base_info`·`idx/krx_dd_trd`·`kospi_dd_trd`·`kosdaq_dd_trd`.
  응답 키·단위·빈 날짜 응답 형태를 기록한다.
- [ ] **Step 2:** 005930 의 2018-05-03·04 행으로 `종가/(종가−전일대비)−1` 이 실제 등락(±수 %)인지 확인한다(−98% 이면 설계 전제 붕괴 → 멈추고 보고).
- [ ] **Step 3:** 2010 이후 상폐 종목 1개가 상폐 전 날짜 응답에 있는지 확인한다. 고배당 종목 1개의 배당락일 전일대비가 현금배당만큼 조정되지 **않는지** 확인한다(조정되면 Task 9 의 이중 계산 → 보고).
- [ ] **Step 4:** 지수 목록에서 KOSPI200 총수익지수(TR) 유무를 확인한다. 당일분 공개 시각(T+1 몇 시)을 확인한다. 한도 초과 시 응답 형태를 확인한다(가능하면 문서로, 일부러 초과시키지 않는다).
- [ ] **Step 5:** 배당 출처 결정: 공공데이터포털 주식배당정보(키 필요 시 사용자에게 요청)와 DART 배당 사항 중 2010~ 배당기준일·주당배당금이 있는 쪽을 고른다. 결정과 근거를 migration-notes 에 기록한다.
- [ ] **Step 6:** 커밋 `docs: 공식 경로(KRX Open API·배당) 실측 기록`

### Task 2: KRX Open API 클라이언트

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/krxopen/KrxOpenApi.kt`
- Modify: `core/src/main/kotlin/com/quantfolio/common/QuantfolioProperties.kt`(`krxOpen: KrxOpen(apiKey, baseUrl="https://data-dbg.krx.co.kr/svc/apis")`),
  `core/src/main/resources/quantfolio-core.yml`, `docker-compose.yml`(시크릿 `krx_openapi_key`)
- Test: `core/src/test/kotlin/com/quantfolio/market/krxopen/KrxOpenApiTest.kt` (fake `ExternalHttp`, 합성 JSON)

**Interfaces:**
- Produces:
  - `data class OpenDailyRow(val day: LocalDate, val symbol: String, val market: String, val open: Double?, val high: Double?, val low: Double?, val close: Double?, val prevDiff: Double?, val volume: Long?, val tradingValue: Long?, val marketCap: Long?, val shares: Long?)`
  - `data class OpenBaseRow(val symbol: String, val name: String, val market: String, val stockKind: String)`(주권 종류 원문)
  - `data class OpenIndexRow(val day: LocalDate, val name: String, val open: Double?, val high: Double?, val low: Double?, val close: Double?)`
  - `class KrxOpenApi` 의 `stockDaily(day, market: String /*KOSPI|KOSDAQ*/): List<OpenDailyRow>`, `stockBase(day, market): List<OpenBaseRow>`, `indexDaily(day, family: String /*KRX|KOSPI|KOSDAQ*/): List<OpenIndexRow>`
  - `const val SOURCE = "krx_open"`

- [ ] **Step 1: 실패 테스트**
  - `parses daily row with commas and dashes` — `"1,234"`→1234.0, `"-"`→null, `"0"`→0.0(0 은 값)
  - `symbol is 6-digit padded`
  - `empty OutBlock is empty list not error`
  - `html body is SourceSchemaError`
  - `401 is SourceAuthError`
  - `blank api key throws SourceAuthError before any http call`
- [ ] **Step 2:** `./gradlew :core:test --tests '*KrxOpenApiTest*'` → FAIL(클래스 없음)
- [ ] **Step 3:** 구현. 필드 키는 Task 1 기록을 따른다. 분류는 `HttpErrors.check`/`transport` 재사용. 쿨다운은 `Cooldowns` 에 SOURCE 로.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5:** 커밋 `feat(market): KRX Open API 클라이언트`

### Task 3: 스키마 — 원천값 컬럼·배당 테이블

**Files:**
- Create: `core/src/main/resources/db/migration/V9__official_market_data.sql`
- Modify: `core/src/main/kotlin/com/quantfolio/market/store/StockDailyStore.kt`(`StockDaily` 에 `prevDiff: Double?`, `stockKind: String?` 추가, upsert COALESCE 규칙 유지)
- Create: `core/src/main/kotlin/com/quantfolio/market/store/DividendStore.kt`
- Test: `core/src/test/kotlin/com/quantfolio/market/store/DividendStoreTest.kt`, 기존 `StockDailyStoreTest` 에 케이스 추가

**Interfaces:**
- Produces:
  - `data class DividendEvent(val symbol: String, val recordDate: LocalDate, val dps: Double, val announcedOn: LocalDate?, val source: String)`
  - `DividendStore.write(rows: List<DividendEvent>)`, `DividendStore.read(symbols: List<String>, from: LocalDate, to: LocalDate): List<DividendEvent>`
  - SQL: `stock_daily_snapshots` + `prev_diff numeric(18,4)`, `stock_kind varchar(20)`.
    `dividends(symbol varchar(20), record_date date, dps numeric(18,4) not null, announced_on date, source varchar(20) not null, created_at timestamptz default now(), primary key(symbol, record_date))`

- [ ] **Step 1: 실패 테스트**
  - `prevDiff and stockKind round trip`
  - `null prevDiff does not overwrite existing`(COALESCE)
  - `dividend upsert by symbol+recordDate keeps last`
- [ ] **Step 2:** `./gradlew :core:test --tests '*DividendStoreTest*' --tests '*StockDailyStoreTest*'` → FAIL
- [ ] **Step 3:** 마이그레이션 + 저장소 구현. `BaselineMigrationTest` 류가 있으면 V9 반영.
- [ ] **Step 4:** 같은 명령 → PASS
- [ ] **Step 5:** 커밋 `feat(db): 원천 전일대비·주권종류 컬럼, 배당 테이블`

### Task 4: 수정주가 계산

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/AdjustedBars.kt`
- Test: `core/src/test/kotlin/com/quantfolio/market/official/AdjustedBarsTest.kt`

**Interfaces:**
- Produces: `object AdjustedBars { fun build(rows: List<StockDaily>, days: List<LocalDate>): List<Bar> }`
  - 입력은 한 종목의 날짜 오름차순 원천행. `days` 는 같은 길이의 날짜.
  - 출력 `Bar.time` 은 기존 `price_ticks` 와 같은 규약(UTC 자정).

- [ ] **Step 1: 실패 테스트** (합성값)
  - `split day uses prevDiff` — d1 close 2,650,000 / d2 close 53,000 prevDiff 0 → d1 수정종가 53,000(마지막 날 원주가 기준 역산), d2 수익률 0.
  - `last bar equals raw close`
  - `ohlc scaled by same factor, volume raw`
  - `null prevDiff starts new chain without NaN`(Review Focus 1) — 그 날의 인자는 1.
  - `close minus prevDiff zero or negative is treated as null prevDiff`
- [ ] **Step 2:** `./gradlew :core:test --tests '*AdjustedBarsTest*'` → FAIL
- [ ] **Step 3:** 구현. `r_t = close_t/(close_t − prevDiff_t)`, 인자 = 이후 r 의 누적곱 역수.
- [ ] **Step 4:** PASS
- [ ] **Step 5:** 커밋 `feat(market): 원주가+전일대비 수정주가`

### Task 5: 적재 — 백필·일일 작업

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/OfficialIngest.kt`
- Modify: `worker/src/main/kotlin/com/quantfolio/worker/WorkerJobs.kt`(`ingestDailySnapshots`·`ingestDailyOhlcv` 를 `OfficialIngest.daily` 로 교체), `WorkerSchedule.kt`(시각 = Task 1 실측 공개 시각 + 여유)
- Test: `core/src/test/kotlin/com/quantfolio/market/official/OfficialIngestTest.kt` (fake `KrxOpenApi`, Testcontainers DB)

**Interfaces:**
- Consumes: Task 2 `KrxOpenApi`, Task 3 저장소, Task 4 `AdjustedBars`, `FetchLedger`, `PriceTickStore.upsert`
- Produces:
  - `OfficialIngest.backfill(from: LocalDate, to: LocalDate, callBudget: Int): IngestReport`
  - `OfficialIngest.daily(day: LocalDate): IngestReport`
  - `OfficialIngest.rebuildAdjusted(symbol: String)`
  - `OfficialIngest.dartBackfill(fromYear: Int = 2015, callBudget: Int): IngestReport`
    - 대상: 최신 `stock_kind` 보통주 KOSPI 전 종목.
    - 보고서: 11011·11012·11013·11014.
    - 기존 `DartFinancials` 원격 조회 경로를 원장 기반으로 호출하고, 020(한도)이면 즉시 멈춘다.
  - `data class IngestReport(val daysDone: Int, val calls: Int, val stoppedByBudget: Boolean, val failed: List<String>)`
  - 원장 키 `krx_open|<service>|<yyyyMMdd>|<market>`.
  - 휴장일: 같은 날 지수·주식 모두 0행 + 그날이 7일 이상 지난 날 → 원장 `rowCount=0, final=true`. 7일 이내 0행은 미확정. 이것이 "0행 확정 금지" 규칙의 유일한 예외이며 `market-data.md` 에 한 줄 추가한다.

- [ ] **Step 1: 실패 테스트**
  - `backfill resumes from ledger`
  - `budget stops and reports`
  - `holiday older than 7 days is finalized, recent zero rows are not`(Review Focus 2)
  - `api failure on a day records nothing and continues next day, report lists it`
  - `corporate action day triggers full rebuild of that symbol`(r 와 원주가비가 다르면)
  - `dart backfill stops on quota error and resumes next run`
  - `normal day appends one adjusted bar`
- [ ] **Step 2:** `./gradlew :core:test --tests '*OfficialIngestTest*'` → FAIL
- [ ] **Step 3:** 구현. 백필은 날짜 오름차순으로 진행하고, 날마다 주식 2시장 + 지수 3계열 + 기본정보(월 1회면 충분 — 주권 종류는 거의 안 바뀜) 순서로 받는다.
- [ ] **Step 4:** PASS
- [ ] **Step 5:** worker 에 백필 수동 트리거(기존 수동 작업 실행 경로)를 연결하고 커밋 `feat(worker): KRX Open API 백필·일일 적재`

### Task 6: 로컬 전용 읽기·미적재 예외

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/MarketDataNotLoaded.kt`, `core/src/main/kotlin/com/quantfolio/market/official/LocalSnapshots.kt`
- Modify: `OhlcvLoader.kt`(로컬 전용 경로: Naver 호출 제거, 결측 시 예외), `market/dart/DartFinancials.kt`(로컬 전용 플래그 — 원장 확정 없으면 예외),
  `web/.../api/ApiErrorHandler.kt`(→ 409, body `{"detail", "kind", "key", "missing"}`)
- Test: `LocalSnapshotsTest.kt`, `OhlcvLoaderLocalTest.kt`, `ApiErrorHandler` 테스트 케이스

**Interfaces:**
- Produces:
  - `class MarketDataNotLoaded(val kind: String, val key: String, val missing: List<Pair<LocalDate, LocalDate>>) : RuntimeException` — `DataSourceError` 하위 아님.
  - `class LocalSnapshots : SnapshotSource` — `fundamentals`(Task 8 계산 위임), `marketCap`, `priceChange`, `marketOhlcvSnapshot`, `indexOhlcv` 를 로컬에서.
    `netPurchases`·`indexTickers`·`indexName` 은 `MarketDataNotLoaded("unsupported_in_phase1", …)`.
  - Spring: `@Qualifier("local") SnapshotSource`. 기존 주입처는 그대로 두고, Task 10 에서 전략 경로만 교체한다.

- [ ] **Step 1: 실패 테스트**
  - `missing trading days inside range throw with exact gaps`
  - `delisted symbol after last row is not missing`(Review Focus 3) — 마지막 행 이후 그 종목 행이 없고 같은 날 시장 전체 행은 있으면 상폐로 본다.
  - `holiday is not missing`(원장 휴장 확정일)
  - `ohlcv loader never calls naver`(fake 로 호출 0 단언)
  - `dart local only throws when ledger has no final entry`
  - `api maps MarketDataNotLoaded to 409 with missing ranges`
- [ ] **Step 2:** 해당 테스트 → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** PASS
- [ ] **Step 5:** 커밋 `feat(market): 로컬 전용 시장데이터 읽기와 미적재 예외`

### Task 7: 유니버스 소스 KOSPI_MCAP

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/McapUniverse.kt`
- Modify: `strategy/StrategySchemas.kt`(`universe_rule.source` Lit 에 `"KOSPI_MCAP"` 추가), `backtest/BacktestRunner.kt`(`pitPool`: KOSPI_MCAP 은 `McapUniverse`, 그 외 지수는 `index_constituents` 로컬만, `session.requireAuth()` 제거),
  `rebalance/Rebalance.kt` 영향 없음 확인
- Test: `McapUniverseTest.kt`, `PortfolioLookaheadTest.kt` 에 케이스 추가, `StrategyConfigParser` 테스트에 소스 값 추가

**Interfaces:**
- Produces: `McapUniverse.members(asOf: LocalDate, n: Int): List<String>` — asOf **직전 거래일** KOSPI, `stockKind == 보통주`(Task 1 에서 확인한 원문 값), 시총 내림차순, 동률은 종목코드 오름차순.
  직전 거래일 행이 없으면 `MarketDataNotLoaded`.

- [ ] **Step 1: 실패 테스트**
  - `uses previous trading day caps not same day`
  - `preferred shares excluded`(Review Focus 4)
  - `tie broken by code`
  - `lookahead: perturb caps after cut, members up to cut unchanged`
  - `schema accepts KOSPI_MCAP and pick as N`
- [ ] **Step 2:** → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** 해당 테스트 + `PortfolioLookaheadTest` → PASS
- [ ] **Step 5:** 커밋 `feat(strategy): 시총 상위 N 유니버스 소스`

### Task 8: 밸류 지표 직접 계산

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/Valuation.kt`
- Modify: `LocalSnapshots.fundamentals` 가 이것을 쓰도록
- Test: `ValuationTest.kt`

**Interfaces:**
- Produces: `Valuation.compute(asOf: LocalDate, symbols: List<String>): List<StockDaily>`(per·pbr·div 만 채움)
  - 입력: 시총·종가 = asOf 직전 거래일, 재무 = 접수일 ≤ asOf 인 최신 사업보고서(11011) 연결 우선·별도 폴백의 지배주주 당기순이익·지배주주 자본(기존 `DartMetrics` 계정 매핑 재사용), DPS = 기준일 ≤ asOf 최신 연간 합계.

- [ ] **Step 1: 실패 테스트**
  - `per = cap / net income`
  - `loss gives null per`
  - `non positive equity gives null pbr`
  - `report received after asOf is ignored`
  - `no dividend gives div 0.0 only when report says zero, null when unknown`
- [ ] **Step 2:** → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** → PASS
- [ ] **Step 5:** 커밋 `feat(market): DART·시총 기반 PER·PBR·배당수익률`

### Task 9: 배당 적재·백테스트 반영

**Files:**
- Create: `core/src/main/kotlin/com/quantfolio/market/official/DividendIngest.kt`(Task 1 에서 고른 출처)
- Modify: `strategy/StrategySchemas.kt`(rebalance 에 `F("dividend_tax", FloatT, 0.154, ge = 0, le = 1)`), `backtest/BacktestRunner.kt`(`withDividends` — `_dividends` 를 config 에 싣는다. `withSectorMap` 패턴),
  `backtest/PortfolioBacktest.kt`(배당락일 종가 시점에 현금 적립), 결과에 `dividend_income` 추가,
  벤치마크: Task 1 에서 TR 지수가 있으면 `benchmark_index` 해석에 TR 을 쓰고, 없으면 결과 `assumptions` 에 "벤치마크는 배당 제외" 추가.
- Test: `DividendIngestTest.kt`, `PortfolioDividendTest.kt`, `PortfolioLookaheadTest.kt` 케이스 추가

**Interfaces:**
- Consumes: Task 3 `DividendStore`, 달력(로컬 지수 날짜)
- Produces: config 키 `_dividends: Map<String, List<Map<String, Any?>>>` (`ex_date`, `dps`).
  배당락일 = 기준일 **직전 거래일**(Review Focus 5).

- [ ] **Step 1: 실패 테스트**
  - `hold one stock one year: total minus price-only return equals dps*(1-tax)/price within 1e-9`
  - `ex date is previous trading day when record date is holiday`
  - `no dividends gives identical result to before`(회귀)
  - `lookahead: dividend events after cut do not change results up to cut`
  - `dividend_tax default 0.154 parsed`
- [ ] **Step 2:** → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** 해당 테스트 + `PortfolioLookaheadTest` → PASS
- [ ] **Step 5:** 커밋 `feat(backtest): 현금배당 반영`

### Task 10: 백테스트 전략 경로 교체

**Files:**
- Modify: `backtest/BacktestRunner.kt` — `snapshots` 를 `@Qualifier("local")` 로, `indexClose` 는 로컬 `index_ohlcv`, `krx.marketCaps`·`krx.sectorMap` 은 로컬 시총·KIS 마스터 업종(지수업종중분류)으로,
  `factorCoverageWarnings` → 리밸런싱 시점마다 가중 팩터 입력 커버리지 검사, **50% 미만이면 `MarketDataNotLoaded("factor_coverage", …)`**
  (근거: 20종목 선정에 후보의 절반 이상 점수가 없으면 순위가 결측 패턴에 좌우된다 — 측정 후 조정 가능), `flow` 가중치 > 0 이면 즉시 미지원 오류.
  `metrics/PanicService.kt` — 생성자의 `SnapshotSource` 를 local 로(전략·UI 공용이지만 UI panic 도 로컬로 옮겨도 기능 동일).
- Test: `BacktestRunnerLocalTest.kt`(외부 fake 들이 호출 0회임을 단언), panic 기존 테스트 유지

- [ ] **Step 1: 실패 테스트**
  - `rebalance backtest with KOSPI_MCAP runs on local data only`(fake KRX·Naver·DART 원격 호출 0)
  - `coverage below half at any rebalance throws`
  - `flow weight rejected in phase1`
- [ ] **Step 2:** → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** `./gradlew :core:test` 전체 → PASS(오라클 테스트 포함)
- [ ] **Step 5:** 커밋 `feat(backtest): 로컬 전용 데이터로 전환`

### Task 11: 실매매 리밸런싱 교체 (fail-closed)

**Files:**
- Modify: `engine/.../RebalanceRunner.kt` — `resolveUniverse`: KOSPI_MCAP → `McapUniverse`. `MarketDataNotLoaded` 면 **리밸런싱 건너뜀 + `AlertSeverity.critical`**(기존 config.universe 폴백은 KOSPI_MCAP 에 적용하지 않음).
  기존 지수 소스(KOSPI200·KOSPI100·KRX300)는 `IndexConstituentStore` 로컬만 읽는다. 없으면 기존 폴백 + 경보 동작을 유지한다(원본 동작, 2단계에서 존폐 결정).
  `sectors()` → KIS 마스터 업종. `marketCaps` → 로컬. `Runners.kt` ctx 에 필요한 빈 추가.
- Test: `engine` 의 RebalanceRunner 테스트에 케이스 추가

- [ ] **Step 1: 실패 테스트**
  - `missing previous day data skips rebalance and publishes critical once`
  - `no orders placed when universe not loaded`
  - `KOSPI_MCAP universe resolves from local`
- [ ] **Step 2:** `./gradlew :engine:test --tests '*RebalanceRunner*'` → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** PASS + `./gradlew :engine:test` 안전 불변식 테스트 전부 초록
- [ ] **Step 5:** 커밋 `feat(engine): 리밸런싱 유니버스·업종 로컬 전환, 미적재 시 정지`

### Task 12: 거래일 달력·종목 목록·업종 스냅샷

**Files:**
- Modify: `market/MarketCalendar.kt` — 과거: 로컬 `index_ohlcv`(KOSPI 1001) 날짜. 오늘·미래: KIS 국내휴장일조회(`KisClient` 에 `holidays(from: LocalDate): Map<LocalDate, Boolean>` 추가, 결과는 하루 캐시).
  `market/symbols/SymbolCatalog.kt` — 최신 `stock_base` 로컬 + KIS 마스터(FDR·MDC 제거).
  `worker/WorkerJobs.snapshotSectorMap` — KRX 업종 스냅샷 대신 KIS 마스터 스냅샷만(이미 적재 중이면 작업 삭제).
- Test: `MarketCalendarTest`(기존 수정), `SymbolCatalogTest`

- [ ] **Step 1: 실패 테스트**
  - `past business day from local index dates`
  - `today holiday from KIS`
  - `KIS failure for today falls back to weekday rule and logs`(장 개장 판단을 막지 않되 기록)
  - `catalog built without KRX MDC or FDR`
- [ ] **Step 2:** → FAIL
- [ ] **Step 3:** 구현
- [ ] **Step 4:** PASS
- [ ] **Step 5:** 커밋 `feat(market): 달력·종목목록 공식 경로`

### Task 13: 규칙 테스트·문서·운영 전환·리서치 재개 확인

**Files:**
- Modify: `core/src/test/kotlin/com/quantfolio/common/CodeRulesTest.kt` — 다음 파일에서 `KrxSession|KrxMdc|KrxStockApi|KrxIndexService|KrxSnapshots|NaverDaily` 참조 금지:
  `BacktestRunner.kt`, `PortfolioBacktest.kt`, `RebalanceRunner.kt`, `Runners.kt`, `PanicService.kt`, `MarketCalendar.kt`, `SymbolCatalog.kt`, `OhlcvLoader.kt`, `WorkerJobs.kt`
- Modify: `docs/migration-notes.md`(의도적 원본 변경: 소스 교체·미적재 실패·배당·업종 체계·fail-closed), `.claude/rules/market-data.md`(소스 표 갱신)

- [ ] **Step 1:** 규칙 테스트 추가 → 위반이 남아 있으면 FAIL, 없으면 PASS 확인
- [ ] **Step 2:** `./gradlew build` 전체 초록
- [ ] **Step 3: 운영 전환(qf-paper)** — 단계마다 사용자 확인:
  - `price_ticks` 덤프를 만든다.
  - 백필을 실행한다(한도 내, 수일 소요 가능).
  - 검증 3종을 수행한다: 분할일, 상폐 종목, 임의 5종목 수익률 대조(네이버 기존값과 일간 수익률 상관 > 0.99).
  - `price_ticks` 를 교체한다.
- [ ] **Step 4:** 리서치 T1 을 KOSPI_MCAP(pick 100)으로 1회 실행한다. `external_fetches` 에 실행 시간대 외부 호출 0, 결과 `factor_warnings` 없음, `dividend_income > 0` 을 확인한다.
  `research/hypotheses.md` 에 유니버스 변경을 날짜·사유와 함께 추가한다.
- [ ] **Step 5:** 커밋 `test: 전략 경로 MDC·네이버 금지 규칙, 문서 갱신`
