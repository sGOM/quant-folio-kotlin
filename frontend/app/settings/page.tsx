"use client";

import { useEffect, useState } from "react";
import { useMutation, useQueryClient } from "@tanstack/react-query";
import { CheckCircle2, AlertCircle, Loader2, Globe, UserCircle } from "lucide-react";
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
import { Input as TextInput } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
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

        <ProfileCard />

        <Card className="mt-4">
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

/**
 * 프로필(닉네임) 설정 카드. 닉네임은 공유 전략 목록에 작성자명으로 표시되며,
 * 비워두면 '익명'으로 노출된다.
 */
function ProfileCard() {
  const qc = useQueryClient();
  const me = useCurrentUser();
  const [name, setName] = useState("");

  // 사용자 정보 로드 시 현재 닉네임으로 초기화.
  useEffect(() => {
    setName(me.data?.display_name ?? "");
  }, [me.data?.display_name]);

  const save = useMutation({
    mutationFn: () => api.updateProfile(name.trim()),
    onSuccess: () => qc.invalidateQueries({ queryKey: ["me"] }),
  });

  return (
    <Card className="mt-6">
      <CardHeader>
        <CardTitle className="flex items-center gap-1.5 text-base">
          <UserCircle className="h-4 w-4" /> 프로필
        </CardTitle>
        <CardDescription>
          닉네임은 공유 전략 목록에 작성자명으로 표시됩니다. 비워두면 ‘익명’으로
          표시됩니다.
        </CardDescription>
      </CardHeader>
      <CardContent>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            save.mutate();
          }}
          className="space-y-4"
        >
          <div className="space-y-1.5">
            <Label>닉네임</Label>
            <TextInput
              type="text"
              maxLength={50}
              value={name}
              placeholder="예: 퀀트초보"
              onChange={(e) => setName(e.target.value)}
            />
          </div>

          {save.isError && (
            <p className="flex items-center gap-1.5 text-sm text-destructive">
              <AlertCircle className="h-4 w-4 shrink-0" />
              {(save.error as Error).message}
            </p>
          )}
          {save.isSuccess && (
            <p className="flex items-center gap-1.5 text-sm text-status-ok">
              <CheckCircle2 className="h-4 w-4 shrink-0" />
              닉네임이 저장되었습니다.
            </p>
          )}

          <Button type="submit" disabled={save.isPending}>
            {save.isPending && <Loader2 className="h-4 w-4 animate-spin" />}
            {save.isPending ? "저장 중…" : "저장"}
          </Button>
        </form>
      </CardContent>
    </Card>
  );
}
