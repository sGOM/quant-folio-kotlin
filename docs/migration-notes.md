# 마이그레이션 노트 — 원본 동작 보존 기록

설계 §11 원칙: 원본에서 발견한 버그·의심 동작은 **동작을 보존**하고 여기 기록한다. 수정은 컷오버 이후 별도 결정.

| # | 위치(Kotlin) | 원본 | 내용 | 영향 |
|---|---|---|---|---|
| 1 | `DartFinancialStore.read` | `store/dart_store.py::_select` | 확정 판정이 `date.today()`(컨테이너 UTC) 기준. 다른 곳은 KST(`now_kst`). | KST 00~09시에 하루 늦게 확정 — 안전측(재조회 1회 더) |
| 2 | `DataSourceErrors.secondsUntilMidnight` | `errors.py` | 프로세스 로컬 시각 기준 자정(UTC 컨테이너면 KST 09시). DART 한도는 KST 자정 리셋. | 쿨다운이 길어지거나 짧아짐 — 원본 주석이 수용한 오차 |
| 3 | `KrxMdc.pykrx` | pykrx `KrxWebIo.read` | 730일 초과 조회에서 두 번째 조각부터 항상 `result["output"]` 에 이어붙여, `OutBlock_1` 응답(MDCSTAT01602 기간등락률)은 KeyError 로 실패 | 2년 초과 기간등락률 조회 불가(Unavailable) |
| 4 | `KrxStockApi` int32 컬럼 | pykrx `astype(np.int32)` | 시가·종가·거래량 등 int32 컬럼이 2^31 을 넘으면 numpy OverflowError 로 그 시장 조회 전체가 실패 | 거래량 21억 주 초과 종목이 있는 날 해당 시장 데이터 누락(Unavailable) |
| 5 | `TrackingController` | `routes/tracking.py` + `tracking.reconstruct_realized_curve` | 가격 패널 인덱스(tz-aware UTC)와 체결일(tz-naive KST 날짜)을 한 집합에 섞어 정렬 → 체결 종목에 시세가 하나라도 있으면 `TypeError` 로 500(원본 컨테이너에서 재현 확인). **크래시는 보존 대상이 아니라고 판정** — 의도대로 날짜끼리 비교해 정상 응답 | 원본에선 사실상 항상 실패하던 화면이 동작. 원본 결과와 비교할 기준값이 없다 |

## 의도적 차이 (동작 동등, 표현만 다름)

- 422 검증 오류 `msg` 는 pydantic 영문 대신 한국어/Bean Validation 메시지.
- `representative()` 메시지의 원인별 건수 표기 형식(`{A=1}` vs `{'A': 1}`).
- 성과귀속 롱숏(`Attribution.factorAttribution` 의 `ls_return`): 원본 `pair.sort_values(ascending=False)` 는 numpy quicksort 라
  **동점 순서가 구현(CPU SIMD 경로)마다 다르다.** Kotlin 은 안정 정렬. `score` 가 소수 4자리 반올림이라 실데이터에서
  상·하위 1/3 경계에 동점이 걸리면 값이 갈린다(2층 정답지에서 1건 관측). IC(평균순위)·매매 결정(안정 정렬 `list.sort`)은 무관.
- 스크리너 라우트 캐시: 원본은 Redis 조회 실패 시 500, Kotlin 은 다른 지표 라우트처럼 캐시 미스로 보고 계산(`JsonCache`). Redis 장애 때만 다르다.
- `/ws` 인증 실패: 원본은 accept 전에 close(4401) 해 uvicorn 이 핸드셰이크 403 으로 응답 → 브라우저엔 1006 이라 프론트의 4401(로그인 이동) 분기가 동작하지 않고 재연결을 반복했다. Kotlin 은 accept 후 4401 로 닫아 프론트 계약대로 동작한다.
- 뉴스 HTML 엔티티: 원본 `html.unescape` 는 HTML5 이름 엔티티 2,231개를 모두 푼다. Kotlin `NewsFeeds.htmlUnescape` 는 숫자 엔티티 전부 + 뉴스에 쓰이는 이름 44개만 — 표에 없는 드문 이름 엔티티는 원문 그대로 남는다(요약 표기만 다름).
- 뉴스 전체 실패 알림 문구의 확인 위치: 원본 `app/services/news.py::FEEDS` → `NewsIngestor.FEEDS`(이전 후 실제 위치).
- 워커 DB 대기(`wait_for_db`)는 옮기지 않았다 — Spring 은 기동 시 Flyway 가 DB 를 요구하고 실패하면 컨테이너 재시작 정책으로 재기동된다. 원본처럼 "DB 없이 기동"하지 않는다.
