---
paths:
  - "docker-compose.yml"
  - "backend/Dockerfile"
  - "frontend/Dockerfile"
  - "Caddyfile"
  - ".env.example"
  - ".github/workflows/**"
  - "backend/*/src/main/resources/application.yml"
---

# 배포·스택 (compose·이미지·CI)

## 불변식

| 규칙 | 깨지면 |
|---|---|
| compose `name: quantfolio`·볼륨 `pgdata` 는 원본과 동일 | 컷오버 때 빈 DB 로 기동 |
| Flyway 는 **web 만**(engine·worker `spring.flyway.enabled=false`), 둘은 `web: service_healthy` 뒤에 기동 | 같은 DB 동시 baseline / `ddl-auto: validate` 기동 실패 |
| engine·worker `spring.main.keep-alive: true` | 스레드가 전부 가상(데몬)이라 기동 직후 JVM 종료(스모크에서 실측) |
| 런타임 이미지 `-noble` 고정 → pg_dump 16(빌드 때 검사) | 낮으면 덤프 거부, 17+ 면 `transaction_timeout` 이 들어가 pg16 복원 오류 |
| exec 형식 CMD, engine `stop_grace_period: 60s` > `EngineRuntime.STOP_WAIT_SEC`(전 러너 병렬 대기) | SIGTERM 못 받거나 러너 정리 중 SIGKILL |
| `backend/gradlew` 는 git 실행 비트(100755) | Linux CI·docker build 에서 Permission denied (Windows 체크아웃은 못 느낀다) |
| 시크릿은 `/run/secrets/<이름>` → configtree → `TrimmedSecrets` 가 앞뒤 공백 제거(원본 `strip()`, `SecretsConfigTreeTest`) | 개행이 포함된 API 키로 인증 실패 |
| web `server.forward-headers-strategy: native` | Caddy HTTPS(TLS 종료) 뒤 같은 출처 요청이 CORS 403 — 폰 로그인 불가(`ProxyHttpsTest`) |
| compose 백엔드에 고정 `image:` 태그 금지 | 태그는 전역 — 격리 스모크 빌드가 운영 이미지를 덮어씀 |

## 함정

- **이 PC 의 운영은 로컬 스택 `qf-paper`(복제 DB)다.** 원본 Python 스택(`quantfolio-*`)은 정지(`docker stop`)·볼륨 보존 상태이고 더 쓰지 않는다(2026-11-17 소유자 결정, 원격 배포·오프사이트 백업 없음).
  기동: `docker compose -p qf-paper -f docker-compose.yml -f docker-compose.paper.local.yml up -d`(`.env`(git 무시)의 `COMPOSE_PROJECT_NAME=qf-paper`·`COMPOSE_FILE` 이 자동 적용, 포트 DB 15432·Redis 16379·web 18000·화면 13000).
  시험 스택은 저장소 밖 사본에서 `-p qf-smoke`(포트는 `.env` 의 `DB_PORT`·`WEB_PORT`·`APP_PORT` 등).
  `.env` 없는 곳에서 `-p` 없이 띄우거나 `-p quantfolio`(compose 기본 `name`)를 주면 보존된 원본 볼륨 `quantfolio_pgdata` 를 물고 Flyway 가 원본 DB 를 마이그레이션(V6 은 되돌릴 수 없음)하며 live 전략을 복구한다 — `.env` 의 `COMPOSE_PROJECT_NAME` 은 `-p` 를 생략한 경우만 막는다(우선순위 `-p` > 환경변수 > `name:`). `-p quantfolio` 는 쓰지 않는다.
- Git Bash 에서 `docker exec <c> cat /run/secrets/x` 는 경로가 `C:/Program Files/Git/run/...` 로 바뀐다 — `MSYS_NO_PATHCONV=1` 을 붙인다.
- 저장소 `secrets/` 에 더미 키를 두지 않는다 — 컷오버 때 실제 증권사·외부 API 키 대신 쓰인다.
- 이미지 빌드는 테스트를 안 돈다(Testcontainers 불가). 검증은 CI `./gradlew build` + e2e-smoke.
  e2e-smoke 는 회원가입이 없어 빈 DB 에 소유자(id 1) 행을 SQL 로 시드하고 `TRADING_OWNER_USER_ID=1` 로 맞춘다.
- frontend 는 원본(`8a563702`)에서 시작했으며 의존성 보안 업데이트·주문 정합 알림·단일 소유자 설정 안내를 반영했다. Dockerfile 은 `npm ci`로 lockfile을 따르고 `next build` → `next start`(원본은 소스 마운트 dev 서버)다.
  화면 코드를 고치면 `docker compose build frontend` 가 필요하고, `NEXT_PUBLIC_API_BASE_URL` 은 빌드 인자라 바꿔도 재빌드 전엔 반영되지 않는다.
- **Redis 에 매매 상태(MDD·트레일 고점·`rebalance:last`)가 있다** — compose 는 AOF + 볼륨 `redis_data`. AOF 를 켠 Redis 는 기존 `dump.rdb` 를 무시하고 빈 상태로 뜬다(2026-11-26 실측) — 볼륨을 바꿀 땐 cutover runbook 4단계의 `CONFIG SET appendonly yes` 이관을 거친다.
- db·redis·caddy 이미지는 버전 태그로 고정하고 Testcontainers(`Containers.kt`)도 같은 태그 — `CodeRulesTest` 가 강제. 올릴 땐 `ALTER EXTENSION timescaledb UPDATE` 를 같은 작업에서.
- Caddy 는 `X-Forwarded-Port` 를 넘겨야 한다(`Caddyfile` 의 `header_up X-Forwarded-Port {http.request.port}`). web(`forward-headers-strategy: native` = Tomcat RemoteIpValve)은 `X-Forwarded-Host` 의 포트를 버리고 Proto 가 있으면 80/443 으로 봐서, `APP_PORT=8080` 같은 비표준 포트에선 같은 출처 POST 가 CORS 403 이 된다(`ProxyHttpsTest`, e2e-smoke 가 8080 으로 지킨다).
- 거래를 켜려면 `.env`에 확인한 소유자의 `TRADING_OWNER_USER_ID`를 넣고 web·engine을 함께 재기동한다(`env_file` 공유). 미설정은 거래 차단이며 원본 DB 사용자나 첫 가입자를 자동으로 선택하지 않는다.
