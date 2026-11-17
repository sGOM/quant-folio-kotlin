"use client";

import { useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { AlertCircle, Info } from "lucide-react";
import { api, type MetricMarket, type PanicMarket, type PanicLevel, type PanicSignal } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";
import { fmtPct, fmtNum, formatNumber } from "@/lib/format";
import { fmtDate, SELECT_CLS, TableSkeleton, MarketBadge, ErrorState, EmptyState } from "@/app/metrics/TableParts";

// ─────────────────────────── 패닉셀 탭 ───────────────────────────

/** 경보 단계별 표시 메타(라벨·배지 색·설명). */
const PANIC_LEVEL_META: Record<
  PanicLevel,
  { label: string; badge: "muted" | "warning" | "destructive"; ring: string; desc: string }
> = {
  normal: {
    label: "정상",
    badge: "muted",
    ring: "border-border",
    desc: "통상적 등락 범위입니다. 특이 대응이 필요하지 않습니다.",
  },
  caution: {
    label: "주의",
    badge: "warning",
    ring: "border-amber-500/40",
    desc: "매도압력이 커지고 브레드스가 약화되고 있습니다. 조정 초입일 수 있어 추격매수·신규 레버리지를 자제하세요.",
  },
  warning: {
    label: "경계",
    badge: "warning",
    ring: "border-amber-500/60",
    desc: "강한 하락이나 투매(광범위+거래폭증)로 확증되진 않았습니다. 리스크 축소를 검토하되 반등 가능성도 열려 있습니다.",
  },
  panic: {
    label: "패닉·투매",
    badge: "destructive",
    ring: "border-destructive/60",
    desc: "무차별 투매·자본항복 국면입니다. 역설적으로 중장기 저점 부근일 수 있어, 투매 동참은 최악의 타이밍이 될 수 있습니다. 분할 대응·현금비중 관리 관점으로 보세요.",
  },
};

/** 시그널 원시값을 key에 맞는 단위로 포맷한다. */
function fmtSignalValue(sig: PanicSignal): string {
  if (sig.value == null) return "-";
  switch (sig.key) {
    case "r1":
    case "r5":
    case "dd60":
    case "bdr":
    case "cr5":
    case "cr10":
      return fmtPct(sig.value);
    case "vr":
      return `${sig.value.toFixed(2)}×`;
    default: // z, clv
      return fmtNum(sig.value, 2);
  }
}

export function PanicTab() {
  const [market, setMarket] = useState<MetricMarket>("ALL");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["metrics", "panic", market],
    queryFn: () => api.getPanic(market),
    staleTime: 5 * 60 * 1000,
  });

  return (
    <div>
      {/* 필터 바 */}
      <div className="mb-4 flex flex-wrap items-center gap-3">
        <label className="flex items-center gap-1.5 text-sm">
          <span className="text-muted-foreground">시장</span>
          <select
            value={market}
            onChange={(e) => setMarket(e.target.value as MetricMarket)}
            className={SELECT_CLS}
          >
            <option value="ALL">전체</option>
            <option value="KOSPI">KOSPI</option>
            <option value="KOSDAQ">KOSDAQ</option>
          </select>
        </label>
        {data && (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            <Info className="h-3.5 w-3.5" />
            기준일: {fmtDate(data.as_of)} · 종가 확정 기준(장중 실시간 아님)
          </span>
        )}
      </div>

      {/* 해석 주의 배너 — 매매 신호가 아님을 명시 */}
      <div className="mb-4 flex items-start gap-2 rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2 text-xs text-muted-foreground">
        <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0 text-amber-500" />
        <span>
          패닉셀 지표는 시장 공포 국면을 알리는 <b>컨텍스트</b>일 뿐 매매 신호가 아닙니다. 자본항복은
          통계적으로 바닥 근처에서 발생하는 경우가 많아, 투매에 동참하는 것이 최악의 타이밍이 될 수
          있습니다. 일봉 종가 기반이라 장중 급락 후 회복(V자)은 감지하지 못합니다.
        </span>
      </div>

      {/* 방법론상 알려진 한계 고지(백엔드 caveats 배선 전에는 미노출) */}
      {data?.caveats && data.caveats.length > 0 && (
        <div className="mb-4 flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
          <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <ul className="list-disc space-y-0.5 pl-4">
            {data.caveats.map((c, i) => (
              <li key={i}>{c}</li>
            ))}
          </ul>
        </div>
      )}

      {/* 로딩/에러/데이터 */}
      {isLoading && <TableSkeleton rows={2} />}
      {isError && <ErrorState message={(error as Error).message} />}
      {data && data.items.length === 0 && (
        <EmptyState message="패닉셀 지표 데이터가 없습니다." />
      )}
      {data && data.items.length > 0 && (
        <div className="grid gap-4 md:grid-cols-2">
          {data.items.map((item) => (
            <PanicCard key={item.market} item={item} />
          ))}
        </div>
      )}
      {/* 계산 불가 시장 명시 — "정상"과 "계산 불가"를 구분 */}
      {data && data.unavailable.length > 0 && (
        <div className="mt-4 flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
          <AlertCircle className="mt-0.5 h-3.5 w-3.5 shrink-0" />
          <span>
            {data.unavailable.join(", ")} 시장은 데이터 조회 실패로 계산하지 못했습니다(이상 없음이 아님).
          </span>
        </div>
      )}
    </div>
  );
}

/** 시장 1개의 패닉 판정 카드. */
function PanicCard({ item }: { item: PanicMarket }) {
  const meta = PANIC_LEVEL_META[item.level] ?? PANIC_LEVEL_META.normal;
  return (
    <div className={cn("rounded-lg border-2 bg-card p-4", meta.ring)}>
      {/* 헤더: 시장 + 라벨 + 점수 */}
      <div className="flex items-center justify-between gap-3">
        <div className="flex items-center gap-2">
          <MarketBadge market={item.market} />
          <Badge variant={meta.badge} className="text-[11px]">
            {meta.label}
          </Badge>
          {item.hard_trigger && (
            <Badge variant="destructive" className="text-[10px]">
              하드트리거
            </Badge>
          )}
        </div>
        <div className="text-right tabular-nums">
          <span className="text-2xl font-semibold">{Math.round(item.score)}</span>
          <span className="text-sm text-muted-foreground"> / 100</span>
        </div>
      </div>

      {/* 점수 게이지 */}
      <div className="mt-2 h-1.5 w-full overflow-hidden rounded-full bg-muted">
        <div
          className={cn(
            "h-full rounded-full transition-all",
            item.level === "panic"
              ? "bg-destructive"
              : item.level === "normal"
                ? "bg-muted-foreground/40"
                : "bg-amber-500",
          )}
          style={{ width: `${Math.max(0, Math.min(100, item.score))}%` }}
        />
      </div>

      {/* 단계 설명 */}
      <p className="mt-3 text-xs leading-relaxed text-muted-foreground">{meta.desc}</p>

      {/* 축 요약 */}
      <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-muted-foreground">
        <span>가격축 {item.price_sub == null ? "-" : Math.round(item.price_sub)}</span>
        <span>브레드스축 {item.breadth_sub == null ? "-" : Math.round(item.breadth_sub)}</span>
        <span>게이팅 {item.gated ? "충족" : "미충족"}</span>
        <span>60일고점대비 {fmtPct(item.dd60)}</span>
        <span>유효종목 {formatNumber(item.universe)}</span>
      </div>

      {/* 시그널 분해 */}
      <div className="mt-3 space-y-1.5 border-t border-border/60 pt-3">
        {item.signals
          .filter((s) => s.weight > 0)
          .map((sig) => (
            <PanicSignalRow key={sig.key} sig={sig} />
          ))}
      </div>
    </div>
  );
}

/** 시그널 1행: 라벨·원시값·서브스코어 미니바. */
function PanicSignalRow({ sig }: { sig: PanicSignal }) {
  const sub = sig.subscore;
  const barColor =
    sub == null
      ? "bg-muted-foreground/30"
      : sub >= 100
        ? "bg-destructive"
        : sub >= 50
          ? "bg-amber-500"
          : "bg-muted-foreground/50";
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="w-32 shrink-0 text-muted-foreground">{sig.label}</span>
      <span className="w-16 shrink-0 text-right tabular-nums">{fmtSignalValue(sig)}</span>
      <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-muted">
        <div
          className={cn("h-full rounded-full", barColor)}
          style={{ width: `${sub == null ? 0 : Math.max(0, Math.min(100, sub))}%` }}
        />
      </div>
    </div>
  );
}
