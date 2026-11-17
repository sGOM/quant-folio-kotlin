"use client";

import { useRef, useState } from "react";
import { useQuery, useQueryClient } from "@tanstack/react-query";
import {
  Building2,
  Globe,
  HelpCircle,
  HeartPulse,
  Star,
} from "lucide-react";
import { api, BROKER_INFO } from "@/lib/api";
import { Nav } from "@/components/Nav";
import { RequireAuth } from "@/components/RequireAuth";
import { Watchlist } from "@/app/monitor/Watchlist";
import { OrderAuditTable } from "@/app/monitor/OrderAuditTable";
import { StrategyToggle } from "@/app/monitor/StrategyToggle";
import { StrategyHealthPanel } from "@/app/monitor/StrategyHealthPanel";
import { FillQualityPanel } from "@/app/monitor/FillQualityPanel";
import { Table } from "@/app/monitor/Table";
import { useCurrentUser } from "@/lib/useAuth";
import { useSymbolNames } from "@/lib/useSymbolNames";
import { useEventSocket } from "@/lib/useWebSocket";
import {
  Tooltip,
  TooltipContent,
  TooltipProvider,
  TooltipTrigger,
} from "@/components/ui/tooltip";
import { cn } from "@/lib/utils";
import { fmtKRW, formatNumber, formatRelativeTime } from "@/lib/format";

// 매매·체결 관련 이벤트 타입 — 어느 것이든 잔고/주문을 다시 가져온다.
const TRADE_EVENTS = new Set(["execution", "order", "position", "fill", "signal"]);

/** DB 백업 신선도 판정 임계(시간) — worker.check_backup_freshness(§9)와 동일 기준. */
const BACKUP_STALE_HOURS = 26;

/** 백업 신선도 배지 톤 판정. null(백업 이력 없음)·임계 초과는 위험, 그 외 정상. */
function backupFreshnessTone(lastSuccessAt: string | null | undefined): "ok" | "bad" | "unknown" {
  if (lastSuccessAt === undefined) return "unknown";
  if (lastSuccessAt === null) return "bad";
  const hoursSince = (Date.now() - new Date(lastSuccessAt).getTime()) / 3_600_000;
  return hoursSince > BACKUP_STALE_HOURS ? "bad" : "ok";
}

/** 매매 엔진 상태 배지에 붙일 상세 설명(툴팁). */
function engineStatusDesc(loading: boolean, alive?: boolean): string {
  if (loading) return "매매 엔진의 가동 여부를 확인하는 중입니다.";
  if (alive)
    return (
      "24시간 자동매매 데몬(engine)이 이벤트 루프를 돌며 시세 수신·신호 평가·주문 실행을 " +
      "정상 처리 중입니다. 활성 전략의 매매가 실시간으로 수행됩니다."
    );
  return (
    "매매 엔진 데몬이 가동되지 않고 있습니다. 전략을 ON 으로 두어도 실제 주문이 나가지 " +
    "않으니, 관리자에게 엔진 프로세스 상태를 확인하세요."
  );
}

/**
 * 실시간 이벤트 로그 한 줄. id 는 안정적인 React key 용 단조 증가 값.
 * raw 는 WS 로 수신한 이벤트 페이로드 원문 — 요약(text)에는 side/symbol/qty/price/status
 * 등 핵심 필드만 담기므로, 상세 진단이 필요하면 접기 UI로 원본 전체를 펼쳐 본다(§16).
 */
interface LogEntry {
  id: number;
  text: string;
  raw: Record<string, unknown>;
}

/** 실시간 모니터링 라우트. 인증 게이트로 감싼 콘텐츠를 렌더한다. */
export default function MonitorPage() {
  return (
    <RequireAuth>
      <MonitorContent />
    </RequireAuth>
  );
}

/**
 * 실시간 모니터링 본문.
 * 전략 ON/OFF, 보유 포지션, 실시간 이벤트 로그, 주문 감사 로그를 표시한다.
 * WS 이벤트로 즉시 갱신하되, 누락 대비 15초 폴백 폴링을 병행한다.
 */
function MonitorContent() {
  const qc = useQueryClient();
  const [log, setLog] = useState<LogEntry[]>([]);
  const logSeq = useRef(0);
  // 체결 정합 리포트 대상 전략. undefined="전체"(계정 통합) — 슬리피지 캘리브레이션은
  // 전략별 config 를 바꾸는 조치라, 특정 전략을 골랐을 때만 승인 UI 를 노출한다.
  const [fillQualityStrategyId, setFillQualityStrategyId] = useState<number | undefined>(
    undefined,
  );
  // 전략 ON/OFF 목록을 즐겨찾기 전략만으로 좁혀 보는 토글.
  const [favoritesOnly, setFavoritesOnly] = useState(false);

  // 인증 쿼리는 useCurrentUser 로 통일(retry:false 등 옵션이 화면마다 갈리지 않게).
  const me = useCurrentUser();
  const strategies = useQuery({ queryKey: ["strategies"], queryFn: api.listStrategies });
  // 종목코드 → 한글명 매핑(로그·표에 종목명 표시). 캐시 정책은 훅 참조.
  const { nameOf } = useSymbolNames();
  // WS 이벤트를 놓쳐도 화면이 영구히 stale 되지 않도록 보수적 폴백 폴링.
  const positions = useQuery({
    queryKey: ["positions"],
    queryFn: api.positions,
    refetchInterval: 15000,
  });
  const orders = useQuery({
    queryKey: ["orders"],
    queryFn: api.orders,
    refetchInterval: 15000,
  });
  const engine = useQuery({
    queryKey: ["engine-status"],
    queryFn: api.engineStatus,
    refetchInterval: 10000,
  });
  // 전략별 러너 헬스(마지막 성공 틱·연속 실패). 엔진 전역 생존과 별개로 러너 단위 이상을 감지.
  const health = useQuery({
    queryKey: ["strategies-health"],
    queryFn: api.getStrategiesHealth,
    refetchInterval: 30000,
  });
  // 체결 정합 실측(P2-3, D-2) — 최근 90일 실거래 체결이 백테스트 슬리피지 가정과
  // 얼마나 어긋나는지. 매매가 드문 계정은 표본 부족(INSUFFICIENT)이 정상 경로.
  const fillQuality = useQuery({
    queryKey: ["fill-quality", fillQualityStrategyId ?? "all"],
    queryFn: () => api.getFillQuality(90, fillQualityStrategyId),
    staleTime: 5 * 60 * 1000,
  });

  // 실시간 이벤트 → 쿼리 무효화 + 로그
  useEventSocket((data) => {
    const type = data.type as string;
    if (type === "alert") {
      // 러너 실패·MDD 킬 등은 헬스 위젯을 즉시 갱신(30초 폴링을 기다리지 않음).
      qc.invalidateQueries({ queryKey: ["strategies-health"] });
      return;
    }
    if (TRADE_EVENTS.has(type)) {
      qc.invalidateQueries({ queryKey: ["positions"] });
      qc.invalidateQueries({ queryKey: ["orders"] });
      qc.invalidateQueries({ queryKey: ["strategies"] });
      const t = new Date().toLocaleTimeString("ko-KR");
      const f = (v: unknown) => (v == null ? "" : String(v));
      const desc =
        type === "execution"
          ? `체결 ${f(data.side)} ${nameOf(data.symbol as string)} ${f(data.qty)}주 @ ${f(data.price)}`
          : type === "order"
            ? `주문 ${f(data.status)} ${nameOf(data.symbol as string)}`
            : `${type} ${nameOf(data.symbol as string)}`;
      const id = logSeq.current++;
      setLog((l) => [{ id, text: `${t}  ${desc}`, raw: data }, ...l].slice(0, 30));
    }
  });

  return (
    <TooltipProvider delayDuration={150}>
      <Nav />
      <main className="mx-auto max-w-5xl px-4 py-8 sm:px-6">
        <div className="flex items-center justify-between">
          <div>
            <h1 className="text-2xl font-semibold tracking-tight">실시간 모니터링</h1>
            {me.data && (
              <p className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground">
                {me.data.broker === "toss" ? (
                  <Globe className="h-3.5 w-3.5" />
                ) : (
                  <Building2 className="h-3.5 w-3.5" />
                )}
                주문 브로커:{" "}
                <span className="font-medium text-foreground">
                  {BROKER_INFO[me.data.broker].name}
                </span>
                <span className="text-xs">
                  ({BROKER_INFO[me.data.broker].market})
                </span>
              </p>
            )}
          </div>
          <Tooltip>
            <TooltipTrigger asChild>
              <span
                tabIndex={0}
                className={cn(
                  "flex cursor-help items-center gap-2 rounded-full border px-3 py-1 text-sm font-medium",
                  // 첫 응답 전(isLoading)엔 engine_alive 가 undefined(falsy) 라 실제 가동 중이어도
                  // 적색 '중지'로 오표시된다. 로딩 중엔 중립(muted) 상태로 분기한다.
                  engine.isLoading
                    ? "border-border bg-muted text-muted-foreground"
                    : engine.data?.engine_alive
                      ? "border-status-ok/30 bg-status-ok/10 text-status-ok"
                      : "border-status-bad/30 bg-status-bad/10 text-status-bad",
                )}
              >
                <span className="relative flex h-2 w-2">
                  {engine.data?.engine_alive && !engine.isLoading && (
                    <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-status-ok opacity-75" />
                  )}
                  <span
                    className={cn(
                      "relative inline-flex h-2 w-2 rounded-full",
                      engine.isLoading
                        ? "bg-muted-foreground"
                        : engine.data?.engine_alive
                          ? "bg-status-ok"
                          : "bg-status-bad",
                    )}
                  />
                </span>
                매매 엔진{" "}
                {engine.isLoading ? "확인 중…" : engine.data?.engine_alive ? "동작 중" : "중지"}
                <HelpCircle className="h-3.5 w-3.5 opacity-60" />
              </span>
            </TooltipTrigger>
            <TooltipContent>
              {engineStatusDesc(engine.isLoading, engine.data?.engine_alive)}
            </TooltipContent>
          </Tooltip>
        </div>

        {!engine.isLoading && (
          <Tooltip>
            <TooltipTrigger asChild>
              <span
                tabIndex={0}
                className={cn(
                  "mt-2 flex w-fit cursor-help items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium",
                  backupFreshnessTone(engine.data?.backup_last_success_at) === "ok"
                    ? "border-status-ok/30 bg-status-ok/10 text-status-ok"
                    : "border-status-bad/30 bg-status-bad/10 text-status-bad",
                )}
              >
                DB 백업{" "}
                {engine.data?.backup_last_success_at
                  ? `마지막 성공 ${formatRelativeTime(engine.data.backup_last_success_at)}`
                  : "성공 이력 없음"}
                <HelpCircle className="h-3 w-3 opacity-60" />
              </span>
            </TooltipTrigger>
            <TooltipContent>
              worker 컨테이너가 매일 새벽 03:00 KST 에 DB 를 백업합니다(§9). 마지막 성공이
              {BACKUP_STALE_HOURS}시간을 넘으면 백업이 밀렸다는 뜻이므로 worker 로그와
              Celery beat 스케줄을 확인하세요.
            </TooltipContent>
          </Tooltip>
        )}

        <Watchlist
          broker={me.data?.broker}
          tossQuote={!!me.data?.has_toss_quote}
        />

        <section className="mt-8">
          <div className="mb-2 flex items-center justify-between">
            <h2 className="text-sm font-medium text-muted-foreground">
              전략 ON/OFF
            </h2>
            <button
              type="button"
              onClick={() => setFavoritesOnly((v) => !v)}
              aria-pressed={favoritesOnly}
              className={cn(
                "flex items-center gap-1.5 rounded-full border px-3 py-1 text-xs font-medium transition-colors",
                favoritesOnly
                  ? "border-amber-400/40 bg-amber-400/10 text-amber-500"
                  : "border-border text-muted-foreground hover:bg-accent hover:text-foreground",
              )}
            >
              <Star
                className={cn("h-3.5 w-3.5", favoritesOnly && "fill-amber-400 text-amber-400")}
              />
              즐겨찾기만
            </button>
          </div>
          <div className="space-y-2">
            {(favoritesOnly
              ? strategies.data?.filter((s) => s.is_favorite)
              : strategies.data
            )?.map((s) => <StrategyToggle key={s.id} strategy={s} />)}
            {strategies.data?.length === 0 && (
              <p className="text-sm text-muted-foreground">전략이 없습니다.</p>
            )}
            {favoritesOnly &&
              strategies.data &&
              strategies.data.length > 0 &&
              !strategies.data.some((s) => s.is_favorite) && (
                <p className="text-sm text-muted-foreground">
                  즐겨찾기한 전략이 없습니다. 전략 메뉴에서 별표를 눌러 추가하세요.
                </p>
              )}
          </div>
        </section>

        <section className="mt-8">
          <h2 className="mb-2 flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
            <HeartPulse className="h-4 w-4" /> 전략 러너 헬스
          </h2>
          <StrategyHealthPanel
            data={health.data}
            isLoading={health.isLoading}
            isError={health.isError}
          />
        </section>

        <section className="mt-8">
          <div className="mb-2 flex flex-wrap items-center justify-between gap-2">
            <h2 className="text-sm font-medium text-muted-foreground">
              체결 정합 실측 (최근 90일)
            </h2>
            <label className="flex items-center gap-1.5 text-xs text-muted-foreground">
              대상 전략
              <select
                value={fillQualityStrategyId ?? "all"}
                onChange={(e) =>
                  setFillQualityStrategyId(
                    e.target.value === "all" ? undefined : Number(e.target.value),
                  )
                }
                className="h-8 rounded-md border border-input bg-transparent px-2 text-xs shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              >
                <option value="all">전체(계정 통합)</option>
                {strategies.data?.map((s) => (
                  <option key={s.id} value={s.id}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
          </div>
          <FillQualityPanel
            data={fillQuality.data}
            isLoading={fillQuality.isLoading}
            isError={fillQuality.isError}
            strategyId={fillQualityStrategyId}
          />
        </section>

        <div className="mt-8 grid gap-6 lg:grid-cols-2">
          <section>
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">
              보유 포지션
            </h2>
            <Table
              head={["종목", "수량", "평균단가"]}
              rows={
                positions.data?.map((p) => [
                  nameOf(p.symbol),
                  formatNumber(p.qty),
                  fmtKRW(p.avg_price, false),
                ]) ?? []
              }
              empty="보유 포지션이 없습니다."
            />
          </section>

          <section>
            <h2 className="mb-2 text-sm font-medium text-muted-foreground">
              실시간 이벤트
            </h2>
            <div className="h-48 overflow-auto rounded-lg border bg-card p-3 font-mono text-xs text-muted-foreground">
              {log.length === 0 ? (
                <p className="text-muted-foreground/60">이벤트 대기 중…</p>
              ) : (
                log.map((l) => (
                  <details
                    key={l.id}
                    className="animate-fade-in border-b border-border/40 py-1 last:border-0"
                  >
                    <summary className="cursor-pointer list-none">{l.text}</summary>
                    <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-all rounded bg-muted/40 p-1.5 text-[10px] leading-relaxed text-muted-foreground/80">
                      {JSON.stringify(l.raw, null, 2)}
                    </pre>
                  </details>
                ))
              )}
            </div>
          </section>
        </div>

        <section className="mt-8">
          <h2 className="mb-2 text-sm font-medium text-muted-foreground">
            최근 주문 (감사 로그)
          </h2>
          <OrderAuditTable orders={orders.data} nameOf={nameOf} />
        </section>
      </main>
    </TooltipProvider>
  );
}
