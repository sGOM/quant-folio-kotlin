---
name: doc-check
description: Claude 문서(CLAUDE.md·.claude/rules·skills) 품질 점검. 마이그레이션 각 단계 종료 시, 또는 "문서 점검"·"doc-check" 요청 시 사용.
---

# 문서 점검

설계 §4.4 의 주기 점검 절차. 순서대로 한다.

1. **린트:** `node .claude/hooks/md-lint.mjs` → 0건이 될 때까지 고친다.
2. **커버리지:** 이번 단계에서 만든 코드 영역마다 `paths` 로 묶인 rules 문서가 있는지 확인. 없으면 만든다
   (원본 `sGOM/quant-folio` 의 해당 rules 문서를 *내용 출처*로, 형식은 이 저장소 규칙대로).
3. **사용량 점검:** `md-refine` 스킬을 실행해 세션 기록상 안 쓰인 문서·절을 찾아 삭제/병합/분리를 제안받는다.
4. **교정 회수:** 이번 단계 대화에서 사용자가 교정한 내용이 rules "함정" 절에 반영됐는지 확인.
5. 변경을 `docs(claude): …` 로 커밋.
