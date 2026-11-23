---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/domain/**"
  - "backend/core/src/main/resources/db/migration/**"
---

# 데이터 모델

테이블은 두 덩어리다. 수명주기와 삭제 정책이 다르고 **둘 사이엔 FK 가 없다**(symbol 6자리 문자열로만 느슨히 연결).

| 덩어리 | 성격 | 접근 |
|---|---|---|
| 운영 | 사용자가 만들고 엔진이 채움 — users·strategies·backtests·orders·executions·positions·risk_limits·alerts·news·스냅샷 | JPA 엔티티(`domain/**`) |
| 시장데이터 저장소 | 외부에서 받아 굳힌 과거 데이터 — price_ticks·stock_daily_snapshots·index_ohlcv·dart_financials·external_fetches(stock_period_stats·index_ohlcv_coverage 는 테이블만 남음) | JdbcTemplate(엔티티 없음) |

저장소는 언제든 통째로 지우고 재적재할 수 있어야 하므로 운영 데이터가 거기 묶이면 안 된다.

## 스키마 관리

- Flyway. `V1__baseline.sql` = 원본 Alembic head 0017 재생 DB 의 public 스키마 덤프 + hypertable 2개.
- 기존 운영 DB 는 `baselineOnMigrate` 로 V1 을 건너뛴다. **V1 은 절대 수정하지 않는다** — 변경은 `V2__...` 부터.
- JPA 는 `ddl-auto=validate`. 엔티티가 스키마와 어긋나면 부팅이 실패한다(의도된 안전장치).
- `alembic_version` 테이블은 남아 있지만 쓰지 않는다.

## 삭제 정책 (FK `ondelete` 가 도메인 규칙)

| 관계 | 정책 | 이유 |
|---|---|---|
| users → strategies·orders·positions·risk_limits·alerts | CASCADE | 사용자 소유물 |
| strategies → backtests·risk_limits | CASCADE | 전략 종속 산출물 |
| strategies → orders·executions·positions | **SET NULL** | 전략을 지워도 체결 이력은 감사용으로 남긴다 |

## 매핑 규칙

- FK 는 연관관계 대신 `Int` id 컬럼. 필요할 때 리포지토리로 조회한다(지연 로딩 함정 회피).
- PK 는 DB `integer` 라 `Int`. NUMERIC → `BigDecimal`, timestamptz → `Instant`, date → `LocalDate`,
  JSONB → `@JdbcTypeCode(SqlTypes.JSON)`.
- `createdAt` 은 생성자 기본값 `Instant.now()` — `@CreationTimestamp` 는 명시한 값을 덮어써서 쓰지 않는다.

## 함정

- `orders.strategy_id`·`executions.strategy_id` 는 **null 일 수 있다**(SET NULL). 조회 시 항상 다룬다.
- `orders.reason` 은 주문 근거를 사람이 읽을 한국어로. 새 주문 경로는 반드시 채운다.
- `alerts.user_id = null` 은 전역 알림(배치 장애 등). `code` 로 종류를 식별하고 중복 억제에 쓴다.
- `strategies.config` 는 저장된 행에 필드가 빠져 있을 수 있다. 읽는 쪽에서 원본 Python `.get(키, 기본값)` 의 기본값을 적용한다 — 스키마(pydantic) 기본값과 다를 수 있다(`strategy-config.md`).
- `orders.closed_at` 은 자동 근거 종결과 수동 `resolve_order` 가 같은 조건부 update(`Reconciler.closeIfUnchanged`) 한 문장으로만 쓴다(엔티티 `updatable=false`). "살아 있는 주문"은 status 만이 아니라 `closed_at is null` 로 판정한다 — 종결된 partial 이 있다.
- `orders.order_type` 의 DB(`V1__baseline.sql`)·엔티티(`Trading.kt`) 기본값은 `'limit'` 다. 엔진은 `"market"` 만 쓰고(`OrderExecutor`), `order_type` 을 빼고 넣은 행은 `'limit'` 로 남는다 — 지정가의 근거가 아니다.
- 상태 enum(`OrderStatus`·`AlertSeverity`·`StrategyStatus`)은 소문자 상수 = DB 문자열. JPA 는 변환하지만 **JdbcTemplate 바인딩은 `.name`** — enum 을 그대로 넘기면 실패한다.
