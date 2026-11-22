# secrets/

비밀(증권사·외부 API 키)을 **평문 `.env` 대신 파일**로 보관하는 디렉터리입니다.
docker compose 가 이 파일들을 `/run/secrets/<이름>`(tmpfs)으로 마운트하고, Spring
`configtree` 가 같은 이름의 속성으로 읽습니다(앞뒤 공백·개행은 떼어 냄 — 원본 `strip()` 과 같음). 따라서 비밀이 `docker inspect`·이미지 레이어·
프로세스 환경(`/proc`)에 남지 않습니다.

> ⚠️ 이 디렉터리의 `*.txt` 는 `.gitignore` 로 커밋에서 제외됩니다. 절대 커밋하지 마세요.

## 필요한 파일

| 파일 | 내용 | 비고 |
|------|------|------|
| `kis_app_key.txt` | KIS app_key (서버 설정이 유일한 출처) | 미사용 시 빈 파일 |
| `kis_app_secret.txt` | KIS app_secret (서버 설정이 유일한 출처) | 미사용 시 빈 파일 |
| `account_num` | KIS 계좌번호 `CANO-PRDT`(예: 50012345-01). 확장자 없음 | 미사용 시 빈 파일 |
| `hts_id.txt` | KIS HTS ID(실시간 체결통보 구독용) | 미사용 시 빈 파일 |
| `toss_app_key.txt` | 토스 client_id (서버 설정이 유일한 출처) | 미사용 시 빈 파일 |
| `toss_app_secret.txt` | 토스 client_secret (서버 설정이 유일한 출처) | 미사용 시 빈 파일 |
| `krx_id.txt` | (선택) KRX 데이터 포털 로그인 ID | 지표 화면용. 미사용 시 빈 파일 |
| `krx_pw.txt` | (선택) KRX 데이터 포털 비밀번호 | 지표 화면용. 미사용 시 빈 파일 |
| `opendart_api_key.txt` | (선택) OpenDART 인증키 | 퀄리티·성장 팩터·턴어라운드 스크리너. 없으면 해당 팩터가 중립 처리돼 점수가 편향된다. 미사용 시 빈 파일 |

혼자 쓰는 서비스라 증권사 키·시크릿·계좌는 **서버 설정(이 디렉터리의 시크릿 파일 우선, 환경변수 호환)에서만** 읽습니다.
웹 화면·API 로 등록하는 기능은 없고 사용자 DB 에도 자격증명 컬럼이 없습니다(Flyway V6 에서 삭제).
거래·로그인은 `.env` 의 `TRADING_OWNER_USER_ID` 로 지정한 사용자만 가능합니다.
KIS 계좌번호(`account_num`)와 HTS ID(`hts_id.txt`)도 같은 방식으로 이 디렉터리에서만 읽습니다(파일 끝 개행 없이 저장).
브로커 키를 쓰지 않는다면 브로커 파일들은 빈 파일로 두면 됩니다(파일 자체는 존재해야 compose 가 기동됩니다).

## 생성 예시

```bash
mkdir -p secrets
# 미사용 브로커 키는 빈 파일로 생성
: > secrets/kis_app_key.txt;  : > secrets/kis_app_secret.txt
: > secrets/account_num;       : > secrets/hts_id.txt
: > secrets/toss_app_key.txt; : > secrets/toss_app_secret.txt
# KRX 데이터 포털(지표 화면용). 무료 가입 https://data.krx.co.kr — 미사용 시 빈 파일
: > secrets/krx_id.txt; : > secrets/krx_pw.txt
# OpenDART 재무데이터(퀄리티·성장 팩터). 무료 발급 https://opendart.fss.or.kr — 미사용 시 빈 파일
: > secrets/opendart_api_key.txt
```

## 파일 권한(선택, 권장)

```bash
chmod 600 secrets/*.txt
```
