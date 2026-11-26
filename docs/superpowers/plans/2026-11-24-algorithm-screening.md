# 논문 알고리즘 선별 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 논문 알고리즘 5개(A1 순위 버퍼, A2 GP/A, A3 MAX, A4 단기 반전, A5 반전+버퍼)와 PBO 도구를 algorithm 폴더에서 구현·시험하고, 사전 고정 합격 규칙을 통과한 것만 Kotlin 에 기본값이 꺼진 옵션으로 넣는다.

**Architecture:** algorithm 폴더에 Python 소형 백테스터(qf-paper DB 읽기 전용 스냅샷 위)를 만들고, Kotlin 백테스트와 교정한 뒤 변형별 시험 9항목을 돌린다. 1단계는 가격만(lowvol 기준, 잠정), 2단계는 DART 적재 후 H1 기준(최종 판정). Kotlin 적용 작업은 최종 판정에서 통과한 알고리즘만 수행한다.

**Tech Stack:** Python 3.14.4 / NumPy 2.5 / pandas 3.0 / pyarrow 25 / unittest(algorithm `.venv`), Kotlin + Gradle(`backend/`).

**Spec:** `docs/superpowers/specs/2026-11-24-algorithm-screening-design.md`

## Global Constraints

- 판정·시험은 학습 구간 **2017-06-01 ~ 2022-12-31** 만. 2023-01-01 이후 데이터는 스냅샷에도 넣지 않는다(가격은 마지막 결정의 체결·월말 평가를 위해 2023-01-31 까지만).
- DB 는 `qf-paper-db-1` **읽기 전용 트랜잭션**으로만 조회 — 기존 `quantfolio_data.query_csv(container, sql)` 재사용.
- 공통값: `KOSPI_MCAP` 상위 100(결정일 직전 거래일 시총, `stock_kind` 종목별 최신값 이월, `보통주`만, 시총 내림차순·심볼 오름차순), 월간(각 달 첫 거래일 결정), `next_close` 체결, `top_n` 20, 동일가중, `drift_band_pct` 0.02, 자본 30,000,000(정수주 = `floor(비중 × 초기자본 / 가격)`), 수수료 0.00015, 매도세 0.002, 슬리피지 10bp.
- 비용 시나리오: 저비용 = 슬리피지 0, 기본 = 10bp, 고비용 = 30bp(수수료·세금은 동일).
- 신규 카테고리 주 가중치 .2, 이웃 .1/.3, 기존 가중치는 비례 축소. 버퍼 k 주 10, 이웃 5/20. MAX·반전 창 21거래일 고정.
- 시드 고정: 부트스트랩 10,000회(3개월 블록), 위약 200회, 시드 20261124.
- 결측은 NaN(0 아님). 단, 카테고리 점수 합성은 Kotlin `FactorScores` 와 같이 z 의 NaN → 0.
- 출력은 덮어쓰지 않는다(기존 폴더면 거부). 실데이터 결과는 Kotlin 저장소에 커밋 금지 — `research/`(git 무시)와 algorithm 폴더에만.
- Kotlin 교정 백테스트는 **전략 37 이 아닌 별도 전략**에서. 그 전략은 시작하지 않는다.
- algorithm 폴더는 git 저장소가 아니다 — 그쪽 작업은 커밋 단계가 없다. 새 출력 폴더는 algorithm `.gitignore` 에 추가.

## Review Focus

1. **미래참조** — 결정일 d 의 유니버스가 d 당일 시총을 쓰거나, 팩터가 d 이후 가격·당해 사업보고서를 쓰면 결과 전체가 무효. → Task 3 교란 테스트, Task 8 4/1 경계 테스트.
2. **체결일 가격 없음(거래정지·상폐)** — 목표 종목에 가격이 없으면 Kotlin 처럼 그 종목만 건너뛰고, 보유 종목은 마지막 가격으로 평가. → Task 3 `test_missing_fill_price_skips_symbol`.
3. **후보가 top_n 미만** — 점수 있는 종목이 20개 미만이면 있는 만큼만 동일가중(나머지 현금이 아님 — Kotlin `targetWeights` 는 선택 종목끼리 1/n). → Task 3 `test_fewer_candidates_than_top_n`.
4. **재현성** — 같은 스냅샷·시드로 두 번 돌리면 보고서가 바이트 단위로 같아야 한다(순서 없는 컬렉션 순회 금지). → Task 6 `test_report_is_deterministic`.
5. **스냅샷 바꿔치기** — 매니페스트 해시와 다른 파일로 시험하면 거부. → Task 1 `test_load_rejects_hash_mismatch`.

---

## 1단계 — 가격 기반 (잠정)

### Task 1: 스냅샷 수집기

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_data.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_data.py`
- Modify: `C:\Users\pooh6\workspace\algorithm\.gitignore` (`screening_data/`, `screening_result/` 추가)

**Interfaces:**
- Produces:
  - `snapshot(output: Path, container: str = 'qf-paper-db-1', query=quantfolio_data.query_csv) -> Path` — `output/<YYYYMMDDTHHMMSS>-<sha8>/` 에 parquet 저장 후 경로 반환. 파일: `prices.parquet`(symbol, date, close), `caps.parquet`(date, symbol, market_cap, stock_kind — **이월 적용 후**), `trading_days.parquet`(date; `stock_daily_snapshots` 의 `market='KOSPI'` 행이 있는 날), `kospi100.parquet`(base_date, symbol), `corp_codes.parquet`(symbol, corp_code), `dart.parquet`(corp_code, bsns_year, fs_div, accounts_json; `reprt_code='11011'` 만), `manifest.json`(파일별 sha256, 조회 시각, 구간).
  - `load(snapshot_dir: Path) -> Data` — `Data` 는 dataclass: `closes: pd.DataFrame`(index=date, columns=symbol), `caps: pd.DataFrame`(date, symbol, market_cap, stock_kind), `days: list[date]`, `kospi100: dict[date, list[str]]`, `corp: dict[str, str]`, `dart: dict[tuple[str, int, str], list[dict]]`.
- 조회 구간: 가격 2015-06-01 ~ 2023-01-31(워밍업 500일 포함), 시총·거래일 2017-05-01 ~ 2023-01-31(이월용 `stock_kind` 는 그 이전 최신값까지), DART 2014~2022 연도.

- [ ] **Step 1: 실패 테스트 작성** — `query` 를 가짜 함수로 주입.
  - `test_stock_kind_is_carried_forward`: 월초에만 `보통주` 가 있고 이후 날은 NULL 인 입력 → `caps` 의 이후 날 행도 `보통주`.
  - `test_snapshot_refuses_existing_output`: 같은 이름 폴더가 있으면 `FileExistsError`.
  - `test_load_rejects_hash_mismatch`: 저장 후 `prices.parquet` 1바이트 변경 → `load` 가 `ValueError`.
  - `test_no_rows_after_2023_01_31`: 가짜 응답에 2023-02-01 행이 있어도 저장본엔 없다(SQL 에 상한이 들어갔는지 쿼리 문자열도 확인).
- [ ] **Step 2: 실패 확인** — `.\.venv\Scripts\python.exe -X utf8 -m unittest test_screen_data -v` → ImportError.
- [ ] **Step 3: 구현** — 이월은 SQL 이 아니라 pandas `groupby(symbol).ffill()` 로(이월 시작점 확보를 위해 `stock_kind` 는 구간 이전 최신값 1행씩 추가 조회). 큰 가격 조회는 연도별로 나눠 `statement_timeout` 90초 안에 끝나게.
- [ ] **Step 4: 통과 확인** — 같은 명령 → OK.

### Task 2: 팩터 입력과 점수 합성

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_factors.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_factors.py`

**Interfaces:**
- Produces:
  - `winsorize_z(s: np.ndarray, invert: bool = False) -> np.ndarray` — Kotlin `FactorScores.winsorizeZ` 와 같게: 유효값 < 3 → 전부 NaN, 1%·99% 분위(선형 보간 `pos = q*(n-1)`)로 자르고 ddof=0 표준편차, 표준편차 0 → 전부 0.
  - `price_inputs(close: pd.Series) -> dict[str, float]` — NaN 제거 후: `vol_ann`(마지막 253행의 로그수익률 std ddof=1 × √252; 행 < 20 또는 수익률 < 10 → NaN), `mdd_252`(마지막 252행 종가의 최대 낙폭, 음수), `max_21`(마지막 22행 단순수익률 최댓값; 행 < 22 → NaN), `rev_21`(`last / c[n-22] - 1`; n < 22 → NaN).
  - `CATEGORIES = ('lowvol', 'quality', 'profitability', 'lottery', 'reversal')`
  - `compose(cols: dict[str, np.ndarray], weights: dict[str, float]) -> np.ndarray` — 카테고리: lowvol = mean(z(vol_ann, invert), z(mdd_252)), quality = mean(z(roe), z(debt_ratio, invert), z(fcf>0 → 1/0, NaN 유지), z(f_score)), profitability = z(gp_a), lottery = z(max_21, invert), reversal = z(rev_21, invert). 각 z 는 NaN→0 후 평균, 총점 = Σ 가중치 × 카테고리. 입력 컬럼이 없으면 그 z 는 전부 NaN→0.
  - `reweight(base: dict[str, float], new: str, w: float) -> dict[str, float]` — 기존 가중치 × (1−w) + {new: w}.

- [ ] **Step 1: 실패 테스트 작성**
  - `test_winsorize_z_edge_cases`: `[1, 2, nan]` → 전부 NaN; `[5, 5, 5, 5]` → 전부 0; `[1,2,3,4,100]` 의 결과가 손계산(분위 자르기 후 ddof=0)과 1e-12 이내.
  - `test_price_inputs_known_series`: 길이 300 의 결정적 수열에서 네 값이 직접 계산값과 일치, 길이 15 수열 → 네 값 모두 NaN.
  - `test_compose_inverts_lottery_and_reversal`: max_21·rev_21 이 큰 종목일수록 해당 카테고리 점수가 낮다.
  - `test_reweight`: `reweight({'lowvol': 1.0}, 'lottery', .2)` == `{'lowvol': .8, 'lottery': .2}`; H1 `{'lowvol': .5, 'quality': .5}` → `.4/.4/.2`.
- [ ] **Step 2: 실패 확인** — `... -m unittest test_screen_factors -v` → ImportError.
- [ ] **Step 3: 구현** — 위 정의 그대로.
- [ ] **Step 4: 통과 확인.**

### Task 3: 백테스터

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_backtest.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_backtest.py`

**Interfaces:**
- Consumes: Task 1 `Data`, Task 2 `price_inputs`, `compose`.
- Produces:
  - `@dataclass(frozen=True) Config`: `weights: dict[str, float]`, `top_n: int = 20`, `hold_buffer: int | None = None`, `drift_band: float = 0.02`, `capital: float = 30_000_000`, `fees: float = 0.00015`, `tax: float = 0.002`, `slippage_bps: float = 10`, `universe: str = 'mcap'`(또는 `'kospi100'` — 결정일 이하 가장 최근 `base_date` 구성), `start: date = 2017-06-01`, `end: date = 2022-12-31`, `placebo: tuple[str, int] | None = None`(카테고리, 시드 — 매 결정일 그 카테고리 입력을 유니버스 안에서 무작위 순열).
  - `@dataclass Result`: `daily: pd.Series`(일간 수익률), `monthly: pd.Series`(월말 기준), `turnover: float`(연환산 단측), `holdings: dict[date, tuple[str, ...]]`, `signals: dict[date, dict[str, dict[str, float]]]`(결정일별 신규 카테고리 원입력, IC 용).
  - `rebalance_dates(days: list[date], start: date, end: date) -> list[date]` — 각 달 첫 거래일.
  - `universe_at(data: Data, d: date, mode: str, n: int = 100) -> list[str]`
  - `select(scores: dict[str, float], held: set[str], top_n: int, hold_buffer: int | None) -> list[str]` — 버퍼 없으면 상위 top_n. 있으면: 보유 중 순위 ≤ top_n + k 인 종목 유지 → 빈자리를 상위부터 채움 → 유지 종목이 top_n 초과면 점수순 상위 top_n.
  - `fundamentals: Callable[[date, list[str]], dict[str, dict[str, float]]] | None` 인자를 `run` 이 받는다(1단계는 None; 2단계에서 Task 8 이 공급).
  - `run(data: Data, cfg: Config, fundamentals=None) -> Result`
- 체결 규칙은 Kotlin `PortfolioBacktest`/`PortfolioExecution.applyRebalance` 를 읽고 맞춘다: 결정은 d 종가까지(`closes.loc[:d]`), 체결은 다음 거래일 종가, 매도 먼저(매도 대금 × (1 − fees − tax − slip)), 매수 대금 × (1 + fees + slip), 드리프트 밴드 이내 보유 종목은 매매 안 함(신규·전량 청산 예외), 가격 없는 목표 종목은 제외. 장부는 초기 1.0 정규화.

- [ ] **Step 1: 실패 테스트 작성** (합성 `Data` 로)
  - `test_rebalance_dates_first_trading_day`: 1일이 휴일인 달 → 첫 거래일.
  - `test_universe_uses_previous_day_caps_and_common_only`: d 당일 시총 순위가 바뀌어도 결과는 직전 거래일 기준; `우선주` 제외; 시총 동률은 심볼 오름차순.
  - `test_select_with_buffer`: 점수 순위 1..40, 보유 {순위 25, 순위 35}, top_n 20, k 10 → 25위 유지·35위 제외·상위 19개 채움.
  - `test_drift_band_no_trade`: 목표와 현재 비중 차이 0.01 → 그 종목 거래 없음.
  - `test_cost_math_two_stocks`: 2종목 손계산 장부와 일간 수익률 1e-12 이내.
  - `test_missing_fill_price_skips_symbol`, `test_fewer_candidates_than_top_n`(Review Focus 2·3).
  - `test_no_lookahead`: 컷 날짜 이후 가격을 ×1.5 교란 → 컷까지의 `daily` 동일.
  - `test_placebo_changes_only_target_category`: placebo 켜면 다른 카테고리 입력은 그대로.
- [ ] **Step 2: 실패 확인** — `... -m unittest test_screen_backtest -v`.
- [ ] **Step 3: 구현.**
- [ ] **Step 4: 통과 확인.**

### Task 4: 통계 — Sharpe·부트스트랩·IC·PBO

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_stats.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_stats.py`

**Interfaces:**
- Produces:
  - `sharpe(daily: pd.Series) -> float` — 평균/표준편차(ddof=1) × √252, 표준편차 0 → NaN.
  - `bootstrap_p(a: pd.Series, b: pd.Series, block: int = 3, n: int = 10_000, seed: int = 20261124) -> float` — 같은 인덱스의 월 수익률 쌍을 원형 블록으로 재표본, P(Sharpe(a*) − Sharpe(b*) > 0), Sharpe 는 월 기준 × √12.
  - `ic(signals: dict[date, dict[str, float]], closes: pd.DataFrame, rebal: list[date]) -> pd.Series` — 결정일별 Spearman(신호, 다음 결정일까지 수익률), 유효 쌍 < 10 이면 그 달 제외.
  - `ic_ci(ic: pd.Series, block: int = 3, n: int = 10_000, seed: int = 20261124) -> tuple[float, float, float]` — (평균, 2.5%, 97.5%).
  - `pbo_cscv(m: np.ndarray, s: int = 8) -> float` — m 은 (기간 T, 전략 N) 월 수익률. 행을 S 블록으로 나눠 C(S, S/2) 조합마다 표본 내 Sharpe 최고 전략의 표본 밖 상대순위 ω, 로짓 λ = ln(ω/(1−ω)), PBO = P(λ ≤ 0). 상대순위 ω = (표본 밖 순위)/(N+1).
- [ ] **Step 1: 실패 테스트 작성**
  - `test_sharpe_known`: 일정 수익률(분산 0) → NaN; 알려진 수열 → 손계산.
  - `test_bootstrap_identical_series_is_zero`: a == b → 0.0(차이 > 0 인 표본 없음).
  - `test_bootstrap_dominant_series_near_one`: a = b + 0.01 → ≥ 0.99.
  - `test_pbo_noise_near_half`: 시드 고정 순수 잡음 (T=72, N=20) → 0.35 ≤ PBO ≤ 0.65 (스펙의 도구 합격 조건 ①).
  - `test_pbo_true_signal_low`: 같은 잡음에 한 전략만 평균 +2%/월 → PBO < 0.2 (조건 ②).
  - `test_ic_perfect_rank`: 신호 = 다음 달 수익률 → IC 1.0.
- [ ] **Step 2: 실패 확인.** **Step 3: 구현.** **Step 4: 통과 확인.**

### Task 5: Kotlin 교정 하네스

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_kotlin.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_kotlin.py`

**Interfaces:**
- Consumes: Task 3 `Result`.
- Produces:
  - `login(password: str, cookie_path: Path) -> None` — `POST http://localhost:18000/api/auth/login`(폼 `password`, 헤더 `Origin: http://localhost:13000`), 쿠키 파일 저장. 비밀번호는 인자로만 받고 파일·로그에 남기지 않는다.
  - `ensure_strategy(cookie_path: Path, name: str = '알고리즘-선별 교정') -> int` — 같은 이름이 있으면 그 id, 없으면 생성. **시작(start) 호출 금지.**
  - `kotlin_config(weights: dict[str, float]) -> dict` — `qf_trials.py` 의 공통 config 에서 `universe_rule` 을 `{"type":"momentum","source":"KOSPI_MCAP","lookback":20,"pick":100}` 으로, `factor_weights` 키는 Kotlin 스키마 키만(`flow` 없음).
  - `run_backtest(cookie_path: Path, strategy_id: int, config: dict) -> dict` — PATCH config → `POST /api/strategies/{id}/backtest` (`period_start` 2017-06-01, `period_end` 2022-12-31) → 응답 본문.
  - `compare(py: Result, kt: dict) -> dict` — `{"monthly_corr": float, "holding_overlap": float, "sharpe_py": float, "sharpe_kt": float}`. Kotlin 월 수익률은 `result.equity_curve`(t, v), 보유는 `result.trades` 로 재구성.
  - `CALIBRATION_OK = {"monthly_corr": 0.95, "holding_overlap": 0.80}`
- [ ] **Step 1: 실패 테스트 작성** — `test_compare_identical_is_perfect`(같은 경로·보유 → 1.0/1.0), `test_kotlin_config_uses_mcap_and_no_flow`.
- [ ] **Step 2~4:** 실패 확인 → 구현 → 통과.

### Task 6: 선별 실행기와 합격 규칙

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_run.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_run.py`

**Interfaces:**
- Consumes: Task 1~5 전부.
- Produces:
  - `VARIANTS: dict[str, list[tuple[str, Callable[[Config], Config]]]]` — 알고리즘 → [(변형 이름, 기준 Config → 변형 Config)], 첫 항목이 주 변형. A1: k 10/5/20, A2: profitability .2/.1/.3, A3: lottery .2/.1/.3, A4: reversal .2/.1/.3, A5: reversal .2 + k 10.
  - `BASES = {'lowvol': {'lowvol': 1.0}, 'h1': {'lowvol': .5, 'quality': .5}, 'quality': {'quality': 1.0}}`
  - `evaluate(data, algo: str, base: str, fundamentals=None) -> dict` — 항목 1~8 결과를 키 `item1`..`item8` 로(각 항목: 원수치와 `passed: bool | None`, 측정 불가면 None).
  - `verdict(items: dict[str, dict], algo: str, pbo: float, stage: int) -> dict` — `{"passed": bool | None, "required": {...}, "robust_count": int, "reasons": [...]}`. stage 1 은 항목 6 을 측정 가능한 구성만으로 세고 `passed` 는 항상 None(잠정).
  - CLI: `snapshot`, `calibrate --base lowvol|h1 --password-env QF_PASSWORD`, `screen --stage 1|2 --snapshot <dir>`, `pbo-selftest`. 출력 `screening_result/stage<n>-<ts>/report.json` + 시험별 상세 JSON.
- 합격 규칙은 스펙 "합격 규칙" 절을 **그대로** 코드로: 필수(항목 1 Δ>0, 항목 8, PBO<0.5, A2~A4 항목 7 부호·CI, A5 는 A4 의 항목 7) + 견고성 5개 중 ≥ 3(항목 2 두 구간 Δ>0, 항목 3 P≥0.8, 항목 4 이웃 전부 Δ>0, 항목 5 기본·고비용 Δ>0 — A1 은 고비용 Δ > 기본 Δ, 항목 6 4개 중 ≥ 3). A1 항목 8 = 비용 귀속(Δ 저 < 기본 < 고, 저비용 Δ ≥ −0.1).
- [ ] **Step 1: 실패 테스트 작성** — `verdict` 표 기반:
  - 필수 하나 실패 → False; 견고성 3/5 → True; 2/5 → False; PBO 0.5 → 모든 알고리즘 False; A1 은 항목 7 없이도 통과 가능; A5 는 A4 의 항목 7 실패 시 False; stage 1 → `passed` None.
  - `test_report_is_deterministic`: 작은 합성 `Data` 로 `screen` 두 번 → `report.json` 바이트 동일(Review Focus 4).
- [ ] **Step 2~4:** 실패 확인 → 구현 → 통과. 전체 회귀: `.\.venv\Scripts\python.exe -X utf8 -m unittest discover -v` → 기존 테스트 포함 OK.

### Task 7: 1단계 실행·기록·보고

**Files:**
- Modify: `research/trials.md`(선별 표 추가), `research/notes.md`(E절 — 잠정 결과, `mom_1m` 방향 기록)

- [ ] **Step 1: 스냅샷** — `.\.venv\Scripts\python.exe -X utf8 screen_run.py snapshot` → 출력 경로 기록, 매니페스트 확인.
- [ ] **Step 2: PBO 도구 검증** — `screen_run.py pbo-selftest` → 조건 ①·② 둘 다 통과 출력.
- [ ] **Step 3: 교정(lowvol 단독)** — 사용자에게 웹 비밀번호를 받아 환경변수 `QF_PASSWORD` 로만 전달 → `screen_run.py calibrate --base lowvol`. 기준(상관 ≥ 0.95, 겹침 ≥ 80%) 미달이면 **멈추고** 원인(배당 `_dividends`, 정수주, 체결가 처리)을 찾아 Task 3 을 고친 뒤 재교정. 통과 전에는 Step 4 금지.
- [ ] **Step 4: 1단계 선별** — `screen_run.py screen --stage 1 --snapshot <경로>`.
- [ ] **Step 5: 기록** — `trials.md` 에 "선별(1단계)" 표: 변형마다 행(날짜, 기준, 알고리즘, 변형, Sharpe, ΔSharpe, 회전율, 결과 파일). `notes.md` E절에 잠정 요약(실수치는 `research/` 안에만).
- [ ] **Step 6: 사용자 보고** — 교정 결과, 알고리즘별 항목 결과, lowvol 기준 PBO, "잠정·최종은 2단계" 명시.

---

## 2단계 — DART 기반 (최종 판정)

### Task 8: DART 지표 이식 (1단계와 병행 가능)

**Files:**
- Create: `C:\Users\pooh6\workspace\algorithm\screen_dart.py`
- Test: `C:\Users\pooh6\workspace\algorithm\test_screen_dart.py`

**Interfaces:**
- Consumes: Task 1 `Data`.
- Produces:
  - `derive_metrics(accounts: list[dict]) -> dict[str, float | None]` — Kotlin `DartMetrics.deriveMetrics` 이식(account_id 우선·접두 일치·이름 폴백 첫 후보, 순이익 이름 제외어, FCF = CFO − (유형+무형 취득), roe/debt_ratio 는 자본 > 0 일 때만).
  - `f_score(cur: dict, prev: dict) -> int | None` — `piotroskiFScore` 이식(계산 가능 < 5 → None).
  - `lagged_year(as_of: date) -> int` — 4월부터 직전 연도, 그 전엔 전전 연도.
  - `fundamentals(data: Data) -> Callable[[date, list[str]], dict[str, dict[str, float]]]` — 종목 → {roe, debt_ratio, fcf, f_score, gp_a}. 연간 보고서는 CFS 우선·OFS 폴백, `gp_a = gross_profit / assets`(assets > 0, 둘 다 있을 때만).
  - `coverage(data: Data, fund, dates: list[date]) -> float` — 결정일별 상위 100 중 quality 입력 하나라도 있는 비율의 최솟값.
- [ ] **Step 1: 실패 테스트 작성**
  - `test_pick_prefers_account_id_over_name`, `test_net_income_excludes_controlling_names`, `test_fcf_requires_both_parts`.
  - `test_lagged_year_boundary`: 2019-03-31 → 2017, 2019-04-01 → 2018 (Review Focus 1).
  - `test_cfs_falls_back_to_ofs`, `test_gp_a_none_for_financials`(매출총이익 계정 없음 → None, 0 아님).
  - `test_f_score_matches_kotlin_case`: Kotlin `DartMetrics` 테스트(`backend/core/src/test/.../DartMetricsTest` 류)의 같은 입력 하나를 옮겨 같은 값.
- [ ] **Step 2~4:** 실패 확인 → 구현 → 통과.

### Task 9: 2단계 실행·최종 판정

- [ ] **Step 1: 적재 완료 확인** — 새 스냅샷 후 `coverage(...) ≥ 0.5`. 미달이면 백필 진행 상황(`external_fetches` `dart_accounts`)을 보고하고 대기.
- [ ] **Step 2: 교정(H1)** — `calibrate --base h1`, 기준 동일. Kotlin 이 `MarketDataNotLoaded`(409)면 적재 미완 — Step 1 로.
- [ ] **Step 3: 2단계 선별** — `screen --stage 2`: 주 기준 H1, 항목 6 = H1·lowvol·quality·KOSPI100 유니버스(H1), A2 포함, PBO 는 H1 기준 선별 전체.
- [ ] **Step 4: 기록·보고** — `trials.md` "선별(2단계)" 표, `notes.md` E절 최종. 알고리즘별 합격/불합격·항목별 결과를 사용자에게 보고하고, **통과 목록을 확인받은 뒤** 아래 Kotlin 작업 중 해당하는 것만 진행.

---

## 3단계 — Kotlin 적용 (통과분만)

공통: 새 옵션은 기본값 꺼짐 → 기존 백테스트·엔진 결과 불변(기존 테스트 전부 초록). 정답지는 algorithm 의 Python 구현으로 만든 **합성 입력** JSON 을 `backend/core/src/test/resources/oracle/screen_<이름>.json` 에(파일 머리 주석에 "출처: algorithm/screen_*.py, 원본 Python 아님"). 로드는 기존 `Oracle.load` 를 쓴다.

### Task 10: A1 순위 버퍼 (A1 통과 시)

**Files:**
- Modify: `backend/core/src/main/kotlin/com/quantfolio/strategy/StrategySchemas.kt`(SELECTION 에 `F("hold_buffer", IntT, null, nullable = true, ge = 1, le = 50)`)
- Modify: `backend/core/src/main/kotlin/com/quantfolio/rebalance/Rebalance.kt`(`targetWeights` 에 `held: Set<String> = emptySet()` 인자)
- Modify: `backend/core/src/main/kotlin/com/quantfolio/backtest/PortfolioBacktest.kt`(`targetsAt` 가 `book.value.keys` 전달), `backend/engine/src/main/kotlin/com/quantfolio/engine/RebalanceRunner.kt`(보유 포지션 키 전달)
- Test: `backend/core/src/test/kotlin/com/quantfolio/rebalance/RebalanceHoldBufferTest.kt`

- [ ] **Step 1:** 정답지 생성 — Python `select` 로 합성 점수·보유 10케이스(동점, 보유 > top_n, 버퍼 null) → `screen_hold_buffer.json`.
- [ ] **Step 2: 실패 테스트** — 정답지 전 케이스 일치, `hold_buffer` null 이면 기존 결과와 동일, 스키마가 0·51 거부.
- [ ] **Step 3: 실패 확인** — `cd backend && ./gradlew :core:test --tests '*HoldBuffer*'`.
- [ ] **Step 4: 구현.** 동점 순서는 Kotlin `sortedByDescending`(안정 정렬)과 Python 정렬을 같게 맞춘다(정답지에 동점 케이스 포함).
- [ ] **Step 5: 통과 확인** — 같은 명령 + `./gradlew :core:test :engine:test`.
- [ ] **Step 6: 커밋** — `feat(rebalance): 순위 버퍼 hold_buffer 옵션(기본 꺼짐)`.

### Task 11: 신호 팩터 A2/A3/A4 (통과한 것만)

**Files:**
- Modify: `backend/core/src/main/kotlin/com/quantfolio/factor/FactorScores.kt`(CATEGORIES·DEFAULT_WEIGHTS 에 통과한 `profitability`/`lottery`/`reversal` 를 **가중치 0** 으로, `compute` 에 카테고리 점수, 입력 리스트 상수 `PROFITABILITY_INPUTS = listOf("gp_a")` 등)
- Modify: `StrategySchemas.kt`(FACTOR_WEIGHTS·FACTOR_KEYS 에 기본 0.0 필드)
- Modify: 입력 두 곳 — 가격 신호는 `PortfolioBacktest.factorFrameOf` 와 `metrics/UniverseScores.kt`, `gp_a` 는 `DartFinancials.metricsBySymbol` 결과 + `FactorScores.QUALITY_COLS` 경로(백테스트 `fundamentalsProvider`·`UniverseScores` 가 함께 읽음)
- Modify: `BacktestRunner.fundamentalsProvider` 커버리지 검사(가중치 > 0 일 때 해당 입력으로 `requireCoverage`)
- Test: `backend/core/src/test/kotlin/com/quantfolio/factor/FactorScoresNewCategoriesTest.kt`

- [ ] **Step 1:** 정답지 — Python `compose`·`price_inputs` 로 합성 패널(NaN·동점·짧은 이력 포함) → `screen_factors.json`.
- [ ] **Step 2: 실패 테스트** — 정답지 일치(1e-9), 새 가중치 0 이면 `score` 가 기존과 동일, 가중치 > 0 이고 입력 커버리지 < 50% 면 `MarketDataNotLoaded`, 백테스트와 `UniverseScores` 가 같은 날 같은 입력값을 낸다.
- [ ] **Step 3~5:** 실패 확인(`./gradlew :core:test --tests '*NewCategories*'`) → 구현 → `./gradlew build` 통과.
- [ ] **Step 6: 커밋** — `feat(factor): <통과 팩터> 카테고리(기본 가중치 0)`.

### Task 12: PBO (도구 검증 통과 시)

**Files:**
- Create: `backend/core/src/main/kotlin/com/quantfolio/backtest/Pbo.kt` — `object Pbo { fun cscv(m: Array<DoubleArray>, s: Int = 8): Double? }`(행 = 월, 열 = 시행; 시행 < 2 또는 월 < s 면 null)
- Modify: `backend/web/.../backtest/BacktestsController.kt` `/backtests/{id}/dsr` 응답에 `pbo` 필드(같은 DSR 동질 시행들의 자산곡선 → 공통 월 구간 정렬)
- Test: `backend/core/src/test/kotlin/com/quantfolio/backtest/PboTest.kt`

- [ ] **Step 1:** 정답지 — Python `pbo_cscv` 로 합성 행렬 3개(잡음, 진짜 신호, 동점 Sharpe) → `screen_pbo.json`.
- [ ] **Step 2~5:** 실패 테스트(정답지 일치, 시행 1개 → null) → 확인 → 구현 → `./gradlew build`.
- [ ] **Step 6: 커밋** — `feat(backtest): PBO(CSCV) — DSR 응답에 pbo`.

### Task 13: Kotlin 재현 확인과 마무리

- [ ] **Step 1:** 교정용 별도 전략에서 적용한 옵션을 켠/끈 Kotlin 백테스트(학습 구간, H1 기준) → ΔSharpe 부호가 Python 2단계 결과와 같은지. 다르면 **멈추고** 보고.
- [ ] **Step 2:** `research/notes.md` E절에 재현 결과. `strategy-config.md` 등 문서 갱신은 단계 종료 `doc-check` 에서.
- [ ] **Step 3:** 사용자에게 최종 보고 — 적용된 옵션, 켜는 법(config 키), 다음 라운드 가설로 넘길 항목.
