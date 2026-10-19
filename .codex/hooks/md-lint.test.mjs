import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtempSync, mkdirSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join, dirname } from "node:path";
import { lint } from "./md-lint.mjs";

function repo(files) {
  const root = mkdtempSync(join(tmpdir(), "mdlint-"));
  for (const [p, body] of Object.entries(files)) {
    mkdirSync(dirname(join(root, p)), { recursive: true });
    writeFileSync(join(root, p), body);
  }
  return root;
}

const rule = (paths, body = "본문") => `---\npaths:\n${paths.map((p) => `  - "${p}"`).join("\n")}\n---\n\n# 규칙\n\n${body}\n`;

test("정상 저장소는 위반 0건", () => {
  const root = repo({
    "CLAUDE.md": "# 프로젝트\n\n[규칙](.claude/rules/a.md)\n",
    ".claude/rules/a.md": rule(["src/**/*.kt"]),
    "src/x/A.kt": "class A",
  });
  assert.deepEqual(lint(root), []);
});

test("CLAUDE.md 80줄 초과는 위반", () => {
  const root = repo({ "CLAUDE.md": "줄\n".repeat(81) });
  assert.match(lint(root).join("\n"), /CLAUDE\.md.*80/);
});

test("rules 문서 150줄 초과는 위반", () => {
  const root = repo({ "CLAUDE.md": "# p\n", ".claude/rules/a.md": rule(["CLAUDE.md"], "줄\n".repeat(150)) });
  assert.match(lint(root).join("\n"), /a\.md.*150/);
});

test("rules 문서에 paths 없으면 위반", () => {
  const root = repo({ "CLAUDE.md": "# p\n", ".claude/rules/a.md": "# 규칙\n" });
  assert.match(lint(root).join("\n"), /a\.md.*paths/);
});

test("어떤 파일에도 매칭 안 되는 paths glob 은 낡은 문서로 위반", () => {
  const root = repo({ "CLAUDE.md": "# p\n", ".claude/rules/a.md": rule(["nope/**"]) });
  assert.match(lint(root).join("\n"), /nope\/\*\*.*matches no files/);
});

test("깨진 상대 링크는 위반", () => {
  const root = repo({ "CLAUDE.md": "[x](docs/missing.md)\n" });
  assert.match(lint(root).join("\n"), /CLAUDE\.md:1.*docs\/missing\.md/);
});

test("외부 링크·앵커는 검사하지 않는다", () => {
  const root = repo({ "CLAUDE.md": "[a](https://x.y) [b](#sec)\n" });
  assert.deepEqual(lint(root), []);
});

test("두 문서에 40자 이상 같은 문단이 있으면 중복 위반", () => {
  const para = "이 문단은 두 문서에 똑같이 들어가 있어서 한 곳으로 모아야 하는 긴 설명 문장입니다.";
  const root = repo({
    "CLAUDE.md": `# p\n\n${para}\n`,
    ".claude/rules/a.md": rule(["CLAUDE.md"], para),
  });
  assert.match(lint(root).join("\n"), /duplicate/);
});
