"""metrics/factors.py 순수 계산부 정답지 — 윈저라이즈 z-score, 중립화, 종합점수, 잔차 모멘텀."""
import numpy as np
import pandas as pd

from app.services.metrics.factors import _compute_stock_scores, _winsorize_zscore, compute_residual_momentum_panel
from oracle_lib import save

rs = np.random.RandomState(17)
N = 40
codes = [f"{100000 + i:06d}" for i in range(N)]


def col(scale=1.0, nan_frac=0.15, ties=False):
    v = rs.normal(0, scale, N)
    if ties:
        v = np.round(v)
    v[rs.rand(N) < nan_frac] = np.nan
    return v


df = pd.DataFrame({
    "PER": rs.choice([-5.0, 0.0, 8.0, 12.0, 25.0, 300.0, np.nan], N),
    "PBR": np.abs(col(1.5)) + 0.2, "DIV": np.abs(col(2.0)),
    "mom_1m": col(0.05), "mom_3m": col(0.1), "mom_6m": col(0.2), "high_52w_ratio": -np.abs(col(0.2)),
    "vol_ann": np.abs(col(0.3)) + 0.1, "mdd_252": -np.abs(col(0.3)),
    "roe": col(0.1), "debt_ratio": np.abs(col(1.0)), "fcf": col(1e9), "f_score": rs.randint(0, 9, N).astype(float),
    "op_growth": col(0.3), "net_growth": col(0.5), "turnaround": rs.choice([0.0, 1.0, np.nan], N),
    "flow_norm": col(0.01), "resid_mom": col(1.0), "pead_sue": col(1.5, ties=True),
    "market_cap": np.exp(rs.normal(27, 1.5, N)),
}, index=pd.Index(codes, name="code"))
df.loc[codes[3], "market_cap"] = np.nan
df["sector"] = rs.choice(["반도체", "은행", "화학", "단독업종", None], N)
df.loc[codes[0], "sector"] = "단독업종"
df.loc[[c for c in codes if df.loc[c, "sector"] == "단독업종"][1:], "sector"] = "화학"

weights_sets = {
    "default": None,
    "all": {"momentum": 0.2, "value": 0.1, "lowvol": 0.1, "quality": 0.15, "growth": 0.1, "flow": 0.1, "residual_momentum": 0.15, "pead": 0.1},
}
cases = []
for wname, w in weights_sets.items():
    for neut in ("none", "size", "sector", "size_sector"):
        out = _compute_stock_scores(df.copy(), weights=w, neutralize=neut)
        cats = [c for c in out.columns if c.startswith("score")]
        cases.append({"weights": wname, "neutralize": neut, "out": {c: list(out[c]) for c in cats}})

wz = {}
for c in ("PBR", "mom_6m", "pead_sue"):
    wz[c] = {"plain": list(_winsorize_zscore(df[c])), "invert": list(_winsorize_zscore(df[c], invert=True))}
wz["few"] = list(_winsorize_zscore(pd.Series([1.0, np.nan, 2.0])))
wz["const"] = list(_winsorize_zscore(pd.Series([5.0, 5.0, 5.0, np.nan])))

# 잔차 모멘텀: 일별 종가 패널(월말 집계)
days = pd.bdate_range("2020-01-01", "2024-06-28")
mkt = pd.Series(2500 * np.exp(np.cumsum(rs.normal(0.0003, 0.01, len(days)))), index=days)
panel = {}
for i in range(8):
    beta = 0.5 + 0.2 * i
    eps = rs.normal(0.0002 * (i - 4), 0.012, len(days))
    s = pd.Series(10000 * np.exp(np.cumsum(beta * np.log(mkt / mkt.shift(1)).fillna(0).to_numpy() + eps)), index=days)
    if i == 6:
        s.iloc[: len(days) // 2] = np.nan  # 상장 이력 짧음 → 관측 부족
    panel[f"{200000 + i:06d}"] = s
panel = pd.DataFrame(panel)
rm_cases = []
for as_of, rw, mw, sk in [("2024-06-28", 36, 11, 1), ("2023-12-15", 24, 6, 0), ("2022-03-31", 36, 11, 2)]:
    out = compute_residual_momentum_panel(panel, mkt, pd.Timestamp(as_of).date(), reg_window=rw, mom_window=mw, skip=sk)
    rm_cases.append({"as_of": as_of, "reg_window": rw, "mom_window": mw, "skip": sk, "out": {k: float(v) for k, v in out.items()}})

save("factors", {
    "frame": {"index": codes, **{c: list(df[c]) for c in df.columns if c != "sector"}, "sector": [None if s is None else s for s in df["sector"]]},
    "cases": cases, "winsor": wz,
    "panel": {"index": [d.strftime("%Y-%m-%d") for d in days], "market": list(mkt), **{c: list(panel[c]) for c in panel.columns}},
    "resid_mom": rm_cases,
})
print(len(cases), "score cases,", len(rm_cases), "resid cases")
