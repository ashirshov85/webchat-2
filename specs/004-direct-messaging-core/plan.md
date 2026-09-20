# Implementation Plan: Личный текстовый диалог в реальном времени с exactly-once доставкой

**Branch**: `004-direct-messaging-core` | **Date**: 2026-09-20 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/004-direct-messaging-core/spec.md`

## Summary

Ядро продукта: личный диалог «один на один» с обменом текстовыми сообщениями в реальном
времени и эффектом exactly-once для пользователя. Клиент генерирует UUID сообщения;
сервер дедуплицирует по нему (`INSERT … ON CONFLICT` по PK), подтверждает запись и
назначает серверный `seq` (bigserial; монотонность и уникальность — только в рамках чата,
глобальная монотонность контрактом не является и потому не мешает шардированию по `chat_id`) —
порядок стабилен для обоих участников
и служит курсором пагинации истории (50 сообщений на страницу). Статусы: «отправляется»
(локально до подтверждения) → «доставлено» (записано сервером, одна галочка) → «прочитано»
(водяной знак `last_read_seq` получателя, двойная галочка; события прочтения идемпотентны
и монотонны по построению — GREATEST-обновление).

Реалтайм-канал — **SSE-поток событий пользователя** `GET /api/v1/users/me/events`
(server→client; отправка — только REST с идемпотентностью), полностью описанный в
публичном OpenAPI-контракте (FR-022, US6). Фанаут между инстансами stateless-бэкенда —
**Redis Pub/Sub** (канал на пользователя); пропущенные при обрыве события восполняются
рефетчем после переподключения (полный курсорный delta-sync — фича 005, ROADMAP).

Новое состояние в PostgreSQL 17 (Flyway V10–V11): `chats` (каноническая пара
user_low/user_high — ровно один диалог на пару), `chat_participants` (per-user состояние:
`last_read_seq`, `deleted_up_to_seq`-водяной знак удаления истории, флаг скрытия),
`messages` (PK = клиентский UUID, `seq` bigserial, видимость per-user через водяной знак),
`user_contacts`, `user_blocks`. Блокировка: запрет отправки в обе стороны с различимыми
ошибками, заморозка прочтений и бейджа (натурально: сообщений нет — счётчик не растёт,
чтения подавлены — не сбрасывается). Флуд-лимит 30 сообщений/мин — Bucket4j + Redis
(`rl:user:msgsend:*`, переиспользуется семейство 002). Frontend: мессенджер (панель
«Чаты»/«Контакты», галочки, бейджи «99+», блок-метки) + локальный outbox неподтверждённых
сообщений в localStorage с автоповторами тем же ID, индикацией задержки флуд-лимита и
терминальным статусом «не отправлено» (ручной повтор / локальное удаление).

Детальные решения с обоснованием и расчётом нагрузки — [research.md](./research.md).

## Technical Context

*Исходные неизвестные (реалтайм-транспорт, фанаут между инстансами, механизм порядка
сообщений, per-user удаление истории, модель прочтений, область дедупликации, интеграция
флуд-лимита, аутентификация SSE, формат описания реалтайма в OpenAPI, клиентский outbox)
разрешены в Phase 0 — [research.md](./research.md).*

**Language/Version**: без изменений относительно 001–003: Kotlin 2.2.x + JDK 21 (backend),
TypeScript 5.x strict (frontend), Node 24 + pnpm.

**Primary Dependencies** (новых внешних зависимостей нет; версии — из BOM Spring Boot):
- Backend: переиспользуются `spring-boot-starter-web` (SSE через Servlet-async /
  `SseEmitter`), `spring-boot-starter-security` + `oauth2-resource-server` (Bearer),
  `spring-boot-starter-jdbc` + Flyway (PostgreSQL), `spring-boot-starter-data-redis`
  (Lettuce pub/sub + Bucket4j уже в classpath). Отдельный websocket/stomp-стартер НЕ
  вводится (VII, [research.md §1](./research.md)).
- Frontend: без новых runtime-зависимостей — SSE-клиент на `fetch` + `ReadableStream`
  (нативный `EventSource` не умеет `Authorization`-заголовок, токен в URL запрещён — V;
  [research.md §9](./research.md)); типы — из codegen.

**Storage**: **PostgreSQL 17** (долговечное: `chats`, `chat_participants`, `messages`,
`user_contacts`, `user_blocks`; Flyway V10–V11) + **Redis 7** (эфемерное: бакеты флуд-лимита
`rl:user:msgsend:*`; Pub/Sub-каналы `rt:user:{userId}` — не персистентны, источник истины —
PG, [research.md §2](./research.md)).

**Testing**: Backend — JUnit 5 + Testcontainers (PG+Redis, `@ServiceConnection`):
дедупликация/порядок/пагинация, прочтения (идемпотентность/монотонность), блокировки,
контакты/поиск, удаление чата, флуд-лимит, SSE-поток (WebTestClient), membership-отказы
(FR-002). Frontend — Vitest + Testing Library: outbox (ретраи тем же ID, флуд-отсрочка,
терминальный статус), галочки, панель «Чаты»/«Контакты». Контракт — конвейер 001
(vacuum/drift/oasdiff, additive minor 0.4.0). Нагрузочный smoke — k6
(`load/k6/messaging.smoke.js`): латентность доставки + SSE, 429 без 5xx (SC-008).

**Target Platform**: без изменений: Linux-контейнеры в K8s dev (stateless-поды). Для SSE —
отключение прокси-буферизации на маршруте событий (аннотация ingress + `X-Accel-Buffering:
no`, [research.md §9](./research.md)).

**Project Type**: web-service (монорепозиторий 001, структура сохраняется).

**Performance Goals** (спека): 95% сообщений «доставлено» ≤ 2 с (SC-001); 0 дублей в
сценариях ретраев/двойных отправок (SC-002, автотесты); порядок одинаков у обоих участников
в 100% прогонов (SC-003); последние сообщения ≤ 3 с после открытия, страница истории ≤ 3 с
(SC-004); реалтайм-доставка онлайн-получателю ≤ 2 с в 95% (SC-005); «прочитано» у
онлайн-отправителя ≤ 2 с от отображения получателю в 95% (SC-007); observability — метрики
латентности доставки/пуша, доля дедупликаций, отклонения по лимитам (SC-008); укладка в
бюджеты конституции с расчётом (SC-006, [research.md §12](./research.md)).

**Constraints**: stateless-поды — состояние только в PG/Redis, SSE-соединения не несут
состояния (переподключение безопасно, FR-009); авторизация на каждом ресурсе — membership
диалога (FR-002, конституция V); идемпотентность отправки по клиентскому UUID (FR-004,
конституция III); значения 4096 символов / 30 сообщений в минуту / 50 на страницу —
фиксируются в контракте (FR-003/008/011); блокировка — семантика FR-020 без утечки факта
блокировки заблокированному (кроме явной ошибки при отправке); обратная совместимость
контракта (additive minor); переподключение клиента сходится рефетчем (до 005).

**Scale/Scope**: 6 новых endpoints диалогов/сообщений + 1 SSE-канал + 6 endpoints
контактов/поиска/блокировок; 5 таблиц PG (2 миграции) + 2 семейства Redis-ключей/каналов;
SPA: мессенджер-страница (панель «Чаты»/«Контакты» + окно диалога) и hook-слой SSE/outbox;
1 k6-сценарий. E2EE, групповые чаты, push-уведомления, курсорный delta-sync при
переподключении — вне объёма (Assumptions, ROADMAP 005).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Статус | Комментарий |
|---|---------|--------|-------------|
| I | Spec-Driven (NON-NEGOTIABLE) | ✅ PASS | Спека утверждена (3 раунда clarify); план — этот документ; `tasks.md` — следующий шаг (`/speckit.tasks`). Код только по задачам. |
| II | Масштабируемость / stateless (NON-NEGOTIABLE) | ✅ PASS | Поды stateless: всё состояние — PG (сообщения, водяные знаки) и Redis (лимиты, pub/sub); SSE-сокеты эфемерны и безопасны для переподключения. Расчёт под бюджеты (1M CCU / 100k msg/s): ~20 инстансов × ~50k SSE-сокетов (Tomcat NIO), ~2 Redis-публикации на сообщение (~1M msg/s предел узла — достаточно с запасом при 100k msg/s), вертикальный PG на 004 (десятки тысяч INSERT/s; ко-локация по `chat_id` и составная уникальность `(chat_id, seq)`: партиционирование — как есть, шардирование — per-shard seq с watermark-инициализацией, SQL-паттерны приложения не меняются) — [research.md §3, §12](./research.md). |
| III | Доставка без дублей (NON-NEGOTIABLE) | ✅ PASS | Ядро фичи: клиентский UUID = PK `messages`, дедуп `INSERT … ON CONFLICT DO NOTHING` + возврат существующей записи (FR-004); кворум — синхронный commit PG до подтверждения клиенту; порядок — серверный `seq` bigserial, одинаков для обоих участников (FR-006); ретраи безопасны (US2). Тесты на потерю/дубль/порядок обязательны (SC-002/003, VI). |
| IV | API-First и встраиваемость | ✅ PASS | Все операции и **реалтайм-канал** — сначала в `contracts/openapi.yaml` (minor 0.3.0 → 0.4.0, additive, без BREAKING.md): SSE-endpoint с `text/event-stream` + схемы событий в components; TS-типы — codegen; drift/breaking-детекция CI действует. Любой контрактный клиент (SPA/виджет/сторонний) работает через одни интерфейсы (US6). |
| V | Безопасность и приватность | ✅ PASS | Только аутентифицированные (Bearer 002); membership-проверка на каждом ресурсе (FR-002, US1-5); SSE — авторизация заголовком, токен в URL запрещён; флуд-лимит 30/мин на пользователя во внешнем Redis (FR-011, конституция Security); блокировка не раскрывается заблокированному нигде, кроме явной ошибки отправки (FR-020); E2EE — вне scope (Assumptions); секреты — env/K8s Secrets. |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | Тесты в каждой задаче (DoD): IT на дедуп/порядок/пагинацию/прочтения/блокировки/контакты/удаление/флуд/SSE по всем acceptance-сценариям US1–US6; контрактный конвейер; k6 smoke доставки и реалтайм-латентности (SC-001/005/008). |
| VII | Простота (YAGNI) | ✅ PASS | Минимально: SSE вместо WebSocket/STOMP (односторонний поток, описуем в OpenAPI, без апгрейд-инфраструктуры — [research.md §1](./research.md)); Redis Pub/Sub вместо Kafka (нет персистентности — источник истины PG, рефетч при переподключении — [research.md §2](./research.md)); водяной знак `deleted_up_to_seq` вместо per-recipient строк сообщений; `last_read_seq` вместо per-message read-квит; без таблицы delivery-состояний и курсорного delta-sync (005). |
| VIII | SOLID на уровне кода | ✅ PASS | SRP: package-by-feature (`chats/`, `contacts/`, `realtime/`); DIP: домен `chats` публикует события через порт `RealtimeEventPublisher` (адаптер Redis pub/sub — снаружи), порты репозиториев; OCP/LSP/ISP — без спекулятивных абстракций (VII), точки расширения — типы контента (IV, будущие фичи). |

**Нарушений нет** — Complexity Tracking не требуется.

**Re-check после Phase 1 (дизайн)**: data-model.md не вводит in-process бизнес-состояния —
всё долговечное в PG (водяные знаки `last_read_seq`/`deleted_up_to_seq` вместо per-message
квит/строк видимости), эфемерное — в Redis (бакеты TTL-ированы; pub/sub — транспорт уведомлений
при источнике истины PG; сброс Redis допустим); contracts/api-contract.md +
contracts/realtime-channel.md — additive к публичному контракту (minor 0.4.0), реалтайм-канал
выражен в OpenAPI схемами событий (FR-022/US6), leak-полей блокировки нет (`blockedByMe`
единственная проекция). quickstart.md проверяем без ручных правок артефактов. Выводы таблицы
не меняются — **все гейты PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/004-direct-messaging-core/
├── plan.md                       # This file (/speckit.plan command output)
├── research.md                   # Phase 0 output (/speckit.plan command)
├── data-model.md                 # Phase 1 output (/speckit.plan command)
├── quickstart.md                 # Phase 1 output (/speckit.plan command)
├── contracts/
│   ├── api-contract.md           # Phase 1: REST endpoints №11–24
│   └── realtime-channel.md       # Phase 1: SSE-канал и схемы событий
└── tasks.md                      # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/webchat/backend/
├── BackendApplication.kt                     # без изменений
├── config/
│   ├── SecurityConfig.kt                     # новые пути — authenticated() (правки списков не требуется:
│   │                                          #   anyRequest().authenticated() уже закрывает новые endpoints)
│   └── ChatsProperties.kt                    # @ConfigurationProperties("chats.*"): лимиты 30/мин, страница 50,
│                                               #   максимум 4096, heartbeat SSE
├── chats/                                    # диалоги, сообщения, прочтения
│   ├── api/
│   │   ├── ChatController.kt                 # ensure/list/get/delete чата
│   │   ├── MessageController.kt              # отправка (идемпотентная), история, прочтение
│   │   └── dto/                              # EnsureChatRequest, SendMessageRequest, MessageView, …
│   ├── domain/
│   │   ├── model/                            # Chat, ChatParticipant, Message, MessageText (валидация FR-003)
│   │   ├── port/                             # ChatRepository, MessageRepository, ParticipantRepository,
│   │   │                                     #   BlockRepository, RealtimeEventPublisher (реализован в realtime/)
│   │   └── service/                          # ChatService (ensure/удаление), MessageService (отправка+дедуп),
│   │                                         #   HistoryService (пагинация), ReadService (GREATEST-водяной знак)
│   └── repository/                           # JdbcChatRepository (ensure ON CONFLICT), JdbcMessageRepository
│                                               #   (INSERT … ON CONFLICT), JdbcParticipantRepository, JdbcBlockRepository
├── contacts/                                 # контакты, блокировки, поиск пользователей
│   ├── api/
│   │   ├── ContactController.kt              # список (сортировка login/email), добавление, удаление
│   │   ├── BlockController.kt                # PUT/DELETE блокировки (идемпотентно)
│   │   └── UserSearchController.kt           # GET /users/search?query= (точное email ИЛИ логин, lower())
│   ├── domain/
│   │   ├── model/                            # Contact, UserBlock
│   │   ├── port/                             # ContactRepository, UserLookupPort
│   │   └── service/                          # ContactService, BlockService
│   └── repository/                           # JdbcContactRepository, JdbcUserLookup (lower()-индексы users)
├── realtime/
│   ├── RealtimeController.kt                # GET /api/v1/users/me/events (SseEmitter, heartbeat)
│   ├── SseConnectionRegistry.kt             # emitters по userId (в рамках инстанса; мульти-сессии/устройства)
│   └── RedisRealtimePublisher.kt            # адаптер порта: публикация в rt:user:{id}; подписка Lettuce,
│                                              #   диспетчеризация в локальные emitters
└── users/api/UsersController.kt              # без изменений

backend/src/main/resources/
├── db/migration/
│   ├── V10__chats.sql                        # chats, chat_participants, messages (+индексы, CHECK)
│   └── V11__contacts_blocks.sql              # user_contacts, user_blocks (+уникальные пары)
└── application.yml                           # + chats.* (лимиты, страница, максимум текста, heartbeat)

backend/src/test/kotlin/webchat/backend/      # MessagingDeliveryIT (дедуп/порядок/ретраи), HistoryIT,
                                               # ReadReceiptsIT, BlockingIT, ContactsIT, ChatDeletionIT,
                                               # FloodLimitIT, RealtimeSseIT, ChatAccessIT (membership)

frontend/src/
├── api/
│   ├── schema.d.ts                           # GENERATED (regen из контракта 0.4.0)
│   ├── chats.ts                              # типизированные вызовы endpoints 11–24
│   └── sse.ts                                # SSE-клиент на fetch+ReadableStream: Bearer, авто-refresh,
│                                               #   авто-reconnect + событие onOpen (рефетч)
├── chats/
│   ├── pages/MessengerPage.tsx               #layout: панель слева + окно диалога
│   ├── components/                           # ChatListItem (бейдж 99+, метка «заблокирован»), ChatListPanel
│   │                                         #   (переключатель «Чаты»/«Контакты»), ContactList, UserSearchBox,
│   │                                         #   MessageList (пагинация, галочки ✓/✓✓), MessageInput, ErrorBanner
│   ├── hooks/                                # useChatList, useChatMessages (SSE+история), useOutbox,
│   │                                         #   useRealtime (единый поток событий пользователя)
│   └── outbox.ts                             # localStorage webchat.chats.outbox.<userId>: «отправляется»/
│                                               #   «не отправлено», автоповторы тем же ID, флуд-отсчёт, purge при
│                                               #   удалении чата (FR-012, FR-021)
└── App.tsx                                   # + маршрут «/» (мессенджер, защищённый)

contracts/openapi.yaml                        # + endpoints №11–24, SSE-канал, схемы (ChatView, ChatListItem,
                                               #   Message, ContactView, события message.created/chat.read),
                                               #   коды ошибок (0.3.0 → 0.4.0, additive)

deploy/k8s/base/ingress.yaml                  # аннотация disable-buffering для /api/v1/users/me/events
load/k6/messaging.smoke.js                    # SC-001/005/008: отправка+подтверждение, SSE-латентность, 429
```

**Structure Decision**: структура 001–003 сохранена (плоский монорепозиторий
`backend/` + `frontend/` + `contracts/`). Backend — package-by-feature поверх
`webchat.backend`: новые пакеты `chats/`, `contacts/`, `realtime/`; существующие фичи
(auth/sso/email/users) не правятся (кроме application.yml и — потенциально — permitAll-списка,
куда новые пути не добавляются: все новые endpoints аутентифицированы). Порты домена
(`RealtimeEventPublisher`, репозитории) — в `domain/port`, адаптеры — снаружи (DIP, VIII).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица пуста.
