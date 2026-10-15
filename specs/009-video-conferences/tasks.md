---

description: "Task list for feature implementation: Видеоконференции в чатах"

---

# Tasks: Видеоконференции в чатах (речь, видео, демонстрация экрана)

**Input**: Design documents from `/specs/009-video-conferences/`

**Prerequisites**: plan.md, spec.md (US1–US5), research.md, data-model.md, contracts/ (api-contract.md, livekit-integration.md, realtime-conference-events.md), quickstart.md, `.specify/memory/constitution.md` (принципы I–VIII; VI Test-First — тесты обязательны)

**Tests**: Включены (конституция VI — NON-NEGOTIABLE; план тестирования quickstart.md §4, research §13).

**Organization**: Задачи сгруппированы по user stories (US1–US5 из spec.md) для независимой реализации и проверки каждой истории.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно параллелить (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность user story (US1–US5); Setup/Foundational/Polish — без метки
- Все задачи содержат точные пути файлов; критерии приёмки — контракты №44–№55, SC-001–SC-009, quickstart §3

## Path Conventions

- backend: `backend/src/main/kotlin/webchat/backend/…`, тесты `backend/src/test/kotlin/webchat/backend/…`
- frontend: `frontend/src/…`, e2e `frontend/tests/e2e/`
- контракт: `contracts/openapi.yaml`; миграции: `backend/src/main/resources/db/migration/`

---

## Phase 1: Setup (Общая инфраструктура)

**Purpose**: Зависимости, окружение LiveKit, конфигурация per-env, контракт API (API-First, конституция IV)

- [ ] T001 Добавить зависимость `io.livekit:livekit-server` (Kotlin SDK: минт токенов, Twirp RoomService, WebhookReceiver) в `backend/build.gradle.kts` (выбор обоснован research §3)
- [ ] T002 [P] Добавить зависимости `livekit-client` + `@livekit/components-react` (peer-dep) в `frontend/package.json`; тяжёлый модуль — только ленивый чанк (research §10)
- [ ] T003 [P] Добавить сервис `livekit` в `deploy/local/docker-compose.yml`: образ `livekit/livekit-server --dev`, `devkey/secret`, `webhook.urls → http://backend:8080/api/internal/conferences/livekit/webhook`, `enable_remote_unmute: true`, порты 7880/7881/7882+UDP (livekit-integration §1)
- [ ] T004 [P] Создать манифесты `deploy/k8s/base/livekit.yaml` и `deploy/k8s/overlays/dev/livekit.yaml`: Deployment/Service, порты 7880/7881/7882+UDP/TURN, Redis distributed mode, hostNetwork (research §11)
- [ ] T005 [P] Создать `backend/src/main/kotlin/webchat/backend/config/ConferencesProperties.kt` (`conferences.*`: `max-participants`, `token-ttl=900s`, `guest-invite-ttl=24h`, `empty-room-timeout=60s`, `max-duration=4h`, `warning-before-end=5m`, `reconnect-window=120s`, `guest-name-max-length=50`, `livekit.url/key/secret` из env) + дефолты Q19 в `backend/src/main/resources/application.yml`
- [ ] T006 Обновить `contracts/openapi.yaml`: аддитивно операции №44–№55 (тег `conferences`), SSE №18 `conference.started`/`conference.ended`, схема `Message` (nullable `senderId`, `kind`, `payload`), minor 0.10.0→0.11.0; прогнать `./scripts/validate-contracts.sh` (vacuum→oasdiff, без breaking) и `pnpm --dir frontend generate:api` (обновить `schema.d.ts`)

**Checkpoint**: зависимости, окружение и контракт готовы; генерация TS-типов актуальна

---

## Phase 2: Foundational (Блокирующие предпосылки)

**Purpose**: Ядро домена `conferences`, порты/адаптеры, LiveKit-интеграция — MUST быть готово до любых user stories

**⚠️ CRITICAL**: Ни одна user story не начинается до завершения фазы

- [ ] T007 Создать миграцию `backend/src/main/resources/db/migration/V18__conferences.sql`: таблицы `conferences`, `conference_participants`, `conference_guest_invites` (uuid PK, CHECK-инварианты, `ux_conferences_chat_active` WHERE status<>'ended', `ux_guest_invites_active` WHERE status='active', shape-CHECK участника) + эволюция `messages` (nullable `sender_id`, `kind`, `payload jsonb` с CHECK) — строго по data-model.md сущности 1–4
- [ ] T008 Создать доменные модели в `backend/src/main/kotlin/webchat/backend/conferences/domain/model/`: `Conference` (status/end_reason, переходы только вперёд), `ConferenceParticipant` (identity `u:{userId}`/`g:{connId}`, роль, 4 ограничения), `GuestInvite`, `ParticipantRestrictions` (единая функция вычисления grants: speech ⟹ canPublish=false; sources − restricted, research §4/§5), `ConferenceIdentity` (парсинг identity-префиксов)
- [ ] T009 [P] Создать порты в `backend/src/main/kotlin/webchat/backend/conferences/domain/port/`: `ConferenceRepository` (create/get, conditional UPDATE статусов), `ParticipantRepository`, `GuestInviteRepository`, `LiveKitAdminClient` (CreateRoom/DeleteRoom/ListRooms/ListParticipants/RemoveParticipant/UpdateParticipant/MutePublishedTrack/SendData, livekit-integration §4), `ChatMembershipChecker` (адаптер над 006)
- [ ] T010 [P] Реализовать `JdbcConferenceRepository` и `JdbcParticipantRepository` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` (JdbcTemplate, конвенция доменов без JPA; идемпотентный conditional finalize; удаление строк на finalize — Q18)
- [ ] T011 [P] Реализовать `JdbcGuestInviteRepository` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` (инвариант «одна active» в одной транзакции revoke+insert, Q17)
- [ ] T012 Реализовать адаптер `LiveKitServerAdminClient` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/LiveKitServerAdminClient.kt`: минт админ-токена (roomCreate+roomList+roomAdmin, в памяти адаптера), все методы контракта livekit-integration §4 с retry+backoff (SET-семантика, идемпотентность)
- [ ] T013 [P] Реализовать адаптер `ChatMembershipCheckerImpl` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` поверх membership-механизма 006 (`GroupMembershipGate`/`ParticipantRepository`); unit-тесты member/non-member/left-member
- [ ] T014 [P] Создать `backend/src/main/kotlin/webchat/backend/conferences/ConferenceMetrics.kt`: метрики `webchat_conference_*` — активные конференции/участники (gauge по `ix_conferences_status`), события жизненного цикла, применение квот, отказы гостевого входа (FR-018, SC-009)

**Checkpoint**: Фундамент готов — реализации user stories могут идти параллельно

---

## Phase 3: User Story 1 — Старт конференции из чата и подключение по ссылке (Priority: P1) 🎯 MVP

**Goal**: Кнопки «видеозвонок»/«аудиозвонок» в заголовке чата → конференция + системное сообщение-приглашение; защищённое membership-check'ом подключение членов чата; речь/видео/экран в 1:1 и группах (FR-001–FR-004)

**Independent Test**: quickstart §3.1 — alice нажимает «видеозвонок» в личном чате → bob видит карточку с «Подключиться», оба видят/слышат друг друга; carol (не член) по ссылке — `403 conference_forbidden`, токен не выдан (SC-002); групповой чат — тот же флоу; выход последнего → комната закрывается таймаутом LiveKit

### Implementation for User Story 1

- [ ] T015 [P] [US1] Создать DTO в `backend/src/main/kotlin/webchat/backend/conferences/api/dto/ConferenceDtos.kt`: `ConferenceView`, `MeView`, `ParticipantView`, `JoinTicket`, `CreateConferenceRequest{mode}` — строго по api-contract §4
- [ ] T016 [US1] Реализовать `ConferenceService.create` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ConferenceService.kt`: E2EE-чат → `409 conference_e2ee_unsupported`; идемпотентность «одна активная на чат» (ux-индекс → `200` существующей, сообщение не дублируется, edge); `CreateRoom(name=c:{id}, empty/departure_timeout=60, max_participants)`; строка создателя `role='moderator'`; системное сообщение-виджет в той же транзакции (FR-001/FR-002; зависит от T007–T012)
- [ ] T017 [US1] Расширить домен chats системными сообщениями: nullable `sender`, `kind`, `payload` в модели записи/чтения сообщений и в fanout `message.created` (порт системных сообщений в `backend/src/main/kotlin/webchat/backend/chats/`; payload `{"type":"conference_invite","conferenceId","mode"}` ≤ 4 КБ, seq-выделение и post-commit фанаут без изменений — research §8, конституция III/IV)
- [ ] T018 [US1] Реализовать `JoinTokenService` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/JoinTokenService.kt`: минт LiveKit JWT HS256 (identity `u:{userId}:{connId}`, name=username, roomJoin, TTL `conferences.token-ttl`, grants из `ParticipantRestrictions`, metadata `{role,guest:false}`, без админ-grants — livekit-integration §3); pre-check квоты `ListParticipants` vs `max_participants` → `409 conference_full` (FR-015)
- [ ] T019 [US1] Реализовать `ConferenceController` (№44 create, №45 get, №46 me, №47 join) и `ConferencesExceptionHandler` (ProblemDetail: `403 conference_forbidden`, `404 conference_not_found`, `409 conference_e2ee_unsupported|conference_ended|conference_full`, `503 conference_media_unavailable`) в `backend/src/main/kotlin/webchat/backend/conferences/api/`; membership-check на каждом join до минта (FR-003, SC-002)
- [ ] T020 [US1] Реализовать in-process слушатель `MemberRemoved` (событие 006, публикация в `backend/src/main/kotlin/webchat/backend/groups/`) → post-commit `ListParticipants` по префиксу `u:{userId}:` → `RemoveParticipant` (FR-003 edge: исключённый из чата отключается, повторный минт заблокирован membership-check'ом)
- [ ] T021 [US1] IT в `backend/src/test/kotlin/webchat/backend/conferences/` (Testcontainers PG+Redis+LiveKit `--dev`): конкурентное создание → одна конференция на чат; join не-члена → 403 до всякой строки participant (SC-002); grants тикета из ограничений; системное сообщение в истории с `kind='system'`; `conference_full` при исчерпании слотов (инварианты 1–3 data-model §примеры)
- [ ] T022 [P] [US1] Создать API-клиент `frontend/src/conferences/api/conferences.ts` на сгенерированных типах `schema.d.ts` (№44–№47, обработка Problem-ошибок)
- [ ] T023 [P] [US1] Добавить кнопки «видеозвонок»/«аудиозвонок» в `frontend/src/chats/components/ChatHeader.tsx`: №44 с `mode=video|audio` → переход `/conference/{id}?mode=…`; при активной конференции — переход на существующую (без дубля карточки)
- [ ] T024 [P] [US1] Реализовать рендер системного сообщения-карточки в компонентах списка сообщений `frontend/src/chats/`: `payload.type='conference_invite'` → карточка с кнопкой «Подключиться» (переход по ссылке, статус проверяется №45 при клике); неизвестные `payload.type` — нейтральный плейсхолдер (OCP, api-contract §4)
- [ ] T025 [US1] Добавить маршруты в `frontend/src/App.tsx`: `/conference/:id` (protected, ленивый чанк `frontend/src/conferences/pages/ConferencePage.tsx`)
- [ ] T026 [US1] Реализовать хук `frontend/src/conferences/hooks/useConference.ts` + `ConferencePage`: загрузка №45/№46, join №47 → `JoinTicket`, «одна конференция на вкладку» — вход во вторую корректно покидает первую (Q10=A), обработка `conference_ended` («конференция завершена»); повторный №47 при реконнекте с истёкшим токеном
- [ ] T027 [US1] Реализовать `frontend/src/conferences/components/ConferenceRoom.tsx` на `livekit-client`: подключение по `JoinTicket` (wsUrl+token), аудио/видео/демонстрация экрана, тайлы участников, simulcast/dynacast — адаптация качества без обрыва (FR-004)
- [ ] T028 [US1] Реализовать `frontend/src/conferences/components/MediaControls.tsx`: тумблеры микрофона/камеры/экрана, выход из конференции; подключение к `ConferenceRoom` (вход «только слушаю» разрешён — FR-006)
- [ ] T029 [US1] Тесты US1: Vitest (`frontend/src/conferences/` + chats: кнопки ChatHeader, карточка-приглашение, плейсхолдер) и Playwright e2e `frontend/tests/e2e/conferences.spec.ts` — звонок двух вкладок против локального LiveKit (fake media: `--use-fake-device-for-media-stream`), SC-001 ≤ 5 c

**Checkpoint**: MVP работает end-to-end — создание из чата, карточка, защищённый join, медиа, демонстрация экрана

---

## Phase 4: User Story 2 — Pre-join экран: предпросмотр и стартовое состояние (Priority: P1)

**Goal**: Единый pre-join для всех точек входа: превью камеры, тумблеры «микрофон»/«камера», выбор устройств, «Войти»; дефолты по mode; отражение ограничений прав; вход без публикации треков (FR-005–FR-007)

**Independent Test**: quickstart §3.2 — из каждой точки входа (видеозвонок/аудиозвонок/ссылка/гостевая ссылка) один и тот же pre-join с корректными дефолтами; оба тумблера выкл → «только слушаю»; закрытие без «Войти» — без следов; запрет доступа к устройствам — предупреждение + вход доступен (Q6=A)

### Implementation for User Story 2

- [ ] T030 [US2] Реализовать `frontend/src/conferences/components/PreJoinScreen.tsx`: локальное превью, тумблеры стартового состояния, выбор устройств ввода/вывода, кнопка «Войти»; дефолты: видеозвонок → камера+микрофон, аудиозвонок → только микрофон (тумблер камеры доступен — soft, Q3=A); треки не публикуются и слот не занимается до «Войти» (FR-007)
- [ ] T031 [US2] Реализовать хук `frontend/src/conferences/hooks/useDevices.ts`: enumerate устройств, состояние разрешений, переключение микрофона/камеры/динамиков на лету в звонке (Q11=B), сигналы добавления/удаления устройств
- [ ] T032 [US2] Обработать отказ браузера в доступе к камере/микрофону в `frontend/src/conferences/components/PreJoinScreen.tsx` и `ConferenceRoom.tsx`: понятное предупреждение, вход без недоступных треков («только слушаю»), повторный запрос доступа уже в звонке (Q6=A)
- [ ] T033 [US2] Отразить ограничения прав на pre-join: данные №46 `MeView` (`micRestricted`/`cameraRestricted`/`screenRestricted`/`speechRestricted`) → недоступные источники скрыты/заблокированы с пояснением (FR-007); «право слова» блокирует все тумблеры публикации (Q4=A)
- [ ] T034 [US2] Интегрировать `PreJoinScreen` в `frontend/src/conferences/pages/ConferencePage.tsx` перед подключением для всех точек входа (mode из маршрута/карточки), передача стартового состояния в `ConferenceRoom` (вход с обоими выкл — FR-006); выход из pre-join без «Войти» отменяет подключение без последствий
- [ ] T035 [US2] Тесты US2 (Vitest, `frontend/src/conferences/`): дефолты тумблеров для каждой точки входа; блокировка тумблеров по `MeView`; предупреждение при запрете устройств; «Войти» с обоими выкл → connect без публикации; закрытие pre-join → disconnect без следов

**Checkpoint**: US1 и US2 работают независимо; все точки входа членов чата дают единый pre-join

---

## Phase 5: User Story 3 — Управление участниками и модерация (Priority: P2)

**Goal**: Серверная модерация: мьют микрофона/камеры/экрана, «право слова», назначение/снятие модераторов, кик, завершение конференции; персистентность ролей и ограничений до конца конференции (FR-008)

**Independent Test**: quickstart §3.3 — модератор глушит микрофон, отключает камеру и экран, применяет право слова, назначает/снимает модератора, кикает, завершает конференцию; каждое действие ≤ 2 c (SC-005) и видно всем; ограничения переживают переподключение (Q5=A), модераторы равноправны (Q21=A)

### Implementation for User Story 3

- [ ] T036 [US3] Реализовать `ModerationService` (ограничения №49) в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ModerationService.kt`: SET-состояние ограничений в `conference_participants` (идемпотентно) + немедленное применение к живому подключению `UpdateParticipant` (canPublish/canPublishSources + metadata) и `MutePublishedTrack` уже опубликованных треков; персистентность до конца конференции (Q5/Q22=A; требует `enable_remote_unmute`)
- [ ] T037 [US3] Реализовать в `ModerationService` смену роли №50 (персистентна, включая переподключение — Q9=A) и кик №51 (`RemoveParticipant`; кик ≠ бан — переподключение по действующей ссылке допустимо, строка принципала не трогается)
- [ ] T038 [US3] Реализовать в `ModerationService` завершение №48: `DeleteRoom` — все отключаются немедленно; SSE `conference.ended` доставляется через вебхук `room_finished` (интеграция завершается в US5 — T055); `409 conference_ended` при повторе
- [ ] T039 [US3] Контроллер модерации в `backend/src/main/kotlin/webchat/backend/conferences/api/ConferenceController.kt`: №48–№51, проверка `moderator_required` (403), равноправие модераторов — действия к любому участнику, включая других модераторов и создателя (Q21=A)
- [ ] T040 [US3] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: не-модератор → 403; право слова блокирует все публикации (Q4=A); ограничение переживает переподключение — воспроизводится в grants нового минта (Q5=A, инвариант 3 data-model); роль модератора переживает переподключение (Q9=A); модератор снимает роль/кикает другого модератора (Q21=A); `UpdateParticipant`/`MutePublishedTrack` вызываются адаптеру (fake-порт)
- [ ] T041 [P] [US3] Реализовать `frontend/src/conferences/components/ParticipantsPanel.tsx`: список участников (identity/name, роль, online, индикация ограничений; пометка «гость» — поле готово, наполняется в US4)
- [ ] T042 [P] [US3] Реализовать `frontend/src/conferences/components/ModerationPanel.tsx`: мьют микрофона/камеры/экрана, «право слова», назначение/снятие модератора, кик, завершение конференции (№48–№51, подтверждения деструктивных действий)
- [ ] T043 [US3] Отразить собственные ограничения в `MediaControls.tsx`/pre-join: заблокированные тумблеры с пояснением, live-обновление по permission-событиям LiveKit + refetch №46; тесты US3 — Vitest (панели, блокировки) + Playwright модерация (SC-005 ≤ 2 c)

**Checkpoint**: Модерация полностью серверная и немедленная; US1–US3 независимы и работают вместе

---

## Phase 6: User Story 4 — Гостевой доступ по инвайт-ссылке (Priority: P3)

**Goal**: Внешний участник без аккаунта по подписанной ссылке: публичные №54/№55 (единственное исключение из «все API аутентифицированы»), nonce ES256 + TTL + отзыв, rate limiting, гость — только экран конференции, старт «только микрофон» (FR-009–FR-012)

**Independent Test**: quickstart §3.4 — генерация ссылки → приватное окно: invite-info → имя → pre-join → «Войти» → пометка «гость», старт только аудио (≤ 3 действий, SC-003); истёкшая/отозванная/подделанная ссылка и флуд отклоняются (SC-004); отзыв отключает вошедших гостей (Q13=B); новая ссылка отзывает старую (Q17=A)

### Implementation for User Story 4

- [ ] T044 [P] [US4] Реализовать `GuestInviteTokens` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/GuestInviteTokens.kt`: подпись/проверка nonce JWT ES256 (Nimbus, инфраструктура JwtService), `sub=inviteId`, `exp=created+ttl`; отказ единообразный без раскрытия причин подделки (edge)
- [ ] T045 [US4] Реализовать `GuestInviteService` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/GuestInviteService.kt`: №52 генерация (одна активная: revoke предыдущей + insert в одной транзакции, Q17=A; в чат не постится) и №53 отзыв; post-commit `ListParticipants` → все с `metadata.inviteId={id}` → `RemoveParticipant` (Q13=B, livekit-integration §6.1)
- [ ] T046 [US4] Реализовать `GuestJoinController` в `backend/src/main/kotlin/webchat/backend/conferences/api/GuestJoinController.kt`: №54 `invite-info` (`valid/reason/live`, минимум раскрытия) и №55 `join-by-invite` (валидация имени trim 1–50 → `422 name_invalid`; nonce → статус → live → квота → строка гостя на подключение (Q7=A) → минт `g:{connId}` с полными canPublishSources (Q1=C), metadata `{role:participant,guest:true,inviteId}`, TTL токена как у членов); ошибки `invite_invalid|not_found`, `invite_expired|revoked|conference_ended|conference_full` (api-contract §3)
- [ ] T047 [US4] Открыть публичную поверхность: `+3 permitAll` (`invite-info`, `join-by-invite`, webhook — строка вебхука используется в US5) в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` и per-IP бакеты в `backend/src/main/kotlin/webchat/backend/config/RateLimitFilter.kt` (`rl:ip:conferences-invite-info` 30/мин, `rl:ip:conferences-join-by-invite` 10/мин, паттерн 002)
- [ ] T048 [US4] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: подделанный/истёкший/отозванный nonce → отказ (SC-003); имя пустое/51-символьное → 422; флуд №54/№55 → 429, остальные API не деградируют (SC-004, Δp99 ≤ 10 %); отзыв отключает вошедших гостей (Q13=B, инвариант 4 data-model); генерация новой отзывает старую (Q17=A); гость и члены делят квоту → `conference_full` (Q23=A); аутентифицированный член по гостевой ссылке входит гостем (Q14=A)
- [ ] T049 [P] [US4] Реализовать `frontend/src/conferences/pages/GuestJoinPage.tsx`: №54 статус ссылки (истёк/отозван/завершён — понятные сообщения) → ввод имени → переиспользование `PreJoinScreen` (дефолт «только микрофон») → №55 «Войти» → `ConferenceRoom` по `JoinTicket`
- [ ] T050 [P] [US4] Добавить публичный маршрут `/join/:inviteToken` в `frontend/src/App.tsx`: без аутентификации, ленивый чанк, доступ к чату исключён (FR-010)
- [ ] T051 [US4] Пометка «гость» в `frontend/src/conferences/components/ParticipantsPanel.tsx` (метаданные участника LiveKit `guest:true`) и отображение гостевого имени
- [ ] T052 [US4] Тесты US4: Vitest GuestJoinPage (валидный/невалидный инвайт, ошибки имени, пометка «гость») + Playwright гостевой сценарий (приватное окно без аккаунта, SC-003)

**Checkpoint**: Гости входят без аккаунта; публичная поверхность защищена rate limiting; US1–US4 независимы

---

## Phase 7: User Story 5 — События жизненного цикла, завершение и квоты (Priority: P3)

**Goal**: SSE `conference.started`/`conference.ended` участникам чата (≤ 2 c, SC-006); корректное завершение всеми путями (последний вышел/модератор/max-duration/потеря комнаты/удаление чата) с освобождением ресурсов (SC-007); квоты при создании и минте (FR-013–FR-015)

**Independent Test**: quickstart §3.5 — carol, не входя в звонок, получает события по SSE ≤ 2 c; после завершения медиа-комната отсутствует, старые токены/ссылки отклоняются; потеря вебхука компенсируется sweeper'ом; max-duration: предупреждение + контролируемое завершение (Q16=B)

### Implementation for User Story 5

- [ ] T053 [US5] Реализовать `LiveKitWebhookController` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/LiveKitWebhookController.kt`: `POST /api/internal/conferences/livekit/webhook`, приём сырого тела `byte[]`, проверка подписи `WebhookReceiver.receive(rawBody, authHeader)` (невалидная подпись → 401, иначе всегда 200), дедуп по `event.id`, payload не логируется (livekit-integration §5)
- [ ] T054 [US5] Обработать вебхуки в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ConferenceLifecycleService.kt`: `room_started` → conditional `created→active` (дубли — no-op) + post-commit SSE `conference.started` ровно один раз; `participant_joined`/`participant_left` → метки `last_joined_at`/`last_left_at` (principals по префиксу identity) + метрики; прочие события — 200/no-op
- [ ] T055 [US5] Реализовать идемпотентный `finalize` в `ConferenceLifecycleService`: conditional `→ended` + `end_reason` (`last_left|moderator|max_duration|room_lost|chat_deleted`), удаление строк participants/invites (Q18=A), post-commit SSE `conference.ended` активным членам чата (снимок членства в транзакции) через существующий `RealtimeEventPublisher` (канал `rt:user:{userId}`, realtime-conference-events); вызывается вебхуком `room_finished`, №48 (T038), scheduler'ом, CASCADE чата
- [ ] T056 [US5] Реализовать `backend/src/main/kotlin/webchat/backend/conferences/scheduler/ConferenceLifecycleScheduler.kt`: max-duration — предупреждение за `warning-before-end` (SSE + `SendData` topic `conference.warning` — достаёт гостей без SSE, Q16=B) и контролируемое завершение по истечении; sweeper SC-007 — `ListRooms`: досоздание потерянных комнат для `created`, finalize «зависших» `active` без комнаты (`room_lost`), компенсация потери вебхуков
- [ ] T057 [US5] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: невалидная подпись вебхука → 401, валидная → 200; дубли `room_finished` не меняют `ended_at`/`end_reason` (идемпотентность, инвариант 5 data-model); после finalize строки отсутствуют, история чата не изменена, старая ссылка → «конференция завершена» (Q18/Q20=A, инвариант 6); sweeper завершает конференцию при остановке LiveKit (SC-007); max-duration: предупреждение + завершение (Q16=B); события SSE доставляются ≤ 2 c (SC-006)
- [ ] T058 [P] [US5] Создать нагрузочный smoke `load/k6/conferences.smoke.js`: профиль создание/join/гость/лимиты контрольной плоскости (запуск: `docker run --rm -i grafana/k6 run - < load/k6/conferences.smoke.js`, quickstart §4)
- [ ] T059 [P] [US5] Реализовать `frontend/src/conferences/hooks/useConferenceRealtime.ts` + `frontend/src/conferences/components/ConferenceBadge.tsx`: индикатор «звонок идёт» по SSE `conference.started`/`conference.ended` (список чатов + заголовок), дубли безопасны (применение как функция статуса), REST-fallback №45 при переподключении SSE (realtime-conference-events §4)
- [ ] T060 [P] [US5] Реализовать UX недоступности медиа-плоскости в `frontend/src/conferences/components/ConferenceRoom.tsx`: индикация потери связи, попытки переподключения в окне `conferences.reconnect-window` (дефолт 120 с) с обновлением токена (№47/№55) при истёкшем TTL, при неудаче — контролируемое завершение с понятным сообщением; при восстановлении звонок продолжается (Q15=B)
- [ ] T061 [US5] Тесты US5 (Vitest): бейдж «звонок идёт» по событиям started/ended, снятие по ended, REST-fallback; e2e-проверка индикатора в `frontend/tests/e2e/conferences.spec.ts`

**Checkpoint**: Все user stories независимо функциональны; жизненный цикл наблюдаем и корректно освобождает ресурсы

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные проверки качества, безопасности и готовности (конституция: Development Workflow & Quality Gates)

- [ ] T062 [P] Прогнать полный контур контракта и backend: `./scripts/validate-contracts.sh` (vacuum → openapi-typescript → drift-check → oasdiff без breaking) и `./gradlew test` (workdir `backend/`) — все IT зелёные
- [ ] T063 [P] Прогнать frontend: `pnpm --dir frontend test`, `pnpm --dir frontend exec playwright test tests/e2e/conferences.spec.ts` (против local-compose LiveKit), lint/format backend+frontend
- [ ] T064 Аудит безопасности (принцип V): `LIVEKIT_API_KEY/SECRET` и ключи nonce — только env; webhook payload и identity без PII не логируются; гостю недоступны API чатов (проверка без Bearer); ошибки публичных endpoints не раскрывают деталей конференции/причин подделки
- [ ] T065 Прогнать ручную валидацию quickstart.md §3.1–§3.5 целиком на `deploy/local` (включая негативные ветки 3.1.3, 3.4.5–3.4.8, 3.5.2–3.5.5)
- [ ] T066 Проверить наблюдаемость (SC-009): метрики `webchat_conference_*` на `/actuator/prometheus` — активные конференции/участники, события, квоты, отказы гостевого входа; k6 smoke из T058 против dev-стенда (SC-004/SC-008)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно; T006 (контракт) блокирует T015+ (DTO) и T022 (TS-типы)
- **Foundational (Phase 2)**: после Setup (T001 для T012, T005 для всех конфигураций) — БЛОКИРУЕТ все user stories
- **User Stories (Phases 3–7)**: после Foundational; внутри истории — модели → сервисы → контроллеры → фронтенд → тесты
- **Polish (Phase 8)**: после всех реализуемых историй

### User Story Dependencies

- **US1 (P1)**: после Foundational; ядро — от T016 зависят T018–T021 (backend) и T026+ (frontend)
- **US2 (P1)**: после Foundational; переиспользует №46/`JoinTicket` из US1 (T019/T018) и `ConferenceRoom` (T027) — реализуема параллельно с US1 после T027 либо сразу после US1
- **US3 (P2)**: после Foundational; зависит от T018 (grants) и T012 (`UpdateParticipant`/`MutePublishedTrack`); SSE-событие завершения №48 доезжает в US5 (T055)
- **US4 (P3)**: после Foundational; переиспользует `PreJoinScreen` (US2, T030) на гостевой странице; квота — T018
- **US5 (P3)**: после Foundational; завешивает SSE-события для №48 (US3) и негативные ответы «конференция завершена» для US1/US4

### Within Each User Story

- Тесты пишутся до/одновременно с реализацией (конституция VI); IT-задачи замыкают историю
- Порты/модели → сервисы → контроллеры/страницы → интеграция → тесты
- История завершена checkpoint'ом — независимая проверка по quickstart

### Parallel Opportunities

- Phase 1: T002–T005 параллельно (независимые файлы); T006 после изучения — независимо от T002–T005
- Phase 2: T009–T011, T013, T014 полностью параллельны; T010–T012 после T007–T008
- Фазы историй: US2 (frontend) параллельно с US3/US4 (backend) разными исполнителями
- Внутри US1: T015, T022–T024 параллельны; US3: T041–T042 параллельны; US4: T044, T049–T050 параллельны; US5: T058–T060 параллельны

---

## Parallel Example: User Story 1

```bash
# Независимые задачи US1 (разные файлы):
Task: "T015 [US1] DTO ConferenceDtos.kt"
Task: "T022 [US1] frontend/src/conferences/api/conferences.ts"
Task: "T023 [US1] ChatHeader.tsx кнопки звонка"
Task: "T024 [US1] карточка-приглашение в frontend/src/chats/"

# Затем последовательно (общие зависимости):
Task: "T016 [US1] ConferenceService.create"
Task: "T018 [US1] JoinTokenService" → "T019 [US1] ConferenceController №44–№47"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1: Setup (контракт №44–№55 + окружение LiveKit)
2. Complete Phase 2: Foundational (V18, домен, порты, LiveKit-адаптер)
3. Complete Phase 3: User Story 1 — звонок из чата end-to-end
4. **STOP and VALIDATE**: quickstart §3.1 (SC-001/SC-002) вручную + e2e
5. Deploy/demo если готово

### Incremental Delivery

1. Setup + Foundational → фундамент
2. +US1 → MVP звонок членов чата (валидация, demo)
3. +US2 → pre-join для всех точек входа (P1-пара завершена)
4. +US3 → модерация групповых звонков
5. +US4 → гостевой доступ (публичная поверхность)
6. +US5 → события жизненного цикла, квоты, компенсации
7. Каждая история добавляет ценность, не ломая предыдущие (независимые тесты quickstart §3.x)

### Parallel Team Strategy

1. Команда вместе закрывает Setup + Foundational
2. Далее параллельно: Developer A — US1→US2 (фронтенд-цепочка), Developer B — US3 (модерация), Developer C — US5 ( lifecycle backend); US4 — после US2 (переиспользование pre-join)
3. Интеграция историй независима; сквозные проверки — Phase 8

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story] — трассировка к US1–US5 (spec.md)
- Медиа-состояние — только в LiveKit, не в БД; БД хранит роли/ограничения/статусы (data-model)
- Секреты (`LIVEKIT_API_KEY/SECRET`, ключи nonce) — только env; identity/room name без PII
- Коммит после каждой задачи или логической группы; статусы отмечаются в этом файле
- Избегать: расплывчатых задач, конфликтов одного файла, cross-story зависимостей, ломающих независимость
