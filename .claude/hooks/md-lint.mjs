#!/usr/bin/env node
// Claude 문서 품질 린트. 설계 §4.4 의 예산·낡음·링크·중복 규칙을 기계적으로 강제한다.
//   node .claude/hooks/md-lint.mjs          → 위반 시 exit 1 (CI)
//   node .claude/hooks/md-lint.mjs --hook   → PostToolUse 훅: .md 편집일 때만 검사, 위반은 exit 2 로 Claude 에 피드백
import { existsSync, globSync, readFileSync } from "node:fs";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const CLAUDE_MD_MAX = 80;
const RULE_MAX = 150;
const DUP_MIN_CHARS = 40;

const lines = (s) => s.replace(/\n$/, "").split("\n");

function frontmatterPaths(text) {
  const m = text.match(/^---\n([\s\S]*?)\n---/);
  if (!m) return null;
  const block = m[1].match(/^paths:\s*\n((?:\s+-\s+.*\n?)+)/m);
  if (!block) return null;
  return [...block[1].matchAll(/-\s+["']?([^"'\n]+)["']?/g)].map((x) => x[1].trim());
}

/** 코드 펜스 밖의 문단(빈 줄로 구분)만. 표·목록·제목은 중복 검사 대상이 아니다. */
function proseParagraphs(text) {
  const out = [];
  let fence = false;
  for (const p of text.replace(/^---\n[\s\S]*?\n---\n/, "").split(/\n\s*\n/)) {
    if ((p.match(/```/g) || []).length % 2 === 1) fence = !fence;
    const t = p.trim();
    if (fence || t.includes("```") || /^[#|>\-*\d]/.test(t)) continue;
    if (t.length >= DUP_MIN_CHARS) out.push(t.replace(/\s+/g, " "));
  }
  return out;
}

export function lint(root) {
  const v = [];
  const docs = ["CLAUDE.md", ...globSync(".claude/**/*.md", { cwd: root })]
    .map((p) => p.replaceAll("\\", "/"))
    .filter((p) => existsSync(join(root, p)));

  const seen = new Map(); // 문단 → 처음 나온 문서
  for (const doc of docs) {
    const text = readFileSync(join(root, doc), "utf8").replaceAll("\r\n", "\n");
    const n = lines(text).length;

    if (doc === "CLAUDE.md" && n > CLAUDE_MD_MAX) v.push(`${doc}: ${n} lines > budget ${CLAUDE_MD_MAX}`);

    if (doc.startsWith(".claude/rules/")) {
      if (n > RULE_MAX) v.push(`${doc}: ${n} lines > budget ${RULE_MAX}`);
      const paths = frontmatterPaths(text);
      if (!paths?.length) v.push(`${doc}: missing 'paths' frontmatter`);
      for (const g of paths ?? []) {
        const hits = globSync(g, { cwd: root, exclude: (f) => f.includes("node_modules") || f.includes("build") });
        if (hits.length === 0) v.push(`${doc}: paths glob '${g}' matches no files (stale doc?)`);
      }
    }

    lines(text).forEach((line, i) => {
      for (const [, target] of line.matchAll(/\]\(([^)\s]+)\)/g)) {
        if (/^[a-z]+:|^#/.test(target)) continue;
        const file = target.split("#")[0];
        if (!existsSync(resolve(root, dirname(doc), file))) v.push(`${doc}:${i + 1}: broken link '${target}'`);
      }
    });

    for (const p of proseParagraphs(text)) {
      const first = seen.get(p);
      if (first && first !== doc) v.push(`${doc}: duplicate paragraph also in ${first}: "${p.slice(0, 50)}…"`);
      else seen.set(p, doc);
    }
  }
  return v;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const root = resolve(dirname(fileURLToPath(import.meta.url)), "../..");
  const hook = process.argv.includes("--hook");
  if (hook) {
    const input = JSON.parse(readFileSync(0, "utf8") || "{}");
    const file = input.tool_input?.file_path ?? "";
    if (!file.endsWith(".md")) process.exit(0);
  }
  const violations = lint(root);
  for (const x of violations) console.error(x);
  if (hook && violations.length) {
    console.error(`md-lint: ${violations.length} violation(s) — 같은 작업에서 고칠 것 (설계 §4.4)`);
    process.exit(2); // PostToolUse exit 2 → Claude 에게 피드백으로 전달(편집은 이미 반영됨)
  }
  process.exit(violations.length && !hook ? 1 : 0);
}
