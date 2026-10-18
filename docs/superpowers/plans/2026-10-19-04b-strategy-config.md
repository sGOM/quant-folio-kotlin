# 4b단계 전략 설정 스키마 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 원본 `schemas/strategy.py` 의 전략 설정 검증·기본값·정규화를 재현 — 생성·수정 요청의 config 를 검증하고 pydantic `model_dump()` 와 같은 맵으로 돌려준다.

**Architecture:** 원본은 생성·수정 시 검증 후 **기본값이 채워진 dump** 를 DB 에 저장하고, 백테스트는 저장된 dict 를 그대로 읽는다.
그래서 이 단계는 API 경계의 검증·dump 만 다룬다(백테스트 쪽 `.get(키, 기본값)` 은 4c 이후 해당 코드와 함께).
`core/strategy/` 에 설정 data class(18종 + 중첩 9종)와, JSON 트리(Map)를 필드별로 읽으며 pydantic 과 같은 오류(loc·msg·type)를 모으는 파서.

**Spec:** 설계 §7(JSONB config), §5(422 형식)

## Global Constraints

- 판별자 `type`(18종). 알 수 없는 키는 무시(pydantic extra=ignore). 기본값은 검증하지 않는다(pydantic 동일).
- 느슨한 변환(pydantic lax): int ← 정수값 실수·숫자 문자열, float ← int·숫자 문자열, bool ← bool·"true"/"false"/"1"/"0"·0/1. str·Literal 은 문자열만.
- 오류 메시지는 pydantic 문구 그대로: `Field required`, `Input should be greater than or equal to {v}`, `Input should be greater than {v}`, `Input should be less than or equal to {v}`, `String should have at least {n} characters`, `String should have at most {n} characters`, `List should have at least {n} item after validation, not {m}`, `List should have at most {n} items after validation, not {m}`, `Input should be {a}, {b} or {c}`(Literal), `Input should be a valid integer` 등, 커스텀 검증은 `Value error, {원문}`.
- 정규화: universe 공백 제거, rebalance_time `HH:MM` 0 채움, weekly→weekday 기본 0, monthly/quarterly→dom 기본 1, entry/exit 가 리스트면 `{"combinator":"and","children":[...]}`.

## Review Focus

1. **drift_band_pct 가 목표비중 이상** — 매매가 영원히 안 일어나는 설정 거부(score 가중이면 2/(n+1)). → Task 2 정답지.
2. **factor_weights 합 ≠ 1** — 1e-6 허용. → Task 2.
3. **PIT 소스(universe_rule.source≠fixed)면 universe 비어도 허용** — 아니면 거부. → Task 2.
4. **커스텀 규칙에 OHLC 피연산자(리밸런싱)** — 거부. → Task 2.
5. **레거시 리스트형 entry/exit** — 그룹으로 감싼다. → Task 2.

---

### Task 1: 정답지

**Files:** Create `tools/oracle/gen_strategy_config.py`, `backend/core/src/test/resources/oracle/strategy_config.json`.

- 케이스 ≈ 50: 18종 각각 최소 입력(기본값 dump), 대표 전체 입력, 각 model/field validator 위반, 범위 위반(ge/gt/le), 누락 필드, 잘못된 Literal, 문자열 숫자 변환, 레거시 리스트 entry, 중첩 그룹, cadence 기본값 채움, rebalance_time 정규화.
- 출력: `{"input":…, "ok": true, "dump": model_dump()}` 또는 `{"ok": false, "errors": [{"loc": […], "msg": …, "type": …}]}`(TypeAdapter(StrategyConfig) 로 검증, loc 은 판별자 태그 포함).
- [ ] 실행·생성 → commit `test(oracle): 전략 설정 정답지`.

### Task 2: 파서·data class·dump

**Files:** Create `backend/core/src/main/kotlin/com/quantfolio/strategy/{StrategyConfig.kt,StrategyConfigParser.kt,ConfigReader.kt}`; Test `.../strategy/StrategyConfigOracleTest.kt`.

**Interfaces (Produces):**
- `sealed interface StrategyConfig { val type: String; fun toMap(): Map<String, Any?> }` + `data class SmaConfig(...)` … `RebalanceConfig(...)`, 중첩 `Operand`, `Condition`, `ConditionGroup`(children: `List<RuleNode>`, `sealed interface RuleNode`), `FactorWeights`, `UniverseRule`, `VolGate`, `RebalanceSelection`, `RegimeFilter`, `RiskLayer`, `PanicOverlay`.
- `data class ConfigError(val loc: List<Any>, val msg: String, val type: String)`; `class StrategyConfigException(val errors: List<ConfigError>) : RuntimeException()`.
- `object StrategyConfigParser { fun parse(input: Any?): StrategyConfig /* 실패 시 StrategyConfigException */ }`. loc 은 정답지와 같은 형식(판별자 태그 포함, `body`·`config` 접두는 web 이 붙인다).
- 대조 규칙: 성공 케이스는 dump 맵이 정답지와 같다(숫자는 값으로 비교: 10000000 == 1.0E7). 실패 케이스는 (loc, msg) 집합이 같다.
- [ ] 실패 테스트(정답지 전 케이스) → RED → 구현 → GREEN → commit `feat(strategy): 전략 설정 스키마`.
- [ ] `.claude/rules/strategy-config.md` 작성(paths `backend/core/src/main/kotlin/com/quantfolio/strategy/**`): 저장 dump 원칙, 검증 규칙 요지, 옛 행 필드 누락 주의. commit.
