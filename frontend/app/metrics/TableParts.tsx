"use client";

import { useState } from "react";
import { AlertCircle, Info, HelpCircle, ChevronDown, ChevronUp, ChevronsUpDown } from "lucide-react";
import { Badge } from "@/components/ui/badge";
import { Skeleton } from "@/components/ui/skeleton";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

/** 종합점수(score) 계산 방식 설명 — 헤더 툴팁·안내에 공용으로 사용. */
export const SCORE_HINT =
  "종합점수 = 필터를 통과한 후보 종목군 안에서의 상대 우위를 표준화(z-score)해 합산한 값입니다. " +
  "모멘텀 40% + 밸류 30% + 저변동성 30% 가중 평균이며, 각 지표는 1·99% 윈저라이징 후 z-score로 변환합니다" +
  "(PER·PBR·변동성은 낮을수록 가점). 0 이상이면 평균 이상, 값이 클수록 상대적으로 우수합니다. 절대 수익률이 아닙니다.";

// ─────────────────────────── 포맷 헬퍼 ───────────────────────────

/** 기준일 문자열을 표시용으로 포맷. */
export function fmtDate(s: string): string {
  if (!s) return "";
  return s.slice(0, 10);
}

// ─────────────────────────── 정렬(클라이언트) 유틸 ───────────────────────────
// 서버는 전체 스냅샷을 반환하므로, 정렬은 컬럼 머리글 클릭으로 클라이언트에서
// 처리한다. 같은 컬럼을 다시 클릭하면 desc ↔ asc 를 토글하고, null 값은 방향과
// 무관하게 항상 아래로 보낸다.

export type SortDir = "asc" | "desc";
export interface SortState<K extends string> {
  key: K;
  dir: SortDir;
}

/** 컬럼 클릭 정렬 상태 훅. 최초 클릭은 desc, 재클릭 시 asc 로 토글. */
export function useTableSort<K extends string>(initialKey: K) {
  const [sort, setSort] = useState<SortState<K>>({
    key: initialKey,
    dir: "desc",
  });
  const onSort = (key: K) =>
    setSort((p) =>
      p.key === key
        ? { key, dir: p.dir === "desc" ? "asc" : "desc" }
        : { key, dir: "desc" },
    );
  return { sort, onSort };
}

/** rows 를 key/dir 로 정렬한 새 배열을 반환한다(null 은 항상 하단). */
export function sortRows<T, K extends keyof T & string>(
  rows: T[],
  key: K,
  dir: SortDir,
): T[] {
  return [...rows].sort((a, b) => {
    const av = a[key] as unknown;
    const bv = b[key] as unknown;
    const aN = av == null;
    const bN = bv == null;
    if (aN && bN) return 0;
    if (aN) return 1; // null 하단
    if (bN) return -1;
    let cmp: number;
    if (typeof av === "number" && typeof bv === "number") cmp = av - bv;
    else cmp = String(av).localeCompare(String(bv), "ko");
    return dir === "asc" ? cmp : -cmp;
  });
}

/** 머리글 라벨 옆에 붙는 설명 툴팁 아이콘. 클릭 시 정렬로 이벤트가 전파되지 않는다. */
export function HintIcon({ text }: { text: string }) {
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <button
          type="button"
          aria-label="설명"
          onClick={(e) => e.stopPropagation()}
          className="inline-flex text-muted-foreground/70 transition-colors hover:text-foreground"
        >
          <HelpCircle className="h-3.5 w-3.5" />
        </button>
      </TooltipTrigger>
      <TooltipContent>{text}</TooltipContent>
    </Tooltip>
  );
}

/** 클릭으로 정렬되는 표 머리글 셀. 활성 컬럼에 방향 화살표를 표시한다. */
export function SortHeader<K extends string>({
  label,
  colKey,
  sort,
  onSort,
  align = "right",
  sticky = false,
  hint,
}: {
  label: string;
  colKey: K;
  sort: SortState<K>;
  onSort: (k: K) => void;
  align?: "left" | "right";
  sticky?: boolean;
  hint?: string;
}) {
  const active = sort.key === colKey;
  return (
    <th
      onClick={() => onSort(colKey)}
      aria-sort={active ? (sort.dir === "asc" ? "ascending" : "descending") : "none"}
      className={cn(
        "cursor-pointer select-none px-3 py-2.5 font-medium transition-colors hover:text-foreground",
        align === "right" ? "text-right" : "text-left",
        sticky && "sticky left-0 z-10 bg-muted/50",
        active && "text-foreground",
      )}
    >
      <span
        className={cn(
          "inline-flex items-center gap-1",
          align === "right" && "flex-row-reverse",
        )}
      >
        <span className="inline-flex items-center gap-1">
          {label}
          {hint && <HintIcon text={hint} />}
        </span>
        {active ? (
          sort.dir === "desc" ? (
            <ChevronDown className="h-3 w-3" />
          ) : (
            <ChevronUp className="h-3 w-3" />
          )
        ) : (
          <ChevronsUpDown className="h-3 w-3 opacity-30" />
        )}
      </span>
    </th>
  );
}

// ─────────────────────────── 공통 SELECT 스타일 ───────────────────────────
// bg-transparent 대신 bg-input 을 명시해 네이티브 select 가 OS 기본(흰 배경)
// 콤보박스로 렌더되는 것을 방지하고, text-foreground 로 글씨 대비를 보장한다.
export const SELECT_CLS =
  "h-8 rounded-md border border-input bg-input text-foreground px-2 py-1 text-sm focus:outline-none focus:ring-1 focus:ring-ring";

// ─────────────────────────── 공용 서브 컴포넌트 ───────────────────────────

/** 표 로딩 스켈레톤. */
export function TableSkeleton({ rows }: { rows: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, i) => (
        <Skeleton key={i} className="h-10 w-full rounded-md" />
      ))}
    </div>
  );
}

/** 시장 구분 배지. */
export function MarketBadge({ market }: { market: string }) {
  return (
    <Badge
      variant={market === "KOSPI" ? "secondary" : market === "KOSDAQ" ? "muted" : "outline"}
      className="text-[10px]"
    >
      {market}
    </Badge>
  );
}

/** 정보 배너. */
export function InfoBanner({ text }: { text: string }) {
  return (
    <div className="mb-4 flex items-start gap-2 rounded-md border border-border bg-muted/40 px-3 py-2 text-xs text-muted-foreground">
      <Info className="mt-0.5 h-3.5 w-3.5 shrink-0" />
      <span>{text}</span>
    </div>
  );
}

/** 에러 상태. */
export function ErrorState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-2 rounded-lg border border-destructive/30 bg-destructive/5 py-12 text-center">
      <AlertCircle className="h-8 w-8 text-destructive" />
      <p className="text-sm font-medium text-destructive">데이터를 불러오지 못했습니다</p>
      <p className="text-xs text-muted-foreground">{message}</p>
    </div>
  );
}

/** 빈 상태. */
export function EmptyState({ message }: { message: string }) {
  return (
    <div className="flex flex-col items-center justify-center gap-1 rounded-lg border border-dashed py-12 text-center">
      <p className="text-sm font-medium text-muted-foreground">{message}</p>
    </div>
  );
}
