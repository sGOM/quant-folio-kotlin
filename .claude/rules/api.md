---
paths:
  - "backend/web/src/main/kotlin/com/quantfolio/web/**"
  - "frontend/app/settings/**"
  - "frontend/app/dashboard/**"
  - "frontend/app/login/**"
  - "frontend/lib/api.ts"
---

# API — 응답 계약·인증

**진짜 계약서는 원본 프론트 `frontend/lib/api.ts`** 다. 엔드포인트를 옮길 땐 원본 FastAPI 라우트
(`backend/app/api/routes/*.py`)와 `api.ts` 의 사용처를 같이 보고 경로·메서드·파라미터·상태코드·필드를 그대로 맞춘다.

## 응답 계약 (프론트가 무수정으로 붙기 위한 규칙)

| 항목 | 규칙 | 구현 위치 |
|---|---|---|
| 필드명 | snake_case (DTO 는 camelCase 로 쓰면 자동 변환) | `JsonConfig` |
| 결측 | 항상 `null` 직렬화. 0 sentinel 금지 | Jackson 기본 |
| NaN/±Inf | `null` | `JsonConfig` |
| 오류 | `{"detail": "한국어 메시지"}` — `throw ApiException(status, detail)` | `ApiErrorHandler` |
| 검증 오류 | 422 `{"detail":[{"type","loc":["body"\|"query", 필드],"msg"}]}` | `ApiErrorHandler` |
| 404·405 | `{"detail":"Not Found"}` 등 reason phrase | `ApiErrorHandler` |
| 미처리 예외 | 500 `{"detail":"Internal Server Error"}`, 상세는 로그에만(`DEBUG_ERRORS` 는 prod 에서 무시) | `ApiErrorHandler` |

결측을 정렬할 땐 맨 뒤로, 필터링할 땐 탈락시킨다 — 결측은 기준 충족을 증명할 수 없다.

## 인증

- 서버측 세션(JWT 아님). 쿠키 `qf_session`(HttpOnly, path `/`) → Redis `session:{sid}` → user_id. 인증 성공마다 TTL 갱신.
- 컨트롤러는 `@CurrentUser user: User` 파라미터로 인증을 요구한다. 실패 시 401 `자격 증명을 확인할 수 없습니다.`
- 로그인은 form `password` 하나로 `TRADING_OWNER_USER_ID` 사용자 행의 기존 해시만 대조한다(틀리면 401 `비밀번호가 올바르지 않습니다.`).
  구버전 클라이언트의 `username` 은 읽지 않는다(오류도 아님). 소유자 미설정·행 없음은 같은 503 문구, 원인은 로그에만.
  회원가입은 403 `회원가입은 지원하지 않습니다.`(본문 무시, 행 생성 없음).
- 브루트포스 방어: 고정 소유자 키(`OWNER_THROTTLE_KEY`) 10회·IP 50회/15분 고정 윈도우 → 429. 입력 username으로 제한 키를 바꿀 수 없다.
  DB 조회 전에 차단 여부를 본다. Redis 장애면 차단하지 않는다(fail-open).
- WebSocket 도 같은 쿠키로 인증, 실패 시 close code 4401.
- 기존 사용자 ID·비밀번호 해시는 데이터 연결과 접속 보호를 위해 내부적으로 유지한다. `TRADING_OWNER_USER_ID` 외 사용자의 기존 세션은
  `SessionStore.userId()` 가 null 로 돌려(HTTP·WS 공용 지점) 401·4401 이 된다 — 미설정이면 모든 세션. 사용자 응답의 자격증명 상태는 실제 서버 설정을 반영하고 비소유자에게 소유자 계좌를 노출하지 않는다.
- 증권사 자격증명 등록 API는 없다(`PUT /api/kis/credentials`·`/toss-quote` 는 404). 설정 화면은 서버에서 관리하는 연결 상태를 표시한다.

## 함정

- 혼자 쓰는 서비스이므로 사용자에게 계정 선택·소유자 ID 지정을 맡기지 않는다. 접속 비밀번호만 유지하고 기존 데이터 소유권은 내부 ID로 보존한다(2026-11-16 사용자 교정).
- **클라이언트 IP 는 `ClientIp.of()` 하나로만.** `X-Forwarded-For` 의 **마지막** 항목만 신뢰한다.
  앞 항목을 쓰면 헤더를 끼워 넣어 로그인 스로틀을 우회할 수 있다(원본 §58 실제 결함).
- 사용자 응답(`UserOut`)에 자격증명 원문·암호문을 넣지 않는다. 보유 여부만 boolean 으로.
- 메시지 문자열은 원본과 동일하게 유지한다(프론트·사용자가 보는 문구). 예외: 로그인·가입 문구는 단일 소유자 전환으로 바뀌었다.
- **숫자가 낀 필드명은 snake_case 자동 변환이 틀린다**(`mom1m`→`mom1m`, 원본은 `mom_1m`). `@JsonProperty("mom_1m")` 로 명시한다.
- 세션을 쓰는 web 테스트는 그 사용자를 소유자로 지정해야 한다(`@MockitoSpyBean props` + `willReturn(id).given(props).tradingOwnerUserId`).
  스파이 조합이 다른 테스트 클래스마다 Spring 컨텍스트가 늘어 Postgres 접속 수가 바닥난다(`too many clients`) — 기존 조합에 맞춘다.
- MockMvc `jsonPath` 가 **배열을 돌려주는 식**(`$.items[*].x`, `$.a.list`)은 null 로 평가돼 실패한다. 원소 단위(`[0]`)로 단언하거나 본문을 파싱해 비교한다.
