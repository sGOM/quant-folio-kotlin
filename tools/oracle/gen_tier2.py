"""2층 정답지 — 실제 수정주가(price_ticks)로 단일종목·리밸런싱 백테스트. 산출물은 로컬 전용(gitignore)."""
import pandas as pd

from app.services.backtest import portfolio
from app.services.backtest.engine import run_backtest
from app.services.backtest.signals import requires_ohlc
from oracle_lib import save

df = pd.read_csv("/out/prices.csv", header=None, names=["symbol", "date", "open", "high", "low", "close", "volume"], dtype={"symbol": str})
df["date"] = pd.to_datetime(df["date"])
frames = {s: g.set_index("date")[["open", "high", "low", "close", "volume"]].astype(float) for s, g in df.groupby("symbol")}
symbols = sorted(frames)

single_cfgs = {
    "sma": {"type": "sma_crossover", "fast": 5, "slow": 20},
    "rsi_trail": {"type": "rsi", "period": 14, "lower": 30, "upper": 70, "trailing_stop_pct": 0.08, "stop_loss_pct": 0.12},
    "macd": {"type": "macd"},
    "bollinger": {"type": "bollinger", "period": 20},
    "volbrk_tp": {"type": "volatility_breakout", "take_profit_pct": 0.1},
    "momentum_volslip": {"type": "momentum", "lookback": 60, "slippage_vol_scale": 1.0},
}
single = []
for s in symbols[:10]:
    f = frames[s].loc["2021-01-01":]
    for name, cfg in single_cfgs.items():
        data = f if requires_ohlc(cfg) else f["close"]
        single.append({"symbol": s, "config": name, "result": run_backtest(data, cfg)})

panel = pd.DataFrame({s: frames[s]["close"] for s in symbols})
volume = pd.DataFrame({s: frames[s]["volume"] for s in symbols})
proxy = panel.pct_change().mean(axis=1).fillna(0).add(1).cumprod() * 1000  # 동일가중 지수(레짐·벤치 대용)
U = {"universe": symbols}
port_cfgs = {
    "momentum": {**U, "selection": {"method": "momentum", "lookback": 120, "top_n": 8}},
    "all_invvol_caps": {**U, "selection": {"method": "all"}, "weighting": "inverse_vol", "risk_layer": {"max_position_pct": 0.08, "target_vol": 0.15}},
    "score_regime": {**U, "selection": {"method": "score", "top_n": 10}, "regime_filter": {"enabled": True, "ma_period": 120}, "cadence": "weekly"},
    "adv_kill_limit": {**U, "selection": {"method": "momentum", "lookback": 60, "top_n": 5}, "adv_participation_cap": 0.05,
                       "price_limit_model": True, "risk_layer": {"mdd_kill_pct": 0.15}, "slippage_vol_scale": 0.5},
}
port = []
for name, cfg in port_cfgs.items():
    out = portfolio.run_rebalance_backtest(panel, cfg, "2021-01-04", panel.index[-1], regime_series=proxy, benchmark_series=proxy, volume_panel=volume)
    port.append({"config": name, "out": out})
    print(name, out["num_trades"], round(out["total_return"], 4))

save("tier2", {"single_configs": single_cfgs, "single": single, "port_configs": port_cfgs, "port": port, "proxy": list(proxy),
               "proxy_dates": [d.strftime("%Y-%m-%d") for d in proxy.index]})
