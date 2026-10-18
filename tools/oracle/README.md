# 정답지(oracle) 하네스

원본 Python 백엔드(기준 커밋 `8a563702`)를 원본 Docker 이미지 안에서 실행해, Kotlin 구현이 대조할 결과를 만든다(설계 §8).

```bash
ORIGINAL_SRC=/path/to/quant-folio/backend tools/oracle/run.sh gen_series.py
```

- **1층(커밋)**: 합성 입력만. 입력과 출력을 함께 `backend/core/src/test/resources/oracle/*.json` 에 쓴다. CI 에서 대조.
- **2층(로컬 전용)**: 실데이터 백테스트. 출력은 커밋하지 않는다(공개 저장소 — 데이터 재배포·용량).
- 컨테이너는 `--network none` 으로 돈다. 정답지가 외부 데이터에 기대면 실패해야 한다.
