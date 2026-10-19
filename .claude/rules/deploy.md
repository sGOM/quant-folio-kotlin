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
| exec 형식 CMD, engine `stop_grace_period: 60s` | SIGTERM 못 받거나 러너 정리 중 SIGKILL |
| 시크릿은 `/run/secrets/<이름>` → configtree(끝 개행 제거, `SecretsConfigTreeTest`) | Fernet 키 깨짐 → DB 자격증명 전부 복호화 실패 |

## 함정

- **원본 스택이 이 PC 에서 가동 중이다(`quantfolio` 프로젝트).** 로컬 시험은 반드시 다른 프로젝트명·포트로:
  저장소 밖 사본에서 `docker compose -p qf-smoke up`(포트는 `.env` 의 `DB_PORT`·`WEB_PORT`·`APP_PORT` 등).
  `-p quantfolio` 로 띄우면 Kotlin 엔진이 live 전략을 복구해 원본과 **이중 매매**하고 실 DB 를 baseline 한다.
- 저장소 `secrets/` 에 더미 키를 두지 않는다 — 컷오버 때 실제 키 대신 쓰이면 자격증명을 못 푼다.
- 이미지 빌드는 테스트를 안 돈다(Testcontainers 불가). 검증은 CI `./gradlew build` + e2e-smoke.
- frontend 는 원본 무수정 복사(커밋 `8a563702`). 예외: Dockerfile 의 `npm ci`(lockfile 고정).
