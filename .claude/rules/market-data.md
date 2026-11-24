---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/market/**"
---

# 시장데이터 — 외부 소스·로컬 저장소

## 실패는 값이 아니라 예외다

외부 소스(KRX·DART·data.go.kr·KOFIA·KIS) 실패는 반드시 `DataSourceError` 하위로 던진다. 빈 리스트로 삼키면
백테스트가 빈 패널 위에서 '성공'하며 무의미한 수치를 낸다(원본 §44-1 실제 사고).

| 원인 | 의미 |
|---|---|
| `SourceAuthError` | 인증·차단 |
| `SourceQuotaError` | 한도 초과(`retryAfterSeconds` 로 리셋 시각) |
| `SourceUnavailableError` | 네트워크·5xx·점검 |
| `SourceSchemaError` | 응답 형식이 파서와 다름 — 코드 수정 필요 |
| `SourceRequestError` | 우리가 잘못 보냄 — 버그 |

- 종목·시장별 루프는 실패를 모으고 `Cooldowns.stopAggregate()` 로 단락, 전량 실패면 `DataSourceErrors.representative()` 를 던진다.
  일부만 실패하면 성공분을 쓰되 **확정으로 굳히지 않는다**(다음 호출에서 보완).
- 쿨다운 검사는 요청을 보내기 전 **맨 앞에서** 한다(원본은 세션 재사용 분기가 앞서 차단 중에도 요청이 나갔다, §44-1).

**`MarketDataNotLoaded` 는 `DataSourceError` 가 아니다** — 외부 실패가 아니라 "로컬에 아직 없음"(`market/official/`). 전략 경로는 원격으로 내려가지 않고
이를 던지고, 호출부는 삼키지 않는다(백테스트 409, 라이브 엔진은 주문 없이 멈춤+critical). `catch (Exception)` 이 이를 `null`·빈 값으로 바꾸는 게 가장 흔한 사고다.

## 로컬 우선 4상태 (원장 `FetchLedger` — `DartFinancials`·`OfficialIngest`)

확정 기록 있음 → 로컬 / 기록 없음·미확정 → 원격 1회 + 저장 + 원장 / 원격 실패 → 예외 전파 / 소스 미설정 → 진입 전 처리.

- **0행은 절대 확정 금지.** "진짜 무자료"와 "스키마 변경으로 값 잃음"을 구분할 수 없고, 굳으면 수동 삭제 전까지 영구 0행(§47·§49).
  유일한 예외: KRX Open API 적재(`OfficialIngest`)의 휴장일 — 같은 날 지수·주식 5개 호출이 모두 0행이고 그날이 7일 이상 지났을 때만 `final` 로 굳힌다(7일 이내 0행은 미확정, 재조회).
- 새 적재 경로를 만들면: 요청 컬럼 중 **전부 null 인 컬럼**이 있으면 확정 보류(포맷 변경으로 변환이 통째로 실패한 신호).

## 저장소 테이블

- 리포지토리는 테이블별 타입 행(`StockDaily` 등, 숫자 `Double?`)을 주고받는다. upsert 는 모든 컬럼
  `COALESCE(EXCLUDED, 기존)` — 들어온 null 은 기존값 보존(여러 소스가 한 행을 나눠 채운다).
- 저장 시 같은 종목 중복은 마지막 값, NaN/±Inf → null.
- 시장 혼재 테이블(`stock_daily_snapshots`)의 단일시장 조회는 반드시 `markets` 필터.
- 운영 테이블과 FK 없음 — 저장소는 통째로 지우고 재적재할 수 있어야 한다.

## 공식 경로 (`market/official/`·`market/krxopen/`) — 전략 경로의 단일 출처

시장데이터는 전부 로컬 테이블만 읽는다. 새 전략·UI 계산 경로 파일은 `CodeRulesTest` 의 목록에 추가한다(`@Qualifier("local")`·catch-all 의 MDNL 재던짐을 검사).

- **업종지수 키:** 접두어 키(`KOSPI:<이름>`·`KOSDAQ:<이름>`)만 섹터 화면 후보이고, 첫 행 이전 비결측 취급도 접두어 키만(대표지수 `1001` 등은 제외).
- **원장 키:** 쓰는 쪽(`OfficialIngest`·`DividendIngest`)과 읽는 쪽(`LocalTradingDays` 등)의 문자열이 같아야 한다.
- **거래일 = 원장 `krx_open|stk_bydd_trd|날짜|KOSPI`**: 행>0 거래일, 0행·final 휴장, 그 외 모름(=결측). `index_ohlcv` 는 업종지수 등
  전 행을 담으므로 달력 근거로 쓰지 않는다. 0행·미확정이라도 5호출 모두 0행이고 뒷날(행>0) 기록 **이후에** 받은 것이면 잠정 휴장 —
  공개 전에 받은 0행과 구분하려는 것이다. 연휴 다음 날 아침엔 뒷날 기록이 없어 이게 성립하지 않으므로, 설정 `quantfolio.market.krx-holidays`
  (`KrxHolidays`, 매년 KRX 공지로 사람이 추가)에 있는 날의 0행은 바로 휴장이다 — 행이 있거나 기록이 없으면 목록보다 원장. 직전 거래일은 `LocalTradingDays.previous` 한 곳(적재의 비교용 직전일만 예외, `previousForAppend`).
- **Valuation `div=0.0` 규칙:** 전체 배당 적재가 있고 창(공시일 ≤ asOf, 기준일이 365일 안) 안에 행이 없으면 0.0, 적재가 없거나 배당 이력 시작+365일 이전이면 `null`. 0 과 결측을 섞지 않는다.
- 배당 금액은 기준일 당시 주식 단위(분할 전 값)다 — `PriceUnits`(수정/원시 종가 비)로 환산: 백테스트 현금은 배당락일 비, `Valuation` DIV 는 배당락일 비 ÷ asOf 비(환산 못 하면 null). 배당락일은 "기준일 이하 마지막 거래일의 직전 거래일".
- 거래정지일 봉(거래량 0·시고저 0)은 버리지 말고 o=h=l=종가로 — 버리면 커버리지 결측, 그대로 두면 ATR 등 오염.
- 휴장일과 **아직 공개 안 된 날**은 둘 다 HTTP 200·빈 `OutBlock_1` 이다(2026-11-23 실측). 공개는 T+1(시각 관측 중). 필드명·지수명은 실측과 일치(`docs/migration-notes.md` "공식 경로 실측").

## 그 밖의 소스

| 패키지 | 원본 | 핵심 규칙 |
|---|---|---|
| `kofia/` | `kofia.py` | 라벨 없는 컬럼은 확정된 것만 이름, 나머지 raw. 반대매매 비중 단독 게이트 금지 |
| `kis/` | `kis_master.py` | cp949 고정폭. 관리·정리매매 차단은 스냅샷 10일 이내만 |
| `dart/` | `opendart.py` | 키 없음·013 만 null, 나머지는 예외. `crtfc_key` 마스킹. account_id 우선·이름 폴백 |

## 함정

- KRX Open API(AUTH_KEY)는 지수 구성종목·업종 분류·PER/PBR·투자자별 순매수를 주지 않는다(2026-11-17 조사). 전략 경로는 PER/PBR 을 `Valuation` 으로 로컬 계산하고 구성종목은 `KOSPI_MCAP`, 업종은 KIS 마스터로 대체했다. 수급·공시 달력은 대체가 없다(`strategy-config.md`).
- KRX MDC 는 약관(제10조 2호)상 자동 수집 금지다. 2026-11-22 PIT 백테스트 2회의 대량 조회로 **IP 1일 차단**(KDM 이용 제한) — 차단 중엔 Open API 도 401 이다. KRX MDC 를 대량 조회하지 말 것(코드는 삭제됨).
- Open API 가 유효한 키로도 `401 Unauthorized API Call` 이면 서비스별 이용신청 미승인이다(인증키 승인과 별개, 2026-11-22). 신청은 포털 `openapi.krx.co.kr`, 호출은 데이터 호스트 `data-dbg.krx.co.kr` — 다른 주소다. IP 차단 중에도 같은 401 — 둘 다 반복 호출하지 말고 사람에게 확인.
- 업종지수는 indexes-only 백필로 섹터 계산 시작일(기준일−383일)까지 채워야 한다 — 첫 행 이전은 결측이 아니라서 덜 채우면 409 대신 짧은 지표가 나온다.
- data.go.kr 배당 API: 구 경로 `/service/GetStocDiviInfoService/getDiviInfo` 는 code 12(서비스 없음), V2 만 동작. 필터 없이 전체 이력(약 7만 행)이 한 번에 오고 `basDt` 를 주면 0건. 공시일 필드가 없다. 개발계정 일 10,000 건.
- OpenDART 백필(`dartBackfill`)은 호출 간격 제한이 없다. 2026-11-24 수동 백필이 55초에 1,001회를 부른 직후 연결 끊김(`header parser received no bytes`)이 나고, 이후 홈페이지까지 TCP 리셋(차단 추정). 일 한도(20,000)와 별개로 속도를 낮출 것 — 끊김이 나면 재시도 반복 금지. 리포트의 `stoppedByBudget=true` 는 쿨다운·한도 정지도 포함한다.
