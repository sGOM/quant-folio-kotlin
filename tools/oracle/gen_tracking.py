"""트래킹(실측 NAV↔백테스트)·체결 정합(fill_quality)·슬리피지 캘리브레이션 정답지."""
import numpy as np
import pandas as pd

from app.services.backtest import fill_quality, slippage_calibration, tracking
from oracle_lib import save

rs = np.random.RandomState(5)
idx = pd.bdate_range("2025-03-03", periods=80)
SYMS = ["005930", "000660", "035420", "051910"]
panel = pd.DataFrame({s: 50000 * (k + 1) * np.exp(np.cumsum(rs.normal(0, 0.015, 80))) for k, s in enumerate(SYMS)}, index=idx)
panel.iloc[10:13, 1] = np.nan
panel.iloc[0:10, 2] = np.nan  # 체결(idx[7]) 시점에 종가 없음 → bfill 경로

# 체결: 주말·휴장일 체결(패널에 없는 날짜), 종가 없는 종목, 수수료 결측 포함
execs = []
for k in range(30):
    d = idx[3 + 2 * k] if k % 7 else idx[3 + 2 * k] + pd.Timedelta(days=1 if idx[3 + 2 * k].weekday() == 4 else 0)
    sym = SYMS[k % 4] if k % 9 else "999999"
    side = "buy" if k % 3 else "sell"
    e = {"date": d.strftime("%Y-%m-%d"), "symbol": sym, "side": side, "qty": float(1 + k % 5), "price": float(40000 + 1000 * k)}
    if k % 4:
        e["fee"] = 15.0 + k
    execs.append(e)

trk = []
for disp in [None, idx[30].strftime("%Y-%m-%d")]:
    curve, notes = tracking.reconstruct_realized_curve(execs, panel, initial_capital=10_000_000, display_from=disp)
    trk.append({"display_from": disp, "curve": curve, "notes": notes})
empty_panel_curve = tracking.reconstruct_realized_curve(execs[:3], pd.DataFrame(), initial_capital=1e6)
cash = tracking.replay_cash_balance(execs, 10_000_000)

real = trk[0]["curve"]
bt = [{"t": f"{d.strftime('%Y-%m-%d')}T00:00:00", "v": 1e7 * (1 + 0.001 * i + rs.normal(0, 0.002))} for i, d in enumerate(idx[::1])]
bt[5]["v"] = None
bt[6]["v"] = float("nan")
comp = tracking.compute_tracking(real, bt)
comp_empty = tracking.compute_tracking([], bt)

orders = []
for k in range(70):
    d = idx[5 + k % 70] if k < 66 else idx[-1]
    sym = SYMS[k % 4] if k % 11 else "999999"
    p = panel[sym].iloc[idx.get_loc(d)] if sym in panel.columns else 1000.0
    if p != p:
        p = 1000.0
    o = {"order_id": k, "symbol": sym, "side": "buy" if k % 2 else "sell", "fill_date": d.strftime("%Y-%m-%d"), "p_live": float(p * (1 + rs.normal(0.0003, 0.002)))}
    if k % 5:
        o["decision_price"] = float(p * (1 + rs.normal(0, 0.001)))
    orders.append(o)
sat = idx[20] + pd.Timedelta(days=(5 - idx[20].weekday()) % 7)  # 토요일 체결 → 직전 거래일 기준
orders.append({"order_id": 98, "symbol": SYMS[0], "side": "sell", "fill_date": sat.strftime("%Y-%m-%d"), "p_live": float(panel[SYMS[0]].iloc[20]), "decision_price": float(panel[SYMS[0]].iloc[20])})
orders.append({"order_id": 99, "symbol": SYMS[0], "side": "buy", "fill_date": "2025-03-01", "p_live": 1.0})  # 패널 이전
fq = [fill_quality.compute_fill_quality(orders, panel, slip_base=0.0005, slip_vol_scale=v, annual_turnover=t, min_sample=ms)
      for v, t, ms in [(0.0, None, 30), (0.7, 4.0, 30), (0.5, 2.0, 100)]]

cal = []
for rep in [fq[0], fq[1], {"m1_exec": {"all": {"n": 40, "median": 12.345}}, "assumptions": {"backtest_slip_bps": 5.0}},
            {"m1_exec": {"all": {"n": 40, "median": 5.4}}, "assumptions": {"backtest_slip_bps": 5.0}},
            {"m1_exec": {"all": {"n": 40, "median": -9.0}}, "assumptions": {"backtest_slip_bps": 2.0}},
            {"m1_exec": {"all": {"n": 10, "median": 20.0}}, "assumptions": {}}]:
    p = slippage_calibration.propose_slippage_calibration(rep)
    cal.append({"report": {"m1_exec": {"all": {"n": rep["m1_exec"]["all"]["n"], "median": rep["m1_exec"]["all"]["median"]}}, "assumptions": rep.get("assumptions", {})},
                "out": None if p is None else p.__dict__})

save("tracking", {"dates": [d.strftime("%Y-%m-%d") for d in idx], "panel": {s: list(panel[s]) for s in SYMS}, "execs": execs,
                  "reconstruct": trk, "empty_panel": {"curve": empty_panel_curve[0], "notes": empty_panel_curve[1]}, "cash": cash,
                  "bt": bt, "compare": comp, "compare_empty": comp_empty, "orders": orders, "fill_quality": fq, "calibration": cal})
print(comp["metrics"], [f["grades"] for f in fq], [c["out"] and c["out"]["proposed_bps"] for c in cal])
