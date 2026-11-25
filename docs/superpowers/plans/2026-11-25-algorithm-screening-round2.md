# 논문 알고리즘 선별 2라운드 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** B1 밸류(Kotlin `value`), B2 잔차 모멘텀(Kotlin `ResidualMomentum`), B3 Ledoit-Wolf 최소분산 비중을 algorithm 폴더 백테스터에 넣고, 새 합격 규칙으로 선별한다.

**Architecture:** B2 는 Kotlin 정의를 Python 으로 이식하고 원본 정답지로 검증한다. B1 입력(PER·PBR·DIV)은 이식하지 않고 worker 1회 실행 모드로 Kotlin `Valuation` 값을 내보내 스냅샷에 붙인다. B3 는 Python 에만 만든다. 잠정 실행(lowvol 기준, B2·B3)은 지금, 최종 판정은 DART 적재 후 1라운드 2단계와 같은 스냅샷·교정으로.

**Tech Stack:** Python(algorithm `.venv`: NumPy·pandas·pyarrow·scipy 1.18·scikit-learn 1.9.1·unittest), Kotlin + Gradle(`backend/worker`).

**Spec:** `docs/superpowers/specs/2026-11-25-algorithm-screening-round2-design.md` (1라운드 설계·계획 `2026-11-24-algorithm-screening*.md` 의 공통 규칙 상속)

## Global Constraints

- 1라운드 계획의 Global Constraints 전부 그대로(구간·유니버스·체결·비용·시드 20261124·결측 NaN·출력 덮어쓰기 금지·실측 결과 커밋 금지·algorithm 폴더는 git 아님).
- 변형: B1 `value` 주 .2 / 이웃 .1·.3, B2 `residual_momentum` 주 .2 / 이웃 .1·.3 (`reweight`), B3 `('minvar', 252, .10)` 주 / `('minvar', 126, .10)`·`('minvar', 504, .10)` 이웃.
- B2 = Kotlin 기본값: 회귀 36개월, 모멘텀 11개월, skip 1, 최소 관측 24, 시장 = KOSPI200 지수 `1028` 종가.
- B3: 결정일까지 lookback 거래일 일수익률, 종목별 유효 ≥ 200(126일 변형은 ≥ 100), 미달 종목이 하나라도 있거나 `n × cap < 1` 이면 그 결정일은 동일가중(횟수 보고). `sklearn.covariance.LedoitWolf`, 롱온리·종목당 ≤ cap·합 1, `scipy.optimize.minimize(method='SLSQP')`.
- 2라운드 규칙: 항목 3 = Ledoit-Wolf 일간 Sharpe 차 양측 p, B1~B3 주 변형 3개 Holm 보정 < 0.05 / 항목 7(B1·B2) = IC 구간이 + 방향으로 0 을 벗어남 AND Fama-MacBeth NW t > 3.0 / 항목 8 = B1·B2 위약 200회, B3 무작위 비중 200회, Δ > 95분위 / PBO(기준+2라운드 변형 9) < 0.5. 나머지는 1라운드와 같다.
- 외부 호출(data.go.kr 배당 1회, OpenDART 백필)은 실패하면 반복 재시도하지 않고 보고한다. 전략 23·37·38 시작 금지. 비밀번호는 `QF_PASSWORD` 환경변수로만.
- algorithm 폴더 구현마다 docstring 에 한국어 설명·논문 링크(확인한 URL만), README "구현 목록"에 행 추가.

## Review Focus

1. **밸류 내보내기 일부 실패** — 어느 결정일이든 `MarketDataNotLoaded` 등으로 실패하면 파일을 아예 쓰지 않는다(일부 날짜만 있는 파일은 Python 에서 '결측'으로 보여 커버리지 오류로 뭉개진다). → Task 2 `export writes nothing when any date fails`.
2. **최소분산 불가능** — 후보 < 10(cap .10 에서 n·cap < 1)이거나 이력 부족이면 동일가중으로, 횟수를 `Result.weight_fallbacks` 에. → Task 5 `test_minvar_infeasible_falls_back`.
3. **지수 결측 달** — 1028 이 빈 달은 Kotlin 처럼 그 달과 다음 달 수익률이 NaN(채우지 않음). → Task 4 정답지(원본 케이스) + `test_resid_mom_gap_month_is_nan`.
4. **옛 스냅샷 재현** — 새 테이블(`index1028`·`valuation`)이 없는 1라운드 스냅샷도 그대로 읽혀야 한다(1라운드 결과 재현). → Task 3 `test_load_old_snapshot_without_new_tables`.
5. **밸류 위약** — PER·PBR·DIV 를 같은 순열로 섞어야 한다(따로 섞으면 종목 간 조합이 깨져 위약이 아니라 잡음). → Task 5 `test_value_placebo_shuffles_jointly`.

---

### Task 1: worker 배당 1회 실행 트리거

**Files:**
- Create: `backend/worker/src/main/kotlin/com/quantfolio/worker/DividendRunner.kt`
- Test: `backend/worker/src/test/kotlin/com/quantfolio/worker/DividendRunnerTest.kt`

**Interfaces:**
- Consumes: `WorkerJobs.ingestDividends(): Map<String, Any?>`(기존)
- Produces: 속성 `quantfolio.dividends.run-once=true` 로 기동 시 1회 실행. 로그 `수동 배당 적재 결과: {...}` / 실패 `수동 배당 적재 실패`(Task 3 가 이 문구를 기다린다).

- [ ] **Step 1: 실패 테스트** — `DartBackfillRunnerTest` 형식으로 두 개: `runs ingest once`(목 `jobs` 에서 `ingestDividends()` 1회 호출 검증), `unexpected exception does not fail worker startup`(`IllegalStateException` 을 던지는 목으로 `run` 이 예외 없이 끝남).
- [ ] **Step 2: 실행 → 실패 확인** — `cd backend && ./gradlew :worker:test --tests '*DividendRunnerTest*'` → 컴파일 실패(`DividendRunner` 없음).
- [ ] **Step 3: 구현** — `@Component @ConditionalOnProperty("quantfolio.dividends.run-once", havingValue = "true") class DividendRunner(private val jobs: WorkerJobs) : ApplicationRunner`. `BackfillRunner` 처럼 예외는 로그만(기동 실패 → 재기동 반복 방지).
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS.
- [ ] **Step 5: 커밋** — `feat(worker): 배당 이력 1회 적재 트리거(quantfolio.dividends.run-once)`

### Task 2: worker 밸류 내보내기 1회 모드

**Files:**
- Create: `backend/worker/src/main/kotlin/com/quantfolio/worker/ValuationExportRunner.kt`
- Test: `backend/worker/src/test/kotlin/com/quantfolio/worker/ValuationExportRunnerTest.kt`

**Interfaces:**
- Consumes: `SnapshotSource.fundamentals(asOf: LocalDate, markets: List<String>): List<StockDaily>`(백테스트 밸류 경로와 같은 호출, markets = `listOf("KOSPI", "KOSDAQ")`).
- Produces: 속성 `quantfolio.valuation-export.out=<파일>` + `quantfolio.valuation-export.dates=2017-06-01,2017-07-03,...`. CSV 헤더 `date,symbol,per,pbr,div`, `market == "KOSPI"` 행만, (date, symbol) 오름차순, null 은 빈 칸. 로그 `수동 밸류 내보내기 결과: rows=<n> dates=<n>` / `수동 밸류 내보내기 실패`.

- [ ] **Step 1: 실패 테스트** — 목 `SnapshotSource` 로 세 개:
  - `writes kospi rows sorted with blank nulls` — 두 날짜, KOSDAQ 행은 빠지고 `per=null` 은 `,,` 로 쓰인다(파일 전체 문자열 비교).
  - `export writes nothing when any date fails` — 둘째 날짜에서 `MarketDataNotLoaded` → 출력 파일이 없다.
  - `unexpected exception does not fail worker startup`.
- [ ] **Step 2: 실행 → 실패 확인** — `./gradlew :worker:test --tests '*ValuationExportRunnerTest*'` → 컴파일 실패.
- [ ] **Step 3: 구현** — 모든 날짜를 메모리에 모은 뒤 `<out>.tmp` 에 쓰고 `Files.move(ATOMIC_MOVE)`. 하나라도 예외면 쓰지 않고 로그. 숫자는 `toString()`(Kotlin Double 기본 표기 — Python `float()` 로 읽힌다).
- [ ] **Step 4: 통과 확인** — 같은 명령 → PASS. 이어서 `./gradlew :worker:test` 전체 PASS.
- [ ] **Step 5: 커밋** — `feat(worker): Valuation 값 1회 내보내기(quantfolio.valuation-export) — 선별 백테스트 입력`

### Task 3: 배당 적재(운영) + 스냅샷 확장

**Files:**
- Modify: `C:\Users\pooh6\workspace\algorithm\screen_data.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_data.py`

**Interfaces:**
- Consumes: Task 1 트리거, Task 2 CSV 형식.
- Produces: `PRICE_FROM = '2014-01-02'`, `FILES` 에 `'index1028'`(열 `date, close`). `Data.market: pd.Series | None`(index=date, 1028 종가), `Data.valuation: dict | None`(`date -> {symbol: (per, pbr, div)}`, 값 None 허용). `attach_valuation(snapshot_dir, csv_path) -> Path`(스냅샷에 `valuation.parquet` 추가, 매니페스트 `sha256.valuation` 기록, 이미 있으면 `FileExistsError`). `load` 는 매니페스트에 있는 파일만 읽는다.

- [ ] **Step 1: 배당 1회 적재(운영)** — worker 이미지 재빌드 후 `docker compose -p qf-paper -f docker-compose.yml -f docker-compose.paper.local.yml run -d --no-deps --name qf-paper-dividends -e 'SPRING_APPLICATION_JSON={"quantfolio":{"scheduling":{"enabled":false},"dividends":{"run-once":true}}}' worker`, 로그에서 `수동 배당 적재 결과|실패` 한 줄을 기다린 뒤 컨테이너 삭제. 확인: `SELECT count(*), count(DISTINCT symbol), min(record_date) FROM dividends` 가 0 이 아니고 `fetch_ledger` 의 `datagokr|dividends_full` 이 final. **실패면 재시도하지 말고 보고.**
- [ ] **Step 2: 실패 테스트** — `test_screen_data.py` 에 세 개:
  - `test_collect_includes_index1028` — 가짜 `query` 가 받은 SQL 중 하나가 `index_code = '1028'` 을 포함하고 결과 테이블에 `index1028` 이 있다.
  - `test_attach_valuation_roundtrip` — 작은 CSV(빈 칸 하나 포함)를 붙인 뒤 `load(...).valuation[date][sym] == (12.5, None, 1.2)`, 두 번째 호출은 `FileExistsError`.
  - `test_load_old_snapshot_without_new_tables` — `index1028` 없는 매니페스트로 만든 스냅샷이 읽히고 `market is None`, `valuation is None`.
- [ ] **Step 3: 실행 → 실패 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_data` → FAIL.
- [ ] **Step 4: 구현** — 1028 조회는 `index_ohlcv` 의 종가 열(열 이름은 `\d index_ohlcv` 로 확인), `PRICE_FROM ~ UNTIL`. 매니페스트에 `price_from` 이 바뀐 값으로 남는다.
- [ ] **Step 5: 통과 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_data test_screen_backtest test_screen_run` → OK.

### Task 4: 잔차 모멘텀 이식(B2)과 밸류·잔차 카테고리

**Files:**
- Modify: `C:\Users\pooh6\workspace\algorithm\screen_factors.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_factors.py`

**Interfaces:**
- Produces:
  - `residual_momentum(closes: pd.DataFrame, market: pd.Series, as_of: dt.date, codes=None, reg_window=36, mom_window=11, skip=1, min_obs=24) -> dict[str, float]` — Kotlin `ResidualMomentum.compute` 와 같은 의미(월말 = 그 달 마지막 유효값, as_of 가 든 달도 포함, NaN 달 다음 달도 NaN, 회귀 쌍 < min_obs 제외, sd(ddof=1) > 0 이면 μ/sd 아니면 μ).
  - `CATEGORIES` 에 `'value'`, `'residual_momentum'` 추가. `category_scores(cols) -> dict[str, np.ndarray]`(compose 가 쓰는 카테고리 점수, 키 = 카테고리). value = mean(z(PER>0 만, 역), z(PBR, 역), z(DIV)) 각 NaN→0, residual_momentum = z(`resid_mom`) NaN→0.
- [ ] **Step 1: 실패 테스트**
  - `test_resid_mom_matches_kotlin_oracle` — `quant-folio-kotlin/backend/core/src/test/resources/oracle/factors.json` 의 `panel`(index·market·종목 열)과 `resid_mom` 세 케이스(as_of·창·skip 이 다름)의 `out` 과 1e-9 이내 일치. 경로는 Windows 경로(`C:\Users\pooh6\...`)로.
  - `test_resid_mom_gap_month_is_nan` — 시장 지수 한 달 비움 → 그 달·다음 달 회귀 쌍에서 빠져 관측 수가 2 줄어든다(min_obs 경계로 확인).
  - `test_value_category_matches_kotlin_oracle` — `factors.json` `cases` 중 value 가중이 있는 케이스의 `score_value` 와 일치(없으면 `frame` 으로 직접: PER ≤ 0 은 NaN 취급 확인).
- [ ] **Step 2: 실행 → 실패 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_factors` → FAIL.
- [ ] **Step 3: 구현** — docstring 에 Blitz, Huij & Martens (2011) https://ideas.repec.org/a/eee/empfin/v18y2011i3p506-521.html, Kang (2020) https://www.emerald.com/jdqs/article/28/2/3/206237/Market-anomalies-in-the-Korean-stock-market, Kotlin 원 구현 경로.
- [ ] **Step 4: 통과 확인** — 같은 명령 → OK.

### Task 5: 백테스터 — 밸류·잔차 입력, 최소분산·무작위 비중

**Files:**
- Modify: `C:\Users\pooh6\workspace\algorithm\screen_backtest.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_backtest.py`

**Interfaces:**
- Consumes: Task 3 `Data.market`·`Data.valuation`, Task 4 `residual_momentum`·`category_scores`.
- Produces:
  - `Config.weighting: tuple | None = None` — `('minvar', lookback, cap)` 또는 `('random', seed, cap)`.
  - `CATEGORY_INPUT` 값을 튜플로: `{'lottery': ('max_21',), 'reversal': ('rev_21',), 'profitability': ('gp_a',), 'value': ('PER', 'PBR', 'DIV'), 'residual_momentum': ('resid_mom',)}` — 위약은 튜플 안 열을 **같은 순열**로 섞는다.
  - `factor_frame` 이 `weights['value'] > 0` 이면 PER·PBR·DIV(`data.valuation[d]`, 없으면 ValueError `value 입력 미적재`), `weights['residual_momentum'] > 0` 이면 `resid_mom`(패널 `.loc[:d]`, `data.market.loc[:d]`). 커버리지: value 는 셋 중 하나라도 있는 비율 ≥ 50%, 아니면 ValueError.
  - `signals[d]` 에 `'score_value'`(category_scores 의 value)와 `'resid_mom'` 추가.
  - `minvar_weights(returns: pd.DataFrame, cap: float, min_obs: int) -> dict | None` — None = 불가(동일가중으로). `random_capped_weights(symbols: list, cap: float, rng) -> dict`.
  - `Result.weight_fallbacks: int`.
- [ ] **Step 1: 실패 테스트**
  - `test_minvar_weights_lower_variance_than_equal` — 시드 난수 15종목(변동성 서로 다름): 합 1(1e-9), 모두 0 ≤ w ≤ .10 + 1e-9, 표본 공분산 기준 분산이 동일가중보다 작다.
  - `test_minvar_infeasible_falls_back` — 8종목(8 × .10 < 1) → None, 한 종목 유효 수익률 150 < 200 → None. `run` 에서는 그 결정일 동일가중이고 `weight_fallbacks == 1`.
  - `test_random_capped_weights` — 같은 시드 같은 결과, 합 1, ≤ cap.
  - `test_value_placebo_shuffles_jointly` — placebo `('value', 7)` 에서 섞인 PER·PBR·DIV 의 (종목→원래 행) 대응이 세 열 모두 같다.
  - `test_minvar_no_lookahead` — 결정일 d 이후 가격을 바꿔도 d 의 비중이 같다(1라운드 교란 테스트 형식).
  - `test_value_missing_raises` — value 가중인데 `data.valuation is None` → ValueError.
- [ ] **Step 2: 실행 → 실패 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_backtest` → FAIL.
- [ ] **Step 3: 구현** — `run` 에서 `chosen` 확정 뒤 `cfg.weighting` 이면 목표 비중을 그것으로(그 외는 기존 1/n). 최소분산 목적함수 `w @ Σ @ w`, 시작점 동일가중, 제약 합 1, 경계 `(0, cap)`. SLSQP 가 수렴 못 하면 None. docstring: Ledoit & Wolf (2004) https://bse.eu/research/working-papers/honey-i-shrunk-sample-covariance-matrix + scikit-learn `LedoitWolf` 문서(확인한 URL), 항등 목표라는 점(`ponytail:` 상수상관 목표로 재확인 경로).
- [ ] **Step 4: 통과 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_backtest test_screen_run` → OK(1라운드 테스트 포함).

### Task 6: 선별 실행기 2라운드 규칙 + 교정 확장

**Files:**
- Modify: `C:\Users\pooh6\workspace\algorithm\screen_run.py`, `screen_kotlin.py`, `README.md`
- Test: `test_screen_run.py`, `test_screen_kotlin.py`

**Interfaces:**
- Consumes: Task 5 `Config.weighting`, `signals` 키, `Result.weight_fallbacks`. `screen_eval.ledoit_wolf_sharpe_diff`·`holm`·`fama_macbeth`·`T_HURDLE`, `screen_stats.forward_pairs`.
- Produces:
  - `ROUND_ALGOS = {1: {1: ('A1','A3','A4','A5'), 2: ('A1',…,'A5')}, 2: {1: ('B2','B3'), 2: ('B1','B2','B3')}}` (기존 `STAGE_ALGOS` 대체), `VARIANTS` 에 B1~B3(Global Constraints 값), `SIGNAL` 에 `'B1': ('value', 'score_value', 1)`, `'B2': ('residual_momentum', 'resid_mom', 1)`.
  - `evaluate(runner, algo, stage, placebo_runs, rnd=1)`, `verdict(items, algo, pbo, stage, rnd=1)`, `screen(..., rnd=1)` — `rnd=1` 은 기존 동작 그대로(1라운드 테스트 불변).
  - 2라운드: item3 = `{'p': LW p, 'p_holm': …, 'passed': p_holm < .05}`(screen 이 Holm 후 채움), item7(B1·B2) = 기존 IC 조건 AND `fama_macbeth(...)['t_nw'] > T_HURDLE`, B3 item7 = None(필수 아님), item5 B3 = 신호 알고리즘과 같은 규칙, item8 B3 = `('random', SEED+i, cap)` 200회 위약. 보고서에 `weight_fallbacks`.
  - CLI: `screen --round 1|2 --stage 1|2`, `attach-valuation --snapshot <dir> --csv <file>`, `calibrate --base h1 [--variant B1|B2]`. 출력 폴더 `screening_result/round2-stage<k>-<시각>`.
  - `screen_kotlin.kotlin_config(weights)` 가 `value`·`residual_momentum` 를 `factor_weights` 로 넘기고 나머지 카테고리는 0(합 1).
- [ ] **Step 1: 실패 테스트**
  - `test_round2_holm_sets_item3` — LW p 가 (.01, .02, .5) 인 가짜 결과에 Holm 적용 → passed (True, True, False)(Holm: .03, .04, .5).
  - `test_round2_item7_needs_fm_t3` — IC 조건 참·FM t 2.9 → False, 3.1 → True.
  - `test_round2_b3_needs_no_ic` — B3 verdict 의 required 에 item7 없음.
  - `test_round1_unchanged` — 기존 `VerdictTest`·`ReportTest` 가 `rnd` 기본값으로 그대로 통과(수정 없이).
  - `test_round2_report_deterministic` — `make_data` 에 시장 지수를 더한 작은 데이터로 `screen(..., rnd=2, stage=1, algos=('B2','B3'), placebo_runs=2)` 두 번 → `report.json` 바이트 동일, `weight_fallbacks` 키 존재.
  - `test_kotlin_config_new_categories` — `kotlin_config({'lowvol': .4, 'quality': .4, 'value': .2})` 의 `factor_weights` 가 value .2·나머지 0·합 1.
- [ ] **Step 2: 실행 → 실패 확인** — `.venv/Scripts/python.exe -X utf8 -m unittest test_screen_run test_screen_kotlin` → FAIL.
- [ ] **Step 3: 구현** — README "구현 목록" 표에 B1~B3 행(링크는 Task 4·5 docstring 과 같은 URL).
- [ ] **Step 4: 통과 확인** — 모든 `test_screen_*` → OK.

### Task 7: 2라운드 잠정 실행(B2·B3, lowvol 기준)

**Files:** 출력만 — `algorithm/screening_result/round2-stage1-<시각>/`, `research/trials.md`, `research/notes.md`(git 무시)

- [ ] **Step 1: 새 스냅샷** — `.venv/Scripts/python.exe -X utf8 screen_run.py snapshot` → 매니페스트 `price_from` 이 `2014-01-02`, `index1028` 해시 존재 확인.
- [ ] **Step 2: 실행** — `nohup .venv/Scripts/python.exe -X utf8 screen_run.py screen --round 2 --stage 1 --snapshot screening_data/<id> > screening_result/round2-stage1.log 2>&1 &`(세션 종료에 죽지 않게). 메모리 여유 2GB 미만이면 Gradle 데몬부터 멈춘다.
- [ ] **Step 3: 기록** — `research/trials.md` 에 "선별 2라운드(1단계, 잠정)" 표, `research/notes.md` 에 F절 요약(항목별·보조 보고·`weight_fallbacks`). 사용자에게 잠정 결과 보고.

### Task 8: 최종 판정(DART 적재 후) — 1라운드 2단계와 함께

**Files:** 출력만 + 진행 원장

- [ ] **Step 1: 커버리지 확인** — 새 스냅샷에서 `screen_dart.coverage` 최소 ≥ 0.5(1라운드 기준). 미달이면 DART 루프 상태를 보고하고 멈춘다.
- [ ] **Step 2: 밸류 내보내기** — 스냅샷의 결정일 목록(2017-06~2022-12 각 달 첫 거래일)으로 Task 2 모드를 qf-paper worker 에서 1회 실행(`-v` 로 algorithm 폴더 마운트), 이어서 `screen_run.py attach-valuation`.
- [ ] **Step 3: 교정** — 사용자에게 비밀번호를 요청해 `QF_PASSWORD` 로 `calibrate --base h1`, `--variant B1`, `--variant B2`(전략 38, 시작 금지). 미달 후보는 판정 제외·원인 보고.
- [ ] **Step 4: 실행** — `screen --round 1 --stage 2` 와 `screen --round 2 --stage 2`(각각 nohup).
- [ ] **Step 5: 보고** — 알고리즘별 합격/불합격·항목·보조 보고, 사용자가 통과 목록 확인. 진행 원장 갱신.

### Task 9: 적용 (통과분만)

- B1·B2 통과: 사용자 확인 후 가설 설정 `factor_weights` 에 반영(코드 변경 없음), `research/hypotheses.md` 갱신.
- B3 통과: Kotlin 롱온리 최소분산 비중 옵션은 **별도 설계·계획**(정답지·`PortfolioLookaheadTest` 포함) — 이 계획 범위 밖.
- 1라운드 통과분은 1라운드 계획 Task 10~13.
