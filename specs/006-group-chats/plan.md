# Implementation Plan: Групповые чаты с ролями и member-авторизацией

**Branch**: `006-group-chats` | **Date**: 2026-09-25 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/006-group-chats/spec.md`

## Summary

Групповые чаты надстраиваются над ядром 004/005 **переиспользованием их хранилища и путей доставки**:
единая таблица `chats` получает дискриминатор `kind ('direct'|'group')` и поля `title`/`description`
(миграция **V14**), членство и роли — на существующей `chat_participants` (`role` + `state`), сообщения —
без изменений в `messages` (per-chat `seq`, дедуп по клиентскому ID, флуд-лимит, backpressure). Все
механизмы 005 (курсоры, ack, sync, счётчики, outbox) работают в группах автоматически, т.к. группа —
это строка `chats` со строками участников. Роль owner гарантируется частичным уникальным индексом
(ровно один `role='owner'` на группу); членство — активная строка участника, проверяемая на каждом
запросе к ресурсу группы (FR-008). Выход/исключение — `state='removed'` с **сохранением водяных
знаков** (семантика повторного добавления FR-002); при первом добавлении водяной знак
инициализируется `chats.last_seq` (бейдж с 0, FR-013). «Прочитано» (✓✓) — `MIN(last_read_seq)`
активных участников кроме отправителя (`othersReadUpToSeq`); вышедшие исключаются из условия
натурально. Realtime-фанаут — по существующим per-user каналам `rt:user:{userId}`: снимок активных
участников берётся в транзакции операции, публикация — post-commit; исключённый получает финальное
событие `group.you_removed`, «закрытие подписки» группы логическое (поток пользователя не рвётся —
он мультиплексирует все чаты; события группы больше не приходят). Управление — новый REST-блок
`/groups` (операции №27–№35, additive minor **0.6.0**), ошибки приватности — единый `404
group_not_found` для чужих и несуществующих. Hard-delete группы — `DELETE … CASCADE` по существующим
FK + журнал `group_admin_log` без FK (переживает удаление). Наблюдаемость — журнал + метрики
(authz-отказы, размеры групп, доставка). Нагрузочная валидация — расчёт усиления фанаута (≤200 ног
на сообщение, документированные рычаги) + k6 smoke `load/k6/group-chats.js`; полнообъёмные прогоны —
платформенная фича 016 (прецедент 004/005).

Детальные решения с обоснованием — [research.md](./research.md).

## Technical Context

*Исходные неизвестные (модель хранения групп; представление членства/ролей и сохранение водяных
знаков; семантика «прочитано» в группе; фанаут realtime и «закрытие подписки» ≤5 с; влияние на пути
№12–№18/№25/№26; hard-delete + журнал; набор операций API и коды ошибок; наблюдаемость; нагрузочный
расчёт SC-007; структура фронтенда) разрешены в Phase 0 — [research.md](./research.md).*

**Language/Version**: без изменений относительно 001–005: Kotlin 2.2.x + JDK 21 (backend),
TypeScript 5.x strict (frontend), Node 24 + pnpm.

**Primary Dependencies** (новых внешних зависимостей нет; версии — из BOM Spring Boot):
- Backend: переиспользуются `spring-boot-starter-web`/`-security` + `oauth2-resource-server`,
  `spring-boot-starter-jdbc` + Flyway (PostgreSQL 17), Micrometer; SSE/Redis Pub/Sub — транспорт
  004 (`SseConnectionRegistry`, `RedisRealtimePublisher`) без замены.
- Frontend: без новых runtime-зависимостей — fetch-слой (`api/client.ts`, `api/sse.ts`),
  localStorage; типы — из codegen (`pnpm --dir frontend generate:api`).

**Storage**: **PostgreSQL 17** — миграция **V14__group_chats.sql**: `chats` + `kind`/`title`/
`description` (пара `user_low_id/user_high_id` становится nullable для групп; shape-CHECK; парный
UNIQUE не мешает — NULLs distinct), `chat_participants` + `role` (NULL у личных) / `state`
('active'|'removed'), частичный уникальный индекс владельца, частичный индекс активного состава;
новая таблица `group_admin_log` (журнал административных операций, FR-017, без FK на `chats` —
переживает hard-delete). **Redis 7** — без новых ключей/каналов (фанаут групп — публикации в
существующие `rt:user:{userId}`). Клиент — без новых хранилищ (контакт-пикер, кэш состава — в
памяти; курсоры/outbox 005 не меняются).

**Testing**: Backend — JUnit 5 + Testcontainers (PG+Redis): `GroupLifecycleIT` (создание/состав/
роли/передача/выход/удаление), `GroupPrivacyIT` (матрица «каждая операция × не-участник/исключённый»
— SC-003), `GroupReadStatusIT` (✓✓ по MIN-водяному знаку, выход из условия, повторное/первое
добавление), `GroupRealtimeIT` (фанаут, финальное `you_removed`, отсутствие событий после),
`GroupLimitsIT` (200/64/256, идемпотентность добавления, конкуренция исключение×выход),
`GroupAdminLogIT` (журнал, переживание удаления). Frontend — Vitest + Testing Library: groups-хуки,
идемпотентная обработка событий, единый список «Чаты» (type-дискриминация, поиск по названию).
Контракт — конвейер 001 (additive minor 0.6.0). Нагрузочные — k6 `load/k6/group-chats.js`
(создание+состав+фанаут+SC-001/SC-004; масштаб — smoke, SC-007).

**Target Platform**: без изменений: Linux-контейнеры в K8s dev (stateless-поды; новое состояние —
только PG/миграция V14).

**Project Type**: web-service (монорепозиторий 001, структура сохраняется).

**Performance Goals** (спека): SC-001 — 95% сообщений групп «доставлено» ≤ 2 с; SC-002 — 0
дублей/0 потерь при разрывах и повторных входах (автотесты доставки); SC-003 — 100% запросов
не-участников отклонены (автотесты по каждой операции); SC-004 — 95% изменений состава/ролей/
метаданных видимы онлайн-участникам ≤ 2 с; SC-005 — p90 сценария «создание
группы + добавление ≤10 участников» ≤ 60 с (k6-сценарий A); SC-006 — подписки исключённого закрыты ≤ 5 с, запросы отклоняются (100%);
SC-007 — группы до 200 участников в конституционных бюджетах (расчёт ниже + k6 smoke; полнообъёмный
прогон — 016); SC-008 — журнал админ-операций + метрики наблюдаемости.

**Constraints**: авторизация по ресурсу на КАЖДОМ запросе (FR-008, конституция V) — active-строка
участника; приватность — единый `404` для чужих/несуществующих, каталогов/поиска групп нет (FR-009);
механизмы 004/005 — без изменений (FR-011: `seq`, дедуп по ID, флуд 30/мин, admission-порядок,
курсоры/ack/sync, 50 на страницу); «прочитано» монотонно, не сбрасывается новыми участниками (FR-012);
водяной знак первого добавления = позиция группы (FR-013); события группы не пишутся в историю
(FR-015, модель 004 не расширяется системными сообщениями); блокировки 004 не действуют в группах
(Assumptions); лимиты 200/64/256/5 с фиксируются в контракте; hard-delete (FR-006) — единственное
долговечное удаление, журнал хранит факт без содержимого; additive-совместимость 0.5.0 → 0.6.0
(конституция IV); фанаут группы ≤ 200 ног (расчёт — Complexity Tracking/SC-007).

**Scale/Scope**: 9 новых REST-операций (№27–№35, tag `groups`) + 6 новых SSE-событий; 1 миграция PG
(V14: 2 эволюции таблиц + 1 новая таблица `group_admin_log` + 3 индекса: 2 частичных + индекс журнала); 0 новых
Redis-ключей; frontend — новый модуль `src/groups/` (создание, инфо-панель с ролями, пикер из
контактов) + эволюция `chats/` (type-aware список/окно, обработка событий) и `api/groups.ts`;
1 новый k6-сценарий; журнал + 3 группы метрик (FR-017).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Статус | Комментарий |
|---|---------|--------|-------------|
| I | Spec-Driven (NON-NEGOTIABLE) | ✅ PASS | Спека утверждена (раунд clarify зафиксирован); план — этот документ; `tasks.md` — следующий шаг (`/speckit.tasks`). Код только по задачам. |
| II | Масштабируемость / stateless (NON-NEGOTIABLE) | ✅ PASS | Новое долговечное состояние — только PG (V14): колонки дискриминатора/ролей + журнал; in-process состояния НЕ добавляется, поды остаются stateless. Путь отправки получает ≤200-строчный read состава (PK-range) и ≤199 post-commit Redis-публикаций — расчёт усиления и рычаги (групповой канал `rt:chat:{id}` как точка расширения) — [research.md §4](./research.md); k6 smoke + закрепление полнообъёмного прогона за 016 (прецедент 004/005, Complexity Tracking). |
| III | Доставка без дублей (NON-NEGOTIABLE) | ✅ PASS | Пути 004/005 не меняются: клиентский ID + дедуп-фастпас, per-chat `seq`, GREATEST-водяные знаки, курсоры/ack/sync — группа получает их автоматически (единое хранилище). События группы — at-most-once канал + идемпотентная обработка клиента (FR-015); тесты дубли/порядка/потерь расширены на группы (`GroupRealtimeIT`, k6). |
| IV | API-First и встраиваемость | ✅ PASS | Сначала `contracts/openapi.yaml` (additive minor 0.5.0 → 0.6.0: №27–№35, group-поля №12/№13/№26, события №18, схемы `Group*`); TS-типы — codegen; drift/breaking-детекция CI; протокол событий нормативно описан в [contracts/realtime-group-events.md](./contracts/realtime-group-events.md) на основе OpenAPI-схем. Неизвестные `event:`-типы игнорируются (прямая совместимость 004). |
| V | Безопасность и приватность | ✅ PASS | FR-008/FR-009 — ядро фичи: active-membership на каждом запросе (порт `GroupMembershipGate`), единый `404 group_not_found` (не раскрывает существование), публичного каталога нет, метаданные/состав/события — только участникам; SSE-аутентификация Bearer (без изменений 004); журнал без содержимого сообщений; логи без PII-текста (прецедент 004). |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | Тесты в каждой задаче (DoD): IT-матрица приватности по каждой операции (SC-003), IT доставки/статусов, frontend-тесты событий/списка, контрактный конвейер, k6 — группы в бюджетах (SC-007). |
| VII | Простота (YAGNI) | ✅ PASS | Минимально: переиспользование `chats`/`chat_participants`/`messages` вместо новых таблиц (одна миграция); фанаут по существующим per-user каналам вместо нового брокера/групповых каналов; state-колонка вместо таблицы истории членств; приглашения по ссылке/публичные группы/аватары/«только админы пишут» — вне объёма (Assumptions); системные сообщения не вводятся (FR-015). Отклонённые альтернативы — [research.md §1, §2, §4](./research.md). |
| VIII | SOLID на уровне кода | ✅ PASS | SRP: новый пакет `groups/` (жизненный цикл/состав/роли) отделён от `chats/` (сообщения) и `contacts/` (гейт контактов — порт); DIP: домен зависит от портов (`GroupRepository`, `GroupAdminLogRepository`, `RealtimeEventPublisher`); эволюция `chats` — точечная (membership-гейт, group-поля DTO); без спекулятивных абстракций (VII). |

**Нарушений нет** — таблица Complexity Tracking не заполняется (примечание о нагрузочной валидации
бюджетов — в конце документа, перед Re-check).

**Re-check после Phase 1 (дизайн)**: см. конец документа.

## Project Structure

### Documentation (this feature)

```text
specs/006-group-chats/
├── plan.md                       # This file (/speckit.plan command output)
├── research.md                   # Phase 0 output (/speckit.plan command)
├── data-model.md                 # Phase 1 output (/speckit.plan command)
├── quickstart.md                 # Phase 1 output (/speckit.plan command)
├── contracts/
│   ├── api-contract.md           # Phase 1: операции №27–№35 + additive-изменения REST
│   └── realtime-group-events.md  # Phase 1: события группы на канале №18, семантика доставки
└── tasks.md                      # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/webchat/backend/
├── BackendApplication.kt                     # без изменений
├── config/
│   ├── SecurityConfig.kt                     # без изменений (anyRequest().authenticated())
│   └── GroupsProperties.kt                   # NEW @ConfigurationProperties("groups.*"):
│                                               #   max-members=200, title-max-length=64,
│                                               #   description-max-length=256 (константы контракта)
├── groups/                                   # NEW: жизненный цикл группы, состав, роли
│   ├── GroupMetrics.kt                       # NEW: метрики FR-017 (authz-отказы, fanout, размер групп)
│   ├── api/
│   │   ├── GroupController.kt                # №27–№35 /api/v1/groups…
│   │   ├── GroupsExceptionHandler.kt         # problem+json: group_not_found/forbidden_role/
│   │   │                                     #   role_hierarchy_violation/not_in_contacts/group_full/…
│   │   └── dto/                              # CreateGroupRequest/GroupView/GroupMember/
│   │                                         #   AddMembersRequest/SetRoleRequest/TransferRequest
│   ├── domain/
│   │   ├── model/GroupChat.kt                # kind-проекция chats (title/description/lastSeq);
│   │   │                                     #   GroupTitle/GroupDescription (валидация FR-001)
│   │   │                                     #   MemberRole (owner/admin/member), MembershipState
│   │   ├── port/
│   │   │   ├── GroupRepository.kt            # create/get/update/delete(hard), состав, MIN-водяные
│   │   │   │                                 #   знаки, countActive (FOR UPDATE сериализация добавлений)
│   │   │   ├── GroupAdminLogRepository.kt    # append-only журнал (FR-017)
│   │   │   └── GroupEventPublisher.kt        # доменные события группы (адаптер — realtime)
│   │   └── service/
│   │       ├── GroupService.kt               # сценарии US1/US3/US4/US6; идемпотентность добавления,
│   │       │                                 #   инициализация водяных знаков, transfer (demote→promote)
│   │       └── GroupRolePolicy.kt            # иерархия FR-003/FR-004: кто кого добавляет/исключает/
│   │                                         #   назначает; owner-инвариант
│   └── repository/
│       ├── JdbcGroupRepository.kt            # миграции-совместимые запросы, tx-границы
│       └── JdbcGroupAdminLogRepository.kt
├── chats/                                    # эволюция 004 (точечно)
│   ├── api/
│   │   ├── ChatController.kt                 # №12/№13: group-варианты ответов (type-дискриминация)
│   │   └── dto/                              # ChatListItem/ChatView + type/title/memberCount/
│   │                                         #   myRole/othersReadUpToSeq (optional/nullable)
│   ├── domain/
│   │   ├── model/Chat.kt                     # +kind/title/description; involves() только для direct
│   │   ├── model/ChatParticipant.kt          # +role/state
│   │   ├── port/ParticipantRepository.kt     # +findActive(chatId,userId), activeMembers(chatId),
│   │   │                                     #   addMember/reactivate (init водяных знаков),
│   │   │                                     #   removeMember, minOtherReadUpToSeq
│   │   ├── port/RealtimeEventPublisher.kt    # +group-события и message.fanout(list)
│   │   └── service/
│   │       ├── ChatService.kt                # membership-гейт: active-строка для kind='group'
│   │       ├── MessageService.kt             # gate по составу; fanout всем активным, кроме
│   │       │                                 #   отправителя (FR-011); блокировки — только direct
│   │       └── ReadService.kt                # №17 в группе: событие всем активным, кроме читавшего
│   └── repository/JdbcParticipantRepository.kt
├── realtime/
│   ├── RealtimeController.kt                 # без изменений (поток на пользователя)
│   ├── SseConnectionRegistry.kt              # без изменений
│   └── RedisRealtimePublisher.kt             # +публикация group-событий (список адресатов) —
│                                               #   те же rt:user:{id}, конверт realtime-канала 004
└── sync/
    └── domain/service/SyncService.kt         # №26: только active-членства в отборе, group-поля
                                                #   дельты (title, othersReadUpToSeq вместо peer*)

backend/src/main/resources/
├── db/migration/
│   └── V14__group_chats.sql                  # chats+kind/title/description (nullable пара, shape-CHECK),
│                                               #   chat_participants+role/state (+частичный uniq owner,
│                                               #   частичный индекс активного состава), group_admin_log
└── application.yml                           # +groups.* (лимиты контракта)

backend/src/test/kotlin/webchat/backend/      # GroupLifecycleIT, GroupPrivacyIT, GroupReadStatusIT,
                                                #   GroupRealtimeIT, GroupLimitsIT, GroupAdminLogIT,
                                                #   GroupRepositoryIT + unit-срезы Phase 2
                                                #   (GroupChatModelTest, GroupMetricsTest,
                                                #   GroupMembershipGateTest/GroupsExceptionHandlerTest),
                                                #   эволюция ChatListIT/SyncIT (group-элементы)

frontend/src/
├── api/
│   ├── schema.d.ts                           # GENERATED (regen из контракта 0.6.0)
│   ├── groups.ts                             # NEW: create/get/patch/delete/addMembers/kick/leave/
│   │                                         #   setRole/transferOwner
│   └── chats.ts                              # без изменений (type-поля приходят из схем)
├── groups/                                   # NEW
│   ├── components/
│   │   ├── CreateGroupDialog.tsx             # название/описание + выбор контактов (FR-001/FR-002)
│   │   ├── GroupInfoPanel.tsx                # заголовок: title/members; переименование (admin+)
│   │   ├── MemberList.tsx                    # состав с ролями; действия по иерархии (kick/admin/
│   │   │                                     #   transfer) — видимость по myRole
│   │   ├── AddMembersPicker.tsx              # из контактов добавляющего, мультивыбор
│   │   └── LeaveDeleteControls.tsx           # выход (не-owner), удаление (owner), подсказки ошибок
│   └── hooks/
│       ├── useGroup.ts                       # №28 + оптимистичные обновления по событиям
│       ├── useGroupMembers.ts                # состав/роли, локальный мьютекс конкурирующих действий
│       └── useGroupRealtime.ts               # редьюсер событий группы (идемпотентный, FR-015):
│                                               #   group.updated/group.member.added/
│                                               #   group.member.removed/group.role.changed/
│                                               #   group.you_removed/group.deleted → состояние
│                                               #   списка «Чаты»
├── chats/
│   ├── components/
│   │   ├── ChatListItem.tsx                  # type-aware: аватар/название группы, memberCount,
│   │   │                                     #   бейдж непрочитанных (99+), поиск по названию (FR-014)
│   │   ├── ChatListPanel.tsx                 # единый список direct+group (сортировка 004)
│   │   ├── MessageList.tsx                   # group-вариант: атрибуция отправителей, ✓✓ по
│   │   │                                     #   othersReadUpToSeq (FR-012)
│   │   └── MessageInput.tsx                  # без изменений (chatId-агностичен)
│   ├── hooks/
│   │   ├── useChatList.ts                    # group-элементы + события состава/удаления
│   │   └── useRealtime.ts                    # диспетчер новых event:-типов → useGroupRealtime
│   └── pages/MessengerPage.tsx               # вход создания группы, открытие GroupInfoPanel
└── App.tsx                                   # без изменений маршрутов

contracts/openapi.yaml                        # 0.5.0 → 0.6.0 (additive): №27–№35, group-поля
                                                #   №12/№13/№26, события №18, схемы Group*/

load/k6/group-chats.js                        # NEW: создание+участники+переписка (SC-001/SC-005),
                                                #   изменения состава/метаданных (SC-004), authz-отказы,
                                                #   200-участниковый smoke (SC-007)
```

**Structure Decision**: структура 001–005 сохранена (плоский монорепозиторий `backend/` +
`frontend/` + `contracts/`). Backend — package-by-feature: новый пакет `groups/` (жизненный цикл,
состав, роли, журнал); `chats/` эволюционирует точечно (membership-гейт для kind='group',
group-поля DTO, fanout-ноги); `realtime/` получает только новые типы событий на существующем
транспорте; `sync/` — фильтр active-членств и group-поля дельты. Frontend — новый модуль `src/groups/`
(диалог создания, инфо-панель, состав, события) и точечная эволюция `chats/` (type-aware список/окно).
Порты (`GroupRepository`, `GroupAdminLogRepository`, расширение `ParticipantRepository`/
`RealtimeEventPublisher`) — в `domain/port`, адаптеры — снаружи (DIP, VIII).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица пуста. Документированное ограничение (не нарушение):
фанаут группового сообщения порождает до 199 post-commit публикаций Redis Pub/Sub (лимит участников
200); в пределе «все 100k msg/s — сообщения групп по 200 участников» усиление достигает ~20M
публикаций/с, что превышает одиночный Redis-узел pub/sub. Обоснование принятого решения:
(а) лимит 30 сообщений/мин на пользователя (004) ограничивает интенсивность на группу сверху
(участники × 30/мин), и реалистический микс (средний размер группы ≪ 200) держит усиление в
бюджете — расчёт в [research.md §4](./research.md); (б) архитектурная точка расширения зафиксирована
— групповой канал `rt:chat:{chatId}` (1 публикация на сообщение, релеи инстансов по локальным
участникам) вводится без изменения публичного контракта (SSE-кадры те же); (в) k6-сценарий
`load/k6/group-chats.js` валидирует smoke-масштаб (включая 200-участниковую группу), полнообъёмный
прогон пиковых бюджетов закреплён за платформенной фичей 016 (ROADMAP; прецедент — plan.md
004/005 Constraints). Дополнительное усиление — `chat.read`-события каждому участнику при прочтении:
оптимизация (публикация только отправителям непрочитанных хвостов) отклонена по YAGNI, зафиксирована
как рычаг в research.md §4.

**Re-check после Phase 1 (дизайн)**: data-model.md не вводит in-process бизнес-состояния — всё новое
долговечное состояние в PG (V14: колонки `chats`/`chat_participants` + `group_admin_log` без FK),
эфемерное — без изменений (Redis `rt:user:{id}`/`rl:*`); частичный уникальный индекс владельца
гарантирует инвариант «ровно один owner» на уровне хранилища; water-mark-семантика повторного
добавления — `state`-колонка (выход/исключение ≠ удаление строки). contracts/api-contract.md +
contracts/realtime-group-events.md — additive к публичному контракту (minor 0.6.0): новые №27–№35,
optional-поля `type`/`title`/`memberCount`/`myRole`/`othersReadUpToSeq` у №12/№13/№26, новые
`event:`-типы №18 (неизвестные игнорируются — прямая совместимость 004); nullable-переход `peer`/`blockedByMe` у №12 и `peer`/`blockedByMe`/`peerReadUpToSeq` у №13/№26 — единственное формальное изменение обязательности полей, обосновано и
помечено в research.md §5 / api-contract.md §4 (для существующих потребителей semver-minor-совместимо: старые клиенты
групп не запрашивают). quickstart.md проверяем без ручных правок артефактов; наблюдаемость — журнал +
3 группы метрик Micrometer (FR-017), tracing наследуется от 001. Выводы таблицы не меняются —
**все гейты PASS**.
