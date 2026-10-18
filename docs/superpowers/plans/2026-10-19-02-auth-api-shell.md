# 2단계 인증·API 껍데기 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 프론트가 무수정으로 붙는 응답 계약(직렬화·에러) + 원본과 호환되는 암호(Fernet·bcrypt) + 세션 인증과 `/api/auth/*`·`/health`.

**Architecture:** 암호·비밀번호는 `core`(engine 도 자격증명을 복호화하므로), 세션·로그인 스로틀·컨트롤러·에러 처리·Jackson 설정은 `web`.
원본 `app/core/{security,session}.py`, `app/api/{deps.py,routes/auth.py}`, `app/main.py` 를 옮긴다.

**Tech Stack:** 1단계 + `spring-security-crypto`(BCrypt만; Spring Security 필터 체인은 쓰지 않는다).

**Spec:** `docs/superpowers/specs/2026-10-19-kotlin-migration-design.md` §5, §6.3

## Global Constraints

- 세션 쿠키 이름 `qf_session`, HttpOnly, path `/`, max-age = `sessionTtlMinutes*60`, secure/samesite/domain 은 설정값.
- 세션 키 `session:{sid}` → user_id 문자열, TTL = `sessionTtlMinutes*60`, 인증 성공마다 TTL 갱신. sid = 32바이트 난수 URL-safe base64(패딩 없음).
- 로그인 스로틀 키 `login:fail:email:{email}`, `login:fail:ip:{ip}`, 고정 윈도우 900초, 한도 이메일 10·IP 50(`>=` 이면 차단). Redis 실패 시 **차단하지 않음**(fail-open).
- 클라이언트 IP = `X-Forwarded-For` 의 **마지막** 항목(trim), 없으면 remote addr.
- 에러 본문 `{"detail": "..."}`; 검증 오류 422 `{"detail":[{"type","loc","msg"}]}`; 미처리 예외 500 `{"detail":"Internal Server Error"}`(`exposeErrorDetails` 면 `error:{type,message,path,traceback}` 추가).
- JSON snake_case, null 항상 포함, NaN/±Inf → null, `Instant` 는 ISO-8601 문자열.
- 메시지 문자열은 원본 그대로: 401 `자격 증명을 확인할 수 없습니다.`, 로그인 실패 401 `이메일 또는 비밀번호가 올바르지 않습니다.`, 429 `로그인 시도가 너무 많습니다. 잠시 후 다시 시도하세요.`, 409 `이미 등록된 이메일입니다.`

## Review Focus

1. **Python 이 만든 Fernet 토큰 복호화** — 기존 DB 의 증권사 키. → Task 1 픽스처 테스트.
2. **`$2b$` bcrypt 해시 검증** — 기존 사용자 로그인. → Task 1 픽스처 테스트.
3. **위조/변조 Fernet 토큰** — 예외로 거부(빈 문자열 반환 금지). → Task 1.
4. **X-Forwarded-For 스푸핑** — `"1.1.1.1, 9.9.9.9"` 에서 9.9.9.9 를 쓴다. → Task 3.
5. **Redis 장애 시 로그인** — 스로틀이 예외로 로그인을 막지 않는다. → Task 3.

---

### Task 1: Fernet·비밀번호 (core)

**Files:** Create `backend/core/src/main/kotlin/com/quantfolio/security/{Fernet.kt,Passwords.kt,CredentialCipher.kt}`; Test `backend/core/src/test/kotlin/com/quantfolio/security/{FernetTest.kt,PasswordsTest.kt}`; Modify `backend/core/build.gradle.kts`(`api("org.springframework.security:spring-security-crypto")`).

**Interfaces (Produces):**
- `class Fernet(key: String) { fun encrypt(plaintext: String): String; fun decrypt(token: String): String }` — `decrypt` 실패는 `InvalidFernetToken`(RuntimeException). 키가 base64url 32바이트가 아니면 `IllegalArgumentException`. `companion fun generateKey(): String`.
- `object Passwords { fun hash(raw: String): String; fun verify(raw: String, hash: String): Boolean }` — 형식 오류 해시는 false.
- `@Component class CredentialCipher(props)` — `encrypt/decrypt` 위임. 키가 비었으면 dev 는 임시키 생성+WARN, prod 는 1단계 검증이 이미 거부. 키 형식 오류는 부팅 실패.

Fernet: 토큰 = base64url(`0x80` ‖ ts(8B BE) ‖ IV(16) ‖ AES-128-CBC/PKCS7(ct) ‖ HMAC-SHA256(앞 전체)); 키 앞 16B=서명키, 뒤 16B=암호키. TTL 검사 없음(원본도 ttl 미사용).

- [ ] 실패 테스트: `decryptsPythonToken`(키 `q8Zx3-1o0ZL3bP4kQ6V0wJm2r9sYH5c7nT1uE2fGhIk=`, 토큰 `gAAAAABq1O9Ed_IckAjfAkJiEKvFB6QxHmnNu7dMuWkDxAJ1zIiuDvasSZk0kDdPSJa5MxDL2QV74zFCugb17bxICIEmjFvn6kcWkDZ4wg69-rYII5Cgw10=` → `PSabc123-앱시크릿/+=`), `roundTrip`, `tamperedTokenRejected`(마지막 문자 변경), `wrongKeyRejected`; `verifiesPythonBcrypt`(`$2b$12$tzU.mBUX9gtZUw3/q0ZafehPvEa.xtjVjhvJqfSPzzETSeaPxJC3y` ↔ `correct horse 8`), `wrongPasswordFalse`, `malformedHashFalse`.
- [ ] RED → 구현 → GREEN → commit `feat(security): 원본 호환 Fernet·bcrypt`.

### Task 2: 응답 계약 — Jackson·에러 처리 (web)

**Files:** Create `backend/web/src/main/kotlin/com/quantfolio/web/api/{ApiException.kt,ApiErrorHandler.kt,JsonConfig.kt,HealthController.kt}`, `web/config/CorsConfig.kt`; Test `backend/web/src/test/kotlin/com/quantfolio/web/api/ApiContractTest.kt`(테스트 전용 `@RestController` 로 각 상황 재현).

**Interfaces (Produces):**
- `open class ApiException(val status: HttpStatus, val detail: String) : RuntimeException(detail)` — 모든 라우트는 이걸 던져 `{"detail"}` 를 만든다.
- CORS: origin = `frontendOrigin`, credentials 허용, 모든 메서드·헤더.
- `GET /health` → `{"status":"ok","redis":bool,"kis_env":str,"paper_trading":bool}`.

- [ ] 실패 테스트: `apiExceptionHasDetail`(409 → `{"detail":"x"}`), `validationIs422WithLocMsg`(body `{"n":-1}` 에 `@Min(0)` → 422, `detail[0].loc == ["body","n"]`, `msg` 존재), `malformedJsonIs422`, `unhandledIs500Generic`(본문이 정확히 `{"detail":"Internal Server Error"}`), `snakeCaseAndNulls`(`data class X(val fooBar: Int, val maybe: String?)` → `{"foo_bar":1,"maybe":null}`), `nanBecomesNull`(`Double.NaN`, `POSITIVE_INFINITY` → null), `instantIsIso`(`2026-10-19T01:02:03.456Z`), `healthShape`.
- [ ] RED → 구현 → GREEN → commit `feat(web): 응답 계약(직렬화·에러)·health·CORS`.

### Task 3: 세션·로그인 스로틀·현재 사용자 (web)

**Files:** Create `backend/web/src/main/kotlin/com/quantfolio/web/auth/{SessionStore.kt,LoginThrottle.kt,CurrentUser.kt,ClientIp.kt}`; Test `backend/web/src/test/kotlin/com/quantfolio/web/auth/{SessionStoreTest.kt,LoginThrottleTest.kt,ClientIpTest.kt}`.

**Interfaces (Produces):**
- `@Component class SessionStore { fun create(userId: Int): String; fun userId(sid: String?): Int?; fun destroy(sid: String?) }` — 빈 sid 는 Redis 를 건드리지 않고 null/no-op, 값 파싱 실패는 null.
- `@Component class LoginThrottle { fun blocked(email: String, ip: String): Boolean; fun recordFailure(email: String, ip: String); fun reset(email: String) }`.
- `object ClientIp { fun of(request: HttpServletRequest): String }`.
- `@Target(VALUE_PARAMETER) annotation class CurrentUser` + `HandlerMethodArgumentResolver` → `User`, 실패 시 `ApiException(401, "자격 증명을 확인할 수 없습니다.")`.

- [ ] 실패 테스트(실제 Redis 컨테이너): `createStoresUserIdWithTtl`(sid 길이 43, TTL ≈ 설정값), `userIdRenewsTtl`(TTL 을 10초로 줄인 뒤 조회 → 다시 설정값), `unknownSidNull`, `blankSidNull`, `garbageValueNull`, `destroyRemoves`; `blockedAtEmailLimitNotBefore`(9회 false, 10회 true), `blockedByIpAcrossEmails`(서로 다른 이메일 50회), `resetClearsEmailNotIp`, `failOpenWhenRedisDown`(연결 불가 팩토리로 만든 인스턴스 → `blocked` false, `recordFailure` 예외 없음); `lastForwardedForWins`, `fallsBackToRemoteAddr`.
- [ ] RED → 구현 → GREEN → commit `feat(auth): 세션·로그인 스로틀·현재 사용자`.

### Task 4: `/api/auth/*` 라우트

**Files:** Create `backend/web/src/main/kotlin/com/quantfolio/web/auth/{AuthController.kt,AuthDtos.kt}`; Test `backend/web/src/test/kotlin/com/quantfolio/web/auth/AuthControllerTest.kt`(MockMvc + 컨테이너).

**Interfaces (Produces):**
- `data class UserOut(id: Int, email: String, displayName: String?, broker: String, kisAccountNo: String?, hasKisCredentials: Boolean, hasTossQuote: Boolean)`; `fun User.toOut(props): UserOut` — `hasKisCredentials = !kisAppKey.isNullOrEmpty()`, `hasTossQuote = (tossAppKey·tossAppSecret 둘 다 존재) || (props.toss.appKey·appSecret 둘 다 존재)`.
- `POST /api/auth/register` JSON `{email, password(8..128)}` → 201 UserOut / 409; `POST /api/auth/login` form `username,password` → 200 UserOut + 쿠키 / 401 / 429(DB 조회 전); `POST /api/auth/logout` → 204 + 쿠키 삭제(Max-Age=0); `GET /api/auth/me`; `PATCH /api/auth/me` `{display_name}` → trim, 빈 값은 null.

- [ ] 실패 테스트: `registerHashesPassword`, `registerDuplicate409`, `registerShortPassword422`, `loginSetsCookieAndResets`, `loginWrongPassword401Counts`, `loginUnknownEmail401Counts`, `loginBlocked429`, `logoutDestroysAndClears`, `logoutWithoutCookie204`, `meRequiresSession401`, `meReturnsUser`, `patchMeTrims`, `patchMeBlankToNull`, `existingPythonUserCanLogin`(Task 1 의 `$2b$` 해시로 저장한 사용자).
- [ ] RED → 구현 → GREEN → commit `feat(auth): /api/auth 라우트`.
- [ ] `.claude/rules/api.md` 작성(paths: `backend/web/src/main/kotlin/com/quantfolio/web/**`) — 위 계약·함정. md 린트 0건. commit `docs(claude): api 규칙`.
