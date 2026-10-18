"""리밸런싱 백테스트 정답지 — 부품(rebalance_parts.json)과 엔진 시나리오(portfolio.json)."""
from datetime import datetime

import numpy as np
import pandas as pd

from app.services.backtest import attribution, portfolio, risk_caps, slippage
from engine import rebalance as rb
from oracle_lib import save

rs = np.random.RandomState(11)
N = 520
idx = pd.bdate_range("2023-01-02", periods=N)
SYMS = [f"{i:06d}" for i in range(10, 140, 10)]  # 13종목


def path(n, start, vol, drift):
    return start * np.exp(np.cumsum(rs.normal(drift, vol, n)))


closes = {}
for k, s in enumerate(SYMS):
    closes[s] = path(N, [3000, 8000, 15000, 42000, 120000, 260000, 650000][k % 7], 0.012 + 0.002 * (k % 5), 0.0004 * ((k % 4) - 1))
panel = pd.DataFrame(closes, index=idx)
panel.iloc[350:372, 10] = np.nan            # interior gap(재개)
panel.iloc[440:, 11] = np.nan               # terminal gap(상장폐지)
panel.iloc[300, 3] = panel.iloc[299, 3] * 0.70  # 하한가 근처 급락
panel.iloc[:120, 12] = np.nan               # 신규상장(이력 짧음)
for c in range(6):                          # 320일 6종목 하한가(호가 라운딩된 정확한 하한) — 같은 날 킬스위치 청산이 막혀야 한다
    panel.iloc[320, c] = portfolio._round_to_tick(panel.iloc[319, c] * 0.7)
volume = pd.DataFrame(rs.randint(100, 50_000, size=(N, len(SYMS))).astype(float), index=idx, columns=SYMS)
regime = pd.Series(path(N, 2500, 0.009, 0.0), index=idx)
regime.iloc[330:380] *= np.linspace(1.0, 0.82, 50)
regime.iloc[380:] *= 0.82
bench = pd.Series(path(N, 330, 0.01, 0.0002), index=idx)

# 패닉 시계열(지수 종가 경로 + 라벨). 단일 사건: 360일 panic → 이후 반등.
pc = regime.copy()
plow = pc * 0.99
lv = pd.Series("normal", index=idx)
lv.iloc[358:361] = ["warning", "panic", "panic"]
hard = pd.Series(False, index=idx)
hard.iloc[359] = True
pc.iloc[361:380] = pc.iloc[358] * np.linspace(1.02, 1.15, 19)
panic_df = pd.DataFrame({"score": 0.0, "level": lv, "gated": False, "hard_trigger": hard, "close": pc, "low": plow, "dd60": -0.05}, index=idx)

FUND = {s: {"PER": [8.0, -3.0, 15.0, 30.0, 11.0][k % 5], "PBR": 0.5 + 0.2 * k, "DIV": [0.0, 1.5, 3.0][k % 3],
            "market_cap": 1e11 * (k + 1), "sector": ["A", "B", "C"][k % 3]} for k, s in enumerate(SYMS)}


def fund_provider(d, codes):
    return pd.DataFrame({c: FUND[c] for c in codes if c in FUND}).T


def pool_provider(d):
    return SYMS[:9] if d < idx[400] else SYMS[4:]


SIM = (idx[280], idx[-1])
BASE = {"universe": SYMS, "selection": {"method": "momentum", "lookback": 60, "top_n": 4}, "cadence": "monthly", "rebalance_dom": 1}
SECTOR = {s: FUND[s]["sector"] for s in SYMS}
scenarios = [
    ("momentum_default", BASE, {}),
    ("same_close", {**BASE, "fill_mode": "same_close"}, {}),
    ("weekly_no_int", {**BASE, "cadence": "weekly", "rebalance_weekday": 2, "integer_shares": False, "drift_band_pct": 0.02}, {}),
    ("quarterly_dom31", {**BASE, "cadence": "quarterly", "rebalance_dom": 31}, {}),
    ("all_inverse_vol", {**BASE, "selection": {"method": "all"}, "weighting": "inverse_vol"}, {}),
    ("score", {**BASE, "selection": {"method": "score", "top_n": 5, "factor_weights": {"momentum": 0.5, "value": 0.3, "lowvol": 0.2}}}, {"fund": True}),
    ("score_rank_neutral", {**BASE, "selection": {"method": "score", "top_n": 5, "neutralize": "size_sector", "min_score": -0.5}, "weighting": "score"}, {"fund": True}),
    ("score_vol_gate", {**BASE, "selection": {"method": "score", "top_n": 6, "vol_gate": {"spike_lookback": 20, "base_lookback": 120, "spike_min": 0.8, "cap": 0.9, "require_uptrend": False}}}, {"fund": True}),
    ("custom", {**BASE, "selection": {"method": "custom", "entry": {"combinator": "and", "children": [{"left": {"kind": "price"}, "op": ">", "right": {"kind": "sma", "period": 20}}]},
                                       "exit": [{"left": {"kind": "price"}, "op": "<", "right": {"kind": "sma", "period": 20}}]}}, {}),
    ("regime_buffers", {**BASE, "regime_filter": {"enabled": True, "ma_period": 60, "exit_buffer_pct": 0.02, "reentry_buffer_pct": 0.01}}, {"regime": True}),
    ("mdd_kill", {**BASE, "risk_layer": {"mdd_kill_pct": 0.06, "mdd_rearm_days": 15}}, {}),
    ("risk_caps", {**BASE, "selection": {"method": "all"}, "risk_layer": {"max_position_pct": 0.12, "max_sector_pct": 0.4, "target_vol": 0.12, "vol_lookback": 30},
                   "_sector_map": SECTOR}, {}),
    ("adv_slip_limit", {**BASE, "adv_participation_cap": 0.0005, "slippage_bps": 10, "slippage_vol_scale": 0.5, "price_limit_model": True}, {"volume": True}),
    ("panic_overlay_A", {**BASE, "regime_filter": {"enabled": True, "ma_period": 60}, "panic_overlay": {"enabled": True, "arm_window": 5, "hold_days": 15}}, {"regime": True, "panic": True}),
    ("panic_overlay_B", {**BASE, "panic_overlay": {"enabled": True, "event_only": True, "knife_stop_pct": 0.0, "profit_reclaim_pct": 0.3}}, {"panic": True}),
    ("limit_kill", {**BASE, "selection": {"method": "all"}, "fill_mode": "same_close", "price_limit_model": True,
                    "risk_layer": {"mdd_kill_pct": 0.08, "mdd_rearm_days": 10}}, {}),
    ("pool_rule_bench", {**BASE, "selection": {"method": "momentum", "lookback": 40, "top_n": 3, "universe_rule": {"lookback": 30, "pick": 5}}, "risk_free_rate": 0.03,
                         "delisting_recovery": 0.5}, {"pool": True, "bench": True}),
]
runs = []
for name, cfg, opt in scenarios:
    out = portfolio.run_rebalance_backtest(
        panel, cfg, *SIM,
        fundamentals_provider=fund_provider if opt.get("fund") else None,
        regime_series=regime if opt.get("regime") else None,
        pool_provider=pool_provider if opt.get("pool") else None,
        benchmark_series=bench if opt.get("bench") else None,
        panic_series=panic_df if opt.get("panic") else None,
        volume_panel=volume if opt.get("volume") else None,
    )
    runs.append({"name": name, "config": cfg, "opt": opt, "out": out})
    print(name, out["num_trades"], round(out["total_return"], 4), out["num_kills"], out["num_panic_events"], len(out["factor_ic"]))

save("portfolio", {
    "dates": [d.strftime("%Y-%m-%d") for d in idx], "panel": {s: list(panel[s]) for s in SYMS},
    "volume": {s: list(volume[s]) for s in SYMS}, "regime": list(regime), "bench": list(bench),
    "panic": {"level": list(lv), "hard_trigger": list(hard), "close": list(pc), "low": list(plow)},
    "fund": FUND, "pool_switch": idx[400].strftime("%Y-%m-%d"), "sim": [SIM[0].strftime("%Y-%m-%d"), SIM[1].strftime("%Y-%m-%d")],
    "runs": runs,
})

# ── 부품 ──
parts = {}
parts["cap_normalize"] = [{"raw": r, "lo": lo, "hi": hi, "out": rb.cap_normalize_weights(r, lo, hi)} for r, lo, hi in [
    ({"a": 1.0, "b": 2.0, "c": 3.0}, 0.005, 0.15), ({f"s{i}": 1.0 / (i + 1) for i in range(12)}, 0.005, 0.15),
    ({f"s{i}": float(i + 1) ** 2 for i in range(20)}, 0.01, 0.1), ({"a": 0.0, "b": 0.0}, 0.0, 1.0), ({"x": 5.0}, 0.005, 0.15)]]
parts["orders"] = [{"targets": t, "positions": p, "prices": pr, "capital": c, "band": b, "out": [list(o) for o in rb.compute_rebalance_orders(t, p, pr, c, b)]} for t, p, pr, c, b in [
    ({"a": 0.5, "b": 0.5}, {"a": 10, "c": 5}, {"a": 1000.0, "b": 2000.0, "c": 500.0}, 100_000.0, 0.05),
    ({"a": 0.52, "b": 0.48}, {"a": 50, "b": 24}, {"a": 1000.0, "b": 2000.0}, 100_000.0, 0.05),
    ({"a": 0.3}, {"a": 0}, {"a": None}, 100_000.0, 0.0)]]
parts["period_key"] = [{"d": d, "cadence": c, "out": list(rb._period_key(datetime.fromisoformat(d), c)) if c != "daily" else d}
                       for d in ["2024-12-30", "2025-01-01", "2026-03-31", "2026-04-01"] for c in ["daily", "weekly", "monthly", "quarterly"]]
gate_series = {s: panel[s].dropna() for s in SYMS[:6]}
parts["vol_gate"] = [{"symbol": s, "gate": g, "out": rb.passes_vol_gate(gate_series[s], g)} for s in SYMS[:6]
                     for g in [{"spike_lookback": 20, "base_lookback": 120, "spike_min": 0.9, "cap": 0.9}, {"spike_lookback": 10, "base_lookback": 60, "spike_min": 0.5, "spike_max": 1.5, "cap": 0.5, "require_uptrend": True}]]
hist = panel.iloc[:300]
parts["risk"] = []
for tg in [{s: 1 / 6 for s in SYMS[:6]}, {SYMS[0]: 0.5, SYMS[1]: 0.3, SYMS[2]: 0.2}, {s: w for s, w in zip(SYMS[:8], [0.3, 0.2, 0.1, 0.1, 0.1, 0.1, 0.05, 0.05])}]:
    parts["risk"].append({"targets": tg,
                          "pos": risk_caps._cap_position_weights(tg, 0.15), "sector": risk_caps._cap_sector_weights(tg, {**SECTOR, SYMS[7]: None}, 0.45),
                          "vol": risk_caps._portfolio_vol_ann(hist, tg, 20),
                          "all": risk_caps._apply_risk_caps(tg, hist, {"max_position_pct": 0.2, "max_sector_pct": 0.5, "target_vol": 0.1, "max_leverage": 1.5}, SECTOR)})
parts["slip"] = slippage._vol_slippage_map(hist, SYMS, 0.0005, 0.7)
snaps = []
for k, d in enumerate(idx[300:500:20]):
    snaps.append((d, pd.DataFrame({c: rs.normal(0, 1, len(SYMS)) for c in attribution._FACTOR_SCORE_COLS[:3] + ("score",)}, index=SYMS)))
parts["attribution"] = {"snaps": [{"d": d.strftime("%Y-%m-%d"), "frame": {c: list(f[c]) for c in f.columns}} for d, f in snaps],
                        "out": attribution._factor_attribution(snaps, panel)}
rets = rs.normal(0.0004, 0.01, 200)
brets = rs.normal(0.0003, 0.009, 200)
brets[[0, 50]] = np.nan
parts["radj"] = {"rets": list(rets), "bench": list(brets), "out": attribution._risk_adjusted_metrics(rets, brets, 0.03),
                 "out_nobench": attribution._risk_adjusted_metrics(rets, None, 0.0)}
save("rebalance_parts", parts)
