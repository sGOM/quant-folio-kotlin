"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Loader2 } from "lucide-react";
import { api, ApiError, type CalibrationProposal } from "@/lib/api";
import { Button } from "@/components/ui/button";

/**
 * 슬리피지 캘리브레이션 승인 배너. 실측 중앙값 기반 제안(bp)을 보여주고, 사람이
 * 확인 후 승인하면 전략 config 의 slippage_bps 를 갱신한다(자동 반영 없음).
 * 전체 보기(strategyId undefined)에서는 반영 대상 전략이 모호하므로 승인 버튼을
 * 숨기고 "전략을 선택하라"는 안내만 보여준다.
 */
export function SlippageCalibrationBanner({
  calibration,
  strategyId,
}: {
  calibration: CalibrationProposal;
  strategyId?: number;
}) {
  const qc = useQueryClient();
  const [notice, setNotice] = useState<string | null>(null);

  const apply = useMutation({
    mutationFn: () => {
      if (strategyId === undefined) {
        throw new Error("전략을 선택한 뒤 승인할 수 있습니다.");
      }
      return api.applySlippageCalibration(strategyId, calibration.proposed_bps);
    },
    onSuccess: (res) => {
      setNotice(
        `반영 완료: slippage_bps ${res.previous_bps}bp → ${res.applied_bps}bp (표본 ${res.sample_size}건)`,
      );
      qc.invalidateQueries({ queryKey: ["fill-quality"] });
      qc.invalidateQueries({ queryKey: ["strategies"] });
    },
    onError: (err) => {
      if (err instanceof ApiError && err.status === 409) {
        setNotice("리포트가 갱신되어 제안이 달라졌습니다. 새로고침 후 다시 시도하세요.");
        qc.invalidateQueries({ queryKey: ["fill-quality"] });
        return;
      }
      setNotice(err instanceof Error ? err.message : "반영에 실패했습니다.");
    },
  });

  const handleApply = () => {
    if (strategyId === undefined) return;
    const ok = window.confirm(
      `전략의 slippage_bps 를 ${calibration.current_bps}bp → ${calibration.proposed_bps}bp 로 ` +
        `변경합니다(실측 중앙값 ${calibration.observed_median_bps.toFixed(1)}bp, 표본 ` +
        `${calibration.sample_size}건). 계속하시겠습니까?`,
    );
    if (!ok) return;
    setNotice(null);
    apply.mutate();
  };

  return (
    <div className="rounded-md border border-dashed border-amber-500/40 bg-amber-500/5 p-3 text-xs">
      <p className="font-medium text-foreground">
        슬리피지 캘리브레이션 제안: 현재 {calibration.current_bps}bp → 제안{" "}
        {calibration.proposed_bps}bp (표본 {calibration.sample_size}건)
        {calibration.clamped && " · 클램프 적용됨"}
      </p>
      <p className="mt-1 text-muted-foreground">{calibration.reason}</p>
      {strategyId === undefined ? (
        <p className="mt-2 text-muted-foreground">
          승인하려면 위 &quot;대상 전략&quot;에서 특정 전략을 선택하세요.
        </p>
      ) : (
        <div className="mt-2 flex items-center gap-2">
          <Button
            size="sm"
            variant="outline"
            onClick={handleApply}
            disabled={apply.isPending}
          >
            {apply.isPending ? (
              <Loader2 className="h-3.5 w-3.5 animate-spin" />
            ) : (
              "제안 적용"
            )}
          </Button>
        </div>
      )}
      {notice && <p className="mt-2 text-foreground">{notice}</p>}
    </div>
  );
}
