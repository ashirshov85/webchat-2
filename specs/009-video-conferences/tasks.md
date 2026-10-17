---

description: "Task list for feature implementation: Видеоконференции в чатах"

---

# Tasks: Видеоконференции в чатах (речь, видео, демонстрация экрана)

**Input**: Design documents from `/specs/009-video-conferences/`

**Prerequisites**: plan.md, spec.md (US1–US5), research.md, data-model.md, contracts/ (api-contract.md, livekit-integration.md, realtime-conference-events.md), quickstart.md, `.specify/memory/constitution.md` (принципы I–VIII; VI Test-First — тесты обязательны)

**Tests**: Включены (конституция VI — NON-NEGOTIABLE; план тестирования quickstart.md §4, research §13).

**Organization**: Задачи сгруппированы по user stories (US1–US5 из spec.md) для независимой реализации и проверки каждой истории (оговорка: финализация завершения (вебхук T053 + finalize T055 + sweeper T056) замыкается в US5 и нужна не только для №48 из US3 (SSE-статус и `conference.ended`), но и для ответов «конференция завершена» по старой ссылке в US1-6 и `conference_ended` в гостевых №54/№55 из US4; поэтому полные сценарии US1-6, US3-6 и негативные ветки «конференция завершена» US4 проверяются только после US5 (T057); см. Checkpoint Phase 5/7).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно параллелить (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность user story (US1–US5); Setup/Foundational/Polish — без метки
- Все задачи содержат точные пути файлов; критерии приёмки — контракты №44–№55, SC-001–SC-009, quickstart §3; тесты каждой имплементационной задачи замыкаются тест-задачей своей истории (US1 — T021/T029, US2 — T035, US3 — T040/T043, US4 — T048/T052, US5 — T057/T061/T062; Foundational — unit T013 и IT историй)

## Path Conventions

- backend: `backend/src/main/kotlin/webchat/backend/…`, тесты `backend/src/test/kotlin/webchat/backend/…`
- frontend: `frontend/src/…`, e2e `frontend/tests/e2e/`
- контракт: `contracts/openapi.yaml`; миграции: `backend/src/main/resources/db/migration/`

---

## Phase 1: Setup (Общая инфраструктура)

**Purpose**: Зависимости, окружение LiveKit, конфигурация per-env, контракт API (API-First, конституция IV)

- [X] T001 Добавить зависимость `io.livekit:livekit-server` (Kotlin SDK: минт токенов, Twirp RoomService, WebhookReceiver) в `backend/build.gradle.kts` (выбор обоснован research §3); критерий: `./gradlew build` зелёный, классы SDK (AccessToken, RoomServiceClient, WebhookReceiver) резолвятся — используются в T012
- [X] T002 [P] Добавить зависимости `livekit-client` + `@livekit/components-react` (peer-dep) в `frontend/package.json`; тяжёлый модуль — только ленивый чанк (research §10); критерий: `pnpm --dir frontend build` зелёный, `livekit-client` отсутствует в main-chunk (только ленивый) — проверяется в T029/T035
- [X] T003 [P] Добавить сервис `livekit` в `deploy/local/docker-compose.yml`: образ `livekit/livekit-server --dev`, `devkey/secret`, `webhook.urls → http://backend:8080/api/internal/conferences/livekit/webhook`, `enable_remote_unmute: true`, порты 7880/7881/7882+UDP (livekit-integration §1); критерий: `docker compose up livekit` healthy, вебхук доезжает до backend: запрос достигает приложения (доступ в логе; сам обработчик появляется в T053) — используется всеми IT (T021+) и quickstart §2
- [X] T004 [P] Создать манифесты `deploy/k8s/base/livekit.yaml` и `deploy/k8s/overlays/dev/livekit.yaml`: Deployment/Service, порты 7880/7881/7882+UDP/TURN, Redis distributed mode, hostNetwork, `enable_remote_unmute: true` (обязательно для серверного мьюта №49 — livekit-integration §1; research §11); критерий: `kubectl apply` dev-оверлея проходит, readiness-проба LiveKit зелёная — задействуется в T068
- [X] T005 [P] Создать `backend/src/main/kotlin/webchat/backend/config/ConferencesProperties.kt` (`conferences.*`: `max-participants=16`, `token-ttl=900s`, `guest-invite-ttl=24h`, `empty-room-timeout=60s`, `departure-timeout=60s`, `max-duration=4h`, `warning-before-end=5m`, `reconnect-window=120s`, `guest-name-max-length=50`, `sweep-interval=60s`, `room-lost-grace=120s`, `livekit.url/key/secret` из env) + дефолты Q19 в `backend/src/main/resources/application.yml`; критерий: все ключи `conferences.*` биндятся с дефолтами Q19 и переопределяются из env — unit-тест биндинга (входит в набор IT T021+)
- [X] T006 Обновить `contracts/openapi.yaml`: аддитивно операции №44–№55 (тег `conferences`; в №44 включить `503 conference_media_unavailable` — недоступность медиа-плоскости при создании, edge spec.md), SSE №18 `conference.started`/`conference.ended`, схема `Message` (nullable `senderId`, `kind`, `payload`), minor 0.10.0→0.11.0; прогнать `./scripts/validate-contracts.sh` (vacuum→oasdiff, без breaking) и `pnpm --dir frontend generate:api` (обновить `schema.d.ts`)

**Checkpoint**: зависимости, окружение и контракт готовы; генерация TS-типов актуальна

---

## Phase 2: Foundational (Блокирующие предпосылки)

**Purpose**: Ядро домена `conferences`, порты/адаптеры, LiveKit-интеграция — MUST быть готово до любых user stories

**⚠️ CRITICAL**: Ни одна user story не начинается до завершения фазы

- [X] T007 Создать миграцию `backend/src/main/resources/db/migration/V18__conferences.sql`: таблицы `conferences`, `conference_participants`, `conference_guest_invites` (uuid PK, CHECK-инварианты, `ux_conferences_chat_active` WHERE status<>'ended', `ux_guest_invites_active` WHERE status='active', shape-CHECK участника) + эволюция `messages` (nullable `sender_id`, `kind`, `payload jsonb` с CHECK) и `chats.e2ee boolean NOT NULL DEFAULT false` (признак для границы FR-001/010, читается `ConferenceService.create` — data-model, сущность 1) — строго по data-model.md сущности 1–4
- [X] T008 Создать доменные модели в `backend/src/main/kotlin/webchat/backend/conferences/domain/model/`: `Conference` (status/end_reason, переходы только вперёд), `ConferenceParticipant` (identity `u:{userId}`/`g:{connId}`, роль, 4 ограничения), `GuestInvite`, `ParticipantRestrictions` (единая функция вычисления grants: speech ⟹ canPublish=false; sources − restricted, research §4/§5), `ConferenceIdentity` (парсинг identity-префиксов)
- [X] T009 [P] Создать порты в `backend/src/main/kotlin/webchat/backend/conferences/domain/port/`: `ConferenceRepository` (create/get, conditional UPDATE статусов), `ParticipantRepository`, `GuestInviteRepository`, `LiveKitAdminClient` (CreateRoom/DeleteRoom/ListRooms/ListParticipants/RemoveParticipant/UpdateParticipant/MutePublishedTrack/SendData, livekit-integration §4), `ChatMembershipChecker` (адаптер над 006)
- [X] T010 [P] Реализовать `JdbcConferenceRepository` и `JdbcParticipantRepository` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` (JdbcTemplate, конвенция доменов без JPA; идемпотентный conditional finalize; удаление строк на finalize — Q18)
- [X] T011 [P] Реализовать `JdbcGuestInviteRepository` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` (инвариант «одна active» в одной транзакции revoke+insert, Q17)
- [X] T012 Реализовать адаптер `LiveKitServerAdminClient` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/LiveKitServerAdminClient.kt`: минт админ-токена (roomCreate+roomList+roomAdmin, в памяти адаптера), все методы контракта livekit-integration §4 с retry+backoff (SET-семантика, идемпотентность)
- [X] T013 [P] Реализовать адаптер `ChatMembershipCheckerImpl` в `backend/src/main/kotlin/webchat/backend/conferences/repository/` поверх membership-механизма 006 (`GroupMembershipGate`/`ParticipantRepository`); unit-тесты member/non-member/left-member
- [X] T014 [P] Создать `backend/src/main/kotlin/webchat/backend/conferences/ConferenceMetrics.kt`: метрики `webchat_conference_*` — активные конференции/участники (gauge по `ix_conferences_status`), события жизненного цикла, применение квот, отказы гостевого входа (FR-018, SC-009)

**Checkpoint**: Фундамент готов — реализации user stories могут идти параллельно

---

## Phase 3: User Story 1 — Старт конференции из чата и подключение по ссылке (Priority: P1) 🎯 MVP

**Goal**: Кнопки «видеозвонок»/«аудиозвонок» в заголовке чата → конференция + системное сообщение-приглашение; защищённое membership-check'ом подключение членов чата; речь/видео/экран в 1:1 и группах (FR-001–FR-004)

**Independent Test**: quickstart §3.1 — alice нажимает «видеозвонок» в личном чате → bob видит карточку с «Подключиться», оба видят/слышат друг друга; carol (не член) по ссылке — `403 conference_forbidden`, токен не выдан (SC-002); групповой чат — тот же флоу; выход последнего → комната закрывается таймаутом LiveKit

### Implementation for User Story 1

- [X] T015 [P] [US1] Создать DTO в `backend/src/main/kotlin/webchat/backend/conferences/api/dto/ConferenceDtos.kt`: `ConferenceView`, `MeView`, `ParticipantView`, `JoinTicket`, `CreateConferenceRequest{mode}` — строго по api-contract §4
- [X] T016 [US1] Расширить домен chats системными сообщениями: nullable `sender`, `kind`, `payload` в модели записи/чтения сообщений и в fanout `message.created` (порт системных сообщений в `backend/src/main/kotlin/webchat/backend/chats/`; payload `{"type":"conference_invite","conferenceId","mode"}` ≤ 4 КБ, seq-выделение и post-commit фанаут без изменений — research §8, конституция III/IV)
- [X] T017 [US1] Реализовать `ConferenceService.create` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ConferenceService.kt`: E2EE-чат → `409 conference_e2ee_unsupported`; идемпотентность «одна активная на чат» (ux-индекс → `200` существующей, сообщение не дублируется, edge); pre-flight доступности LiveKit (`ListRooms`-probe) до транзакции → `503 conference_media_unavailable` без записи в БД (T021, research §7); `CreateRoom(name=c:{id}, empty_timeout={conferences.empty-room-timeout}, departure_timeout={conferences.departure-timeout}, max_participants={conferences.max-participants})` — вызов post-commit, после фиксации транзакции («сироты» лечатся sweeper'ом T056 — research §7); livekit-integration §2; строка создателя `role='moderator'`; системное сообщение-виджет в той же транзакции (FR-001/FR-002; зависит от T007–T012 и T016 — порт системных сообщений chats)
- [X] T018 [US1] Реализовать `JoinTokenService` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/JoinTokenService.kt`: минт LiveKit JWT HS256 (identity `u:{userId}:{connId}`, name=username, roomJoin, TTL `conferences.token-ttl`, grants из `ParticipantRestrictions`, metadata `{role,guest:false}`, без админ-grants — livekit-integration §3); pre-check квоты `ListParticipants` vs `max_participants` → `409 conference_full` (FR-015)
- [X] T019 [US1] Реализовать `ConferenceController` (№44 create, №45 get, №46 me, №47 join) и `ConferencesExceptionHandler` (ProblemDetail: `403 conference_forbidden`, `404 conference_not_found`, `409 conference_e2ee_unsupported|conference_ended|conference_full`, `503 conference_media_unavailable`) в `backend/src/main/kotlin/webchat/backend/conferences/api/`; membership-check на каждом join до минта (FR-003, SC-002)
- [X] T020 [US1] Реализовать in-process слушатель `GroupMemberRemovedEvent` (событие 006, публикация в `backend/src/main/kotlin/webchat/backend/groups/GroupService.kt`; обрабатывать обе причины — №32 исключение и №33 добровольный выход, включая `actorId = null`) → post-commit `ListParticipants` по префиксу `u:{userId}:` → `RemoveParticipant` (FR-003 edge: вышедший/исключённый из чата отключается, повторный минт заблокирован membership-check'ом)
- [X] T021 [US1] IT в `backend/src/test/kotlin/webchat/backend/conferences/` (Testcontainers PG+Redis+LiveKit `--dev`): конкурентное создание → одна конференция на чат; join не-члена → 403 до всякой строки participant (SC-002); исключение/добровольный выход участника из чата во время конференции (T020): активное подключение закрывается (`RemoveParticipant` по префиксу identity), повторный №47 → `403 conference_forbidden` (FR-003 edge); grants тикета из ограничений; системное сообщение в истории с `kind='system'`; `conference_full` при исчерпании слотов; два подключения одного пользователя (две вкладки) занимают два слота квоты (edge spec.md); остановка контейнера LiveKit → №44 отвечает `503 conference_media_unavailable`, строка конференции и системное сообщение не создаются (откат транзакции); создание из E2EE-чата (фикстура `chats.e2ee=true`) → `409 conference_e2ee_unsupported`, комната LiveKit и системное сообщение не создаются (FR-001, research §13) (инварианты 1–3 data-model §примеры)
- [X] T022 [P] [US1] Создать API-клиент `frontend/src/conferences/api/conferences.ts` на сгенерированных типах `schema.d.ts` (№44–№47, обработка Problem-ошибок); критерий: strict-TS компиляция на сгенерированных типах, Problem-ошибки типизированы — моки клиента в T029
- [X] T023 [P] [US1] Добавить кнопки «видеозвонок»/«аудиозвонок» в `frontend/src/chats/components/ChatHeader.tsx`: №44 с `mode=video|audio` → переход `/conference/{id}?mode=…`; при активной конференции — переход на существующую (без дубля карточки)
- [X] T024 [P] [US1] Реализовать рендер системного сообщения-карточки в компонентах списка сообщений `frontend/src/chats/`: `payload.type='conference_invite'` → карточка с кнопкой «Подключиться» (переход по ссылке, статус проверяется №45 при клике); неизвестные `payload.type` — нейтральный плейсхолдер (OCP, api-contract §4)
- [X] T025 [US1] Добавить маршруты в `frontend/src/App.tsx`: `/conference/:id` (protected, ленивый чанк `frontend/src/conferences/pages/ConferencePage.tsx`); критерий: `/conference/:id` под auth грузит ленивый чанк, без auth — редирект — Vitest маршрута + e2e T029
- [X] T026 [US1] Реализовать хук `frontend/src/conferences/hooks/useConference.ts` + `ConferencePage`: загрузка №45/№46, join №47 → `JoinTicket`, «одна конференция на вкладку» — вход во вторую корректно покидает первую (Q10=A), обработка `conference_ended` («конференция завершена»); повторный №47 при реконнекте с истёкшим токеном
- [X] T027 [US1] Реализовать `frontend/src/conferences/components/ConferenceRoom.tsx` на `livekit-client`: подключение по `JoinTicket` (wsUrl+token), аудио/видео/демонстрация экрана, тайлы участников, simulcast/dynacast — адаптация качества без обрыва (FR-004)
- [X] T028 [US1] Реализовать `frontend/src/conferences/components/MediaControls.tsx`: тумблеры микрофона/камеры/экрана, выход из конференции; подключение к `ConferenceRoom` (вход «только слушаю» разрешён — FR-006)
- [X] T029 [US1] Тесты US1: Vitest (`frontend/src/conferences/` + chats: кнопки ChatHeader, карточка-приглашение, плейсхолдер) и Playwright e2e `frontend/tests/e2e/conferences.spec.ts` — звонок двух вкладок против локального LiveKit (fake media: `--use-fake-device-for-media-stream`), SC-001 ≤ 5 c (каноничная форма «от «Войти» на pre-join» — после T034; на фазе US1 — от «Подключиться» до медиа); устойчивость к деградации сети (FR-004): CDP network throttling (`client.emit('Network.emulateNetworkConditions', ...)`) на одной вкладке — подключение сохраняется без обрыва, после снятия троттлинга звонок продолжается (адаптацию слоёв по качеству дополняет ручная проверка quickstart §3.1 шаг 4)

**Checkpoint**: MVP работает end-to-end — создание из чата, карточка, защищённый join, медиа, демонстрация экрана (оговорка: ответ «конференция завершена» по старой ссылке из US1-6 требует finalize US5 — проверяется в T057; каноничный замер SC-001 «от «Войти» на pre-join» — после US2 (T034): до этого e2e T029 измеряет путь «Подключиться» → медиа без pre-join)

---

## Phase 4: User Story 2 — Pre-join экран: предпросмотр и стартовое состояние (Priority: P1)

**Goal**: Единый pre-join для всех точек входа: превью камеры, тумблеры «микрофон»/«камера», выбор устройств, «Войти»; дефолты по mode; отражение ограничений прав; вход без публикации треков (FR-005–FR-007)

**Independent Test**: quickstart §3.2 — из каждой точки входа (видеозвонок/аудиозвонок/ссылка/гостевая ссылка) один и тот же pre-join с корректными дефолтами; оба тумблера выкл → «только слушаю»; закрытие без «Войти» — без следов; запрет доступа к устройствам — предупреждение + вход доступен (Q6=A)

### Implementation for User Story 2

- [X] T030 [US2] Реализовать `frontend/src/conferences/components/PreJoinScreen.tsx`: локальное превью, тумблеры стартового состояния, выбор устройств ввода/вывода, кнопка «Войти»; дефолты: видеозвонок → камера+микрофон, аудиозвонок → только микрофон (тумблер камеры доступен — soft, Q3=A); треки не публикуются и слот не занимается до «Войти» (FR-007); доступность по стандартам 008 (research §10): `aria-pressed` тумблеров, `focus-visible`, dialog-семантика; стили модуля — `frontend/src/conferences/conferences.css` (единый файл стилей conferences, plan.md Structure)
- [X] T031 [US2] Реализовать хук `frontend/src/conferences/hooks/useDevices.ts`: enumerate устройств, состояние разрешений, переключение микрофона/камеры/динамиков на лету в звонке (Q11=B), сигналы добавления/удаления устройств
- [X] T032 [US2] Обработать отказ браузера в доступе к камере/микрофону в `frontend/src/conferences/components/PreJoinScreen.tsx` и `ConferenceRoom.tsx`: понятное предупреждение, вход без недоступных треков («только слушаю»), повторный запрос доступа уже в звонке (Q6=A)
- [X] T033 [US2] Отразить ограничения прав на pre-join: данные №46 `MeView` (`micRestricted`/`cameraRestricted`/`screenRestricted`/`speechRestricted`) → недоступные источники скрыты/заблокированы с пояснением (FR-007); «право слова» блокирует все тумблеры публикации (Q4=A)
- [X] T034 [US2] Интегрировать `PreJoinScreen` в `frontend/src/conferences/pages/ConferencePage.tsx` перед подключением для всех точек входа (mode из маршрута/карточки), передача стартового состояния в `ConferenceRoom` (вход с обоими выкл — FR-006); выход из pre-join без «Войти» отменяет подключение без последствий
- [X] T035 [US2] Тесты US2 (Vitest, `frontend/src/conferences/`): дефолты тумблеров для каждой точки входа; блокировка тумблеров по `MeView` (гость: на pre-join ограничений нет — строка гостя создаётся при входе, data-model §2); предупреждение при запрете устройств; «Войти» с обоими выкл → connect без публикации; закрытие pre-join → disconnect без следов

**Checkpoint**: US1 и US2 работают независимо; все точки входа членов чата дают единый pre-join

---

## Phase 5: User Story 3 — Управление участниками и модерация (Priority: P2)

**Goal**: Серверная модерация: мьют микрофона/камеры/экрана, «право слова», назначение/снятие модераторов, кик, завершение конференции; персистентность ролей и ограничений до конца конференции (FR-008)

**Independent Test**: quickstart §3.3 — модератор глушит микрофон, отключает камеру и экран, применяет право слова, назначает/снимает модератора, кикает, завершает конференцию; каждое действие ≤ 2 c (SC-005) и видно всем; ограничения переживают переподключение (Q5=A), модераторы равноправны (Q21=A)

### Implementation for User Story 3

- [X] T036 [US3] Реализовать `ModerationService` (ограничения №49) в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ModerationService.kt`: SET-состояние ограничений в `conference_participants` (идемпотентно) + немедленное применение к живому подключению `UpdateParticipant` (canPublish/canPublishSources + metadata) и `MutePublishedTrack` уже опубликованных треков; персистентность до конца конференции (Q5/Q22=A; требует `enable_remote_unmute`)
- [X] T037 [US3] Реализовать в `ModerationService` смену роли №50 (персистентна, включая переподключение — Q9=A) и кик №51 (`RemoveParticipant`; кик ≠ бан — переподключение по действующей ссылке допустимо, строка принципала не трогается)
- [X] T038 [US3] Реализовать в `ModerationService` завершение №48: `DeleteRoom` — все отключаются немедленно; штатный путь финализации — вебхук `room_finished` (№48 → DeleteRoom → вебхук → finalize T055); прямой вызов finalize — только компенсация при потере вебхука (sweeper, T056); интеграция завершается в US5 — T053/T055; `409 conference_ended` при повторе
- [X] T039 [US3] Контроллер модерации в `backend/src/main/kotlin/webchat/backend/conferences/api/ConferenceController.kt`: №48–№51, проверка `moderator_required` (403), равноправие модераторов — действия к любому участнику, включая других модераторов и создателя (Q21=A)
- [X] T040 [US3] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: не-модератор → 403; право слова блокирует все публикации (Q4=A); ограничение переживает переподключение — воспроизводится в grants нового минта (Q5=A, инвариант 3 data-model); роль модератора переживает переподключение (Q9=A); модератор снимает роль/кикает другого модератора (Q21=A); кикнутый член чата повторно входит по действующей ссылке-приглашению → успех (кик ≠ бан, строка принципала не тронута — edge spec.md); `UpdateParticipant`/`MutePublishedTrack` вызываются адаптеру (fake-порт)
- [X] T041 [P] [US3] Реализовать `frontend/src/conferences/components/ParticipantsPanel.tsx`: список участников (identity/name, роль, online, индикация ограничений; пометка «гость» — поле готово, наполняется в US4); критерий: список отражает identity/name/роль/online из LiveKit — Vitest T043
- [X] T042 [P] [US3] Реализовать `frontend/src/conferences/components/ModerationPanel.tsx`: мьют микрофона/камеры/экрана, «право слова», назначение/снятие модератора, кик, завершение конференции (№48–№51, подтверждения деструктивных действий)
- [X] T043 [US3] Отразить собственные ограничения в `MediaControls.tsx`/pre-join: заблокированные тумблеры с пояснением, live-обновление по permission-событиям LiveKit + refetch №46; тесты US3 — Vitest (панели, блокировки) + Playwright модерация (SC-005 ≤ 2 c)

**Checkpoint**: Модерация полностью серверная и немедленная; US1–US3 работают вместе (полная независимость — с оговоркой: завершение №48 замыкается в US5 — вебхук-приёмник T053 + finalize T055; до Phase 7 статус/SSE по №48 не финализируются, сценарий US3-6 проверяется полностью после US5)

---

## Phase 6: User Story 4 — Гостевой доступ по инвайт-ссылке (Priority: P3)

**Goal**: Внешний участник без аккаунта по подписанной ссылке: публичные №54/№55 (единственное исключение из «все API аутентифицированы»), nonce ES256 + TTL + отзыв, rate limiting, гость — только экран конференции, старт «только микрофон» (FR-009–FR-012)

**Independent Test**: quickstart §3.4 — генерация ссылки → приватное окно: invite-info → имя → pre-join → «Войти» → пометка «гость», старт только аудио (≤ 3 действий, SC-003); истёкшая/отозванная/подделанная ссылка и флуд отклоняются (SC-004); отзыв отключает вошедших гостей (Q13=B); новая ссылка отзывает старую (Q17=A)

### Implementation for User Story 4

- [X] T044 [P] [US4] Реализовать `GuestInviteTokens` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/GuestInviteTokens.kt`: подпись/проверка nonce JWT ES256 (Nimbus, инфраструктура JwtService), `sub=inviteId`, `exp=created+ttl`; отказ единообразный без раскрытия причин подделки (edge)
- [ ] T045 [US4] Реализовать `GuestInviteService` в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/GuestInviteService.kt`: №52 генерация (одна активная: revoke предыдущей + insert в одной транзакции, Q17=A; в чат не постится) и №53 отзыв + эндпоинты №52/№53 в `backend/src/main/kotlin/webchat/backend/conferences/api/ConferenceController.kt` с проверкой `403 moderator_required`, `409 conference_ended`, `404 guest_invite_not_found` (api-contract §2); post-commit `ListParticipants` → все с `metadata.inviteId={id}` → `RemoveParticipant` (Q13=B, livekit-integration §6.1)
- [ ] T046 [US4] Реализовать `GuestJoinController` в `backend/src/main/kotlin/webchat/backend/conferences/api/GuestJoinController.kt`: №54 `invite-info` (`valid/reason/live`, минимум раскрытия) и №55 `join-by-invite` (валидация имени trim 1–50 → `422 name_invalid`; nonce → статус → live → квота → строка гостя создаётся при выдаче JoinTicket №55 (Q7=A) → минт `g:{connId}` с полными canPublishSources (Q1=C), metadata `{role:participant,guest:true,inviteId}`, TTL токена как у членов); ошибки `invite_invalid|not_found`, `invite_expired|revoked|conference_ended|conference_full` (api-contract §3)
- [ ] T047 [US4] Открыть публичную поверхность: `+2 permitAll` (`invite-info`, `join-by-invite`) в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` и per-IP бакеты в `backend/src/main/kotlin/webchat/backend/config/RateLimitFilter.kt` (`rl:ip:conferences-invite-info` 30/мин, `rl:ip:conferences-join-by-invite` 10/мин — дефолт per-env, research §6, канон чисел — api-contract №54/№55; паттерн 002); строка вебхука открывается в T053 вместе с контроллером
- [ ] T048 [US4] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: подделанный/истёкший/отозванный nonce → отказ (SC-003); имя пустое/51-символьное → 422; флуд №54/№55 → 429, остальные API не деградируют (SC-004, Δp99 ≤ 10 %); отзыв отключает вошедших гостей (Q13=B, инвариант 4 data-model); генерация новой отзывает старую (Q17=A); кикнутый гость повторно входит по действующей ссылке → успех (edge: кик ≠ бан, spec.md edge); гость и члены делят квоту → `conference_full` (Q23=A); аутентифицированный член по гостевой ссылке входит гостем (Q14=A); не-модератор на №52/№53 → 403 moderator_required
- [ ] T049 [US4] Реализовать `frontend/src/conferences/pages/GuestJoinPage.tsx`: №54 статус ссылки (истёк/отозван/завершён — понятные сообщения) → ввод имени → переиспользование `PreJoinScreen` (дефолт «только микрофон», тумблер камеры доступен, как у членов чата — Q1=C; у гостя на pre-join ограничений нет — строка гостя создаётся при входе, применённые модератором ограничения отражаются в звонке live-права́ми, data-model §2) → №55 «Войти» → `ConferenceRoom` по `JoinTicket`
- [ ] T050 [P] [US4] Добавить публичный маршрут `/join/:inviteToken` в `frontend/src/App.tsx`: без аутентификации, ленивый чанк, доступ к чату исключён (FR-010); критерий: `/join/:inviteToken` без аутентификации грузит ленивый чанк, чат-роуты недоступны — Vitest + Playwright T052
- [ ] T051 [US4] Пометка «гость» в `frontend/src/conferences/components/ParticipantsPanel.tsx` (метаданные участника LiveKit `guest:true`) и отображение гостевого имени; критерий: бейдж «гость» у участников с `metadata.guest=true` — Vitest T052
- [ ] T052 [US4] Тесты US4: Vitest GuestJoinPage (валидный/невалидный инвайт, ошибки имени, пометка «гость») + Playwright гостевой сценарий (приватное окно без аккаунта, SC-003)

**Checkpoint**: Гости входят без аккаунта; публичная поверхность защищена rate limiting; US1–US4 независимы

---

## Phase 7: User Story 5 — События жизненного цикла, завершение и квоты (Priority: P3)

**Goal**: SSE `conference.started`/`conference.ended` участникам чата (≤ 2 c, SC-006); корректное завершение всеми путями (последний вышел/модератор/max-duration/потеря комнаты/удаление чата) с освобождением ресурсов (SC-007); квоты при создании и минте (FR-013–FR-015)

**Independent Test**: quickstart §3.5 — carol, не входя в звонок, получает события по SSE ≤ 2 c; после завершения медиа-комната отсутствует, старые токены/ссылки отклоняются; потеря вебхука компенсируется sweeper'ом; max-duration: предупреждение + контролируемое завершение (Q16=B)

### Implementation for User Story 5

- [ ] T053 [US5] Реализовать `LiveKitWebhookController` в `backend/src/main/kotlin/webchat/backend/conferences/livekit/LiveKitWebhookController.kt`: `POST /api/internal/conferences/livekit/webhook`, приём сырого тела `byte[]`, проверка подписи `WebhookReceiver.receive(rawBody, authHeader)` (невалидная подпись → 401, иначе всегда 200), дедуп по `event.id`, payload не логируется (livekit-integration §5); открыть `permitAll` вебхука в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` и бакет `rl:ip:conferences-livekit-webhook` 60/мин (дефолт per-env, покрывает ретраи LiveKit) в `RateLimitFilter.kt` — вместе с контроллером, чтобы маршрут не был публичным до появления обработчика
- [ ] T054 [US5] Обработать вебхуки в `backend/src/main/kotlin/webchat/backend/conferences/domain/service/ConferenceLifecycleService.kt`: `room_started` — 200/no-op (комната создаётся eagerly при №44, вебхук приходит до входа участников — research §7); первый `participant_joined` для конференции в статусе `created` → conditional `created→active` (дубли — no-op) + post-commit SSE `conference.started` ровно один раз (US5-1: событие — при фактическом входе первого участника); все `participant_joined`/`participant_left` → метки `last_joined_at`/`last_left_at` (principals по префиксу identity) + метрики; прочие события — 200/no-op
- [ ] T055 [US5] Реализовать идемпотентный `finalize` в `ConferenceLifecycleService`: conditional `→ended` + `end_reason` (`last_left|moderator|max_duration|room_lost|chat_deleted`), удаление строк participants/invites (Q18=A), post-commit SSE `conference.ended` активным членам чата (снимок членства в транзакции) через существующий `RealtimeEventPublisher` (канал `rt:user:{userId}`, realtime-conference-events); вызывается вебхуком `room_finished` (штатный путь и для №48 — T038: DeleteRoom → вебхук → finalize), scheduler'ом (max-duration/sweeper, T056) и слушателем удаления чата (T062); mapping причин — data-model §Сущность 1: №48 предзаписывает `moderator` до DeleteRoom; `created`-конференции (empty-timeout, «никто не вошёл») и sweeper → `room_lost`; вебхук без предзаписи → `last_left`
- [ ] T056 [US5] Реализовать `backend/src/main/kotlin/webchat/backend/conferences/scheduler/ConferenceLifecycleScheduler.kt`: max-duration — предупреждение за `warning-before-end` (`SendData` topic `conference.warning` всем подключённым — членам и гостям; отдельный SSE-тип не вводится — контракт №18 остаётся двумя событиями, FR-015/Q16=B) и контролируемое завершение по истечении; sweeper SC-007 — цикл `conferences.sweep-interval` (дефолт 60 с), `ListRooms`: досоздание потерянных комнат для `created`, finalize «зависших» `active` без комнаты дольше `conferences.room-lost-grace` (дефолт 120 с ≥ 2× empty-timeout — не конкурирует со штатным закрытием пустой комнаты; `room_lost`), компенсация потери вебхуков
- [ ] T057 [US5] IT в `backend/src/test/kotlin/webchat/backend/conferences/`: невалидная подпись вебхука → 401, валидная → 200; SSE `conference.started` не отправляется при №44 и отправляется ровно один раз — при первом `participant_joined` (T054, US5-1); дубли `room_finished` не меняют `ended_at`/`end_reason` (идемпотентность, инвариант 5 data-model); после finalize строки отсутствуют, история чата не изменена, «конференция завершена» по обоим путям старой ссылки: member №47 → `409 conference_ended`, guest №54 → `200 {valid:false, reason:"ended"}`, №55 → `409 conference_ended` (Q18/Q20=A, инвариант 6); sweeper завершает конференцию при остановке LiveKit (SC-007); созданная, но «пустая» конференция завершается по empty-timeout с `end_reason='room_lost'` и SSE `reason='room_lost'`; max-duration: предупреждение + завершение (Q16=B); «поздний»/внепорядковый `participant_joined` после `room_finished` (статус `ended`) → переход `created→active` не выполняется, повторный SSE `conference.started` не отправляется (порядок доставки, конституция III); события SSE доставляются ≤ 2 c (SC-006)
- [ ] T058 [P] [US5] Создать нагрузочный smoke `load/k6/conferences.smoke.js`: профиль создание/join/гость/лимиты контрольной плоскости; профиль SC-004 — легитимный фон (№12/№27, суммарно ~10 rps) + флуд №54 60 req/min/IP и №55 20 req/min/IP (2× дефолтных бакетов T047) в течение 5 мин, критерий — Δp99 фона ≤ 10 % (запуск: `docker run --rm -i grafana/k6 run - < load/k6/conferences.smoke.js`, quickstart §4)
- [ ] T059 [P] [US5] Реализовать `frontend/src/conferences/hooks/useConferenceRealtime.ts` + `frontend/src/conferences/components/ConferenceBadge.tsx`: индикатор «звонок идёт» по SSE `conference.started`/`conference.ended` (список чатов + заголовок), дубли и внепорядковые события безопасны (применение как функция статуса), REST-fallback №45 при переподключении SSE (realtime-conference-events §4)
- [ ] T060 [P] [US5] Реализовать UX недоступности медиа-плоскости в `frontend/src/conferences/components/ConferenceRoom.tsx`: индикация потери связи, попытки переподключения в окне `conferences.reconnect-window` (дефолт 120 с) с обновлением токена (№47/№55) при истёкшем TTL, при неудаче — контролируемое завершение с понятным сообщением; при восстановлении звонок продолжается (Q15=B); приём data-пакета LiveKit topic `conference.warning` (RoomEvent.DataReceived, T056) → тост «конференция завершится по лимиту длительности» (FR-015, Q16=B)
- [ ] T061 [US5] Тесты US5 (Vitest): бейдж «звонок идёт» по событиям started/ended, снятие по ended, REST-fallback, внепорядковый `started` после `ended` не поднимает бейдж (функция статуса, конституция III); Vitest `ConferenceRoom` — индикация потери связи и контролируемое завершение при истечении `reconnect-window` (Q15=B, T060); e2e-проверка индикатора в `frontend/tests/e2e/conferences.spec.ts`; тост предупреждения по data-пакету `conference.warning` (T060/T056)
- [ ] T062 [US5] Реализовать завершение конференций при удалении чата: публикация события домена chats в точке удаления чата (`backend/src/main/kotlin/webchat/backend/chats/`) **в транзакции удаления — до коммита и до срабатывания ON DELETE CASCADE** (не по образцу T020, где публикация post-commit: строки конференций и членство ещё нужны; напр. `@TransactionalEventListener(phase = BEFORE_COMMIT)`) → идемпотентный `finalize(end_reason='chat_deleted')` для всех незавершённых конференций чата: предзапись `end_reason` + снимок членства в той же транзакции, `DeleteRoom` при живой комнате — post-commit, SSE `conference.ended` по снимку членства на момент удаления (FR-014, edge «чат удалён», quickstart §3.5); IT: удаление чата при активной конференции → участники отключены, комната отсутствует, событие получено бывшими членами, `end_reason='chat_deleted'` зафиксирован

**Checkpoint**: Все user stories независимо функциональны; жизненный цикл наблюдаем и корректно освобождает ресурсы

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные проверки качества, безопасности и готовности (конституция: Development Workflow & Quality Gates)

- [ ] T063 [P] Прогнать полный контур контракта и backend: `./scripts/validate-contracts.sh` (vacuum → openapi-typescript → drift-check → oasdiff без breaking) и `./gradlew test` (workdir `backend/`) — все IT зелёные
- [ ] T064 [P] Прогнать frontend: `pnpm --dir frontend test`, `pnpm --dir frontend exec playwright test tests/e2e/conferences.spec.ts` (против local-compose LiveKit), lint/format backend+frontend
- [ ] T065 Аудит безопасности (принцип V): `LIVEKIT_API_KEY/SECRET` и ключи nonce — только env; webhook payload и identity без PII не логируются; гостю недоступны API чатов (проверка без Bearer); ошибки публичных endpoints не раскрывают деталей конференции/причин подделки
- [ ] T066 Прогнать ручную валидацию quickstart.md §3.1–§3.5 целиком на `deploy/local` (включая негативные ветки: §3.1 шаг 3, §3.4 шаги 5–8, §3.5 шаги 2–5) и доступность pre-join/экрана конференции по стандартам 008 (FR-007)
- [ ] T067 Проверить наблюдаемость (SC-009): метрики `webchat_conference_*` на `/actuator/prometheus` — активные конференции/участники, события, квоты, отказы гостевого входа; k6 smoke из T058 против dev-стенда (SC-004; медиа-прогон — T068)
- [ ] T068 [P] Нагрузочная валидация медиа-плоскости на dev-стенде (SC-008, конституция II/VI) по требованиям к окружениям (specs/ROADMAP.md): `lk load-test` (или эквивалент, research §12) против dev livekit-server (VPS 4 vCPU / 6 ГБ; limits cpu ~1500m–2 / mem 1–1.5Gi; 720p low-слои без 1080p; maxParticipants ≤ 16) в пределах ёмкости стенда ~10 одновременных 1:1 / ~2 групповых: профили publishers/subscribers, фиксация join-латентности, потерь, CPU/mem ноды (Prometheus-метрики LiveKit + `webchat_conference_*`); экстраполяция prod-бюджета (1000 конференций / ~36k поток-эквивалентов) по формуле ёмкости нод research §12 (8 vCPU ≈ 3–4k streams → 10–14 медиа-нод); полноразмерные прогоны — платформенная фича 016; отчёт — в `load/REPORT.md`

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно; T006 (контракт) блокирует T015+ (DTO) и T022 (TS-типы)
- **Foundational (Phase 2)**: после Setup (T001 для T012, T005 для всех конфигураций) — БЛОКИРУЕТ все user stories
- **User Stories (Phases 3–7)**: после Foundational; внутри истории — модели → сервисы → контроллеры → фронтенд → тесты
- **Polish (Phase 8)**: после всех реализуемых историй

### User Story Dependencies

- **US1 (P1)**: после Foundational; ядро — T016 (порт системных сообщений chats) → T017; от T017 зависят T018–T021 (backend) и T026+ (frontend)
- **US2 (P1)**: после Foundational; переиспользует №46/`JoinTicket` из US1 (T019/T018) и `ConferenceRoom` (T027) — реализуема параллельно с US1 после T027 либо сразу после US1
- **US3 (P2)**: после Foundational; зависит от T018 (grants), T012 (`UpdateParticipant`/`MutePublishedTrack`) и US1-базы T019/T022 (базовый контроллер и API-клиент — нужны T039/T042); `ConferenceController.kt` — общий файл T019→T039→T045, правки строго последовательно; SSE-событие завершения №48 доезжает в US5 (T055)
- **US4 (P3)**: после Foundational и T030 (US2 — `PreJoinScreen` для T049); квота — T018; T045 расширяет `ConferenceController.kt` — после T019 (и T039, если US3 в работе)
- **US5 (P3)**: после Foundational; завершает SSE-события для №48 (US3) и негативные ответы «конференция завершена» для US1/US4

### Within Each User Story

- Тесты пишутся до/одновременно с реализацией (конституция VI); IT-задачи замыкают историю
- Порты/модели → сервисы → контроллеры/страницы → интеграция → тесты
- История завершена checkpoint'ом — независимая проверка по quickstart

### Parallel Opportunities

- Phase 1: T002–T005 параллельно (независимые файлы); T006 после изучения — независимо от T002–T005
- Phase 2: T009–T011, T013, T014 полностью параллельны; T010–T012 после T007–T008
- Фазы историй: US2 (frontend) параллельно с US3/US4 (backend) разными исполнителями
- Внутри US1: T015, T022–T024 параллельны; US3: T041–T042 параллельны; US4: T044, T050 параллельны; T049 — после T030 (US2); US5: T058–T060 параллельны

---

## Parallel Example: User Story 1

```bash
# Независимые задачи US1 (разные файлы):
Task: "T015 [US1] DTO ConferenceDtos.kt"
Task: "T022 [US1] frontend/src/conferences/api/conferences.ts"
Task: "T023 [US1] ChatHeader.tsx кнопки звонка"
Task: "T024 [US1] карточка-приглашение в frontend/src/chats/"

# Затем последовательно (общие зависимости):
Task: "T016 [US1] системные сообщения в домене chats"
Task: "T017 [US1] ConferenceService.create" → "T018 [US1] JoinTokenService" → "T019 [US1] ConferenceController №44–№47"
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1: Setup (контракт №44–№55 + окружение LiveKit)
2. Complete Phase 2: Foundational (V18, домен, порты, LiveKit-адаптер)
3. Complete Phase 3: User Story 1 — звонок из чата end-to-end
4. **STOP and VALIDATE**: quickstart §3.1 (SC-001/SC-002) вручную + e2e (шаги с pre-join (2, 5) и полный замер SC-001 — после US2; на US1 валидны шаги 1, 3, 4, 6, 7 и SC-002)
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
