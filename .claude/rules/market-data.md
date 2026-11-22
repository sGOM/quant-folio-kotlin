---
paths:
  - "backend/core/src/main/kotlin/com/quantfolio/market/**"
---

# 시장데이터 — 외부 소스·로컬 저장소

## 실패는 값이 아니라 예외다

외부 소스(KRX·DART·data.go.kr·KOFIA·네이버·KIS) 실패는 반드시 `DataSourceError` 하위로 던진다. 빈 리스트로 삼키면
백테스트가 빈 패널 위에서 '성공'하며 무의미한 수치를 낸다(원본 §44-1 실제 사고).

| 원인 | 의미 | 재시도 | 쿨다운 | HTTP |
|---|---|---|---|---|
| `SourceAuthError` | 인증·차단 | ✗ | 300s | 503 |
| `SourceQuotaError` | 한도 초과(`retryAfterSeconds` 로 리셋 시각) | ✗ | 300s | 503 |
| `SourceUnavailableError` | 네트워크·5xx·점검 | ✓ | 60s | 503 |
| `SourceSchemaError` | 응답 형식이 파서와 다름 — 코드 수정 필요 | ✗ | 없음 | 502 |
| `SourceRequestError` | 우리가 잘못 보냄 — 버그 | ✗ | 없음 | 500 |

- 종목·시장별 루프는 실패를 모으고 `Cooldowns.stopAggregate()` 로 단락, 전량 실패면 `DataSourceErrors.representative()` 를 던진다.
  일부만 실패하면 성공분을 쓰되 **확정으로 굳히지 않는다**(다음 호출에서 보완).
- 쿨다운 검사는 "캐시된 세션 재사용" 분기보다 **앞에서** 한다(차단 중에도 요청이 나가는 결함, §44-1).

**`MarketDataNotLoaded` 는 `DataSourceError` 가 아니다** — 외부 실패가 아니라 "로컬에 아직 없음"(`market/official/`). 전략 경로는 원격으로 내려가지 않고
이를 던지고, 호출부는 삼키지 않는다(백테스트 409, 라이브 엔진은 주문 없이 멈춤+critical). `catch (Exception)` 이 이를 `null`·빈 값으로 바꾸는 게 가장 흔한 사고다.

## 로컬 우선 4상태 (`StoreCache`)

확정 기록 있음 → 로컬 / 기록 없음·미확정 → 원격 1회 + 저장 + 원장 / 원격 실패 → 예외 전파 / 소스 미설정 → 진입 전 처리.

- **0행은 절대 확정 금지.** "진짜 무자료"와 "스키마 변경으로 값 잃음"을 구분할 수 없고, 굳으면 수동 삭제 전까지 영구 0행(§47·§49).
  유일한 예외: KRX Open API 적재(`OfficialIngest`)의 휴장일 — 같은 날 지수·주식 5개 호출이 모두 0행이고 그날이 7일 이상 지났을 때만 `final` 로 굳힌다(7일 이내 0행은 미확정, 재조회).
- 요청 컬럼 중 **전부 null 인 컬럼**이 있으면 확정 보류(포맷 변경으로 변환이 통째로 실패한 신호).
- 확정은 **KST 전날까지**(`StoreCache.lastFinalDate()`). 당일분은 장중 값이 바뀐다.
- 범위 조회(`cachedRange`)는 **개별** 확보 구간이 요청을 덮을 때만 로컬. 구간 합집합으로 판정하지 않는다.
  실패 예외엔 로컬 확보 구간을 덧붙이되, 기간을 대신 줄이지 않는다.

## 저장소 테이블

- 리포지토리는 테이블별 타입 행(`StockDaily` 등, 숫자 `Double?`)을 주고받는다. upsert 는 모든 컬럼
  `COALESCE(EXCLUDED, 기존)` — 들어온 null 은 기존값 보존(여러 소스가 한 행을 나눠 채운다).
- 저장 시 종목코드 6자리 0-패딩, 같은 종목 중복은 마지막 값, NaN/±Inf → null.
- 시장 혼재 테이블(`stock_daily_snapshots`·`stock_period_stats`)의 단일시장 조회는 반드시 `markets` 필터.
- 운영 테이블과 FK 없음 — 저장소는 통째로 지우고 재적재할 수 있어야 한다.

## 공식 경로 (`market/official/`·`market/krxopen/`) — 전략 경로의 단일 출처

전략 경로(백테스트·리밸런싱·패닉·유니버스·달력·심볼·배치)는 `CodeRulesTest` 가 MDC·네이버 클래스 참조를 금지한다. 로컬 테이블만 읽는다.

| 클래스 | 역할 |
|---|---|
| `krxopen/` | KRX Open API(AUTH_KEY) 클라이언트·일일/백필 적재. 원장 `krx_open|<서비스>|yyyyMMdd|<시장>` |
| `AdjustedBars` | 원주가 + 전일대비로 수정주가 계산(분할 반영). 수정 후 `price_ticks` 에 저장 |
| `LocalSnapshots`·`LocalTradingDays` | 로컬 `SnapshotSource`·거래일(= `index_ohlcv` 1001 날짜). 적재 최대일 초과는 결측 |
| `Valuation` | PER/PBR/DIV 로컬 계산(DART 재무·시총·배당) |
| `DividendIngest`·`DividendFeed` | 공공데이터포털 `getDiviInfo_V2` 전체 적재(원장 `datagokr|dividends_full`)·백테스트 현금 적립 |
| `McapUniverse` | `KOSPI_MCAP` 유니버스(직전 거래일 이하 최신 시총·종목별 최신 `stock_kind`) |

- **Valuation `div=0.0` 규칙:** 전체 배당 적재가 있고 창(공시일 ≤ asOf, 기준일이 365일 안) 안에 행이 없으면 0.0, 적재가 없거나 배당 이력 시작+365일 이전이면 `null`. 0 과 결측을 섞지 않는다.
- 배당 금액은 기준일 당시 주식 단위(분할 전 값)다 — 수정주가 단위로 환산해 쓴다. 배당락일은 "기준일 이하 마지막 거래일의 직전 거래일".
- 0행 휴장 확정은 위 4상태 예외(7일 이상 지난 날만 `final`). 지수명→코드 매핑 밖 지수는 저장하지 않는다.
- Open API 필드명·지수명·공개 시각은 **미실측**(문서상 이름) — 파서가 어긋나면 `SourceSchemaError` 로 드러난다.

## KRX MDC (`market/krx/`) — UI 경로 전용(2단계에서 제거 예정)

| 클래스 | 원본 | 역할 |
|---|---|---|
| `KrxSession` | pykrx `KRXSession` + `krx_index._session` | 로그인·쿠키·1시간 만료(300s 버퍼)·쿨다운 우선 |
| `KrxMdc.rows` | `krx_index._krx_rows` | 응답 원인 분류(HTML=Auth, 키 없음=Schema) |
| `KrxMdc.pykrx` | pykrx `KrxWebIo` | 분류 없음, 730일 분할. 호출자가 Unavailable 로 감싼다 |
| `KrxStockApi` | pykrx `stock.*` 9종 | 같은 bld·파라미터·숫자 정리 규칙 |
| `KrxIndexService` | `krx_index.py` | 구성종목·전종목·시총. `sectorMap` 만 로컬 스냅샷 전용(전략 경로도 사용) |
| `KrxSnapshots` | `metrics/fetch.py` | 위를 `StoreCache` 로 감싼 로컬 우선 조회 |

- `KrxStockApi` 의 숫자 정리는 함수마다 다르다(`\W` 제거는 부호·소수점까지 지움, int32 컬럼은 범위 초과 시 실패). pykrx 를 그대로 옮긴 것이니 "통일"하지 말 것.
- KRX 미인증(자격증명 없음)은 실패가 아니라 빈 결과. 캐시는 **성공했을 때만**.

## 그 밖의 소스

| 패키지 | 원본 | 핵심 규칙 |
|---|---|---|
| `ohlcv/` | `loader.py`·`ingest.py` | UI 전용: FDR→pykrx 순 네이버 차트, price_ticks 는 부족분(머리·꼬리, 5일 유예)만 보충. 전략 경로는 `LocalOhlcvCoverage`(외부 없이 빠진 거래일을 `MarketDataNotLoaded`) |
| `kofia/` | `kofia.py` | 라벨 없는 컬럼은 확정된 것만 이름, 나머지 raw. 반대매매 비중 단독 게이트 금지 |
| `kis/` | `kis_master.py` | cp949 고정폭. 관리·정리매매 차단은 스냅샷 10일 이내만 |
| `dart/` | `opendart.py` | 키 없음·013 만 null, 나머지는 예외. `crtfc_key` 마스킹. account_id 우선·이름 폴백. 전략 경로는 `remote=false`(로컬만) |
| `symbols/` | `symbols.py` | KIS 마스터(+seed)만. FDR·KRX 제거 |

## 함정

- 원본 데이터 소스: 종목 일봉 = FDR → 네이버 차트(수정주가), KRX MDC 계열 = `getJsonData.cmd` POST. 전략 경로는 이제 Open API+로컬 수정주가라 **둘을 섞으면** 같은 종목의 가격 단위가 달라진다(백필 시 기존 `price_ticks` 보다 이른 날을 채울 때 특히).
- KRX Open API(AUTH_KEY)는 지수 구성종목·업종 분류·PER/PBR·투자자별 순매수를 주지 않는다(2026-11-17 조사). 전략 경로는 PER/PBR 을 `Valuation` 으로 로컬 계산하고 구성종목은 `KOSPI_MCAP`, 업종은 KIS 마스터로 대체했다. 수급(`flow`)·공시 달력(`pead`)은 대체가 없어 1단계에서 거부한다.
  UI 경로의 MDC 로그인은 그대로다 — KRX 가 비밀번호 변경을 요구하면 `CD010` 으로 실패한다(사람이 사이트에서 바꾸고 `secrets/krx_pw.txt` 갱신).
- KRX MDC 는 약관(제10조 2호)상 자동 수집 금지다. 2026-11-22 PIT 백테스트 2회의 대량 조회로 **IP 1일 차단**(KDM 이용 제한) — 차단 중엔 Open API 도 401 이다. 백테스트로 MDC 를 백필하지 말 것.
- KRX 로그인은 계정 차단 이력이 있다 — 반복 로그인 테스트 금지, 응답 녹화는 한 번에.
- data.go.kr 배당 API: 구 경로 `/service/GetStocDiviInfoService/getDiviInfo` 는 code 12(서비스 없음), V2 만 동작. 필터 없이 전체 이력(약 7만 행)이 한 번에 오고 `basDt` 를 주면 0건. 공시일 필드가 없다. 개발계정 일 10,000 건.
