---

description: "Task list for feature 008-chat-window-styling (Aethergram reskin)"
---

# Tasks: Стилизация окна чата по дизайн-макету «Aethergram»

**Input**: Design documents from `/specs/008-chat-window-styling/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/design-tokens.md, contracts/ui-behavior.md, quickstart.md

**Tests**: Требуются спецификацией и конституцией (VI Test-First, SC-001, SC-002, FR-034): Vitest-тесты пишутся до/одновременно с реализацией; Playwright-визуальная регрессия — независимый тест US1; адаптация существующих тестов 004–007 обязательна.

**Organization**: Задачи сгруппированы по user stories (US1–US5 из spec.md) для независимой реализации и тестирования каждой истории.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность к user story (US1–US5)
- Во всех описаниях — точные пути к файлам

## Path Conventions

- Web app monorepo: только `frontend/` (backend и `contracts/openapi.yaml` не затрагиваются — SC-003, FR-005)
- Новые модули: `frontend/src/theme/`, `frontend/src/ui/`, `frontend/tests/visual/`
- Существующие: `frontend/src/chats/`, `frontend/src/groups/`, `frontend/src/settings/`
- Нормативный визуальный эталон: `specs/008-chat-window-styling/design/chats.html`
- Нормативные константы: `specs/008-chat-window-styling/contracts/design-tokens.md`; поведение: `contracts/ui-behavior.md`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Инициализация зависимостей и структуры

- [X] T001 Установить runtime-зависимости шрифтов `@fontsource/old-standard-tt`, `@fontsource/cormorant-sc`, `@fontsource/anonymous-pro` (woff2, cyrillic+latin) в frontend/package.json (research §A, FR-003)
- [X] T002 Установить dev-зависимость `@playwright/test` и добавить скрипт `test:visual` в frontend/package.json (research §B, SC-001)
- [X] T003 [P] Создать структуру каталогов по plan.md: `frontend/src/theme/`, `frontend/src/ui/` (+ `frontend/src/ui/__tests__/`), `frontend/tests/visual/` — проверка: `pnpm build` зелёный, каталоги используются задачами T004+

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Дизайн-система токенов/корпуса и переиспользуемые UI-примитивы, нужные нескольким историям (правило data-model: сущность на несколько историй → базовая фаза)

**⚠️ CRITICAL**: Работа над историями не начинается до завершения фазы

- [X] T004 Создать `frontend/src/theme/tokens.css`: `:root`-токены — палитра (`--gold`, `--gold-gradient`, `--panel-bg`, `--input-bg`, `--cream`, `--muted`, `--ok/--err`, `--line`), шрифтовые роли, радиусы, отступы, z-слои, паттерны `--pat-main`/`--pat-msg` (data-URI SVG) — точная проекция contracts/design-tokens.md §1, §7 (FR-002)
- [X] T005 [P] Создать `frontend/src/theme/fonts.css`: @fontsource-импорты трёх шрифтов (400/700 + italic, cyrillic+latin) и резервные стеки serif/heading/mono (FR-003, design-tokens §2) — проверка: `pnpm build` зелёный; шрифты — в снимках T026; кириллица/латиница — T072 (SC-006)
- [X] T006 Создать `frontend/src/theme/machine.css` (зависит от T004): корпус `.machine` (max-width 1360px, `calc(100dvh - 36px)`, золотая окантовка, тени), панели `.panel` с заклёпками, зерно `body::before` и виньетка `body::after`, золотые скроллбары, глобальный `prefers-reduced-motion` (design-tokens §3, §8; FR-004) — проверка: корпус/панели/зерно — снимки T026 ≤ ~2% (SC-001); reduced-motion — T025
- [X] T007 Пересобрать `frontend/src/index.css` (зависит от T004–T006): импорт `theme/fonts.css`, `theme/tokens.css`, `theme/machine.css`; удалить заменённые стили мессенджера; auth-тему не трогать (plan.md Structure Decision) — проверка: `pnpm build` + `pnpm test` зелёные; auth-экран не изменён (существующие auth-тесты)
- [X] T008 [P] Реализовать `frontend/src/ui/avatar.ts` + тесты `frontend/src/ui/__tests__/avatar.test.ts`: `initialsOf(source)` (первые буквы первых двух слов), `colorOf(source)` = `PALETTE[fnv1a32(source) mod 6]`, PALETTE из design-tokens §4; детерминизм, пересчёт при переименовании (FR-024, research §E)
- [X] T009 [P] Реализовать `frontend/src/ui/time.ts` + тесты `frontend/src/ui/__tests__/time.test.ts`: `formatTime` ЧЧ:ММ (`Intl ru-RU`), `formatDate` длинный формат «19 сентября» (+год при отличии от текущего), `pluralRu` (FR-016, FR-019, research §J)
- [X] T010 Реализовать `frontend/src/ui/Avatar.tsx` + тесты `frontend/src/ui/__tests__/Avatar.test.tsx` (зависит от T008): круг/октагон (clip-path 30/70%), обод `--gold-gradient`, инициалы Cormorant SC, проп `presenceDot: 'online'|'offline'|'unknown'|null` (design-tokens §4)
- [X] T011 [P] Реализовать `frontend/src/ui/Toast.tsx` + тесты `frontend/src/ui/__tests__/Toast.test.tsx`: ToastProvider + useToast, одиночный слот z-99, авто-скрытие 3000 мс, новый тост заменяет предыдущий (FR-025, data-model 1.4)
- [X] T012 [P] Реализовать `frontend/src/ui/ModalShell.tsx` + тесты `frontend/src/ui/__tests__/ModalShell.test.tsx`: фокус-ловушка, возврат фокуса на инициатора, Esc, закрытие по фону press+release (перетаскивание не закрывает), переключение formId без второй подложки (FR-026, data-model 1.6/3.3)
- [X] T013 [P] Реализовать `frontend/src/ui/ContextMenu.tsx` + тесты `frontend/src/ui/__tests__/ContextMenu.test.tsx`: позиционирование у якоря + clamp в пределы экрана (8px), клик-вне/Esc, разделители, danger-пункты, ArrowUp/Down/Enter/Space (FR-027, FR-035, data-model 1.5)
- [X] T014 Реализовать `frontend/src/ui/ConfirmDialog.tsx` + тесты `frontend/src/ui/__tests__/ConfirmDialog.test.tsx` (зависит от T012): заголовок, текст с `<b>`-выделением, кнопки обычная/основная/опасная (FR-014, SC-007, ui-behavior §3)

**Checkpoint**: Фундамент готов — дизайн-система и примитивы доступны; можно начинать истории

---

## Phase 3: User Story 1 - Рескин «машины Aethergram»: сайдбар и окно чата (Priority: P1) 🎯 MVP

**Goal**: Основные поверхности (сайдбар, окно чата) в точном соответствии прототипу при сохранении всей функциональности 004–007

**Independent Test**: Визуальное сравнение ключевых экранов с эталонными снимками по прототипу (≤ ~2% пикселей) + прогон существующих приёмочных сценариев отправки/чтения сообщений (SC-001, SC-002)

### Tests for User Story 1

- [X] T015 [P] [US1] Настроить Playwright-инфраструктуру `frontend/tests/visual/`: `playwright.config.ts` (Chromium, вьюпорты 1440×900 / 900×700 / 480×800, `maxDiffPixelRatio ≈ 0.02`), детерминированная подача fixture-данных (данные прототипа; способ — тестовый режим/фикстуры, research §B)
- [X] T016 [US1] Снять эталонные baseline-снимки с `specs/008-chat-window-styling/design/chats.html` в `frontend/tests/visual/` (зависит от T015): (а) US1-регионы — список чатов БЕЗ верхней строки табов/меню (clip-регион), заголовок/лента/композер личного чата, групповой чат; (б) полноэкранные эталоны US2+ — сайдбар с главным меню (без табов), открытое главное меню, модали «Контакты»/«Мой профиль»/создание группы, контекстные меню, тост, burger-drawer 900×700, окно чата 480×800
- [X] T017 [P] [US1] Адаптировать существующие тесты `frontend/src/chats/pages/__tests__/` и `frontend/src/chats/components/__tests__/` под новые классы-хуки прототипа без изменения поведенческих ожиданий (FR-034, SC-002; тексты «отправляется»/«не отправлено»/«Повторить»/«Удалить» сохраняются)

### Implementation for User Story 1

- [X] T018 [US1] Перестроить `frontend/src/chats/pages/MessengerPage.tsx` в корпус-«машину»: grid сайдбар+окно чата, классы machine/panel, пустое состояние «Чат не выбран»; существующие хуки/роутинг без изменений (FR-001, FR-004)
- [X] T019 [P] [US1] Перестилизовать `frontend/src/chats/components/ChatListPanel.tsx`: строка поиска и список по прототипу (табы пока остаются, рестайлинг), сохранить хук `chat-panel-search` (research §C) — проверка: тесты T017 зелёные; полноэкранный снимок сайдбара — T039
- [X] T020 [P] [US1] Перестроить `frontend/src/chats/components/ChatListItem.tsx`: аватар (Avatar), имя с усечением+title-подсказкой, превью «Вы: »/«Имя: »/«Нет сообщений», время последнего сообщения ЧЧ:ММ (пусто без сообщений), бейдж непрочитанных с капом «99+» с 100, метка «заблокирован»; порядок не меняется (FR-008, FR-009)
- [X] T021 [P] [US1] Создать `frontend/src/chats/components/ChatHeader.tsx`: аватар, имя, базовый статус на существующих данных (presence-лампа и members-tip — US3/US4) (FR-016 базовая часть)
- [X] T022 [P] [US1] Перестроить `frontend/src/chats/components/MessageList.tsx`: пузыри исходящие «золотая пластина» / входящие «тёмная панель», асимметричные радиусы, аватары отправителей, имя отправителя в групповых входящих, анимация `pop`, `React.memo` + `content-visibility: auto` (FR-018, SC-010, research §G)
- [X] T023 [US1] Оформить штампы доставки в `frontend/src/chats/components/MessageList.tsx`: ✓/✓✓ SVG «гравированные», анимация `tickStamp` только при смене статуса, массовое обновление без лавины перерисовок (FR-018, edge case)
- [X] T024 [P] [US1] Перестроить `frontend/src/chats/components/MessageInput.tsx`: золотая рама поля, кнопка «ОТПРАВИТЬ», отправка по Enter, фокус остаётся в поле (mousedown preventDefault на кнопке), существующий лимит длины 004 (FR-021)
- [X] T025 [US1] Проверить `prefers-reduced-motion` в `frontend/src/theme/machine.css` и на поверхностях US1: анимации (flick/pop/tickStamp/переходы) отключены, читаемость и статичные паттерны сохранены (US1-AS5, FR-004)
- [X] T026 [US1] Прогнать визуальную регрессию US1 по регионам T016(а) (верхняя строка сайдбара исключена: табы ещё стоят до T029, меню появится в T030) — зелёные ≤ ~2% пикселей в `frontend/tests/visual/`; полноэкранное сравнение сайдбара — в T039 (зависит от T015–T024; SC-001 в объёме US1) — проверка: `tests/visual/us1-regions.spec.ts` (5 регионов, все зелёные ≤2%). По ходу прогона: убран auth-наследный паддинг `section` у `.chat` (messenger.css), `.c-prev` стал блочным как прототипный div (chat-list-panel.css), лента получила время ЧЧ:ММ в ножку и разделители дат (T044-feed подтянут вперёд — baseline T016(а) их несёт; тесты в MessageList.test.tsx), fixture несёт имена демо-набора в username (research §B), harness T015 стабилизирован против content-visibility (allTextContents/attached)

**Checkpoint**: US1 полностью функционален и независимо тестируем — MVP готов

---

## Phase 4: User Story 2 - Навигация без табов: главное меню, модальные «Контакты» и «Мой профиль» (Priority: P1)

**Goal**: Табы удалены; все операции 004–007 достижимы через главное меню и модальные окна; заблокированный контакт блокирует композер

**Independent Test**: Сценарно: добавление/удаление контакта, блокировка/разблокировка, удаление/создание чата, инкогнито, создание группы выполняются только через меню/модали, без табов, и завершаются успешно (SC-004)

### Tests for User Story 2

- [X] T027 [P] [US2] Переписать тесты табов `frontend/src/chats/components/__tests__/ChatListPanel.test.tsx` на главное меню + модальные окна (research §D, FR-034)
- [X] T028 [P] [US2] Перенести тесты `frontend/src/chats/components/__tests__/` для ContactList/UserSearchBox в тесты ContactsModal (поиск, добавление, «⋯»-действия, идемпотентность) — поведенческие ожидания 004 сохраняются — проверка: новый `ContactsModal.test.tsx` (22 теста, TDD-красные до T031/T032): список №20 + клиентская сортировка username ru-locale + живой фильтр username/email без регистра + пустые состояния, пометки «заблокирован»/«чат удалён» из пропа chats (№12), клик с чатом → №11 → onOpenChat / без чата — без действия (Clarification), «⋯»-меню (Заблокировать/Разблокировать/Удалить чат/Создать чат/Удалить контакт — danger+разделитель, подтверждения ConfirmDialog с <b>именем</b>, тосты прототипа, сбои — список жив), форма «Добавить контакт» (№19 с триммингом, промах «требуется точное совпадение», подтверждение имя+username·email, №21 идемпотентно → №11 → onOpenChat + «Уже в контактах», сбои); ожидания 004 сохранены (сбой №20+«Повторить», №22 удаляет только запись адресной книги — FR-017); `ContactsModal.tsx` — контракт-заглушка (пропсы chats/onOpenChat) для зелёного typecheck по паттерну T027; lint+typecheck чистые, полный прогон 641 passed / 28 failed (22 T028 + 6 T027 — ожидаемо красные до своих реализаций); старый ContactList.test.tsx не тронут — удаляется в T038 вместе с ContactList/UserSearchBox

### Implementation for User Story 2

- [X] T029 [US2] Удалить табы «Чаты»/«Контакты» из `frontend/src/chats/components/ChatListPanel.tsx`; поиск фильтрует список чатов по имени (FR-006, FR-010) — проверка: тест T027 «без табов» зелёный; поиск по имени (title/логин) — ChatListPanel.group.test.tsx; из MessengerPage удалена contacts-секция (ContactList/UserSearchBox отключены от панели — файлы удаляет T038), CSS табов снят; lint+typecheck+build зелёные, test:visual US1 — 48 passed/0 failed; полный прогон 642 passed / 27 failed (5 T030 + 22 T028 — ожидаемо красные до своих реализаций)
- [X] T030 [P] [US2] Создать `frontend/src/chats/components/MainMenuButton.tsx`: кнопка у поиска, ContextMenu с пунктами «Мой профиль», «Контакты», «Создать групповой чат»; закрытие повторным кликом/клик-вне/Esc (FR-007, ui-behavior §2) — проверка: 5 тестов T027 «ChatListPanel main menu» зелёные (пункты/закрытие/колбэки форм); `.menu-btn` в `.search-row` слева от поля по прототипу §5 (CSS в chat-list-panel.css с перекрыкой auth-наследия button, hover-фон input-bg); якорь — rect кнопки, паттерн MenuHarness из ContextMenu.test; lint+typecheck+build зелёные, полный прогон 647 passed / 22 failed (22 T028 — ожидаемо красные до T031/T032)
- [X] T031 [P] [US2] Создать `frontend/src/chats/components/ContactsModal.tsx`: список, сортировка displayName → username (ru-locale), фильтр по имени/username/email без регистра, пометки «заблокирован»/«чат удалён», пустые состояния «Нет контактов»/«Ничего не найдено», «⋯»-меню (ContextMenu): Заблокировать/Разблокировать (подтверждение), Удалить чат / Создать чат, Удалить контакт (подтверждение); клик по контакту с чатом открывает чат, без чата — без действия (FR-011, FR-013) — проверка: 17 тестов T028 зелёные (список/пометки/клики/«⋯»-меню): №20 на монтировании + сбой с «Повторить» (004), клиентская сортировка username без регистра — латиница раньше кириллицы, «анна» < «Борис» (фиксация T028; компаратор byUsername на корневой коллации ICU 'und' — ru-тюнинг CLDR/full-ICU ставит кириллицу ПЕРЕД латиницей и противоречит контракту; displayName в контрактах 004–007 нет — ui-behavior §9), живой фильтр username/email без регистра, «Нет контактов — добавьте первого»/«Ничего не найдено»; пометки из пропа chats (№12 blockedByMe/наличие direct-чата), клик с чатом → №11 ensureChat → onOpenChat(ChatView), без чата — без действия (Clarification FR-013), сбой №11 — .modal-err при живом списке; «⋯»-меню по blockedByMe/чату: Заблокировать/Разблокировать, Удалить чат/Создать чат, Удалить контакт (danger + единственный .ctx-sep), подтверждения ConfirmDialog с <b>именем</b> (SC-007, отмена — без действия), тосты «Контакт заблокирован/разблокирован — {username}», «Чат удалён — контакт сохранён», «Контакт удалён — чат сохранён»; №22 убирает строку локально — чат/история не тронуты (FR-017), диалог не открывается; DOM — дословно §9 прототипа (.pick-row.ctc-row, #ctcSearch → .ctc-search, кебаб .c-menu с stopPropagation от клика-строки, #ctcList → .ctc-list 280px), стили в новом contacts-modal.css (базовые .pick-list/.pick-row/.pick-empty/.modal-err/.c-menu для T035+, перекрыка auth-наследия index.css по специфичности 0-3-0, focus-ring FR-035, Row/Enter/Space-активация строк); lint+typecheck+build зелёные, полный прогон 664 passed / 5 failed (5 T032 — ожидаемо красные до формы «Добавить контакт»)
- [X] T032 [US2] Добавить форму «Добавить контакт» в `frontend/src/chats/components/ContactsModal.tsx`: точное совпадение username/email без регистра (№19 searchUsers), ошибка «требуется точное совпадение» при промахе, идемпотентность «Уже в контактах» + тост, подтверждение (имя, username · email), открытие переписки (FR-012, ui-behavior §3) — проверка: 5 тестов T032 блока T028 зелёные (22/22 файла): кнопка `#ctcAdd` (.m-btn primary «Добавить контакт» под списком) → форма #addForm прототипа (label «Username или email — точное совпадание» + .add-query + «Отмена»/submit «Добавить»), №19 с триммингом и пустым запросом без похода на сервер (004), промах — «Пользователь не найден — требуется точное совпадение username или email» без №21, находка — ConfirmDialog `<b>имя</b>` + `.confirm-sub` «username · email» (sub — ReactNode внутри text, ConfirmDialog не менялся), подтверждение → №21 → идемпотентный тост «Уже в контактах — {username}»/«Контакт добавлен — {username}» (по текущей книге) → №11 → onOpenChat + рефетч №20, сбой №21 — .modal-err при живом списке; по ходу прогона стабилизирован харнесс трёх тестов T028 (синхронное чтение `.confirm-form` сразу после submit-клика невозможно — №19 асинхронен, sync-act не флашит микротаски; вставлен `await waitFor` до чтения формы, поведенческие ожидания неизменны — паттерн стабилизации T026); lint+typecheck+build зелёные, полный прогон 669 passed / 0 failed, test:visual 48 passed / 0 failed (сайдбар/US1 не затронуты — модаль монтируется в T034)
- [X] T033 [P] [US2] Создать `frontend/src/chats/components/ProfileModal.tsx`: username/email readonly (`GET /users/me`), переключатель «Режим инкогнито» (№38), Сохранить → PUT → тост (FR-015) — проверка: 8 тестов `ProfileModal.test.tsx` зелёные (TDD-красные до реализации): username/email — readonly-строки .modal-ro из getCurrentUser (GET /users/me, email-фолбэк «—», полей ввода нет — имя НЕ редактируется, 008a), №38 GET на монтировании отражает персистентный режим, Сохранить → PUT updatePresenceSettings → ровно один тост «Профиль обновлён — {username}[ · инкогнито: вы offline для всех]» + onClose (submit→closeModal прототипа), сбой PUT — .modal-err + откат чекбокса к серверному значению (поведение 007 PresenceSettingsPage, FR-034) без тоста/закрытия, сбой загрузки — .modal-err + «Повторить» с рефетчем обоих источников, «Отмена» — onClose без PUT; DOM — проекция #profileForm прототипа минус имя-поле (.tgl-row/.tgl-txt с подписью «Всем участникам вы отображаетесь как offline», .modal-btns «Отмена»/«Сохранить»), стили в новом profile-modal.css (.modal-ro определены здесь для T035+, токены FR-002); по ходу: test-results добавлен в .prettierignore (артефакты убитого visual-прогона валили lint до T033); lint+typecheck+build зелёные, полный прогон 677 passed / 0 failed (модаль монтируется в T034 — сайдбар/US1 не затронуты)
- [X] T034 [US2] Интегрировать единую ModalShell в `frontend/src/chats/pages/MessengerPage.tsx`: формы contacts / add-contact / profile / create-group / confirm, переключение форм без наложения фонов (edge case, data-model 3.3) — проверка: 7 тестов нового `MessengerPage.modal.test.tsx` зелёные (TDD-красные до реализации): меню сайдбара (T030-колбэки ChatListPanel → MessengerPage) открывает contacts («Контакты», поиск/список №20, Esc закрывает) / profile («Мой профиль», readonly username/email + №38) / create-group («Новый групповой чат» — промежуточный обитатель №27 CreateGroupDialog до grpForm T035; кнопка-вход из сайдбара удалена — вход только пункт меню, ui-behavior §2); инлайн-«Добавить контакт» поднимается в formId 'add-contact' через onFormChange ContactsModal (заголовок/фокус сменяются, .modal-back всегда ОДИН — edge case 3.3); подтверждение «Действий» чата — formId 'confirm' поверх оболочки (ui/ConfirmDialog с <b>именем</b>, danger-кнопка, SC-007; Отмена/Esc — без действия, подтверждение №14 → окно к «Чат не выбран»); открытие переписки из «Контактов» закрывает оболочку (closeModal+selectChat прототипа) + рефетч №12; ToastProvider страницы — тост-слот z-99 над модалью (ui-behavior §5); закрытие единообразно — Esc/фон press+release/«Отмена»/успех submit → closeShell; адаптирован MessengerPage.group.test.tsx (вход создания группы — пункт меню, ожидания №27/№12/окна сохранены); lint+typecheck+build зелёные, полный прогон 684 passed / 0 failed, test:visual 48 passed / 0 failed (сайдбар/US1-регионы не затронуты: panel-actions не входил ни в один регион T016(а))
- [X] T035 [P] [US2] Перевести `frontend/src/groups/components/CreateGroupDialog.tsx` в модальную форму grpForm на ModalShell: название + выбор участников из контактов (валидация 006, пустые состояния), создание №27 → открытие чата + тост (research §D, ui-behavior §3) — проверка: 12 тестов переписанного `CreateGroupDialog.test.tsx` зелёные (TDD-красные до реализации): проекция #grpForm прототипа (label «Название» + плейсхолдер «Название группового чата», «Участники» + фильтр «Поиск контакта — имя, username или email», строки .pick-row с чекбоксом `Выбрать {username}`/аватаром/подписью «username · email», .modal-btns «Отмена»/«Создать»; поля описания в создании НЕТ — прототип его не несёт, описание — grpEditForm T056), живой фильтр username/email без регистра с сохранением выбора, пустые состояния «Нет контактов — сначала добавьте контакт»/«Ничего не найдено», сбой №20 — .modal-err + «Повторить»; валидация в порядке прототипа — сперва ≥1 участника («Выберите хотя бы одного участника из контактов» — норматив FR-001; клиентская, №27 по-прежнему принимает пустой batch — контракт не тронут), затем validateGroupTitle 006 (trim 1–64, /64/-тексты); №27 — точное тело {title, memberUserIds в порядке выбора} без description, успех — ровно один тост «Групповой чат создан — {title}» (FR-025, слот ToastProvider страницы) + onCreated → closeShell/№12/окно группы, сбой 422 not_in_contacts — .modal-err при живом черновике; адаптированы MessengerPage.group/modal.test.tsx (диалог «Новый групповой чат», метка «Название», кнопка «Создать», тост) без изменения ожиданий №27; стили — новый create-group.css (колонка/поля #grpName/#grpSearch дословно .modal input, чекбокс .pick-row input золотой accent — определён здесь для T056+), тело строки .c-* в contacts-modal.css перескоуплено .contacts-form → .pick-row для переиспользования пикерами; lint+typecheck+build зелёные, полный прогон 687 passed / 0 failed, test:visual 48 passed / 0 failed (сайдбар/US1-регионы не затронуты)
- [X] T036 [P] [US2] Реализовать состояние заблокированного контакта в `frontend/src/chats/components/MessageInput.tsx`: поле и кнопка disabled + подсказка о блокировке (единственная блокировка ввода), разблокировка возвращает активность (FR-022, US2-AS5) — проверка: 4 теста MessageInput.test.tsx + 1 интеграционный MessengerPage.modal.test.tsx зелёные (TDD-красные до реализации): проп `blocked` — проекция §7 updateInputState() прототипа дословно (поле и «ОТПРАВИТЬ» disabled, плейсхолдер-подсказка «Контакт заблокирован — разблокируйте, чтобы писать сообщения», CSS :disabled уже из T024), onSend недостижим по обоим путям submit, rerender blocked→false возвращает активность + обычный «Сообщение…», независимость от 004-провода `disabled`; страница передаёт `blocked={direct && blockedByMe}` — сквозной сценарий US2-AS5 (№24 через «Действия» → confirm → оптимистический сброс + рефетч №12 сходятся к активному композеру; тест жёсток к порядку эффектов — мок №12 мутируем, waitFor ждёт конечное состояние); CSS не менялся (статические :disabled из T024 = прототип); lint+typecheck+build зелёные, полный прогон 692 passed / 0 failed, test:visual 48 passed / 0 failed (композер в не-заблокированном состоянии DOM не меняет — снимки T026 стабильны)
- [X] T037 [US2] Пустое состояние «Чат не выбран» в `frontend/src/chats/pages/MessengerPage.tsx` после удаления открытого чата/выхода из группы — без автоперехода (Clarification, edge case) — проверка: ручной сценарий quickstart D5 (последний шаг) + существующие тесты chats/ в адаптации T017 — реализовано: недостающий путь №14 — «Удалить чат» из «Контактов» (T031 не сообщал странице): ContactsModal получил проп `onChatDeleted(chatId)` (успех №14 → модаль жива, контакт сохранён FR-017), MessengerPage `handleChatDeleted` — outbox.purgeChat (FR-021), окно к «Чат не выбран» ТОЛЬКО если удалён открытый чат (соседний не открывается сам — без автоперехода), рефетч №12; пути заголовка (№14 через confirm), выхода/удаления группы (LeaveDeleteControls + фреймы group.you_removed/group.deleted) уже сходились к null из T034/T066; тесты: 2 новых MessengerPage.modal.test.tsx (открытый чат → «Чат не выбран» + bob НЕ открыт автопереходом + №12 рефетч; неоткрытый чат → окно не тронуто) + onChatDeleted-контракт в ContactsModal.test.tsx (TDD-красные до реализации); lint+typecheck+build зелёные, полный прогон 694 passed / 0 failed, test:visual 48 passed / 0 failed (DOM/стили пустого состояния не менялись — снимки стабильны)
- [X] T038 [US2] Удалить `frontend/src/chats/components/ContactList.tsx` и `frontend/src/chats/components/UserSearchBox.tsx` (функции мигрированы в ContactsModal) вместе с их тестовыми файлами — проверка: grep по frontend (src + css, вне node_modules/test-results) — ссылок не осталось (единственное упоминание — исторический комментарий миграции в ContactsModal.test.tsx, сохранён); тестового файла UserSearchBox не существовало, ContactList.test.tsx удалён вместе с компонентами (−10 тестов, ожидаемо); lint+typecheck+build зелёные, полный прогон 684 passed / 0 failed (58 файлов), test:visual 48 passed / 0 failed / 42 skipped (US2+ baseline — T039; сайдбар/US1-регионы не затронуты — компоненты отключены от панели ещё в T029)
- [X] T039 [US2] Добавить визуальные снимки US2 в `frontend/tests/visual/` против полноэкранных baseline T016(б): сайдбар целиком (без табов, с меню), открытое главное меню, модали «Контакты», «Мой профиль», создание группы (SC-001) — проверка: новый `tests/visual/us2-fullscreens.spec.ts` (5 полноэкранных снимков @1440×900, все зелёные ≤2% `maxDiffPixelRatio` конфига; паритет состояний — «alex» открыт перед каждым снимком, меню/модали открываются тем же путём клика, что и харнесс T016, SHOT-рецепт T016/T026). По ходу прогона: (1) фикс реального бага ContextMenu — в настоящем Chromium React 19 сбрасывает пассивные эффекты доверенного дискретного события ещё внутри его доставки, и слушатель клика-вне успевал услышать САМ клик, открывший меню (мгновенное самозакрытие главного меню и «⋯»-меню; jsdom/fireEvent эффекты откладывает — юнит-тесты этого не видели) — введена метка времени: клики с timeStamp < момента монтирования слушателя игнорируются как открывающие; (2) fixture №20 отвечает порядком объявления прототипа c1…c10 (раньше сортировал username) — подборщик #grpMembers прототипа итерирует контакты в порядке объявления, приложение №20 не пересортирует (контракт серверного владения порядком), ContactsModal не затронута (клиентская byUsername-сортировка T031); (3) перекрыты утечки auth-наследия `form div{flex-direction:column;gap:4px}` index.css в обитателях-<form> оболочки: `.modal-btns` явно строка (ui/confirm-dialog.css — кнопки «Отмена»/«Создать» укладывались столбиком, +42px высоты модали), `.pick-row .c-main` явно блок и `.c-top` явно строка (contacts-modal.css, +2px на строку пикера); lint+typecheck+build зелёные, полный прогон 684 passed / 0 failed, test:visual 53 passed / 0 failed / 52 skipped (48 US1+baseline + 5 новых T039)

**Checkpoint**: US1 и US2 работают независимо; вся функциональность 004–007 достижима без табов

---

## Phase 5: User Story 3 - UI-слой: аватары, время, разделители дат, тосты, контекстные меню (Priority: P2)

**Goal**: Обогащение поверхностей деталями прототипа на существующих данных: presence-точки, время сообщений, разделители дат, ровно один тост на операцию, корректное позиционирование меню

**Independent Test**: Визуальное сравнение + сценарно: аватары/время/разделители по прототипу; каждая операция — ровно один тост; меню позиционируются в пределах экрана и закрываются по клику-вне/Esc

### Tests for User Story 3

- [X] T040 [P] [US3] Тесты presence-отображения в `frontend/src/chats/components/__tests__/`: точка online/offline/unknown на аватарах, статус заголовка «В сети»/нейтральное «неизвестно» без ложного «офлайн» (US3-AS2, семантика 007) — проверка: новый `presence-display.test.tsx` (11 тестов, TDD-красные до T042/T043 — 10 failed ожидаемо): контракт фиксирован — статус ТОЛЬКО из presenceStore 007 (usePresenceStatus per-peer: peer.id / contact.user.id / chat.peerId); ChatListItem — точка `.av-dot`/`.off`/`.unknown` на аватарах direct-строк (базовый класс = online, aria «онлайн»/«офлайн»/«неизвестно»), отсутствие записи в store (до первого №36) — нейтральная точка БЕЗ `.off`, групповая строка — точки нет; ContactsModal — per-contact точки трёх состояний + все-нейтральные до №36 (ни одной ложной `.off`); ChatHeader direct — прототипная `.status-row .lamp` (`.off` только для настоящего offline) + `.status-txt` «В сети»/«офлайн»/«неизвестно», unknown без текста «офлайн» (edge case 007); usePresence замокан Map-хранилищем (defaults 'unknown'), api/chats — по паттерну ContactsModal.test; lint+typecheck зелёные, полный прогон 685 passed / 10 failed (все 10 — T040, ожидаемо красные до T042/T043; +1 зелёный guard «группа без точки»)
- [X] T041 [P] [US3] Тесты разделителей дат в `frontend/src/chats/components/__tests__/MessageList.test.tsx`: один разделитель между днями, полночь — новый разделитель, ровно один на стыке страниц (US3-AS3, data-model 1.3) — проверка: новый describe-блок «date dividers at pagination junctions» (+3 теста к 5 baseline-parity тестам T026, покрывавшим дневные прогоны/полночь/same-day-слияние/скрытие верхнего): (1) кросс-дневной стык страниц через rerender-подгрузку ровно как loadOlder/MessengerPage — старшая страница 19.09 prepend над окном 20.09: ровно ОДИН разделитель на стыке с меткой formatDate первого сообщения НОВОЙ страницы (цель сравнения data-model 1.3; rowSketch из `ol > li` пинит позицию между последней строкой prepend-страницы и первой строкой окна — наивный per-page-разделитель дал бы два), исчерпание истории (hasOlder→false) открывает верхний разделитель prepend-дня, стыковый остаётся единственным; (2) same-day prepend — на границе страниц разделителя НЕТ (0 при hasOlder, 1 верхний при исчерпании), разделитель — на дневной прогон, не на границу страниц; (3) пустая лента — разделителей нет: пустое состояние без списка и лента только из optimistic-строк (PendingMessage без createdAt — разделителю неоткуда взяться); тесты зелёные против реализации, подтянутой вперёд в T026 (T044-feed); lint+typecheck+build зелёные, файл 24 passed, полный прогон 688 passed / 10 failed (все 10 — T040, ожидаемо красные до T042/T043), test:visual не затронут (0 изменений src — снимки стабильны)

### Implementation for User Story 3

- [ ] T042 [P] [US3] Встроить presence-точки из presenceStore в Avatar: `frontend/src/chats/components/ChatListItem.tsx` (личные чаты) и `frontend/src/chats/components/ContactsModal.tsx` (контакты) — зелёная мерцающая / тусклая / нейтральная (FR-024, design-tokens §4)
- [ ] T043 [P] [US3] Достроить статус `frontend/src/chats/components/ChatHeader.tsx`: direct — presence-лампа + «В сети»/нейтральное «неизвестно»; group — «N участников» (pluralRu) + members-tip по hover/focus статуса («Вы» первым, пометки «администратор») (FR-016, FR-017)
- [ ] T044 [P] [US3] Добавить время ЧЧ:ММ в ножку каждого пузыря и разделители дат (formatDate) между сообщениями разных дней в `frontend/src/chats/components/MessageList.tsx` (FR-019)
- [ ] T045 [US3] Гарантировать ровно один тост (~3 с) на каждую завершающуюся операцию во всех точках (контакты, профиль, группы, блокировки) — аудит вызовов useToast в `frontend/src/chats/` и `frontend/src/groups/` (FR-025, US3-AS4)
- [ ] T046 [US3] Проверить позиционирование меню у края экрана (clamp, не обрезается), danger-пункты, порядок закрытия Esc сверху вниз: ctx-menu/members-tip → модаль → drawer в `frontend/src/ui/ContextMenu.tsx` и потребителях (FR-027, edge case)
- [ ] T047 [US3] Добавить визуальные снимки US3 в `frontend/tests/visual/` против baseline T016(б): аватары с presence, лента с временем и разделителями дат, members-tip (SC-001)

**Checkpoint**: US1–US3 независимо функциональны; облик соответствует прототипу

---

## Phase 6: User Story 4 - Стилизованные состояния доставки и устойчивости 005–007 (Priority: P2)

**Goal**: Все функции 005–007 (outbox, синхронизация, пагинация, роли групп, настройки) сохранены и оформлены в дизайн-систему

**Independent Test**: Прогон существующих приёмочных сценариев 005–007 (сбой отправки, переполнение очереди, флуд-лимит, разрыв связи, длинная история, управление группой, настройки) на новом UI — каждый успешен, состояния в дизайн-системе (SC-002, SC-004)

### Tests for User Story 4

- [ ] T048 [P] [US4] Адаптировать тесты outbox/sync/пагинации в `frontend/src/chats/__tests__/` и `frontend/src/chats/components/__tests__/` под новый DOM: статусы «отправляется», «не отправлено», «Повторить», «Удалить», баннеры, идемпотентность ретраев — ожидания 005 без изменений (FR-030, FR-034)
- [ ] T049 [P] [US4] Перенести тесты GroupInfoPanel/MemberList/AddMembersPicker из `frontend/src/groups/components/__tests__/` в тесты GroupMembersModal/GroupEditModal/ChatGearMenu (ростер-действия и роли 006 без изменений) (research §D)

### Implementation for User Story 4

- [ ] T050 [P] [US4] Оформить outbox-состояния на пузырях в `frontend/src/chats/components/MessageList.tsx`: «отправляется» статично, «не отправлено» + Повторить/Удалить в дизайн-системе; повтор сохраняет идемпотентность clientMessageId (FR-030, US4-AS1)
- [ ] T051 [P] [US4] Добавить строку статуса ретрая флуд-лимита в `frontend/src/chats/components/MessageInput.tsx`: «Повтор через N с» из головной retryAt-записи открытого чата, тикает один элемент, ввод остаётся активным (FR-030, Clarification)
- [ ] T052 [P] [US4] Перестилизовать `frontend/src/chats/components/QueueOverflowBanner.tsx`, `frontend/src/chats/components/ErrorBanner.tsx`, `frontend/src/chats/components/SyncIndicator.tsx` в токены дизайн-системы, позиции сохранить (FR-030) — проверка: тесты T048 зелёные; baseline — реализованные снимки T060 (исключение SC-001)
- [ ] T053 [US4] Проверить пагинацию в `frontend/src/chats/components/MessageList.tsx`: подгрузка старых при прокрутке вверх с сохранением якоря скролла, разделители дат и порядок корректны на стыке страниц (FR-020, US4-AS4)
- [ ] T054 [P] [US4] Создать `frontend/src/chats/components/ChatGearMenu.tsx`: direct — «Добавить в контакты» (если нет), «Заблокировать/Разблокировать» (подтверждение), «Удалить чат» (подтверждение); группа owner/admin — «Участники», «Редактировать чат», «Удалить чат»; member — «Участники», «Выйти из чата»; встроить кнопку-«шестерёнку» с этим меню в `frontend/src/chats/components/ChatHeader.tsx` (FR-016: единственная кнопка заголовка; FR-023, ui-behavior §4)
- [ ] T055 [P] [US4] Создать `frontend/src/groups/components/GroupMembersModal.tsx`: список с ролями, ростер-действия по иерархии 006 (kick/роль/передача владения — owner/admin), «Добавить в контакты» для собеседника вне контактов, «Вы» не выводится (FR-023, ui-behavior §3)
- [ ] T056 [P] [US4] Создать `frontend/src/groups/components/GroupEditModal.tsx`: название/описание/состав (добавление «+», удаление «✕»), валидация 006 (≥1 участник, title 1–64, description ≤256), PATCH №29 + №31/32, аватар группы пересчитывается при переименовании (FR-023)
- [ ] T057 [US4] Удалить `frontend/src/groups/components/GroupInfoPanel.tsx` и `LeaveDeleteControls.tsx` (заменены ChatGearMenu + модалями), переиспользовать MemberList/AddMembersPicker внутри модалей; обновить импорты в `frontend/src/chats/pages/MessengerPage.tsx` — проверка: перенесённые тесты T049 зелёные, `pnpm test` без битых импортов
- [ ] T058 [P] [US4] Перестилизовать страницы настроек `frontend/src/settings/pages/PresenceSettingsPage.tsx` и `frontend/src/settings/pages/SecurityPage.tsx` в дизайн-систему; маршруты и функции без изменений (FR-031) — проверка: тесты `settings/pages/__tests__/` зелёные без изменения ожиданий (FR-034)
- [ ] T059 [US4] Единый стиль пустых/загрузочных/ошибочных состояний: «Нет сообщений», «Ничего не найдено», «Диалогов пока нет», сбой загрузки, «Чат не выбран» — на токенах в `frontend/src/chats/components/` (FR-032) — проверка: пустые состояния — ручной прогон T072 (quickstart) + тесты T017/T028
- [ ] T060 [US4] Добавить визуальные снимки US4 в `frontend/tests/visual/` против baseline T016(б): модали участников/редактирования группы, меню «шестерёнки» (SC-001). Outbox-состояния и индикатор синхронизации — baseline фиксируется по реализованным снимкам в дизайн-токенах (исключение: в статическом прототипе этих состояний нет)

**Checkpoint**: Все истории US1–US4 независимо функциональны; функциональность 004–007 полностью сохранена

---

## Phase 7: User Story 5 - Мобильный burger-drawer и звуковое оповещение приёма (Priority: P3)

**Goal**: Адаптив ≤900px/≤480px с drawer и visual viewport; WebAudio-звоночек приёма с коалесцированием

**Independent Test**: На узком экране burger открывает/закрывает drawer, выбор чата закрывает; на широком — прокрутки страницы нет; сигнал приёма звучит (включая фоновые чаты) один на событие/пакет, на отправке нет, при запрете автозвука функциональность не нарушается

### Tests for User Story 5

- [ ] T061 [P] [US5] Тесты `frontend/src/ui/__tests__/sound.test.ts`: realtime 1:1 на входящее (любой чат), sync-batch — один сигнал на пакет, история/пагинация/отправка — без сигнала, запрет автозвука — молчаливый пропуск (FR-028, research §F)
- [ ] T062 [P] [US5] Тесты drawer в `frontend/src/chats/pages/__tests__/`: открытие burger'ом, закрытие выбором чата/кликом по затемнению/повторным burger/Esc (если выше нет слоёв) (FR-029, data-model 3.1)

### Implementation for User Story 5

- [ ] T063 [US5] Реализовать `frontend/src/ui/sound.ts`: WebAudio-синтез «динь-динь» (партиалы 1:2.76:5.4, 1050 Гц t=0 / 1400 Гц t+0.1 с), ленивый AudioContext + resume(), все ошибки — молчаливый try/catch (зависит от T061; FR-028)
- [ ] T064 [US5] Подключить звук в `frontend/src/chats/pages/MessengerPage.tsx`: realtime `onMessageCreated` (senderId ≠ me, любой чат включая фоновые), один сигнал на цикл массовой доставки в sync-пути, исключения — история/пагинация/отправка (FR-028, research §F)
- [ ] T065 [US5] Реализовать burger + drawer в `frontend/src/chats/pages/MessengerPage.tsx` и `frontend/src/theme/machine.css` (≤900px): sidebar fixed `min(320px, 85vw)`, transform -120% ↔ 0 (.3s), backdrop z-35, burger fixed 42px (FR-029, design-tokens §9)
- [ ] T066 [US5] Обеспечить ≥901px отсутствие прокрутки страницы (`html,body{overflow:hidden}`, машина в окне) и адаптацию к visual viewport (слушатель `--vvh`/`--vvo` на body) в `frontend/src/theme/machine.css` и `frontend/src/chats/pages/MessengerPage.tsx`: композер над клавиатурой, лента сжимается (FR-029, research §H) — проверка: quickstart E1–E2 (SC-005); мобильные снимки — T068
- [ ] T067 [US5] Оформить ≤480px в `frontend/src/theme/machine.css` и `frontend/src/chats/components/`: компактная кнопка «ОТПРАВИТЬ», пузырь до 84% ширины ленты (US5-AS5, design-tokens §9) — проверка: quickstart E-сценарии на ~480px (US5-AS5); снимок 480×800 — T068
- [ ] T068 [US5] Добавить мобильные визуальные снимки в `frontend/tests/visual/` против baseline T016(б): открытый burger-drawer 900×700, окно чата 480×800 (SC-001, Clarification)

**Checkpoint**: Все истории US1–US5 независимо функциональны

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные качества: производительность, клавиатура, чистота токенов, приёмка

- [ ] T069 [P] Профилирование производительности на fixture-данных `frontend/tests/visual/` (SC-010/SC-011): лента 1000+ сообщений и список 200+ чатов — 60 fps, первый рендер чата ≤1 с (DevTools Performance / Playwright trace); при недостижении — отдельная задача виртуализации с обоснованием (research §G); измерение — Chromium из T015 (SC-010/SC-011 требуют «на браузере матрицы FR-036» — одним из, что Chromium закрывает)
- [ ] T070 [P] Приёмочный прогон «без мыши» по `frontend/src/ui/` и `frontend/src/chats/` (quickstart E4, SC-008): Tab/Enter/Space/Esc/стрелки по всем интерактивам, видимый focus-ring, возврат фокуса на инициатора после меню/модалей/drawer (FR-035)
- [ ] T071 [P] Аудит токенов (FR-002): отсутствие литеральных визуальных констант вне `frontend/src/theme/tokens.css` (палитра/паттерны/шрифтовые роли) — data-model 1.1
- [ ] T072 Прогнать валидацию quickstart.md целиком: сборка, lint, typecheck, test, test:visual, ручные сценарии A–E, браузерная матрица FR-036/SC-009, SC-006 (кириллица+латиница без «тофу»); экспресс-проверка плавности ленты 1000+ и списка 200+ (SC-010/SC-011) в остальных браузерах матрицы FR-036 — ручная, без инструментального измерения — сам является приёмочной проверкой; критерии прохождения — quickstart.md «Критерии прохождения»
- [ ] T073 Полная регрессия и контроль контрактов: `pnpm lint` + `pnpm typecheck` + `pnpm test` + `pnpm build` в frontend/; SC-003 — job contract (oasdiff) в CI без отличий, `pnpm generate:api` без diff; FR-033 — статическая проверка: 0 вхождений `dangerouslySetInnerHTML` в `frontend/src/` (grep)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Без зависимостей — начинать немедленно
- **Foundational (Phase 2)**: Зависит от Phase 1 — БЛОКИРУЕТ все истории
- **User Stories (Phases 3–7)**: Все зависят от Foundational
  - Рекомендуемый порядок: US1 (P1) → US2 (P1) → US3 (P2) → US4 (P2) → US5 (P3)
  - US2 и далее частично параллелизуемы (см. ниже), но затрагивают общие файлы (MessengerPage.tsx, MessageList.tsx, MessageInput.tsx) — одним агентом выполняются последовательно (конституция, Development Workflow)
- **Polish (Phase 8)**: Зависит от завершения всех историй

### User Story Dependencies

- **US1 (P1)**: После Phase 2 — нет зависимостей от других историй (MVP)
- **US2 (P1)**: После Phase 2; наслаивается на surfaces US1 (рассчитывает на перестроенные ChatListPanel/MessageInput) — фактически после US1
- **US3 (P2)**: После Phase 2; независимо тестируем, но обогащает поверхности US1/US2 (Avatar-точки, заголовок, лента, тосты) — фактически после US2
- **US4 (P2)**: После Phase 2; использует ModalShell/ContextMenu/ConfirmDialog и поверхности US1–US3 (gear-меню, модали групп, композер) — фактически после US3
- **US5 (P3)**: После Phase 2; drawer меняет MessengerPage, звук — события 005 — фактически после US4

### Within Each User Story

- Тесты пишутся ДО/одновременно с реализацией (конституция VI) и падают до реализации
- Примитивы → поверхности → интеграция → визуальные снимки
- История завершена checkpoint'ом: независимое тестирование до перехода к следующей

### Parallel Opportunities

- T003 параллельно с T001–T002; T005, T008, T009, T011, T012, T013 параллельно внутри Phase 2
- T015/T017, T019–T022, T024 параллельно внутри US1 (разные файлы)
- T027/T028, T030/T031/T033/T035/T036 параллельно внутри US2
- T040/T041, T042/T043/T044 параллельно внутри US3
- T048/T049, T050–T052, T054–T056, T058 параллельно внутри US4
- T061/T062 параллельно в US5; T069/T070/T071 параллельно в Phase 8
- Разные истории — разными исполнителями после Phase 2 (при согласовании общих файлов MessengerPage/MessageList/MessageInput)

---

## Parallel Example: User Story 1

```bash
# Тесты и инфраструктура US1 параллельно:
Task: "T015 [P] [US1] Настроить Playwright-инфраструктуру frontend/tests/visual/"
Task: "T017 [P] [US1] Адаптировать существующие тесты chats/"

# Поверхности US1 параллельно (разные файлы):
Task: "T019 [P] [US1] Перестилизовать frontend/src/chats/components/ChatListPanel.tsx"
Task: "T020 [P] [US1] Перестроить frontend/src/chats/components/ChatListItem.tsx"
Task: "T021 [P] [US1] Создать frontend/src/chats/components/ChatHeader.tsx"
Task: "T022 [P] [US1] Перестроить frontend/src/chats/components/MessageList.tsx"
Task: "T024 [P] [US1] Перестроить frontend/src/chats/components/MessageInput.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (шрифты, Playwright, каталоги)
2. Complete Phase 2: Foundational (токены, машина, UI-примитивы) — CRITICAL, блокирует всё
3. Complete Phase 3: User Story 1 (рескин + визуальная регрессия)
4. **STOP and VALIDATE**: SC-001 в объёме US1-регионов T016(а) + SC-002 (существующие сценарии) — независимо; полноэкранный SC-001 сайдбара — после US2 (T039)
5. Deploy/demo если готово

### Incremental Delivery

1. Setup + Foundational → фундамент дизайн-системы
2. + US1 → MVP: утверждённый облик с работающим мессенджером
3. + US2 → полная достижимость функций 004–007 без табов (по спеке — обязательное продолжение US1 в одном релизе)
4. + US3 → детали облика: presence, время, разделители, тосты, меню
5. + US4 → гарантия сохранности 005–007 в дизайн-системе
6. + US5 → адаптив и звук; Polish — приёмка по quickstart.md

### Parallel Team Strategy

1. Команда проходит Setup + Foundational вместе
2. После Foundational (координация общих файлов MessengerPage/MessageList/MessageInput):
   - Разработчик A: US1 → US3 (поверхности)
   - Разработчик B: US2 → US4 (модали/состояния)
   - Разработчик C: US5 после готовности поверхностей
3. Каждая история завершается независимым checkpoint-тестированием

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story] метки связывают задачи с историями для трассировки
- 0 изменений API-контрактов (SC-003): backend, `contracts/openapi.yaml`, `src/api/schema.d.ts` не затрагиваются
- Существующие тестовые классы-хуки (`chat-item`, `message-list`, `chat-panel-search`, …) сохраняются как обёртки прототипных классов (FR-034, research §C)
- Тексты статусов 004–007 («отправляется», «не отправлено», «Повторить», «Удалить», «доставлено», «прочитано») сохраняются видимыми или в aria-label (SC-002, ui-behavior §1)
- `dangerouslySetInnerHTML` с пользовательскими данными запрещён (FR-033)
- Виртуализация списков — только по факту недостижения 60 fps (T069, YAGNI)
- Коммит после каждой задачи или логической группы; статус отмечается в tasks.md
- Остановка на любом checkpoint для независимой валидации истории
