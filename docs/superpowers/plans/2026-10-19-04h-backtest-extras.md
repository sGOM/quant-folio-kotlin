# 4h단계 백테스트 부가 분석 Implementation Plan

**Goal:** 원본 `backtest/tracking.py`·`fill_quality.py`·`slippage_calibration.py` 이전(attribution·risk_caps·slippage 는 4g 에서 선반영).
**Spec:** 설계 §8 · 정답지 `gen_tracking.py` → `tracking.json`.

## Global Constraints
- 순수 함수(조회 없음). 입력은 체결·주문 데이터 클래스 + `Panel`, 출력은 원본 dict 키 그대로의 Map.
- 캘리브레이션은 **제안만** — config 반영은 사람 승인 라우트(5단계)에서만.

### Task 1 (단일): `backtest/Tracking.kt` (`Tracking`·`FillQuality`·`SlippageCalibration`) + `TrackingOracleTest`
- [x] RED(미정의) → GREEN 4종(재구성·현금 재생·곡선 비교·체결 정합 3설정·캘리브레이션 6건).
- [x] 뮤테이션 2종(종가 bfill·휴장일 체결의 직전 거래일 폴백)이 처음엔 생존 → 정답지에 경로 추가 → 사살.
