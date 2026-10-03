# Tasks: Присутствие пользователей (статус «онлайн»)

**Input**: Design documents from `/specs/007-user-presence/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/presence-api.md, contracts/presence-events.md, quickstart.md

**Tests**: Включены — конституция VI (Test-First, NON-NEGOTIABLE) и quickstart.md требуют интеграционных/Vitest/k6-проверок; тесты пишутся до/вместе с реализацией и должны падать до неё.

**Organization**: Задачи сгруппированы по user stories (US1–US4 из spec.md) для независимой реализации и проверки каждой истории.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно разными исполнителями/агентами (разные файлы, нет зависимостей от незавершённых задач); один агент реализует задачи строго последовательно в порядке фаз — параллельная реализация несвязанных задач одним агентом запрещена (конституция, Development Workflow & Quality Gates)
- **[Story]**: Принадлежность к user story (US1, US2, US3, US4)
- В описаниях — точные пути к файлам

## Path Conventions

- Backend: `backend/src/main/kotlin/webchat/backend/presence/` (package-by-feature, конвенция 001–006)
- Frontend: `frontend/src/presence/` + точечная интеграция в `frontend/src/chats/components/`, `frontend/src/settings/`
- Контракт: корневой `contracts/openapi.yaml` (0.6.0 → 0.7.0, аддитивно)
- Миграции: `backend/src/main/resources/db/migration/` (следующая — V15)

---

## Phase 1: Setup (общая инфраструктура)

**Purpose**: Контракт-первым (конституция IV), durable-схема, конфигурация

- [x] T001 [P] Обновить `contracts/openapi.yaml` 0.6.0 → 0.7.0 аддитивно: пути №36 `GET /api/v1/users/me/presence?userIds=` (batch ≤ 200, `{items:[{userId,status:online|offline|unknown,rev}]}`), №37 `POST /api/v1/users/me/presence/heartbeat` (`{connectionId}` → 204), №38 `GET|PUT /api/v1/users/me/presence/settings` (`{incognito}`); SSE-кадры №18 `connected {connectionId}` и `presence.updated {userId,status,rev}`; problem-коды RFC 9457 `presence_ids_too_many` (400), `presence_connection_not_found` (404), `flood_limit` (429, переиспользование); в описании №18/№37 развести термины: transport-keepalive `:ka` (существующий кадр-комментарий каждые 15 с, удержание соединения) ≠ presence-heartbeat №37 (прикладное продление регистрации каждые 30 с) — Приёмка: `vacuum lint -e contracts/openapi.yaml` без ошибок; diff 0.6.0 → 0.7.0 строго аддитивный (новые пути №36–38, SSE-кадры `connected`/`presence.updated`, problem-коды `presence_ids_too_many`/`presence_connection_not_found`; без изменений существующих схем/путей); схемы и параметры совпадают с contracts/presence-api.md §1–4 и presence-events.md §1–3; терминология `:ka` ≠ №37 разведена в описаниях №18/№37; полная локальная валидация (lint + breaking-check + drift) — T037
- [X] T002 [P] Создать миграцию `backend/src/main/resources/db/migration/V15__presence_settings.sql`: `ALTER TABLE users ADD COLUMN presence_hidden BOOLEAN NOT NULL DEFAULT FALSE` (data-model §2) — Приёмка: Flyway применяет V15 в контексте `AbstractIntegrationTest` (запись в `flyway_schema_history`; проверяется первым поднятием PresenceIT — T011)
- [X] T003 [P] Добавить свойства `presence.*` в `backend/src/main/resources/application.yml` (ttl=90s, hysteresis=45s, heartbeat-interval=30s, poller-interval=1s, snapshot-batch-limit=200 — research §G) и ужать окна в тест-профиле `backend/src/test/resources/application-test.yml` (TTL→3s, гистерезис→2s, heartbeat-interval→1s, poller-interval→200ms — чтобы бюджет SC-003 не флакал из-за slack поллеров, а регистрации не истекали посреди сценариев длиннее TTL) — Приёмка: дефолты совпадают с contracts/presence-api.md §2 и presence-events.md §3; ужатые окна читаются тестами T011/T023/T026

---

## Phase 2: Foundational (блокирующие предпосылки)

**Purpose**: Порты домена, Redis-адаптер, SQL-аудитория, метрики — до любых user stories

**⚠️ CRITICAL**: Ни одна user story не может начаться до завершения фазы

- [X] T004 [P] Определить порт `PresenceStore` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/PresenceStore.kt`: register/renew/unregister/clearSessionRegistrations(userId, sessionId), hasAliveRegistration, чтение pub/rev, offq-операции (schedule/cancel/dueBatch), watch-обслуживание, snapshot-batch чтение (атомарные операции, data-model §3) — Приёмка: компиляция; контракты операций соответствуют data-model §3 и верифицируются зелёными T011/T023/T026 через реализации T008/T014/T024/T025/T028
- [X] T005 [P] Определить порт `VisibilityAudienceReader` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/VisibilityAudienceReader.kt`: аудитория видимости пользователя + проверка per-pair доступа (data-model §1.4) — Приёмка: компиляция; контракт соответствует data-model §1.4 и верифицируется адресацией T030 через реализацию T009/T016/T017
- [X] T006 [P] Определить порт `PresenceEventPublisher` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/PresenceEventPublisher.kt`: публикация `presence.updated {userId,status,rev}` в `rt:user:{observerId}` (расширение RealtimeEventPublisher, DIP) — Приёмка: компиляция; кадр по contracts/presence-events.md §2 — верифицируется сценарием публикации в T011 через реализацию T017
- [X] T007 [P] Сгенерировать фронтенд-типы `pnpm --dir frontend generate:api` (openapi-typescript из обновлённого контракта T001; типы presence в `frontend/src/api/`) — Приёмка: генерация проходит; повторный прогон не меняет `schema.d.ts` (drift-check T037 зелёный)
- [X] T008 Реализовать `RedisPresenceStore` в `backend/src/main/kotlin/webchat/backend/presence/repository/RedisPresenceStore.kt`: ZSET `presence:alive:{userId}` (member=connectionId, score=expiresAt, ленивая чистка ZREMRANGEBYSCORE), `presence:watch`, `presence:offq`, String `presence:pub:{userId}` / `presence:rev:{userId}` / `presence:online:count`; Lua-скрипты атомарных переходов (CAS pub + INCR rev + счётчик + offq-мутации; при nil rev инициализируется меткой now_ms — монотонность между «эпохами»; TTL pub продлевается в renew-Lua), продление alive+watch одним Lua (watch здесь — только renew-ветка; register/unregister-ветки watch — T027/US3; зависит от T004; data-model §3) — Приёмка: Lua-CAS атомарность переходов — сценарии T011 (мультидевайс, rev-монотонность) зелёные после T014
- [X] T009 Реализовать `JdbcVisibilityAudienceReader` в `backend/src/main/kotlin/webchat/backend/presence/repository/JdbcVisibilityAudienceReader.kt`: SQL `chat_participants state='active' (direct+group) ∪ user_contacts − user_blocks (обе стороны) − сам X` по индексам V10/V11/V14 (зависит от T005; research C1) — Приёмка: адресация T030 — посторонний/блок-пара получает `unknown` и ноль событий
- [X] T010 [P] Создать `PresenceMetrics` в `backend/src/main/kotlin/webchat/backend/presence/PresenceMetrics.kt`: counter `webchat_presence_events_published_total{status}`, counter `webchat_presence_hysteresis_suppressed_total`, gauge `webchat_presence_online_users` (экспорт Redis-счётчика), переиспользование `webchat_realtime_push_seconds{event=presence.updated}` (FR-009, research F) — Приёмка: компиляция; состав и имена метрик соответствуют presence-events.md §5 (`webchat_presence_online_users`, `webchat_presence_events_published_total{status}`, `webchat_presence_hysteresis_suppressed_total`, `webchat_realtime_push_seconds{event=presence.updated}`); инкременты/экспорт подключаются в переходах T014/T025 и публикации T017; сквозная проверка присутствия всех 4 метрик в `/actuator/prometheus` после сценария переключения — T036 (по образцу GroupObservabilityIT)

**Checkpoint**: Фундамент готов — реализация user stories может начинаться параллельно

---

## Phase 3: User Story 1 — Индикатор «онлайн» с realtime-обновлением (Priority: P1) 🎯 MVP

**Goal**: «онлайн» ⟺ ≥1 живой регистрации (мультидевайс); события `presence.updated` аудитории видимости; снапшот №36 отображаемых поверхностей; индикатор с a11y-подписью в трёх точках UI

**Independent Test**: Два пользователя с общим чатом: второй подключается — у первого индикатор «онлайн» без перезагрузки; закрывает все устройства — гаснет; мультидевайс ≥1 живого — «онлайн»; переподключение наблюдателя — актуальный снимок; дубль кадра — без искажений

### Tests for User Story 1 (писать первыми, до реализации — красные)

- [X] T011 [P] [US1] Написать `PresenceIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceIT.kt` (на `AbstractIntegrationTest`, Testcontainers PG+Redis, тест-профиль окна): SSE-open → фрейм `connected` + публикация «онлайн» аудитории ≤ 2 c (SC-001); мультидевайс — закрытие одного из двух подключений оставляет «онлайн» (SC-005); регистрации продлеваются тестом явно (POST №37 с интервалом тест-профиля 1 c), чтобы TTL 3 c не истекал посреди сценария; снапшот №36 на (re)connect отражает опубликованный статус и rev (SC-006); в окне гистерезиса (pending-offline) снапшот возвращает `online` — согласован с событиями (FR-004); повторно доставленный кадр не искажает состояние (идемпотентность — FR-003); потеря кадра (имитация пропуска presence.updated подписчику) сходится рефетчем снапшота №36 по max(rev) — тест пути доставки на потерю (конституция III); 429 на heartbeat (flood-бакет) не рвёт соединение — повтор в следующем интервале, регистрация не истекает
- [X] T012 [P] [US1] Написать Vitest-тесты в `frontend/src/presence/__tests__/`: слияние по строго возрастающему `rev` per-user (дубль/устаревший — no-op); нейтральный индикатор «неизвестно» до первого снапшота (ложный «офлайн» недопустим); доступная подпись/aria-метка «онлайн»/«офлайн»/нейтрально; пропущенное событие восполняется снапшотом со строго большим rev (потеря кадра → сходимость, конституция III); догрузка новой поверхности: userId без статуса в store (элемент списка «Чаты», контакт, открытый чат) → появление/открытие поверхности триггерит №36 для недостающих, статус сходится

### Implementation for User Story 1

- [X] T013 [US1] Добавить opening-фрейм `connected {connectionId}` (сразу после `retry: 3000`) и хуки жизненного цикла регистрации в реалтайм-канале №18: генерация connectionId при SSE-open, колбэк на close/error → `PresenceService` (интеграция в `backend/src/main/kotlin/webchat/backend/realtime/` + вызов presence; contracts/presence-events.md §1) — Приёмка: T011 — SSE-open → `connected {connectionId}` ≤ 2 c; close/error → unregister
- [X] T014 [US1] Реализовать `PresenceService` в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt`: register/unregister/renew через `PresenceStore` (регистрация несёт sessionId из access-токена — data-model §1.1, per-session logout); переход offline→online — немедленный CAS, rev++, отмена offq, публикация аудитории (дебаунс-online = 0; data-model §1.2; зависит от T008, T013) — Приёмка: T011 — «онлайн» аудитории ≤ 2 c (SC-001); мультидевайс ≥1 живого (SC-005)
- [X] T015 [US1] Реализовать эндпоинт №37 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceController.kt`: `POST /api/v1/users/me/presence/heartbeat` — атомарное продление alive+watch до now+90 c (Lua); 404 `presence_connection_not_found` для чужого/истёкшего connectionId; per-user бакет `flood_limit` (429 + Retry-After) — Приёмка: T011 — 404 `presence_connection_not_found` на чужой/истёкший connectionId; 429 `flood_limit` не рвёт SSE-соединение (повтор в следующем интервале); продление атомарно (Lua alive+watch)
- [X] T016 [US1] Реализовать эндпоинт №36 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceController.kt`: `GET /api/v1/users/me/presence?userIds=` — batch ≤ 200 с дедупом; политику per-pair через `VisibilityAudienceReader`: аудитория → опубликованный статус (`presence:pub`, гистерезис-согласован с событиями FR-004) + rev, вне аудитории/несуществующий → `unknown`; 400 `presence_ids_too_many`/`malformed_request`; per-user бакет `flood_limit` (429 + Retry-After, как №26) (contracts/presence-api.md §1) — Приёмка: T011 — снапшот отражает опубликованный статус и rev (SC-006), в окне pending-offline возвращает `online` (FR-004); T030 — вне аудитории/блок-пара/несуществующий userId → `unknown`
- [X] T017 [US1] Реализовать публикацию `presence.updated` аудитории видимости: адаптер `PresenceEventPublisher` поверх существующего RedisRealtimePublisher (`rt:user:{observerId}`, один фанаут на наблюдателя независимо от числа общих чатов), инкремент `webchat_presence_events_published_total` (зависит от T009, T010; contracts/presence-events.md §2) — Приёмка: T030 — наблюдатель с direct- и group-чатом получает ровно одно событие на переход
- [X] T018 [P] [US1] Создать `frontend/src/presence/presenceStore.ts`: `Map<userId,{status,rev}>`, правило слияния — применять только строго большее `rev` per-user (research C2) — Приёмка: Vitest T012 — слияние по строго возрастающему rev (дубль/устаревший — no-op) зелёный
- [X] T019 [P] [US1] Создать `frontend/src/presence/presenceApi.ts`: клиент №36 (batch-снапшот) и №37 + heartbeat-планировщик (интервал 30 c от connectionId из фрейма `connected`; на 404 — немедленный reconnect SSE, на 429 — сохранить соединение и повторить в следующем интервале, TTL 90 c — 3× запас; research E2) — Приёмка: поведение 404/429 по presence-api.md §2 (404 → reconnect, 429 → повтор в следующем интервале) покрыто тестами клиента
- [X] T020 [US1] Создать `frontend/src/presence/usePresence.ts`: снапшот отображаемых поверхностей (открытые/отображаемые чаты — включая отображаемые элементы списка «Чаты», — видимый список «Контакты») в цикле (re)connect 005 после sync/chats/contacts + догрузка при открытии новой поверхности (открытие чата / появление видимого контакта или элемента списка «Чаты» без статуса в store → №36 только для недостающих userIds, при >200 — чанкинг batch'ами ≤200 — contracts/presence-api.md §1 «Вызовы»/«Чанкинг») + подписка `presence.updated` со слиянием в store (зависит от T018, T019) — Приёмка: T012 — снапшот поверхностей + слияние событий, восполнение пропущенного кадра; догрузка нового собеседника: «неизвестно» сменяется актуальным статусом без reconnect
- [X] T021 [P] [US1] Создать `frontend/src/presence/PresenceIndicator.tsx`: точка + единообразная доступная подпись на всех трёх поверхностях: всегда aria-метка («онлайн»/«офлайн»/«неизвестно»), видимая текстовая метка — только в заголовке 1:1-чата (T022); `online` (зелёная), `offline` (серая), `unknown` (нейтрально-серая) — до первого снапшота и для «нет доступа» (FR-006, clarify a11y) — Приёмка: T012 — нейтральное «неизвестно» до снимка, aria-метка трёх состояний
- [X] T022 [US1] Интегрировать индикатор в три поверхности: `frontend/src/chats/components/ChatListItem.tsx` (только личные чаты), заголовок окна 1:1-чата (`DirectChatHeader` в `frontend/src/chats/pages/MessengerPage.tsx`), элементы списка «Контакты» (`frontend/src/chats/components/ContactList.tsx`, панель 004 US5); в групповых чатах UI присутствия отсутствует (зависит от T020, T021) — Приёмка: QS-1 руками — индикатор в списке «Чаты», заголовке 1:1 и «Контактах»; видимая текстовая метка статуса — только в заголовке 1:1-чата; в группах отсутствует

**Checkpoint**: US1 полностью функционален и независимо проверяем (PresenceIT + Vitest зелёные, QS-1 руками)

---

## Phase 4: User Story 2 — Устойчивость к флапу: дебаунс и гистерезис (Priority: P2)

**Goal**: Серия разрывов < 45 c → 0 наблюдаемых переключений; разрыв > окна → ровно одно «офлайн» и один возврат; «онлайн» публикуется немедленно

**Independent Test**: Автотест «метро»: ≥10 разрывов/восстановлений короче окна — 0 смен индикатора; единичный длинный разрыв — ровно один переход и возврат

### Tests for User Story 2 (писать первыми)

- [X] T023 [P] [US2] Написать `PresenceHysteresisIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceHysteresisIT.kt`: метро-серия ≥ 10 разрывов/восстановлений короче окна → 0 публикаций смен (SC-002); единичный разрыв длиннее окна → ровно одно «offline» (CAS исключает ≥2) и один возврат; разрыв на границе окна → 0 или 1 переключение; чередование устройств в окне → без промежуточного «офлайн»

### Implementation for User Story 2

- [X] T024 [US2] Реализовать гистерезис-планирование в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt` + `RedisPresenceStore.kt`: потеря последней регистрации (SSE close / закрытие устройства; ИСКЛЮЧЕНИЕ — logout-исключение FR-004: немедленный «офлайн» без окна, реализация — T029) НИКОГДА не публикует сразу — ZADD `presence:offq` с score=now+гистерезис (data-model §1.2, research B1) — Приёмка: T023 — метро-серия → 0 публикаций
- [X] T025 [US2] Реализовать offq-поллер в `backend/src/main/kotlin/webchat/backend/presence/scheduler/PresenceTransitionScheduler.kt`: fixed-delay 1 c, батчево ZRANGEBYSCORE; на исполнении — есть живая регистрация → отмена + `hysteresis_suppressed_total`++; нет → атомарный CAS online→offline + rev++ + публикация аудитории (идемпотентность конкуренции инстансов — CAS) — Приёмка: T023 — длинный разрыв → ровно одно `offline`; подавления растят `webchat_presence_hysteresis_suppressed_total`

**Checkpoint**: US2 независимо проверяем (PresenceHysteresisIT зелёный, QS-2 руками)

---

## Phase 5: User Story 3 — Самоистечение при сбоях, stateless-инстансы (Priority: P2)

**Goal**: Регистрации самоистекают без явного disconnect: «офлайн» гарантирован ≤ TTL (90 c) + гистерезис (45 c) + 2 интервала поллера (SC-003; канон — T026); подключения на разных инстансах — единый статус; logout — немедленный «офлайн»

**Independent Test**: Автотест «крэш»: heartbeat прекращается без отключения — «офлайн» у всех наблюдателей ≤ TTL+гистерезис+2×poller-interval (SC-003); kill/restart инстанса не искажает статусы других

### Tests for User Story 3 (писать первыми)

- [X] T026 [P] [US3] Написать `PresenceExpiryIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceExpiryIT.kt`: тишина heartbeat без явного disconnect → «офлайн» ≤ TTL+гистерезис+2×poller-interval в 100% случаев (SC-003, slack поллеров); регистрации одного пользователя на двух инстансах — единый статус, падение одного инстанса не «дребезжит»; шторм-сценарий (US3 AC4): одновременное истечение регистраций ≥10 пользователей (имитация краха инстанса) с возвратом ≥ половины в окне гистерезиса → 0 ложных «офлайн» у вернувшихся, счётчик `hysteresis_suppressed_total` растёт, публикации «офлайн» — только для невозвратившихся; осиротевшая регистрация реконнекта самоистекает, дубль не искажает статус (edges); мультидевайс-logout: logout одной сессии при живой второй → статус «онлайн», logout последней → немедленный «офлайн» без окна (edge); после истечения rev-TTL новая ревизия строго больше прежней (эпоха now_ms) — слияние не ломается

### Implementation for User Story 3

- [X] T027 [US3] Обслуживать `presence:watch` в `backend/src/main/kotlin/webchat/backend/presence/repository/RedisPresenceStore.kt`: score=max(expiresAt) регистраций пользователя в register/unregister-ветках Lua (renew-ветка — уже в T008; research A2) — Приёмка: T026 — тихое истечение ≤ бюджета SC-003
- [X] T028 [US3] Реализовать watch-поллер тихих истечений в `backend/src/main/kotlin/webchat/backend/presence/scheduler/PresenceTransitionScheduler.kt`: fixed-delay 1 c, батчево ZRANGEBYSCORE −inf..now, вычистка истёкших members из `presence:alive:{userId}`, пересчёт → при пустоте offq (ленивая + активная чистка, FR-002) — Приёмка: T026 — истёкшие регистрации вычищаются из `presence:alive:{userId}`, пересчёт направляет в offq
- [X] T029 [US3] Интегрировать logout в существующий флоу `backend/src/main/kotlin/webchat/backend/auth/`: очистка регистраций только logout-сессии (registration.sessionId из sid токена; 002 отзывает одну сессию — clearSessionRegistrations); при потере последней регистрации — немедленный CAS→offline с публикацией (обход offq), при живых других — статус не меняется (edge «мультидевайс-logout»; research B2) — Приёмка: T026 — logout последней сессии → немедленный «офлайн»; при живой второй — «онлайн»

**Checkpoint**: US3 независимо проверяем (PresenceExpiryIT зелёный, QS-3 руками; валидация требует offq-поллера T025 из US2 — см. Dependencies)

---

## Phase 6: User Story 4 — Приватность видимости и режим «невидимки» (Priority: P3)

**Goal**: События и статус — только аудитории видимости (общий чат ∪ контакты − блок-пары); «невидимка» неотличима от офлайна, действует per-user, переживает сессии, ≤ одного переключения

**Independent Test**: Посторонний и блок-пара не получают событий, №36 → `unknown`; «невидимка» виден окружающим как «офлайн», сам видит статусы; режим сохраняется после перелогина

### Tests for User Story 4 (писать первыми)

- [X] T030 [P] [US4] Написать `PresencePrivacyIT` в `backend/src/test/kotlin/webchat/backend/presence/PresencePrivacyIT.kt`: посторонний (нет чата/контактов) и блок-пара не получают ни одного `presence.updated`, №36 отвечает `unknown` (SC-004); в общем групповом чате остальные участники статусы друг друга видят; «нет доступа» не различает «офлайн»/«скрыто»; наблюдатель с общим direct- и group-чатом получает ровно один `presence.updated` на переход (один фанаут на наблюдателя — edge «до 200 участников»); сходимость аудитории: после исключения из группы/удаления чата событие не доставляется (окно сходимости ≤ 5 с — как 006 FR-010), после добавления в контакты — доставляется без перезапуска клиента (edges)
- [X] T031 [P] [US4] Написать `PresenceInvisibleIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceInvisibleIT.kt`: включение при живых подключениях → ровно одно переключение в `offline` + заморозка событий; выключение онлайн-пользователя → публикация `online`; выключение офлайн-пользователя (без живых подключений) → ни одного события, статусная часть снапшота №36 неизменна, rev не убывает (edge); снапшот цели в режиме → `offline` неотличимо; сам «невидимка» читает №36 без ограничений; режим сохраняется после перелогина (PG V15); переключение режима ≤ одной смены индикатора

### Implementation for User Story 4

- [X] T032 [US4] Реализовать эндпоинт №38 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceSettingsController.kt`: `GET/PUT /api/v1/users/me/presence/settings` `{incognito}` — чтение/запись `users.presence_hidden` (V15), идемпотентный PUT (повтор того же значения — no-op без событий), 400 `malformed_request`; per-user `flood_limit` на PUT (429 — contracts/presence-api.md §3) — Приёмка: T031 — идемпотентный PUT (повтор — no-op без событий), 400 `malformed_request`, 429 `flood_limit`
- [X] T033 [US4] Реализовать семантику «невидимки» в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt`: включение → немедленный CAS→offline (rev++, одно переключение, обход offq) + заморозка исходящих событий; выключение → публикация фактического статуса (rev++; если фактический статус совпадает с публикуемым — только rev++ без события, edge «выключение без живых подключений»); снапшот №36 для цели в режиме → `offline` (неотличимость, FR-007; research D1/D2; зависит от T032) — Приёмка: T031 — одно переключение, заморозка событий, неотличимость снапшота, сохранение режима
- [X] T034 [US4] Добавить переключатель «невидимки» в UI профиля/настроек — новая страница `frontend/src/settings/pages/PresenceSettingsPage.tsx` (по образцу `SecurityPage.tsx`, с регистрацией в роутинге настроек) (через №38 и сгенерированные типы T007; единообразие SPA/виджет — shared-модули presence, конституция IV; поверхность виджета — фича 015, в 007 не реализуется); нейтральный индикатор «неизвестно» для контакта без доступа — владелец поведения T021/T022 (№36 `unknown`), здесь не реализуется — Приёмка: QS-4, шаг 2 руками — переключатель №38 сохраняет режим (переживает перелогин)

**Checkpoint**: US4 независимо проверяем (PresencePrivacyIT + PresenceInvisibleIT зелёные, QS-4 руками)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Нагрузочный smoke, наблюдаемость, валидация контракта, сквозная проверка

- [X] T035 [P] Создать k6-сценарий `load/k6/presence.smoke.js` (конвенция `load/k6/*.js`): подключения + heartbeat (30 c) + переключения + batch-снапшоты №36; p95-пороги присутствия: доставка `presence.updated` до наблюдателя ≤ 2 c (SC-001), снапшот №36 ≤ 2 c (SC-006); отсутствие деградации delivery-метрик messaging (Δp99 `webchat_realtime_push_seconds` ≤ 10% к базовому прогону `load/k6/messaging.smoke.js` без presence — SC-007; полные пики — фича 016) — Приёмка: `k6 run load/k6/presence.smoke.js` зелёный: p95 доставки `presence.updated` ≤ 2 c, p95 снапшота №36 ≤ 2 c, Δp99 `webchat_realtime_push_seconds` ≤ 10% к базовому прогону `messaging.smoke.js`
- [X] T036 [P] Проверить наблюдаемость (SC-008): интеграционный тест/проверка `/actuator/prometheus` — `webchat_presence_online_users`, `webchat_presence_events_published_total{status}`, `webchat_presence_hysteresis_suppressed_total`, `webchat_realtime_push_seconds{event=presence.updated}` (в `backend/src/test/kotlin/webchat/backend/presence/`) — Приёмка: интеграционный тест зелёный — все 4 метрики присутствуют в `/actuator/prometheus` после сценария переключения и подавленного гистерезисом разрыва (по образцу GroupObservabilityIT)
- [X] T037 Создать `scripts/validate-contracts.sh` — локальную обёртку contract-job CI (`.github/workflows/ci.yml:157-210`: `vacuum lint -e contracts/openapi.yaml`; регенерация TS-типов `pnpm --dir frontend generate:api` + drift-check `git diff --exit-code -- frontend/src/api/schema.d.ts`; `oasdiff breaking --fail-on ERR` против `origin/main`, зеркаля условную логику ci.yml:157-210: `--err-ignore contracts/oasdiff-err-ignore.txt` добавляется только при наличии файла, при существовании `contracts/BREAKING.md` breaking-check пропускается (маркер намеренных breaking-изменений — в 007 его нет)) — и запустить его: lint + drift + breaking-check 0.6.0 → 0.7.0 — только аддитивные изменения (конституция IV)
- [X] T038 Выполнить сквозную ручную валидацию по `specs/007-user-presence/quickstart.md`: QS-1 (индикатор/realtime), QS-2 (метро), QS-3 (самоистечение), QS-4 (приватность/невидимка) + автоматизированный блок (workdir `backend/`: `./gradlew test --tests 'webchat.backend.presence.*'`, `pnpm --dir frontend test`)
- [ ] T039 [P] Финальный lint/форматирование: backend (ktlint/detekt через Gradle check) и frontend (`pnpm --dir frontend lint`), очистка кода — Приёмка: (workdir `backend/`) `./gradlew check` и `pnpm --dir frontend lint` зелёные; незакоммиченного диффа форматирования не остаётся (контроль — финальный прогон T038)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Без зависимостей — старт немедленно (T001 контракт первым: T007 зависит от него)
- **Foundational (Phase 2)**: Зависит от Phase 1 — БЛОКИРУЕТ все user stories
- **User Stories (Phases 3–6)**: Все зависят от Phase 2; US2 и US3 строятся на механизмах US1 (PresenceService/scheduler из T014), поэтому при последовательной реализации одним агентом: US1 → US2 → US3; US4 зависит от T016 (снапшот) и T017 (публикация)
- **Polish (Phase 7)**: Зависит от завершения всех реализуемых user stories

### User Story Dependencies

- **US1 (P1)**: После Phase 2; фундамент сигнала для US2/US3/US4
- **US2 (P2)**: После US1 (offq-планирование встроено в переходы PresenceService)
- **US3 (P2)**: После US1. Реализация T027–T029 — параллельно с US2 разными командами, НО чекпоинт US3 (T026/SC-003) требует offq-конвейера US2: watch-поллер T028 направляет истёкших в offq, публикацию «офлайн» выполняет offq-поллер T025 (планирование — T024) — PresenceExpiryIT зелёный только после T024+T025
- **US4 (P3)**: После US1 (снапшот №36/публикация); не зависит от US2/US3

### Within Each User Story

- Тесты пишутся первыми и красные до реализации (конституция VI)
- Порты/модели → сервисы → эндпоинты → интеграция UI
- История завершена чекпоинтом: независимая проверка зелёная

### Parallel Opportunities

- Phase 1: T001, T002, T003 — параллельно (разные файлы)
- Phase 2: T004–T007, T010 — параллельно; T008 — после T004, T009 — после T005 (далее параллельно остальным разными исполнителями); фронтенд-кодогенерация T007 параллельно бэкенд-адаптерам
- US1: backend (T013–T017) и frontend (T018–T022) — параллельно разными исполнителями; внутри frontend T018/T019/T021 параллельны
- US4: T030/T031 (разные тест-файлы) параллельны
- Phase 7: T035/T036/T039 параллельны

---

## Parallel Example: User Story 1

```bash
# Тесты US1 параллельно (разные файлы):
Task: "T011 [P] [US1] PresenceIT в backend/src/test/kotlin/webchat/backend/presence/PresenceIT.kt"
Task: "T012 [P] [US1] Vitest rev-merge/нейтральный индикатор в frontend/src/presence/__tests__/"

# Backend и frontend US1 параллельно (разные исполнители):
Task: "T013–T017 backend: realtime-хуки, PresenceService, контроллеры №36/№37, публикация"
Task: "T018 [P] / T019 [P] / T021 [P] frontend: presenceStore.ts, presenceApi.ts, PresenceIndicator.tsx"
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (контракт 0.7.0, V15, конфиг)
2. Complete Phase 2: Foundational (порты, RedisPresenceStore, аудитория, метрики)
3. Complete Phase 3: User Story 1
4. **STOP and VALIDATE**: PresenceIT + Vitest + QS-1 quickstart независимо
5. Deploy/demo — видимый realtime-индикатор работает

### Incremental Delivery

1. Setup + Foundational → фундамент готов
2. + US1 → независимая проверка → Demo (MVP!)
3. + US2 → метро-сценарий зелёный → доверие к сигналу
4. + US3 → self-expiry/stateless → отказоустойчивость
5. + US4 → приватность/невидимка → полная фича
6. Polish: k6 smoke + метрики + контракт-валидация + quickstart

### Parallel Team Strategy

1. Команда вместе завершает Setup + Foundational
2. После Phase 2: Разработчик A — US1 backend; Разработчик B — US1 frontend
3. Далее: US2 и US3 параллельно (offq- и watch-поллеры — разные задачи одного scheduler-файла, координировать); US4 после US1
4. Histories интегрируются независимо, чекпоинты каждой — зелёные тесты

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач; параллелизм — только между исполнителями, один агент идёт последовательно (конституция, Development Workflow)
- Терминология: «снапшот» ≡ «снимок» (spec.md) — один и тот же batch-запрос №36
- [Story]-метка связывает задачу с user story для трассировки
- Все числовые параметры (TTL 90 c, heartbeat 30 c, гистерезис 45 c, немедленная публикация «онлайн») — из contracts/presence-api.md §2, contracts/presence-events.md §3 (каноническая фиксация FR-004) и research §G; тест-профиль ужимает окна
- Тесты красные до реализации (VI); Definition of Done: реализация + зелёные тесты + lint/форматирование + обновлённый API-контракт (если затронут) + пройденный `/speckit.checklist`
- Коммит после каждой задачи/логической группы; стоп на чекпоинтах историй для независимой валидации
- Избегать: vague-задач, конфликтов одного файла, межсторных зависимостей, ломающих независимость
