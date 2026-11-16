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

- KIS vts만 모의투자다. 토스는 `KIS_ENV=vts`여도 전략 기동·주문 직전에 실전 게이트를 적용한다. 승인 플래그·상한 설정은 기존 `KIS_PROD_APPROVED`·KIS 설정을 공유한다.
- 체결 정합 **표본 부족은 등급보다 먼저** 차단한다 — "판단 불가"는 통과가 아니라 차단.
- 차단 등급은 `RED` 뿐(AMBER 통과). 금액 상한은 **초과만** 차단(같으면 통과), 일일 상한 0/미설정이면 검사 생략.
- 거부 사유 문구는 원본 그대로(알림·로그 소비처가 있다). 금액은 Python `,.0f`(HALF_EVEN) 형식.
- 입력 조립(최근 90일 체결 정합 리포트·전략별→사용자 공통 리스크 한도·KST 당일 체결 명목 합)은 엔진 쪽 책임.

## 주문·체결 불변식 (엔진)

| 규칙 | 위치 | 깨지면 |
|---|---|---|
| 멱등 키·Redis·UNIQUE + 사용자 행 잠금으로 예약 직렬화. 같은 사용자·종목의 접수불명 주문, 또는 같은 사용자·계좌·전략·종목에 오늘(KST) 낸 주문번호 있는 submitted·partial 이 있으면 다른 신호도 보류 | `OrderExecutor` | 같은 신호 이중 주문 / 체결 미기록 포지션으로 이중 매도·매수 |
| 러너가 판단에 쓴 이 전략·종목 보유수량을 `place`·`execute` 필수 인자(`heldQty`)로 넘기고, 예약 트랜잭션에서 미완료 주문 검사 **뒤에** 읽은 장부와 다르면 보류. 순서는 `Fills.record` 의 상태·포지션 단일 트랜잭션에 기댄다 | `OrderExecutor`·`BaseRunner.place` | 판단 뒤 기록된 체결로 낡은 수량 주문(리밸런싱 이중 매수·초과 매도) |
| 접수 결과는 조회 전에 독립 커밋. 명시적 거절과 주문 POST 전 실패(`OrderNotSent` — 토큰·hashkey 확보, 인터럽트 제외; critical `order_not_sent` 알림)만 rejected, 주문번호 누락·HTTP 5xx/408·주문 POST 의 연결 장애는 pending 유지 + critical | `OrderExecutor`·`beforeOrderPost` | 체결됐을 주문을 거부로 확정 → 장부 어긋남 / 미전송 주문이 접수불명으로 종목 보류 |
| 체결 조회 실패는 미체결(0)로 — 전량 체결로 간주 금지 | `OrderExecutor.resolveFill` | filled 확정 후 정합 대상에서 빠져 영구 오염 |
| 정합은 **증분만** 기록, 접수불명은 주문일 주문 목록의 유일 후보로 주문번호 회수, 아니면 잔고 교차확인(KIS vts)으로만 — 매수는 미기록 체결 배정, 매도는 주문일 뒤 "잔고 == 장부 순보유" 판정만(매도 체결은 추정·기록 금지), 5분 유예. 잔고 조회 실패·단가 불명은 보유 없음·접수불명이 아니라 판단 불가 — 알림·기록 없이 errors 로 센다 | `Reconciler` | 중복 가산 / 전송 중 주문 오판 |
| 정합의 접수불명 대상 = `OrderExecutor` 가 신규 주문을 막는 집합(주문번호 없는 pending·submitted·partial). 네 술어(정합 open·stale, 실행기 unknown·live) 모두 `closed_at is null` 이고 한쪽만 바꾸지 않는다. 미완료 보류(주문번호 있음·당일)는 정합 open 대상의 부분집합이어야 하고, live 의 당일 한정은 유지 — 종결은 근거가 있을 때만이라 소멸 주문도 비종결로 남을 수 있어 풀면 영구 보류 | `Reconciler`·`OrderExecutor` | 일부 회수된 주문이 정합·알림 없이 그 종목 주문만 영구 보류 |
| 주문 예약에 실제 주문 클라이언트의 계좌 키를 기록하고, REST·잔고·실시간 통보는 일치하는 계좌에만 반영. 미확인 기존 주문은 자동 정합 보류 | `OrderExecutor`·`Reconciler`·`FillNoticeApplier` | 계좌가 다른 같은 주문번호를 오인 |
| 등록 계좌 또는 주문 브로커가 바뀌면 KIS 모의 잔고 추정 차단, 반영 직전 DB에서 재확인 | `Reconciler`·Flyway V2 | 이전 장부와 새 계좌 잔고 혼합 |
| KIS 모의 잔고에서 기존 체결의 순보유(매수 − 매도)를 차감하고, 순보유가 음수이거나 잔고 조회 전후 기록 매도가 달라지면 추정 보류. 알려진 구멍: 같은 종목에 미기록 매수·매도가 같은 수량이면 잔고 == 장부라 구분 못 함 | `Reconciler` | 매도 이력 이후 신규 체결 누락 / 근거 없는 매수 기록 |
| KIS 체결 조회는 주문 생성 시각의 KST 날짜 하루로 한정하고, 응답에 주문일이 있으면 일치하는 행만 사용. 단건 조회 TR 은 `TTC0081R`+`ODNO`+`EXCG_ID_DVSN_CD=ALL` — 원본 `TTC8001R` 은 모의에서 체결된 주문도 `rt_cd=0` 행 0개(2026-11-16 실측, 실전 미측정) | `KisClient` | 자정 이후 체결 누락 / 다른 날짜의 같은 번호 오인 |
| KIS 잔고·주문일 주문 목록은 연속조회로 끝까지 받는다(잔고 최대 10페이지). 상한 초과·규약 위반·완결 미확인은 부분 결과가 아니라 조회 실패(`KisError`) — 주문 목록은 마지막 `tr_cont` D/E 가 아니면(헤더 없음 포함) 실패, "없음"이 근거로 쓰여서 | `KisClient` | 일부만 보이는 잔고·목록을 보유 없음·주문 없음으로 오독 |
| 주문 종결(`closed_at`): 주문일(KST)이 지난 미완료 주문을 확실한 근거로만 종결(체결 0 → cancelled, 일부 → partial 유지). 근거 = ① 원주문 목록 행 하나·총 체결 == 기록 ② 주문일 뒤 체결조회 누적 > 0 == 기록 ③ 종료 상태(`Fill.terminal`)의 누적 == 기록(0 포함) ④ KIS 모의 잔고 — 매수는 "더 배정할 수량 없음", 매도는 잔고 == 장부 순보유 ⑤ 접수불명만: 완결 목록에 증인(같은 사용자·계좌·주문일의 다른 주문에 붙은 번호의 원주문 행)이 있고 같은 종목·방향의 주인 없는 행(번호·원주문번호가 어떤 주문에도 안 붙음)이 없음 — 잔고가 체결을 배정했거나 판단 불가인 pass 는 제외. 조회 실패·목록 미완결·빈 목록·증인 없는 목록·종료 상태 아닌 체결 0 은 근거 아님. 목록·종료 상태 누적 > 기록이면 증분 반영 뒤 종결, < 기록이면 기록을 줄이지 않고 critical `order_fill_mismatch`. 락 안 조건부 update 한 문장(executions·포지션·`qty` 불변; 매도 잔고 근거는 사용자 행 잠금 아래 계좌 재확인 뒤), 접수불명 종결은 warning `order_closed_unconfirmed`(근거를 문구로 말함). 목록 행·정정 체결·종료 상태가 있으면 잔고로 체결 추정 금지. 종결은 주문일이 지난 주문만이고 체결통보 매칭(`FillNoticeApplier`)은 오늘(KST) 주문만이라 종결된 주문은 통보가 정합을 트리거하지 않는다 — 종결을 당일로 넓히면 이 전제가 깨진다 | `Reconciler`·Flyway V5 | 체결 누락이 확정 / 기록 감소 / 종결 주문이 계속 보류·조회 |
| 주문번호 회수: 주문일 목록의 원주문 행(`originalOrderNo == null`)만 후보 — 정정·취소 행 제외. 종목·방향·수량 같고 주문 시각이 행 생성 −2초~+120초, 같은 사용자·계좌·주문일의 다른 주문에 안 붙은 번호가 **정확히 하나**일 때만 조건부 update(pending → submitted), 그 pass 끝, warning `order_number_recovered`. 0·복수·미지원·조회 실패면 잔고 교차확인. 가격 조건은 없다 — 엔진은 시장가만 내고(`OrderExecutor`) `orders.price` 는 신호가라 증권사 주문단가와 못 맞추며, `orders.order_type` 기본값 'limit' 는 지정가의 근거가 아니다. 회수·종결 경로에서 주문 전송 없음 | `Reconciler` | 남의 체결을 오기록 / 이중 매수 |
| 원주문번호 사슬(문자열 일치만, 정정의 정정 포함)로 이어진 정정·취소 행의 체결 합 > 0 이면 자동 기록·종결 없이 critical `order_modified_externally`(6시간 dedup)를 내고 errors 로 센다 — 그 pass 에는 원주문번호의 근거도 반영하지 않는다 | `Reconciler` | 정정 뒤 체결이 원주문 행에 안 잡혀 체결 누락을 종결로 확정 |
| 주문일이 지난 주문이 한 pass 에서 진전(종결·회수·체결 기록·상태 보정) 없이 끝나면 메모리 맵으로 3600초(`PAST_DAY_RECHECK_SECONDS`, 측정값 아님) 뒤까지 증권사 조회를 건너뛴다(락 바쁨·오늘 주문 제외, 재시작하면 비어 한 번 더 확인). 재확인에도 미해결인 주문번호 있는 주문은 관측 사실만 담은 critical `order_unresolved`(24시간 dedup, 측정값 아님; 더 구체적인 critical 을 낸 pass 는 생략). 사람의 정리 수단은 엔진 제어 메시지 `resolve_order`(아래 행) | `Reconciler` | 종결 불가 주문의 매 주기 증권사 조회(유량제한 공유) / 조용히 남는 주문 |
| 수동 정리 `resolve_order`(`{"action","order_id","symbol","filled_qty","avg_price"}`): 사람이 증권사에서 확인한 최종 누적 체결로 장부 반영·종결. 정합 락 안에서 주문을 다시 읽어 모두 만족할 때만 — 종목 일치(`symbol` 은 JSON 문자열, 다르면 거부), 미종결·pending/submitted/partial, **주문일(KST) 경과**(오늘 주문 거부), 0 ≤ 누적 ≤ 주문수량, 누적 ≥ 기록(기록을 줄이지 않는다 — `order_fill_mismatch` 는 이 경로로 못 푼다), 증분 > 0 이면 평균가 > 0·주문 계좌 키 있음·증분 단가(`incrementalPrice`, 자동 정합과 같은 산식) > 0. 이미 전량 기록된 미완료 주문은 거부(상태만 바꾸지 않는다), 락이 바쁘면 거부. 반영은 한 트랜잭션(증분 `Fills.record` + 누적 < 주문수량이면 `closeIfUnchanged`, 종결이 실패하면 증분까지 롤백). 증권사 조회·주문 전송·계좌 대조 없음, 엔진은 입력값을 검증하지 않아 틀린 값의 되돌림은 DB 직접 수정뿐. 알림 warning `order_resolved_manually`·`order_resolve_rejected`(사유), 형식 오류·없는 주문은 로그 경고만 | `Reconciler.resolveManually`·`EngineRuntime.resolveOrder` | 근거 없는 종결 / 기록 감소 / 증분만 기록되고 종결 안 된 반쪽 반영 / 다른 주문 정리 |
| KIS 주문 목록은 정정·취소 행(`orgn_odno`)을 버리지 않고 `BrokerOrderRow.originalOrderNo`(trim 원문, 정규화 없음)로 표시해 돌려준다. KIS 모의 실측(2026-11-16): 원주문 행 `orgn_odno="0000000000"`, 정정·취소 행은 원주문 `odno` 와 같은 10자리 표기, 취소 행은 `ord_qty`=취소 수량·`tot_ccld_qty`=0·`cncl_yn=Y`, 부분 체결 뒤 잔량 취소된 원주문 행은 `ord_qty` 그대로·`rmn_qty=0`. 토스 `Fill.terminal` 은 `OrderStatus` 가 FILLED·CANCELED·REJECTED 이고 `filledQuantity` 를 0 이상 정수로 읽었을 때만 true(REPLACED·`*_REJECTED`·진행 중·모르는 값은 false), KIS 는 항상 false | `KisClient`·`TossClient` | 정정 체결 누락 / 진행 중 주문을 종료로 오독해 종결 |
| 체결통보는 `CNTG_YN`=2 이고 `RFUS_YN`≠1 일 때만 체결 — 접수·정정·취소·거부 통보의 `CNTG_QTY` 는 주문수량. 판별 불가(빈 값)도 체결 아님. 모의 실측(23필드): 체결 통보의 `CNTG_QTY` 는 **체결 건별**(누적 아님), 체결 고유번호 없음, 같은 초·같은 수량·단가의 별개 체결이 연달아 옴, 부분 취소 뒤 `ODER_QTY` 는 줄어든 수량 | `KisFrames.parseFillNotice` | 접수 즉시 전량 체결·거부 주문의 포지션 기록 |
| 체결통보 수량·단가는 장부에 쓰지 않는다 — 매칭(오늘 KST·사용자·계좌 키·주문번호·종목)된 주문 하나의 정합(`Reconciler.reconcileOrder`, 주기 정합과 같은 `processOne`·open 술어)만 트리거해 단건 조회 누적으로 증분 기록. 매칭 실패 시 같은 사용자·계좌·종목·방향의 오늘 번호 없는 최근 pending 이 있을 때만 250ms×20회 재탐색(POST 응답보다 먼저 온 시장가). 통보 직후 조회가 그 체결을 아직 안 보이면 60초 주기 정합이 잡는다(조회 지연 미측정) | `FillNoticeApplier` | 건별 통보를 누적으로 오독해 체결 누락 / 정합이 반영한 체결의 늦은 통보를 더해 이중 기록 |
| REST 누적 평균가 × 누적 수량에서 해당 주문의 기기록 금액을 빼 증분 단가 계산. 장부 정밀도로 계산한 증분 단가가 0 이하면 반영 보류 | `Reconciler` | 부분 체결 평단·매도대금 왜곡 |
| executor·reconcile(주기·통보 트리거)은 반영 시 같은 주문 락. 증권사 조회는 락 밖, 락 안에서 주문 행·기기록 수량을 다시 읽어 증분만 반영. 락이 바쁘면 정합은 기다리지 않고 넘긴다(다음 주기). 정합의 상태 보정은 조회 전에 읽은 상태일 때만 갱신하는 조건부 update 로만 | `Reconciler`·`OrderExecutor` | 같은 체결 이중 기록 |
| Redis 락 해제는 소유자 토큰 확인. 락 TTL: 주문 92초·포지션 125초만 락 안 증권사 HTTP 의 코드 상한 합에서 유도(산식은 `RedisKeys`), 정합 락은 안이 DB 반영만이라 원본 30초. 본문 수신·같은 계좌 키 throttle 대기·DB 잠금·풀·Redis 대기는 상한이 없어 그래도 만료될 수 있다 — 락만 믿지 말고 DB 방어선(UNIQUE·잠금 뒤 기록 수량 재확인)을 둔다 | `withLockOrElse` | 만료 뒤 남의 락 삭제 → 제3 경로 동시 진입 |
| 서로 다른 주문이 같은 사용자·계좌·전략·종목의 포지션을 갱신하면 DB 트랜잭션 잠금으로 커밋까지 직렬화(첫 생성 포함), 잠금 뒤 체결 수량 재확인 | `Fills.record` | 보유수량·평단 갱신 유실 |
| 포지션 조회·리스크·화면 잔고 대체 조회는 활성 주문 계좌에 한정. 계좌 미상 포지션은 보존하되 사용하지 않는다 | `Fills`·러너·`RiskManager`·`TradingController` | 이전 계좌 수량·평단으로 주문 |
| 0 이하 현재가는 "시세 없음" — 손절·익절 판정에 쓰지 않는다 | 러너·`RiskManager` | −100% 로 오독해 전량 청산 |
| 주문 순서: 실전 게이트(KIS prod·토스) → 정지/CB 게이트 → 실행기 | `BaseRunner.place` | 승인 없는 실전 주문 / CB 중 시장가 |
| 타임아웃·종료로 버려진 틱은 주문하지 않는다(표식 검사 — 인터럽트는 `catch (e: Exception)` 가 삼킨다) | `BaseRunner.tickAborted` | 겹친 틱 이중 배분 / 정지 후 주문 |
| start·stop·피드 동기화는 직렬(`@Synchronized`) — 제어 메시지는 각자 다른 스레드로 온다(`resolve_order` 는 `@Synchronized` 가 아니라 주문 정합 락으로 직렬화) | `EngineRuntime`·`KisRealtime` | 정지한 전략이 계속 매매 / 추적 안 되는 WS 리스너 |

- 테스트는 가짜 증권사만. 엔진 테스트는 `quantfolio.engine.autostart=false`(test `config/application.yml`) — 켜면 다른 테스트의 live 전략을 복구해 주문을 낸다. `todayOrderTime()` 을 쓰는 정합 테스트는 KST 00:00~00:05:30 에 사유와 함께 skip 으로 표시된다(실패 아님).

## 자격증명

- 증권사 키는 `CredentialCipher`(Python 호환 Fernet)로 DB 저장. **`CREDENTIAL_ENC_KEY` 를 바꾸면 기존 암호문을 못 푼다** — 키 교체는 재암호화 절차와 함께.
- 원문·암호문을 응답·로그에 넣지 않는다(응답은 보유 여부 boolean 만).
