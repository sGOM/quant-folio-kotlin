"""backtest/engine.py 정답지 — vectorbt from_signals 재현용.

1) sim: 무작위 진입·청산 배열(같은 봉 충돌·중복 진입 포함) × 손절/익절/트레일링/수수료/슬리피지 조합으로
   vbt.Portfolio.from_signals 를 직접 호출한 결과(자산곡선·지표·거래).
2) run: 원본 run_backtest(config) 를 전략 유형·체결 방식·슬리피지 모델별로 호출한 결과 전체.
"""
import numpy as np
import pandas as pd
import vectorbt as vbt

from app.services.backtest.engine import run_backtest
from oracle_lib import save


def ohlcv(seed, n=160):
    rs = np.random.RandomState(seed)
    idx = pd.bdate_range("2022-01-03", periods=n)
    close = 30000 * np.exp(np.cumsum(rs.normal(0, 0.02, n)))
    open_ = close * (1 + rs.normal(0, 0.006, n))
    high = np.maximum(open_, close) * (1 + np.abs(rs.normal(0, 0.008, n)))
    low = np.minimum(open_, close) * (1 - np.abs(rs.normal(0, 0.008, n)))
    vol = rs.randint(10_000, 2_000_000, n).astype(float)
    return pd.DataFrame({"open": open_, "high": high, "low": low, "close": close, "volume": vol}, index=idx)


def series_json(s):
    return [float(x) for x in s]


sim_cases = []
rs = np.random.RandomState(42)
for k in range(80):
    df = ohlcv(100 + k, n=120)
    close = df["close"]
    p_entry, p_exit = rs.choice([0.03, 0.08, 0.2]), rs.choice([0.03, 0.08, 0.2])
    entries = pd.Series(rs.rand(len(close)) < p_entry, index=close.index)
    exits = pd.Series(rs.rand(len(close)) < p_exit, index=close.index)
    params = {
        "init_cash": float(rs.choice([1_000_000, 10_000_000])),
        "fees": float(rs.choice([0.0, 0.00015, 0.00115])),
        "slippage": float(rs.choice([0.0, 0.0005, 0.002])),
        "sl_stop": None, "sl_trail": False, "tp_stop": None,
    }
    mode = k % 4
    if mode == 1:
        params["sl_stop"] = float(rs.choice([0.03, 0.07]))
    elif mode == 2:
        params["sl_stop"] = float(rs.choice([0.03, 0.07]))
        params["sl_trail"] = True
    elif mode == 3:
        params["tp_stop"] = float(rs.choice([0.05, 0.1]))
        if rs.rand() < 0.5:
            params["sl_stop"] = 0.05
    vary_slip = k % 5 == 0
    slippage = pd.Series(rs.uniform(0, 0.003, len(close)), index=close.index) if vary_slip else params["slippage"]
    pf = vbt.Portfolio.from_signals(
        close, entries, exits, init_cash=params["init_cash"], fees=params["fees"], slippage=slippage, freq="1D",
        sl_stop=params["sl_stop"], sl_trail=params["sl_trail"], tp_stop=params["tp_stop"],
    )
    tr = pf.trades
    sim_cases.append({
        "close": series_json(close), "entries": [bool(x) for x in entries], "exits": [bool(x) for x in exits],
        "params": params, "slippage_series": series_json(slippage) if vary_slip else None,
        "value": series_json(pf.value()),
        "total_return": float(pf.total_return()), "max_drawdown": float(pf.max_drawdown()),
        "sharpe": float(pf.sharpe_ratio()), "sharpe_rf": float(pf.sharpe_ratio(risk_free=0.0001)),
        "sortino": float(pf.sortino_ratio(required_return=0.0001)),
        "num_trades": int(tr.count()), "win_rate": float(tr.win_rate()) if tr.count() > 0 else None,
        "orders": [[int(r["idx"]), float(r["size"]), float(r["price"]), float(r["fees"]), int(r["side"])] for r in pf.orders.values],
    })

frames = {"a": ohlcv(7), "b": ohlcv(8)}
run_configs = {
    "sma_next": {"type": "sma_crossover", "fast": 5, "slow": 20},
    "sma_same": {"type": "sma_crossover", "fast": 5, "slow": 20, "fill_mode": "same_close"},
    "sma_noslip": {"type": "sma_crossover", "fast": 5, "slow": 20, "slippage_bps": 0},
    "sma_volslip": {"type": "sma_crossover", "fast": 5, "slow": 20, "slippage_vol_scale": 1.5},
    "rsi_stop": {"type": "rsi", "period": 7, "lower": 40, "upper": 60, "stop_loss_pct": 0.05},
    "rsi_trail": {"type": "rsi", "period": 7, "lower": 40, "upper": 60, "trailing_stop_pct": 0.04, "stop_loss_pct": 0.1},
    "breakout_tp": {"type": "breakout", "period": 10, "take_profit_pct": 0.08, "risk_free_rate": 0.03},
    "macd_fees": {"type": "macd", "fees": 0.001, "tax": 0.003, "cash": 5_000_000},
    "volbrk": {"type": "volatility_breakout"},
    "stoch": {"type": "stochastic", "lower": 30, "upper": 70},
    "custom": {"type": "custom", "entry": [{"left": {"kind": "price", "source": "close"}, "op": ">", "right": {"kind": "sma", "period": 10}}],
               "exit": [{"left": {"kind": "price", "source": "close"}, "op": "<", "right": {"kind": "sma", "period": 10}}]},
}
from app.services.backtest.signals import requires_ohlc

run_cases = []
for fname, df in frames.items():
    for cname, cfg in run_configs.items():
        data = df if requires_ohlc(cfg) else df["close"]
        run_cases.append({"frame": fname, "config": cname, "result": run_backtest(data, cfg)})
try:
    run_backtest(frames["a"]["close"].iloc[:10], {"type": "sma_crossover"})
    short_err = None
except ValueError as e:
    short_err = str(e)

save("engine", {
    "sim": sim_cases,
    "frames": {k: {"index": [d.strftime("%Y-%m-%d") for d in v.index], **{c: series_json(v[c]) for c in v.columns}} for k, v in frames.items()},
    "configs": run_configs, "run": run_cases, "short_error": short_err,
})
print(len(sim_cases), "sim,", len(run_cases), "run")
