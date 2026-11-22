import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import type { ReactNode } from "react";
import { api, ApiError, type FillQualityReport, type Strategy } from "@/lib/api";
import MonitorPage from "../page";

// 인증·내비·WS·전략 요약은 이 테스트의 관심사가 아니다 — 페이지 본문만 렌더한다.
vi.mock("@/components/Nav", () => ({ Nav: () => null }));
vi.mock("@/components/RequireAuth", () => ({
  RequireAuth: ({ children }: { children: ReactNode }) => children,
}));
vi.mock("@/lib/useWebSocket", () => ({ useEventSocket: () => {} }));
vi.mock("@/lib/strategy", () => ({ summarizeConfig: () => "요약" }));

function strategy(status: Strategy["status"]): Strategy {
  return {
    id: 7,
    name: "테스트전략",
    description: null,
    config: {} as Strategy["config"],
    status,
    is_favorite: false,
    sort_order: 0,
    created_at: "2026-01-01T00:00:00Z",
    updated_at: "2026-01-01T00:00:00Z",
  };
}

const metric = { mean: 1, median: 1, n: 10 } as unknown as FillQualityReport["m1_exec"]["all"];
const report = {
  sample: { n_orders: 50, n_skipped: 0, n_clusters: 0, n_arrival_fallback: 0, min_sample: 30, note: "" },
  assumptions: { backtest_slip_bps: 5, slip_base_bps: 5, slip_vol_scale: 1, annual_turnover: null },
  m1_exec: { all: metric },
  m2_time: { all: metric },
  m3_total: { all: metric },
  checksum: { mean_abs_residual_bp: null, note: "" },
  annualized_drag: { drag_abs_pct_per_yr: null, drag_diff_pct_per_yr: null },
  grades: { m1_exec: "GREEN", m3_total: "GREEN" },
  window: { date_from: "", date_to: "", strategy_id: null, turnover_estimated: false },
  calibration: {
    current_bps: 5,
    proposed_bps: 8,
    observed_median_bps: 8.2,
    sample_size: 50,
    clamped: false,
    reason: "실측이 가정보다 큼",
  },
} as unknown as FillQualityReport;

function mockApi(status: Strategy["status"]) {
  vi.spyOn(api, "me").mockResolvedValue({
    id: 1, email: "o@example.com", broker: "kis", kis_account_no: null,
    has_kis_credentials: true, has_toss_quote: false,
  });
  const list = vi.spyOn(api, "listStrategies").mockResolvedValue([strategy(status)]);
  vi.spyOn(api, "positions").mockResolvedValue([]);
  vi.spyOn(api, "orders").mockResolvedValue([]);
  vi.spyOn(api, "engineStatus").mockResolvedValue({ engine_alive: true } as never);
  vi.spyOn(api, "getStrategiesHealth").mockResolvedValue({ threshold: 3, strategies: [] });
  vi.spyOn(api, "getFillQuality").mockResolvedValue(report);
  vi.spyOn(api, "symbolNames").mockResolvedValue({});
  return list;
}

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MonitorPage />
    </QueryClientProvider>,
  );
}

describe("MonitorPage 전략 시작/중지", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  it("정지된 전략의 '시작' 클릭은 startStrategy(id) 를 호출한다", async () => {
    mockApi("stopped" as Strategy["status"]);
    const start = vi.spyOn(api, "startStrategy").mockResolvedValue({ status: "ok" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "시작" }));
    await waitFor(() => expect(start).toHaveBeenCalledWith(7));
  });

  it("live 전략의 '중지' 클릭은 stopStrategy(id) 를 호출한다", async () => {
    mockApi("live");
    const stop = vi.spyOn(api, "stopStrategy").mockResolvedValue({ status: "ok" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "중지" }));
    await waitFor(() => expect(stop).toHaveBeenCalledWith(7));
  });

  it("시작 API 가 실패하면 서버 오류 문구를 보이고 버튼은 복구되며 전략 목록은 다시 조회되지 않는다", async () => {
    const list = mockApi("stopped" as Strategy["status"]);
    const start = vi.spyOn(api, "startStrategy").mockRejectedValue(new ApiError("엔진 오류", 500));
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "시작" }));
    await waitFor(() => expect(start).toHaveBeenCalled());
    expect(await screen.findByRole("alert")).toHaveTextContent("엔진 오류");
    expect(await screen.findByRole("button", { name: "시작" })).toBeEnabled();
    expect(list).toHaveBeenCalledTimes(1);
  });

  it("중지 API 가 실패하면 오류 문구를 보이고 다음 시도가 시작되면 지운다", async () => {
    const list = mockApi("live");
    const stop = vi
      .spyOn(api, "stopStrategy")
      .mockRejectedValueOnce(new Error("network"))
      .mockResolvedValue({ status: "ok" });
    renderPage();
    fireEvent.click(await screen.findByRole("button", { name: "중지" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("전략 중지에 실패했습니다");
    expect(await screen.findByRole("button", { name: "중지" })).toBeEnabled();
    expect(list).toHaveBeenCalledTimes(1);
    fireEvent.click(screen.getByRole("button", { name: "중지" }));
    await waitFor(() => expect(stop).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(screen.queryByRole("alert")).toBeNull());
  });
});

describe("MonitorPage 슬리피지 캘리브레이션 승인", () => {
  beforeEach(() => localStorage.clear());
  afterEach(() => vi.restoreAllMocks());

  async function selectStrategyAndApply() {
    renderPage();
    // 전체 보기에서는 승인 버튼이 없다 — 대상 전략을 고른 뒤에만 노출.
    expect(await screen.findByText(/슬리피지 캘리브레이션 제안/)).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "제안 적용" })).toBeNull();
    const select = screen.getByRole("combobox");
    await screen.findByRole("option", { name: "테스트전략" });
    fireEvent.change(select, { target: { value: "7" } });
    fireEvent.click(await screen.findByRole("button", { name: "제안 적용" }));
  }

  it("승인 시 confirm 후 applySlippageCalibration(id, proposed_bps) 를 호출하고 결과를 안내한다", async () => {
    mockApi("stopped" as Strategy["status"]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    const apply = vi.spyOn(api, "applySlippageCalibration").mockResolvedValue({
      strategy_id: 7, previous_bps: 5, applied_bps: 8, observed_median_bps: 8.2,
      sample_size: 50, clamped: false, reason: "",
    });
    await selectStrategyAndApply();
    await waitFor(() => expect(apply).toHaveBeenCalledWith(7, 8));
    expect(
      await screen.findByText("반영 완료: slippage_bps 5bp → 8bp (표본 50건)"),
    ).toBeInTheDocument();
  });

  it("409 응답이면 '리포트가 갱신되어' 안내를 보이고 리포트를 다시 조회한다", async () => {
    mockApi("stopped" as Strategy["status"]);
    vi.spyOn(window, "confirm").mockReturnValue(true);
    vi.spyOn(api, "applySlippageCalibration").mockRejectedValue(new ApiError("불일치", 409));
    await selectStrategyAndApply();
    expect(
      await screen.findByText("리포트가 갱신되어 제안이 달라졌습니다. 새로고침 후 다시 시도하세요."),
    ).toBeInTheDocument();
    await waitFor(() => expect(vi.mocked(api.getFillQuality).mock.calls.length).toBeGreaterThan(2));
  });
});
