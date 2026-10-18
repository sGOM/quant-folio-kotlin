---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/live/**"
  - "backend/core/src/main/kotlin/com/quantfolio/security/**"
---

# 실거래 안전 — 실전 게이트·자격증명

모의투자(vts) 단계지만 실전(prod) 전환을 전제로 만든다. 여기 규칙은 "돈이 나가는 경로"라 원본보다 느슨해지면 안 된다.

## 실전 2단계 승인 (둘 다 유지)

| 층 | 위치 | 동작 |
|---|---|---|
| 부팅 | `QuantfolioProperties.validate()` | `KIS_ENV=prod` 인데 `KIS_PROD_APPROVED` 가 아니면 프로세스가 뜨지 않는다 |
| 주문 | `LiveGate.decide()` | 승인 플래그·체결 정합 등급·1회/일일 금액 상한 — 하나라도 걸리면 거부 |

## LiveGate 판정 규칙

- vts 는 같은 검사를 하되 **로깅만 하고 항상 통과**(`details.logged_failures`). prod 만 차단.
- 체결 정합 **표본 부족은 등급보다 먼저** 차단한다 — "판단 불가"는 통과가 아니라 차단.
- 차단 등급은 `RED` 뿐(AMBER 통과). 금액 상한은 **초과만** 차단(같으면 통과), 일일 상한 0/미설정이면 검사 생략.
- 거부 사유 문구는 원본 그대로(알림·로그 소비처가 있다). 금액은 Python `,.0f`(HALF_EVEN) 형식.
- 입력 조립(최근 90일 체결 정합 리포트·전략별→사용자 공통 리스크 한도·KST 당일 체결 명목 합)은 엔진 쪽 책임.

## 자격증명

- 증권사 키는 `CredentialCipher`(Python 호환 Fernet)로 DB 저장. **`CREDENTIAL_ENC_KEY` 를 바꾸면 기존 암호문을 못 푼다** — 키 교체는 재암호화 절차와 함께.
- 원문·암호문을 응답·로그에 넣지 않는다(응답은 보유 여부 boolean 만).
