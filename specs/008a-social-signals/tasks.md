# Tasks: 008a-social-signals — Typing-индикатор, lastSeen, отображаемые имена, per-chat звук

**Input**: Design documents from `/specs/008a-social-signals/` (plan.md, spec.md, research.md, data-model.md, contracts/api-contract.md, contracts/realtime-events.md, contracts/ui-behavior.md, quickstart.md)

**Prerequisites**: план (структура/решения), спека (US1–US4), контракты (операции №39–№42, события, тайминги), модель данных (V16, Redis-ключи).

**Tests**: ОБЯЗАТЕЛЬНЫ — конституция VI (Test-First, NON-NEGOTIABLE): IT пишутся до/параллельно реализации и должны быть зелёными для закрытия задачи; Vitest/Playwright — по ui-behavior.md §6; нагрузочный smoke — SC-008.

**Organization**: задачи сгруппированы по user stories (US1 P1 → US2 P1 → US3 P2 → US4 P3), каждая история — независимо реализуемый и тестируемый инкремент. Все изменения контракта строго аддитивные (SC-007).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: параллелизуемо (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: US1/US2/US3/US4; Setup/Foundational/Polish — без метки
- Все пути — от корня monorepo (backend/, frontend/, contracts/, load/)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Базовая линия зелёная перед началом (конституция I — код только по задачам)

- [ ] T001 Проверить базовую линию: ветка `008a-social-signals`; поднять зависимости `docker compose -f deploy/local/docker-compose.yml up -d`; `./gradlew check` (из `backend/`) зелёно; `pnpm --dir frontend lint && pnpm --dir frontend typecheck && pnpm --dir frontend test` зелёно; `scripts/validate-contracts.sh` = 0 ERR — зафиксировать базовую линию (SC-007)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Контракт 0.9.0 + миграция V16 + конфигурация — блокируют ВСЕ user stories

**⚠️ CRITICAL**: Ни одна user story не начинается до завершения фазы (контракт — источник истинных типов, миграция — колонки US1/US4; US3 — только Redis)

- [ ] T002 Поднять версию `contracts/openapi.yaml` 0.8.0 → 0.9.0 и дополнить `info.description` параграфом «Фича 008a…» (кумулятивный лог конвенции 001, см. specs/008a-social-signals/contracts/api-contract.md шапку)
- [ ] T003 Добавить операции №39 `PUT /api/v1/users/me/profile` (updateMyProfile, ProfileUpdateRequest `displayName?: string | null`, 200 PublicUser, 400 `invalid_display_name`, 429 `flood_limit` + Retry-After) и №40 `PUT /api/v1/contacts/{userId}/alias` (setContactAlias, AliasUpdateRequest `alias?: string | null`, 200 ContactView, 400 `invalid_alias`, 404 `contact_not_found`) со схемами и problem-кодами в `contracts/openapi.yaml` (полные спецификации — specs/008a-social-signals/contracts/api-contract.md §1)
- [ ] T004 Добавить операции №41 `POST /api/v1/chats/{chatId}/typing` (sendTypingSignal, TypingRequest `action: start|stop`, 204, 400 `invalid_action`, 403/404 коды чата как №16) и №42 `PUT /api/v1/chats/{chatId}/sound` (setChatSound, ChatSoundRequest `enabled: boolean`, 200 ChatSoundResponse) со схемами в `contracts/openapi.yaml`; в description №41 зафиксировать нормативные константы: окно повтора клиента 3 с, окно молчания 5 с, серверный TTL состояния 8 с, страховый таймаут наблюдателя 10 с, флуд 60/мин (api-contract.md §1, realtime-events.md §3; Assumption спеки — фиксация таймингов в публичном контракте)
- [ ] T005 Добавить в `contracts/openapi.yaml` опциональные поля существующих схем и новые события: `PublicUser.displayName?`, `ContactView.alias?`, peer-фрагменты №11/№12/№13 (`displayName?`,`alias?`), `GroupMember.user` №28 (`displayName?`,`alias?`), результат №19 (`displayName?`,`alias?`), `ChatView`/`ChatListItem.soundEnabled?`, `PresenceStatusItem.lastSeenAt?` (date-time), `PresenceUpdatedEvent.lastSeenAt?`; новые схемы `TypingStartedEvent`/`TypingStoppedEvent`/`ChatSoundUpdatedEvent` (uuid+uuid|boolean, `additionalProperties: false`) + примеры кадров в описании канала №18; в description полей `lastSeenAt` (№36 и PresenceUpdatedEvent) зафиксировать условия раскрытия (offline ∧ аудитория 007 ∧ не инкогнито ∧ данные есть; отсутствие поля нейтрально) и точность (≤ TTL-модели 007: регистрация 90 с + гистерезис 45 с + poller 1 с) — Assumption спеки о фиксации точности lastSeen в публичном контракте (api-contract.md §2, realtime-events.md §2.1)
- [ ] T006 Прогнать контрактные гейты и регенерировать TS-типы: `scripts/validate-contracts.sh` (vacuum + oasdiff breaking = 0 ERR, SC-007) и `pnpm --dir frontend generate:api` → закоммитить `frontend/src/api/schema.d.ts` (drift-check `git diff --exit-code -- frontend/src/api/schema.d.ts`)
- [ ] T007 [P] Создать миграцию `backend/src/main/resources/db/migration/V16__social_signals.sql`: `ALTER TABLE users ADD COLUMN display_name VARCHAR(64) NULL` (+ CHECK `display_name IS NULL OR (char_length(btrim(display_name)) BETWEEN 1 AND 64)`), `ALTER TABLE user_contacts ADD COLUMN alias VARCHAR(64) NULL` (+ аналогичный CHECK), `ALTER TABLE chat_participants ADD COLUMN sound_enabled BOOLEAN NOT NULL DEFAULT TRUE` (конвенция аддитивных колонок V12/V14/V15, data-model.md §1)
- [ ] T008 [P] Добавить конфигурацию typing и флуд-констант: свойства `chats.typing.*` (state-ttl 8s, poller-enabled true, poll-interval 1s, poll-batch 1000) в `backend/src/main/resources/application.yml` + ускоренные окна в `backend/src/test/resources/application-test.yml`; константы флуда 60/мин (`rl:user:typing:`) и 30/мин (`rl:user:profile:`/`rl:user:alias:`/`rl:user:chat-sound:`) по конвенции `UserRateLimiter` в `backend/src/main/kotlin/webchat/backend/config/` (research.md A2/A5, C2, D2)

**Checkpoint**: Контракт 0.9.0 валиден, типы сгенерированы, схема БД готова — реализации историй можно начинать

---

## Phase 3: User Story 1 — Отображаемые имена: displayName, alias, инициалы (Priority: P1) 🎯 MVP

**Goal**: Профильное имя (№39), персональный alias контакта (№40) и единая цепочка `alias → displayName → username` на всех поверхностях + инициалы аватаров из отображаемого имени при цвете от `username` (FR-001–FR-005)

**Independent Test**: alice задаёт displayName «Мария» в «Мой профиль»; bob переименовывает alice в alias «Маша»; у bob — «Маша» в Чатах/Контактах/участниках группы; у carol и alice — «Мария»; сброс alias → «Мария»; без имени — username; инициалы соответствуют отображаемому имени, цвет аватара не меняется (quickstart.md US1)

### Tests for User Story 1 (писать ПЕРВЫМИ, до реализации — конституция VI)

- [ ] T009 [P] [US1] Написать `backend/src/test/kotlin/webchat/backend/users/ProfileDisplayNameIT.kt` (по образцу существующих IT из `AbstractIntegrationTest`): set/clear №39, серверный trim, 400 `invalid_display_name` (пусто/пробельно/>64), displayName в №10/№19/№11–№13/№28-проекциях, обратная совместимость (без имени — поле отсутствует), флуд 30/мин → 429 `flood_limit` + Retry-After; убедиться в FAIL
- [ ] T010 [P] [US1] Написать `backend/src/test/kotlin/webchat/backend/contacts/ContactAliasIT.kt`: set/reset №40, alias виден только владельцу во всех его представлениях (сам контакт/третьи лица поле не получают), 404 `contact_not_found`, 400 `invalid_alias`, alias переживает удаление чата и удаляется вместе с контактом (№22), флуд 429; убедиться в FAIL

### Implementation for User Story 1 (backend)

- [ ] T011 [P] [US1] Реализовать домен профиля: валидация displayName (trim, 1–64, 400 `invalid_display_name`) + store-метод обновления `users.display_name` в `backend/src/main/kotlin/webchat/backend/users/domain/` (+ JDBC-реализация, value пишется после trim; data-model.md §1.1)
- [ ] T012 [US1] Реализовать №39 `PUT /api/v1/users/me/profile` в `backend/src/main/kotlin/webchat/backend/users/api/UsersController.kt`: тело `ProfileUpdateRequest` (null/отсутствие = сброс), 200 PublicUser, флуд `rl:user:profile:` 30/мин; добавить `displayName?` в `backend/src/main/kotlin/webchat/backend/users/api/dto/PublicUserResponse.kt`
- [ ] T013 [P] [US1] Расширить контакты: поле `alias` в `backend/src/main/kotlin/webchat/backend/contacts/domain/model/Contact.kt`; методы `storeAlias(owner, contactUserId, alias|null)` и `aliasesOf(ownerId, userIds): Map<UUID, String>` (один SELECT по `user_contacts`) в `backend/src/main/kotlin/webchat/backend/contacts/domain/port/ContactRepository.kt` + реализацию в `backend/src/main/kotlin/webchat/backend/contacts/repository/JdbcContactRepository.kt`
- [ ] T014 [US1] Реализовать №40 `PUT /api/v1/contacts/{userId}/alias` в `backend/src/main/kotlin/webchat/backend/contacts/api/ContactController.kt`: set/reset (null = сброс), 200 ContactView, 404 `contact_not_found`, 400 `invalid_alias`, флуд 30/мин, идемпотентность last-write-wins; поля `alias?` (ContactView) и `displayName?` (PublicUserView) в `backend/src/main/kotlin/webchat/backend/contacts/api/dto/ContactDtos.kt`
- [ ] T015 [US1] Прокинуть имена в peer-проекции чатов: `displayName?`/`alias?` запросчика к peer в `backend/src/main/kotlin/webchat/backend/chats/api/dto/ChatDtos.kt` (ChatPeerView) и сборка в `backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt` (directView, №11/№12/№13; join через `aliasesOf`)
- [ ] T016 [US1] Прокинуть `displayName?`/`alias?` в участников групп (№28 + members-tip) в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt` (memberView: PublicUser + alias запросчика к участнику)
- [ ] T017 [US1] Прокинуть `displayName?` (+`alias?` если найденный — контакт запросчика) в результаты поиска №19 в `backend/src/main/kotlin/webchat/backend/contacts/api/UserSearchController.kt` (+ DTO); серверная семантика поиска (точное совпадение username/email) НЕ меняется (FR-005)

### Implementation for User Story 1 (frontend)

- [ ] T018 [P] [US1] Создать `frontend/src/ui/names.ts` — единственная функция `resolveDisplayName(alias?, displayName?, username): string` (цепочка alias → displayName → username, ui-behavior.md §1) + unit-тесты в `frontend/src/ui/__tests__/names.test.ts`
- [ ] T019 [P] [US1] Добавить API-функции `updateProfile` (№39) и `setContactAlias` (№40) с типами из `frontend/src/api/schema.d.ts` в `frontend/src/api/chats.ts`
- [ ] T020 [US1] Разделить источники аватара: инициалы — из отображаемого имени (`initialsOf(resolveDisplayName(...))`), цвет — `colorOf(username)`/название группы (переименование не перекрашивает, FR-004): проп `initials`/`initialsOf` в `frontend/src/ui/Avatar.tsx` + `frontend/src/ui/avatar.ts` и прокинуть на вызывающих поверхностях
- [ ] T021 [US1] Добавить поле «Имя» в профиль: input над строкой инкогнито (placeholder = username, максимум 64), сохранение №39, пустое значение = явный сброс к username, ошибка валидации `Укажите имя без пробелов в начале и конце`, тост `Профиль обновлён — {имя|username}` в `frontend/src/chats/components/ProfileModal.tsx` (ui-behavior.md §1.1)
- [ ] T022 [US1] Добавить «Переименовать» контакта: пункт первым в «⋯»-меню, модальная форма «Имя контакта» (предзаполнена цепочкой/alias), сохранение №40, пустое значение = сброс alias, ошибки `Укажите имя контакта`, тост `Контакт переименован — {имя}`; сортировка списка по цепочке (locale ru, затем username) и фильтр по подстроке `[цепочка, username, email]` без регистра — в `frontend/src/chats/components/ContactsModal.tsx` (ui-behavior.md §1.2)
- [ ] T023 [US1] Применить `resolveDisplayName` на остальных поверхностях: список «Чаты» peer (`frontend/src/chats/components/ChatListItem.tsx`), заголовок личного чата + инициалы (`frontend/src/chats/components/ChatHeader.tsx`), результаты поиска, участники групп; длинные имена — усечение с многоточием + tooltip (конвенция 008); aria-label используют отображаемое имя
- [ ] T024 [P] [US1] Написать Vitest-тесты: сортировка/фильтр/переименование ContactsModal и поле «Имя» ProfileModal в `frontend/src/chats/components/__tests__/` (мок-паттерн `MessengerPage.sound.test.tsx`)
- [ ] T025 [P] [US1] Создать `frontend/tests/visual/social-signals.spec.ts` (Playwright, ru-RU, Europe/Moscow, допуск 2%): сценарии поля «Имя» профиля и формы переименования; перезахват затронутых базлайнов (us1-direct-chat-head и др.), снимки с отключёнными анимациями
- [ ] T026 [US1] Зелёная проверка US1: `./gradlew test --tests 'webchat.backend.users.ProfileDisplayNameIT' --tests 'webchat.backend.contacts.ContactAliasIT'` + `pnpm --dir frontend test` (names/ContactsModal/ProfileModal) + `pnpm --dir frontend test:visual` — SC-004 подтверждён

**Checkpoint**: US1 полностью функционален и тестируем независимо (displayName/alias/инициалы на всех поверхностях)

---

## Phase 4: User Story 2 — Typing-индикатор «X печатает…» с дебаунсом (Priority: P1)

**Goal**: Эфемерный сигнал набора (№41) с серверным состоянием в Redis (TTL 8 с; публикация `typing.stopped` при самоистечении — не позднее TTL + poll-interval 1 с), доставкой `typing.started`/`typing.stopped` участникам чата кроме отправителя через канал №18, флуд 60/мин, блок-пары подавлены; клиент — дебаунс отправки (3 с/5 с) и TypingRow ≤ 2 с (FR-006–FR-009, SC-001/SC-002/SC-003)

**Independent Test**: Два пользователя в общем чате: первый набирает — у второго ≤ 2 с «{имя} печатает…» (имя по цепочке US1, при её отсутствии — username); отправка/очистка/молчание — индикатор исчезает; наблюдатель офлайн — индикатор не восстанавливается; обрыв печатающего — состояние истекает по TTL 8 с, `typing.stopped` у наблюдателя ≤ ~9 с + доставка (quickstart.md US2)

### Tests for User Story 2 (писать ПЕРВЫМИ)

- [ ] T027 [P] [US2] Написать `backend/src/test/kotlin/webchat/backend/chats/TypingIT.kt` (+ `TypingTestSupport` быстрые окна по образцу `PresenceTestSupport`, poller включается в тесте): SSE-фреймы через `MessagingTestSupport.openUserEvents` + `assertConformsToSchema` против живого `contracts/openapi.yaml`; сценарии — start→typing.started ≤ 2с (SC-001), stop/отправка сообщения→typing.stopped, самоистечение: состояние по TTL 8 с, событие stopped ≤ TTL + poll-interval (SC-003, 0 следов в PG), эфемерность (подключившемуся не реплеится), self-exclusion (отправитель не получает), блок-пара DIRECT — 204 без публикации в обе стороны, флуд > 60/мин → 429 `flood_limit` без дребезга у наблюдателей, не-участник → 403/404; убедиться в FAIL

### Implementation for User Story 2 (backend)

- [ ] T028 [P] [US2] Реализовать порт и Redis-хранилище состояния: `backend/src/main/kotlin/webchat/backend/chats/domain/port/TypingStore.kt` (`start(chatId, userId, ttl)`, `stop(chatId, userId)`, `dueExpired(batch)`) и `backend/src/main/kotlin/webchat/backend/chats/repository/RedisTypingStore.kt` — ZSET `typing:active:{chatId}` (member=userId, score=expiresAtMs) + глобальный `typing:watch` (`{chatId}:{userId}`), TTL-страховка ключей (data-model.md §2.1)
- [ ] T029 [P] [US2] Расширить publisher: метод `fanoutTypingEvent(toUserIds, event)` в `backend/src/main/kotlin/webchat/backend/chats/domain/port/RealtimeEventPublisher.kt` + реализация в Redis-паблишере (`backend/src/main/kotlin/webchat/backend/realtime/RedisRealtimePublisher.kt`, канал `rt:user:{id}`)
- [ ] T030 [US2] Реализовать `backend/src/main/kotlin/webchat/backend/chats/domain/service/TypingService.kt`: гейты membership (коды №16) + блок-пара DIRECT (`BlockRepository`, обе стороны, образец `publishToPeerUnlessBlocked`), start → `TypingStore.start` + публикация `typing.started` всем активным участникам кроме отправителя, stop → `typing.stopped`; метрики `webchat_typing_events_total{event}`, `webchat_typing_flood_suppressed_total` в `backend/src/main/kotlin/webchat/backend/chats/TypingMetrics.kt` (теги — словарные, конституция V)
- [ ] T031 [US2] Реализовать №41 `POST /api/v1/chats/{chatId}/typing` в `backend/src/main/kotlin/webchat/backend/chats/api/TypingController.kt` (НОВОЕ): тело TypingRequest, 204, 400 `invalid_action`/`invalid_uuid`, флуд `rl:user:typing:` 60/мин → 429 + Retry-After (избыточные сигналы не публикуются)
- [ ] T032 [US2] Реализовать poller `backend/src/main/kotlin/webchat/backend/chats/scheduler/TypingTransitionScheduler.kt` по образцу `PresenceTransitionScheduler`: тик 1 с, батч ≤ 1000, `ZRANGEBYSCORE typing:watch -inf..now` → ZREM + публикация `typing.stopped` + метрика `webchat_typing_state_expired_total`; gated `chats.typing.poller-enabled` (research.md A4)
- [ ] T033 [US2] Погасить набор при серверной отправке сообщения: в пути №16 (успешный INSERT сообщения печатающим) вызвать `TypingStore.stop` + publish `typing.stopped` — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/` (сервис отправки сообщений; data-model.md §2.1 переходы). Отклонённая валидацией отправка (пустой текст) — погашение клиентом в useTyping (T036, stop при любом исходе; edge-кейс спеки), серверный путь не участвует
- [ ] T034 [P] [US2] Зелёная проверка TypingIT: `./gradlew test --tests 'webchat.backend.chats.TypingIT'` — SC-001/SC-002/SC-003 подтверждены

### Implementation for User Story 2 (frontend)

- [ ] T035 [P] [US2] Добавить API-функцию `sendTyping(chatId, action)` (№41) в `frontend/src/api/chats.ts`
- [ ] T036 [P] [US2] Создать `frontend/src/chats/hooks/useTyping.ts` — машина состояний отправки: start после первого символа и далее не чаще 3 с; stop при отправке (любой исход), очистке поля, молчании 5 с; заблокированный композер (008 FR-022) сигналы не шлёт; смена чата = stop предыдущего best-effort (ui-behavior.md §2.1)
- [ ] T037 [P] [US2] Создать `frontend/src/chats/components/TypingRow.tsx` (пузырь `.typing-b` с тремя `.tlamp`, анимация `lampBlink` 1.2 с с задержками .2/.4 с, `.typing-txt` курсив; 1 печатающий — `{имя} печатает…`, N>1 — `{имя первого} и ещё {N-1} печатают…`; в группе — аватар участника; prefers-reduced-motion без анимации) + CSS (lampBlink) в `frontend/src/chats/components/chat-header.css` и подключение единым экземпляром после ленты в `frontend/src/chats/components/MessageList.tsx` (ui-behavior.md §2.2)
- [ ] T038 [US2] Проводка realtime: обработчик `onTypingEvent(chatId, userId, started)` в `frontend/src/chats/hooks/useRealtime.ts` и состояние «кто печатает» активного чата в `frontend/src/chats/pages/MessengerPage.tsx`: появление ≤ 2 с, скрытие по `typing.stopped`/`message.created` от печатающего/страховочному таймауту 10 с, скрытие при смене чата, имя печатающего через `resolveDisplayName` из клиентского кэша участников (№11 peer / №28); неизвестный `userId` не рендерится (ui-behavior.md §2.2)
- [ ] T039 [P] [US2] Написать Vitest-тесты: машина состояний `useTyping` в `frontend/src/chats/hooks/__tests__/useTyping.test.ts` (первый символ/окно 3 с/stop-триггеры/блок-композер) и рендер TypingRow (тексты 1/N печатающих, подавление неизвестного `userId`) в `frontend/src/chats/components/__tests__/TypingRow.test.tsx`
- [ ] T040 [US2] Дописать сценарий typing-строки (анимации disabled) в `frontend/tests/visual/social-signals.spec.ts` + `pnpm --dir frontend test:visual`
- [ ] T041 [P] [US2] Создать `load/k6/social-signals.smoke.js` по образцу `load/k6/presence.smoke.js`: базовый прогон (мессенджинг) vs прогон с активным набором (start каждые 3 с, stop на отправке); критерий Δp99 `webchat_realtime_push_seconds` ≤ 10% (SC-008)

**Checkpoint**: US1 и US2 работают независимо; typing эфемерен, самоистекает, приватен (SC-001–SC-003)

---

## Phase 5: User Story 3 — lastSeen: «Был в сети — {дата, время}» при офлайне (Priority: P2)

**Goal**: Фиксация времени последней активности presence-канала в `presence:lastseen:{userId}` (Lua-ноги 007), раскрытие опциональным `lastSeenAt` в №36 и `presence.updated` строго в рамках приватности 007 (инкогнито/блок-пара/нет данных — единый нейтральный фолбэк «Был в сети — давно»); формат наблюдателя ru-RU (FR-010/FR-011, SC-002/SC-005)

**Independent Test**: Собеседник закрывает все устройства → после offline (гистерезис 007) наблюдатель видит «Был в сети — {дата, время}» в заголовке чата и «Контактах»; инкогнито — время пропадает из UI и API (quickstart.md US3)

### Tests for User Story 3 (писать ПЕРВЫМИ)

- [ ] T042 [P] [US3] Написать `backend/src/test/kotlin/webchat/backend/presence/PresenceLastSeenIT.kt` (по образцу `PresencePrivacyIT`/`PresenceTestSupport`): `lastSeenAt` в №36 только при offline ∧ аудитория ∧ не инкогнито ∧ ключ есть; инкогнито/блок-пара/посторонний — поле ОТСУТСТВУЕТ и неотличимо от «нет данных» (0 утечек, SC-002); точность ≤ TTL 90 с + гистерезис 45 с + poller 1 с (SC-005); offline-событие с `lastSeenAt`, FREEZE — без; schema-конформность №36; убедиться в FAIL

### Implementation for User Story 3 (backend)

- [ ] T043 [US3] Расширить `backend/src/main/kotlin/webchat/backend/presence/repository/RedisPresenceStore.kt`: запись `presence:lastseen:{userId}` (STRING, epoch ms из Redis TIME, TTL 30 дней) атомарно в Lua-ногах REGISTER/RENEW/UNREGISTER/CLEAR_SESSION; пакетное чтение в SNAPSHOT (№36) (research.md B1)
- [ ] T044 [US3] Реализовать фильтр раскрытия в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt`: `lastSeenAt` в №36-снапшоте и offline-событии `presence.updated` только при (status=offline ∧ наблюдатель в аудитории `JdbcVisibilityAudienceReader` ∧ субъект не в инкогнито — пакетное чтение `presence_hidden` через `JdbcPresenceSettingsStore` ∧ ключ существует); FREEZE-переход — событие без `lastSeenAt`; иначе поле отсутствует (нейтрально, research.md B2)
- [ ] T045 [US3] Зелёная проверка: `./gradlew test --tests 'webchat.backend.presence.PresenceLastSeenIT'` — SC-002/SC-005 подтверждены

### Implementation for User Story 3 (frontend)

- [ ] T046 [P] [US3] Добавить `lastSeenFormat(v: string, now: Date): string` в `frontend/src/ui/time.ts` (композиция formatTime/formatDate): сегодня → `HH:MM`; иначе → `D месяца, HH:MM` (`24 сентября, 16:20`); другой год → `D месяца YYYY, HH:MM`; локаль ru-RU, пояс наблюдателя (research.md B3)
- [ ] T047 [P] [US3] Расширить `frontend/src/presence/presenceStore.ts` (PresenceEntry + `lastSeenAt?`, merge из №36 и `presence.updated` по строго большему rev, как 007) и `frontend/src/presence/usePresence.ts` (+ `usePresenceEntry` со status + lastSeenAt)
- [ ] T048 [US3] Отобразить статусы: «Был в сети — {время}» / «Был в сети — давно» (offline без поля) / «В сети» (без изменений) в заголовке личного чата (`.status-txt`) `frontend/src/chats/components/ChatHeader.tsx` и превью строк «Контактов» (`.c-prev`, кроме флагов заблокирован/чат удалён) `frontend/src/chats/components/ContactsModal.tsx`; в сайдбар-списке чатов текст статуса не показывается (ui-behavior.md §3)
- [ ] T049 [P] [US3] Написать Vitest-тесты: `lastSeenFormat` в `frontend/src/ui/__tests__/time.test.ts` (сегодня/полная дата/другой год/граница суток/фолбэк) и статусы ChatHeader в `frontend/src/chats/components/__tests__/`
- [ ] T050 [US3] Дописать Playwright-сценарий «Был в сети — …» в заголовке и превью контакта в `frontend/tests/visual/social-signals.spec.ts` + `pnpm --dir frontend test:visual`

**Checkpoint**: US1–US3 работают независимо; lastSeen приватен (0 утечек) и неотличим в скрытых случаях

---

## Phase 6: User Story 4 — Переключатель звука чата, per-chat «звонок» (Priority: P3)

**Goal**: Колонка `chat_participants.sound_enabled` в проекциях (№12/№13 `soundEnabled`), операция №42 с идемпотентностью и флудом 30/мин, событие `chat.sound.updated` только в собственный канал (мультидевайс ≤ 2 с); bell-кнопка в заголовке, звук-гейт приёма realtime и sync-батчей, визуальные уведомления не фильтруются (FR-012–FR-015, SC-006)

**Independent Test**: Два устройства пользователя: приглушение на первом → кнопка/поведение на втором меняются ≤ 2 с; входящее в приглушённый чат — 0 звуков на обоих устройствах, бейджи приходят; другой чат звучит (quickstart.md US4)

### Tests for User Story 4 (писать ПЕРВЫМИ)

- [ ] T051 [P] [US4] Написать `backend/src/test/kotlin/webchat/backend/chats/ChatSoundIT.kt`: №42 смена/повтор значения (идемпотентность — 200 без события), `chat.sound.updated` приходит только в собственный канал (другие участники не получают — приватность), `soundEnabled` в №12/№13, не-участник → 403/404, флуд > 30/мин → 429, schema-конформность; убедиться в FAIL

### Implementation for User Story 4 (backend)

- [ ] T052 [P] [US4] Прокинуть `sound_enabled` в чтение/запись `chat_participants` в `backend/src/main/kotlin/webchat/backend/chats/repository/` и поле `soundEnabled` в ChatView/ChatListItem проекциях (`backend/src/main/kotlin/webchat/backend/chats/api/dto/ChatDtos.kt`, `backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt`); умолчание TRUE для новых участий (data-model.md §1.3)
- [ ] T053 [US4] Реализовать №42 `PUT /api/v1/chats/{chatId}/sound` в `backend/src/main/kotlin/webchat/backend/chats/api/ChatSoundController.kt` (НОВОЕ) + `ChatService.updateSound`: membership-гейт (коды №13/№16), идемпотентность (повтор значения — 200 без события), смена → событие `chat.sound.updated {chatId, soundEnabled}` только в собственный канал пользователя, флуд `rl:user:chat-sound:` 30/мин, метрика `webchat_sound_settings_updated_total{result=changed|unchanged|rejected}` (research.md D2)
- [ ] T054 [US4] Зелёная проверка: `./gradlew test --tests 'webchat.backend.chats.ChatSoundIT'` — SC-006 подтверждён

### Implementation for User Story 4 (frontend)

- [ ] T055 [P] [US4] Добавить API-функцию `setChatSound` (№42) и тип события `chat.sound.updated` в `frontend/src/api/chats.ts`
- [ ] T056 [P] [US4] Расширить `frontend/src/ui/sound.ts`: per-chat mute-гейт в `chimeOnRealtimeIncoming` (звонок только если `soundEnabled !== false`), отклик «выкл» — «глухой щелчок» `bellTone(300 Hz)` прототипа, отклик «вкл» — существующий playBellTone; запрет автозвука — молчаливый пропуск (research.md D3, ui-behavior.md §4.2)
- [ ] T057 [US4] Добавить bell-кнопку в `frontend/src/chats/components/ChatHeader.tsx`: `.ch-btn` SVG-колокол между поиском и шестернёнкой, состояние `выкл` — класс `.off {opacity:.45}`, `title="Звуковые оповещения"`, `aria-pressed`, focus-visible; действие №42, тосты `Звуковые оповещения включены/отключены — {имя по цепочке}`, ошибка сети/429 — состояние не меняется; CSS в `frontend/src/chats/components/chat-header.css` (ui-behavior.md §4.1)
- [ ] T058 [US4] Проводка синхронизации: `onChatSoundUpdated(chatId, soundEnabled)` в `frontend/src/chats/hooks/useRealtime.ts`; в `frontend/src/chats/pages/MessengerPage.tsx` — звук-состояние из №12/№13 при (ре)подключении, per-chat подсчёт входящих sync-батчей 005 (звонок батча — только если среди пришедших есть неприглушённые чаты); визуальные уведомления не фильтруются (FR-014)
- [ ] T059 [P] [US4] Написать Vitest-тесты звук-гейта по мок-паттерну `MessengerPage.sound.test.tsx` (vi.hoisted + `installStream`): realtime-входящее в приглушённый/обычный чат, sync-батч только с приглушёнными — 0 звуков, bell aria-pressed/тосты — в `frontend/src/chats/__tests__/` / `frontend/src/chats/components/__tests__/`
- [ ] T060 [US4] Дописать Playwright-сценарии bell вкл/выкл (снятки + e2e-фреймы) в `frontend/tests/visual/social-signals.spec.ts` + `pnpm --dir frontend test:visual`

**Checkpoint**: Все истории US1–US4 независимо функциональны

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Сквозная верификация контракта, регрессии, нагрузки и quickstart (SC-007/SC-008)

- [ ] T061 Полная регрессия: `./gradlew check` (все IT 002–008 без изменения ожиданий, SC-007), `pnpm --dir frontend lint && pnpm --dir frontend typecheck && pnpm --dir frontend test && pnpm --dir frontend test:visual` (с перезахватом затронутых базлайнов), `scripts/validate-contracts.sh` = 0 ERR
- [ ] T062 Выполнить ручные сценарии quickstart.md (US1–US4, три пользователя + мультидевайс) и сверить SC-001–SC-008; прогнать `k6 run load/k6/social-signals.smoke.js` (Δp99 push ≤ 10%) и проверить метрики FR-017 в `/actuator/prometheus` — включая покрытие новых событий push-таймером `webchat_realtime_push_seconds` с тегами `event=typing.*|chat.sound.updated`, `stage=dispatch` (realtime-events.md §4)
- [ ] T063 Финальная чистка: убрать временный/мёртвый код, проверить консистентность тегов метрик (только словарные значения), обновить статусы задач в `specs/008a-social-signals/tasks.md`
- [ ] T064 Пройти `/speckit.checklist` (Definition of Done, конституция VI): прогнать чек-лист `specs/008a-social-signals/checklists/requirements.md` по всем закрытым задачам, устранить замечания, зафиксировать результат — фича закрыта только после прохождения

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно
- **Foundational (Phase 2)**: после Phase 1 — БЛОКИРУЕТ все истории (контракт 0.9.0 = источник типов; V16 = колонки US1/US4 (US3 — без миграции, только Redis); T003–T006 строго последовательно — один файл `contracts/openapi.yaml` + генерация)
- **User Stories (Phases 3–6)**: все после Phase 2; последовательный порядок приоритетов US1 (P1) → US2 (P1) → US3 (P2) → US4 (P3) одним агентом (конституция: последовательная реализация); при команде — US3/US4 параллельны с US2 после US1, кроме общих файлов исторей (см. Parallel Team Strategy)
- **Polish (Phase 7)**: после всех историй

### User Story Dependencies

- **US1 (P1)**: после Foundational; не зависит от других историй (имена/alias/инициалы)
- **US2 (P1)**: после Foundational; формально независима (фолбэк имени на username), но TypingRow/aria используют `resolveDisplayName` из US1 — рекомендован порядок после US1
- **US3 (P2)**: после Foundational; независима (расширяет presence 007); UI-статусы в ChatHeader/ContactsModal поверх US1-структур
- **US4 (P3)**: после Foundational; независима (звук поверх 008); bell/тосты используют цепочку имён US1

### Within Each User Story

- Тесты (IT/Vitest) пишутся ПЕРВЫМИ и падают до реализации (конституция VI)
- Порты/модели → сервисы → контроллеры (backend); примитивы (names/time/sound) → хуки → компоненты → страницы (frontend)
- История закрыта только с зелёными тестами задачи проверки (T026/T034/T045/T054)

### Parallel Opportunities

- Phase 2: T007 (миграция) и T008 (конфиг) параллельны между собой и с T002–T005 (другие файлы), но до T006
- US1: T009/T010 (разные тест-файлы) параллельны; T011/T013 (users/contacts) параллельны; T016/T017 — после T013 (используют ContactRepository.aliasesOf), дальше параллельны между собой и с T012/T014/T015; фронтенд-примитивы T018/T019 параллельны backend-задачам (разные пакеты)
- US2: T028/T029 (порт+store vs publisher) параллельны; T035/T036/T037 (api/hook/компонент — разные файлы) параллельны; T041 (k6) параллелен фронтенду
- US3: T046/T047 (time.ts vs presenceStore) параллельны backend T043/T044
- US4: T052 (репозиторий/проекции) параллелен T051 (тест); T055/T056 параллельны; разные истории — параллельны разным разработчикам после US1, кроме общих файлов исторей (см. Parallel Team Strategy)

---

## Parallel Example: User Story 1

```bash
# Тесты US1 параллельно (разные файлы):
Task: "T009 ProfileDisplayNameIT в backend/src/test/kotlin/webchat/backend/users/"
Task: "T010 ContactAliasIT в backend/src/test/kotlin/webchat/backend/contacts/"

# Backend-модули US1 параллельно (разные пакеты):
Task: "T011 домен профиля users/domain/ + JDBC"
Task: "T013 Contact.alias + ContactRepository.storeAlias/aliasesOf"

# Фронтенд-примитивы US1 параллельно backend:
Task: "T018 frontend/src/ui/names.ts + тесты"
Task: "T019 frontend/src/api/chats.ts updateProfile/setContactAlias"
```

## Parallel Example: User Story 2

```bash
# Порты и хранилища параллельно (разные файлы):
Task: "T028 TypingStore.kt + RedisTypingStore.kt"
Task: "T029 RealtimeEventPublisher.fanoutTypingEvent + Redis-реализация"

# Фронтенд-слой типинга параллельно (разные файлы):
Task: "T035 api/chats.ts sendTyping"
Task: "T036 chats/hooks/useTyping.ts"
Task: "T037 chats/components/TypingRow.tsx + CSS + MessageList"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (базовая линия)
2. Complete Phase 2: Foundational (контракт 0.9.0 + V16 + конфиг — КРИТИЧНО, блокирует всё)
3. Complete Phase 3: User Story 1 (отображаемые имена)
4. **STOP and VALIDATE**: T026 — US1 независимо (quickstart US1)
5. Deploy/demo при готовности

### Incremental Delivery

1. Setup + Foundational → контракт/схема готовы
2. + US1 (имена) → тест → demo (MVP)
3. + US2 (typing) → тест (TypingIT + k6 smoke) → demo
4. + US3 (lastSeen) → тест (приватность) → demo
5. + US4 (звук) → тест (мультидевайс) → demo → Phase 7 (полная регрессия)

### Parallel Team Strategy

1. Команда вместе закрывает Setup + Foundational
2. После Foundational: Developer A → US1; затем B → US2 (нужен resolveDisplayName), C → US3, D → US4 (независимы после US1)
3. Общие файлы исторей — `frontend/tests/visual/social-signals.spec.ts` (T025/T040/T050/T060), `frontend/src/chats/components/ChatHeader.tsx` + `chat-header.css` (T023/T037/T048/T057), `frontend/src/chats/pages/MessengerPage.tsx` (T038/T058), `frontend/src/api/chats.ts` (T019/T035/T055), `frontend/src/chats/components/ContactsModal.tsx` (T022/T048) — редактируются строго последовательно в порядке US1 → US2 → US3 → US4 одним исполнителем; остальное параллельно
4. Истории интегрируются независимо; Phase 7 — совместная регрессия

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story] метки связывают задачи со stories спеки (трассировка FR/SC)
- Тесты пишутся первыми и падают до реализации; задача закрыта только с зелёными тестами (конституция VI)
- Definition of Done задачи (конституция): реализация + зелёные тесты + lint/форматирование + обновлённый API-контракт (если затронут); фича в целом — только после пройденного `/speckit.checklist` (T064)
- Коммит после каждой задачи/логической группы; статусы вести в этом файле
- Контракт — единственный источник истинных типов: любое изменение полей/событий начинается с `contracts/openapi.yaml` + `pnpm --dir frontend generate:api` (дрейф-чек в CI)
- Все изменения строго аддитивные: `oasdiff breaking` = 0 ERR (SC-007); существующие ожидания тестов 004–008 не меняются
- Чекпоинты историй — точки независимой валидации (stop-and-validate)
