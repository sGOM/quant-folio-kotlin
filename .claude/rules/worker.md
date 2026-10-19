---
paths:
  - "backend/worker/**"
  - "backend/core/src/main/kotlin/com/quantfolio/alert/**"
  - "backend/core/src/main/kotlin/com/quantfolio/news/**"
---

# 워커(배치)·알림

원본 Celery beat 9개 → `WorkerSchedule`(`@Scheduled`, zone `Asia/Seoul`) + 작업 본체 `WorkerJobs`·`DbBackup`.
본체는 결과 맵을 돌려주고 스케줄 층은 로그만 — 테스트는 본체를 직접 부른다(`quantfolio.scheduling.enabled=false`).

## 실패는 알림으로 넘긴다

- 사람이 알아야 하는 실패는 `AlertPublisher.publish` — DB(알림함) + 사용자 채널(WS) + critical 은 텔레그램.
  세 경로는 서로를 막지 않는다(한쪽 실패는 로그만).
- 상태형 경보(매 주기 재발하는 것)는 **dedup 창**을 준다. 값은 원본 그대로(20h·24h·6h) — 바꾸면 알림 피로/누락이 생긴다.
- 전역 알림은 `user_id = null`, `strategy_id = 0`. `alerts.user_id` 는 users FK 라 임의 id 로는 적재가 실패한다.
- 이벤트 페이로드는 원본 `json.dumps` 문자열 그대로(`pyJsonDumps`, ensure_ascii) — 엔진·프론트가 같은 채널을 공유.

## 함정

- **날짜:** 원본이 `date.today()`(컨테이너 UTC)를 쓴 작업(일봉 적재·체결 정합 점검)은 UTC 그대로 보존. 스냅샷 선적재만 KST 전날.
- 백업은 워커 이미지에 `pg_dump`(postgresql-client)가 있어야 한다. 비밀번호는 `PGPASSWORD` 로만.
- 뉴스 XML 은 DOCTYPE 금지(XXE). 원본 의미: XML 이 엔티티를 풀고 HTML unescape 가 한 번 더 — "고치지" 말 것.
- 일봉 보충 실패는 `ensureCoverage` 가 삼킨다(원본 동일) — 실패율 알림은 DB 계열 오류에서만 발화한다.
