---
description: "Task list for feature implementation: групповые чаты с ролями и member-авторизацией"
---

# Tasks: Групповые чаты с ролями и member-авторизацией

**Input**: Design documents from `/specs/006-group-chats/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/ (api-contract.md, realtime-group-events.md) ✅, quickstart.md ✅, `.specify/memory/constitution.md` ✅

**Tests**: ОБЯЗАТЕЛЬНЫ (конституция VI Test-First NON-NEGOTIABLE; план — JUnit 5 + Testcontainers, Vitest + Testing Library, k6). В каждой фазе story тесты пишются ПЕРВЫМИ (красные) → реализация → зелёные.

**Organization**: Задачи сгруппированы по user stories (US1–US6 из spec.md) для независимой реализации и проверки каждой story. Backend — Kotlin/Spring (package-by-feature: новый `groups/`, точечная эволюция `chats/`/`realtime/`/`sync/`), frontend — React/TS (новый модуль `src/groups/`, эволюция `src/chats/`).

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность user story (US1–US6); Setup/Foundational/Polish — без метки
- Во всех описаниях — точные пути файлов

## Path Conventions

Монорепозиторий 001: `backend/` (Kotlin + Gradle), `frontend/` (React + TS + pnpm), `contracts/` (OpenAPI), `load/k6/`. Корень backend-кода: `backend/src/main/kotlin/webchat/backend/`; тесты: `backend/src/test/kotlin/webchat/backend/`.

---

## Phase 1: Setup (контракт-first и инфраструктура фичи)

**Purpose**: API-контракт 0.6.0 (конституция IV), миграция БД, конфигурация лимитов

- [ ] T001 Обновить публичный контракт до 0.6.0 (additive minor) в `contracts/openapi.yaml`: операции №27–№35 (tag `groups`), схемы `CreateGroupRequest`/`UpdateGroupRequest`/`AddMembersRequest`/`SetMemberRoleRequest`/`TransferOwnershipRequest`/`GroupMember`/`GroupView`/`Group*Event`; optional-поля `type`/`title`/`memberCount`/`myRole`/`othersReadUpToSeq` и nullable `peer`/`blockedByMe`/`peerReadUpToSeq` у `ChatListItem`/`ChatView`/`SyncChatDelta`; +6 `event:`-типов у №18; константы 200/64/256/50/30/5с — по contracts/api-contract.md
- [ ] T002 Прогнать контрактный конвейер: `vacuum lint -e contracts/openapi.yaml` (0 ошибок); база сравнения — `git show origin/main:contracts/openapi.yaml > /tmp/openapi-base.yaml` (ожидается 0.5.0) → `oasdiff breaking --fail-on ERR /tmp/openapi-base.yaml contracts/openapi.yaml` (без ERR; конвенция ci.yml; единственное ожидаемое изменение обязательности полей — nullable-переход `peer`/`blockedByMe`/`peerReadUpToSeq` у №12/№13/№26, research.md §5: если oasdiff пометит его ERR — зафиксировать точечное исключение в конфигурации oasdiff со ссылкой на research.md §5, не ослабляя `--fail-on ERR` для остальных изменений); регенерация типов `pnpm --dir frontend generate:api` → закоммитить `frontend/src/api/schema.d.ts` (drift-free, типы `Group*`) (depends T001)
- [ ] T003 [P] Создать миграцию `backend/src/main/resources/db/migration/V14__group_chats.sql` по data-model.md §Миграция: `chats` + `kind`/`title`/`description` (nullable пара `user_low_id`/`user_high_id`, shape-CHECK, title/description-CHECK), `chat_participants` + `role`/`state` (CHECK-и), частичный уникальный индекс `ux_chat_participants_owner`, индекс `ix_chat_participants_chat_active`, таблица `group_admin_log` (без FK на `chats`) + индекс `(group_id, created_at)`
- [ ] T004 [P] Создать `backend/src/main/kotlin/webchat/backend/config/GroupsProperties.kt` (`@ConfigurationProperties("groups")`: `max-members=200`, `title-max-length=64`, `description-max-length=256`) и добавить `groups.*` в `backend/src/main/resources/application.yml`

**Checkpoint**: Контракт 0.6.0 зафиксирован, типы сгенерированы, схема БД готова (V14), лимиты конфигурируемы

---

## Phase 2: Foundational (блокирующие предпосылки всех stories)

**Purpose**: Доменные модели, порты, JDBC-адаптеры, realtime-транспорт, гейт членства, метрики

**⚠️ CRITICAL**: Работа над stories не начинается до завершения фазы

**Тестовое покрытие Phase 2** (конституция VI, трассировка): T005/T006 → T005a/T018/T018a; T007/T012/T013 → T012a/T018/T040/T049/T059/T060; T008/T011 → T012a/T018/T018a/T031/T061; T009/T014 → T012a/T030/T054; T010 → T010a/T054/T068; T015 → T015a/T034/T054/T056; T016/T017 → T015a/T018/T022. DoD задачи Phase 2: компиляция + чистый линт + зелёные тесты примыкающей тест-задачи (T005a/T010a/T012a/T015a — пишутся ПЕРВЫМИ, красными); сценарная приёмка — зелёные IT stories при закрытии соответствующей user story.

### Tests for Phase 2 (писать ПЕРВЫМИ — красные)

- [ ] T005a [P] Написать `backend/src/test/kotlin/webchat/backend/groups/domain/model/GroupChatModelTest.kt` (JUnit 5, без контейнеров): `GroupTitle` (trim, 1–64; пусто/65 → invalid), `GroupDescription` (≤256; 257 → invalid), `MemberRole`/`MembershipState` (covers T005; валидация T016)
- [ ] T010a [P] Написать `backend/src/test/kotlin/webchat/backend/groups/GroupMetricsTest.kt` (JUnit 5 + Micrometer `SimpleMeterRegistry`): счётчики/таймер/гистограмма регистрируются и растут (covers T010)
- [ ] T012a [P] Написать `backend/src/test/kotlin/webchat/backend/GroupRepositoryIT.kt` (JUnit 5 + Testcontainers PG+Redis): тонкий срез адаптеров — create (chats+participants в одной tx), `activeMembers`/`countActive`, `addMember`/`reactivate` (инициализация и сохранение водяных знаков), `removeMember` (rowcount), MIN-водяные знаки, append-only журнал, hard-delete; post-commit публикация `RedisRealtimePublisher` в `rt:user:{id}` (подписка в Testcontainers Redis) (covers T011–T014)
- [ ] T015a [P] Написать `backend/src/test/kotlin/webchat/backend/groups/domain/service/GroupMembershipGateTest.kt` + `GroupsExceptionHandlerTest.kt` (JUnit 5, fake-порты): гейт — не-участник/несуществующая → `404 group_not_found` + инкремент `webchat_group_authz_denials_total`; маппинг problem-кодов (covers T015, T017)

- [ ] T005 [P] Создать доменную модель групп в `backend/src/main/kotlin/webchat/backend/groups/domain/model/GroupChat.kt`: kind-проекция `chats` (`title`/`description`/`lastSeq`), enum `MemberRole` (owner/admin/member), enum `MembershipState` (active/removed), value-объекты `GroupTitle` (trim, 1–64) и `GroupDescription` (≤256) с валидацией FR-001
- [ ] T006 [P] Эволюционировать модели чатов: `backend/src/main/kotlin/webchat/backend/chats/domain/model/Chat.kt` (+`kind`/`title`/`description`; `involves()` — только для direct) и `backend/src/main/kotlin/webchat/backend/chats/domain/model/ChatParticipant.kt` (+`role`/`state`)
- [ ] T007 [P] Создать порты `backend/src/main/kotlin/webchat/backend/groups/domain/port/`: `GroupRepository.kt` (create/get/update/delete-hard, активный состав, `countActive` c `FOR UPDATE`, MIN-водяные знаки), `GroupAdminLogRepository.kt` (append-only, FR-017), `GroupEventPublisher.kt` (доменные события группы)
- [ ] T008 [P] Расширить порт `backend/src/main/kotlin/webchat/backend/chats/domain/port/ParticipantRepository.kt`: `findActive(chatId, userId)`, `activeMembers(chatId)` (снимок ≤200), `addMember`/`reactivate` (инициализация `last_read_seq = delivered_up_to_seq = chats.last_seq` при первом добавлении; реактивация — сохранение знаков + `role='member'`, `hidden=false`), `removeMember` (единый UPDATE `state='removed'`, `role='member'` → rowcount), `minOtherReadUpToSeq(chatId, userId)`
- [ ] T009 [P] Расширить порт `backend/src/main/kotlin/webchat/backend/chats/domain/port/RealtimeEventPublisher.kt`: публикация group-событий (`group.updated`, `group.member.added`, `group.member.removed`, `group.role.changed`, `group.you_removed`, `group.deleted`) и `message.created`/`chat.read` списку адресатов (фанаут)
- [ ] T010 [P] Создать `backend/src/main/kotlin/webchat/backend/groups/GroupMetrics.kt` (Micrometer, FR-017): счётчики `webchat_group_authz_denials_total`, `webchat_group_message_fanout_total` + `_seconds`, гистограмма `webchat_group_size`
- [ ] T011 [P] Реализовать `backend/src/main/kotlin/webchat/backend/chats/repository/JdbcParticipantRepository.kt`: новые методы порта T008 (index-only-scan по `ix_chat_participants_chat_active`, GREATEST-монотонность знаков, tx-границы) (depends T003, T006, T008)
- [ ] T012 [P] Реализовать `backend/src/main/kotlin/webchat/backend/groups/repository/JdbcGroupRepository.kt`: create (chats+participants в одной tx), get/active-состав, `SELECT … FOR UPDATE` строки `chats` для сериализации добавлений, update-метаданных, hard-delete (depends T003, T005, T007)
- [ ] T013 [P] Реализовать `backend/src/main/kotlin/webchat/backend/groups/repository/JdbcGroupAdminLogRepository.kt`: append-only журнал, 10 значений `action` по data-model.md §Сущность 4 (depends T003, T007)
- [ ] T014 [P] Расширить `backend/src/main/kotlin/webchat/backend/realtime/RedisRealtimePublisher.kt`: публикация group-событий и фанаут-списков в существующие `rt:user:{userId}` (конверт 004, строго post-commit) (depends T009)
- [ ] T015 [P] Создать `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupMembershipGate.kt`: проверка active-строки участника на каждом запросе (FR-008); не-участник/несуществующая → единый `404 group_not_found`; инкремент `webchat_group_authz_denials_total` (depends T008, T010)
- [ ] T016 [P] Создать DTO `backend/src/main/kotlin/webchat/backend/groups/api/dto/` (в одном/нескольких файлах): `CreateGroupRequest`, `UpdateGroupRequest`, `AddMembersRequest`, `SetMemberRoleRequest`, `TransferOwnershipRequest`, `GroupMember`, `GroupView` (соответствуют схемам 0.6.0) (depends T005)
- [ ] T017 Создать `backend/src/main/kotlin/webchat/backend/groups/api/GroupsExceptionHandler.kt`: RFC 9457 Problem-коды `group_not_found`/`forbidden_role`/`role_hierarchy_violation`/`not_group_owner`/`owner_must_transfer`/`not_in_contacts`/`group_full`/`target_not_member`/`self_forbidden`/`invalid_*` (depends T016)

**Checkpoint**: Фундамент готов — реализация user stories может начинаться (параллельно разными исполнителями или последовательно в порядке приоритетов)

---

## Phase 3: User Story 1 — Создание группы и участники из контактов (Priority: P1) 🎯 MVP

**Goal**: Пользователь создаёт группу (название/описание), добавляет участников из своих контактов; группа с названием и составом видна всем участникам в едином списке «Чаты» (FR-001, FR-002, FR-013, FR-014)

**Independent Test**: Пользователь создаёт группу, добавляет двух контактов; группа с корректным названием/составом появляется в «Чатах» у всех троих; сообщение отправляется любым из них (US1-тест spec.md)

### Tests for User Story 1 (писать ПЕРВЫМИ, до реализации — красные)

- [ ] T018 [P] [US1] Написать `backend/src/test/kotlin/webchat/backend/GroupLifecycleIT.kt` (JUnit 5 + Testcontainers PG+Redis): создание с названием/описанием → 201 GroupView (создатель owner, добавленные member); №28 GET; ошибки `invalid_title` (пустое/65)/`invalid_description` (257)/`not_in_contacts` (атомарный batch)/`self_forbidden`; №31 добавление: первое добавление — бейдж непрочитанных 0 (FR-013); лимиты/идемпотентность — в T018a (GroupLimitsIT)
- [ ] T018a [P] [US1] Создать `backend/src/test/kotlin/webchat/backend/GroupLimitsIT.kt` (JUnit 5 + Testcontainers PG+Redis): лимит 200 участников → `group_full` (заниженный `groups.max-members`, №31); валидация title 1–64/trim и description ≤256 (№27/№29); идемпотентность повторного добавления уже активного (№31 → 200, дубля в составе нет); база для T061 (конкуренция «исключение×выход»)
- [ ] T019 [P] [US1] Написать frontend-тесты (Vitest + Testing Library): `frontend/src/groups/components/__tests__/CreateGroupDialog.test.tsx` (валидация названия, выбор контактов, мультивыбор), `frontend/src/chats/components/__tests__/ChatListItem.group.test.tsx` (type-дискриминация, title/memberCount, бейдж)

### Implementation for User Story 1

- [ ] T020 [US1] Создать `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupRolePolicy.kt`: проверка прав на добавление участников (owner/admin — FR-002/FR-004, матрица расширится в US3)
- [ ] T021 [US1] Реализовать сценарии в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt`: `create` (owner + начальный состав из контактов создателя, инициализация водяных знаков `chats.last_seq`, журнал `group_created`/`member_added`×N, post-commit `group.member.added` всем активным, включая создателя — realtime-group-events.md §3.2) и `addMembers` (№31: `FOR UPDATE`, контакты добавляющего, batch атомарен, идемпотентность, реактивация с сохранением знаков и сбросом роли в member (data-model.md §Сущность 2), лимит 200 → `group_full`) (depends T011–T015, T020)
- [ ] T022 [US1] Реализовать `backend/src/main/kotlin/webchat/backend/groups/api/GroupController.kt`: №27 `POST /api/v1/groups`, №28 `GET /api/v1/groups/{chatId}`, №31 `POST /api/v1/groups/{chatId}/members` (коды/тела — api-contract.md §2; member → `403 forbidden_role`) (depends T021, T017)
- [ ] T023 [US1] Эволюционировать №12: group-поля `ChatListItem` (`type:'group'`, `title`, `memberCount`, `myRole`, `peer`/`blockedByMe` = null) в `backend/src/main/kotlin/webchat/backend/chats/api/dto/` + проекция списка в `ChatService` (единый список, FR-014)
- [ ] T024 [US1] Эволюционировать №13: group-вариант `ChatView` (`type`, `title`, `description`, `myRole`, `myReadUpToSeq`, `othersReadUpToSeq`, `memberCount`) в `backend/src/main/kotlin/webchat/backend/chats/api/dto/` + `ChatService`/`ChatController` (depends T023)
- [ ] T025 [P] [US1] Создать `frontend/src/api/groups.ts`: `createGroup`/`getGroup`/`addMembers` (типы — из `schema.d.ts` 0.6.0); остальные функции контракта добавляются в US3/US4/US6 (T044a/T052a/T065a)
- [ ] T026 [P] [US1] Создать компоненты `frontend/src/groups/components/CreateGroupDialog.tsx` (название/описание + валидация 64/256) и `frontend/src/groups/components/AddMembersPicker.tsx` (мультивыбор из контактов добавляющего, FR-001/FR-002) (depends T025)
- [ ] T027 [US1] Реализовать `frontend/src/groups/hooks/useGroupRealtime.ts`: идемпотентный редьюсер событий, стартово `group.member.added` → добавить/обновить группу в «Чатах» (FR-015, контракт realtime-group-events.md §3.2)
- [ ] T028 [US1] Эволюционировать `frontend/src/chats/hooks/useChatList.ts`, `frontend/src/chats/components/ChatListItem.tsx`, `frontend/src/chats/components/ChatListPanel.tsx`: единый список direct+group (сортировка 004), аватар/название группы, memberCount, бейдж 99+, поиск по названию (FR-014)
- [ ] T029 [US1] Интегрировать вход создания в `frontend/src/chats/pages/MessengerPage.tsx`: кнопка «Создать группу» → `CreateGroupDialog`, открытие группового окна из списка (depends T026–T028)

**Checkpoint**: US1 работает и проверяется независимо: группа создаётся, участники из контактов добавляются, группа видна в «Чатах» у всех, GroupLifecycleIT (creation slice) + frontend-тесты зелёные

---

## Phase 4: User Story 2 — Переписка с гарантиями 004/005 (Priority: P1)

**Goal**: Сообщения в группах со всеми гарантиями 004/005 без изменений: realtime-доставка всем кроме отправителя, дедуп, порядок, история 50/стр, курсорная синхронизация, ✓/✓✓ по MIN-водяному знаку, флуд-лимит (FR-011, FR-012, FR-013)

**Independent Test**: Трое участников обмениваются сообщениями (включая разрывы/повторные входы): каждая запись ровно один раз, одинаковый порядок; непрочитанные сходятся; ✓✓ при прочтении всеми (US2-тест spec.md)

### Tests for User Story 2 (писать ПЕРВЫМИ)

- [ ] T030 [P] [US2] Написать `backend/src/test/kotlin/webchat/backend/GroupRealtimeIT.kt`: фанаут `message.created` всем активным кроме отправителя; дедуп по клиентскому ID (0 дублей); порядок по seq; reconnect → sync №26 без потерь; флуд 30/мин (SC-001/SC-002); заблокированная в личном диалоге пара (004) обменивается сообщениями в общей группе (edge «блокировки не действуют», Assumptions)
- [ ] T031 [P] [US2] Написать `backend/src/test/kotlin/webchat/backend/GroupReadStatusIT.kt`: ✓✓ = MIN(`last_read_seq`) активных кроме отправителя (`othersReadUpToSeq`); группа из одного — ✓✓ не выставляется; монотонность; `chat.read`-фанаут всем кроме читавшего
- [ ] T032 [P] [US2] Эволюционировать ChatList/Sync-тесты (`ChatListIT`, `SyncIT` в `backend/src/test/kotlin/webchat/backend/`): group-элементы №12/№26 (`type`/`title`/`memberCount`/`othersReadUpToSeq`, nullable peer-поля)
- [ ] T033 [P] [US2] Написать frontend-тесты: `frontend/src/chats/components/__tests__/MessageList.group.test.tsx` (атрибуция отправителей, ✓✓ по `othersReadUpToSeq` монотонно max), обработка `chat.read` в группе

### Implementation for User Story 2

- [ ] T034 [US2] Встроить membership-гейт в пути №15/№16/№17 `backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt`: для `kind='group'` — active-строка через `GroupMembershipGate` (семантика 004: `404 chat_not_found`/`403 not_participant`) (depends T015)
- [ ] T035 [US2] Эволюционировать `backend/src/main/kotlin/webchat/backend/chats/domain/service/MessageService.kt`: membership по активному составу; post-commit фанаут всем активным кроме отправителя (снимок состава в tx, FR-011); блокировки 004 — только direct; метрики фаната (T010); дедуп/admission/флуд — без изменений
- [ ] T036 [US2] Эволюционировать `backend/src/main/kotlin/webchat/backend/chats/domain/service/ReadService.kt`: №17 в группе — `chat.read` всем активным кроме читавшего; `othersReadUpToSeq` пересчитывается по активным (FR-012)
- [ ] T037 [US2] Эволюционировать `backend/src/main/kotlin/webchat/backend/sync/domain/service/SyncService.kt`: №26 отбирает только active-членства (исключённому дельты не приходят); group-дельта (`type:'group'`, `title`, `memberCount`, `othersReadUpToSeq`)
- [ ] T038 [US2] Эволюционировать `frontend/src/chats/components/MessageList.tsx`: group-вариант — атрибуция отправителей, ✓✓ по `othersReadUpToSeq` (№13/№26) + `chat.read`-кадрам, монотонно (max), история постранично (depends T033)
- [ ] T039 [US2] Эволюционировать `frontend/src/chats/hooks/useRealtime.ts`: диспетчер `chat.read` для групп (пересчёт ✓✓ по MIN), счётчик непрочитанных группы сходится после reconnect (depends T038)

**Checkpoint**: US1 и US2 независимо работоспособны: переписка в группе с гарантиями exactly-once, ✓✓ и синхронизацией; GroupRealtimeIT/GroupReadStatusIT зелёные

---

## Phase 5: User Story 3 — Роли и управление участниками (Priority: P2)

**Goal**: Owner управляет ролями (admin, передача владения), owner/admin управляют составом (исключение) с иерархией FR-003/FR-004; изменения видны всем в реальном времени (SC-004)

**Independent Test**: Owner назначает admin; admin добавляет/исключает member; admin не может исключить admin/owner; передача владения — прежний owner становится admin, ровно один owner (US3-тест spec.md)

### Tests for User Story 3 (писать ПЕРВЫМИ)

- [ ] T040 [P] [US3] Расширить `backend/src/test/kotlin/webchat/backend/GroupLifecycleIT.kt` (roles slice): №34 назначение/снятие admin (только owner); №35 передача (demote→promote, ровно один owner — инвариант индекса), передача себе → `400 self_forbidden` (edge); №32 исключение: admin исключает только member (`403 role_hierarchy_violation`), member → `403 forbidden_role`; события `group.role.changed`/`group.member.removed`; `target_not_member`/`self_forbidden`
- [ ] T041 [P] [US3] Написать frontend-тесты: `frontend/src/groups/components/__tests__/MemberList.test.tsx` (роли, видимость действий по myRole), `frontend/src/groups/hooks/__tests__/useGroupMembers.test.ts` (мьютекс конкурирующих действий), обработка `group.role.changed`/`group.member.removed`

### Implementation for User Story 3

- [ ] T042 [US3] Расширить `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupRolePolicy.kt` до полной иерархии FR-003/FR-004: owner — всё; admin — добавление, исключение только member, метаданные; роли/передача — только owner; owner-инвариант
- [ ] T043 [US3] Реализовать в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt`: `setRole` (№34: только active-цели, не себе), `transferOwnership` (№35: одна tx demote THEN promote — частичный индекс не нарушается; цель — active-участник и не сам owner → `400 self_forbidden` / `409 target_not_member`, api-contract.md §2), `kick` (№32: self-проверка (вызывающий ≠ цель) → `self_forbidden` до иерархии ролей; иерархия до UPDATE, rowcount-конкуренция, журнал `admin_granted`/`admin_revoked`/`ownership_transferred`/`member_removed`, post-commit `group.role.changed` ×2 при передаче, `group.you_removed {reason:'kicked'}` + `group.member.removed`) (depends T042)
- [ ] T044 [US3] Реализовать endpoint'ы в `backend/src/main/kotlin/webchat/backend/groups/api/GroupController.kt`: №32 `DELETE /groups/{chatId}/members/{userId}`, №34 `PUT /groups/{chatId}/members/{userId}/role`, №35 `POST /groups/{chatId}/owner` (коды — api-contract.md §2) (depends T043)
- [ ] T044a [P] [US3] Расширить `frontend/src/api/groups.ts`: `kickMember` (№32), `setMemberRole` (№34), `transferOwnership` (№35) — типы из `schema.d.ts` 0.6.0 (depends T002, T025)
- [ ] T045 [P] [US3] Создать `frontend/src/groups/components/MemberList.tsx`: состав с ролями, действия kick/admin/transfer с видимостью по `myRole` (иерархия FR-004) (depends T044a)
- [ ] T046 [US3] Создать `frontend/src/groups/components/GroupInfoPanel.tsx`: заголовок (title/члены), состав (MemberList), входы AddMembersPicker/LeaveDeleteControls (placeholder до US6) (depends T045)
- [ ] T046a [P] [US3] Создать `frontend/src/groups/hooks/useGroup.ts`: загрузка №28 `GroupView` + оптимистичные обновления метаданных/состава по событиям (`group.updated`/`group.member.added`/`group.member.removed`/`group.role.changed` через `useGroupRealtime`, план Project Structure); подключить в `GroupInfoPanel` (заголовок/состав); тест — `frontend/src/groups/hooks/__tests__/useGroup.test.ts`, пишется первым (depends T025, T027, T046)
- [ ] T047 [US3] Создать `frontend/src/groups/hooks/useGroupMembers.ts`: состав/роли по №28 + локальный мьютекс конкурирующих действий состава
- [ ] T048 [US3] Расширить `frontend/src/groups/hooks/useGroupRealtime.ts`: `group.role.changed` (два кадра передачи — идемпотентно), `group.member.removed`, `group.you_removed {reason:'kicked'}` → локальное удаление группы, последующие события игнорируются (realtime-group-events.md §1 гонка порядка) (depends T041, T047)

**Checkpoint**: US1–US3 независимо работоспособны: роли, иерархия, передача владения, realtime-отражение ≤2 с (SC-004)

---

## Phase 6: User Story 4 — Управление названием и описанием группы (Priority: P2)

**Goal**: Owner/admin изменяют метаданные (FR-007), валидация как при создании; изменения realtime у всех; member — отказ

**Independent Test**: Admin переименовывает группу — все видят новое название в «Чатах»/заголовке без перезагрузки; member получает `403` (US4-тест spec.md)

### Tests for User Story 4 (писать ПЕРВЫМИ)

- [ ] T049 [P] [US4] Расширить `backend/src/test/kotlin/webchat/backend/GroupLifecycleIT.kt` (metadata slice): №29 валидация (`empty_patch`, `invalid_title`/`invalid_description`), member → `403 forbidden_role`, одновременное переименование двух admin — атомарно применяется одна (edge), событие `group.updated`, журнал `title_changed`/`description_changed`
- [ ] T050 [P] [US4] Написать frontend-тесты: переименование в GroupInfoPanel (доступно admin+), обработчик `group.updated` (список «Чаты» + заголовок без перезагрузки)

### Implementation for User Story 4

- [ ] T051 [US4] Реализовать `update` в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt` (№29: гейт роли owner/admin, валидация FR-001, атомарный UPDATE «последняя подтверждённая», журнал, post-commit `group.updated`)
- [ ] T052 [US4] Реализовать №29 `PATCH /api/v1/groups/{chatId}` в `backend/src/main/kotlin/webchat/backend/groups/api/GroupController.kt` (depends T051)
- [ ] T052a [P] [US4] Расширить `frontend/src/api/groups.ts`: `updateGroup` (№29 PATCH) — типы из `schema.d.ts` 0.6.0 (depends T002, T025)
- [ ] T053 [US4] Реализовать UI переименования в `frontend/src/groups/components/GroupInfoPanel.tsx` + обработчик `group.updated` в `frontend/src/groups/hooks/useGroupRealtime.ts` (обновление title в списке «Чатов» и заголовке) (depends T050, T052a)

**Checkpoint**: US1–US4 независимо работоспособны: метаданные управляются и распространяются realtime

---

## Phase 7: User Story 5 — Приватность: членство на каждом запросе (Priority: P2)

**Goal**: FR-008–FR-010: каждый запрос к ресурсу группы авторизуется active-членством; единый `404 group_not_found`; после исключения/выхода доступ прекращается ≤5 с, подписка логически закрывается; каталогов/поиска групп нет

**Independent Test**: Автотесты авторизации: каждая операция × не-участник/исключённый → 100% отказов (SC-003); после исключения подписка закрывается ≤5 с (SC-006); группа не обнаруживается поиском

### Tests for User Story 5 (писать ПЕРВЫМИ)

- [ ] T054 [P] [US5] Написать `backend/src/test/kotlin/webchat/backend/GroupPrivacyIT.kt`: полная матрица «операция × не-участник/несуществующая/исключённый»: пути 004 (№15/№16/№17 с chatId группы) → `404 chat_not_found`/`403 not_participant` (семантика 004 — оговорка FR-008; api-contract.md §3); №26 sync — дельты группы не приходят не-участнику/исключённому (api-contract.md §3); №18 SSE — события группы не приходят в поток не-участника/исключённого; операции groups (№28/№29/№31/№32/№34/№35) → единый `404 group_not_found` (api-contract.md §1) (SC-003; cases №33/№30 — в US6, T059); после `you_removed` события группы не приходят ≤5 с (SC-006); метрика `webchat_group_authz_denials_total` растёт
- [ ] T055 [P] [US5] Написать frontend-тесты: после `group.you_removed`/`group.deleted` группа удаляется из «Чатов» без поллинга, последующие события группы игнорируются (идемпотентная сходимость, FR-015)

### Implementation for User Story 5

- [ ] T056 [US5] Провести гейт единообразно по операциям групп, реализованным к US5 (№28/№29/№31/№32/№34/№35; гейт №30/№33 — в US6; №27 не гейтируется — группы ещё нет), в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt` и `GroupController.kt` (через `GroupMembershipGate`, T015): проверка до различения ролей, инкремент метрики отказов (depends T015, T022, T044, T052)
- [ ] T057 [US5] Обеспечить недоступность групп для не-участников во всех путях перечисления/поиска (FR-009): (а) №26 sync — отбор только active-членств (`backend/src/main/kotlin/webchat/backend/sync/domain/service/SyncService.kt`, верификация T037); (б) №12 `GET /chats` — только чаты с active-членством вызывающего (`backend/src/main/kotlin/webchat/backend/chats/domain/service/ChatService.kt`); (в) поиск пользователей (фичи 002/003) и контакты (004) — не раскрывают групп/членств: ревизия перечислений, правки только если путь идёт не от членств. Приёмка: матрица GroupPrivacyIT (T054) зелёная по №12/№26/№18; в контракте 0.6.0 нет каталога/поиска групп
- [ ] T058 [US5] Реализовать клиентские правила приватности (realtime-group-events.md §5): после `group.you_removed`/`group.deleted` — удаление группы из состояния `frontend/src/chats/` (useChatList/useGroupRealtime), игнорирование последующих событий группы, закрытие окна группы в `frontend/src/chats/pages/MessengerPage.tsx` без «зависших» состояний (depends T055)

**Checkpoint**: US1–US5 независимо работоспособны: SC-003/SC-006 подтверждаются матрицей GroupPrivacyIT

---

## Phase 8: User Story 6 — Выход из группы и удаление группы (Priority: P3)

**Goal**: FR-005/FR-006: выход member/admin (owner — только после передачи), hard-delete группой-владельцем (CASCADE + журнал без содержимого), сообщения остаются, водяные знаки сохраняются

**Independent Test**: Member покидает группу — исчезает из состава, теряет доступ, сообщения остаются; owner передаёт владение и выходит; owner удаляет группу — чат исчезает у всех (US6-тест spec.md)

### Tests for User Story 6 (писать ПЕРВЫМИ)

- [ ] T059 [P] [US6] Расширить `backend/src/test/kotlin/webchat/backend/GroupLifecycleIT.kt` (leave/delete slice): №33 owner → `403 owner_must_transfer`, после передачи — выход штатно; сообщения вышедшего остаются с атрибуцией; №30 hard-delete: `chats`/`messages`/`chat_participants` пусты (SQL-проверка), бывшим — `group.deleted`; расширить матрицу `GroupPrivacyIT.kt` (замыкание SC-003): №33/№30 × не-участник/несуществующая/исключённый → `404 group_not_found`; повторные №33/№30 → `404`
- [ ] T060 [P] [US6] Написать `backend/src/test/kotlin/webchat/backend/GroupAdminLogIT.kt`: хронология журнала соответствует сценариям (10 действий); записи переживают hard-delete (`group_deleted` без содержимого, FR-006/FR-017)
- [ ] T061 [P] [US6] Расширить тесты граничных условий: `backend/src/test/kotlin/webchat/backend/GroupReadStatusIT.kt` — повторное добавление: водяной знак сохранён, счётчик за период отсутствия (FR-002), непрочитавший вышел — ✓✓ достижимо; `backend/src/test/kotlin/webchat/backend/GroupLimitsIT.kt` — конкуренция «исключение×выход» сходится без дублей (edge)
- [ ] T062 [P] [US6] Написать frontend-тесты: `frontend/src/groups/components/__tests__/LeaveDeleteControls.test.tsx` (видимость по роли, подсказки ошибок), обработка `group.deleted` (удаление из «Чатов», закрытие окон — edge)

### Implementation for User Story 6

- [ ] T063 [US6] Реализовать `leave` в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt` (№33: owner → `403 owner_must_transfer`; единый UPDATE state='removed' role='member', rowcount-идемпотентность конкуренции; журнал `member_left`; post-commit `group.you_removed {reason:'left'}` + `group.member.removed` остальным)
- [ ] T064 [US6] Реализовать `delete` в `backend/src/main/kotlin/webchat/backend/groups/domain/service/GroupService.kt` (№30: только owner; снимок активных до удаления; hard-delete tx — CASCADE по FK; журнал `group_deleted` без содержимого; post-commit `group.deleted` всем бывшим) (depends T012, T013)
- [ ] T065 [US6] Реализовать endpoint'ы в `backend/src/main/kotlin/webchat/backend/groups/api/GroupController.kt`: №33 `DELETE /api/v1/groups/{chatId}/membership`, №30 `DELETE /api/v1/groups/{chatId}` (повторно → `404`) (depends T063, T064)
- [ ] T065a [P] [US6] Расширить `frontend/src/api/groups.ts`: `leaveGroup` (№33), `deleteGroup` (№30) — типы из `schema.d.ts` 0.6.0 (depends T002, T025)
- [ ] T066 [US6] Создать `frontend/src/groups/components/LeaveDeleteControls.tsx` (выход для не-owner, удаление для owner, подсказки `owner_must_transfer`) + обработчик `group.deleted` в `frontend/src/groups/hooks/useGroupRealtime.ts` + закрытие окон в `frontend/src/chats/pages/MessengerPage.tsx` (depends T062, T065a)

**Checkpoint**: Все user stories (US1–US6) независимо работоспособны; полный жизненный цикл группы реализован

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: Нагрузочные, наблюдаемость, сквозная валидация

- [ ] T067 [P] Создать `load/k6/group-chats.js` (docker build -t webchat-k6 load/k6): сценарии A — параллельно `K6_VUS` (default 10) VU: каждый создаёт группу и добавляет участников, p90 полного цикла ≤60 с (SC-005); B — переписка p95 «доставлено» ≤2 с (SC-001); C — изменения состава/ролей/метаданных ≤2 с (SC-004); D — authz-отказы (SC-003); E — smoke 200 участников (`K6_GROUP_SIZE=200`): 0 дублей, порядок seq, `you_removed` ≤5 с (SC-006/SC-007); env `K6_BASE_URL`/`K6_MAILPIT_URL`/`K6_MESSAGE_RPS`/`K6_VUS`
- [ ] T068 [P] Валидация наблюдаемости (FR-017/SC-008, quickstart §3.8): `group_admin_log` хронология по группе; `http://localhost:8080/actuator/prometheus` — `webchat_group_authz_denials_total`, `webchat_group_message_fanout_total`/`_seconds`, `webchat_group_size` растут в сценариях
- [ ] T069 Прогнать полный backend-цикл: `./gradlew check` (workdir `backend/`) — все IT зелёные (GroupLifecycleIT, GroupPrivacyIT, GroupReadStatusIT, GroupRealtimeIT, GroupLimitsIT, GroupAdminLogIT, эволюция ChatList/Sync)
- [ ] T070 [P] Прогнать frontend-цикл: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck` — зелёно
- [ ] T071 Финальная сквозная валидация по `specs/006-group-chats/quickstart.md` (§3.1–§3.9 вручную/E2E) + контрактный конвейер (`vacuum`/`generate:api` drift/`oasdiff breaking`) — все критерии приёмки подтверждены

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно (T001 → T002 последовательно; T003/T004 параллельно)
- **Foundational (Phase 2)**: зависит от Phase 1 — БЛОКИРУЕТ все stories
- **User Stories (Phases 3–8)**: все зависят от Phase 2; далее — в порядке приоритета P1 → P2 → P3 (US1 → US2 → US3 → US4 → US5 → US6); независимы в проверке, но реализуются последовательно одним агентом (конституция, Workflow)
- **Polish (Phase 9)**: зависит от завершения всех stories

### User Story Dependencies

- **US1 (P1)**: после Phase 2; MVP — не зависит от других stories (гейт сообщений для №15–№17 вводится в US2, поэтому окно группы в US1 открывается, отправка до US2 может отдавать 404/403 для kind='group' — задокументировано)
- **US2 (P1)**: после Phase 2; использует результаты US1 для E2E-сценариев, но тесты/реализация (пути 004/005) независимы
- **US3 (P2)**: после Phase 2; расширяет GroupRolePolicy/GroupService/GroupController (файлы US1) и useGroupRealtime
- **US4 (P2)**: после Phase 2; №29 независим от US3 (гейт роли owner/admin), UI GroupInfoPanel доопределяется после US3
- **US5 (P2)**: после Phase 2; верификационная матрица поверх операций US1/US3/US4 (поэтому реализуется после них)
- **US6 (P3)**: после Phase 2; №33/№30 независимы, но E2E-сценарии используют передачу владения US3

### Within Each User Story

- Тесты ПЕРВЫМИ (красные) → реализация → зелёные (конституция VI)
- Порты/модели → сервисы → контроллеры; хуки/компоненты → интеграция в страницы
- Story завершена (checkpoint) → следующая

### Parallel Opportunities

- Phase 1: T003 ∥ T004 ∥ (T001 → T002)
- Phase 2: T005–T010 и T011–T016 — взаимно параллельные группы (разные файлы)
- Внутри story: backend-тесты ∥ frontend-тесты; backend-реализация ∥ frontend-компоненты (разные деревья)
- Разные stories — разными исполнителями после Phase 2 (при наличии команды)

---

## Parallel Example: User Story 1

```bash
# Тесты параллельно (разные деревья):
Task T018: "GroupLifecycleIT (creation slice) в backend/src/test/kotlin/webchat/backend/GroupLifecycleIT.kt"
Task T019: "CreateGroupDialog/ChatListItem тесты в frontend/src/**/__tests__/"

# Реализация параллельно (backend ∥ frontend):
Task T021–T024: "GroupService/GroupController/№12/№13 (backend)"
Task T025–T026: "api/groups.ts + CreateGroupDialog/AddMembersPicker (frontend)"

# Затем последовательная интеграция: T027 → T028 → T029
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Phase 1: Setup (контракт 0.6.0 + V14 + GroupsProperties)
2. Phase 2: Foundational (модели/порты/адаптеры/гейт/метрики)
3. Phase 3: US1 — создание группы, участники, видимость в «Чатах»
4. **STOP и VALIDATE**: US1-тест независимо (GroupLifecycleIT creation slice + frontend-тесты + quickstart §3.1)
5. Demo: группа создаётся и видна участникам

### Incremental Delivery

1. Setup + Foundational → фундамент
2. + US1 → независимая проверка → Demo (MVP)
3. + US2 (P1) → переписка с гарантиями 004/005 → quickstart §3.2
4. + US3 (P2) → роли/управление → §3.3
5. + US4 (P2) → метаданные → §3.4
6. + US5 (P2) → приватность (SC-003/SC-006) → §3.5
7. + US6 (P3) → выход/hard-delete → §3.6–§3.7
8. Polish → k6/наблюдаемость/сквозная валидация → §3.8–§3.9

### Parallel Team Strategy

1. Команда вместе завершает Phase 1–2
2. После Phase 2: Developer A — US1→US2 (backend-пути), Developer B — US3→US4, Developer C — US5-верификация → US6; frontend/backend внутри story — параллельно
3. Интеграция по checkpoints stories

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач; один агент реализует ПОСЛЕДОВАТЕЛЬНО (конституция, Development Workflow)
- Метка [US*] трассирует задачу к story spec.md для приёмки
- Тесты в каждой story пишутся первыми и обязаны быть зелёными до checkpoint (DoD: реализация + зелёные тесты + lint + обновлённый API-контракт, если затронут — Phase 1, + пройденный `/speckit.checklist` — конституция VI / Quality Gates)
- Коммит после каждой задачи/логической группы; статус отмечается в tasks.md
- События группы НЕ пишутся в историю (FR-015); неизвестные `event:`-типы клиент игнорирует
- Лимиты контракта: 200 участников / название 1–64 / описание ≤256 / страница 50 / флуд 30-мин / окно прекращения доставки ≤5 с
- Все пути backend — от корня `backend/src/main/kotlin/webchat/backend/`; frontend — от `frontend/src/`
