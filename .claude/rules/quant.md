---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/quant/**"
  - "backend/core/src/main/kotlin/com/quantfolio/{factor,metrics,backtest,rebalance}/**"
  - "tools/oracle/**"
---

# 수치 계산 — pandas 의미 재현과 정답지

원본 계산은 pandas/numpy 위에 있다. Kotlin 은 `Series`/`Panel` 로 **같은 의미**를 재현하고, 원본 Python 을 돌린 정답지로 증명한다.

## 정답지(oracle)

- `tools/oracle/run.sh gen_x.py` — 원본 이미지(`quantfolio-web`)에서 기준 커밋 소스를 마운트해 실행, `--network none`.
  출력: `backend/core/src/test/resources/oracle/x.json`(다른 모듈 정답지는 `ORACLE_OUT=backend/<모듈>/src/test/resources/oracle`). 테스트는 `Oracle.load("x")` + `Oracle.assertClose`.
- 입력은 스크립트가 시드로 만들고 **입력·출력을 함께** 저장한다(Kotlin 이 난수를 재현하지 않는다).
- **1층(커밋)은 합성 입력만.** 실데이터 결과(2층)는 커밋 금지 — 공개 저장소.
- 새 계산을 옮길 때: 원본 함수로 정답지 스크립트 추가 → 실패 테스트 → 구현. 경계값(NaN·동점·짧은 길이)을 입력에 일부러 넣는다.

## pandas 함정 (구현 때 한 줄씩 확인)

| 항목 | pandas 2.2.3 | 흔한 실수 |
|---|---|---|
| std | ddof=1 (numpy 는 0) | 모집단 표준편차 사용 |
| rolling | min_periods 기본 = window, 창 안 NaN 은 개수 제외 | NaN 을 0 으로 계산 |
| ewm | adjust=True, 결측 구간에도 가중치 감쇠 | 단순 재귀식 |
| pct_change | 기본 fill_method='pad'(NaN 앞값 채움) | 채움 없이 계산 |
| sum / mean | skipna. 전부 NaN 이면 sum=0, mean=NaN | |
| round | half-even (`Math.rint`) | `roundToLong` |
| `//`·`%` | 음수에서 내림 | Kotlin 은 0 방향 절삭 |
| sort_values / nlargest | 동점 처리 규칙이 다름 | 동점 순서 차이로 NAV 경로가 갈림 |
| scipy skew/kurtosis | bias=True, kurtosis fisher=True | |

## 미래참조 금지

결정 시점 d 의 계산에는 `Panel.upTo(d)` 뷰만 넘긴다. 뷰는 d 이후 행이 없어 구조적으로 미래를 볼 수 없다.
**예외(원본 보존):** 상장폐지 판정 `lastValid`·DART 정정공시·업종 PIT 폴백은 미래를 본다 — `docs/migration-notes.md` #10~12.
교란 테스트는 cut 이후 값을 **배율로만** 바꿔 결측 패턴이 같으므로 `lastValid` 를 잡지 못한다.
백테스트 엔진을 고치면 `PortfolioLookaheadTest`(cut 이후 교란 → cut 까지 결과 동일)가 반드시 초록이어야 한다.

## 계산부와 조회부 분리

`factor/`·`metrics/` 는 순수 계산(`FactorScores`·`MetricsPure`)을 정답지로, 조회 조립(`*Service`·`UniverseScores`)을
`SnapshotSource`·`PriceHistory`·`FactorInputs` fake 로 테스트한다. 가중치가 걸린 팩터가 **전량** 실패하면 예외 — 중립 처리 금지.
