# 초기 팩터 리밸런싱 전략 수립 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 근거가 글로 남고 백테스트로 검증된 `rebalance` config 하나를 qf-paper 모의투자에 올린다.

**Architecture:** 코드 작성 없음. 리서치 → 가설 고정 → 기존 백테스트 API(로그인된 Chrome 페이지에서 Claude in Chrome 으로 호출) →
DSR·통과 기준 → 사용자 확인 후 모의 기동. 산출물은 gitignore 된 `research/` 에만 쓴다.

**Tech Stack:** WebSearch, Claude in Chrome(`javascript_tool` 로 페이지 안 `fetch`), qf-paper 스택(web 18000·화면 13000·DB 15432).

**Spec:** `docs/superpowers/specs/2026-11-22-initial-factor-strategy-design.md`

## Global Constraints

- 공개 저장소: 실데이터 수치·최종 가중치·리서치 노트는 커밋 금지. 커밋 대상은 `.gitignore` 한 줄과 이 계획의 체크 상태뿐.
- 로그인 시도 금지(비밀번호 입력·`/api/auth/login` 호출 금지 — 10회 실패면 15분 잠김). 세션이 없으면 사용자에게 로그인을 요청.
- 전략 23 기동 금지. 주문을 낼 수 있는 동작(전략 시작)은 매번 사용자 확인.
- KRX 차단 이력: 백테스트는 순차 1건씩, 실패 시 같은 요청 재시도 금지 — 원인 확인 후 사용자에게 보고.
- 시행 총량 ≤ 15회. 확인 구간(2023-01~최근 완결월)은 후보당 한 번만.
- 가설·시행 계획은 백테스트 전에 확정, 이후 수정은 날짜·사유를 붙여 **추가만**.

## 스펙 해석 보정 (실행 전 사용자 확인)

DSR 은 `같은 strategy_id + 같은 기간 + 같은 universe_fingerprint` 인 백테스트만 시행 수로 센다(`BacktestsController.dsr`).
그래서 (1) 모든 시행을 **전략 하나**에서 config 를 PATCH 해 가며 돌리고, (2) 유니버스 소스는 전 시행 공통 하나로 고정한다.
또 다중검정 보정이 의미 있는 곳은 고른 구간(학습)이므로 **DSR ≥ 0.95 는 학습 구간 시행 풀에서** 판정한다
(확인 구간 풀은 후보 수만큼뿐이라 보정이 거의 없다). 나머지 3개 기준은 확인 구간 그대로.

## Review Focus

1. KRX 인증 실패 → PIT 후보풀이 비어도 백테스트는 "성공"한다 → 결과의 거래 종목 수가 0 이면 그 시행은 무효로 기록하고 중단(Task 4).
2. 일부 종목만 팩터가 비면 `result.factor_warnings` 에만 남는다 → 시행마다 확인해 기록, 가중 팩터 커버리지가 낮으면 무효(Task 4).
3. 같은 config 재실행은 DSR 시행 수를 부풀린다 → 실행 전 trials.md 에서 중복 확인(Task 4).
4. residual_momentum(36개월)·pead(8분기)는 학습 구간 앞부분에 점수가 없다 → 해당 팩터를 쓰는 가설은 학습 시작을 늦춘 이유를 가설 문서에 적는다(Task 3).
5. 세션 만료로 401 → 재로그인 요청, 그 사이 요청을 재시도로 쌓지 않는다(Task 4).

---

### Task 1: research/ 준비

**Files:**
- Modify: `.gitignore` (끝에 `research/` 한 줄)
- Create: `research/README.md`, `research/notes.md`, `research/hypotheses.md`, `research/trials.md` (전부 비커밋)

- [ ] **Step 1:** `.gitignore` 에 `research/` 추가, 위 4개 파일 생성. `trials.md` 머리 표:
  `| # | 날짜 | 가설 | 변형 | 구간 | backtest_id | 총수익 | Sharpe | MDD | 거래종목수 | factor_warnings | 유효 |`
- [ ] **Step 2:** `git status --short` 에 `research/` 가 안 보이고 `.gitignore` 만 보이면 통과.
- [ ] **Step 3:** `git commit -m "chore: 비공개 리서치 산출물 디렉터리 무시"` (`.gitignore` 만)

### Task 2: 리서치 → notes.md

**Files:** `research/notes.md`

- [ ] **Step 1:** 국내 팩터 실증 조사(논문·증권사 퀀트 리포트) — 8개 팩터 각각 KRX 성과·실패 시기를 출처 링크와 함께.
- [ ] **Step 2:** 제도·구조 변화 조사: 밸류업 프로그램, 공매도 재개, 외국인 수급, **2026년 현행 증권거래세·수수료율**(config `tax`·`fees` 근거).
- [ ] **Step 3:** 현재 국면: qf-paper DB `news_articles`(2026-09~) 요약 + 웹 검색. 사실과 해석을 구분해 적는다.
- [ ] **Step 4:** 통과 조건: 모든 주장에 출처가 있고, 세율 값이 확정돼 있다.

### Task 3: 가설·시행 계획 고정 → hypotheses.md

**Files:** `research/hypotheses.md`

- [ ] **Step 1:** 가설 2~3개. 각각: 주장 / 근거(notes.md 항목) / 팩터·설정 매핑 / 반증 조건 / (해당 시) 학습 시작 지연 사유.
- [ ] **Step 2:** 공통값 확정: 유니버스 소스 1개(전 시행 공통), `top_n`, `cadence: monthly`, `capital`(실전 계획액), `fill_mode: next_close`,
  `fees`·`tax`(Task 2 값), `slippage_bps ≥ 5`, `benchmark_index: KOSPI200`. 각 값 옆에 근거 한 줄.
- [ ] **Step 3:** 시행 목록을 **완전한 config JSON** 으로 적는다(가설당 기본 1 + 변형 ≤ 4, 총 ≤ 15). 학습 구간·확인 구간 날짜를 숫자로.
- [ ] **Step 4:** 파일 맨 위에 `고정: 2026-MM-DD HH:MM KST` 기록. 사용자에게 가설 요약을 보여 주고 확인받는다.

### Task 4: 학습 구간 백테스트

**Files:** `research/trials.md`

- [ ] **Step 1:** 사용자에게 Chrome `http://localhost:13000` 로그인 요청. `tabs_context_mcp` → 새 탭에서 `fetch('/api/strategies')` 가 200 이면 진행.
- [ ] **Step 2:** `POST /api/strategies` `{name:"리서치-학습", description:"비공개 리서치 시행용 — 시작 금지", config:<시행1>}` 로 전략 1개 생성, id 기록.
- [ ] **Step 3:** 시행마다(순차): trials.md 에서 중복 확인 → `PATCH /api/strategies/{id}` `{config}` → `POST /api/strategies/{id}/backtest`
  `{period_start, period_end}`(학습 구간) → 결과에서 총수익·Sharpe·MDD·거래 종목 수·`factor_warnings` 를 trials.md 에 기록.
  거래 종목 0 → 무효 기록 후 **중단·보고**(Review Focus 1). 401 → 중단·재로그인 요청.
- [ ] **Step 4:** 통과 조건: 계획한 시행이 모두 기록됨(무효 포함), 추가 시행 없음.

### Task 5: 후보 선정 → 확인 구간 → 판정

**Files:** `research/trials.md`, `research/hypotheses.md`(판정 절 추가)

- [ ] **Step 1:** 학습 구간 최고 Sharpe 시행 각 가설별 1개 = 후보. 각 후보의 `GET /api/backtests/{id}/dsr` 기록 — DSR ≥ 0.95 아니면 탈락.
- [ ] **Step 2:** 살아남은 후보만 같은 전략에 config PATCH → 확인 구간 백테스트 **1회씩**.
- [ ] **Step 3:** 판정: KOSPI200 대비 초과수익 > 0 / Sharpe ≥ 학습 Sharpe × 0.5 / MDD ≤ 같은 기간 KOSPI200 MDD. 동률이면 회전율 낮은 쪽.
- [ ] **Step 4:** 통과 후보 없음 → 결과를 사용자에게 보고하고 Task 3 으로(새 가설은 날짜 붙여 추가, 기존 시행 수는 이어서 센다). 통과 → 최종 config 를 hypotheses.md 에 기록.

### Task 6: 모의 기동

- [ ] **Step 1:** 사용자에게 최종 config·근거·학습/확인 성과·예상 범위(확인 구간 월수익 분포, MDD)·모의 실패 기준을 보고하고 **기동 확인**을 받는다.
- [ ] **Step 2:** 확인 후 새 전략 `"팩터-모의-v1"` 생성(리서치 전략과 분리), 사용자 화면에서 시작. 전략별 리스크 한도는 사용자와 정한 값.
- [ ] **Step 3:** 다음 리밸런싱 시점에 주문·체결이 `orders`·`executions` 에 생기는지 확인하고 보고.

### Task 7: 모의 관찰(약 3개월, 리밸런싱 3회)

- [ ] **Step 1:** 매 리밸런싱 후: 같은 기간 백테스트 재실행(별도 전략·별도 기간이라 DSR 풀과 섞이지 않음)과 모의 성과 비교, live_gate 체결 표본(n ≥ 30) 확인 → `research/paper-log.md`.
- [ ] **Step 2:** 실패 기준(예상 범위 크게 이탈 / MDD > 백테스트 최악치 × 1.5) 해당 시 즉시 보고, 실전 불가 판정.
- [ ] **Step 3:** 3회 후 실전 여부는 사용자가 결정. 이어서 ② 정기 재검토 브레인스토밍.
