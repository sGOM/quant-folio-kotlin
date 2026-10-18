"""metrics/common.py 정답지 — MDD·연환산 변동성·ramp·wavg·approx_start."""
from datetime import date

import numpy as np

from app.services.metrics.common import _approx_start, _compute_mdd, _compute_vol_ann, _ramp, _wavg
from oracle_lib import save, walks

out = {"inputs": {}, "mdd": {}, "vol": {}}
for name, s in walks(seed=11, n=60).items():
    out["inputs"][name] = {"index": [d.strftime("%Y-%m-%d") for d in s.index], "values": list(s)}
    out["mdd"][name] = _compute_mdd(s)
    out["vol"][name] = _compute_vol_ann(s)
    out["mdd"][name + "_short"] = _compute_mdd(s.iloc[:1])
    out["vol"][name + "_19"] = _compute_vol_ann(s.iloc[:19])
out["ramp"] = [[x, w, h, _ramp(x, w, h)] for x, w, h in [(5, 0, 10), (-1, 0, 10), (20, 0, 10), (3, 3, 3), (float("nan"), 0, 1)]]
out["wavg"] = [_wavg([(1, 10), (3, None), (2, 40)]), _wavg([(1, None)])]
out["approx_start"] = [str(_approx_start(date(2026, 10, 19), b)) for b in (0, 20, 252)]
save("metrics_common", out)
