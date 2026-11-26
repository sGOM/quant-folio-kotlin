# 공식 경로 시장데이터 2단계 — 설계

## 의도

1단계(`2026-11-22-official-market-data-phase1-design.md`)로 전략 경로는 KRX Open API 로컬 적재만 읽는다.
화면 경로(종목·섹터 지표, 턴어라운드 스크리너, KOSPI200 추천)는 아직 KRX MDC 를 그때그때 조회하고,
네이버 일봉 로더도 `PriceHistory` 빈으로 살아 있다.

사용자 목표(2026-11-23): **시장데이터 출처를 KRX Open API 하나로.** 이유는 둘이다.

- **약관·차단:** MDC 는 약관상 자동 수집 금지이고 2026-11-22 IP 가 하루 차단됐다(차단 중 Open API 도 401).
  화면이 MDC 를 쓰는 한 다시 일어날 수 있다.
- **숫자 단일화:** 같은 종목의 PER·수익률이 화면(MDC)과 전략(로컬 계산)에서 다르게 나오면 화면을 믿고 전략을 판단할 수 없다.

## 목표 / 비목표 / 반목표

- **목표**
  - `main` 소스에서 KRX MDC·네이버 차트 코드와 KRX 로그인 시크릿이 사라진다.
  - 화면 4종이 로컬 테이블만 읽는다. 미적재는 1단계처럼 `MarketDataNotLoaded`(API 409)이다.
  - 지수 구성종목 소스(KOSPI200·KOSPI100·KRX300)를 폐지하고 시총 상위 N(`KOSPI_MCAP`)으로 통일한다.
  - 섹터 지표의 업종지수를 Open API 지수 응답에서 적재한다.
- **비목표**
  - KOFIA(취약성 화면)·DART·KIS(마스터·시세·주문)·뉴스는 그대로 둔다. MDC·네이버가 아니다.
  - `index_constituents`·MDC 시절 `index_ohlcv`(1002~1894)·MDC `stock_daily_snapshots` 행은 지우지 않는다. 읽는 코드만 없앤다.
  - 업종 쏠림 보완(업종별 상한 등)은 하지 않는다. 필요하면 별도 설계.
- **반목표**
  - 조용한 저하: 미적재를 빈 화면·0·중립으로 "성공" 처리.
  - 가격 단위 혼합: Open API 수정주가와 네이버 수정주가가 `price_ticks` 에 섞이는 것(`market-data.md` 함정).
  - 의미가 바뀐 전략 이전: `pick` 이 모멘텀 필터 개수에서 풀 크기로 바뀌는 식의 조용한 변경.

## 제약

- **CLAUDE.md 절대 금지:** 미래참조 금지. 시총 유니버스는 결정일 직전 거래일 데이터만 쓴다.
- **원본 동작 보존이 원칙.** 이 단계의 변경은 모두 사용자가 결정한 의도적 차이다. `docs/migration-notes.md` 에 기록한다.
- **Open API 한도:** 서비스별 일 10,000회.
- **운영:** 운용 중 전략은 0개다(2026-11-23). qf-paper 백필은 진행 중이고, 2단계 배포는 백필과 검증 3종이 끝난 뒤에 한다.
- **공개 저장소:** 실데이터·`research/` 는 커밋하지 않는다.

## 접근법

| 결정 | 안 | 선택 |
|---|---|---|
| 지수 구성종목 대체 | A 지수 소스 유지 + KIS 마스터 편입 플래그 이력 / B 소스 폐지, 시총 상위 N 통일 / C 전략은 B, 추천 화면만 KIS 마스터 | **B** (사용자, 2026-11-23) |
| 시총 풀 크기 표현 | 가 `pool_size` 칸 추가 / 나 소스 이름에 크기(시총100·200·300) | **가** (사용자) |
| 섹터 지표 | 1 Open API 업종지수 적재 / 2 종목+KIS 업종으로 직접 계산 / 3 화면 삭제 | **1** (사용자) |

**B 를 고른 이유**
- KIS 마스터는 "오늘"만 주므로 빠진 날을 되살릴 수 없다. 받아 둔 이력도 2026-08-19부터라 과거 검증이 영원히 불가능하다.
- 시총은 시세와 같은 Open API 표에 있어 과거를 언제든 다시 받을 수 있다.
- KIS 의존(고정폭 파일 형식, 증권사 교체)도 늘지 않는다.

**B 의 대가 (2026-11-23 실측)**
- KOSPI100 대 시총 상위 100(2016-01~2018-05, 월별 29회): 종목 수 겹침 평균 88%(최저 82%), 시총 가중 98.1%.
- KOSPI200 대 시총 상위 200(2026-08-19~09-28, 28회): 겹침 85%, 시총 가중 99.5%.
- 차이는 순위 경계 종목이다. 시총 쪽에는 최근 급등 중형주·금융주가 들어오고, 지수 쪽의 식품·소재 업종 대표주가 빠진다.
- 교체 빈도는 시총 상위가 월 3.2종목, 지수가 0.4종목이다.
- 남은 구간(KOSPI100 2018-06~2022-12, KOSPI200 2025-01~2026-07)은 백필 뒤 같은 비교를 돌려 이 절에 덧붙인다.

## 설계 (사용자 결정 반영)

### 1. 유니버스 소스

- **스키마:** `universe_rule.source` 를 `fixed | KOSPI_MCAP` 로 줄인다(`StrategySchemas`).
  - `pool_size` 를 추가한다(정수, 1~300, 선택). `KOSPI_MCAP` 에서만 의미가 있다. 있으면 풀 크기이고, 없으면 지금처럼 `pick` 이 풀 크기다(리서치 T1 설정 호환).
  - `pick` 은 모멘텀 필터 개수다(최대 100 유지). `pool_size` 가 있으면 `pick ≤ pool_size` 를 검증한다.
- **백테스트·엔진:** 시총 상위 `pool_size ?: pick` 개의 풀에 기존 모멘텀 필터(`pick`)를 적용한다.
  - 지수 소스 분기(`localMembers`·`IndexConstituentStore`·엔진의 `config.universe` 폴백 경보)는 삭제한다.
  - `min_market_cap` 은 계속 무시하고 경고만 남긴다. 시총 상위 200의 200위가 이미 1조 원 안팎이라 5천억 조건이 걸리지 않기 때문이다.
- **Flyway V11 (데이터 이전):**
  - 소스 `KOSPI200` → `KOSPI_MCAP` + `pool_size: 200`, `KOSPI100` → 100, `KRX300` → 300. `pick` 등 나머지 키는 그대로 둔다.
  - 대상은 전략 23·24·25·26·27·32(KOSPI200)와 37(KOSPI100)이다. 운용 중인 것은 없다.
  - 모든 전략의 `factor_weights.flow`, `selection.flow_window`, `selection.flow_denom` 키를 제거한다(아래 flow 삭제).
- **프론트 `RebalanceFields.tsx`:**
  - 지수 3개 항목을 "KOSPI 시총 상위(시점별)" 하나로 바꾼다.
  - 이 항목을 고르면 "후보 풀 크기" 입력칸이 나타난다.

### 2. 일봉: 단일 로컬 `PriceHistory`

- 엔진 `LocalMarketData` 의 로컬 구현(`LocalOhlcvCoverage.require` + `price_ticks`)을 core 의 `@Component` 로 옮겨 유일한 `PriceHistory` 빈으로 둔다.
- 영향받는 곳(지금은 네이버로 간다):
  - `TechIndicatorLoader`: 종목 지표·추천
  - `UniverseScores` 빈
  - `FillQualityReport`: web 분석, worker 작업, **engine `LiveGateService`**
- `LiveGateService` 는 1단계 규칙(전략 경로 네이버 금지)이 인터페이스 주입 때문에 놓친 누수다. 이 변경으로 함께 막힌다.
- `OhlcvLoader`(`ensureCoverage` 포함)와 `NaverDaily` 를 삭제한다.
- **체결 정합 리포트의 구간 끝(설계자 판단, 원본 동작 보존):** `FillQualityReport` 는 [최초 체결일−45일, 최근 체결일+10일]을 읽는다.
  - 네이버는 있는 날까지만 돌려줬다. 로컬은 미래 평일·미공개일까지 요구하므로, 그대로 두면 최근 체결이 있는 순간 모든 종목이 미적재가 된다. 그러면 표본이 0이 되고 `LiveGate` 가 실전을 계속 막는다.
  - 그래서 구간 끝을 **마지막 적재 거래일**로 자른다. 그 뒤 체결은 종가가 없어 원본에서도 표본이 되지 않았다.
  - 구간 **안쪽**의 결손은 그대로 미적재로 다룬다.

### 3. 화면

| 화면 | 변경 |
|---|---|
| 종목 지표·턴어라운드 스크리너 | `SnapshotSource` 를 로컬 구현으로 바꾼다. 계산 로직은 그대로 둔다 |
| 추천 | 구성종목 = **기준일 당일** 시총 상위 200 KOSPI 보통주(`McapUniverse` 와 같은 정렬·주권 종류 규칙). 경로 `/api/recommend/kospi200` → `/api/recommend/mcap200`, 응답의 지수 라벨과 화면 문구도 "시총 상위 200" 으로 바꾼다 |
| 섹터 지표 | 아래 4 의 업종지수를 로컬에서 읽는다. 지수 목록·이름도 로컬에서 가져온다 |
| 패닉 | 변경 없음(1단계에서 로컬화) |

- 화면 기준일은 지금처럼 `MetricsCommon.lastBusinessDay()` 다.
- 전날 데이터 공개(T+1) 전에는 409 가 날 수 있다. 패닉 화면과 같은 동작이다(설계자 판단).
- **섹터 화면의 결측 정책(설계자 판단):** 지수 키의 **첫 행 이전**은 "그때는 없던 지수"로 보고 결측이 아니다. `LocalOhlcvCoverage` 의 상장 전 규칙과 같은 생각이다.
  - 첫 행 이후 기대 거래일이 빠졌으면 미적재이고, 화면 전체가 409 다. 종목 화면과 같은 동작이다.
  - 지금 `SectorMetricsService` 의 지수별 `catch (e: Exception)` 가 `MarketDataNotLoaded` 를 삼키므로, 이 예외는 다시 던지게 고친다.
  - 이름이 바뀐 지수는 새 키로 이력이 짧게 시작한다. 기존 `bars.size < 21` 건너뜀과 짧은 이력 처리로 다룬다.
- **`LiveFactorInputs.marketCaps`(web):** MDC 를 쓰는 `KrxIndexService` 에서 로컬 시총으로 바꾼다.

### 4. 업종지수 적재

- `OfficialIngest` 가 지수 응답(`kospi_dd_trd`·`kosdaq_dd_trd`)의 **모든 행**을 `index_ohlcv` 에 저장한다. 기존 3개 코드(1001·1028·2001)는 그대로다.
- **업종지수 키:** Open API 는 지수 코드를 주지 않고 이름(`IDX_NM`)만 준다. 그래서 시장 접두어 + 원문 이름으로 결정적인 키를 만들고 `index_name` 에 원문을 둔다.
  - MDC 시절 숫자 코드와는 섞지 않는다.
  - 키 형식은 빌더가 정한다. `index_code varchar(20)` 을 넘으면 컬럼을 넓히는 마이그레이션을 둔다.
- **거래량·거래대금도 저장한다(설계자 판단).** 지수 응답에 `ACC_TRDVOL`·`ACC_TRDVAL` 이 있다(1단계 실측). 섹터 지표의 거래대금 추세(`valueTrend`)에 필요하다.
  - 기존 3개 지수 행은 업종지수 백필 구간만 다시 쓰고(upsert), 그 이전 행은 비워 둔다. 쓰는 곳이 없다.
- **섹터 화면 목록:** 시장별로 이 키 집합에서 기준 지수(코스피·코스닥)를 뺀 전체다. 원본도 시장의 지수 전체였다.
  - 실제 응답의 지수 이름 목록을 한 번 실측해 `docs/migration-notes.md` 에 남긴다.
- **업종지수 1년치 백필:** 지수 서비스만 다시 부르는 백필 모드를 둔다. 1단계 원장에서 이미 확정된 날도 다시 받는다.
  - 범위는 섹터 계산에 필요한 252거래일 + 여유분(약 400일, 2계열 × 약 280회)이다.
  - 주식 적재·원장 확정 규칙은 건드리지 않는다.

### 5. 삭제

- **MDC:** `market/krx/` 전체(`KrxSession`·`KrxMdc`·`KrxStockApi`·`KrxIndexService`·`KrxSnapshots`)와 테스트, `FakeKrxTransport`.
- **기타 코드:** `IndexMembers`, `UniverseBuilder`(호출처 없음), `IndexConstituentStore`.
- **`SnapshotSource`:** 인터페이스는 로컬 구현만 남긴다. 구현이 없는 `netPurchases`·`indexTickers`·`indexName` 을 뺀다.
  - 패키지 위치는 `market/krx` 밖으로 옮긴다.
  - `@Primary` 원격이 사라지므로 `@Qualifier("local")` 의 존폐는 빌더가 정한다.
- **flow 팩터:** `FactorScores`·`UniverseScores`·스키마의 flow 경로, 백테스트의 flow 거절 분기. pead 거절은 유지한다.
- **설정·시크릿:** `quantfolio.krx.id/pw`, compose 의 `krx_id`·`krx_pw` 시크릿, `secrets/README.md` 해당 줄.
- **규칙 테스트(`CodeRulesTest`):** `main` 전체에서 MDC·네이버 호스트 문자열(`data.krx.co.kr`, 네이버 차트 호스트)을 금지한다. 재유입을 막기 위해서다.

### 6. 문서

- `market-data.md`: KRX MDC 절과 `ohlcv/` 행을 삭제한다. 함정 중 MDC 로그인·차단 줄은 교훈만 남기고 정리한다.
- `strategy-config.md`: 소스 목록과 `pool_size` 를 반영한다.
- `docs/migration-notes.md`: 지수 소스 폐지, 추천 대상 변경, 섹터 지수 출처, flow 삭제를 원본 차이로 기록한다.
- `research/hypotheses.md`(비공개): 리서치 설정에 `pool_size: 100` 을 명시한다.

## 설계자 판단 (사용자 확인 대상)

- 화면 기준일은 `lastBusinessDay()` 그대로 두고, 공개 전 409 를 허용한다.
- 추천 API 경로 이름(`mcap200`)과 화면 문구.
- `pool_size` 상한 300.
- 데이터 테이블(`index_constituents` 등)은 남긴다.
- 섹터 결측 정책: 지수 첫 행 이전은 결측 아님, 이후 결손은 화면 전체 409.
- 체결 정합 리포트 구간 끝을 마지막 적재 거래일로 자른다(실전 게이트에 영향).
- 업종지수 거래량·거래대금 저장.
- V11 은 전략 ID 가 아니라 소스 값으로 이전한다. 화면의 "후보 풀 크기" 기본값은 200 이다.

## 빌더에게 맡기는 것

- 업종지수 키 형식, 백필 모드의 진입점(1단계 `BackfillRunner` 설정 확장 등).
- `SnapshotSource` 새 위치와 한정자 정리.
- 추천의 "당일 시총 상위" 를 `McapUniverse` 에 메서드로 둘지 여부. 정렬·주권 종류 규칙은 반드시 공유한다.
- 삭제 순서와 커밋 단위.

## 검증 (완료 조건)

- `./gradlew build` 전체 통과. 삭제된 MDC·네이버 테스트를 뺀 기존 테스트는 유지한다.
- `CodeRulesTest` 가 MDC·네이버 호스트 문자열 0건을 강제한다.
- Flyway V11 테스트: KOSPI200·KOSPI100 전략이 `KOSPI_MCAP` + `pool_size` 로 바뀌고 `pick` 이 보존되며, flow 키가 사라진다.
- 백테스트 단위 테스트: `pool_size` 200 + `pick` 40 이면 결정일마다 시총 상위 200 중 모멘텀 상위 40.
- qf-paper(백필 완료 후):
  - 화면 4종이 200을 낸다.
  - 화면 호출 동안 `external_fetches` 에 MDC·네이버 소스 기록이 없다.
  - 섹터 화면에 업종이 나온다.
  - 지수 대 시총 비교(남은 구간)를 이 문서에 덧붙인다.
