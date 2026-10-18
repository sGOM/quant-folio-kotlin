"""턴어라운드 스크리너·KOSPI200 추천 정답지 — 외부 조회 함수를 고정 데이터로 바꿔 조립부까지 검증."""
from datetime import date, timedelta
from unittest import mock

import numpy as np
import pandas as pd

from app.services import recommend, screener
from app.services.metrics import _approx_start, _ymd, stocks
from oracle_lib import save

rs = np.random.RandomState(23)
AS_OF = date(2026, 10, 13)  # 화요일 → 직전 영업일 월요일
CODES = [f"9{i:05d}" for i in range(1, 41)]  # seed 카탈로그에 없는 코드
NAMES = {c: f"종목{k}" for k, c in enumerate(CODES)}

cap = pd.DataFrame({
    "시가총액": rs.uniform(5e10, 5e12, 40).round(0),
    "상장주식수": rs.randint(1_000_000, 50_000_000, 40).astype(float),
    "market": ["KOSPI" if k % 3 else ("KOSDAQ" if k % 2 else " ") for k in range(40)],
}, index=CODES)
cap.iloc[5, 0] = np.nan
cap.iloc[7, 1] = 0.0


def pc(scale, seed_shift):
    r = np.random.RandomState(100 + seed_shift)
    df = pd.DataFrame({
        "종목명": [NAMES[c] if k % 6 else None for k, c in enumerate(CODES)],
        "등락률": r.normal(1.0, 8.0, 40), "종가": r.uniform(1000, 200000, 40).round(0),
        "거래대금": r.uniform(1e8, 5e10, 40) * scale, "거래량": r.randint(0, 100000, 40).astype(float),
    }, index=CODES)
    return df.drop(index=CODES[::9])  # 일부 종목 누락


PC = {"5": pc(1.0, 1), "60": pc(8.0, 2), "1": pc(0.2, 3), "21": pc(3.0, 4), "63": pc(9.0, 5), "126": pc(18.0, 6)}
START = {
    _ymd(_approx_start(AS_OF, 5, buffer=4)): "5", _ymd(_approx_start(AS_OF, 60)): "60", _ymd(AS_OF - timedelta(days=1)): "1",
    _ymd(_approx_start(AS_OF, 21)): "21", _ymd(_approx_start(AS_OF, 63)): "63", _ymd(_approx_start(AS_OF, 126)): "126",
}
FUND = pd.DataFrame({"PER": rs.normal(12, 8, 40), "PBR": rs.uniform(0.3, 3, 40), "DIV": rs.uniform(0, 5, 40)}, index=CODES)
QM = {c: {"debt_ratio": [0.5, 0.8, None, 2.0][k % 4], "loss_years_3": [0, 3, 1, None][k % 4], "net_income": [1e9, -5e8, None, 3e8][k % 4],
          "turnaround": [1.0, 0.0, None, 0.0][k % 4], "net_growth": [0.4, -0.8, None, 3.5][k % 4], "f_score": [6, 2, None, 8][k % 4],
          "roe": rs.normal(0.08, 0.05), "fcf": rs.normal(1e9, 2e9), "op_growth": rs.normal(0.1, 0.3)} for k, c in enumerate(CODES) if k % 5}
MEMBERS = CODES[:30] + ["999999"]
CLOSES = {c: list(30000 * np.exp(np.cumsum(rs.normal(0.0003, 0.015, 300)))) for c in CODES}
DAYS = pd.bdate_range("2025-08-01", periods=300)


def fetch_pc(start, end, mkts):
    return PC[START[start]].copy()


def fake_ohlcv(code, start, end):
    if code not in CLOSES or code.endswith("3"):
        raise RuntimeError("no data")
    return pd.DataFrame({"close": CLOSES[code]}, index=DAYS)


common = [
    mock.patch.object(screener, "_fetch_market_cap", return_value=cap.copy()),
    mock.patch.object(screener, "_fetch_price_change", side_effect=fetch_pc),
    mock.patch.object(screener, "_fetch_fundamentals", return_value=FUND.copy()),
    mock.patch.object(screener, "_build_name_map", return_value={}),
    mock.patch.object(recommend, "_fetch_market_cap", return_value=cap.copy()),
    mock.patch.object(recommend, "_fetch_price_change", side_effect=fetch_pc),
    mock.patch.object(recommend, "_fetch_fundamentals", return_value=FUND.copy()),
    mock.patch.object(recommend, "_build_name_map", return_value={}),
    mock.patch.object(recommend, "_prev_business_day", return_value=AS_OF - timedelta(days=1)),
    mock.patch.object(stocks, "load_ohlcv", side_effect=fake_ohlcv),
]
for p in common:
    p.start()

screens = []
for enabled, qm, kw in [(True, QM, {}), (False, {}, {"top_n": 5, "smallcap_pct": 0.5, "surge": 0.5, "min_value": 1e8}),
                        (True, QM, {"smallcap_pct": 0.8, "surge": 0.2, "min_value": 0, "max_candidates": 30, "top_n": 30}),
                        (True, None, {"smallcap_pct": 0.6, "surge": 0.3, "min_value": 0, "max_debt": 1.0, "max_candidates": 12})]:
    from app.services.data.errors import SourceAuthError
    side = SourceAuthError("opendart", "x") if qm is None else None
    with mock.patch.object(screener.opendart, "is_enabled", return_value=enabled), \
         mock.patch.object(screener.opendart, "metrics_by_symbol", return_value=qm, side_effect=side):
        out = screener.screen_turnaround(AS_OF, "ALL", **kw)
    screens.append({"enabled": enabled, "qm_fail": qm is None, "kw": kw, "out": out.model_dump(mode="json")})

recs = []
for enabled, qm, members in [(True, QM, MEMBERS), (False, {}, MEMBERS), (True, QM, [])]:
    with mock.patch.object(recommend.opendart, "is_enabled", return_value=enabled), \
         mock.patch.object(recommend.opendart, "metrics_by_symbol", return_value=qm), \
         mock.patch.object(recommend.krx_index, "index_members", return_value=members):
        out = recommend.compute_kospi200_scored(AS_OF)
    recs.append({"enabled": enabled, "members": members, "out": out.model_dump(mode="json")})

save("screener", {
    "as_of": AS_OF.isoformat(), "codes": CODES,
    "cap": {"market_cap": list(cap["시가총액"]), "shares": list(cap["상장주식수"]), "market": list(cap["market"])},
    "pc": {k: {"codes": list(v.index), "name": list(v["종목명"]), "chg": list(v["등락률"]), "close": list(v["종가"]),
               "value": list(v["거래대금"]), "volume": list(v["거래량"])} for k, v in PC.items()},
    "fund": {c: list(FUND[c]) for c in FUND.columns}, "qm": QM, "closes": CLOSES, "days": [d.strftime("%Y-%m-%d") for d in DAYS],
    "screens": screens, "recs": recs,
})
print([(s["out"]["scanned"], s["out"]["count"], s["out"]["financial_filter_applied"]) for s in screens], [r["out"]["count"] for r in recs])
