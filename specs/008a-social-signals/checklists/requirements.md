# Specification Quality Checklist: Typing-индикатор, «был в сети», отображаемые имена и per-chat звук

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-10-08
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Notes

- Items checked after validation on 2026-10-08; all items pass.
- Justification for «Content Quality / no implementation details»: ссылки на существующий realtime-канал №18, presence-модель 007 (TTL/heartbeat/гистерезис), операции 004 и прототип 008 — нормативные ограничения уже принятых контрактов и утверждённый дизайн-макет (бизнес-артефакты), а не новые технические решения; форма новых операций, значения дебаунсов/таймаутов, лимиты и схемы событий явно делегированы в `plan.md` (FR-006/FR-007, Assumptions).
- Description provided extremely detailed scope; no [NEEDS CLARIFICATION] markers were needed — все неоднозначности решены информированными допущениями, зафиксированными в разделе Assumptions (агрегация нескольких печатающих «имя + счётчик», нейтральный фолбэк lastSeen «давно» по образцу `unknown` 007, цвет аватара от username при инициалах из имени, alias живёт на контакте, звук строго серверный без офлайн-очереди, без realtime-события смены имён).
- Границы объёма зафиксированы явно: поиск по сообщениям — 013; серверный поиск по displayName, персональное переименование групп, офлайн-переключение звука — вне scope (Assumptions, FR-005); блок-пары не обменивают typing/lastSeen (FR-008, FR-010).
- **T064 (2026-10-10, Definition of Done — конституция VI)**: чек-лист прогнан по всем закрытым задачам T001–T063 на HEAD `fbf8692`. Все 16 пунктов трёх категорий подтверждены повторно (спека с момента утверждения 2026-10-08 не менялась). Свежие гейты: `./gradlew check` — BUILD SUCCESSFUL (742 теста, 0 падений); `pnpm --dir frontend lint` — 0 errors, `typecheck` — чисто, `test` — 996/996 (72 файла), `test:visual` — 78 passed/0 failed; `scripts/validate-contracts.sh` — lint + drift + breaking зелёные, 0 ERR (SC-007). Трассировка: FR-001–FR-005 → T009–T026, FR-006–FR-009 → T027–T041, FR-010/FR-011 → T042–T050, FR-012–FR-015 → T051–T060, FR-016 → T002–T006, FR-017 → T029/T030/T032/T053 (подтверждено T062 в `/actuator/prometheus`); SC-001–SC-008 закрыты задачами проверки историй (T026/T034/T045/T054) и Phase 7 (T061/T062, k6 Δp99 = 0%). Замечание (non-blocking): warning `react-refresh/only-export-components` в `frontend/src/ui/ModalShell.tsx` — экспорт `FOCUSABLE_SELECTOR` введён фичей 008 (T070), 008a расширила только type-union; гейт 0 errors, рефакторинг вне скоупа 008a (конституция I). Замечаний, блокирующих закрытие фичи, нет — чек-лист пройден, фича 008a-social-signals закрыта.
