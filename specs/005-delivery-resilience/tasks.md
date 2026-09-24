---
description: "Task list for feature implementation"
---

# Tasks: Устойчивость доставки при разрывах соединения и пиковой нагрузке

**Input**: Design documents from `/specs/005-delivery-resilience/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/api-contract.md + contracts/sync-protocol.md ✅, quickstart.md ✅, `.specify/memory/constitution.md` ✅

**Tests**: ОБЯЗАТЕЛЬНЫ (конституция VI Test-First — NON-NEGOTIABLE; plan.md «Testing»; research.md §11). Тесты пишутся до/вместе с реализацией и должны падать до неё.

**Organization**: Задачи сгруппированы по пользовательским историям (US1–US4 из spec.md, приоритеты P1–P4) — каждая история реализуется и проверяется независимо.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность к пользовательской истории (US1…US4); setup/foundational/polish — без метки
- Во всех описаниях — точные пути к файлам

## Path Conventions

- **Web-монорепозиторий** (структура 001–004 сохранена): `backend/src/…` (Kotlin 2.2 + JDK 21, package-by-feature `webchat.backend`), `frontend/src/…` (React 19 + TS strict), `contracts/openapi.yaml`, `load/k6/`
- Тесты: `backend/src/test/kotlin/webchat/backend/…`, `frontend/src/…/__tests__/`
- Ссылки на решения: [research.md](./research.md) §№, [data-model.md](./data-model.md) (сущности 1–7), [contracts/api-contract.md](./contracts/api-contract.md) № операций, [contracts/sync-protocol.md](./contracts/sync-protocol.md) §№, [quickstart.md](./quickstart.md) §№

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Конфигурация фичи и расширение тестовой базы (проект и конвейер 001–004 уже существуют)

- [X] T001 Создать `DeliveryProperties` (`@ConfigurationProperties("delivery")`: `sync.message-page-size=50`, `sync.chat-page-size=20`, `sync.ack-batch-limit=100`, `backpressure.enabled=true`, `backpressure.min-limit`, `backpressure.max-limit`, `backpressure.latency-baseline`) и секцию `delivery.*` — в `backend/src/main/kotlin/webchat/backend/config/DeliveryProperties.kt` и `backend/src/main/resources/application.yml` (значения sync/ack — [api-contract.md](./contracts/api-contract.md); backpressure: `min-limit=4`, `max-limit=64`, `latency-baseline=50ms` — [research.md](./research.md) §6 / quickstart §2); приёмка: контекст стартует с `delivery.*`; sync/ack совпадают с контрактом, backpressure-границы — с quickstart §2 (используются IT Phase 3/6)
- [x] T002 [P] Расширить тестовые фикстуры для IT синхронизации (поверх `MessagingTestSupport` 004: регистрация+auth ≥3 пользователей, создание чатов, отправка сообщений с контролируемым `seq`, вызовы №25/№26/№15-after через WebTestClient, чтение `delivered_up_to_seq` из БД для ассертов) — в `backend/src/test/kotlin/webchat/backend/sync/SyncTestSupport.kt`; приёмка: фикстуры компилируются и используются IT T007/T008/T009/T030/T038

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: API-контракт 0.5.0 (источник истины, конституция IV), миграция БД и доменные основы — ДО любой истории

**⚠️ CRITICAL**: Ни одна пользовательская история не начинается до завершения этой фазы

- [X] T003 Обновить публичный контракт до **0.5.0 (additive, без BREAKING.md)**: №25 `POST /api/v1/users/me/delivery-ack` (батч ≤100, атомарен; `400 invalid_up_to_seq|invalid_uuid`, `403 not_participant`, `404 chat_not_found`), №26 `POST /api/v1/users/me/sync` (`cursors`/`chatLimit` 1–50 (20)/`messageLimit` 1–50 (50); `SyncResponse`/`SyncChatDelta` с `startAfterSeq`, `truncatedUpToSeq?`, `messages`, `hasMore`, `peerReadUpToSeq`, `unreadCount`, `lastSeq`, `desynced?`+`serverUpToSeq?`, у `SyncResponse` — `chats` + `moreChats`; чужие chatId в cursors молча игнорируются), расширение №15 (`after` эксклюзивный + `nextAfter?`, `400 mixed_cursors` при `after`+`before`), №16 новый ответ `503 server_busy` + `Retry-After`, схемы `DeliveryAckItem`/`DeliveryAckRequest`/`SyncCursor`/`SyncRequest`/`SyncChatDelta`/`SyncResponse`, `MessagePage.nextAfter?`, уточнение описания `ChatListItem.unreadCount` (доставка-ограниченный) — в `contracts/openapi.yaml` строго по [contracts/api-contract.md](./contracts/api-contract.md) §1–5 и [contracts/sync-protocol.md](./contracts/sync-protocol.md) §9; приёмка: `vacuum lint` зелёный
- [x] T004 Регенерировать TS-типы из контракта 0.5.0 (`pnpm --dir frontend generate:api`) и устранить drift — `frontend/src/api/schema.d.ts` (типы `DeliveryAckRequest`, `SyncRequest`, `SyncChatDelta`, `SyncResponse`, `MessagePage.nextAfter` для всех историй); приёмка: drift-проверка пуста, типы доступны фронтенд-тестам (T021/T022), конвейер T044 зелёный
- [x] T005 [P] Миграция **V12__delivery_sync.sql**: `ALTER TABLE chat_participants ADD COLUMN delivered_up_to_seq bigint NOT NULL DEFAULT 0` + `CHECK (delivered_up_to_seq >= 0)` + backfill `UPDATE chat_participants SET delivered_up_to_seq = GREATEST(last_read_seq, deleted_up_to_seq)` + `CREATE INDEX ix_chat_participants_user ON chat_participants (user_id)` — в `backend/src/main/resources/db/migration/V12__delivery_sync.sql` ([data-model.md](./data-model.md) «Миграция»); приёмка: накат Testcontainers зелёный во всех IT фаз 3–6, backfill даёт «прочитанное/удалённое заведомо позади»
- [x] T006 [P] Эволюция домена `chats`: модель `ChatParticipant` + поле `deliveredUpToSeq`; порт `ParticipantRepository` + методы `advanceDelivered(userId, acks)` (батч GREATEST-обновлений, rowcount 0 = без эффекта) и `loadForSync(userId, chatLimit)` (чаты участника с недоставленным, порядок `chats.last_seq DESC`, tie-break `created_at DESC, chat_id`) — в `backend/src/main/kotlin/webchat/backend/chats/domain/model/ChatParticipant.kt` и `backend/src/main/kotlin/webchat/backend/chats/domain/port/ParticipantRepository.kt` (DIP: адаптер снаружи, T010)

**Checkpoint**: Контракт 0.5.0 + V12 + доменные порты готовы — истории можно реализовывать (последовательно или параллельно командой)

---

## Phase 3: User Story 1 — Синхронизация пропущенных сообщений при переподключении (Priority: P1) 🎯 MVP

**Goal**: Позиция доставки `delivered_up_to_seq` продвигается только ack'ами (№25); догоняющая синхронизация pull'ом по курсору (№26 + №15 `after`): без дублей и пропусков, в порядке `seq`, постранично, чаты по последней активности; обрезка и «курсор из будущего» обрабатываются per-chat без ошибки запроса

**Independent Test**: Разорвать соединение (DevTools Offline), отправить в чат N=10 сообщений от собеседника, восстановить — в диалоге ровно 10 новых сообщений, каждое один раз, в порядке отправки; повтор `POST /users/me/sync` с теми же курсорами — в UI ничего не задвоилось (quickstart §3.1–3.2)

### Tests for User Story 1 (писать ПЕРВЫМИ, до реализации)

- [x] T007 [P] [US1] `DeliveryAckIT`: монотонность (меньшее/повтор — без эффекта, идемпотентно `204`), движение позиции только ack'ом (sync-ответ/SSE-кадр не пишут), атомарность батча (чужой chatId → `403 not_participant` всего батча, несуществующий → `404 chat_not_found`, `upToSeq > chats.last_seq` → `400 invalid_up_to_seq`, частичных эффектов нет), >100 элементов → `400` — в `backend/src/test/kotlin/webchat/backend/sync/DeliveryAckIT.kt` ([data-model.md](./data-model.md) сущность 1)
- [x] T008 [P] [US1] `SyncIT`: полнота/порядок (ascending `seq` строго `(startAfterSeq, …]`, включая исходящие пользователя), эффективный курсор `max(клиентский, серверный)`, пагинация (`messageLimit`/`hasMore`, `chatLimit`/`moreChats`, порядок чатов по последней активности DESC — первыми самые новые), обрезка (`truncatedUpToSeq = GREATEST(deleted_up_to_seq, окно)` — ветка «окно» не тестируема до archival 009, покрывается ветка `deleted_up_to_seq`; сообщения ниже не возвращаются, US1-5), «курсор из будущего» (`desynced: true` + `serverUpToSeq`, без ошибки запроса, остальные чаты штатно), чужие/несуществующие chatId в `cursors` молча игнорируются, all-or-refusal при частичной доступности (ошибка чтения по одному чату → временный отказ 5xx всего запроса, «частичный список как полный» исключён — edge), инвариант «порядок/границы только по серверному seq, клиентские времена нерелевантны» (edge «часы сбиты»), операция не двигает позицию доставки — в `backend/src/test/kotlin/webchat/backend/sync/SyncIT.kt` ([sync-protocol.md](./contracts/sync-protocol.md) §3, §5; SC-001)
- [x] T009 [P] [US1] `AscendingHistoryIT`: №15 `after` — ascending-страницы `(after, after+limit]`, `nextAfter` = seq последней записи, отсутствует при исчерпании (пустая страница на границе корректна), `after`+`before` → `400 mixed_cursors`, видимость по `deleted_up_to_seq` вызывающего, DESC-режим `before` (004) не регрессирует — в `backend/src/test/kotlin/webchat/backend/chats/AscendingHistoryIT.kt` ([api-contract.md](./contracts/api-contract.md) §2)

### Implementation for User Story 1 (backend)

- [x] T010 [US1] `JdbcParticipantRepository`: `advanceDelivered` (батч `UPDATE … SET delivered_up_to_seq = GREATEST(…) WHERE chat_id=? AND user_id=? AND delivered_up_to_seq < ?` по PK, одна транзакция) и `loadForSync` (join `chats`, отбор `max(видимый seq) > эффективный курсор`, топ-N по `chats.last_seq DESC`, `moreChats` по остатку) — в `backend/src/main/kotlin/webchat/backend/chats/repository/JdbcParticipantRepository.kt` (зависит от T005, T006); приёмка: ассерты `DeliveryAckIT`/`SyncIT` по `advanceDelivered`/`loadForSync` зелёные (батч одной транзакцией, rowcount 0 = без эффекта; порядок `chats.last_seq DESC`)
- [x] T011 [P] [US1] DTO синхронизации `DeliveryAckRequest`/`DeliveryAckItem`, `SyncRequest`/`SyncCursor`, `SyncResponse`/`SyncChatDelta` (типы из контракта 0.5.0, опциональные `truncatedUpToSeq`/`desynced`/`serverUpToSeq` отсутствуют, когда явление не возникло) — в `backend/src/main/kotlin/webchat/backend/sync/api/dto/`
- [x] T012 [US1] `DeliveryAckService`: предпроверка membership всех элементов (чужой → `403`, несуществующий → `404` — весь батч отклоняется), `upToSeq ≤ chats.last_seq` (`400 invalid_up_to_seq`), батчевое GREATEST-продвижение одной транзакцией → `204`; метрика `webchat_delivery_ack_seconds` — в `backend/src/main/kotlin/webchat/backend/sync/domain/service/DeliveryAckService.kt`
- [x] T013 [US1] `SyncService`: один снимок чтения (all-or-refusal, «частичный список как полный» исключён), эффективный курсор `max(клиентский, серверный)`, дельта: `messages` ascending ≤ `messageLimit` (только видимые, включая исходящие), `hasMore`/`lastSeq`/`peerReadUpToSeq`, `unreadCount` по полной формуле сущности 3 data-model (`GREATEST(last_read_seq, deleted_up_to_seq) < seq ≤ LEAST(chats.last_seq, delivered_up_to_seq)`, `sender_id != user`) — единый переиспользуемый вычислитель, обрезка `truncatedUpToSeq = startAfterSeq = эффективная нижняя граница`, per-chat ремонт «курсора из будущего» (`desynced`+`serverUpToSeq`, сервер не откатывает `delivered`); метрики `webchat_sync_request_seconds`, `webchat_sync_messages_delivered_total`, `webchat_sync_truncated_total`, `webchat_sync_desynced_total` — в `backend/src/main/kotlin/webchat/backend/sync/domain/service/SyncService.kt` ([research.md](./research.md) §1–§3); приёмка: `SyncIT` зелёный; sync-метрики экспортируются на `/actuator/prometheus`
- [x] T014 [US1] Контроллеры `DeliveryAckController` (№25 `POST /api/v1/users/me/delivery-ack`) и `SyncController` (№26 `POST /api/v1/users/me/sync`): Bearer, problem+json (`400 invalid_uuid|limit_out_of_range`, `401`), лимиты из `DeliveryProperties` — в `backend/src/main/kotlin/webchat/backend/sync/api/DeliveryAckController.kt` и `backend/src/main/kotlin/webchat/backend/sync/api/SyncController.kt`
- [x] T015 [US1] Ascending-режим №15: параметр `after` в `MessageController`, ascending-выборка `(after, after+limit]` + `nextAfter` в `HistoryService` и DTO (`AscendingPage`/расширение `MessagePage`), `400 mixed_cursors` при `after`+`before`; DESC-режим без изменений — в `backend/src/main/kotlin/webchat/backend/chats/api/MessageController.kt`, `backend/src/main/kotlin/webchat/backend/chats/domain/service/HistoryService.kt`, `backend/src/main/kotlin/webchat/backend/chats/api/dto/`

### Implementation for User Story 1 (frontend)

- [x] T016 [P] [US1] API-модуль: `deliveryAck(acks)`, `sync(cursors, chatLimit?, messageLimit?)`, ascending-страницы `listMessagesAfter(chatId, after, limit)` (типы из `schema.d.ts`, через `apiFetch` с refresh) — в `frontend/src/api/chats.ts`
- [x] T017 [P] [US1] `cursors.ts`: localStorage `webchat.sync.cursors.<userId>` — карта `{chatId: seq}`, применение монотонно (только max), self-heal: перемотка на `startAfterSeq`/`serverUpToSeq`, закрепление на `truncatedUpToSeq` — в `frontend/src/sync/cursors.ts` ([sync-protocol.md](./contracts/sync-protocol.md) §1)
- [x] T018 [P] [US1] `ack.ts`: дебаунс-батчер ack'ов (флаш ≤1 с, `upToSeq = max(seq)` по каждому чату; источники: realtime-кадры, применённые страницы sync/№15, точка обрезки; ≤100 элементов; неуспешный ack ретраится следующим флашем) — в `frontend/src/sync/ack.ts`
- [x] T019 [US1] Хук `useSync`: single-flight догоняющая синхронизация на `onOpen` SSE — нормативный цикл [sync-protocol.md](./contracts/sync-protocol.md) §3.1 (цикл A: №26 → курсор=self-heal → render с дедупом по `message.id` → `ack(max(последний seq | truncatedUpToSeq))`; цикл B по `hasMore`-чатам: №15 `after` до исчерпания `nextAfter`; повтор A пока `moreChats`); обрыв посреди цикла — возобновление с последней подтверждённой позиции; параллельные конкурирующие синхронизации запрещены; realtime работает параллельно (гонка безопасна дедупом); индикация прогресса — в `frontend/src/sync/hooks/useSync.ts`; приёмка: T022 зелёный; повторный №26 с теми же курсорами не задваивает UI (quickstart §3.1)
- [x] T020 [P] [US1] Компонент `SyncIndicator` (индикатор процесса дозагрузки: активен во время циклов A/B, гаснет по завершении) — в `frontend/src/chats/components/SyncIndicator.tsx`
- [x] T021 [P] [US1] Frontend-тесты: `cursors` (монотонность — только max; self-heal по `serverUpToSeq`/`truncatedUpToSeq`; переживание перезапуска) и `ack`-батчер (дебаунс ≤1 с, max по чату, батч ≤100, retry неуспешного следующим флашем) — в `frontend/src/sync/__tests__/cursors.test.ts` и `frontend/src/sync/__tests__/ack.test.ts`
- [x] T022 [P] [US1] Frontend-тесты `useSync`: single-flight (второй вызов при in-flight — ожидание), полнота/порядок/дедуп (повтор доставки тем же id — один рендер, US1-3), обрыв посреди цикла → возобновление по курсору без дублей (US1-4), дельта с `truncatedUpToSeq` не восстанавливает удалённое (US1-5), новый чат появляется со всей перепиской (US1-6) — в `frontend/src/sync/hooks/__tests__/useSync.test.ts`
- [x] T023 [US1] Интеграция: `onOpen` SSE-клиента → `useSync`; применение дельт в список чатов и диалоги (рендер по `seq`, дедуп по `message.id`); `SyncIndicator` в layout; прогон `DeliveryAckIT`, `SyncIT`, `AscendingHistoryIT` и frontend-тестов T021/T022 зелёными — в `frontend/src/chats/hooks/useRealtime.ts`, `frontend/src/chats/pages/MessengerPage.tsx` (точки подключения)

**Checkpoint**: MVP работает: переподключение → дозагрузка без потерь/дублей, в порядке, постранично, по последней активности; ack — единственный двигатель позиции (quickstart §3.1–3.2, SC-001)

---

## Phase 4: User Story 2 — Оффлайн-очередь исходящих сообщений (Priority: P2)

**Goal**: Outbox получает лимит 1000 с вытеснением старейших `sending` в терминальное «не отправлено» (`queue_overflow`, уведомление); временные отказы `429 flood_limit` и `503 server_busy` обрабатываются прозрачно (retryAt по Retry-After, FIFO per-chat, лимитное окно не обходится)

**Independent Test**: Offline: 3 сообщения → «отправляется», закрыть/reopen приложение (сеть выкл.) → очередь на месте; сеть on → все 3 доставлены ровно по одному разу, в порядке отправки (quickstart §3.3)

### Tests for User Story 2 (писать ПЕРВЫМИ)

- [x] T024 [P] [US2] Frontend-тесты outbox-лимита: при 1000 записях новые принимаются с вытеснением старейших `sending` → `failed` + `errorCode='queue_overflow'`; `failed` не вытесняются; вытесненное повторно в очередь не принимается; ручной retry вытеснённого тем же ID работает — в `frontend/src/chats/__tests__/outbox.limit.test.ts` ([data-model.md](./data-model.md) сущность 4; edge «лимит очереди»)
- [x] T025 [P] [US2] Frontend-тесты `useOutbox` классификации: `503 server_busy` + `Retry-After` → запись остаётся `sending` + `retryAt` (как 429, FR-009); сеть/таймаут/5xx → `sending` + backoff 1с…30с бессрочно тем же ID; постоянные 4xx (400/403/404/409) → `failed` терминально (FR-006); FIFO per-chat при флаше (чат не ждёт чужой блокировки) — в `frontend/src/chats/hooks/__tests__/useOutbox.retry.test.ts` (503 мокается; E2E-эффект — после T041)

### Implementation for User Story 2

- [x] T026 [US2] `outbox.ts`: константа лимита 1000 записей на пользователя; enqueue с вытеснением старейших `sending` (начало массива): `state='failed'`, `errorCode='queue_overflow'`, автоповторы прекращаются, событие уведомления для баннера; `failed`-записи не вытесняются — в `frontend/src/chats/outbox.ts`; приёмка: T024 зелёный
- [x] T027 [US2] `useOutbox`: классификация `503 server_busy` → `retryAt = now + Retry-After` (единообразно с `429 flood_limit`); флаш FIFO per-chat при восстановлении связи; соблюдение лимитного окна 30/мин планировщиком `retryAt` (лимит не обходится повторами, FR-010); повторы при временных отказах бессрочны, эскалаций по времени нет — в `frontend/src/chats/hooks/useOutbox.ts`; приёмка: T025 зелёный; флаш не обходит 30/мин
- [x] T028 [P] [US2] Компонент `QueueOverflowBanner` (уведомление о вытеснении) и рендер причины «не отправлено (переполнение очереди)» у вытеснённых сообщений — в `frontend/src/chats/components/QueueOverflowBanner.tsx` и `frontend/src/chats/components/MessageList.tsx`
- [x] T029 [US2] Интеграция и валидация: прогон T024/T025 зелёными, ручная проверка quickstart §3.3 (персистентность очереди, потеря подтверждения — дедуп тем же ID, постоянный отказ не блокирует очередь, 31-е сообщение/мин прозрачно встаёт в очередь; одновременная отправка из очереди и реалтайм в один чат после восстановления сети — порядок в диалоге по серверному seq (фактическому порядку принятия), без потерь/дублей (edge spec.md)) — файлы по месту реализации

**Checkpoint**: SC-002 выполняется: оффлайн-отправки доставляются ровно по одному разу, в порядке, переживая перезапуск; переполнение — явное и контролируемое

---

## Phase 5: User Story 3 — Индикаторы непрочитанных сообщений по чатам (Priority: P3)

**Goal**: Счётчик непрочитанных серверно-авторитарный и ограничен позицией доставки: realtime и sync-дозагруженные увеличивают его одинаково (после ack); прочтение сбрасывает на фактически прочитанное; оффлайн-прочтения флушатся идемпотентно (№17); догоняющая синхронизация доносит ✓✓ по `peerReadUpToSeq`

**Independent Test**: Сообщения в двух чатах (одно realtime, одно после переподключения), не открывая чаты — счётчики обоих растут до фактических; открыть чаты — сброс, у отправителей ✓✓ (quickstart §3.4)

### Tests for User Story 3 (писать ПЕРВЫМИ)

- [x] T030 [P] [US3] `UnreadConvergenceIT`: формула `unread = COUNT(входящих, seq > GREATEST(last_read, deleted), seq ≤ LEAST(last_seq, delivered))` — рост только по ack доставки (realtime и sync одинаково, US3-1/US3-2), прочтение №17 → уменьшение на фактически прочитанное (включая постраничное, US3-6), сообщения ниже `truncatedUpToSeq` не входят (US1-5/FR-007), чтение во время дозагрузки — счётчик не уходит в минус и не задваивается (edge), флаш отложенного «прочитано» через №17 → сходимость серверного счётчика и ✓✓ у отправителя (US3-7) — в `backend/src/test/kotlin/webchat/backend/sync/UnreadConvergenceIT.kt` ([data-model.md](./data-model.md) сущность 3; SC-003)

### Implementation for User Story 3

- [x] T031 [US3] Серверно-авторитарная unread-формула с `delivered_up_to_seq`: вычисление в №12 (список чатов) и в `SyncChatDelta.unreadCount` — единый запрос `COUNT` с границами `GREATEST(last_read_seq, deleted_up_to_seq) < seq ≤ LEAST(chats.last_seq, delivered_up_to_seq)`, `sender_id != user` — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt`, SQL в `backend/src/main/kotlin/webchat/backend/chats/repository/` и переиспользование вычислителя T013 в `backend/src/main/kotlin/webchat/backend/sync/domain/service/SyncService.kt` (без дублирования формулы); приёмка: `UnreadConvergenceIT` зелёный
- [x] T032 [P] [US3] `pendingReads.ts`: localStorage `webchat.sync.pendingReads.<userId>` — per-chat watermark `upToSeq` «прочитано, но не подтверждено сервером»; запись ДО попытки №17, удаление после `204`; монотонен локально; переживает перезапуск; применим только к ранее синхронизированным (`upToSeq ≤ delivered` клиента) — в `frontend/src/sync/pendingReads.ts` ([data-model.md](./data-model.md) сущность 5)
- [x] T033 [P] [US3] Frontend-тесты `pendingReads`: переживание перезапуска, монотонность watermark, удаление только после `204`, идемпотентный повторный флаш (GREATEST на сервере — повтор после потери ответа безопасен) — в `frontend/src/sync/__tests__/pendingReads.test.ts`
- [x] T034 [US3] Флаш pendingReads на reconnect: в цикле `useSync` (шаг `flushPendingReads()` до синхронизации, [sync-protocol.md](./contracts/sync-protocol.md) §3.1/§6) + запись watermark при прочтении в диалоге; серверный счётчик сходится с фактом — в `frontend/src/sync/hooks/useSync.ts` и точке прочтения `frontend/src/chats/hooks/useChatMessages.ts`
- [x] T035 [US3] `useChatList`: `unreadCount` из №12 + sync-дельт + realtime с оптимистичным кэшем и сходимостью к серверному значению; рендер «99+» при >99 (внутренний счёт точный); заморозка бейджа при блокировке — семантика 004 без изменений (US3-5) — в `frontend/src/chats/hooks/useChatList.ts` и `frontend/src/chats/components/ChatListItem.tsx` (+ `ChatListPanel.tsx`)
- [x] T036 [P] [US3] Двойные галочки по `peerReadUpToSeq` из sync-дельт: актуализация статусов собственных сообщений после переподключения без обновления страницы, каждое статусное событие применяется один раз (US3-8) — в `frontend/src/chats/components/MessageList.tsx` и `frontend/src/chats/hooks/useChatMessages.ts`
- [ ] T037 [US3] Валидация истории: прогон `UnreadConvergenceIT` + frontend-тестов T033 зелёными, ручная проверка quickstart §3.4 ( realtime/sync-рост, частичное постраничное чтение, оффлайн-прочтения с перезапуском, ✓✓ за оффлайн, заморозка при блокировке) — файлы по месту реализации

**Checkpoint**: SC-003 выполняется: счётчики сходятся с фактом после realtime, reconnect-синхронизации и оффлайн-прочтений

---

## Phase 6: User Story 4 — Контролируемая деградация под пиковой нагрузкой (Priority: P4)

**Goal**: Адаптивный per-instance admission-лимитер (gradient/AIMD, in-memory) на пути отправки №16: поэтапная деградация «рост задержки → `503 server_busy` + `Retry-After`»; дедуп-фастпас остаётся первым (ретраи сходятся без штрафов и без обхода 30/мин); принятые доставляются в приоритете; самовосстановление ≤5 мин

**Independent Test**: В dev-профиле занизить `delivery.backpressure.max-limit=2` → параллельные отправки №16: часть `503`+`Retry-After` problem+json `server_busy`; принятые (201) повтором тем же ID → `200` (никогда 503); UI ошибок не показывает — очередь уходит по Retry-After (quickstart §3.5)

### Tests for User Story 4 (писать ПЕРВЫМИ)

- [ ] T038 [P] [US4] `BackpressureIT`: порядок проверок дедуп → admission → membership → валидация → флуд (ретрай принятого тем же ID — `200`, никогда 503/429); shed → `503` problem+json `errors:{chat:[server_busy]}` + `Retry-After` ≥ 1 с; AIMD-восстановление лимита после спада (gauge `webchat_backpressure_limit` возвращается к базе); флуд-токены не сгорают на shed'ах — после спада лимит 30/мин действует и не обходится повторами (FR-010); при `delivery.backpressure.enabled=false` путь отправки не меняется — в `backend/src/test/kotlin/webchat/backend/backpressure/BackpressureIT.kt` ([data-model.md](./data-model.md) сущность 6; SC-005/006/007)

### Implementation for User Story 4

- [ ] T039 [P] [US4] Порт `SendAdmissionGate` + `AdmissionMetrics`: gauges `webchat_backpressure_limit`/`webchat_backpressure_inflight`, counter `webchat_backpressure_rejections_total` (Micrometer) — в `backend/src/main/kotlin/webchat/backend/backpressure/SendAdmissionGate.kt` и `backend/src/main/kotlin/webchat/backend/backpressure/AdmissionMetrics.kt`
- [ ] T040 [US4] `GradientAdmissionLimiter`: in-flight semaphore + EWMA латентности пути PG-коммита + AIMD по gradient (границы `delivery.backpressure.{min-limit,max-limit,latency-baseline}`); `onShed` → `503` + `Retry-After = ceil(in-flight × EWMA / max(limit,1))`, минимум 1 с; сброс/рестарт безопасен (транспортное состояние, конституция II — прецедент SSE-реестра 004) — в `backend/src/main/kotlin/webchat/backend/backpressure/GradientAdmissionLimiter.kt` ([research.md](./research.md) §6; ~100 строк, без новых зависимостей); приёмка: T038 зелёный (503 + Retry-After ≥ 1 с, AIMD-возврат лимита)
- [ ] T041 [US4] Внедрение admission в путь отправки: вызов порта в `MessageService` ПОСЛЕ дедуп-фастпаса и ДО membership/флуда (порядок из [api-contract.md](./contracts/api-contract.md) §3); маппинг `503 server_busy` + `Retry-After` в `MessageController` (№16); путь доставки принятых — без admission (приоритет доставки, US4-4); warn-лог shed'ов без текста сообщений — в `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt` и `backend/src/main/kotlin/webchat/backend/chats/api/MessageController.kt`; приёмка: `BackpressureIT` зелёный; порядок дедуп→admission→…→INSERT ассертится
- [ ] T042 [P] [US4] k6-сценарий устойчивости: профиль разрывов (принудительные обрывы SSE в разных точках пути; аудит «0 потерь / 0 дублей / порядок монотонен» по seq; дозагрузка 200 сообщений (суммарно, ≥3 чата) p95 ≤ 10 с; env `K6_BASE_URL`, `K6_MAILPIT_URL`, `K6_DISCONNECT_PROFILE`) и профиль насыщения (`K6_TARGET_RPS`/`K6_DURATION`: пороги — 0 «молчаливых» отказов без предшествующего 503/429, все отказы с `Retry-After` ≥ 1, принятые без потерь/дублей, задержка доставки (send→SSE-кадр — консервативный прокси бюджета SC-004 «от приёма сервером до доставки»: включает upstream клиента, пренебрежимый на LAN-стенде) p99 ≤ 500 мс до перегрузки и p99 ≤ 2 с пока активен перегруженный режим (от первого backpressure/rate-limiting сигнала до рассасывания очередей — признак SC-004), дозагрузка 200 сообщений ≤ 30 с (p95) в перегруженном режиме (SC-008), рекавери латентности ≤ 5 мин после ramp-down; критерий валидности прогона 005: достигнутый пик потока ≥ 5 000 msg/s и длительность активного перегруженного режима (от первого 503/429 до рассасывания очередей) ≥ 60 с — независимо от стенда, ramp продолжается до первого shed-сигнала; параметризация бюджетного профиля 100k msg/s × 10 мин для 014) — в `load/k6/delivery-resilience.js` ([research.md](./research.md) §10; SC-001/004/005/006/007/008)
- [ ] T043 [US4] Валидация деградации: прогон `BackpressureIT` зелёным; сборка образа `docker build -t webchat-k6 load/k6` и прогон профиля разрывов на локальном стенде; ручная проверка quickstart §3.5 (заниженный `max-limit`, конвергенция клиентской очереди из T027 с реальными 503) — файлы по месту реализации

**Checkpoint**: SC-005/006/007 выполняются на достижимом масштабе: монотонная деградация, только явные временные отказы, самовосстановление; полнообъёмный прогон — платформенная фича 014 (plan.md Complexity Tracking)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные гарантии — контрактный конвейер, наблюдаемость, полная проверка quickstart

- [ ] T044 [P] Контрактный конвейер 001: `vacuum lint` → `pnpm --dir frontend generate:api` (drift-проверка пуста) → `oasdiff` без breaking (изменение additive minor 0.4.0 → 0.5.0) — проверка в `contracts/openapi.yaml` + CI-конвейере; приёмка: все шаги зелёные, схема quickstart §3.6 подтверждается curl-прогоном (№26 → №25 → №15-after до исчерпания → повтор №26 → `moreChats: false`)
- [ ] T045 [P] Аудит наблюдаемости FR-014: 8 метрик фичи (`webchat_sync_request_seconds`, `webchat_sync_messages_delivered_total`, `webchat_sync_truncated_total`, `webchat_sync_desynced_total`, `webchat_delivery_ack_seconds`, `webchat_backpressure_rejections_total`, gauges `webchat_backpressure_limit`/`webchat_backpressure_inflight`) присутствуют на `/actuator/prometheus` и растут в сценариях quickstart §3.1–3.5; наблюдаемость rate limiting (FR-014) — существующим счётчиком 004 `webchat_send_rejected_total{reason=flood}` (`SendPolicyGate.kt`): проверить его присутствие и рост в профиле насыщения T042; warn-логи shed/обрезок — JSON, без текста сообщений (PII-минимизация 004) — файлы по месту реализации
- [ ] T046 Полный прогон quickstart.md (§3.1–3.6) на локальной инфраструктуре (`docker compose -f deploy/local/docker-compose.yml up -d`, `./gradlew bootRun`, `pnpm --dir frontend dev`) с фиксацией результатов; расхождения — исправить и отразить в `specs/005-delivery-resilience/quickstart.md` — все затронутые файлы
- [ ] T047 [P] Финальная сборка и линт: `./gradlew check` (workdir `backend/`) — все IT зелёные; `pnpm --dir frontend test` + `pnpm --dir frontend lint` (strict TS) зелёные; коммит после каждой задачи/логической группы (конституция «Development Workflow») — репозиторий целиком

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Нет зависимостей — начинать сразу
- **Foundational (Phase 2)**: Зависит от Phase 1 (T001 для лимитов, T002 для IT) — БЛОКИРУЕТ все истории
- **User Stories (Phase 3–6)**: Все зависят от Foundational (контракт 0.5.0 + V12 + порты)
- **Polish (Phase 7)**: Зависит от всех завершённых историй

### User Story Dependencies

- **US1 (P1)**: После Phase 2 — не зависит от других историй (MVP)
- **US2 (P2)**: После Phase 2 — не зависит от US1 (эволюция outbox 004; классификация 503 тестируется моками, E2E-эффект после US4)
- **US3 (P3)**: После Phase 2; **зависит от US1** (sync-дельты, ack-движение позиции, `peerReadUpToSeq` из дельт)
- **US4 (P4)**: После Phase 2 — серверная часть не зависит от US1–US3; полная E2E-цепочка «503 → прозрачная очередь» сходится с US2 (T043)

### Within Each User Story

- Тесты пишутся ПЕРВЫМИ и падают до реализации (конституция VI)
- Backend: миграция/модель → репозиторий → сервис → контроллер/DTO
- Frontend: API-модуль → localStorage-модули → хуки → компоненты → интеграция
- История завершена checkpoint-проверкой (независимый тест) до перехода к следующему приоритету

### Parallel Opportunities

- T002 параллельно T001; T005/T006 параллельно T003 (после — T004)
- В US1: T007/T008/T009 параллельно; T011/T016/T017/T018/T020/T021/T022 параллельно (разные файлы)
- В US2: T024/T025/T028 параллельно; в US3: T030/T032/T033/T036 параллельно; в US4: T038/T039/T042 параллельно
- Разные истории — параллельно разными исполнителями после Phase 2 (US3 только после US1)

---

## Parallel Example: User Story 1

```bash
# Запустить вместе IT-тесты US1 (до реализации; разные файлы):
Task: "T007 DeliveryAckIT в backend/src/test/kotlin/webchat/backend/sync/DeliveryAckIT.kt"
Task: "T008 SyncIT в backend/src/test/kotlin/webchat/backend/sync/SyncIT.kt"
Task: "T009 AscendingHistoryIT в backend/src/test/kotlin/webchat/backend/chats/AscendingHistoryIT.kt"

# Запустить вместе независимые модули US1 (разные файлы):
Task: "T011 DTO sync в backend/src/main/kotlin/webchat/backend/sync/api/dto/"
Task: "T016 API-модуль в frontend/src/api/chats.ts"
Task: "T017 cursors.ts в frontend/src/sync/cursors.ts"
Task: "T018 ack.ts в frontend/src/sync/ack.ts"
Task: "T020 SyncIndicator в frontend/src/chats/components/SyncIndicator.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (T001–T002)
2. Complete Phase 2: Foundational (T003–T006) — CRITICAL, блокирует всё
3. Complete Phase 3: User Story 1 (T007–T023)
4. **STOP and VALIDATE**: quickstart §3.1–3.2 — разрыв/восстановление без потерь и дублей
5. Deploy/demo если готово

### Incremental Delivery

1. Setup + Foundational → контракт 0.5.0 + V12 + порты готовы
2. + US1 → тест независимо → MVP (ядро доверия: exactly-once при разрывах)
3. + US2 → тест независимо → оффлайн-отправка с очередью и вытеснением
4. + US3 → тест независимо → серверно-авторитарные непрочитанные + ✓✓
5. + US4 → тест независимо → контролируемая деградация + k6
6. Polish → конвейер/метрики/quickstart — фича закрыта

### Parallel Team Strategy

1. Команда вместе завершает Setup + Foundational
2. После Phase 2: Developer A → US1; Developer B → US2; Developer C → US4 (серверная часть)
3. US3 стартует после US1 (единственная межисторная зависимость)
4. Интеграция независимо, checkpoint каждой истории

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [P]-маркеры и блоки «Parallel» адресованы команде или параллельным сессиям; при реализации одним агентом (opencode) задачи выполняются строго последовательно в порядке файла (конституция «Development Workflow»)
- [Story] метка связывает задачу с историей для трассируемости (spec.md US1–US4)
- Задача без явного «приёмка:» считается принятой, когда зелёные тесты, на которые она
  ссылается (IT/frontend/quickstart), и пройден чекпоинт истории/фазы (конституция
  «Development Workflow»: приёмка задаётся ссылкой на тесты)
- Порядок в №16 (дедуп → admission → membership → валидация → флуд → блокировки → INSERT) — инвариант FR-009/FR-010, ассертится `BackpressureIT`
- Позиция доставки двигается ТОЛЬКО ack №25 — ни sync-ответ, ни SSE-кадр её не пишут (FR-001, ассертится `DeliveryAckIT`/`SyncIT`)
- Нагрузочная валидация: полнообъёмный прогон 100k msg/s × 10 мин — параметризован в T042, исполняется инфраструктурой 014 (plan.md Complexity Tracking)
- Каждая задача: реализация + зелёные тесты + линт/формат + `/speckit.checklist` (DoD конституции)
- Коммит после каждой задачи или логической группы; статусы отмечаются в этом файле
