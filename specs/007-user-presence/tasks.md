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

- [ ] T001 [P] Обновить `contracts/openapi.yaml` 0.6.0 → 0.7.0 аддитивно: пути №36 `GET /api/v1/users/me/presence?userIds=` (batch ≤ 200, `{items:[{userId,status:online|offline|unknown,rev}]}`), №37 `POST /api/v1/users/me/presence/heartbeat` (`{connectionId}` → 204), №38 `GET|PUT /api/v1/users/me/presence/settings` (`{incognito}`); SSE-кадры №18 `connected {connectionId}` и `presence.updated {userId,status,rev}`; problem-коды RFC 9457 `presence_ids_too_many` (400), `presence_connection_not_found` (404), `flood_limit` (429, переиспользование)
- [ ] T002 [P] Создать миграцию `backend/src/main/resources/db/migration/V15__presence_settings.sql`: `ALTER TABLE users ADD COLUMN presence_hidden BOOLEAN NOT NULL DEFAULT FALSE` (data-model §2)
- [ ] T003 [P] Добавить свойства `presence.*` в `backend/src/main/resources/application.yml` (ttl=90s, hysteresis=45s, heartbeat-interval=30s, poller-interval=1s, snapshot-batch-limit=200 — research §G) и ужать окна в тест-профиле `backend/src/test/resources/application-test.yml` (TTL→3s, гистерезис→2s)

---

## Phase 2: Foundational (блокирующие предпосылки)

**Purpose**: Порты домена, Redis-адаптер, SQL-аудитория, метрики — до любых user stories

**⚠️ CRITICAL**: Ни одна user story не может начаться до завершения фазы

- [ ] T004 [P] Определить порт `PresenceStore` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/PresenceStore.kt`: register/renew/unregister/clearSessionRegistrations(userId, sessionId), hasAliveRegistration, чтение pub/rev, offq-операции (schedule/cancel/dueBatch), watch-обслуживание, snapshot-batch чтение (атомарные операции, data-model §3)
- [ ] T005 [P] Определить порт `VisibilityAudienceReader` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/VisibilityAudienceReader.kt`: аудитория видимости пользователя + проверка per-pair доступа (data-model §1.4)
- [ ] T006 [P] Определить порт `PresenceEventPublisher` в `backend/src/main/kotlin/webchat/backend/presence/domain/port/PresenceEventPublisher.kt`: публикация `presence.updated {userId,status,rev}` в `rt:user:{observerId}` (расширение RealtimeEventPublisher, DIP)
- [ ] T007 [P] Сгенерировать фронтенд-типы `pnpm --dir frontend generate:api` (openapi-typescript из обновлённого контракта T001; типы presence в `frontend/src/api/`)
- [ ] T008 Реализовать `RedisPresenceStore` в `backend/src/main/kotlin/webchat/backend/presence/repository/RedisPresenceStore.kt`: ZSET `presence:alive:{userId}` (member=connectionId, score=expiresAt, ленивая чистка ZREMRANGEBYSCORE), `presence:watch`, `presence:offq`, String `presence:pub:{userId}` / `presence:rev:{userId}` / `presence:online:count`; Lua-скрипты атомарных переходов (CAS pub + INCR rev + счётчик + offq-мутации; при nil rev инициализируется меткой now_ms — монотонность между «эпохами»; TTL pub продлевается в renew-Lua), продление alive+watch одним Lua (зависит от T004; data-model §3)
- [ ] T009 Реализовать `JdbcVisibilityAudienceReader` в `backend/src/main/kotlin/webchat/backend/presence/repository/JdbcVisibilityAudienceReader.kt`: SQL `chat_participants state='active' (direct+group) ∪ user_contacts − user_blocks (обе стороны) − сам X` по индексам V10/V11/V14 (зависит от T005; research C1)
- [ ] T010 [P] Создать `PresenceMetrics` в `backend/src/main/kotlin/webchat/backend/presence/PresenceMetrics.kt`: counter `webchat_presence_events_published_total{status}`, counter `webchat_presence_hysteresis_suppressed_total`, gauge `webchat_presence_online_users` (экспорт Redis-счётчика), переиспользование `webchat_realtime_push_seconds{event=presence.updated}` (FR-009, research F)

**Checkpoint**: Фундамент готов — реализация user stories может начинаться параллельно

---

## Phase 3: User Story 1 — Индикатор «онлайн» с realtime-обновлением (Priority: P1) 🎯 MVP

**Goal**: «онлайн» ⟺ ≥1 живой регистрации (мультидевайс); события `presence.updated` аудитории видимости; снапшот №36 отображаемых поверхностей; индикатор с a11y-подписью в трёх точках UI

**Independent Test**: Два пользователя с общим чатом: второй подключается — у первого индикатор «онлайн» без перезагрузки; закрывает все устройства — гаснет; мультидевайс ≥1 живого — «онлайн»; переподключение наблюдателя — актуальный снимок; дубль кадра — без искажений

### Tests for User Story 1 (писать первыми, до реализации — красные)

- [ ] T011 [P] [US1] Написать `PresenceIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceIT.kt` (на `AbstractIntegrationTest`, Testcontainers PG+Redis, тест-профиль окна): SSE-open → фрейм `connected` + публикация «онлайн» аудитории ≤ 2 c (SC-001); мультидевайс — закрытие одного из двух подключений оставляет «онлайн» (SC-005); снапшот №36 на (re)connect отражает опубликованный статус и rev (SC-006); в окне гистерезиса (pending-offline) снапшот возвращает `online` — согласован с событиями (FR-004); повторно доставленный кадр не искажает состояние (FR-006 идемпотентность)
- [ ] T012 [P] [US1] Написать Vitest-тесты в `frontend/src/presence/__tests__/`: слияние по строго возрастающему `rev` per-user (дубль/устаревший — no-op); нейтральный индикатор «неизвестно» до первого снапшота (ложный «офлайн» недопустим); доступная подпись/aria-метка «онлайн»/«офлайн»/нейтрально

### Implementation for User Story 1

- [ ] T013 [US1] Добавить opening-фрейм `connected {connectionId}` (сразу после `retry: 3000`) и хуки жизненного цикла регистрации в реалтайм-канале №18: генерация connectionId при SSE-open, колбэк на close/error → `PresenceService` (интеграция в `backend/src/main/kotlin/webchat/backend/realtime/` + вызов presence; contracts/presence-events.md §1)
- [ ] T014 [US1] Реализовать `PresenceService` в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt`: register/unregister/renew через `PresenceStore` (регистрация несёт sessionId из access-токена — data-model §1.1, per-session logout); переход offline→online — немедленный CAS, rev++, отмена offq, публикация аудитории (дебаунс-online = 0; data-model §1.2; зависит от T008, T013)
- [ ] T015 [US1] Реализовать эндпоинт №37 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceController.kt`: `POST /api/v1/users/me/presence/heartbeat` — атомарное продление alive+watch до now+90 c (Lua); 404 `presence_connection_not_found` для чужого/истёкшего connectionId; per-user бакет `flood_limit` (429 + Retry-After)
- [ ] T016 [US1] Реализовать эндпоинт №36 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceController.kt`: `GET /api/v1/users/me/presence?userIds=` — batch ≤ 200 с дедупом; политику per-pair через `VisibilityAudienceReader`: аудитория → опубликованный статус (`presence:pub`, гистерезис-согласован с событиями FR-004) + rev, вне аудитории/несуществующий → `unknown`; 400 `presence_ids_too_many`/`malformed_request` (contracts/presence-api.md §1)
- [ ] T017 [US1] Реализовать публикацию `presence.updated` аудитории видимости: адаптер `PresenceEventPublisher` поверх существующего RedisRealtimePublisher (`rt:user:{observerId}`, один фанаут на наблюдателя независимо от числа общих чатов), инкремент `webchat_presence_events_published_total` (зависит от T009, T010; contracts/presence-events.md §2)
- [ ] T018 [P] [US1] Создать `frontend/src/presence/presenceStore.ts`: `Map<userId,{status,rev}>`, правило слияния — применять только строго большее `rev` per-user (research C2)
- [ ] T019 [P] [US1] Создать `frontend/src/presence/presenceApi.ts`: клиент №36 (batch-снапшот) и №37 + heartbeat-планировщик (интервал 30 c от connectionId из фрейма `connected`; на 404 — немедленный reconnect SSE; research E2)
- [ ] T020 [US1] Создать `frontend/src/presence/usePresence.ts`: снапшот отображаемых поверхностей (открытые чаты, видимый список «Контакты») в цикле (re)connect 005 после sync/chats/contacts + подписка `presence.updated` со слиянием в store (зависит от T018, T019)
- [ ] T021 [P] [US1] Создать `frontend/src/presence/PresenceIndicator.tsx`: точка + текстовая/aria-подпись; `online` (зелёная), `offline` (серая), `unknown` (нейтрально-серая/скрытая) — до первого снапшота и для «нет доступа» (FR-006, clarify a11y)
- [ ] T022 [US1] Интегрировать индикатор в три поверхности: `frontend/src/chats/components/ChatListItem.tsx` (только личные чаты), заголовок окна 1:1-чата (`DirectChatHeader` в `frontend/src/chats/pages/MessengerPage.tsx`), элементы списка «Контакты» (`frontend/src/chats/components/ContactList.tsx`, панель 004 US5); в групповых чатах UI присутствия отсутствует (зависит от T020, T021)

**Checkpoint**: US1 полностью функционален и независимо проверяем (PresenceIT + Vitest зелёные, QS-1 руками)

---

## Phase 4: User Story 2 — Устойчивость к флапу: дебаунс и гистерезис (Priority: P2)

**Goal**: Серия разрывов < 45 c → 0 наблюдаемых переключений; разрыв > окна → ровно одно «офлайн» и один возврат; «онлайн» публикуется немедленно

**Independent Test**: Автотест «метро»: ≥10 разрывов/восстановлений короче окна — 0 смен индикатора; единичный длинный разрыв — ровно один переход и возврат

### Tests for User Story 2 (писать первыми)

- [ ] T023 [P] [US2] Написать `PresenceHysteresisIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceHysteresisIT.kt`: метро-серия ≥ 10 разрывов/восстановлений короче окна → 0 публикаций смен (SC-002); единичный разрыв длиннее окна → ровно одно «offline» (CAS исключает ≥2) и один возврат; разрыв на границе окна → 0 или 1 переключение; чередование устройств в окне → без промежуточного «офлайн»

### Implementation for User Story 2

- [ ] T024 [US2] Реализовать гистерезис-планирование в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt` + `RedisPresenceStore.kt`: потеря последней регистрации (SSE close / явное закрытие) НИКОГДА не публикует сразу — ZADD `presence:offq` с score=now+гистерезис (data-model §1.2, research B1)
- [ ] T025 [US2] Реализовать offq-поллер в `backend/src/main/kotlin/webchat/backend/presence/scheduler/PresenceTransitionScheduler.kt`: fixed-delay 1 c, батчево ZRANGEBYSCORE; на исполнении — есть живая регистрация → отмена + `hysteresis_suppressed_total`++; нет → атомарный CAS online→offline + rev++ + публикация аудитории (идемпотентность конкуренции инстансов — CAS)

**Checkpoint**: US2 независимо проверяем (PresenceHysteresisIT зелёный, QS-2 руками)

---

## Phase 5: User Story 3 — Самоистечение при сбоях, stateless-инстансы (Priority: P2)

**Goal**: Регистрации самоистекают без явного disconnect: «офлайн» гарантирован ≤ TTL (90 c) + гистерезис (45 c); подключения на разных инстансах — единый статус; logout — немедленный «офлайн»

**Independent Test**: Автотест «крэш»: heartbeat прекращается без отключения — «офлайн» у всех наблюдателей ≤ TTL+гистерезис; kill/restart инстанса не искажает статусы других

### Tests for User Story 3 (писать первыми)

- [ ] T026 [P] [US3] Написать `PresenceExpiryIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceExpiryIT.kt`: тишина heartbeat без явного disconnect → «офлайн» ≤ TTL+гистерезис в 100% случаев (SC-003); регистрации одного пользователя на двух инстансах — единый статус, падение одного инстанса не «дребезжит»; осиротевшая регистрация реконнекта самоистекает, дубль не искажает статус (edges); мультидевайс-logout: logout одной сессии при живой второй → статус «онлайн», logout последней → немедленный «офлайн» без окна (edge); после истечения rev-TTL новая ревизия строго больше прежней (эпоха now_ms) — слияние не ломается

### Implementation for User Story 3

- [ ] T027 [US3] Обслуживать `presence:watch` в `backend/src/main/kotlin/webchat/backend/presence/repository/RedisPresenceStore.kt`: score=max(expiresAt) регистраций пользователя при каждом register/renew/unregister (research A2)
- [ ] T028 [US3] Реализовать watch-поллер тихих истечений в `backend/src/main/kotlin/webchat/backend/presence/scheduler/PresenceTransitionScheduler.kt`: fixed-delay 1 c, батчево ZRANGEBYSCORE −inf..now, вычистка истёкших members из `presence:alive:{userId}`, пересчёт → при пустоте offq (ленивая + активная чистка, FR-002)
- [ ] T029 [US3] Интегрировать logout в существующий флоу `backend/src/main/kotlin/webchat/backend/auth/`: очистка регистраций только logout-сессии (registration.sessionId из sid токена; 002 отзывает одну сессию — clearSessionRegistrations); при потере последней регистрации — немедленный CAS→offline с публикацией (обход offq), при живых других — статус не меняется (edge «мультидевайс-logout»; research B2)

**Checkpoint**: US3 независимо проверяем (PresenceExpiryIT зелёный, QS-3 руками)

---

## Phase 6: User Story 4 — Приватность видимости и режим «невидимки» (Priority: P3)

**Goal**: События и статус — только аудитории видимости (общий чат ∪ контакты − блок-пары); «невидимка» неотличима от офлайна, действует per-user, переживает сессии, ≤ одного переключения

**Independent Test**: Посторонний и блок-пара не получают событий, №36 → `unknown`; «невидимка» виден окружающим как «офлайн», сам видит статусы; режим сохраняется после перелогина

### Tests for User Story 4 (писать первыми)

- [ ] T030 [P] [US4] Написать `PresencePrivacyIT` в `backend/src/test/kotlin/webchat/backend/presence/PresencePrivacyIT.kt`: посторонний (нет чата/контактов) и блок-пара не получают ни одного `presence.updated`, №36 отвечает `unknown` (SC-004); в общем групповом чате остальные участники статусы друг друга видят; «нет доступа» не различает «офлайн»/«скрыто»; наблюдатель с общим direct- и group-чатом получает ровно один `presence.updated` на переход (один фанаут на наблюдателя — edge «до 200 участников»); сходимость аудитории: после исключения из группы/удаления чата событие не доставляется (короткое окно, как 006 FR-010), после добавления в контакты — доставляется без перезапуска клиента (edges)
- [ ] T031 [P] [US4] Написать `PresenceInvisibleIT` в `backend/src/test/kotlin/webchat/backend/presence/PresenceInvisibleIT.kt`: включение при живых подключениях → ровно одно переключение в `offline` + заморозка событий; выключение онлайн-пользователя → публикация `online`; снапшот цели в режиме → `offline` неотличимо; сам «невидимка» читает №36 без ограничений; режим сохраняется после перелогина (PG V15); переключение режима ≤ одной смены индикатора

### Implementation for User Story 4

- [ ] T032 [US4] Реализовать эндпоинт №38 в `backend/src/main/kotlin/webchat/backend/presence/api/PresenceSettingsController.kt`: `GET/PUT /api/v1/users/me/presence/settings` `{incognito}` — чтение/запись `users.presence_hidden` (V15), идемпотентный PUT (повтор того же значения — no-op без событий), 400 `malformed_request`
- [ ] T033 [US4] Реализовать семантику «невидимки» в `backend/src/main/kotlin/webchat/backend/presence/domain/PresenceService.kt`: включение → немедленный CAS→offline (rev++, одно переключение, обход offq) + заморозка исходящих событий; выключение → публикация фактического статуса (rev++); снапшот №36 для цели в режиме → `offline` (неотличимость, FR-007; research D1/D2; зависит от T032)
- [ ] T034 [US4] Добавить переключатель «невидимки» в UI профиля/настроек `frontend/src/settings/` (единообразно SPA и виджет, через №38 и сгенерированные типы T007); контакт без доступа (блок-пара) отображается нейтральным индикатором «неизвестно»

**Checkpoint**: US4 независимо проверяем (PresencePrivacyIT + PresenceInvisibleIT зелёные, QS-4 руками)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Нагрузочный smoke, наблюдаемость, валидация контракта, сквозная проверка

- [ ] T035 [P] Создать k6-сценарий `load/k6/presence.smoke.js` (конвенция `load/k6/*.js`): подключения + heartbeat (30 c) + переключения; отсутствие деградации delivery-метрик messaging (Δp99 push ≤ 10% к базовому прогону без presence — SC-007; полные пики — фича 016)
- [ ] T036 [P] Проверить наблюдаемость (SC-008): интеграционный тест/проверка `/actuator/prometheus` — `webchat_presence_online_users`, `webchat_presence_events_published_total{status}`, `webchat_presence_hysteresis_suppressed_total`, `webchat_realtime_push_seconds{event=presence.updated}` (в `backend/src/test/kotlin/webchat/backend/presence/`)
- [ ] T037 Создать `scripts/validate-contracts.sh` — локальную обёртку contract-job CI (`.github/workflows/ci.yml:157-210`: `vacuum lint -e contracts/openapi.yaml`; регенерация TS-типов `pnpm --dir frontend generate:api` + drift-check `git diff --exit-code -- frontend/src/api/schema.d.ts`; `oasdiff breaking --fail-on ERR` против `origin/main` с учётом `contracts/BREAKING.md` и `contracts/oasdiff-err-ignore.txt`) — и запустить его: lint + drift + breaking-check 0.6.0 → 0.7.0 — только аддитивные изменения (конституция IV)
- [ ] T038 Выполнить сквозную ручную валидацию по `specs/007-user-presence/quickstart.md`: QS-1 (индикатор/realtime), QS-2 (метро), QS-3 (самоистечение), QS-4 (приватность/невидимка) + автоматизированный блок (`./gradlew :backend:test --tests 'webchat.backend.presence.*'`, `pnpm --dir frontend test`)
- [ ] T039 [P] Финальный lint/форматирование: backend (Gradle check/spotless) и frontend (`pnpm lint`), очистка кода

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
- **US3 (P2)**: После US1 (watch-поллер направляет истёкших в offq US2; разными командами — параллельно с US2)
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
- [Story]-метка связывает задачу с user story для трассировки
- Все числовые параметры (TTL 90 c, heartbeat 30 c, гистерезис 45 c, немедленная публикация «онлайн») — из contracts/presence-api.md §2, contracts/presence-events.md §3 (каноническая фиксация FR-004) и research §G; тест-профиль ужимает окна
- Тесты красные до реализации (VI); Definition of Done: реализация + зелёные тесты + lint + контракт (если затронут)
- Коммит после каждой задачи/логической группы; стоп на чекпоинтах историй для независимой валидации
- Избегать: vague-задач, конфликтов одного файла, межсторных зависимостей, ломающих независимость
