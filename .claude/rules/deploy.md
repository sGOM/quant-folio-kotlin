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

- **원본 스택이 이 PC 에서 가동 중이다(`quantfolio` 프로젝트).** 로컬 시험은 반드시 다른 프로젝트명·포트로:
  저장소 밖 사본에서 `docker compose -p qf-smoke up`(포트는 `.env` 의 `DB_PORT`·`WEB_PORT`·`APP_PORT` 등).
  이 PC 의 `.env`(git 무시)는 `COMPOSE_PROJECT_NAME=qf-paper`·`COMPOSE_FILE`(local override `docker-compose.*.local.yml`, git 무시)로 격리 스택을 고정한다.
  `-p quantfolio` 로 띄우면 Kotlin 엔진이 live 전략을 복구해 원본과 **이중 매매**하고 실 DB 를 baseline 한다.
- 저장소 `secrets/` 에 더미 키를 두지 않는다 — 컷오버 때 실제 증권사·외부 API 키 대신 쓰인다.
- 이미지 빌드는 테스트를 안 돈다(Testcontainers 불가). 검증은 CI `./gradlew build` + e2e-smoke.
  e2e-smoke 는 회원가입이 없어 빈 DB 에 소유자(id 1) 행을 SQL 로 시드하고 `TRADING_OWNER_USER_ID=1` 로 맞춘다.
- frontend 는 원본(`8a563702`)에서 시작했으며 의존성 보안 업데이트·주문 정합 알림·단일 소유자 설정 안내를 반영했다. Dockerfile 은 `npm ci`로 lockfile을 따른다.
- Caddy 는 `X-Forwarded-Port` 를 넘겨야 한다(`Caddyfile` 의 `header_up X-Forwarded-Port {http.request.port}`). web(`forward-headers-strategy: native` = Tomcat RemoteIpValve)은 `X-Forwarded-Host` 의 포트를 버리고 Proto 가 있으면 80/443 으로 봐서, `APP_PORT=8080` 같은 비표준 포트에선 같은 출처 POST 가 CORS 403 이 된다(`ProxyHttpsTest`, e2e-smoke 가 8080 으로 지킨다).
- 거래를 켜려면 `.env`에 확인한 소유자의 `TRADING_OWNER_USER_ID`를 넣고 web·engine을 함께 재기동한다(`env_file` 공유). 미설정은 거래 차단이며 원본 DB 사용자나 첫 가입자를 자동으로 선택하지 않는다.
