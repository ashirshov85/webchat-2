# Implementation Plan: Стилизация окна чата по дизайн-макету «Aethergram»

**Branch**: `008-chat-window-styling` | **Date**: 2026-10-03 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008-chat-window-styling/spec.md`

## Summary

Фича 008 — чисто презентационный рескин действующего мессенджера (наследие 004–007) под утверждённый прототип `specs/008-chat-window-styling/design/chats.html` («Aethergram»: стимпанк-корпус-«машина», палитра auth-экрана, самохостинговые шрифты Old Standard TT / Cormorant SC / Anonymous Pro) плюс UI-слой без изменения API: аватары с инициалами и детерминированным цветом, presence-точки, время сообщений и разделители дат (из `createdAt`), тосты, модальная оболочка и контекстные меню, мобильный burger-drawer, WebAudio-звоночек приёма. Табы «Чаты»/«Контакты» заменяются главным меню у поиска с модальными окнами «Контакты» и «Мой профиль» (инкогнито — API 007). Подход: дизайн-токены и стили переносятся из CSS прототипа почти дословно (что и делает достижимым допуск визуальной регрессии ~2% пикселей, SC-001), DOM мессенджера перестраивается минимально — с сохранением существующих имён классов-хуков для тестов 004–007, где возможно. Контракты API и бэкенд не затрагиваются (SC-003, FR-005); визуальная регрессия — новый Playwright-инструментарий (единственное новое dev-зависимое добавление).

## Technical Context

**Language/Version**: TypeScript 5.9.3 strict (React 19.2.8); CSS (нативный, без CSS-in-JS)

**Primary Dependencies**: существующие (React, Vite 8, Vitest 5 + Testing Library, openapi-typescript); новые: `@fontsource/old-standard-tt`, `@fontsource/cormorant-sc`, `@fontsource/anonymous-pro` (runtime, woff2 cyrillic+latin); `@playwright/test` (dev-only, визуальная регрессия SC-001)

**Storage**: без изменений — localStorage (outbox/токены 004/005) как есть; новых durable-сущностей нет

**Testing**: Vitest + Testing Library (unit/компонентные, адаптация под новый DOM — FR-034); NEW: Playwright screenshot-тесты (Chromium, эталонная ширина + мобильные состояния, допуск ~2% пикселей); ручная матрица браузеров FR-036 для приёмки SC-009

**Target Platform**: браузеры SPA (последние 2 версии Chrome/Edge/Firefox/Safari, desktop+мобильные); breakpoint'ы прототипа ~900px/~480px

**Project Type**: web-app — только фронтенд-слой существующего monorepo (backend не затрагивается)

**Performance Goals**: SC-010 (60 fps лента/анимации, первый рендер ≤1 с, 1000+ сообщений) и SC-011 (60 fps списка чатов при 200+ чатах) — клиентские бюджеты

**Constraints**: 0 изменений контрактов API (SC-003, конституция IV); поведение 004–007 без регрессий (SC-002, FR-034); самохостинг шрифтов, без CDN (FR-003); клавиатурная навигация всех интерактивов (FR-035, SC-008); prefers-reduced-motion отключает анимации (FR-004)

**Scale/Scope**: ~25 React-компонентов (12 новых UI-примитивов + ~13 существующих перестраиваются/рестайлятся); ~700 строк нового CSS (токены + машина + поверхности); 0 строк бэкенда

## Constitution Check

*GATE: Обязателен до research-раунда SDD; повторная проверка — после design-раунда.*

| Принцип | Статус | Обоснование |
|---|---|---|
| I. Spec-Driven | PASS | Артефакты spec→plan→research→data-model→contracts→quickstart до кода; задачи — в tasks.md (раунд /speckit.tasks) |
| II. Stateless + бюджеты | PASS | Изменения только на клиенте; серверное состояние/нагрузка не затрагиваются — расчёт нагрузки не требуется (деградации трафика нет, новые запросы не вводятся). Клиентские бюджеты производительности зафиксированы: SC-010/SC-011 (60 fps, ≤1 с первый рендер) — проверяются в фиче |
| III. Exactly-once доставки | PASS | Пути доставки не меняются; UI сохраняет семантику outbox/ретраев 005 (идемпотентные `clientMessageId`), повтор «не отправлено» — тот же идентификатор (US4-1); тесты 005 на дубли/порядок проходят на новом UI (SC-002) |
| IV. API-First | PASS | Контракты 0.7.0 не изменяются (SC-003): oasdiff-drift в CI подтверждает 0 отличий; TS-типы не перегенерируются; фича — только презентационный слой (FR-005) |
| V. Security/Privacy | PASS | Новых эндпоинтов/секретов нет; экранирование пользовательского текста — React-инвариант, `dangerouslySetInnerHTML` не вводится (FR-033); presence-приватность 007 не меняется |
| VI. Test-First | PASS | Новые UI-примитивы покрываются Vitest одновременно с реализацией; существующие тесты 004–007 адаптируются по селекторам без изменения поведенческих ожиданий (FR-034); SC-001 требует визуальной регрессии — вводится Playwright (dev-only, обоснование в research §B); нагрузочные тесты не требуются (нет влияния на пропускную способность) |
| VII. YAGNI | PASS | Не вводятся: i18n, typing-индикатор, lastSeen, поиск по сообщениям, per-chat звук, редактирование имени, alias-контакты (все — 008a/013); виртуализация списков — только по факту недостижения 60 fps (research §G); звук — без настроек и персистентности; отвергнутые альтернативы — research.md |
| VIII. SOLID | PASS | UI-примитивы (`frontend/src/ui/`) — SRP: один примитив = одна ответственность (тост, меню, модальная оболочка, аватар, звук); ContextMenu/ModalShell переиспользуются четырьмя поверхностями (второй+ потребитель — абстракция оправдана); доменная логика чатов не зависит от стилей (токены — единственный источник констант, FR-002) |

Нарушений нет — Complexity Tracking не заполняется.

## Project Structure

### Documentation (this feature)

```text
specs/008-chat-window-styling/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── design-tokens.md   # нормативные визуальные константы прототипа
│   └── ui-behavior.md     # контракты UI-слоя (тосты/меню/модали/drawer/звук/клавиатура/аватары)
├── design/
│   └── chats.html       # версионированный нормативный прототип (FR-001, SC-001)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
frontend/src/theme/                      # НОВОЕ: дизайн-система прототипа
├── fonts.css                            # @fontsource-импорты (3 шрифта, cyrillic+latin) + резервные стеки (FR-003)
├── tokens.css                           # :root-токены: палитра, шрифтовые роли, радиусы, тени, паттерны (FR-002)
└── machine.css                          # корпус-«машина», панели, заклёпки, зерно/виньетка, скроллбары,
                                         # burger/backdrop, prefers-reduced-motion, адаптив ~900/~480px (FR-004, FR-029)

frontend/src/ui/                         # НОВОЕ: переиспользуемые UI-примитивы (наследуют токены)
├── Avatar.tsx                           # инициалы + детерминированный цвет + presence-точка; круг/октагон (FR-024)
├── avatar.ts                            # derivation: initialsOf(name/username), colorOf(hash → палитра прототипа)
├── ContextMenu.tsx                      # якорное меню: позиционирование в пределах экрана, Esc/клик-вне,
│                                        # разделители, danger-пункты, стрелки клавиатуры (FR-027, FR-035)
├── ModalShell.tsx                       # единая модальная оболочка: фокус-ловушка, возврат фокуса, закрытие
│                                        # по фону (press+release) и Esc (FR-026)
├── ConfirmDialog.tsx                    # подтверждение на ModalShell: обычная/основная/опасная кнопка (FR-014, SC-007)
├── Toast.tsx                            # ToastProvider + useToast: одиночный слот, ~3 с, замена (FR-025)
├── time.ts                              # ЧЧ:ММ из createdAt, длинный формат дат, pluralRu (FR-016, FR-019)
├── sound.ts                             # WebAudio-звоночек (партиалы 1:2.76:5.4), приём только,
│                                        # коалесцирование, молчаливый пропуск при запрете автозвука (FR-028)
└── __tests__/                           # Vitest: avatar-derivation, time, toast, ContextMenu, ModalShell, sound

frontend/src/chats/                      # ПЕРЕСТРОЙКА существующих поверхностей
├── pages/MessengerPage.tsx              # «машина»: сайдбар+окно чата; burger-drawer ≤900px; пустое состояние
│                                        # «Чат не выбран»; звук: onMessageCreated/applySyncUpdate → sound.ts
├── components/ChatListPanel.tsx         # БЕЗ табов (FR-006): поиск + кнопка главного меню (FR-007)
├── components/ChatListItem.tsx          # + аватар, время последнего сообщения, метка «заблокирован» (FR-008)
├── components/MainMenuButton.tsx        # НОВОЕ: главное меню «Мой профиль / Контакты / Создать групповой чат»
├── components/ChatHeader.tsx            # НОВОЕ: аватар, статус (presence/участники), «шестерёнка»; members-tip (FR-016/017)
├── components/ChatGearMenu.tsx          # НОВОЕ: меню чата — ростер-действия 006/контакты/блокировка (FR-023)
├── components/MessageList.tsx           # пузыри «пластина/панель», аватары, время, разделители дат,
│                                        # штампы ✓/✓✓ с анимацией, outbox-статусы, пагинация (FR-018–020, FR-030)
├── components/MessageInput.tsx          # золотая рама, «ОТПРАВИТЬ», Enter, фокус-сохранение, баннеры
│                                        # очереди/ретрая в композере, блокировка при блок-контакте (FR-021/022, FR-030)
├── components/ContactsModal.tsx         # НОВОЕ: модальные «Контакты»: поиск/сортировка/«⋯»-меню/пометки (FR-011–013)
├── components/ProfileModal.tsx          # НОВОЕ: «Мой профиль»: readonly username/email + инкогнито №38 (FR-015)
└── components/ (SyncIndicator, QueueOverflowBanner, ErrorBanner)  # переоформление в токены (FR-030, FR-032)

frontend/src/groups/components/          # переоформление в модальные оболочки без потери функций (FR-023)
├── CreateGroupDialog.tsx                # модальная форма по прототипу (grpForm)
├── GroupMembersModal.tsx                # НОВОЕ: участники + ростер-действия (membersForm)
└── GroupEditModal.tsx                   # НОВОЕ: название/описание/состав (grpEditForm); MemberList/AddMembersPicker — внутрь

frontend/src/settings/pages/             # переоформление страниц в дизайн-систему (FR-031; маршруты не меняются)
frontend/src/index.css                   # остаётся точкой сборки: импорт theme/*, удаление заменённых стилей мессенджера
frontend/tests/visual/                   # НОВОЕ: Playwright-снимки ключевых экранов (SC-001)
└── (base: design/chats.html → эталонные снимки; fixtures, повторяющие данные прототипа)
```

**Structure Decision**: Следуем конвенции monorepo 001–007: package-by-feature. Две новые точки: `frontend/src/theme/` (дизайн-токены и корпус — единственный источник визуальных констант, FR-002) и `frontend/src/ui/` (переиспользуемые примитивы, потребляемые chats/groups/settings — общая библиотека в духе конституции IV). Существующие модули chats/groups/presence/sync сохраняют границы; presence-точки встраиваются через Avatar (presenceStore переиспользуется без изменений). Визуальные тесты — `frontend/tests/visual/` (отдельно от unit-структуры `src/**/__tests__`). Контракты, бэкенд, deploy — без изменений.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений нет — таблица пуста.
