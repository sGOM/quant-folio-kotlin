---
paths:
  - "backend/web/src/main/kotlin/com/quantfolio/web/**"
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
- 로그인은 form(`username`=이메일, `password`). 브루트포스 방어: 이메일 10회·IP 50회/15분 고정 윈도우 → 429.
  DB 조회 전에 차단 여부를 본다. Redis 장애면 차단하지 않는다(fail-open).
- WebSocket 도 같은 쿠키로 인증, 실패 시 close code 4401.

## 함정

- **클라이언트 IP 는 `ClientIp.of()` 하나로만.** `X-Forwarded-For` 의 **마지막** 항목만 신뢰한다.
  앞 항목을 쓰면 헤더를 끼워 넣어 로그인 스로틀을 우회할 수 있다(원본 §58 실제 결함).
- 사용자 응답(`UserOut`)에 자격증명 원문·암호문을 넣지 않는다. 보유 여부만 boolean 으로.
- 메시지 문자열은 원본과 동일하게 유지한다(프론트·사용자가 보는 문구).
