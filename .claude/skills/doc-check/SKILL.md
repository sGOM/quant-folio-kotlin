---
name: doc-check
description: Claude 문서(CLAUDE.md·.claude/rules·skills) 품질 점검·일괄 최신화. 마이그레이션 각 단계 종료 시, 또는 "문서 점검"·"doc-check" 요청 시 사용.
---

# 문서 점검

설계 §4.4 의 주기 점검 절차(규격도 §4.4). 순서대로 한다.

1. **린트:** `node .claude/hooks/md-lint.mjs` → 0건이 될 때까지 고친다.
2. **낡음·모순:** 사용자에게 `/doctor prompt-audit` 실행을 요청하고 지적을 반영한다.
3. **일괄 최신화:** 마지막 `docs(claude)` 커밋 이후 `git diff --stat <그 커밋>..HEAD` 로 바뀐 코드 영역을 보고,
   그 경로에 `paths` 로 묶인 rules 본문을 코드와 대조해 틀린 서술을 고친다.
4. **커버리지:** 이번 단계에서 만든 코드 영역마다 `paths` 로 묶인 rules 문서가 있는지 확인. 없으면 만든다
   (원본 `sGOM/quant-folio` 의 해당 rules 문서를 *내용 출처*로, 형식은 이 저장소 규칙대로).
5. **교정 회수:** 이번 단계 대화의 교정과 auto memory 의 `feedback` 항목 중 저장소 공용 교훈을
   해당 rules "함정" 절로 옮긴다(옮긴 memory 는 지운다).
6. **사용량 점검:** `md-refine` 스킬을 실행해 세션 기록상 안 쓰인 문서·절을 찾아 삭제/병합/분리를 제안받는다.
7. 변경을 `docs(claude): …` 로 커밋.
