import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { describe, expect, it, vi } from "vitest";
import { api } from "@/lib/api";
import LoginPage from "../page";

const { replace } = vi.hoisted(() => ({ replace: vi.fn() }));
vi.mock("next/navigation", () => ({ useRouter: () => ({ replace }) }));
vi.mock("@/lib/api", () => ({ api: { login: vi.fn() } }));

describe("password-only login", () => {
  it("submits only the password and has no email or sign-up UI", async () => {
    vi.mocked(api.login).mockResolvedValue({
      id: 1,
      email: "owner@example.com",
      broker: "kis",
      kis_account_no: null,
      has_kis_credentials: false,
      has_toss_quote: false,
    });
    render(
      <QueryClientProvider client={new QueryClient()}>
        <LoginPage />
      </QueryClientProvider>,
    );

    expect(screen.queryByLabelText("이메일")).toBeNull();
    expect(screen.queryByRole("button", { name: /회원가입/ })).toBeNull();
    const password = screen.getByLabelText("비밀번호");
    expect(password).toHaveAttribute("type", "password");
    expect(password).toHaveAttribute("autocomplete", "current-password");

    fireEvent.change(password, { target: { value: "owner-secret" } });
    fireEvent.click(screen.getByRole("button", { name: "로그인" }));

    await waitFor(() => expect(replace).toHaveBeenCalledWith("/dashboard"));
    expect(api.login).toHaveBeenCalledWith("owner-secret");
  });
});
