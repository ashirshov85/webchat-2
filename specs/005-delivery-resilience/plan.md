# Implementation Plan: Устойчивость доставки при разрывах соединения и пиковой нагрузке

**Branch**: `005-delivery-resilience` | **Date**: 2026-09-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/005-delivery-resilience/spec.md`

## Summary

Надстройка над ядром 004, закрывающая разрывы соединения и пиковую нагрузку. Сервер получает
**позицию доставки** на пару «пользователь–чат»: новая колонка `chat_participants.delivered_up_to_seq`
(миграция V12), продвигаемая **только подтверждениями (ack) клиента** — батчами по чатам через
новый `POST /users/me/delivery-ack`. Догоняющая синхронизация — **pull по курсору**: агрегированный
`POST /users/me/sync` возвращает дельты всех чатов с недоставленным (курсоры передаются клиентом,
порядок — по последней активности, постранично через существующее №15, расширенное курсором `after`).
Нижняя граница дозагрузки — `deleted_up_to_seq` (per-user удаление 004); при обрезке сервер продвигает
позицию на точку обрезки и сообщает её явно; «курсор из будущего» обрабатывается per-chat-ремонтом
без ошибки всего запроса. Счётчик непрочитанных становится серверно-авторитарным и ограничен позицией
доставки (прочитано/доставлено/обрезка). Догоняющая синхронизация доносит и статусы собственных
сообщений (`peerReadUpToSeq`). Оффлайн-очередь клиента (outbox 004) получает лимит 1000 с вытеснением
старейших в терминальный статус «не отправлено» (причина `queue_overflow`), а также персистентную
очередь оффлайн-событий «прочитано» (флаш через идемпотентный №17). Backpressure — адаптивный
per-instance concurrency-лимитер на пути отправки (gradient/AIMD, in-memory — транспортное состояние
по прецеденту SSE-реестра): поэтапная деградация «рост задержки → явный временный отказ
`503 server_busy` + `Retry-After`», дедуп-фастпас остаётся первым (ретраи сходятся без штрафов);
принятые сообщения доставляются в приоритете. Нагрузочная валидация — k6-сценарий устойчивости
(разрывы: 0 потерь/дублей) и профиль насыщения (монотонная деградация, 0 «молчаливых» отказов,
самовосстановление ≤ 5 мин); полнообъёмный прогон 100k msg/s × 10 мин — параметризованный сценарий
в инфраструктуре 014 (обоснование — Constraints/Complexity Tracking, прецедент 004).

Детальные решения с обоснованием — [research.md](./research.md).

## Technical Context

*Исходные неизвестные (позиция доставки и ack-механизм; протокол pull-синхронизации и пагинация;
границы дозагрузки/точка обрезки; серверно-авторитарные непрочитанные; сигнал и алгоритм backpressure;
лимит/вытеснение оффлайн-очереди; оффлайн-события «прочитано»; клиентские курсоры/single-flight;
стратегия нагрузочной валидации бюджетов) разрешены в Phase 0 — [research.md](./research.md).*

**Language/Version**: без изменений относительно 001–004: Kotlin 2.2.x + JDK 21 (backend),
TypeScript 5.x strict (frontend), Node 24 + pnpm.

**Primary Dependencies** (новых внешних зависимостей нет; версии — из BOM Spring Boot):
- Backend: переиспользуются `spring-boot-starter-web`, `spring-boot-starter-security` +
  `oauth2-resource-server`, `spring-boot-starter-jdbc` + Flyway (PostgreSQL), Micrometer.
  Backpressure-лимитер — собственный минимальный gradient/AIMD (~100 строк, без новой зависимости —
  VII, [research.md §6](./research.md)); Kafka/Resilience4j НЕ вводятся.
- Frontend: без новых runtime-зависимостей — fetch-слой 004 (`api/chats.ts`, `api/sse.ts`),
  localStorage; типы — из codegen.

**Storage**: **PostgreSQL 17** — единственное новое долговечное состояние: колонка
`chat_participants.delivered_up_to_seq` (миграция **V12__delivery_sync.sql**, backfill
`GREATEST(last_read_seq, deleted_up_to_seq)`); непрочитанные/дельты/точки обрезки — выводные
(запросы, не хранение). **Redis 7** — без новых ключей (существуют `rl:*`, `rt:user:{id}`);
admission-лимитер — in-memory per-instance (транспортное состояние, конституция II —
прецедент SSE-реестра 004). Клиент: localStorage (`webchat.sync.cursors.<userId>`,
`webchat.sync.pendingReads.<userId>`, outbox 004 + лимит/вытеснение).

**Testing**: Backend — JUnit 5 + Testcontainers (PG+Redis): ack (идемпотентность/монотонность),
sync (полнота/порядок/пагинация/обрезка/десинк/backpressure-совместимость), непрочитанные
(сходимость realtime+sync), 503+Retry-After (порядок дедуп→admission→flood). Frontend — Vitest +
Testing Library: cursors/single-flight, outbox-вытеснение `queue_overflow`, pendingReads
(переживание перезапуска, идемпотентный флаш), прозрачная очередь при 429/503. Контракт — конвейер
001 (additive minor 0.5.0). Нагрузочные — k6 (`load/k6/delivery-resilience.js`: разрывы/SC-001/SC-008;
профиль насыщения `K6_TARGET_RPS`: SC-005/006/007 — [research.md §10](./research.md)).

**Target Platform**: без изменений: Linux-контейнеры в K8s dev (stateless-поды). Admission-лимитер
работает per-instance за LB — суммарный эффект флотский ([research.md §6](./research.md)).

**Project Type**: web-service (монорепозиторий 001, структура сохраняется).

**Performance Goals** (спека): 0 пропусков/0 дублей при разрывах любой длительности (SC-001,
интеграционные + k6 с принудительными разрывами); оффлайн-очередь доставляется ровно по одному разу,
в порядке отправки, переживая перезапуск (SC-002); счётчики сходятся с фактом (SC-003); доставка
p99 ≤ 500 мс штатно / ≤ 2 с в деградированном режиме, устойчивый поток 100k msg/s × 10 мин (SC-004,
расчёт + параметризованный k6-сценарий; полнообъёмный прогон — инфраструктура 014); 0 «молчаливых»
отказов сверх пропускной способности — только явный временный отказ с задержкой (SC-005);
самовосстановление ≤ 5 мин после пика (SC-006); принятые не теряются и не дублируются (SC-007);
дозагрузка 200 сообщений ≤ 10 с p95 штатно / ≤ 30 с p95 деградированно (SC-008).

**Constraints**: ack — единственный двигатель позиции доставки (FR-001); exactly-once-эффект
достигается клиентской дедупликацией по `message.id` + идемпотентными повторами pull; sync —
all-or-refusal, никогда «частичный список как полный» (edge); single-flight синхронизации на клиенте,
курсор монотонен (edge «скачок соединения»); порядок — только серверный `seq`, часы клиента
нерелевантны (edge); дедуп-фастпас 004 остаётся до admission/flood — ретраи сходятся без штрафов
и без обхода лимита 30/мин (FR-010); статус «не отправлено» — только по постоянному отказу или
переполнению очереди, повторы при временных отказах бессрочны (FR-005/FR-006/FR-009); сообщения ниже
точки обрезки — недоступные, не непрочитанные (FR-003/FR-007); мультидевайс — вне объёма
(Assumptions); нагрузочная валидация бюджетов 1M CCU / 100k msg/s в 005 — расчётная + профиль
насыщения на достижимом масштабе, полнообъёмный прогон — платформенная cross-cutting-задача 014
(прецедент 004 plan.md Constraints, ROADMAP; фиксация в Complexity Tracking).

**Scale/Scope**: 2 новых endpoints (№25 `POST /users/me/delivery-ack`, №26 `POST /users/me/sync`) +
расширение №15 опциональным `after` (+`nextAfter`) + №16 новым `503 server_busy`; 1 миграция PG
(V12, одна колонка + backfill); 0 новых таблиц/Redis-ключей; frontend: модуль `src/sync/`
(курсоры/ack/pendingReads/useSync), эволюция outbox (лимит/вытеснение) и панели (индикация
синхронизации); 1 новый k6-сценарий (+профиль насыщения в нём); 8 новых метрик (FR-014).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Статус | Комментарий |
|---|---------|--------|-------------|
| I | Spec-Driven (NON-NEGOTIABLE) | ✅ PASS | Спека утверждена (3 раунда clarify); план — этот документ; `tasks.md` — следующий шаг (`/speckit.tasks`). Код только по задачам. |
| II | Масштабируемость / stateless (NON-NEGOTIABLE) | ✅ PASS | Единственное новое бизнес-состояние — `delivered_up_to_seq` в PG (GREATEST-обновление одной строки, как `last_read_seq` 004); admission-лимитер — in-memory транспортное состояние per-instance (прецедент SSE-реестра 004: сброс/рестарт безопасен, поды взаимозаменяемы). Redis-ключей не добавляется. Расчёт под бюджеты: ack ≈ сообщения, но батчятся (амортизация N:1); sync — индексные range-scan'ы `(chat_id, seq)`; запись 100k msg/s горизонтальна по лестнице 004 §12 (партиции → шард), 005 её не регрессирует ([research.md §10](./research.md)). Полнообъёмный прогон бюджетов — 014 (см. Complexity Tracking). |
| III | Доставка без дублей (NON-NEGOTIABLE) | ✅ PASS | Ядро фичи: позиция доставки движется только по ack; pull идемпотентен (повтор запроса с тем же курсором — тот же ответ); клиентская дедупликация по `message.id` (004) + монотонный курсор ⇒ эффект exactly-once при разрывах любой точки пути (SC-001, edge «повтор посреди синхронизации»). Тесты потери/дубля/порядка с принудительными разрывами обязательны (VI). |
| IV | API-First и встраиваемость | ✅ PASS | Все новые операции и изменения — сначала `contracts/openapi.yaml` (additive minor 0.4.0 → 0.5.0: №25/№26, `after`-параметр №15, `503` №16, схемы `DeliveryAck*`/`Sync*`); TS-типы — codegen; drift/breaking-детекция CI действует. Протокол синхронизации нормативно описан в [contracts/sync-protocol.md](./contracts/sync-protocol.md) на основе OpenAPI-схем. |
| V | Безопасность и приватность | ✅ PASS | Новые endpoints — только аутентифицированные (Bearer), авторизация per-user (`/users/me/*`) и membership на №15/№16 (без изменений 004); admission-отказ не раскрывает ничего, кроме «повторите через N»; логи без текста сообщений (PII-минимизация 004 сохранена). |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | Тесты в каждой задаче (DoD): IT на ack/sync/обрезку/десинк/непрочитанные/503-порядок; frontend-тесты cursors/outbox-вытеснение/pendingReads; k6 — сценарий разрывов (SC-001/008) и профиль насыщения (SC-005/006/007); контрактный конвейер. Изменения пропускной способности сопровождены нагрузочными тестами ([research.md §10](./research.md)). |
| VII | Простота (YAGNI) | ✅ PASS | Минимально: колонка + GREATEST вместо per-message квит; агрегированный sync-endpoint вместо durable-очередей/Redis Streams; собственный ~100-строчный AIMD-лимитер вместо новых зависимостей (Kafka, Resilience4j, Netflix concurrency-limits — отклонены, [research.md §6](./research.md)); push-replay от сервера НЕ вводится (clarify: pull); эскалации по времени нет (спека). |
| VIII | SOLID на уровне кода | ✅ PASS | SRP: `sync/` отделён от `chats/`; admission — отдельный порт `SendAdmissionGate` (внедряется в MessageService без перестройки пути — как SendPolicyGate 004); DIP: домен зависит от портов; OCP/LSP/ISP — без спекулятивных абстракций (VII); точки расширения — будущие групповые чаты (sync по тем же курсорам). |

**Нарушений нет** — Complexity Tracking не требуется (примечание о нагрузочной валидации бюджетов —
в конце документа, перед Re-check).

**Re-check после Phase 1 (дизайн)**: см. конец документа.

## Project Structure

### Documentation (this feature)

```text
specs/005-delivery-resilience/
├── plan.md                       # This file (/speckit.plan command output)
├── research.md                   # Phase 0 output (/speckit.plan command)
├── data-model.md                 # Phase 1 output (/speckit.plan command)
├── quickstart.md                 # Phase 1 output (/speckit.plan command)
├── contracts/
│   ├── sync-protocol.md          # Phase 1: протокол курсоров/ack/догоняющей синхронизации
│   └── api-contract.md           # Phase 1: изменения REST-контракта (№15/№16, №25/№26)
└── tasks.md                      # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/webchat/backend/
├── BackendApplication.kt                     # без изменений
├── config/
│   ├── SecurityConfig.kt                     # без изменений (anyRequest().authenticated())
│   └── DeliveryProperties.kt                 # NEW @ConfigurationProperties("delivery.*"):
│                                               #   sync (страница сообщений 50, чатов 20, лимит ack-элементов),
│                                               #   backpressure (enabled, min/max limit, базовая латентность)
├── chats/                                    # эволюция 004 (путь отправки — точка внедрения admission)
│   ├── api/
│   │   ├── MessageController.kt              # №15: параметр after (ascending-страница + nextAfter);
│   │   │                                     #   №16: маппинг 503 server_busy + Retry-After
│   │   └── dto/                              # AscendingPage / расширение MessagePage (nextAfter?)
│   ├── domain/
│   │   ├── model/ChatParticipant.kt          # + deliveredUpToSeq
│   │   ├── port/ParticipantRepository.kt     # + advanceDelivered (GREATEST, батч), loadForSync
│   │   │                                     #   ( недоставленные чаты по последней активности)
│   │   └── service/
│   │       ├── MessageService.kt             # + admission-вызов после дедуп-фастпаса (до flood)
│   │       └──── SendPolicyGate.kt           # без изменений (flood/blocks)
│   └── repository/JdbcParticipantRepository.kt # реализация новых запросов
├── sync/                                     # NEW: позиция доставки и догоняющая синхронизация
│   ├── api/
│   │   ├── DeliveryAckController.kt          # №25 POST /users/me/delivery-ack (батч GREATEST)
│   │   ├── SyncController.kt                 # №26 POST /users/me/sync (дельты по последней активности)
│   │   └── dto/                              # DeliveryAckRequest/Item, SyncRequest, SyncResponse,
│   │                                         #   SyncChatDelta (truncatedUpToSeq/desynced/…)
│   └── domain/service/
│       ├── DeliveryAckService.kt             # идемпотентное монотонное продвижение позиции
│       └── SyncService.kt                    # сведение курсоров, нижняя граница (deleted_up_to_seq),
│                                             #   точка обрезки, per-chat ремонт «курсора из будущего»,
│                                             #   unreadCount/peerReadUpToSeq в дельте
├── backpressure/                             # NEW: контролируемая деградация пути отправки
│   ├── SendAdmissionGate.kt                  # порт (вызов из MessageService)
│   ├── GradientAdmissionLimiter.kt           # in-flight semaphore + AIMD по gradient латентности;
│   │                                         #   onShed → 503 server_busy + Retry-After (расчёт дрейна)
│   └── AdmissionMetrics.kt                   # gauges/counters (лимит, in-flight, sheds)
└── realtime/                                 # без изменений (SSE-канал 004; ack — REST №25)

backend/src/main/resources/
├── db/migration/
│   └── V12__delivery_sync.sql                # chat_participants.delivered_up_to_seq (+CHECK, backfill
│                                               #   GREATEST(last_read_seq, deleted_up_to_seq))
└── application.yml                           # + delivery.sync.*, delivery.backpressure.*

backend/src/test/kotlin/webchat/backend/      # DeliveryAckIT, SyncIT (полнота/порядок/пагинация/обрезка/
                                                #   десинк/частичная доступность), UnreadConvergenceIT,
                                                #   BackpressureIT (порядок дедуп→admission→flood, Retry-After),
                                                #   AscendingHistoryIT (after-страницы)

frontend/src/
├── api/
│   ├── schema.d.ts                           # GENERATED (regen из контракта 0.5.0)
│   └── chats.ts                              # + deliveryAck(), sync(), ascending-страницы (after)
├── sync/                                     # NEW: клиентская сторона протокола
│   ├── cursors.ts                            # localStorage webchat.sync.cursors.<userId>: per-chat seq,
│   │                                         #   монотонно (только max), self-heal по truncatedUpToSeq/
│   │                                         #   desynced/serverUpToSeq
│   ├── pendingReads.ts                       # localStorage webchat.sync.pendingReads.<userId>: оффлайн
│   │                                         #   «прочитано» (per-chat watermark), переживает перезапуск
│   ├── ack.ts                                # дебаунс-батчер ack'ов realtime-доставок (≤1с/флаш, retry)
│   └── hooks/
│       └── useSync.ts                        # single-flight догоняющая синхронизация на onOpen SSE:
│                                           #   дельты → рендер (дедуп по id) → ack → повтор до исчерпания;
│                                           #   индикация прогресса; флаш pendingReads
├── chats/
│   ├── outbox.ts                             # + лимит 1000, вытеснение старейших sending → failed
│   │                                         #   (queue_overflow), уведомление
│   ├── hooks/
│   │   ├── useOutbox.ts                      # + 429 flood и 503 server_busy → прозрачная очередь тем же ID
│   │   │                                     #   (Retry-After), FIFO-флаш per-chat при восстановлении
│   │   └── useChatList.ts                    # unreadCount из sync/realtime, сходимость с сервером
│   └── components/                           # SyncIndicator (процесс дозагрузки), QueueOverflowBanner,
│                                             #   статусные галочки по peerReadUpToSeq из дельт
└── App.tsx                                   # без изменений маршрутов

contracts/openapi.yaml                        # 0.4.0 → 0.5.0 (additive): №25/№26, №15 after/nextAfter,
                                                #   №16 503 server_busy+Retry-After, схемы DeliveryAck*/Sync*,
                                                #   уточнение описания unreadCount (доставка-ограниченный)

load/k6/delivery-resilience.js                # NEW: разрывы (0 потерь/дублей/порядок, 200 сообщ. ≤10с p95)
                                                #   + профиль насыщения (K6_TARGET_RPS: монотонная деградация,
                                                #   только 429/503 с Retry-After, рекавери ≤5 мин)
```

**Structure Decision**: структура 001–004 сохранена (плоский монорепозиторий `backend/` +
`frontend/` + `contracts/`). Backend — package-by-feature: новые пакеты `sync/` (курсоры/ack/
догоняющая синхронизация) и `backpressure/` (admission); `chats/` эволюционирует точечно
(№15/№16, колонка участника, вызов admission-порта после дедуп-фастпаса). Frontend — новый модуль
`src/sync/` (курсоры, ack-батчер, pendingReads, useSync), эволюция `chats/` (outbox-лимит,
server_busy-классификация, индикаторы). Порты (`SendAdmissionGate`, расширение
`ParticipantRepository`) — в `domain/port`, адаптеры — снаружи (DIP, VIII).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица пуста. Документированное ограничение (не нарушение):
полнообъёмный нагрузочный прогон конституционного бюджета 100k msg/s × 10 мин (FR-012/SC-004)
требует распределённых генераторов нагрузки и масштабированной инфраструктуры (шаг лестницы 004 §12),
выходящей за dev-окружение фичи; в 005 поставляются: (а) механизм деградации, укладывающийся
в бюджет по расчёту, (б) параметризованный k6-сценарий насыщения, валидирующий семантику degrade/
recover на достижимом масштабе, (в) закрепление полнообъёмного прогона за платформенной фичей 014
(ROADMAP, прецедент — plan.md 004 Constraints). Уточнение объёма, не расширяющее и не сужающее
гарантии: архитектурные решения (admission по латентности, GREATEST-колонка) проверяются
интеграционно при любом масштабе.

**Re-check после Phase 1 (дизайн)**: data-model.md не вводит in-process бизнес-состояния —
единственное новое долговечное поле `delivered_up_to_seq` в PG (GREATEST-монотонность, как
`last_read_seq`), лимитер — транспортное in-memory (сброс безопасен: состояние самовосстанавливается
измерением латентности; конституция II); contracts/api-contract.md + contracts/sync-protocol.md —
additive к публичному контракту (minor 0.5.0): новые №25/№26, опциональный `after` у №15, новый
код `503 server_busy` у №16 — breaking-изменений нет (все существующие ответы/поля сохранены,
семантика `unreadCount` уточнена в описании без смены схемы); quickstart.md проверяем без ручных
правок артефактов; observability расширяет существующий Micrometer-стек (8 новых метрик FR-014,
tracing наследуется от 001). Выводы таблицы не меняются — **все гейты PASS**.
