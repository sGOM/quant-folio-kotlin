---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/strategy/**"
---

# 전략 설정(config)

## 저장 원칙

- 생성·수정 요청의 config 는 `StrategyConfigParser.parse()` 로 검증하고 **반환된 맵(기본값이 채워진 dump)을 그대로 저장**한다
  (원본 pydantic `model_dump()` 와 같은 값 — 정답지 `strategy_config.json` 으로 증명).
- 백테스트·엔진은 저장된 맵을 **재검증 없이** 읽는다. 스키마에 필드가 나중에 추가됐다면 **옛 행에는 그 키가 없다** —
  읽는 쪽에서 원본 Python 의 `.get(키, 기본값)` 과 같은 기본값을 적용해야 한다(스키마 기본값과 다를 수 있으니 원본 코드를 확인).

## 구조

- `StrategySchemas` 가 원본 `schemas/strategy.py` 를 **표(필드 명세 + 검증기)** 로 옮긴 것이다. 필드를 추가·변경할 땐
  원본과 같은 제약 타입(Int/Double)으로 적는다 — 오류 문구의 숫자 표기(`1` vs `1.0`)가 여기서 결정된다.
- `Validator` 는 pydantic v2 lax 모드 재현: bool→int/float 허용, 숫자 문자열 허용, 정수가 아닌 실수→int 거부,
  모델 검증기는 필드가 모두 유효할 때만, 조건 노드는 smart union(두 갈래 오류를 태그와 함께 모두 보고).
- 오류 문구는 pydantic 영문 그대로, 커스텀 검증은 `Value error, {한국어 원문}`. 프론트가 msg 를 그대로 보여준다.

## 검증 규칙 요지 (리밸런싱)

- `drift_band_pct` 는 목표비중(동일가중 1/n, 점수가중 2/(n+1)) 미만 — 아니면 매매가 영원히 안 일어난다.
- `risk_layer.max_position_pct > drift_band_pct`, `factor_weights` 합 = 1(±1e-6).
- PIT 소스(`universe_rule.source ≠ fixed`)면 universe 가 비어도 된다.
- 커스텀 선택 규칙은 종가 기반만(open/high/low/volume 피연산자 금지), weighting 은 equal/inverse_vol.

## 함정

- Python 실수 포맷: 메시지 안 `{x:.3f}` 는 `pyFixed`(half-even), 실수 repr 은 `pyFloat`. Java `%.3f` 는 half-up 이라 0.0625 에서 갈린다.
