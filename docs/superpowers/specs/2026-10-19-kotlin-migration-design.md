# quant-folio → Spring Boot + Kotlin 마이그레이션 설계

- 작성일: 2026-10-19
- 원본: `github.com/sGOM/quant-folio` (Python/FastAPI 백엔드 + Next.js 프론트)
- 대상: 이 저장소 `sGOM/quant-folio-kotlin`

## 1. 의도와 이유

소유자는 Spring/Kotlin 개발자이고 Python·FastAPI 를 모른다. 원본 백엔드(앱 코드 약 2.5만 줄,
테스트 약 1.8만 줄)를 학습하는 대신, 익숙한 스택으로 옮겨 **본인이 직접 읽고 고칠 수 있는
코드베이스**를 얻는 것이 목적이다.

시스템은 현재 **모의투자 단계**이며 이후 실거래 예정이다. 따라서 원본이 지키던 실거래 안전
불변식은 이전 후에도 그대로 지켜져야 한다(§9).

## 2. 목표 · 비목표 · 반목표

**목표**
- web(API·WS), engine(실시간 매매), worker(배치), 백테스트·팩터 계산을 **전부 Kotlin 으로** 이전.
  판단 기준(소유자): *코드 줄 수 외에 손실이 없으면 Kotlin 으로 옮긴다.*
- Next.js 프론트는 **사실상 무수정**으로 새 백엔드에 붙는다(§5 API 계약 유지).
- 기존 PostgreSQL DB(스키마·데이터)를 **그대로** 재사용한다.
- 계산 결과가 원본과 일치함을 **정답지(oracle) 테스트**로 증명한다(§8).

**비목표**
- 기능 추가·개선, 전략 로직 변경. 이전은 동작 보존이 원칙이다(원본의 버그도 일단 보존하고 별도 기록).
- 무중단 전환·병행 운영. 컷오버는 **한 번에**(소유자 결정). 모의투자라 정지 허용.
- 데이터 소스를 KRX 공식 OPEN API 로 교체하는 일(→ 후속 과제, §6.2).
- 연구용 검증 스크립트(`backend/scripts/validate_*.py` 등)·노트북 이전(§3).

**반목표 (기술적으로 돌아가도 실패로 본다)**
- 백테스트·팩터 수치가 원본과 **조용히** 달라지는 것(pandas 기본값 차이 등, §8.2).
- 실거래 안전 불변식(§9) 중 하나라도 약해지는 것.
- 기존 DB 의 암호화된 증권사 자격증명이 복호화되지 않는 것.
- 소유자가 읽기 어려운 코드(코루틴·리액티브 남용, 과도한 추상화).

## 3. Python 으로 남기는 것

| 대상 | 처리 | 이유 |
|---|---|---|
| 연구 스크립트 `backend/scripts/*`, `backend/notebooks/*` | **원본 저장소에 그대로 둔다.** 이 저장소로 복사하지 않는다 | 원본 `app.*` 모듈을 import 하는 일회성 연구 코드. 탐색은 pandas 가 편하다 |
| 원본 Python 백엔드 | 이전 기간 동안 **정답지 생성기**로만 사용 | §8. 컷오버 후 원본 저장소는 보관(archive) |

(위 "원본 저장소에 둔다"는 설계자 판단.)

## 4. 기술 스택과 구조

### 4.1 스택 (설계자 판단, 버전은 계획 단계에서 context7 로 확인)

- **Java 25 LTS**, 최신 안정 **Kotlin**, 최신 안정 **Spring Boot**, **Gradle Kotlin DSL**.
  Kotlin·Gradle·Spring Boot 가 JVM 25 타깃을 지원하는지 계획 단계에서 확인하고, 안 되면 21 LTS.
- **Spring MVC + 가상 스레드**(`spring.threads.virtual.enabled=true`). 원본 asyncio 를 대체.
  코루틴/WebFlux 는 쓰지 않는다 — 가독성이 이전 목적이다.
- DB: **PostgreSQL 그대로**, 접근은
  - 운영 테이블(users·strategies·orders·executions·positions·alerts 등) → **Spring Data JPA**
  - 시장데이터 저장소(store 테이블·price_ticks) 대량 upsert/조회 → **NamedParameterJdbcTemplate**
    (`ON CONFLICT` upsert, 날짜×종목 대량 조회는 SQL 그대로가 맞다)
- 마이그레이션: **Flyway** (§7).
- Redis: Spring Data Redis(Lettuce). 채널·키 이름은 원본 `app/core/channels.py` 와 **문자열 동일**.
- 수치: `DoubleArray` 기반 직접 구현. 통계 분포(정규분포 cdf/ppf 등)는 **Apache Commons Math**.
  Kotlin DataFrame·ta4j 는 쓰지 않는다(베타/불필요).
- 시크릿: `spring.config.import=optional:configtree:/run/secrets/` 로 `/run/secrets/*` 파일 마운트를
  그대로 읽는다. 원본의 `<FIELD>_FILE` 커스텀 로더는 옮기지 않는다.
- 테스트: JUnit 5 + Testcontainers(PostgreSQL·Redis). 원본 CI 와 같은 `timescale/timescaledb:latest-pg16` 이미지.

### 4.2 프로세스 구조 (설계자 판단)

원본처럼 **별도 프로세스 3개**를 유지하고 Redis 로만 통신한다. 엔진을 web 장애로부터 격리하는
것이 실거래 관점의 안전한 기본값이다.

```
Gradle 멀티모듈
├─ core     공용: 도메인 엔티티·리포지토리·채널 상수·설정·수치/백테스트/팩터·외부 데이터 클라이언트
├─ web      Spring Boot 앱: REST + WebSocket(/ws), 세션 인증          (원본 app/api)
├─ engine   Spring Boot 앱: 러너·주문 실행·체결·리컨실·리스크·halt    (원본 engine/)
└─ worker   Spring Boot 앱: 배치 스케줄 + 비동기 백테스트 작업 소비    (원본 worker/)
```

Celery 대체:
- **beat** → `@Scheduled(cron=..., zone="Asia/Seoul")`. 스케줄 표는 원본 `.claude/rules/architecture.md` 와 동일.
  worker 가 여러 개 떠도 중복 실행되지 않도록 Redis 락(원본 `lock:` 규약과 같은 방식)으로 감싼다.
- **비동기 백테스트 큐** → 원본의 실행 방식(어떤 엔드포인트가 Celery 로 넘기는지)을 그대로 따라
  Redis 리스트 기반 큐로 worker 가 소비. 구체 방식은 빌더 재량이되 API 응답 계약은 원본과 동일해야 한다.
- 거래일 규칙: 컨테이너 TZ 는 UTC 이고 KRX 거래일은 KST. 날짜는 항상
  `LocalDate.now(ZoneId.of("Asia/Seoul"))` 계열의 단일 헬퍼(원본 `market.now_kst()` 대응)로만 얻는다.

### 4.3 저장소 레이아웃

```
quant-folio-kotlin/
├─ backend/        Gradle 멀티모듈 (위 4.2)
├─ frontend/       원본 frontend/ 를 그대로 복사 (수정 최소)
├─ docker-compose.yml, Caddyfile, secrets/  원본 기반, backend 서비스만 JVM 이미지로 교체
├─ .github/workflows/  backend job 을 Gradle 로 교체, frontend job·e2e-smoke 유지
├─ docs/           원본 docs/ 중 도메인 문서(PRD·CONVENTIONS·strategies 등) 복사 후 Python 경로 갱신
└─ .claude/        원본 rules·agents·skills·hooks 를 Kotlin 경로 기준으로 갱신
```

`help/`(Python 학습 가이드)·`.agents/tossinvest-skill`·`skills-lock.json` 은 가져오지 않는다(설계자 판단).

### 4.4 Claude 문서 구조 (소유자 요구: 이상적인 구조 유지)

원본의 **지연 로딩 구조**를 그대로 유지한다. 원칙: 항상 로드되는 것은 최소로 두고, 도메인 지식은
해당 파일을 만질 때만 도착하게 한다.

| 계층 | 위치 | 담는 것 | 규칙 |
|---|---|---|---|
| 상시 로드 | `CLAUDE.md` | 제품 한 줄 정의, **절대 금지 2항목(문구 그대로)**, 한 줄 아키텍처 지도, 필수 함정, rules 인덱스 표, 문서 유지 규칙 | 짧게 유지(원본 수준 ~60줄). 상세는 넣지 않고 rules 로 링크 |
| 경로 트리거 | `.claude/rules/*.md` (+ `data-model/` 하위) | 아키텍처·데이터 모델·시장데이터·전략 설정·백테스트·검증 워크플로·매매 엔진·API·알림·프론트 | 각 문서의 `paths` frontmatter 를 **Kotlin 경로**(`backend/core/src/main/kotlin/**/backtest/**` 등)로 갱신. 한 문서 = 한 영역. 원본 내용은 보존하고 Python 고유 서술(asyncio·Celery·Alembic·pydantic)만 Kotlin 대응으로 교체 |
| 역할 | `.claude/agents/*.md` | 작업별 서브에이전트 | `backend-fastapi`→`backend-spring`, `review-fastapi`→`review-kotlin` 로 대체. `quant-engine`·`db-architect`·`financial-expert`·`frontend-next`·`review-nextjs`·`ui-expert`·`pr-manager` 는 경로·명령만 갱신 |
| 절차 | `.claude/skills/*` | `run-quantfolio`(기동·스모크), `ci-check`, `commit-pr-merge` | 명령을 Gradle/compose 기준으로 갱신 |
| 자동화 | `.claude/hooks/*`, `.claude/settings.json` | `block-secret-edit`(유지), `backend-restart-reminder`(대상 `backend/**`, 재시작 대상 web/engine/worker 유지) | 동작 동일 |
| 코드 규약 | `docs/CONVENTIONS.md` | Kotlin/Spring 코드 컨벤션 | 원본 Python 컨벤션을 Kotlin 으로 재작성. CLAUDE.md 에서 링크 |

새 문서를 만들 때도 같은 원칙: 상시 지식만 CLAUDE.md, 영역 지식은 `paths` 달린 rules 문서, 반복 절차는 skill.
이 구조 정리는 §10 의 8단계에서 하되, 각 단계에서 만든 코드 영역의 rules 문서는 **그 단계에서 함께** 갱신한다.

## 5. API 계약 — 프론트가 무수정으로 붙기 위한 규칙

**진짜 계약서는 `frontend/lib/api.ts`** 다. 빌더는 각 엔드포인트를 옮길 때 원본 FastAPI 라우트
(49개, `backend/app/api/routes/`)와 `api.ts` 의 사용처를 같이 본다.

- 경로·HTTP 메서드·쿼리 파라미터 이름·상태코드(204 포함) 원본과 동일.
- JSON 필드명 **snake_case** (Jackson `SNAKE_CASE` 전역 설정).
- **null 은 항상 직렬화**한다(`NON_NULL` 금지). 결측은 null, 0 sentinel 금지(원본 api.md 규약).
- NaN/±Infinity → null (원본 `_num.py` 의 `safe_float` 의미).
- 날짜/시각 포맷은 pydantic 이 내보내던 그대로(ISO-8601; tz 유무는 원본 응답을 실측해 맞춘다).
- 에러 본문 `{"detail": "<문자열>"}`, 검증 오류는 **422** + `{"detail": [{"loc": [...], "msg": "...", "type": "..."}]}`.
  `api.ts::formatError` 가 이 두 형태를 파싱한다.
- 인증: 서버측 세션. 쿠키 이름·속성, Redis `session:{sid}` → user_id, 슬라이딩 TTL 원본과 동일.
  로그인 브루트포스 IP 스로틀, 클라이언트 IP 는 `X-Forwarded-For` **마지막 항목**만 신뢰(원본 §58).
- WebSocket `/ws`: 같은 쿠키로 인증, 실패 시 close code **4401**, 연결 시
  `{"type":"connected","user_id":N}`, 이후 `engine:events:{user_id}` 구독 payload 를 그대로 relay.

## 6. 외부 연동

### 6.1 그대로 옮기는 클라이언트
KIS(REST·WebSocket), Toss 증권, OpenDART, KOFIA, 뉴스 RSS, 텔레그램, S3 호환 백업 업로드.
원본 `services/kis`, `services/broker`, `services/data/*`, `services/news.py`, `worker/tasks.py` 를 기준으로
HTTP 요청·재시도·타임아웃·에러 분류(`services/data/errors.py`)를 동일하게 재현한다. HTTP 클라이언트는 Spring `RestClient`.

### 6.2 pykrx · FinanceDataReader 대체 — 1차는 "같은 요청 재현"
원본이 쓰는 함수: `get_market_ohlcv`(종목별·날짜 단면), `get_index_ohlcv(_by_date)`, `get_index_ticker_list/name`,
`get_market_cap`, `get_market_fundamental`(PER·PBR), `get_market_net_purchases_of_equities`(투자자별 순매수),
`get_market_price_change`, `get_nearest_business_day_in_a_week`, `fdr.DataReader`, `fdr.StockListing`.

- 원본 requirements 에 고정된 버전(**pykrx 1.2.8, finance-datareader 0.9.96**)의 소스를 읽고, 각 함수가 보내는
  **HTTP 요청(KRX 데이터 포털 JSON 엔드포인트·KRX 로그인·네이버 금융)과 후처리(수정주가 여부 포함)**를
  Kotlin 으로 그대로 재현한다. pykrx 종목별 일봉은 기본이 **수정주가**(네이버 경유)임에 주의.
- 이유: 기존 DB 데이터가 이 소스로 채워져 있고, 정답지 대조(§8)가 같은 입력을 전제한다.
- KRX 공식 OPEN API 로의 교체는 **후속 과제**로 남긴다(별도 검증 필요, 공식 API 엔 PER·PBR·투자자별 순매수 없음).

### 6.3 암호·해시 호환
- 증권사 자격증명: 원본은 **Fernet**(`CREDENTIAL_ENC_KEY`). Kotlin 에서 Fernet 토큰 포맷
  (버전 바이트·타임스탬프·IV·AES-128-CBC·HMAC-SHA256, URL-safe base64)을 **바이트 단위로 호환** 구현해
  기존 DB 값을 복호화·재암호화할 수 있어야 한다. 원본 Python 으로 만든 암호문 픽스처로 테스트한다.
- 비밀번호: bcrypt(`$2b$`). Spring Security `BCryptPasswordEncoder` 가 기존 해시를 검증하는지 픽스처로 확인.

## 7. 데이터베이스

- 스키마는 원본 **Alembic head `0017`** 과 동일하게 유지.
- Flyway `V1__baseline.sql` = head 0017 상태 DB 의 **schema-only dump**(timescaledb 확장·hypertable 생성 포함).
  `baselineOnMigrate=true`, `baselineVersion=1` → 기존 DB 는 V1 을 건너뛰고, CI·테스트의 빈 DB 는 V1 으로 전체 생성.
- `alembic_version` 테이블은 컷오버 후 남겨두되 사용하지 않는다(설계자 판단; 삭제는 후속 Flyway 마이그레이션 가능).
- TimescaleDB 는 원본에서 hypertable 파티셔닝에만 쓰인다. 기존 데이터 이전 수고를 피하려 **유지**하고 제거는 후속 선택지로 남긴다.
- JSONB 컬럼(`strategies.config`, `backtests.result`, KIS 마스터 `raw` 등):
  `strategies.config` 는 원본 `schemas/strategy.py`(54KB)의 pydantic 모델로 읽히며, **저장된 행에 필드가 빠져 있으면
  pydantic 기본값이 채워진다.** Kotlin 데이터 클래스도 같은 기본값·검증 규칙을 가져야 한다(숨은 대형 작업 — 별도 태스크로 잡을 것).
  실제 DB 의 기존 config 행들을 픽스처로 뽑아 왕복(읽기→쓰기) 테스트한다.

## 8. 동등성 검증 — 정답지 테스트

### 8.1 방식
1. 원본 Python 을 **한 번** 실행해 픽스처를 만든다: 같은 DB 스냅샷 기준으로
   백테스트 결과 지표·일별 NAV, 팩터 값, DSR, 시그널, 리밸런싱 목표 비중 등 계산 결과와 그 입력.
2. 픽스처(JSON/CSV)는 이 저장소에 **커밋**한다. 픽스처 생성 스크립트도 함께 커밋(원본 저장소 체크아웃 위에서 실행).
3. Kotlin 테스트는 픽스처만 읽는다. **런타임에 Python 불필요.**
4. 허용 오차: 실수값은 상대 1e-9(누적 연산은 1e-6까지 허용, 근거를 테스트에 주석), 정수·순위·종목 선택은 **완전 일치**.

### 8.2 pandas 의미 체크리스트 (빌더 필독)
- 표준편차·분산 `ddof`: pandas 기본 1, numpy 기본 0.
- `rolling(window, min_periods)`: 기본 `min_periods=window`, NaN 처리.
- `ewm(span/alpha, adjust=True 기본)`.
- `rank(method='average' 기본, pct, na_option)` 동점 처리.
- `sum/mean` 의 `skipna=True` 기본, 전부 NaN 이면 sum=0·mean=NaN.
- `ffill(limit)`, `shift`, `pct_change`(기본 fill_method 동작), `resample` 경계(월말/분기말 라벨).
- 날짜 인덱스 정렬·`loc[:d]` 의 **포함** 슬라이스(미래참조 금지 규칙과 직결).
- `float64` 합산 순서 차이로 인한 미세 오차 → 8.1 허용 오차로 흡수.

### 8.3 원본 테스트
원본 pytest(약 90파일)는 **행동 명세**로 취급한다. 각 파일의 케이스를 Kotlin 테스트로 옮긴다
(1:1 코드 번역이 아니라 같은 시나리오·같은 기대값). 원본 conftest 의 픽스처 데이터는 재사용한다.

## 9. 실거래 안전 불변식 (컷오버 수용 기준)

아래 각각에 대해 Kotlin 쪽에 **명시적 테스트**가 있어야 컷오버한다. 상세는 원본 `.claude/rules/trading-engine.md`·`backtest.md`.

1. **백테스트 미래참조 금지** — 날짜 `d` 의 결정은 `d` 이하 데이터만.
2. **PIT(생존편향 제거) 유니버스** 사용.
3. **주문 멱등** — `lock:order:{idempotency_key}` 분산락.
4. **접수불명 주문 재주문 금지** — 잔고 교차확인으로만 회수, 실패 시 critical 알림.
5. **포지션 직렬화** — `lock:position:{user_id}:{symbol}`.
6. halt / MDD kill, 리스크 한도, 리컨실, 연속 실패 알림(`FAILURE_ALERT_THRESHOLD = 3`).
7. 엔진 재기동 시 `engine:active_strategies` 로 복구, heartbeat·러너별 health 키.

## 10. 작업 순서 (빌드는 단계적, 전환은 한 번에)

1. 골격: Gradle 멀티모듈, Flyway V1, JPA 엔티티, Testcontainers, CI.
2. 인증·세션·API 껍데기(에러 형식·직렬화 규칙) + Fernet/bcrypt 호환.
3. 외부 데이터 클라이언트·시장데이터 저장소(§6) + 정답지 픽스처 생성.
4. 백테스트·시그널·팩터·지표(§8 대조) → 해당 REST 엔드포인트.
5. 나머지 REST 엔드포인트·WebSocket.
6. worker(스케줄·백테스트 큐·백업·뉴스·스냅샷).
7. engine(러너·실행·체결·리컨실·리스크·halt) + §9 테스트.
8. frontend 복사, compose·Caddy·CI·e2e-smoke 교체, `.claude/`·docs 갱신.
9. 컷오버: 기존 DB 백업 → Kotlin 스택 기동 → e2e-smoke("가입→로그인→전략목록→백테스트→모니터링") →
   모의투자 1영업일 이상 관찰 → 원본 보관.

## 11. 빌더 재량

- 패키지 구조, 클래스 분할, 테스트 구성의 세부.
- 백테스트 큐의 구체 구현(계약만 지키면 됨).
- 로깅·메트릭 도구 선택.
- 원본에서 발견한 버그: **동작은 보존**하고 `docs/` 에 목록으로 기록(수정은 별도 결정).
