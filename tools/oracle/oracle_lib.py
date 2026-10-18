"""정답지 공용 유틸 — 시드 데이터 생성과 NaN 안전 JSON 저장."""
import json
import math

import numpy as np
import pandas as pd


def enc(v):
    """JSON 값으로. NaN/±Inf 는 문자열로 보존한다(Kotlin 이 그대로 복원)."""
    if isinstance(v, (pd.Series, np.ndarray, list, tuple)):
        return [enc(x) for x in list(v)]
    if isinstance(v, dict):
        return {str(k): enc(x) for k, x in v.items()}
    if v is None:
        return None
    if isinstance(v, (bool, np.bool_)):
        return bool(v)
    if isinstance(v, (int, np.integer)):
        return int(v)
    if isinstance(v, (float, np.floating)):
        f = float(v)
        if math.isnan(f):
            return "NaN"
        if math.isinf(f):
            return "Infinity" if f > 0 else "-Infinity"
        return f
    if isinstance(v, (pd.Timestamp,)):
        return v.strftime("%Y-%m-%d")
    return v


def dates(n, start="2020-01-01"):
    return pd.bdate_range(start, periods=n)


def walks(seed=7, n=120):
    """정상·NaN 섞임·동점 많음 세 가지 가격 경로."""
    rs = np.random.RandomState(seed)
    idx = dates(n)
    normal = pd.Series(100 * np.exp(np.cumsum(rs.normal(0, 0.02, n))), index=idx)
    gappy = normal.copy()
    gappy.iloc[[p for p in (0, 5, 6, 7, 30, 61, 62, 100) if p < n]] = np.nan
    ties = pd.Series(np.round(rs.normal(0, 1, n)) + 10, index=idx)
    return {"normal": normal, "gappy": gappy, "ties": ties}


def save(name, payload):
    with open(f"/out/{name}.json", "w", encoding="utf-8") as f:
        json.dump(enc(payload), f, ensure_ascii=False, indent=1)
    print(f"wrote {name}.json")
