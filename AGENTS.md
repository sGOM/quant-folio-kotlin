# AGENTS.md

국내 주식(KRX) 퀀트 전략 **백테스팅** + 실시간 **자동매매** 플랫폼 QuantFolio 의 Kotlin 백엔드.
Python 원본(`sGOM/quant-folio`, 커밋 `8a563702`)을 이전 중이다 —
설계 [`docs/superpowers/specs/2026-10-19-kotlin-migration-design.md`](docs/superpowers/specs/2026-10-19-kotlin-migration-design.md),
진행 [`docs/superpowers/plans/2026-10-19-00-roadmap.md`](docs/superpowers/plans/2026-10-19-00-roadmap.md).
소유자는 Spring/Kotlin 개발자다. 가독성 우선: 코루틴·리액티브·과도한 추상화 금지(가상 스레드 사용).

## 절대 금지

- **백테스트 미래참조 금지.** 어떤 날 `d` 의 결정은 `d` 이하 데이터만 쓴다.
- **접수불명 주문에 임의 재주문 금지.** 이중 매수가 된다. 잔고 교차확인으로만 회수하고,
  못 밝히면 critical 알림으로 사람에게 넘긴다.

## 지도

`backend/` Gradle 멀티모듈: `core`(도메인·리포지토리·공용) + Boot 앱 `web`(REST·WS) / `engine`(실매매) / `worker`(배치).
세 앱은 서로 직접 호출하지 않고 **Redis**(pub/sub·큐·분산락)로만 통신한다. Next.js 프론트는 API 계약만 본다.
DB 는 PostgreSQL(+TimescaleDB), 스키마는 Flyway, 운영 테이블은 JPA, 시장데이터 대량 처리는 JdbcTemplate.

## 명령

```bash
cd backend && ./gradlew build              # 전체 빌드+테스트 (Testcontainers 라 Docker 필요)
cd backend && ./gradlew :core:test --tests '*Name*'
node .Codex/hooks/md-lint.mjs             # Codex 문서 린트 (CI 와 동일)
```

## 필수 함정

- **거래일은 KST.** 컨테이너 TZ 는 UTC 라 `LocalDate.now()` 는 KST 00~09시에 전날이 나온다.
  날짜는 `MarketClock.todayKst()` 로만 얻는다.
- **Redis 키·채널 문자열은 `RedisKeys` 에만.** 원본 Python 과 문자열이 같아야 컷오버 때 상태가 이어진다.
- **원본 동작 보존이 원칙.** 원본 버그를 발견해도 고치지 말고 기록한다(설계 §11).
- **Python 의미를 null 의미로 옮기지 않는다.** `a or b`·`if not x` 는 빈 문자열·0 도 거짓(`?:` 는 null 만 거른다),
  `d["k"]` 는 없으면 KeyError(`d["k"].toString()` 은 `"null"` 문자열로 통과). 셀프 리뷰 4회차 9건 중 6건이 이 유형.
- 시크릿은 `secrets/*.txt` → `/run/secrets/*` 마운트(configtree). 편집은 훅이 막는다.

## 전역 원칙

- **결측은 0 이 아니라 `null`.** 0 은 실제 값과 구분이 안 된다.
- **실패의 종류를 뭉개지 않는다.** 조회 실패·데이터 없음·소스 미설정·미적재는 다른 사건이다.
- **주석의 근거는 측정 후에.** "안전하다/충분하다" 류 미측정 주장 금지.
- **순서 없는 컬렉션(`HashSet` 등)을 순회해 순서 있는 결과를 만들지 않는다.** 재현성이 깨진다.

## 문서 체계 — 스스로 유지한다

- 영역 지식은 `.Codex/rules/<영역>.md` — `paths` 에 맞는 파일을 만질 때 자동 로드된다. 전부 읽지 말 것.
- **교정 즉시 반영:** 사용자가 교정하거나 같은 실수를 반복하면, 그 교훈을 해당 rules 문서의
  "함정" 절에 한 줄로 추가한다. 모든 작업에 필요한 교훈일 때만 이 파일에 넣는다.
- **무효화 즉시 갱신:** 코드 변경이 문서 서술을 틀리게 만들면 같은 작업에서 고친다.
- 문서를 고치면 md 린트가 자동 실행된다(예산 AGENTS.md ≤80줄·rules ≤150줄, 낡은 `paths`, 깨진 링크, 중복).
- 단계 종료 시 `doc-check` 스킬로 사용량 기반 점검을 한다.
- 이 파일엔 모든 작업에 필요한 것만. 코드를 보면 아는 것(구조·목록)은 어디에도 쓰지 않는다.
