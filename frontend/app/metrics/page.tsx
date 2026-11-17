"use client";

import { Nav } from "@/components/Nav";
import { RequireAuth } from "@/components/RequireAuth";
import { Tabs, TabsList, TabsTrigger, TabsContent } from "@/components/ui/tabs";
import { TooltipProvider } from "@/components/ui/tooltip";
import { SectorsTab } from "@/app/metrics/SectorsTab";
import { StocksTab } from "@/app/metrics/StocksTab";
import { PanicTab } from "@/app/metrics/PanicTab";

// ─────────────────────────── 페이지 진입점 ───────────────────────────

export default function MetricsPage() {
  return (
    <RequireAuth>
      <MetricsContent />
    </RequireAuth>
  );
}

function MetricsContent() {
  return (
    <TooltipProvider delayDuration={150}>
      <Nav />
      <main className="mx-auto max-w-7xl px-4 py-8 sm:px-6">
        <div className="mb-5">
          <h1 className="text-2xl font-semibold tracking-tight">시장 지표</h1>
          <p className="mt-1 text-sm text-muted-foreground">
            오늘 기준 KRX 섹터·종목 스냅샷입니다. 과거 백테스트와 다르며,
            모멘텀·유동성 지표가 신뢰도 높고 밸류·퀄리티는 참고 지표입니다.
          </p>
        </div>

        <Tabs defaultValue="sectors">
          <TabsList className="mb-6">
            <TabsTrigger value="sectors">섹터 지표</TabsTrigger>
            <TabsTrigger value="stocks">종목 지표</TabsTrigger>
            <TabsTrigger value="panic">패닉셀</TabsTrigger>
          </TabsList>

          <TabsContent value="sectors">
            <SectorsTab />
          </TabsContent>

          <TabsContent value="stocks">
            <StocksTab />
          </TabsContent>

          <TabsContent value="panic">
            <PanicTab />
          </TabsContent>
        </Tabs>
      </main>
    </TooltipProvider>
  );
}
