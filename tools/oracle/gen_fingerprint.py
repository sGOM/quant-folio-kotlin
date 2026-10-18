"""리밸런싱 백테스트 universe_fingerprint 정답지 — Python json.dumps(sort_keys) 직렬화 그대로여야 DSR 동질 집합이 이어진다."""
from app.api.routes.backtests import _universe_fingerprint, _factor_coverage_warnings
import json
from oracle_lib import save

cases = [
    (["005930", "000660"], None),
    (["000660", "005930", "035420"], {"source": "KOSPI200", "lookback": 120, "pick": 20, "min_market_cap": None}),
    ([], {"source": "fixed", "min_market_cap": 500.0, "flag": True, "nested": {"b": [1, 2.5, "가"], "a": "é\"\/\n"}}),
    (["900001"], {"ratio": 1e-7, "big": 1e16, "neg": -0.0, "x": 0.1}),
]
out = [{"universe": u, "rule": r, "dumps": json.dumps({"universe": sorted(u), "universe_rule": r or {}}, sort_keys=True, default=str), "fp": _universe_fingerprint(u, r)} for u, r in cases]
save("fingerprint", out)
print([c["fp"] for c in out])
