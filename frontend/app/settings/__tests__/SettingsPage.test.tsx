import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen } from "@testing-library/react";
import type { ReactNode } from "react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { api, type UserOut } from "@/lib/api";
import SettingsPage from "../page";

const { useCurrentUser } = vi.hoisted(() => ({ useCurrentUser: vi.fn() }));
vi.mock("@/lib/useAuth", () => ({ useCurrentUser }));
vi.mock("@/components/Nav", () => ({ Nav: () => null }));
vi.mock("@/components/RequireAuth", () => ({
  RequireAuth: ({ children }: { children: ReactNode }) => children,
}));
vi.mock("@/lib/api", () => ({ api: { kisHealth: vi.fn() } }));

const user: UserOut = {
  id: 1,
  email: "owner@example.com",
  broker: "kis",
  kis_account_no: "50012345-01",
  has_kis_credentials: true,
  has_toss_quote: true,
};

const renderSettings = () =>
  render(
    <QueryClientProvider client={new QueryClient()}>
      <SettingsPage />
    </QueryClientProvider>,
  );

describe("server-managed brokerage settings", () => {
  beforeEach(() => vi.clearAllMocks());

  it("shows the configured account without accepting credentials", () => {
    useCurrentUser.mockReturnValue({ data: user });
    const { container } = renderSettings();

    expect(screen.getByText("증권사 자격증명은 서버에서 관리합니다.")).toBeVisible();
    expect(screen.getByText("50012345-01")).toBeVisible();
    expect(screen.queryAllByRole("textbox")).toHaveLength(0);
    expect(container.querySelector('input[type="password"]')).toBeNull();
  });

  it("checks the broker connection on request", async () => {
    useCurrentUser.mockReturnValue({ data: user });
    vi.mocked(api.kisHealth).mockResolvedValue({
      broker: "kis", env: "vts", is_paper_trading: true, token_issued: true,
      message: "토큰 발급 성공 — 연동 정상",
    });
    renderSettings();

    expect(api.kisHealth).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "연결 확인" }));
    expect(await screen.findByText("토큰 발급 성공 — 연동 정상")).toBeVisible();
  });
});
