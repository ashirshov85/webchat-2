---
description: "Task list for feature implementation"
---

# Tasks: Личный текстовый диалог в реальном времени с exactly-once доставкой

**Input**: Design documents from `/specs/004-direct-messaging-core/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/api-contract.md + contracts/realtime-channel.md ✅, quickstart.md ✅, `.specify/memory/constitution.md` ✅

**Tests**: ОБЯЗАТЕЛЬНЫ (конституция VI Test-First — NON-NEGOTIABLE; plan.md «Testing»; research.md §13). Тесты пишутся до/вместе с реализацией и должны падать до неё.

**Organization**: Задачи сгруппированы по пользовательским историям (US1–US6 из spec.md) — каждая история реализуется и проверяется независимо.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность к пользовательской истории (US1…US6)
- Во всех описаниях — точные пути к файлам

## Path Conventions

- **Web-монорепозиторий** (структура 001–003 сохранена): `backend/src/…` (Kotlin, package-by-feature `webchat.backend`), `frontend/src/…` (React 19 + TS strict), `contracts/openapi.yaml`, `deploy/k8s/base/`, `load/k6/`
- Тесты: `backend/src/test/kotlin/webchat/backend/…`, `frontend/src/…/__tests__/`
- Ссылки на решения: [research.md](./research.md) §№, [data-model.md](./data-model.md), [contracts/api-contract.md](./contracts/api-contract.md) № операций, [contracts/realtime-channel.md](./contracts/realtime-channel.md)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Конфигурация фичи и тестовая база (проект и конвейер 001–003 уже существуют)

- [X] T001 Создать `ChatsProperties` (`@ConfigurationProperties("chats")`: `message.max-length=4096`, `message.page-size=50`, `rate-limit.messages-per-minute=30`, `realtime.heartbeat=15s`) и секцию `chats.*` в конфигурации — в `backend/src/main/kotlin/webchat/backend/config/ChatsProperties.kt` и `backend/src/main/resources/application.yml` (значения = контракту FR-003/008/011); приёмка: контекст стартует с `chats.*`, значения совпадают с контрактом (используются IT T008a/T030/T038)
- [X] T002 [P] Создать тестовые фикстуры для IT мессенджинга (регистрация+auth двух и более пользователей, создание чата, отправка сообщений, чтение SSE-кадров) — в `backend/src/test/kotlin/webchat/backend/chats/MessagingTestSupport.kt` (поверх `AbstractIntegrationTest`, Testcontainers PG+Redis); приёмка: фикстуры компилируются и используются IT T007/T008/T008a/T029

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: API-контракт (источник истины, конституция IV) и схема БД — ДО любой истории

**⚠️ CRITICAL**: Ни одна пользовательская история не начинается до завершения этой фазы

- [X] T003 Обновить публичный контракт до **0.4.0 (additive, без BREAKING.md)**, часть 1: endpoints №11–17 и SSE-канал №18 (`GET /api/v1/users/me/events`, `text/event-stream`, `X-Accel-Buffering: no`), схемы `EnsureChatRequest`, `ChatView`, `ChatListItem`, `Message`, `MessagePage`, `SendMessageRequest`, `ReadRequest`, `MessageCreatedEvent`, `ChatReadEvent`, коды ошибок (`text_blank`, `text_too_long`, `invalid_uuid`, `not_participant`, `chat_blocked_by_you`, `you_are_blocked`, `message_id_conflict`, `flood_limit` + `Retry-After`, `self_forbidden`, `peer_not_found`, `chat_not_found`, `limit_out_of_range`, `invalid_up_to_seq`) и константы 4096/50/30 — в `contracts/openapi.yaml` строго по [contracts/api-contract.md](./contracts/api-contract.md) и [contracts/realtime-channel.md](./contracts/realtime-channel.md)
- [X] T003b Часть 2 контракта 0.4.0: endpoints №19–24 (контакты/поиск/блокировки), схемы `ContactView`, `ContactsResponse`, `UsersSearchResponse`, коды ошибок (`user_not_found` (контакты, T052), `invalid_sort`, `query_missing`, `429 flood_limit` + `Retry-After` на №19 поиск (T053a); `self_forbidden` переиспользуется из части 1) — в `contracts/openapi.yaml` строго по [contracts/api-contract.md](./contracts/api-contract.md) (T004 — после T003+T003b)
- [X] T004 Регенерировать TS-типы из контракта 0.4.0 (`pnpm --dir frontend generate:api`) и устранить drift — `frontend/src/api/schema.d.ts` (типы `Message`, `ChatView`, `ChatListItem` и т.д. для всех историй); приёмка: drift-проверка пуста, типы доступны фронтенд-тестам (T027/T045), конвейер T065 зелёный
- [X] T005 [P] Миграция **V10__chats.sql**: таблицы `chats` (UNIQUE `(user_low_id, user_high_id)`, CHECK `user_low_id < user_high_id`, `last_seq`), `chat_participants` (PK `(chat_id, user_id)`, `last_read_seq`/`deleted_up_to_seq` CHECK ≥0, `hidden`), `messages` (PK `id` — клиентский UUID, `seq` IDENTITY, CHECK длины текста, UNIQUE `(chat_id, seq)`, индекс `(chat_id, seq DESC)`) — в `backend/src/main/resources/db/migration/V10__chats.sql` ([data-model.md](./data-model.md) «Миграции»); приёмка: накат Testcontainers зелёный во всех IT фаз 3–8 (PK/UNIQUE/CHECK проверяются T029/T049)
- [X] T006 [P] Миграция **V11__contacts_blocks.sql**: `user_contacts` (PK `(owner_id, contact_user_id)`, CHECK self), `user_blocks` (PK `(blocker_id, blocked_id)`, CHECK self) — в `backend/src/main/resources/db/migration/V11__contacts_blocks.sql`; приёмка: накат V11 зелёный в IT T047/T048 (уникальные пары, CHECK self)

**Checkpoint**: Контракт 0.4.0 + схема БД готовы — истории можно реализовывать (последовательно или параллельно командой)

---

## Phase 3: User Story 1 — Обмен сообщениями в реальном времени (Priority: P1) 🎯 MVP

**Goal**: Два пользователя ведут единый личный диалог: отправка со статусами «отправляется» → «доставлено» (✓), realtime-доставка онлайн-получателю по SSE, валидация текста, membership-авторизация

**Independent Test**: Два пользователя открывают общий диалог (`POST /chats/ensure`); один отправляет сообщение — у отправителя «отправляется» → «доставлено»; у онлайн-получателя сообщение появляется ≤2 с без обновления (SSE `message.created`); пустой/4096+ текст → 400; чужой (Carol) → 403 `not_participant` на каждом ресурсе

### Tests for User Story 1 (писать ПЕРВЫМИ, до реализации)

- [X] T007 [P] [US1] `RealtimeSseIT`: SSE-поток отдаёт `retry: 3000`, heartbeat `:ka` каждые 15 с, кадр `message.created` (payload = схеме контракта) обоим участникам после POST — в `backend/src/test/kotlin/webchat/backend/realtime/RealtimeSseIT.kt` (WebTestClient, [research.md §13](./research.md))
- [X] T008 [P] [US1] `ChatAccessIT`: не-участник получает `403 not_participant` на `GET/DELETE /chats/{id}`, `GET/POST /chats/{id}/messages`, `POST /chats/{id}/read`; несуществующий чат → `404` (FR-002) — в `backend/src/test/kotlin/webchat/backend/chats/ChatAccessIT.kt`
- [X] T008a [P] [US1] `MessageValidationIT`: ровно 4096 символов (после trim) → `201`; 4097 → `400 text_too_long`; текст только из пробелов/переводов строк → `400 text_blank`; внутренние пробелы сохраняются в записи — в `backend/src/test/kotlin/webchat/backend/chats/MessageValidationIT.kt` (FR-003, Edge Cases)

### Implementation for User Story 1

- [X] T009 [P] [US1] Доменные модели `Chat`, `ChatParticipant`, `Message`, `MessageText` (валидация FR-003: trim начальных/конечных пробелов, непустой, ≤4096 после trim) — в `backend/src/main/kotlin/webchat/backend/chats/domain/model/`
- [X] T010 [P] [US1] Порты домена `ChatRepository`, `MessageRepository`, `ParticipantRepository`, `RealtimeEventPublisher` — в `backend/src/main/kotlin/webchat/backend/chats/domain/port/` (DIP: адаптеры снаружи)
- [X] T011 [US1] `JdbcChatRepository` (ensure: `INSERT … ON CONFLICT (user_low_id, user_high_id) DO NOTHING` + SELECT, снятие `hidden`, ленивое создание двух `chat_participants` в одной транзакции) и `JdbcParticipantRepository` (чтение per-user состояния) — в `backend/src/main/kotlin/webchat/backend/chats/repository/`
- [X] T012 [US1] `JdbcMessageRepository`: `INSERT … ON CONFLICT (id) DO NOTHING RETURNING *` + SELECT по id (дедуп-путь), выборка страницы `ORDER BY seq DESC` с фильтром видимости `seq > deleted_up_to_seq`, обновление `chats.last_seq` в той же транзакции, сброс `hidden=false` у обоих участников (только при фактической новой записи, не в дедуп-пути; возврат чата в список при новом входящем — FR-021, data-model «hidden») — в `backend/src/main/kotlin/webchat/backend/chats/repository/JdbcMessageRepository.kt`
- [X] T013 [US1] `ChatService`: ensure (`422 self_forbidden` для себя, `404 peer_not_found`), get с membership-проверкой (FR-001/002) — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt`
- [X] T014 [US1] `MessageService`: membership → валидация текста (FR-003) → INSERT (ON CONFLICT) → `201` после коммита PG → публикация `message.created` в `rt:user:{sender}` и `rt:user:{recipient}` ПОСЛЕ коммита (FR-004 базис, FR-007) — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt`
- [X] T015 [US1] `HistoryService`: последняя страница + курсор `before=<seq>` (эксклюзивно), `limit 1..50` (`400 limit_out_of_range`), `nextBefore` отсутствует при исчерпании (FR-008, [research.md §3](./research.md)) — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/HistoryService.kt`
- [X] T016 [US1] `ChatController` (№11 `POST /api/v1/chats/ensure`, №13 `GET /api/v1/chats/{chatId}`) + DTO `EnsureChatRequest`, `ChatView` — в `backend/src/main/kotlin/webchat/backend/chats/api/ChatController.kt` и `backend/src/main/kotlin/webchat/backend/chats/api/dto/`
- [X] T017 [US1] `MessageController` (№16 `POST /api/v1/chats/{chatId}/messages`, №15 `GET /api/v1/chats/{chatId}/messages`) + DTO `SendMessageRequest`, `MessageView` — в `backend/src/main/kotlin/webchat/backend/chats/api/MessageController.kt` и `backend/src/main/kotlin/webchat/backend/chats/api/dto/`
- [X] T018 [P] [US1] `RealtimeController` (№18 `GET /api/v1/users/me/events`: `SseEmitter`, заголовок `X-Accel-Buffering: no`, кадр `retry: 3000`, heartbeat `:ka` из `chats.realtime.heartbeat`) и `SseConnectionRegistry` (emitters по userId, мульти-сессии) — в `backend/src/main/kotlin/webchat/backend/realtime/RealtimeController.kt` и `backend/src/main/kotlin/webchat/backend/realtime/SseConnectionRegistry.kt`
- [X] T019 [US1] `RedisRealtimePublisher` — адаптер порта `RealtimeEventPublisher`: публикация JSON-конверта в `rt:user:{userId}`, динамическая Lettuce-подписка инстанса при первом SSE-соединении пользователя / отписка при закрытии последнего, диспетчеризация в локальные emitters — в `backend/src/main/kotlin/webchat/backend/realtime/RedisRealtimePublisher.kt` ([research.md §2](./research.md))
- [X] T020 [P] [US1] Ingress для SSE-маршрута `/api/v1/users/me/events`: отключение буферизации (аннотация + таймауты ≥5 мин) — в `deploy/k8s/base/ingress.yaml`; приёмка: SSE-кадры приходят без буферизации за ingress (ручная проверка quickstart §3.1 на dev-стенде)
- [X] T021 [P] [US1] Типизированный API-модуль `ensureChat`, `getChat`, `sendMessage`, `listMessages` (типы из `schema.d.ts`, через `apiFetch` с refresh) — в `frontend/src/api/chats.ts`
- [X] T022 [P] [US1] SSE-клиент на `fetch` + `ReadableStream` (парсинг `event:`/`data:`/`retry:`, `Authorization: Bearer`, авто-refresh на 401, экспоненциальный reconnect 1с…30с с джиттером — клиентский backoff главнее кадра `retry: 3000`: кадр является подсказкой для сторонних контрактных клиентов без собственной стратегии, приоритет ассертится тестом T027; колбэк `onOpen`; токен в URL запрещён) — в `frontend/src/api/sse.ts` ([research.md §9](./research.md))
- [X] T023 [P] [US1] Хук `useRealtime` — единый поток событий пользователя (подписка на `message.created`, диспетчеризация по `chatId`) — в `frontend/src/chats/hooks/useRealtime.ts`
- [X] T024 [US1] Хук `useChatMessages` — начальная загрузка последних сообщений + realtime-добавление с дедупом по `message.id` (идемпотентный рендер); при (пере)подключении SSE (колбэк `onOpen` из T022/T023) — рефетч последних сообщений и сведение состояния к серверному без дублей и изменения порядка (FR-009; ассертится тестом T027a) — в `frontend/src/chats/hooks/useChatMessages.ts`
- [X] T025 [US1] Страница мессенджера (layout: панель слева + окно диалога) и защищённый маршрут `/` — в `frontend/src/chats/pages/MessengerPage.tsx` и `frontend/src/App.tsx`; приёмка: неаутентифицированный `/` → редирект на вход, layout рендерится (проверяется тестами T027/T061 и quickstart §3.1)
- [X] T026 [P] [US1] Компоненты `MessageList` (исходящие со статусом ✓ после подтверждения, входящие без галочек), `MessageInput` (клиентская превалидация по правилу FR-003: trim, непустой, ≤4096 после trim; невалидный текст не попадает в чат и outbox — локальная понятная ошибка, US1-4), `ErrorBanner` (ошибки 400 валидации) — в `frontend/src/chats/components/MessageList.tsx`, `frontend/src/chats/components/MessageInput.tsx`, `frontend/src/chats/components/ErrorBanner.tsx`
- [X] T027 [P] [US1] Frontend-тесты: SSE-ридер (парсинг кадров, реконнект/`onOpen`, приоритет клиентского backoff над `retry`-кадром) и `MessageList` (статусы «отправляется»/«доставлено», отсутствие галочек на входящих) — в `frontend/src/api/__tests__/sse.test.ts` и `frontend/src/chats/components/__tests__/MessageList.test.tsx`
- [X] T027a [P] [US2] Тест сходимости при переподключении (FR-009, US2-5): `onOpen` SSE-клиента → рефетч истории → дедуп по `message.id`, порядок и статусы сохраняются, дублей нет — в `frontend/src/chats/hooks/__tests__/useChatMessages.test.ts`
- [X] T028 [US1] Метрики `webchat_message_ack_seconds`, `webchat_realtime_push_seconds` (Micrometer) в `MessageService`/`RedisRealtimePublisher` + прогон `RealtimeSseIT`, `ChatAccessIT` зелёными — файлы по месту реализации

**Checkpoint**: MVP работает: диалог, отправка, статусы, realtime-доставка, membership — проверяется независимо (quickstart §3.1)

---

## Phase 4: User Story 2 — Exactly-once без дублей, стабильный порядок (Priority: P1)

**Goal**: Ретраи/двойные клики/офлайн-обрывы дают ровно один экземпляр сообщения; порядок одинаков у обоих участников; клиентский outbox с автоповторами тем же ID, флуд-отсрочкой 429 и терминальным «не отправлено»

**Independent Test**: Повторный POST с тем же `clientMessageId` → `200` той же записи, в истории один экземпляр; параллельные ретраи — без дублей; `seq`-порядок одинаков у обоих; 31-е сообщение/мин → `429 flood_limit` + `Retry-After`, записи нет; после закрытия вкладки в момент «отправляется» сообщение доотправляется тем же ID при следующем входе

### Tests for User Story 2 (писать ПЕРВЫМИ)

- [X] T029 [P] [US2] `MessagingDeliveryIT`: дедуп по id (последовательный и параллельные ретраи одного id, двойной POST), `409 message_id_conflict` на чужой id, порядок `seq` у обоих участников и между загрузками, офлайн-получатель видит всё без потерь/дублей — в `backend/src/test/kotlin/webchat/backend/chats/MessagingDeliveryIT.kt` (SC-002/003)
- [X] T030 [P] [US2] `FloodLimitIT`: 31-е сообщение за минуту → `429` + `Retry-After`, записи в БД нет; ретрай уже записанного сообщения НЕ штрафуется 429 (дедуп → флуд); повтор после окна → `201` — в `backend/src/test/kotlin/webchat/backend/chats/FloodLimitIT.kt`

### Implementation for User Story 2

- [X] T031 [US2] Дедуп-быстрый путь в `MessageService` (до лимитов и блокировок): lookup по id → найдено и `chat_id`/`sender_id` совпадают → `200` существующей записи; расхождение → `409 message_id_conflict`; пустой RETURNING (гонка) → SELECT → `200` — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt` ([data-model.md §3](./data-model.md) «Запись»)
- [X] T032 [US2] Флуд-лимит `rl:user:msgsend:{userId}` (Bucket4j + Lettuce, переиспользуя `RateLimitConfig` 002): 30 токенов/мин, проверка ПОСЛЕ дедупа и ДО INSERT; отклонение — `429 flood_limit` problem+json с `Retry-After`, warn-лог (без текста сообщения) и метрика `webchat_send_rejected_total{reason=flood}` — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt` и `backend/src/main/kotlin/webchat/backend/config/RateLimitConfig.kt` ([research.md §7](./research.md))
- [X] T033 [P] [US2] Outbox в `localStorage` (ключ `webchat.chats.outbox.<userId>`): записи `{clientMessageId, chatId, text, state: sending|failed, errorCode?, retryAt?}` — в `frontend/src/chats/outbox.ts` ([research.md §10](./research.md))
- [X] T034 [US2] Хук `useOutbox`: запись в outbox — только текста, прошедшего клиентскую превалидацию FR-003 (серверный 400 остаётся защитой и переводит в `failed`); автоповтор тем же ID с backoff 1с…30с; `429` → `retryAt = now + Retry-After` с UI-отсчётом; невременные ошибки (`403 you_are_blocked`, `403 chat_blocked_by_you`, `409`, `400`) → `failed` («не отправлено»), автоповторы прекращаются; ручной повтор тем же ID и локальное удаление; флеш всех `sending` при старте/входе; purge по `chatId` при удалении чата — в `frontend/src/chats/hooks/useOutbox.ts` (FR-012)
- [X] T035 [P] [US2] Frontend-тесты outbox (hook-уровень, пишутся первыми): отклонение невалидного текста до outbox (пустой, пробелы, 4097 — в чат и outbox не попадает, US1-4); ретраи тем же id, флуд-отсчёт, терминальный статус + ручной повтор/удаление (API хука), purge при удалении чата, флеш при старте — в `frontend/src/chats/__tests__/outbox.test.ts`
- [X] T035a [US2] Frontend-тесты UI-действий для failed-сообщений (после T036): кнопки ручного повтора тем же ID и локального удаления в `MessageList`, рендер `sending`/`failed` — в `frontend/src/chats/components/__tests__/MessageList.outbox.test.tsx`
- [X] T036 [US2] Интеграция outbox в окно диалога: рендер `sending`/`failed` после серверных сообщений, переход в «доставлено» по `201/200`; кнопки действий для `failed` («не отправлено»): ручной повтор тем же ID и локальное удаление (FR-012); исходящее с другого устройства рендерится сразу «доставлено» по `message.created` в собственный поток (US2-6 мультидевайс) — в `frontend/src/chats/hooks/useChatMessages.ts`, `frontend/src/chats/pages/MessengerPage.tsx`, `frontend/src/chats/components/MessageList.tsx`
- [X] T037 [US2] Метрика `webchat_message_dedup_total` (счётчик попаданий в дедуп) + прогон `MessagingDeliveryIT`, `FloodLimitIT` зелёными — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt`

**Checkpoint**: Конституционная гарантия III выполнена: 0 дублей, порядок стабилен, ретраи безопасны, флуд-лимит с корректным клиентским поведением (quickstart §3.2, §3.8)

---

## Phase 5: User Story 3 — История переписки при повторном входе (Priority: P2)

**Goal**: При открытии диалога видны последние сообщения в верном порядке; длинная история подгружается по 50 при прокрутке до начала; пустой диалог и граница пагинации — корректные состояния

**Independent Test**: Чат со 120 сообщениями: повторный вход → последние 50; прокрутка вверх подгружает по 50 (`before`/`nextBefore`) до начала; запрос за началом → пустая страница без `nextBefore`; порядок идентичен между сессиями; пустой диалог — пустое состояние без ошибок (SC-004)

### Tests for User Story 3 (писать ПЕРВЫМИ)

- [X] T038 [P] [US3] `HistoryIT`: 120 сообщений → страницы по 50, `nextBefore` до исчерпания, пустая страница на границе без ошибок, `400 limit_out_of_range` (0 и 51), порядок стабилен между повторными загрузками, видимость per-user после удаления чата — в `backend/src/test/kotlin/webchat/backend/chats/HistoryIT.kt`

### Implementation for User Story 3

- [X] T039 [US3] Пагинация в `useChatMessages`: подгрузка старых при прокрутке (`before=nextBefore`), prepend к списку, остановка при исчерпании, пустое состояние диалога — в `frontend/src/chats/hooks/useChatMessages.ts` и `frontend/src/chats/components/MessageList.tsx`
- [X] T040 [P] [US3] Frontend-тесты: подгрузка старых страниц до начала, граница (пустая страница — без ошибок), пустое состояние чата — в `frontend/src/chats/components/__tests__/MessageList.pagination.test.tsx`; прогон `HistoryIT` зелёным

**Checkpoint**: История доступна и пагинируется при повторном входе (quickstart §3.3)

---

## Phase 6: User Story 4 — Статус «прочитано» (двойная галочка) (Priority: P2)

**Goal**: Просмотр получателем отмечает сообщения прочитанными; отправитель видит ✓ → ✓✓ в реальном времени; галочки только на исходящих; статус монотонен и идемпотентен

**Independent Test**: Отправка → ✓; получатель открывает диалог → у отправителя ✓✓ ≤2 с (`chat.read`); пока не открывал — ✓; повторные/меньшие `upToSeq` не откатывают статус; чтение истории при повторном входе тоже отмечает прочитанным (SC-007)

### Tests for User Story 4 (писать ПЕРВЫМИ)

- [X] T041 [P] [US4] `ReadReceiptsIT`: `POST /read` двигает водяной знак, `204`; повторный/меньший `upToSeq` — без эффекта и без события (идемпотентность/монотонность US4-5); `upToSeq` больше seq последнего сообщения чата → `400 invalid_up_to_seq`, водяной знак не двигается; событие `chat.read` доставляется собеседнику в реальном времени; чтение загруженных страниц истории продвигает отметку — в `backend/src/test/kotlin/webchat/backend/chats/ReadReceiptsIT.kt`

### Implementation for User Story 4

- [X] T042 [US4] `ReadService` + endpoint №17 `POST /api/v1/chats/{chatId}/read` (`{1 ≤ upToSeq ≤ chats.last_seq}`, `400 invalid_up_to_seq`): `UPDATE … SET last_read_seq = GREATEST(...) WHERE ... AND last_read_seq < :upToSeq`; rowcount>0 → после коммита publish `chat.read {chatId, readUpToSeq, byUserId}` собеседнику — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/ReadService.kt` и `backend/src/main/kotlin/webchat/backend/chats/api/MessageController.kt` (по plan.md: «отправка, история, прочтение» — MessageController) ([research.md §5](./research.md))
- [X] T043 [US4] Поля чтения в `ChatView` (`peerReadUpToSeq`, `myReadUpToSeq`) для `GET /chats/{id}` и `POST /chats/ensure` — в `backend/src/main/kotlin/webchat/backend/chats/api/dto/` и `ChatService`/`JdbcParticipantRepository`
- [X] T044 [US4] Frontend: отправка read-отметок при отображении сообщений (троттлинг ≤500 мс — укладывается в SC-007 ≤2 с с запасом; при рендере новых и загрузке страниц истории, включая страницы, подгруженные пагинацией T039); рендер ✓✓ для `seq ≤ peerReadUpToSeq` (из ChatView и событий `chat.read`, монотонно) — в `frontend/src/chats/hooks/useChatMessages.ts`, `frontend/src/chats/components/MessageList.tsx`
- [X] T045 [P] [US4] Frontend-тесты: ✓/✓✓ по водяному знаку и `chat.read`, отсутствие галочек на входящих, троттлинг отправки read — в `frontend/src/chats/components/__tests__/MessageList.test.tsx` (расширение)
- [X] T046 [US4] Метрика `webchat_read_advanced_total` + прогон `ReadReceiptsIT` зелёным — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/ReadService.kt`

**Checkpoint**: Цикл общения замкнут: доставлено/прочитано с realtime-обновлением (quickstart §3.4)

---

## Phase 7: User Story 5 — Панель «Чаты»/«Контакты», контакты, блокировка, удаление чата (Priority: P2)

**Goal**: Панель слева с режимами «Чаты» (сортировка по последнему сообщению, бейдж «99+», метка «заблокирован») и «Контакты» (поиск по email/логину, алфавитная сортировка); контакты и чаты независимы; per-user удаление чата; блокировка с семантикой FR-020 без утечек

**Independent Test**: Контакт добавляется через поиск, клик открывает единый диалог; удаление контакта сохраняет чат; `DELETE /chats/{id}` скрывает историю только у удалившего, новый входящий возвращает чат без старой истории; незнакомец пишет → чат появляется автоматически; блокировка: отправка запрещена в обе стороны (`403 you_are_blocked`/`chat_blocked_by_you`), прочтения/бейдж замирают, разблокировка возвращает всё; себя добавить/открыть → `422`

**Зависимости**: бейдж непрочитанных опирается на водяной знак US4 (фаза выполняется после Phase 6)

### Tests for User Story 5 (писать ПЕРВЫМИ)

- [X] T047 [P] [US5] `ContactsIT`: поиск точный `lower(email)`/`lower(username)` по `@`-правилу, регистронезависимость, пустой результат без ошибок, `400 query_missing`, self → `422 self_forbidden`, повторное добавление → `200` без дубля, удаление контакта сохраняет чат/историю, сортировка `sort=login|email`, `400 invalid_sort` — в `backend/src/test/kotlin/webchat/backend/contacts/ContactsIT.kt`
- [X] T048 [P] [US5] `BlockingIT`: отправка в обе стороны → `403 chat_blocked_by_you`/`you_are_blocked`, записей нет; прочтения блокирующего не двигают водяной знак и не публикуются; прочтения заблокированного — без публикации; бейдж блокирующего «замирает»; `PUT/DELETE block` идемпотентны (204); разблокировка возобновляет; контакты независимы от блокировок; `blockedByMe` — единственная проекция (поля «кто меня заблокировал» нет); блокировка при активных SSE-подписках обоих участников: в поток заблокированного события не публикуются, отправка отклоняется до записи — неявная доставка исключена, состояние сходится (Edge Cases) — в `backend/src/test/kotlin/webchat/backend/contacts/BlockingIT.kt` (FR-020)
- [X] T049 [P] [US5] `ChatDeletionIT`: `DELETE /chats/{id}` скрывает историю только у удалившего (второй участник не затронут), повторный DELETE → `204`; ensure снимает `hidden`, водяной знак сохраняет; новый входящий возвращает чат без старой истории; чат от незнакомца появляется автоматически (FR-019); пустой чат после удаления скрыт полностью — в `backend/src/test/kotlin/webchat/backend/chats/ChatDeletionIT.kt` (FR-021)

### Implementation for User Story 5

- [X] T050 [P] [US5] Доменные модели и порты `Contact`, `UserBlock`, `ContactRepository`, `BlockRepository`, `UserLookupPort` — в `backend/src/main/kotlin/webchat/backend/contacts/domain/model/` и `backend/src/main/kotlin/webchat/backend/contacts/domain/port/`
- [X] T051 [P] [US5] `JdbcContactRepository` (парные PK, ON CONFLICT DO NOTHING), `JdbcBlockRepository` (точечные проверки пары в обе стороны, идемпотентные PUT/DELETE), `JdbcUserLookup` (точный поиск по `lower(email)`/`lower(username)`) — в `backend/src/main/kotlin/webchat/backend/contacts/repository/`
- [X] T052 [US5] `ContactService` (self → `422`, `404 user_not_found`, повтор → `200` существующего) и `BlockService` (идемпотентные блок/разблок → `204`) — в `backend/src/main/kotlin/webchat/backend/contacts/domain/service/`
- [X] T053 [US5] Контроллеры: №19 `GET /api/v1/users/search?query=`, №20 `GET /api/v1/contacts?sort=login|email`, №21 `POST /contacts`, №22 `DELETE /contacts/{userId}`, №23 `PUT /users/{userId}/block`, №24 `DELETE /users/{userId}/block` + DTO `ContactView` — в `backend/src/main/kotlin/webchat/backend/contacts/api/ContactController.kt`, `UserSearchController.kt`, `BlockController.kt`, `backend/src/main/kotlin/webchat/backend/contacts/api/dto/`
- [X] T053a [US5] Пер-юзер rate limit на `GET /api/v1/users/search` (№19; FR-016; после T032 — обобщение `RateLimitConfig`; тест-первым: ассерт в `ContactsIT` — 31-й запрос/мин → `429 flood_limit` + `Retry-After`): бакет `rl:user:search:{userId}` — 30 req/мин (Bucket4j+Lettuce), warn-лог без PII, метрика `webchat_search_rejected_total{reason=flood}` — в `backend/src/main/kotlin/webchat/backend/contacts/api/UserSearchController.kt` и `backend/src/main/kotlin/webchat/backend/config/RateLimitConfig.kt`
- [X] T054 [US5] Семантика блокировки в домене `chats` (дедап-lookup остаётся первым): `MessageService` — обе проверки блока до INSERT с `403`-кодами, warn-логи + `webchat_send_rejected_total{reason=blocked_by_you|you_are_blocked}`; `ReadService` — подавление прочтений (блокирующий: водяной знак не двигается; заблокированный: двигается без публикации); `blockedByMe` в `ChatView`/`ChatListItem` — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt`, `ReadService.kt`, `ChatService.kt` ([research.md §6](./research.md))
- [X] T055 [US5] Endpoint №12 `GET /api/v1/chats` — список `ChatListItem` одним запросом: JOIN участников + peer + собственные блоки + агрегаты (`lastMessage` по максимальному видимому `seq` — выбор внутри чата, `unreadCount` по `seq > last_read_seq AND seq > deleted_up_to_seq AND sender_id != me`), сортировка по `created_at` последнего видимого сообщения DESC NULLS LAST, tie-break `chat_id` — строго по контракту №12 (убывание `createdAt`); `chats.last_seq` — только для видимости/unread, не как межчатовый ключ (глобальная монотонность `seq` контрактом не является — plan.md); исходящее тоже поднимает; исключение только полностью удалённых: `hidden = true AND deleted_up_to_seq >= chats.last_seq` (инвариант видимости data-model §2; пустые чаты с `hidden = false` остаются в списке ниже переписок — FR-014/FR-018; сброс `hidden` при новом входящем — T012); чат с новым входящим (`chats.last_seq > deleted_up_to_seq`) возвращается в список (FR-021, T049) — в `backend/src/main/kotlin/webchat/backend/chats/api/ChatController.kt`, `domain/service/ChatService.kt`, `repository/JdbcChatRepository.kt` ([research.md §8](./research.md))
- [X] T056 [US5] Endpoint №14 `DELETE /api/v1/chats/{chatId}` — per-user: `deleted_up_to_seq = last_seq`, `hidden = true` атомарно, идемпотентный `204` — в `backend/src/main/kotlin/webchat/backend/chats/api/ChatController.kt` и `domain/service/ChatService.kt`
- [X] T057 [P] [US5] Хук `useChatList`: загрузка списка, событийные обновления (`message.created`/`chat.read`: позиции, бейджи); событие `message.created` с неизвестным `chatId` (новый чат от незнакомца, FR-019) → рефетч списка — чат появляется без ручного обновления (US5-4); рефетч при (пере)подключении SSE — в `frontend/src/chats/hooks/useChatList.ts`
- [ ] T058 [P] [US5] `ChatListPanel` (переключатель режимов «Чаты»/«Контакты», состояние списков сохраняется) и `ChatListItem` (превью последнего, бейдж непрочитанных с «99+», метка «заблокирован» при `blockedByMe`) — в `frontend/src/chats/components/ChatListPanel.tsx`, `frontend/src/chats/components/ChatListItem.tsx`
- [ ] T059 [P] [US5] `ContactList` (сортировка login/email переключаемая) и `UserSearchBox` (точный поиск, добавление, удаление контакта; клик по контакту → `ensureChat` → открыть диалог) — в `frontend/src/chats/components/ContactList.tsx`, `frontend/src/chats/components/UserSearchBox.tsx`
- [ ] T060 [US5] Действия панели: удаление чата (с purge outbox этого `chatId`, FR-021), блокировка/разблокировка пользователя, диалоговые меню — в `frontend/src/chats/pages/MessengerPage.tsx`, `frontend/src/chats/hooks/useOutbox.ts`; приёмка: UI-действия покрываются T061, `BlockingIT`/`ChatDeletionIT` остаются зелёными
- [ ] T061 [P] [US5] Frontend-тесты: переключение режимов, бейдж «99+», метка «заблокирован», сортировка контактов, поведение после удаления чата, появление чата от незнакомца по событию с неизвестным `chatId` (FR-019) — в `frontend/src/chats/components/__tests__/ChatListPanel.test.tsx`, `frontend/src/chats/components/__tests__/ContactList.test.tsx`, `frontend/src/chats/hooks/__tests__/useChatList.test.ts`
- [ ] T062 [US5] Прогон `ContactsIT`, `BlockingIT`, `ChatDeletionIT` зелёными + ручная проверка quickstart §3.5/§3.7

**Checkpoint**: Полноценный мессенджер: панель, контакты, блокировка, удаление чата (quickstart §3.5, §3.7)

---

## Phase 8: User Story 6 — Реалтайм-канал в публичном API-контракте (Priority: P3)

**Goal**: Любой клиент, построенный строго по OpenAPI-контракту (без внутренних интерфейсов), подключается к каналу, отправляет и получает сообщения; прямая совместимость (неизвестные события игнорируются); обратная совместимость версий контракта

**Independent Test**: Контрактный скрипт/curl: ensure → подписка SSE → отправка → кадр `message.created` соответствует схеме → повторная отправка того же id без дубля; конвейер `vacuum → generate:api (drift) → oasdiff` без breaking, изменение только additive 0.4.0 (quickstart §3.6)

### Implementation for User Story 6

- [ ] T063 [US6] Прямая совместимость SSE-клиента: неизвестные `event:`-типы обязаны игнорироваться (US6-3) + тест с неизвестным событием — в `frontend/src/api/sse.ts` и `frontend/src/api/__tests__/sse.test.ts`
- [ ] T064 [US6] Контрактная конформанс-проверка: payload кадров SSE ассертится против `components.schemas.MessageCreatedEvent`/`ChatReadEvent` (расширение `RealtimeSseIT`); скрипт строго-по-контракту (ensure → подписка → отправка → приём → повторный id без дубля) по quickstart §3.6.2 — в `backend/src/test/kotlin/webchat/backend/realtime/RealtimeSseIT.kt`
- [ ] T065 [US6] Контрактный конвейер: `vacuum lint` → `pnpm --dir frontend generate:api` без drift → `oasdiff` без breaking (additive minor 0.3.0 → 0.4.0); проверка инварианта состава — все новые пути аутентифицированы, публичный перечень 002–003 не расширен — по `contracts/openapi.yaml` (конвейер 001)

**Checkpoint**: API-First подтверждён практикой контрактного клиента (конституция IV)

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Нагрузочный smoke, наблюдаемость, безопасность, сквозная валидация

- [ ] T066 [P] k6-сценарий: пары VU (отправка + подтверждение, SSE-приём, p95 ≤ 2 с — SC-001/005); открытие диалога и страница истории p95 ≤ 3 с (SC-004); сценарий «прочитано» (POST /read → SSE `chat.read` у отправителя) p95 ≤ 2 с (SC-007); флуд-ветка — только `429` без 5xx; профиль нагрузки — 1 VU-пара (2 пользователя, research.md §13), прогон 5 мин; пороги зафиксированы в шапке скрипта — smoke проверяет латентности, не пиковые бюджеты (SC-006 — расчётное подтверждение, см. plan.md Constraints) — в `load/k6/messaging.smoke.js`
- [ ] T067 [P] Верификация наблюдаемости (SC-008): метрики `webchat_message_ack_seconds`, `webchat_realtime_push_seconds`, `webchat_message_dedup_total`, `webchat_send_rejected_total{reason=…}`, `webchat_search_rejected_total{reason=flood}`, `webchat_read_advanced_total` растут в сценариях quickstart §3.2/3.7/3.8 на `/actuator/prometheus`; warn-логи отклонений без текста сообщений (PII) — проверка по `backend/src/main/kotlin/webchat/backend/` (места реализации)
- [ ] T068 [P] Аудит безопасности/архитектуры: stateless-поды (состояние только PG/Redis), токен не в URL, membership на каждом ресурсе, отсутствие leakage-полей блокировки, секреты через env, наличие trace-спанов на новых endpoints (chats/contacts/realtime, экспорт OTLP) — ревью `backend/src/main/kotlin/webchat/backend/realtime/`, `chats/`, `contacts/`, `deploy/k8s/base/`
- [ ] T069 Сквозная ручная валидация по `specs/004-direct-messaging-core/quickstart.md` (все сценарии §3.1–§3.8) с фиксацией результатов; приёмка: чек-лист по каждому §3.x зафиксирован в PR
- [ ] T070 Финальная уборка: линт/форматирование backend (`./gradlew check`) и frontend (`pnpm --dir frontend lint test`), удаление мёртвого кода, проверка сборки целиком; приёмка: обе команды зелёные, сборка монорепо проходит

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно
- **Foundational (Phase 2)**: после Phase 1 — **БЛОКИРУЕТ все истории** (контракт 0.4.0 и миграции V10–V11)
- **User Stories (Phases 3–8)**: все зависят от Phase 2
  - Последовательный путь (рекомендуется для одного агента, конституция «Development Workflow»): US1 → US2 → US3 → US4 → US5 → US6
  - US1 и US2 — P1 (ядро); US3/US4/US5 — P2; US6 — P3
- **Polish (Phase 9)**: после всех историй

### User Story Dependencies

- **US1 (P1)**: после Phase 2; не зависит от других историй — MVP
- **US2 (P1)**: после Phase 2; расширяет send-путь US1 (`MessageService`, outbox) — реализуется после US1
- **US3 (P2)**: после Phase 2 формально; опирается на endpoint истории из US1 — после US1
- **US4 (P2)**: после Phase 2; опирается на SSE/события US1 — после US1
- **US5 (P2)**: после Phase 2; бейдж непрочитанных и подавление прочтений опираются на водяной знак US4 — после US4; блок-проверки встраиваются в send/read-пути US1/US4
- **US6 (P3)**: верифицирует контракт поверх всех операций — после US1–US5

### Within Each User Story

- Тесты пишутся ПЕРВЫМИ и падают до реализации (конституция VI)
- Порядок: тесты → модели → порты → репозитории → сервисы → endpoints → frontend-интеграция
- История завершена checkpoint-задачей (прогон IT зелёными)

### Parallel Opportunities

- Phase 1: T002 параллельно с T001
- Phase 2: T005 ∥ T006 (разные файлы) после/параллельно T003; T003b строго после T003 (тот же файл); T004 строго после T003b
- Внутри US1: модели (T009) ∥ порты (T010); SSE-стек (T018) ∥ ingress (T020) ∥ api-модули (T021, T022) ∥ хуки (T023, T024) ∥ компоненты (T026) — после своих зависимостей
- Тесты историй помечены [P] — пишутся параллельно (T007 ∥ T008 ∥ T008a; T029 ∥ T030; T047 ∥ T048 ∥ T049)
- Командная работа: после Phase 2 могут параллельно вестись US1, US3 (frontend-часть) и изолированные backend-задачи US5 — T050–T052 (домен/репозитории контактов и блокировок), T053 (контроллеры после T003b), T056 (DELETE чата после T011); задачи US5 с внешними зависимостями — по своим зависимостям: T053a после T032 (обобщение `RateLimitConfig`), T054 после T042 (`ReadService`), T055 после US4 (водяной знак/бейдж), T057–T061 после US4; координация на `MessageService`/контракт

---

## Parallel Example: User Story 1

```bash
# Тесты US1 параллельно:
Task T007: "RealtimeSseIT в backend/src/test/kotlin/webchat/backend/realtime/RealtimeSseIT.kt"
Task T008: "ChatAccessIT в backend/src/test/kotlin/webchat/backend/chats/ChatAccessIT.kt"

# Модели и порты параллельно:
Task T009: "Chat/ChatParticipant/Message/MessageText в backend/.../chats/domain/model/"
Task T010: "ChatRepository/MessageRepository/ParticipantRepository/RealtimeEventPublisher в backend/.../chats/domain/port/"

# Независимые слои после сервисов:
Task T018: "RealtimeController + SseConnectionRegistry в backend/.../realtime/"
Task T020: "ingress-аннотации SSE в deploy/k8s/base/ingress.yaml"
Task T021: "frontend/src/api/chats.ts"
Task T022: "frontend/src/api/sse.ts"
```

## Parallel Example: User Story 5

```bash
# Тесты US5 параллельно:
Task T047: "ContactsIT в backend/src/test/kotlin/webchat/backend/contacts/ContactsIT.kt"
Task T048: "BlockingIT в backend/src/test/kotlin/webchat/backend/contacts/BlockingIT.kt"
Task T049: "ChatDeletionIT в backend/src/test/kotlin/webchat/backend/chats/ChatDeletionIT.kt"

# Модели/порты ∥ репозитории:
Task T050: "Contact/UserBlock + порты в backend/.../contacts/domain/"
Task T051: "JdbcContactRepository/JdbcBlockRepository/JdbcUserLookup в backend/.../contacts/repository/"

# Frontend-компоненты панели параллельно:
Task T057: "useChatList в frontend/src/chats/hooks/useChatList.ts"
Task T058: "ChatListPanel + ChatListItem"
Task T059: "ContactList + UserSearchBox"
```

---

## Implementation Strategy

### MVP First (User Story 1 + User Story 2)

1. Complete Phase 1: Setup
2. Complete Phase 2: Foundational (контракт 0.4.0 + миграции) — CRITICAL
3. Complete Phase 3: US1 — диалог, отправка, статусы, realtime
4. Complete Phase 4: US2 — exactly-once, ретраи, флуд-лимит (конституция III — ядро продукта)
5. **STOP and VALIDATE**: quickstart §3.1, §3.2, §3.8 — демо возможно

### Incremental Delivery

1. Setup + Foundational → фундамент
2. +US1 → тестировать независимо → демо (MVP)
3. +US2 → P1-гарантии complete → демо
4. +US3 → история → тестировать → демо
5. +US4 → двойные галочки → тестировать → демо
6. +US5 → панель/контакты/блокировка/удаление → тестировать → демо
7. +US6 → контрактный клиент → финал
8. Polish (k6, метрики, аудит, quickstart) → релиз

### Parallel Team Strategy

1. Команда вместе проходит Setup + Foundational
2. Далее (при ресурсах): Developer A — US1→US2; Developer B — US5 backend (после US4); US6 — последний
3. Координация: `contracts/openapi.yaml` заморожен фазой 2; `MessageService` и `RateLimitConfig` (T032/T053a) — точки слияния US2/US5

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story]-метки связывают задачи с US1–US6 из spec.md
- Каждая история независимо проверяема (см. «Independent Test» фаз; ручные сценарии — quickstart.md §3)
- Тесты обязательны (конституция VI): IT падают до реализации, зелёные — в checkpoint-задачах
- Коммит после каждой задачи или логической группы; статус отмечать в этом файле
- Изменения контракта — только additive через задачи (не ad hoc); breaking запрещён (конституция IV)
- Избегать: расплывчатых задач, конфликтов одного файла, межисторических зависимостей, ломающих независимость
