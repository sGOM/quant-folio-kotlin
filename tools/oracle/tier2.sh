#!/usr/bin/env bash
# 2층(실데이터) 정답지 — 로컬 DB price_ticks 를 CSV 로 뽑아 원본 Python 엔진에 넣고, 같은 CSV 를 Kotlin 이 재생한다.
# 실데이터라 산출물(tools/oracle/tier2/)은 gitignore. 외부 조회·KRX 로그인 없음.
#   ORIGINAL_SRC=... tools/oracle/tier2.sh   (DB 컨테이너: ${DB_CONTAINER:-quantfolio-db-1})
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
out="$here/tier2"
mkdir -p "$out"
db="${DB_CONTAINER:-quantfolio-db-1}"
docker exec "$db" sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -Atc "
  copy (
    select symbol, time::date, open, high, low, close, volume from price_ticks
    where symbol in (select symbol from price_ticks group by symbol having min(time) < date '\''2019-01-01'\'' order by count(*) desc limit 40)
      and time >= date '\''2019-01-01'\''
    order by symbol, time
  ) to stdout with csv"' > "$out/prices.csv"
echo "prices.csv: $(wc -l < "$out/prices.csv") rows"
ORACLE_OUT="$out" "$here/run.sh" gen_tier2.py
