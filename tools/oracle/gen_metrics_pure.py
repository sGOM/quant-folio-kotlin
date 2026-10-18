"""metrics 순수 계산부 정답지 — 기술지표, 섹터 지표 1건, 패닉 신호·점수·등급, 취약도 점수."""
from datetime import date
from unittest import mock

import numpy as np
import pandas as pd

from app.services.metrics import panic, sectors, stocks, vulnerability
from oracle_lib import save

rs = np.random.RandomState(31)


def path(n, vol=0.015, drift=0.0, start=50000):
    return start * np.exp(np.cumsum(rs.normal(drift, vol, n)))


# ── 기술지표: load_ohlcv 를 가짜로 바꿔 종가 경로별로 계산 ──
tech_cases = []
for name, n, drift in [("long_up", 300, 0.001), ("long_down", 300, -0.001), ("mid", 150, 0.0), ("short", 25, 0.0), ("tiny", 10, 0.0)]:
    closes = path(n, drift=drift)
    df = pd.DataFrame({"close": closes}, index=pd.bdate_range("2023-01-02", periods=n))
    with mock.patch.object(stocks, "load_ohlcv", return_value=df):
        out = stocks._compute_tech_indicators("000001", "20230101", "20241231")
    tech_cases.append({"name": name, "close": list(closes), "out": out})

# ── 섹터 지표 1건 ──
sector_cases = []
for name, n, ref in [("full", 300, 0.05), ("short", 80, 0.05), ("tiny", 15, 0.05), ("zero_ref", 300, 0.0), ("no_ref", 300, None)]:
    idx = pd.bdate_range("2023-01-02", periods=n)
    df = pd.DataFrame({"close": path(n, vol=0.01), "trading_value": rs.uniform(1e12, 3e12, n)}, index=idx)
    with mock.patch.object(sectors, "_fetch_index_ohlcv", return_value=df), mock.patch.object(sectors, "_get_index_name", return_value="전기전자"):
        m = sectors._compute_one_sector("1013", "KOSPI", "20241231", "20230101", ref)
    sector_cases.append({"name": name, "close": list(df["close"]), "trading_value": list(df["trading_value"]), "ref": ref,
                         "out": None if m is None else m.model_dump()})

# ── 패닉: 지수 신호 + 브레드스 + 점수/등급 ──
panic_cases = []
for k in range(14):
    n = 70
    idx = pd.bdate_range("2024-01-02", periods=n)
    close = path(n, vol=0.012)
    crash = [0.0, -0.03, -0.05, -0.08, -0.02, -0.12][k % 6]
    close[-1] = close[-2] * (1 + crash)
    if k % 4 == 1:
        close[-5:-1] *= np.linspace(0.97, 0.9, 4)
    tv = rs.uniform(5e12, 8e12, n)
    tv[-1] *= [1.0, 1.6, 2.4][k % 3]
    high = close * (1 + np.abs(rs.normal(0, 0.006, n)))
    low = close * (1 - np.abs(rs.normal(0, 0.006, n)))
    df = pd.DataFrame({"close": close, "high": high, "low": low, "trading_value": tv}, index=idx)
    nb = 900
    chg = rs.normal(-1.5 * (k % 5), 3.0, nb)
    vol = rs.randint(0, 1000, nb).astype(float)
    vol[rs.rand(nb) < 0.05] = 0
    pc = pd.DataFrame({"등락률": chg, "거래량": vol})
    mkt = "KOSPI" if k % 2 == 0 else "KOSDAQ"
    sig = panic._index_signals(df)
    sig.update(panic._breadth_signals(pc))
    sig["nlr"] = [None, 0.05, 0.2, 0.3][k % 4]
    res = panic._score_and_label(mkt, sig)
    panic_cases.append({"market": mkt, "index": {c: list(df[c]) for c in df.columns}, "breadth": {"chg": list(chg), "vol": list(vol)},
                        "nlr": sig["nlr"], "signals": sig, "result": res})
breadth_empty = panic._breadth_signals(None)

# ── 취약도 ──
vuln_cases = []
for k in range(6):
    n = [300, 300, 100, 400, 50, 300][k]
    credit = list(20e12 * np.exp(np.cumsum(rs.normal(0.0008 * (k - 2), 0.004, n))))
    if k == 5:
        credit[-1] = None
    forced = list(rs.uniform(2, 10, 30))
    out = vulnerability.score_vulnerability(date(2026, 10, 16), credit, forced if k != 4 else [])
    vuln_cases.append({"credit": credit, "forced": forced if k != 4 else [], "out": {
        "score": out.score, "exposure_scale": out.exposure_scale, "signals": out.signals, "subscores": out.subscores}})

save("metrics_pure", {"tech": tech_cases, "sector": sector_cases, "panic": panic_cases, "breadth_empty": breadth_empty, "vuln": vuln_cases})
print(len(tech_cases), len(sector_cases), len(panic_cases), len(vuln_cases))
