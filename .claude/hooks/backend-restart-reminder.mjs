// PostToolUse(Edit|Write): backend/** 변경 시 재기동 안내. web/engine/worker 는 핫리로드가 없다.
import { readFileSync } from "node:fs";

try {
  const input = JSON.parse(readFileSync(0, "utf8") || "{}");
  const file = (input.tool_input?.file_path ?? "").replaceAll("\\", "/");
  if (/(^|\/)backend\/.*\/src\/main\//.test(file)) {
    console.log(JSON.stringify({
      systemMessage: "backend 코드 변경: 실행 중인 스택에 반영하려면 `docker compose up -d --build web` (또는 engine/worker).",
    }));
  }
} catch {
  // 입력 파싱 실패 시 무시
}
