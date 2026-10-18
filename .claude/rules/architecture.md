---
paths:
  - "backend/*.gradle.kts"
  - "backend/*/build.gradle.kts"
  - "backend/*/src/main/resources/*.yml"
  - "backend/core/src/main/kotlin/com/quantfolio/common/**"
  - ".github/workflows/**"
---

# 아키텍처 — 프로세스·통신·설정

## 프로세스

| 앱 | 역할 | 웹 서버 |
|---|---|---|
| `web` | REST `/api/*`, WebSocket `/ws`, 세션 인증 | 8000 |
| `engine` | 전략 러너·주문·체결·리컨실·리스크 | 없음 |
| `worker` | 배치 스케줄(KST)·비동기 백테스트 | 없음 |

엔진을 별도 프로세스로 둔 이유: web 장애·재배포가 실매매를 멈추면 안 된다.
공용 코드는 전부 `core` 에 두고, 앱 모듈엔 그 앱만의 진입점(컨트롤러·러너·스케줄)만 둔다.

## Redis 규약

단일 소스는 `RedisKeys`. 문자열은 원본 Python `app/core/channels.py` 와 동일(컷오버 시 상태 연속).

| 키/채널 | 방향 | 용도 |
|---|---|---|
| `engine:control` | web → engine | `{"action":"start"\|"stop","strategy_id":int}` |
| `engine:active_strategies` (SET) | web/engine | 운용 중 전략 — 엔진 재기동 복구 |
| `engine:events:{user_id}` | engine → web | order·execution·position·signal·alert → WS relay |
| `engine:heartbeat` (TTL) / `engine:health:{id}` | engine → web | 생존·러너별 헬스 |
| `session:{sid}` | web | 세션 → user_id, 슬라이딩 TTL |
| `lock:order:{key}` / `lock:position:{uid}:{sym}` / `reconcile:lock:{order_id}` | engine | 주문 멱등·포지션 직렬화·체결 반영 직렬화 |

`FAILURE_ALERT_THRESHOLD = 3`: 러너 연속 실패 알림 임계이자 web 의 healthy 판정 기준(같은 상수를 써야 한다).

## 설정

- `core/src/main/resources/quantfolio-core.yml` 을 세 앱이 import 한다. 값 우선순위:
  `/run/secrets/<name>` 파일(configtree) > 원본과 같은 이름의 환경변수(.env 호환) > 기본값.
- 부팅 거부(안전측): `KIS_ENV=prod` 인데 `KIS_PROD_APPROVED` 아님, `APP_ENV=prod` 인데 시크릿 누락·`COOKIE_SECURE=false`.
- DB URL 은 `DATABASE_JDBC_URL`(원본 `DATABASE_URL` 은 SQLAlchemy 형식이라 쓰지 않는다).

## 함정

- `@Scheduled` 는 반드시 `zone = "Asia/Seoul"`. 스케줄 시각과 별개로 본문의 날짜도 `MarketClock` 으로.
- 테스트는 Testcontainers(`timescale/timescaledb:latest-pg16`, `redis:7-alpine`) — Docker 가 떠 있어야 한다.
  컨테이너는 `Containers` 싱글턴으로 공유, Spring 테스트는 `@Import(ContainersConfig::class)`.
