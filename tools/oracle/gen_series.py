"""Series 연산 정답지 — pandas 2.2.3 의미."""
import pandas as pd

from oracle_lib import save, walks

cases = []


def case(series_name, op, params, out):
    cases.append({"series": series_name, "op": op, "params": params, "out": out})


for name, s in walks().items():
    for w, mp in [(5, None), (5, 2), (20, None), (20, 10)]:
        r = s.rolling(w, min_periods=mp)
        p = {"window": w, "min_periods": mp}
        case(name, "rolling_mean", p, r.mean())
        case(name, "rolling_std", p, r.std())
        case(name, "rolling_max", p, r.max())
        case(name, "rolling_min", p, r.min())
        case(name, "rolling_sum", p, r.sum())
    for span in [3, 12, 26]:
        case(name, "ewm_mean", {"span": span, "adjust": True}, s.ewm(span=span, adjust=True).mean())
        case(name, "ewm_mean", {"span": span, "adjust": False}, s.ewm(span=span, adjust=False).mean())
    case(name, "ewm_mean_alpha", {"alpha": 0.25, "adjust": False}, s.ewm(alpha=0.25, adjust=False).mean())
    for n in [1, 3, -2]:
        case(name, "shift", {"n": n}, s.shift(n))
    case(name, "diff", {"n": 1}, s.diff())
    case(name, "pct_change", {"n": 1}, s.pct_change())
    case(name, "pct_change", {"n": 5}, s.pct_change(5))
    case(name, "ffill", {"limit": None}, s.ffill())
    case(name, "ffill", {"limit": 1}, s.ffill(limit=1))
    case(name, "cumsum", {}, s.cumsum())
    case(name, "expanding_max", {}, s.expanding().max())
    for m in ["average", "min", "max", "first", "dense"]:
        case(name, "rank", {"method": m, "pct": False}, s.rank(method=m))
    case(name, "rank", {"method": "average", "pct": True}, s.rank(pct=True))
    for q in [0.1, 0.5, 0.9]:
        case(name, "quantile", {"q": q}, [s.quantile(q)])
    case(name, "mean", {}, [s.mean()])
    case(name, "std", {}, [s.std()])
    case(name, "sum", {}, [s.sum()])

inputs = {k: {"index": [d.strftime("%Y-%m-%d") for d in v.index], "values": list(v)} for k, v in walks().items()}
save("series_ops", {"inputs": inputs, "cases": cases})
print(len(cases), "cases")
