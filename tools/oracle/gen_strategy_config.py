"""schemas/strategy.py 정답지 — 검증·기본값 dump·오류 문구."""
from pydantic import TypeAdapter, ValidationError

from app.schemas.strategy import StrategyConfig
from oracle_lib import save

ta = TypeAdapter(StrategyConfig)
S = "005930"


def cond(left, op, right):
    return {"left": left, "op": op, "right": right}


price = {"kind": "price", "source": "close"}
sma20 = {"kind": "sma", "period": 20}
six = ["005930", "000660", "035420", "051910", "006400", "000270"]

inputs = {
    # 단일종목 17종 + 리밸런싱 최소 입력
    "sma_min": {"type": "sma_crossover", "symbol": S},
    "ema_min": {"type": "ema_crossover", "symbol": S},
    "rsi_min": {"type": "rsi", "symbol": S},
    "macd_min": {"type": "macd", "symbol": S},
    "bollinger_min": {"type": "bollinger", "symbol": S},
    "breakout_min": {"type": "breakout", "symbol": S},
    "momentum_min": {"type": "momentum", "symbol": S},
    "zscore_min": {"type": "zscore", "symbol": S},
    "disparity_min": {"type": "disparity", "symbol": S},
    "donchian_min": {"type": "donchian_squeeze", "symbol": S},
    "trix_min": {"type": "trix", "symbol": S},
    "obv_min": {"type": "obv_trend", "symbol": S},
    "atr_min": {"type": "atr_trailing", "symbol": S},
    "volbrk_min": {"type": "volatility_breakout", "symbol": S},
    "keltner_min": {"type": "keltner", "symbol": S},
    "stoch_min": {"type": "stochastic", "symbol": S},
    "custom_min": {
        "type": "custom", "symbol": S,
        "entry": {"children": [cond(price, ">", sma20)]},
        "exit": {"combinator": "or", "children": [
            cond(price, "<", sma20),
            {"children": [cond({"kind": "rsi", "period": 14}, ">", {"kind": "const", "value": 70})]},
        ]},
    },
    "rebalance_min": {"type": "rebalance", "universe": six},
    # 변환·정규화
    "sma_full": {"type": "sma_crossover", "symbol": " 005930", "fast": "10", "slow": 30.0, "cash": 5000000,
                 "stop_loss_pct": 0.1, "fill_mode": "same_close", "slippage_bps": 0, "extra_key": 1},
    "custom_legacy_list": {"type": "custom", "symbol": S, "entry": [cond(price, ">", sma20)], "exit": [cond(price, "<", sma20)]},
    "rebalance_full": {
        "type": "rebalance", "universe": [" 005930", "000660", "035420"], "cadence": "weekly", "rebalance_time": "9:5",
        "selection": {"method": "score", "top_n": 2, "factor_weights": {"momentum": 0.5, "value": 0.25, "lowvol": 0.25},
                      "vol_gate": {"spike_lookback": 10}, "neutralize": "sector"},
        "weighting": "score", "drift_band_pct": 0.1, "regime_filter": {}, "risk_layer": {"max_position_pct": 0.5},
        "panic_overlay": {"base_exposure": 0.5}, "financial_period": "ttm",
    },
    "rebalance_pit_empty_universe": {
        "type": "rebalance", "universe": [], "cadence": "quarterly",
        "selection": {"method": "score", "top_n": 20, "universe_rule": {"source": "KOSPI200", "pick": 200, "lookback": 60}},
    },
    "rebalance_custom": {"type": "rebalance", "universe": ["005930", "000660"],
                         "selection": {"method": "custom", "entry": [cond(price, ">", sma20)], "exit": [cond(price, "<", sma20)]}},
    # 오류
    "missing_type": {"symbol": S},
    "bad_type": {"type": "nope", "symbol": S},
    "missing_symbol": {"type": "rsi"},
    "short_symbol": {"type": "rsi", "symbol": "123"},
    "sma_fast_ge_slow": {"type": "sma_crossover", "symbol": S, "fast": 30, "slow": 20},
    "rsi_lower_ge_upper": {"type": "rsi", "symbol": S, "lower": 70, "upper": 30},
    "macd_fast_ge_slow": {"type": "macd", "symbol": S, "fast": 30, "slow": 26},
    "disparity_bad": {"type": "disparity", "symbol": S, "lower": 110, "upper": 105},
    "stoch_bad": {"type": "stochastic", "symbol": S, "lower": 90},
    "range_ge": {"type": "sma_crossover", "symbol": S, "fast": 0},
    "range_le": {"type": "rsi", "symbol": S, "period": 500},
    "range_gt": {"type": "bollinger", "symbol": S, "num_std": 0},
    "fees_le": {"type": "rsi", "symbol": S, "fees": 0.5},
    "bad_literal": {"type": "rsi", "symbol": S, "fill_mode": "open"},
    "bad_int": {"type": "rsi", "symbol": S, "period": "abc"},
    "float_for_int": {"type": "rsi", "symbol": S, "period": 14.5},
    "operand_missing_period": {"type": "custom", "symbol": S, "entry": [cond(price, ">", {"kind": "sma"})], "exit": [cond(price, "<", sma20)]},
    "operand_missing_source": {"type": "custom", "symbol": S, "entry": [cond({"kind": "price"}, ">", sma20)], "exit": [cond(price, "<", sma20)]},
    "operand_macd_bad": {"type": "custom", "symbol": S,
                         "entry": [cond({"kind": "macd_line", "fast": 30, "slow": 26, "signal": 9}, ">", {"kind": "const", "value": 0})],
                         "exit": [cond(price, "<", sma20)]},
    "empty_children": {"type": "custom", "symbol": S, "entry": {"children": []}, "exit": [cond(price, "<", sma20)]},
    "weights_sum": {"type": "rebalance", "universe": ["005930", "000660"],
                    "selection": {"method": "score", "top_n": 1, "factor_weights": {"momentum": 0.5}}},
    "universe_empty": {"type": "rebalance", "universe": []},
    "universe_dup": {"type": "rebalance", "universe": ["005930", "005930"]},
    "universe_bad_len": {"type": "rebalance", "universe": ["123"]},
    "topn_gt_universe": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 5}},
    "drift_too_big": {"type": "rebalance", "universe": ["005930", "000660", "035420"], "selection": {"top_n": 3}, "drift_band_pct": 0.4},
    "score_weight_needs_score": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 1}, "weighting": "score"},
    "custom_ohlc": {"type": "rebalance", "universe": ["005930", "000660"],
                    "selection": {"method": "custom", "entry": [cond({"kind": "price", "source": "high"}, ">", sma20)],
                                  "exit": [cond(price, "<", sma20)]}},
    "custom_missing_exit": {"type": "rebalance", "universe": ["005930", "000660"],
                            "selection": {"method": "custom", "entry": [cond(price, ">", sma20)]}},
    "maxpos_le_drift": {"type": "rebalance", "universe": ["005930", "000660", "035420", "051910"], "selection": {"top_n": 2},
                        "drift_band_pct": 0.3, "risk_layer": {"max_position_pct": 0.2}},
    "volgate_bad": {"type": "rebalance", "universe": ["005930", "000660"],
                    "selection": {"top_n": 1, "vol_gate": {"spike_lookback": 100, "base_lookback": 50}}},
    "panic_bad": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 1},
                  "panic_overlay": {"base_exposure": 0.9, "panic_exposure": 0.5}},
    "bad_time": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 1}, "rebalance_time": "25:00"},
    "bad_time_format": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 1}, "rebalance_time": "abc"},
    "pick_gt_universe": {"type": "rebalance", "universe": ["005930", "000660"], "selection": {"top_n": 1, "universe_rule": {"pick": 5}}},
    # 3회차 리뷰: Int 범위 밖 정수(랩어라운드 금지)·NaN/Inf 문자열(경계 비교에서 탈락)
    "int_overflow": {"type": "sma_crossover", "symbol": "005930", "fast": 4294967301, "slow": 4294967310},
    "int_overflow_str": {"type": "sma_crossover", "symbol": "005930", "fast": "4294967301"},
    "cash_nan_str": {"type": "sma_crossover", "symbol": "005930", "cash": "nan"},
    "fees_inf_str": {"type": "sma_crossover", "symbol": "005930", "fees": "inf"},
    "fees_neg_inf_str": {"type": "sma_crossover", "symbol": "005930", "fees": "-Infinity"},
}

cases = []
for name, inp in inputs.items():
    try:
        m = ta.validate_python(inp)
        cases.append({"name": name, "input": inp, "ok": True, "dump": m.model_dump()})
    except ValidationError as e:
        cases.append({
            "name": name, "input": inp, "ok": False,
            "errors": [{"loc": list(x["loc"]), "msg": x["msg"], "type": x["type"]} for x in e.errors()],
        })
save("strategy_config", {"cases": cases})
print(len(cases), "cases,", sum(c["ok"] for c in cases), "ok")
