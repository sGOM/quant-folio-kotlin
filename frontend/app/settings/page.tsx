"use client";

import { useMutation } from "@tanstack/react-query";
import { Globe } from "lucide-react";
import { api } from "@/lib/api";
import { useCurrentUser } from "@/lib/useAuth";
import { Nav } from "@/components/Nav";
import { RequireAuth } from "@/components/RequireAuth";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import { Badge } from "@/components/ui/badge";

export default function SettingsPage() {
  return (
    <RequireAuth>
      <SettingsContent />
    </RequireAuth>
  );
}

function SettingsContent() {
  const { data: user } = useCurrentUser();
  const health = useMutation({ mutationFn: api.kisHealth });

  return (
    <>
      <Nav />
      <main className="mx-auto max-w-xl px-4 py-8 sm:px-6">
        <h1 className="text-2xl font-semibold tracking-tight">설정</h1>

        <Card className="mt-6">
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="text-base">증권사 연동</CardTitle>
              {user?.has_kis_credentials ? (
                <Badge variant="success">설정됨</Badge>
              ) : (
                <Badge variant="muted">미설정</Badge>
              )}
            </div>
            <CardDescription>증권사 자격증명은 서버에서 관리합니다.</CardDescription>
          </CardHeader>
          {user?.has_kis_credentials && (
            <CardContent className="space-y-3">
              <dl className="space-y-2 text-sm">
                <div className="flex justify-between gap-4">
                  <dt className="text-muted-foreground">주문 브로커</dt>
                  <dd>{user.broker === "toss" ? "토스증권" : "한국투자증권"}</dd>
                </div>
                <div className="flex justify-between gap-4">
                  <dt className="text-muted-foreground">주문 계좌</dt>
                  <dd>{user.kis_account_no ?? "미설정"}</dd>
                </div>
              </dl>
              <Button type="button" onClick={() => health.mutate()} disabled={health.isPending}>
                {health.isPending ? "확인 중…" : "연결 확인"}
              </Button>
              {health.data && (
                <p role="status" className={health.data.token_issued ? "text-sm text-status-ok" : "text-sm text-destructive"}>
                  {health.data.message}
                </p>
              )}
              {health.isError && (
                <p role="alert" className="text-sm text-destructive">{health.error.message}</p>
              )}
            </CardContent>
          )}
        </Card>

        <Card className="mt-4">
          <CardHeader>
            <div className="flex items-center justify-between">
              <CardTitle className="flex items-center gap-1.5 text-base">
                <Globe className="h-4 w-4" /> 통합 시세 연동 (토스)
              </CardTitle>
              {user?.has_toss_quote ? (
                <Badge variant="success">설정됨</Badge>
              ) : (
                <Badge variant="muted">미설정</Badge>
              )}
            </div>
            <CardDescription>
              토스 시세를 설정하면 워치리스트에서 국내·해외 종목을 함께 모니터링할 수 있습니다.
            </CardDescription>
          </CardHeader>
        </Card>
      </main>
    </>
  );
}
