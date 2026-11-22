"use client";

import { useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { Play, Square, Loader2 } from "lucide-react";
import { api, ApiError, Strategy } from "@/lib/api";
import { summarizeConfig } from "@/lib/strategy";
import { Card } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";

/**
 * 전략별 시작/중지 토글. 현재 상태(live 여부)에 따라 엔진 start/stop 을 호출하고,
 * 성공 시 전략 목록을 무효화해 상태 배지를 갱신하고, 실패 시 오류 문구를 보인다.
 * @param strategy 토글 대상 전략
 */
export function StrategyToggle({ strategy }: { strategy: Strategy }) {
  const qc = useQueryClient();
  const isLive = strategy.status === "live";
  const [error, setError] = useState<string | null>(null);
  const toggle = useMutation({
    mutationFn: () =>
      isLive ? api.stopStrategy(strategy.id) : api.startStrategy(strategy.id),
    onMutate: () => setError(null),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["strategies"] }),
    onError: (err) =>
      setError(
        err instanceof ApiError && err.message
          ? err.message
          : isLive
            ? "전략 중지에 실패했습니다"
            : "전략 시작에 실패했습니다",
      ),
  });

  return (
    <Card className="px-4 py-3">
      <div className="flex items-center justify-between">
      <div className="flex items-center gap-3">
        <Tooltip>
          <TooltipTrigger asChild>
            <span
              tabIndex={0}
              className={cn(
                "h-2.5 w-2.5 shrink-0 cursor-help rounded-full",
                isLive ? "bg-status-ok" : "bg-status-bad",
              )}
            />
          </TooltipTrigger>
          <TooltipContent>
            {isLive
              ? "실행 중(live): 매매 엔진이 이 전략의 신호를 평가해 실제 주문을 냅니다."
              : "정지됨(stopped): 이 전략은 꺼져 있어 신호가 나도 주문하지 않습니다. '시작'을 눌러 활성화하세요."}
          </TooltipContent>
        </Tooltip>
        <div>
          <p className="text-sm font-medium">{strategy.name}</p>
          <p className="text-xs text-muted-foreground">
            {summarizeConfig(strategy.config)}
          </p>
        </div>
      </div>
      {/* 실행 중 = 가동 상태(초록 '중지'), 정지 = 미가동 상태(빨강 '시작').
          버튼 색은 현재 상태를 나타낸다 — 초록이면 운용 중, 빨강이면 꺼짐. */}
      <Button
        size="sm"
        variant={isLive ? "default" : "destructive"}
        onClick={() => toggle.mutate()}
        disabled={toggle.isPending}
        className={cn(isLive && "bg-status-ok text-white hover:bg-status-ok/90")}
      >
        {toggle.isPending ? (
          <Loader2 className="h-4 w-4 animate-spin" />
        ) : isLive ? (
          <>
            <Square className="h-3.5 w-3.5" /> 중지
          </>
        ) : (
          <>
            <Play className="h-3.5 w-3.5" /> 시작
          </>
        )}
      </Button>
      </div>
      {error && (
        <p role="alert" className="mt-2 text-xs text-status-bad">
          {error}
        </p>
      )}
    </Card>
  );
}
