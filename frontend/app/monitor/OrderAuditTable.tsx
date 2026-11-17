"use client";

import { HelpCircle } from "lucide-react";
import type { OrderRow } from "@/lib/api";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { formatNumber } from "@/lib/format";

/** 주문 상태별 표시 라벨·색조(초록=진행/체결, 빨강=거부/취소)·툴팁 설명.
 *  tone 은 운영 상태 축이다 — 손익색(profit=적/loss=청)이 아니라 신호등 규칙(ok=초록/bad=적). */
const ORDER_STATUS: Record<
  string,
  { label: string; tone: "ok" | "bad" | "muted"; desc: string }
> = {
  pending: {
    label: "대기",
    tone: "muted",
    desc: "엔진이 주문을 생성했고 증권사(KIS/토스)로 전송하기 직전 상태입니다.",
  },
  submitted: {
    label: "접수",
    tone: "ok",
    desc: "증권사가 주문을 정상 접수했습니다. 아직 체결 전이며 곧 체결·미체결이 확정됩니다.",
  },
  partial: {
    label: "일부체결",
    tone: "ok",
    desc: "주문 수량 중 일부만 체결되었습니다. 나머지 수량은 계속 체결을 기다립니다.",
  },
  filled: {
    label: "체결완료",
    tone: "ok",
    desc: "주문 수량이 전량 체결되어 포지션·잔고에 반영되었습니다.",
  },
  cancelled: {
    label: "취소",
    tone: "bad",
    desc: "주문이 취소되어 체결되지 않았습니다.",
  },
  rejected: {
    label: "거부",
    tone: "bad",
    desc: "증권사가 주문을 거부했습니다(잔고 부족·호가 이탈·장 상태 등). 체결되지 않았습니다.",
  },
};

/**
 * 주문 감사 로그 테이블.
 * 각 주문이 '어떤 신호·공식·리스크·리밸런싱 기준'으로 나갔는지(reason)를 상세 열로 보여주고,
 * 상태는 색조 배지(초록=진행/체결, 빨강=거부/취소)와 툴팁 설명으로 표기한다.
 */
export function OrderAuditTable({
  orders,
  nameOf,
}: {
  orders?: OrderRow[];
  nameOf: (code?: string | null) => string;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full min-w-[52rem] text-sm">
        <thead className="bg-muted/50 text-xs text-muted-foreground">
          <tr>
            {["시각", "종목", "구분", "수량", "상태", "사유"].map((h) => (
              <th key={h} className="px-3 py-2 text-left font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {!orders || orders.length === 0 ? (
            <tr>
              <td colSpan={6} className="px-3 py-6 text-center text-muted-foreground">
                주문 내역이 없습니다.
              </td>
            </tr>
          ) : (
            orders.map((o) => {
              const st = ORDER_STATUS[o.status] ?? {
                label: o.status,
                tone: "muted" as "ok" | "bad" | "muted",
                desc: "알 수 없는 상태입니다.",
              };
              return (
                <tr
                  key={o.id}
                  className="border-t border-border/60 align-top transition-colors hover:bg-accent/30"
                >
                  <td className="whitespace-nowrap px-3 py-2 tabular-nums text-muted-foreground">
                    {new Date(o.created_at).toLocaleString("ko-KR")}
                  </td>
                  <td className="whitespace-nowrap px-3 py-2">{nameOf(o.symbol)}</td>
                  <td className="px-3 py-2">
                    <span
                      className={cn(
                        "font-medium",
                        o.side === "buy" ? "text-profit" : "text-loss",
                      )}
                    >
                      {o.side === "buy" ? "매수" : "매도"}
                    </span>
                  </td>
                  <td className="px-3 py-2 tabular-nums">{formatNumber(o.qty)}</td>
                  <td className="px-3 py-2">
                    <Tooltip>
                      <TooltipTrigger asChild>
                        <span
                          tabIndex={0}
                          className={cn(
                            "inline-flex cursor-help items-center gap-1 rounded-full border px-2 py-0.5 text-xs font-medium",
                            st.tone === "ok"
                              ? "border-status-ok/30 bg-status-ok/10 text-status-ok"
                              : st.tone === "bad"
                                ? "border-status-bad/30 bg-status-bad/10 text-status-bad"
                                : "border-border bg-muted text-muted-foreground",
                          )}
                        >
                          {st.label}
                          <HelpCircle className="h-3 w-3 opacity-60" />
                        </span>
                      </TooltipTrigger>
                      <TooltipContent>{st.desc}</TooltipContent>
                    </Tooltip>
                  </td>
                  <td className="px-3 py-2 text-xs leading-relaxed text-muted-foreground">
                    {o.reason ?? (
                      <span className="text-muted-foreground/50">—</span>
                    )}
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </div>
  );
}
