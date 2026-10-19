# 8단계 통합(스택·CI) Implementation Plan

**Goal:** 원본 frontend 를 무수정 복사하고, compose·Caddy·CI·e2e-smoke 를 Kotlin 백엔드로 교체해 전체 스택이 원본과 같은 방식으로 뜨게 한다.
**Spec:** 설계 §4(저장소 구조)·§10-8 · `.claude/rules/deploy.md`.

## Tasks
| # | 범위 | 결과 |
|---|---|---|
| 8a | frontend 복사 | `git archive 8a563702 frontend` — 키 유사 문자열 스캔 깨끗. Dockerfile 만 `npm ci` — **완료** |
| 8b | 이미지 | `backend/Dockerfile` 다단계, 세 jar 한 이미지, noble(pg_dump 16)·curl — **완료** |
| 8c | compose·Caddy·secrets | 프로젝트명·볼륨 원본 동일, configtree 시크릿, web 만 Flyway, keep-alive — **완료** |
| 8d | CI | `ci.yml` frontend job, `e2e-smoke.yml`(시크릿 13개, Flyway 대체) — **완료** |
| 8e | 격리 스모크 | `-p qf-smoke`·별도 포트로 전체 스택 기동 → Playwright 스모크 통과, 엔진 heartbeat·SIGTERM(143) 정리·워커 pg_dump 확인 — **완료** |

## 스모크가 잡은 결함
- engine·worker 기동 직후 종료(가상 스레드뿐 → `spring.main.keep-alive`).
- 기본 temurin 이미지(26.04)의 pg_dump 18 → noble 고정.
- 원본 frontend Dockerfile 의 `npm install` 실패.
