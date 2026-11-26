---
paths:
  - "backend/worker/**"
  - "backend/core/src/main/kotlin/com/quantfolio/alert/**"
  - "backend/core/src/main/kotlin/com/quantfolio/news/**"
---

# 워커(배치)·알림

원본 Celery beat 9개(시장데이터 적재는 공식 경로 잡으로 교체 — `docs/migration-notes.md`) → `WorkerSchedule`(`@Scheduled`, zone `Asia/Seoul`) + 작업 본체 `WorkerJobs`·`DbBackup`.
본체는 결과 맵을 돌려주고 스케줄 층은 로그만 — 테스트는 본체를 직접 부른다(`quantfolio.scheduling.enabled=false`).

## 실패는 알림으로 넘긴다

- 사람이 알아야 하는 실패는 `AlertPublisher.publish` — DB(알림함) + 사용자 채널(WS). 외부 푸시 채널은 없다(`docs/migration-notes.md` 의도적 차이).
  DB 적재 실패만 삼키고(로그) 발행으로 넘어간다. Redis 발행 실패는 감싸지 않아 `publish` 호출자로 전파된다(원본과 동일, 보존 — `docs/migration-notes.md` 결함 #18).
- DB 적재는 **자체 트랜잭션(REQUIRES_NEW)** — 호출자(체결 기록 등) 롤백에 알림이 휩쓸리지도, 알림 실패가 체결을 롤백시키지도 않는다.
- 상태형 경보(매 주기 재발하는 것)는 **dedup 창**을 준다. 값은 원본 그대로(20h·24h·6h) — 바꾸면 알림 피로/누락이 생긴다.
- 전역 알림은 `user_id = null`, `strategy_id = 0`. `alerts.user_id` 는 users FK 라 임의 id 로는 적재가 실패한다.
- 이벤트 페이로드는 원본 `json.dumps` 문자열 그대로(`pyJsonDumps`, ensure_ascii) — 엔진·프론트가 같은 채널을 공유.

## 함정

- **날짜:** 전부 KST(`MarketClock.todayKst`). 원본 `date.today()`(컨테이너 UTC) 보존분은 2026-11-26 에 걷어냈다(migration-notes #1·2·6~8).
- 백업은 워커 이미지에 `pg_dump`(postgresql-client)가 있어야 한다. 비밀번호는 `PGPASSWORD` 로만.
- 백업 신선도 감시(`BackupFreshness`)는 **엔진** 루프에서 KST 9시대에 돈다 — 워커를 감시하는 일을 워커 안에 두지 않는다.
- 뉴스 XML 은 DOCTYPE 금지(XXE). 원본 의미: XML 이 엔티티를 풀고 HTML unescape 가 한 번 더 — "고치지" 말 것.
- **공식 일일 적재는 30분마다 반복**(KRX Open API 공개 시각 미실측). 멱등은 원장에 맡긴다 — "목표일 끝났으면 skip" 을
  넣지 말 것. 최근 미확정 날(휴장 0행)을 7일 확정 전까지 매번 다시 받아야 잠정 휴장 판정이 성립한다(`LocalTradingDays`).
- **OpenDART 일 한도(20,000, 미실측)를 나눠 쓴다:** 05:00 `backfillDart` 예산 10,000 + 웹 원격 조회. 수동 `quantfolio.dart-backfill.from-year`
  를 같은 날 돌리면 한도를 다 쓰고, 설정을 남겨 두면 재기동마다 또 쓴다 — 끝나면 지운다. 겹침은 `RedisKeys.DART_BACKFILL_LOCK`.
