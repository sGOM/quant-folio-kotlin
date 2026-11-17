"use client";

import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import { Info } from "lucide-react";
import { api, type MetricMarket, type SectorMetric } from "@/lib/api";
import { cn } from "@/lib/utils";
import { fmtPct, fmtNum, pctColor } from "@/lib/format";
import { fmtDate, type SortState, useTableSort, sortRows, SortHeader, SELECT_CLS, TableSkeleton, MarketBadge, InfoBanner, ErrorState, EmptyState } from "@/app/metrics/TableParts";

// ─────────────────────────── 섹터 탭 ───────────────────────────

/** 섹터 표에서 정렬 가능한 컬럼 키(SectorMetric 필드). */
type SectorSortKey = keyof SectorMetric & string;

export function SectorsTab() {
  const [market, setMarket] = useState<MetricMarket>("ALL");
  const { sort, onSort } = useTableSort<SectorSortKey>("rs_126");

  const { data, isLoading, isError, error } = useQuery({
    queryKey: ["metrics", "sectors", market],
    queryFn: () => api.getSectors(market),
    staleTime: 5 * 60 * 1000, // 5분
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
        {data && (
          <span className="ml-auto flex items-center gap-1 text-xs text-muted-foreground">
            <Info className="h-3.5 w-3.5" />
            기준일: {fmtDate(data.as_of)}
          </span>
        )}
      </div>

      {/* 안내 배너 */}
      <InfoBanner text="모멘텀(RS·6M·3M)·거래대금추세가 핵심 선별 지표입니다. 컬럼 머리글을 클릭하면 해당 지표로 정렬됩니다(다시 클릭하면 오름/내림 전환)." />

      {/* 로딩/에러/데이터 */}
      {isLoading && <TableSkeleton rows={8} />}
      {isError && <ErrorState message={(error as Error).message} />}
      {data && rows.length === 0 && (
        <EmptyState message="조건에 맞는 섹터 데이터가 없습니다." />
      )}
      {data && rows.length > 0 && (
        <SectorTable items={rows} sort={sort} onSort={onSort} />
      )}
    </div>
  );
}

function SectorTable({
  items,
  sort,
  onSort,
}: {
  items: SectorMetric[];
  sort: SortState<SectorSortKey>;
  onSort: (k: SectorSortKey) => void;
}) {
  return (
    <div className="overflow-x-auto rounded-lg border">
      <table className="w-full min-w-[820px] text-sm">
        <thead className="bg-muted/50 text-xs text-muted-foreground">
          <tr>
            <SortHeader<SectorSortKey>
              label="섹터"
              colKey="name"
              sort={sort}
              onSort={onSort}
              align="left"
              sticky
            />
            <SortHeader<SectorSortKey>
              label="시장"
              colKey="market"
              sort={sort}
              onSort={onSort}
              align="left"
            />
            {/* 모멘텀 그룹 */}
            <SortHeader<SectorSortKey> label="3M 모멘텀" colKey="mom_3m" sort={sort} onSort={onSort} />
            <SortHeader<SectorSortKey> label="6M 모멘텀" colKey="mom_6m" sort={sort} onSort={onSort} />
            <SortHeader<SectorSortKey> label="1M 모멘텀" colKey="mom_1m" sort={sort} onSort={onSort} />
            {/* 강도·추세 */}
            <SortHeader<SectorSortKey> label="RS(126일)" colKey="rs_126" sort={sort} onSort={onSort} />
            <SortHeader<SectorSortKey> label="거래대금추세" colKey="value_trend" sort={sort} onSort={onSort} />
            {/* 리스크 */}
            <SortHeader<SectorSortKey> label="변동성(연)" colKey="vol_ann" sort={sort} onSort={onSort} />
            <SortHeader<SectorSortKey> label="위험조정모멘텀" colKey="risk_adj_mom" sort={sort} onSort={onSort} />
            <SortHeader<SectorSortKey> label="MDD(252일)" colKey="mdd_252" sort={sort} onSort={onSort} />
            {/* 가격 위치 */}
            <SortHeader<SectorSortKey> label="52주고가비" colKey="high_52w_ratio" sort={sort} onSort={onSort} />
          </tr>
        </thead>
        <tbody>
          {items.map((row) => (
            <SectorRow key={`${row.market}-${row.name}`} row={row} />
          ))}
        </tbody>
      </table>
    </div>
  );
}

function SectorRow({ row }: { row: SectorMetric }) {
  return (
    <tr className="border-t border-border/60 transition-colors hover:bg-accent/30">
      <td className="sticky left-0 bg-background px-3 py-2 font-medium hover:bg-accent/30">
        {row.name}
      </td>
      <td className="px-3 py-2">
        <MarketBadge market={row.market} />
      </td>
      {/* 모멘텀 */}
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mom_3m))}>
        {fmtPct(row.mom_3m)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mom_6m))}>
        {fmtPct(row.mom_6m)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mom_1m))}>
        {fmtPct(row.mom_1m)}
      </td>
      {/* 강도·추세 */}
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.rs_126))}>
        {fmtNum(row.rs_126, 2)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.value_trend))}>
        {fmtNum(row.value_trend, 2)}
      </td>
      {/* 리스크 */}
      <td className="px-3 py-2 text-right tabular-nums text-muted-foreground">
        {fmtPct(row.vol_ann)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.risk_adj_mom))}>
        {fmtNum(row.risk_adj_mom, 2)}
      </td>
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.mdd_252))}>
        {fmtPct(row.mdd_252)}
      </td>
      {/* 가격 위치 */}
      <td className={cn("px-3 py-2 text-right tabular-nums", pctColor(row.high_52w_ratio))}>
        {fmtPct(row.high_52w_ratio)}
      </td>
    </tr>
  );
}
