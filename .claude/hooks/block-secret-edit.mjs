// PreToolUse(Edit|Write): secrets/*.txt, .env* 편집 차단. 실거래 자격증명이 평문 파일로 있어 실수 노출을 막는다.
import { readFileSync } from "node:fs";

try {
  const input = JSON.parse(readFileSync(0, "utf8") || "{}");
  const file = (input.tool_input?.file_path ?? "").replaceAll("\\", "/");
  const secret = /(^|\/)secrets\/[^/]+\.txt$/.test(file);
  const env = /(^|\/)\.env(\.[^/]*)?$/.test(file) && !file.endsWith(".env.example");
  if (secret || env) {
    console.log(JSON.stringify({
      hookSpecificOutput: {
        hookEventName: "PreToolUse",
        permissionDecision: "deny",
        permissionDecisionReason: `시크릿/환경변수 파일 편집 차단: ${file} — 사람이 직접 관리한다(secrets/README.md).`,
      },
    }));
  }
} catch {
  // 입력 파싱 실패 시 통과
}
