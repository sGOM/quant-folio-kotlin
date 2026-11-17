"use client";

import { AlertCircle } from "lucide-react";
import type { FillQualityReport } from "@/lib/api";
import { SlippageCalibrationBanner } from "@/app/monitor/SlippageCalibrationBanner";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";

/** 등급(GREEN/AMBER/RED/INSUFFICIENT) → Badge variant. */
function gradeBadgeVariant(
  grade: string,
): "success" | "warning" | "destructive" | "muted" {
  if (grade === "GREEN") return "success";
  if (grade === "AMBER") return "warning";
  if (grade === "RED") return "destructive";
  return "muted";
}

const GRADE_LABEL: Record<string, string> = {
  GREEN: "양호",
  AMBER: "주의",
  RED: "이탈",
  INSUFFICIENT: "표본 부족",
};

/**
 * 체결 정합 실측 카드(P2-3, D-2). 실거래 체결이 백테스트 슬리피지 가정(M1)·전체 체결가
 * 가정(M3)과 얼마나 어긋나는지 bp 단위로 보여준다. M2(시점 규약 표류)는 등급 없이 참고용
 * 진단 보조 지표로만 노출한다. 표본(min_sample) 미달이면 등급이 "표본 부족"으로 나오는 게
 * 정상 — 매매 이력이 쌓일수록(B-2 주간 배치가 자동 재점검) 신뢰도가 오른다. RED 등급이면
 * B-2 배치가 알림을 낸다.
 */
export function FillQualityPanel({
  data,
  isLoading,
  isError,
  strategyId,
}: {
  data?: FillQualityReport;
  isLoading: boolean;
  isError: boolean;
  /** 선택된 전략(전체 보기면 undefined) — 슬리피지 캘리브레이션 승인 대상. */
  strategyId?: number;
}) {
  if (isLoading) {
    return <p className="text-sm text-muted-foreground">체결 정합 조회 중…</p>;
  }
  if (isError) {
    return (
      <p className="flex items-center gap-1 text-sm text-status-bad">
        <AlertCircle className="h-3.5 w-3.5" /> 체결 정합 리포트를 불러오지 못했습니다.
      </p>
    );
  }
  if (!data || data.sample.n_orders === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        최근 90일 실거래 체결이 없습니다. 라이브 매매가 시작되면 여기에 표시됩니다.
      </p>
    );
  }

  const m1 = data.m1_exec.all;
  const m2 = data.m2_time.all;
  const m3 = data.m3_total.all;
  const drag = data.annualized_drag.drag_diff_pct_per_yr;

  return (
    <Card className="space-y-3 p-4">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        <FillQualityMetric
          label="M1 실행 슬리피지"
          grade={data.grades.m1_exec}
          value={m1.mean !== null ? `${m1.mean.toFixed(1)}bp` : "-"}
          hint={`가정 ${data.assumptions.backtest_slip_bps}bp`}
        />
        <FillQualityMetric
          label="M2 시점 규약 표류"
          value={m2.mean !== null ? `${m2.mean.toFixed(1)}bp` : "-"}
          hint="참고용, 별도 등급 없음"
        />
        <FillQualityMetric
          label="M3 총 정합 괴리"
          grade={data.grades.m3_total}
          value={m3.mean !== null ? `${m3.mean.toFixed(1)}bp` : "-"}
          hint={drag !== null ? `연환산 드래그차 ${drag.toFixed(2)}%p/yr` : "회전율 미상"}
        />
        <div>
          <p className="text-xs text-muted-foreground">표본</p>
          <p className="mt-0.5 text-sm font-medium tabular-nums">
            {data.sample.n_orders}건 / 하한 {data.sample.min_sample}
          </p>
        </div>
        <div>
          <p className="text-xs text-muted-foreground">체크섬(1차 근사 잔차)</p>
          <p className="mt-0.5 text-sm font-medium tabular-nums">
            {data.checksum.mean_abs_residual_bp !== null
              ? `${data.checksum.mean_abs_residual_bp.toFixed(1)}bp`
              : "-"}
          </p>
        </div>
      </div>
      <p className="text-xs text-muted-foreground">
        M1은 주문 결정가 대비 실제 체결가의 미끄러짐(백테스트 slippage_bps 가정 검증),
        M2는 라이브가 체결한 당일 종가와 백테스트가 가정하는 익일 종가 사이의 시장 이동(시점
        규약이 만드는 계통적 표류 — 진단 보조 지표로 별도 등급 없이 참고만 한다),
        M3은 실제 체결가와 백테스트 체결모형(익일 종가±슬리피지) 가정가의 총 괴리다.
        표본이 {data.sample.min_sample}건 미만인 방향은 &quot;표본 부족&quot;으로 판정을
        보류한다 — 과최적화 방어 통계와 같은 원칙(보일 때만 행동을 바꾼다).
      </p>
      {data.calibration && (
        <SlippageCalibrationBanner calibration={data.calibration} strategyId={strategyId} />
      )}
    </Card>
  );
}

function FillQualityMetric({
  label,
  grade,
  value,
  hint,
}: {
  label: string;
  /** 백엔드가 등급을 매기지 않는 보조 지표(M2 등)는 생략한다 — 뱃지 대신 hint로만 안내. */
  grade?: string;
  value: string;
  hint: string;
}) {
  return (
    <div>
      <div className="flex items-center gap-1.5">
        <p className="text-xs text-muted-foreground">{label}</p>
        {grade && (
          <Badge variant={gradeBadgeVariant(grade)} className="px-1.5 py-0 text-[10px]">
            {GRADE_LABEL[grade] ?? grade}
          </Badge>
        )}
      </div>
      <p className="mt-0.5 text-sm font-medium tabular-nums">{value}</p>
      <p className="text-xs text-muted-foreground">{hint}</p>
    </div>
  );
}
