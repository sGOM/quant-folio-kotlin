"""backtest/signals.py 정답지 — 전략 16종 + 사용자 정의 조건의 진입·청산 신호, 최소기간, 설명 문구."""
import numpy as np
import pandas as pd

from app.services.backtest import signals as sig
from oracle_lib import save


def ohlcv(seed, n=260, plateau=False):
    rs = np.random.RandomState(seed)
    idx = pd.bdate_range("2021-01-04", periods=n)
    close = 50000 * np.exp(np.cumsum(rs.normal(0, 0.018, n)))
    if plateau:
        close = np.round(close / 500) * 500  # 호가 단위처럼 동점이 많은 경로
    open_ = close * (1 + rs.normal(0, 0.006, n))
    high = np.maximum(open_, close) * (1 + np.abs(rs.normal(0, 0.008, n)))
    low = np.minimum(open_, close) * (1 - np.abs(rs.normal(0, 0.008, n)))
    vol = rs.randint(10_000, 2_000_000, n).astype(float)
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close, "volume": vol}, index=idx)


def frame_json(df):
    return {"index": [d.strftime("%Y-%m-%d") for d in df.index], **{c: list(df[c]) for c in df.columns}}


price = {"kind": "price", "source": "close"}
configs = {
    "sma": {"type": "sma_crossover", "fast": 5, "slow": 20},
    "sma_long": {"type": "sma_crossover", "fast": 20, "slow": 60},
    "ema": {"type": "ema_crossover"},
    "rsi": {"type": "rsi"},
    "rsi_tight": {"type": "rsi", "period": 7, "lower": 40, "upper": 60},
    "macd": {"type": "macd"},
    "bollinger": {"type": "bollinger"},
    "bollinger_1": {"type": "bollinger", "period": 10, "num_std": 1.0},
    "breakout": {"type": "breakout"},
    "momentum": {"type": "momentum", "lookback": 20},
    "zscore": {"type": "zscore", "entry": 1.0},
    "disparity": {"type": "disparity", "lower": 97, "upper": 103},
    "donchian": {"type": "donchian_squeeze"},
    "trix": {"type": "trix", "period": 5, "signal_period": 3},
    "atr": {"type": "atr_trailing", "period": 10, "atr_period": 10, "k": 2.0},
    "volbrk": {"type": "volatility_breakout"},
    "keltner": {"type": "keltner", "mult": 1.0},
    "stoch": {"type": "stochastic", "lower": 30, "upper": 70},
    "obv": {"type": "obv_trend"},
    "custom_and": {"type": "custom",
                   "entry": {"combinator": "and", "children": [
                       {"left": price, "op": ">", "right": {"kind": "sma", "period": 20}},
                       {"left": {"kind": "rsi", "period": 14}, "op": "<", "right": {"kind": "const", "value": 70}}]},
                   "exit": [{"left": price, "op": "<", "right": {"kind": "ema", "period": 10}}]},
    "custom_or_nested": {"type": "custom",
                         "entry": {"combinator": "or", "children": [
                             {"left": {"kind": "macd_line", "fast": 12, "slow": 26, "signal": 9}, "op": ">=",
                              "right": {"kind": "macd_signal", "fast": 12, "slow": 26, "signal": 9}},
                             {"combinator": "and", "children": [
                                 {"left": {"kind": "price", "source": "high"}, "op": ">", "right": {"kind": "sma", "period": 5}},
                                 {"left": {"kind": "price", "source": "volume"}, "op": ">", "right": {"kind": "const", "value": 1000000}}]}]},
                         "exit": {"combinator": "and", "children": [
                             {"left": price, "op": "<=", "right": {"kind": "sma", "period": 5}}]}},
}

datasets = {"walk": ohlcv(3), "plateau": ohlcv(5, plateau=True)}
cases = []
for dname, df in datasets.items():
    for cname, cfg in configs.items():
        data = df if sig.requires_ohlc(cfg) else df["close"]
        entries, exits = sig.generate_signals(data, cfg)
        case = {
            "data": dname, "config": cname, "requires_ohlc": sig.requires_ohlc(cfg), "min_periods": sig.min_periods(cfg),
            "entries": [bool(x) for x in entries], "exits": [bool(x) for x in exits],
            "latest": sig.latest_signal(data, cfg),
            "explain_buy": sig.explain_signal(data, cfg, "buy"), "explain_sell": sig.explain_signal(data, cfg, "sell"),
        }
        if cfg["type"] == "custom":
            case["position_state"] = list(sig.custom_position_state(data, cfg))
        cases.append(case)

save("signals", {"datasets": {k: frame_json(v) for k, v in datasets.items()}, "configs": configs, "cases": cases})
print(len(cases), "cases")
