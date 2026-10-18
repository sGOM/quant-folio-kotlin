#!/usr/bin/env bash
# 원본 Python(기준 커밋 8a563702)을 원본 이미지 안에서 실행해 정답지 JSON 을 만든다.
#   tools/oracle/run.sh gen_series.py
# ORIGINAL_SRC: 원본 저장소 체크아웃의 backend 디렉터리(기준 커밋). 네트워크 없이 돈다(외부 조회 금지).
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
repo="$(cd "$here/../.." && pwd)"
src="${ORIGINAL_SRC:?ORIGINAL_SRC 에 원본 backend 경로를 지정하세요}"
out="${ORACLE_OUT:-$repo/backend/core/src/test/resources/oracle}"
to_win() { if command -v cygpath >/dev/null; then cygpath -w "$1"; else echo "$1"; fi; }
MSYS_NO_PATHCONV=1 docker run --rm --network none \
  -v "$(to_win "$src"):/app" -v "$(to_win "$here"):/oracle" -v "$(to_win "$out"):/out" \
  -e PYTHONPATH=/app -e PYTHONIOENCODING=utf-8 -e SECRET_KEY=x -e CREDENTIAL_ENC_KEY=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA= \
  --entrypoint python quantfolio-web "/oracle/$1"
