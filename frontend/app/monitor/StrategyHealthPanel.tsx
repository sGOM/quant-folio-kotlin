"use client";

import { AlertCircle } from "lucide-react";
import type { StrategyHealth } from "@/lib/api";
import { Card } from "@/components/ui/card";
import { Badge } from "@/components/ui/badge";
import {
  Tooltip,
  TooltipContent,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { formatRelativeTime } from "@/lib/format";

/**
 * 라이브 전략 러너 헬스 목록.
 * `GET /api/engine/strategies/health` 를 30초 주기로 폴링해(+ WS "alert" 수신 시 즉시 무효화),
 * 각 러너의 마지막 성공 틱 상대시각·연속 실패 횟수·healthy 배지를 보여준다.
 * 엔진 프로세스 전역 생존(상단 배지)과 달리, 전략 단위로 "조용한 실패"(외부 조회 지연 등)를 드러낸다.
 * MDD 킬스위치가 발동 중이면(전량 청산 후 쿨다운 재가동 대기) 별도 배지로 표시한다 —
 * 이전엔 발동 순간의 토스트/알림함 메시지 외엔 이 상태를 나중에 확인할 방법이 없었다.
 */
export function StrategyHealthPanel({
  data,
  isLoading,
  isError,
}: {
  data?: { threshold: number; strategies: StrategyHealth[] };
  isLoading: boolean;
  isError: boolean;
}) {
  if (isLoading) {
    return <p className="text-sm text-muted-foreground">헬스 상태 확인 중…</p>;
  }
  if (isError) {
    return (
      <p className="flex items-center gap-1 text-sm text-status-bad">
        <AlertCircle className="h-3.5 w-3.5" /> 헬스 상태를 불러오지 못했습니다.
      </p>
    );
  }
  const strategies = data?.strategies ?? [];
  if (strategies.length === 0) {
    return (
      <p className="text-sm text-muted-foreground">
        실행 중(live)인 전략이 없습니다. 전략을 시작하면 여기에 헬스가 표시됩니다.
      </p>
    );
  }
  return (
    <div className="space-y-2">
      {strategies.map((s) => (
        <Card key={s.strategy_id} className="flex items-center justify-between px-4 py-3">
          <div className="min-w-0">
            <p className="truncate text-sm font-medium">{s.name}</p>
            <p className="mt-0.5 text-xs text-muted-foreground">
              {s.reported ? (
                <>마지막 성공 틱: {formatRelativeTime(s.last_success_ts)}</>
              ) : (
                "아직 첫 틱 전(방금 시작)"
              )}
              {s.consecutive_failures > 0 && (
                <span className="ml-2 text-status-bad">
                  연속 실패 {s.consecutive_failures}회
                </span>
              )}
            </p>
            {s.last_error && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <p className="mt-0.5 cursor-help truncate text-xs text-status-bad/80">
                    최근 오류: {s.last_error}
                  </p>
                </TooltipTrigger>
                <TooltipContent>{s.last_error}</TooltipContent>
              </Tooltip>
            )}
          </div>
          <div className="flex shrink-0 items-center gap-1.5">
            {s.mdd_killed && (
              <Tooltip>
                <TooltipTrigger asChild>
                  <span tabIndex={0} className="cursor-help">
                    <Badge variant="warning">MDD 킬 발동중</Badge>
                  </span>
                </TooltipTrigger>
                <TooltipContent>
                  {s.mdd_kill_date
                    ? `${s.mdd_kill_date}에 고점 대비 낙폭이 임계를 넘어 전량 청산됐습니다. `
                    : "고점 대비 낙폭이 임계를 넘어 전량 청산됐습니다. "}
                  쿨다운(거래일 경과) 후 자동 재가동됩니다.
                </TooltipContent>
              </Tooltip>
            )}
            <Tooltip>
              <TooltipTrigger asChild>
                <span tabIndex={0} className="cursor-help">
                  <Badge variant={!s.reported ? "muted" : s.healthy ? "success" : "destructive"}>
                    {!s.reported ? "대기" : s.healthy ? "정상" : "이상"}
                  </Badge>
                </span>
              </TooltipTrigger>
              <TooltipContent>
                {!s.reported
                  ? "엔진이 이 전략을 시작했지만 아직 첫 틱을 처리하지 못했습니다."
                  : s.healthy
                    ? `연속 실패 ${s.consecutive_failures}회 (임계 ${data?.threshold} 미만) — 정상 처리 중입니다.`
                    : `연속 실패 ${s.consecutive_failures}회로 임계(${data?.threshold})를 넘었습니다. 외부 조회 지연·오류가 지속되고 있을 수 있습니다.`}
              </TooltipContent>
            </Tooltip>
          </div>
        </Card>
      ))}
    </div>
  );
}
