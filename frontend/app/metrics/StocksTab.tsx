"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Info } from "lucide-react";
import { api, type MetricMarket, type StockMetric } from "@/lib/api";
import { Badge } from "@/components/ui/badge";
import { Tooltip, TooltipTrigger, TooltipContent } from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { fmtPct, fmtNum, fmtAmt, fmtKRW, formatNumber, pctColor } from "@/lib/format";
import { SCORE_HINT, fmtDate, type SortState, useTableSort, sortRows, SortHeader, SELECT_CLS, TableSkeleton, MarketBadge, InfoBanner, ErrorState, EmptyState } from "@/app/metrics/TableParts";

// ─────────────────────────── 종목 탭 ───────────────────────────

/** 종목 표에서 정렬 가능한 컬럼 키(StockMetric 필드). */
type StockSortKey = keyof StockMetric & string;

const LIMIT_OPTIONS = [30, 50, 100, 200];

export function StocksTab() {
  const [market, setMarket] = useState<MetricMarket>("ALL");
  const [limit, setLimit] = useState(50);
  const { sort, onSort } = useTableSort<StockSortKey>("score");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["metrics", "stocks", market, limit],
    queryFn: () => api.getStocks({ market, limit }),
    staleTime: 5 * 60 * 1000,
  });

  const rows = useMemo(
    () => (data ? sortRows(data.items, sort.key, sort.dir) : []),
    [data, sort.key, sort.dir],
  );

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
        <label className="flex items-center gap-1.5 text-sm">
          <span className="text-muted-foreground">표시</span>
          <select
            value={limit}
            onChange={(e) => setLimit(Number(e.target.value))}
            className={SELECT_CLS}
          >
            {LIMIT_OPTIONS.map((n) => (
              <option key={n} value={n}>
                {n}개
              </option>
            ))}
          </select>
        </label>
        {data && (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            <Info className="h-3.5 w-3.5" />
            기준일: {fmtDate(data.as_of)} · 종합점수 상위 {formatNumber(data.count)}종목
          </span>
        )}
      </div>

      {/* 안내 배너 */}
      <InfoBanner text="종합점수(모멘텀·밸류·저변동성 가중합) 상위 종목입니다. 컬럼 머리글을 클릭하면 이 목록을 해당 지표로 재정렬합니다(다시 클릭하면 오름/내림 전환)." />

      {/* 로딩/에러/데이터 */}
      {isLoading && <TableSkeleton rows={10} />}
      {isError && <ErrorState message={(error as Error).message} />}
      {data && rows.length === 0 && (
        <EmptyState message="조건에 맞는 종목 데이터가 없습니다." />
      )}
      {data && rows.length > 0 && (
        <StocksTable items={rows} sort={sort} onSort={onSort} />
      )}
    </div>
  );
}

function StocksTable({
  items,
  sort,
  onSort,
}: {
  items: StockMetric[];
  sort: SortState<StockSortKey>;
  onSort: (k: StockSortKey) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full min-w-[1200px] text-sm">
        <thead className="bg-muted/50 text-xs text-muted-foreground">
          <tr>
            <SortHeader<StockSortKey>
              label="종목"
              colKey="name"
              sort={sort}
              onSort={onSort}
              align="left"
              sticky
            />
            <SortHeader<StockSortKey> label="시장" colKey="market" sort={sort} onSort={onSort} align="left" />
            {/* 가격 */}
            <SortHeader<StockSortKey> label="현재가" colKey="price" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="등락률" colKey="change_rate" sort={sort} onSort={onSort} />
            {/* 규모 */}
            <SortHeader<StockSortKey> label="시총" colKey="market_cap" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="거래대금(20일)" colKey="avg_value_20" sort={sort} onSort={onSort} />
            {/* 밸류 */}
            <SortHeader<StockSortKey> label="PER" colKey="per" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="PBR" colKey="pbr" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="배당(%)" colKey="div" sort={sort} onSort={onSort} />
            {/* 모멘텀 */}
            <SortHeader<StockSortKey> label="3M" colKey="mom_3m" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="6M" colKey="mom_6m" sort={sort} onSort={onSort} />
            {/* 기술적 */}
            <SortHeader<StockSortKey> label="RSI14" colKey="rsi14" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="변동성" colKey="vol_ann" sort={sort} onSort={onSort} />
            <SortHeader<StockSortKey> label="MDD" colKey="mdd_252" sort={sort} onSort={onSort} />
            {/* 상태(정렬 불가) */}
            <th className="px-3 py-2.5 text-center font-medium">상태</th>
            {/* 점수 */}
            <SortHeader<StockSortKey>
              label="종합점수"
              colKey="score"
              sort={sort}
              onSort={onSort}
              hint={SCORE_HINT}
            />
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <StockRow key={row.code} row={row} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function StockRow({ row }: { row: StockMetric }) {
  return (
    <tr className="border-t border-border/60 transition-colors hover:bg-accent/30">
      <td className="sticky left-0 bg-background px-3 py-2 hover:bg-accent/30">
        <div className="font-medium leading-tight">{row.name}</div>
        <div className="font-mono text-[11px] text-muted-foreground">{row.code}</div>
      </td>
      <td className="px-3 py-2">
        <MarketBadge market={row.market} />
      </td>
      {/* 가격 */}
      <td className="px-3 py-2 text-right tabular-nums">
        {fmtKRW(row.price, false)}
      </td>
      <td
        className={cn(
          "px-3 py-2 text-right tabular-nums",
          pctColor(row.change_rate),
        )}
      >
        {row.change_rate == null
          ? "-"
          : `${row.change_rate > 0 ? "▲" : row.change_rate < 0 ? "▼" : "─"} ${fmtPct(row.change_rate)}`}
      </td>
      {/* 규모 */}
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtAmt(row.market_cap)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtAmt(row.avg_value_20)}
      </td>
      {/* 밸류 */}
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtNum(row.per, 1)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtNum(row.pbr, 2)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {row.div == null ? "-" : `${row.div.toFixed(2)}%`}
      </td>
      {/* 모멘텀 */}
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mom_3m))}>
        {fmtPct(row.mom_3m)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mom_6m))}>
        {fmtPct(row.mom_6m)}
      </td>
      {/* 기술적 */}
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtNum(row.rsi14, 1)}
      </td>
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtPct(row.vol_ann)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mdd_252))}>
        {fmtPct(row.mdd_252)}
      </td>
      {/* 상태 배지 */}
      <td className="px-3 py-2">
        <div className="flex justify-center gap-1">
          {row.trend_aligned && (
            <Badge variant="success" className="text-[10px]">
              정배열
            </Badge>
          )}
          {row.above_sma200 && (
            <Badge variant="warning" className="text-[10px]">
              200선↑
            </Badge>
          )}
          {!row.trend_aligned && !row.above_sma200 && (
            <span className="text-xs text-muted-foreground/50">-</span>
          )}
        </div>
      </td>
      {/* 종합점수 */}
      <td className="px-3 py-2 text-right">
        <ScoreCell row={row} />
      </td>
    </tr>
  );
}

/**
 * 종합점수 강조 셀. score 는 z-score 합성값(대략 -2~+2)으로, 0 이상이 평균 이상이다.
 * 호버(포커스)하면 가치·모멘텀·저변동성 서브스코어 분해를 툴팁으로 보여준다(§14).
 */
function ScoreCell({ row }: { row: StockMetric }) {
  const { score, score_value, score_momentum, score_lowvol } = row;
  if (score == null) return <span className="text-muted-foreground">-</span>;
  const color =
    score >= 0.5
      ? "text-profit font-semibold"
      : score >= 0
        ? "text-foreground font-medium"
        : "text-muted-foreground";
  return (
    <Tooltip>
      <TooltipTrigger asChild>
        <span
          tabIndex={0}
          className={cn("cursor-help tabular-nums underline decoration-dotted decoration-muted-foreground/40", color)}
        >
          {score.toFixed(2)}
        </span>
      </TooltipTrigger>
      <TooltipContent>
        <div className="space-y-0.5 text-xs">
          <p className="font-medium text-foreground">서브스코어 분해(z-score)</p>
          <p>모멘텀 40%: {fmtNum(score_momentum, 2)}</p>
          <p>밸류 30%: {fmtNum(score_value, 2)}</p>
          <p>저변동성 30%: {fmtNum(score_lowvol, 2)}</p>
        </div>
      </TooltipContent>
    </Tooltip>
  );
}

