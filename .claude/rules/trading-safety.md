---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/live/**"
  - "backend/core/src/main/kotlin/com/quantfolio/security/**"
  - "backend/core/src/main/kotlin/com/quantfolio/broker/**"
  - "backend/engine/**"
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

## 주문·체결 불변식 (엔진)

| 규칙 | 위치 | 깨지면 |
|---|---|---|
| 멱등 3중 방어: 결정적 키 + Redis 락 + UNIQUE | `OrderExecutor` | 같은 신호 이중 주문 |
| 전송 결과 3갈래: 성공→submitted, `BrokerError`→rejected, **그 밖의 예외→pending 유지 + critical, 재전파** | `OrderExecutor` | 체결됐을 주문을 거부로 확정 → 장부 어긋남 |
| 체결 조회 실패는 미체결(0)로 — 전량 체결로 간주 금지 | `OrderExecutor.resolveFill` | filled 확정 후 정합 대상에서 빠져 영구 오염 |
| 정합은 **증분만** 기록, 접수불명은 잔고 교차확인(모의·매수)으로만, 5분 유예 | `Reconciler` | 중복 가산 / 전송 중 주문 오판 |
| reconcile·체결통보는 **같은 주문 락 키**(`reconcileLock`), executor 는 멱등 락(`lock:order:{key}`) | `Reconciler`·`FillNoticeApplier` | 같은 체결 이중 기록 |
| 0 이하 현재가는 "시세 없음" — 손절·익절 판정에 쓰지 않는다 | 러너·`RiskManager` | −100% 로 오독해 전량 청산 |
| 주문 순서: 실전 게이트(prod) → 정지/CB 게이트 → 실행기 | `BaseRunner.place` | 승인 없는 실전 주문 / CB 중 시장가 |
| 타임아웃·종료로 버려진 틱은 주문하지 않는다(표식 검사 — 인터럽트는 `catch (e: Exception)` 가 삼킨다) | `BaseRunner.tickAborted` | 겹친 틱 이중 배분 / 정지 후 주문 |
| start·stop·피드 동기화는 직렬(`@Synchronized`) — 제어 메시지는 각자 다른 스레드로 온다 | `EngineRuntime`·`KisRealtime` | 정지한 전략이 계속 매매 / 추적 안 되는 WS 리스너 |

- 테스트는 가짜 증권사만. 엔진 테스트는 `quantfolio.engine.autostart=false`(test `config/application.yml`) — 켜면 다른 테스트의 live 전략을 복구해 주문을 낸다.

## 자격증명

- 증권사 키는 `CredentialCipher`(Python 호환 Fernet)로 DB 저장. **`CREDENTIAL_ENC_KEY` 를 바꾸면 기존 암호문을 못 푼다** — 키 교체는 재암호화 절차와 함께.
- 원문·암호문을 응답·로그에 넣지 않는다(응답은 보유 여부 boolean 만).
