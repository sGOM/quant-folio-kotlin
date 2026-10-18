"""backtest/deflated_sharpe.py 정답지."""
import numpy as np

from app.services.backtest import deflated_sharpe as ds
from oracle_lib import save

rs = np.random.RandomState(21)


def curve(n, drift, vol, seed):
    r = np.random.RandomState(seed).normal(drift, vol, n)
    v = 10_000_000 * np.cumprod(1 + r)
    return [{"t": str(i), "v": float(x)} for i, x in enumerate(v)]


def result(n, drift, vol, seed, sharpe):
    return {"sharpe": sharpe, "equity_curve": curve(n, drift, vol, seed)}


cases = []
target = result(200, 0.0008, 0.012, 1, 1.4)
for name, homo in {
    "single": [target],
    "two": [target, result(200, 0.0005, 0.012, 2, 0.9)],
    "four_low_conf": [target] + [result(200, 0.0004 + 0.0001 * k, 0.012, 10 + k, 0.6 + 0.2 * k) for k in range(3)],
    "ten": [target] + [result(200, 0.0003 + 0.00005 * k, 0.011, 20 + k, 0.5 + 0.1 * k) for k in range(9)],
    "correlated": [target] + [{"sharpe": 1.3 + 0.01 * k, "equity_curve": target["equity_curve"]} for k in range(6)],
    "mixed_len_missing_sharpe": [target, {"sharpe": None, "equity_curve": curve(150, 0.0, 0.01, 40)}, result(120, 0.0002, 0.02, 41, 0.3), result(5, 0, 0.01, 42, 0.1)],
}.items():
    cases.append({"name": name, "target": target, "homogeneous": homo, "out": ds.analyze_backtest_dsr(target, homo)})

cases.append({"name": "no_sharpe", "target": {"sharpe": None, "equity_curve": curve(100, 0, 0.01, 3)}, "homogeneous": [],
              "out": ds.analyze_backtest_dsr({"sharpe": None, "equity_curve": curve(100, 0, 0.01, 3)}, [])})
short = result(20, 0.001, 0.01, 4, 1.0)
cases.append({"name": "short", "target": short, "homogeneous": [short], "out": ds.analyze_backtest_dsr(short, [short])})
neg = result(300, -0.001, 0.02, 5, -1.1)
cases.append({"name": "negative", "target": neg, "homogeneous": [neg, target, result(300, 0.0, 0.015, 6, 0.0)],
              "out": ds.analyze_backtest_dsr(neg, [neg, target, result(300, 0.0, 0.015, 6, 0.0)])})

try:
    ds.compute_deflated_sharpe([0.5] + [-0.001] * 40, 40.0, 3, 0.01)
    se_err = None
except ValueError as e:
    se_err = str(e)

save("dsr", {"cases": cases, "grades": [[x, ds.grade_dsr(x)] for x in (None, 0.97, 0.95, 0.92, 0.9, 0.6, 0.5, 0.1)], "se_error": se_err})
print(len(cases), "cases; se_err =", se_err)
