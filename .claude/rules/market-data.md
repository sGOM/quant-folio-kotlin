---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/market/**"
---

# 시장데이터 — 외부 소스·로컬 저장소

## 실패는 값이 아니라 예외다

외부 소스(KRX·DART·KOFIA·네이버·KIS) 실패는 반드시 `DataSourceError` 하위로 던진다. 빈 리스트로 삼키면
백테스트가 빈 패널 위에서 '성공'하며 무의미한 수치를 낸다(원본 §44-1 실제 사고).

| 원인 | 의미 | 재시도 | 쿨다운 | HTTP |
|---|---|---|---|---|
| `SourceAuthError` | 인증·차단 | ✗ | 300s | 503 |
| `SourceQuotaError` | 한도 초과(`retryAfterSeconds` 로 리셋 시각) | ✗ | 300s | 503 |
| `SourceUnavailableError` | 네트워크·5xx·점검 | ✓ | 60s | 503 |
| `SourceSchemaError` | 응답 형식이 파서와 다름 — 코드 수정 필요 | ✗ | 없음 | 502 |
| `SourceRequestError` | 우리가 잘못 보냄 — 버그 | ✗ | 없음 | 500 |

- 종목·시장별 루프는 실패를 모으고 `Cooldowns.stopAggregate()` 로 단락, 전량 실패면 `DataSourceErrors.representative()` 를 던진다.
  일부만 실패하면 성공분을 쓰되 **확정으로 굳히지 않는다**(다음 호출에서 보완).
- 쿨다운 검사는 "캐시된 세션 재사용" 분기보다 **앞에서** 한다(차단 중에도 요청이 나가는 결함, §44-1).

## 로컬 우선 4상태 (`StoreCache`)

확정 기록 있음 → 로컬 / 기록 없음·미확정 → 원격 1회 + 저장 + 원장 / 원격 실패 → 예외 전파 / 소스 미설정 → 진입 전 처리.

- **0행은 절대 확정 금지.** "진짜 무자료"와 "스키마 변경으로 값 잃음"을 구분할 수 없고, 굳으면 수동 삭제 전까지 영구 0행(§47·§49).
- 요청 컬럼 중 **전부 null 인 컬럼**이 있으면 확정 보류(포맷 변경으로 변환이 통째로 실패한 신호).
- 확정은 **KST 전날까지**(`StoreCache.lastFinalDate()`). 당일분은 장중 값이 바뀐다.
- 범위 조회(`cachedRange`)는 **개별** 확보 구간이 요청을 덮을 때만 로컬. 구간 합집합으로 판정하지 않는다.
  실패 예외엔 로컬 확보 구간을 덧붙이되, 기간을 대신 줄이지 않는다.

## 저장소 테이블

- 리포지토리는 테이블별 타입 행(`StockDaily` 등, 숫자 `Double?`)을 주고받는다. upsert 는 모든 컬럼
  `COALESCE(EXCLUDED, 기존)` — 들어온 null 은 기존값 보존(여러 소스가 한 행을 나눠 채운다).
- 저장 시 종목코드 6자리 0-패딩, 같은 종목 중복은 마지막 값, NaN/±Inf → null.
- 시장 혼재 테이블(`stock_daily_snapshots`·`stock_period_stats`)의 단일시장 조회는 반드시 `markets` 필터.
- 운영 테이블과 FK 없음 — 저장소는 통째로 지우고 재적재할 수 있어야 한다.

## 함정

- 원본 데이터 소스: 종목 일봉 = FDR → 네이버 차트(수정주가), KRX MDC 계열 = `getJsonData.cmd` POST. 소스를 바꾸면 기존 DB 와 섞인다.
- KRX 로그인은 계정 차단 이력이 있다 — 반복 로그인 테스트 금지, 응답 녹화는 한 번에.
