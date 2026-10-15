# Implementation Plan: Видеоконференции в чатах (речь, видео, демонстрация экрана)

**Branch**: `009-video-conferences` | **Date**: 2026-10-15 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/009-video-conferences/spec.md`

## Summary

Видеоконференции строятся как **две плоскости поверх существующего монолита**: медиа-плоскость —
self-hosted SFU **LiveKit** (отдельный Deployment, своего медиа-кода нет, конституция VII);
контрольная плоскость — новый stateless-модуль `backend/.../conferences/` по образцу
`chats/groups/presence` (FR-017): сущности `conferences`, `conference_participants`,
`conference_guest_invites` в PostgreSQL (миграция **V18**), эволюция `messages` (nullable
`sender_id` + `kind` + `payload jsonb`) для первого системного виджета — карточки-приглашения
со ссылкой и кнопкой «Подключиться» (FR-002, конституция IV). Подключение членов чата — через
`POST /conferences/{id}/join`: membership-check на каждой выдаче (переиспользование
`GroupMembershipGate`/`ParticipantRepository`, 006) → минт **короткожившего LiveKit JWT**
(HS256, grants из БД-ограничений участника; self-hosted LiveKit не ревоцирует токены —
компенсируется TTL, `conferences.token-ttl`). Гостевой вход — единственная публичная
поверхность: stateless-подписанный nonce (ES256, инфраструктура `JwtService`) + строка в
`conference_guest_invites` для отзыва (одна активная ссылка на конференцию); публичные
`GET invite-info` / `POST join-by-invite` + LiveKit-webhook закрыты `permitAll`-строками
SecurityConfig и per-IP бакетами `RateLimitFilter` (паттерн 002). Модерация — только серверная:
клиентам не выдаётся `roomAdmin`; действия применяются через RoomService API LiveKit
(Twirp over HTTP: `UpdateParticipant` permissions, `MutePublishedTrack`, `RemoveParticipant`,
`DeleteRoom`, `SendData`) и персистентны в `conference_participants` до конца конференции
(Q5/Q9/Q22). Жизненный цикл наблюдаем из чата realtime-событиями `conference.started` /
`conference.ended` через существующий per-user SSE-канал (post-commit публикация); завершение —
по вебхуку `room_finished`, команде модератора, per-env максимуму длительности (scheduler),
компенсирующему sweeper'у при потере вебхука (SC-007) либо CASCADE удаления чата
(`chat_deleted`). Фронтенд — модуль
`frontend/src/conferences/` на `livekit-client` + `@livekit/components-react` (ленивая загрузка):
единый pre-join (FR-005–FR-007), экран конференции с переключением устройств на лету (Q11=B),
лёгкая публичная гостевая join-страница `/join/:inviteToken`, кнопки «видеозвонок»/«аудиозвонок»
в `ChatHeader`. Квоты (`maxParticipants`, дефолты Q19) — конфигурация per-env; LiveKit-комната
создается с `max_participants` + `empty_timeout=60 c` и `departure_timeout=60 c` — обе границы
жизни пустой комнаты (livekit-integration §2).
Нагрузочный расчёт на бюджет 1000 одновременных конференций (принцип II) —
[research.md §12](./research.md); валидация — k6 (контрольная плоскость) + нагрузочный прогон
медиа-плоскости на dev-стенде (SC-008).

Детальные решения с обоснованием и альтернативами — [research.md](./research.md).

## Technical Context

*Исходные неизвестные (медиа-плоскость и способ интеграции; модель токенов/identity; серверная
модерация и персистентность ограничений; механика гостевого nonce и отзыва ссылки; события
жизненного цикла и компенсация потери вебхуков; системное сообщение-виджет; развёртывание
LiveKit; квоты per-env; наблюдаемость; фронтенд-модуль и публичная гостевая страница; нагрузочный
расчёт) разрешены в Phase 0 — [research.md](./research.md).)*

**Language/Version**: без изменений относительно 001–008: Kotlin 2.x + JDK 21 (backend), React 19 +
TypeScript strict (frontend). Новые зависимости: `io.livekit:livekit-server` (официальный
Kotlin/Java server SDK: минт токенов, Twirp-клиент RoomService, `WebhookReceiver`; fallback —
ручной Twirp-адаптер, [research.md §3](./research.md)); фронтенд — `livekit-client` +
`@livekit/components-react` (peer-dep), ленивый чанк.

**Primary Dependencies**: Spring Boot 3.x (существующий монолит, модуль `conferences/`), Spring
JDBC (`JdbcTemplate`, без JPA — конвенция доменов), Flyway (V18), Redis 7 (pub/sub SSE, бакеты
Bucket4j, распределённый режим LiveKit), Nimbus JOSE+JWT (уже в проекте — подпись гостевых nonce),
Testcontainers (PG 17, Redis 7, + контейнер `livekit/livekit-server --dev` для IT).

**Storage**: PostgreSQL 17 — миграция `V18__conferences.sql` (3 новые таблицы + эволюция
`messages`); Redis 7 — rate-limit-бакеты публичных endpoints (`rl:ip:...`, паттерн 002) и
keyspace LiveKit (dev — общий инстанс, prod — отдельный logical DB; [research.md §11]).
Медиа-состояние (комнаты, подключения, треки) — в LiveKit, **не** дублируется в БД; квота
подключений считается медиа-плоскостью (`Room.num_participants`, `max_participants` комнаты).

**Testing**: конвенция 001–008 — JUnit 5 + AssertJ + Spring Boot Test (`AbstractIntegrationTest`,
Testcontainers PG/Redis/TestRestTemplate; для вебхуков и join-флоу — GenericContainer LiveKit);
unit-тесты сервисов на фейках портов (`SimpleMeterRegistry`); фронтенд — Vitest +
Testing Library (pre-join, join-страница, SSE-индикатор «звонок идёт»), Playwright для
e2e-звонка двух вкладок против локального LiveKit; нагрузочные — k6
(`load/k6/conferences.smoke.js`, контрольная плоскость) + медиа-прогон dev-стенда (SC-008).

**Target Platform**: backend — Linux/K8s (существующий Deployment монолита, stateless,
горизонтальное масштабирование без изменений); LiveKit — отдельный Deployment (hostNetwork/
выделенные ноды, порты 7880/7881/7882 + UDP range, TURN — [research.md §11]); фронтенд —
настольные браузеры последних версий Chrome/Firefox/Safari/Edge (Q8=A; мобильный веб — вне v1).

**Project Type**: web-service (монолит Spring Boot + SPA + встраиваемые модули) — расширяется
новым доменом `conferences` и новым публичным frontend-маршрутом гостя.

**Performance Goals**: SC-001 — вход ≤ 5 c (p95) от «Войти» до медиа; SC-005 — модерационные
действия ≤ 2 c (p95); SC-006 — события начала/завершения ≤ 2 c (p95); SC-004 — публичный
гостевой endpoint стабилен под флудом, деградация остальных API ≤ 10 % (Δp99); SC-008 — бюджет
1000 одновременных конференций (принцип II) с горизонтальным масштабированием LiveKit.

**Constraints**: квоты и таймеры per-env (Q19): `maxParticipants` (dev ≤ 16), таймаут пустой
комнаты 60 c, TTL гостевой ссылки 24 ч, максимум длительности 4 ч (+предупреждение за 5 мин),
имя гостя ≤ 50 символов, TTL LiveKit-токена — контракт/конфиг (`conferences.*`); self-hosted
LiveKit не ревоцирует токены → только короткий TTL + серверные `RemoveParticipant`/`UpdateParticipant`; токен может истечь в середине длительной конференции (TTL 900 с при максимуме 4 ч) — реконнект с истёкшим токеном выполняется повторным минтом через №47/№55 (клиент обновляет JoinTicket), окно реконнекта — конфиг `reconnect-window` (дефолт 120 с); при создании №44 — pre-flight
доступности LiveKit (`ListRooms`-probe): недоступность → `503 conference_media_unavailable`
без записи в БД (комната создаётся post-commit, «сироты» лечатся sweeper'ом — research §7);
sweeper — конфиг `sweep-interval`/`room-lost-grace` (дефолты 60/120 с);
серверный мьют требует `enable_remote_unmute: true` в конфиге LiveKit; E2EE-чаты (010) не
порождают конференций (граница v1).

**Scale/Scope**: до 1000 одновременных конференций / 1 000 000 одновременных пользователей
(принцип II): контрольная плоскость — stateless, доли RPS от существующих бюджетов
([research.md §12](./research.md)); медиа-плоскость — оценка ~6000 участников / ~36k потоков →
формула ёмкости нод LiveKit + обязательная нагрузочная валидация dev-стенда (SC-008).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Оценка | Обоснование |
|---|---|---|---|
| I | Spec-Driven | ✅ PASS | Артефакты в порядке SDD; `tasks.md` будет создан `/speckit.tasks` до кода. Спека полна (26 clarify/specify-решений зафиксированы). |
| II | Масштабируемость (NON-NEGOTIABLE) | ✅ PASS | Контрольная плоскость — stateless-модуль монолита, состояние только в PG/Redis/LiveKit; медиа — horizontally scalable LiveKit (distributed mode, Redis). Расчёт на 1000 конференций — [research.md §12](./research.md). Отдельный сервис не вводится (FR-017). |
| III | Доставка без дублей | ✅ PASS | Путь доставки сообщений не меняется; системное сообщение — одна INSERT-транзакция с существующим seq/fanout, идемпотентность создания конференции — частичным уникальным индексом «одна активная на чат». Realtime-события конференции — по существующему post-commit каналу (at-least-once, консьюмеры идемпотентны по статусам); вебхуки идемпотентны (переход статуса под `FOR UPDATE`, дедуп по `event.id`). |
| IV | API-First | ✅ PASS | Все операции — аддитивно в `contracts/openapi.yaml` (№44–№55, minor 0.10.0 → 0.11.0, oasdiff без breaking; nullable-дельта `Message` обоснована как в прецеденте 006); TS-типы — генерация `pnpm generate:api`. LiveKit-webhook — внутренний контракт, [contracts/livekit-integration.md](./contracts/livekit-integration.md). Системное сообщение — первый тип виджета: `kind` enum расширяется без правки ядра. |
| V | Безопасность и приватность | ✅ PASS | Все операции, кроме двух гостевых + вебхука, — только аутентифицированные; membership-check на каждой выдаче токена (FR-003); публичные endpoints — per-IP rate limiting (паттерн 002) и минимум раскрытия (без деталей чата); гостевой nonce — подписанный JWT с TTL + строка-статус для отзыва; identity/room name — без PII (рекомендация LiveKit). Секреты (`LIVEKIT_API_KEY/SECRET`, ключи nonce) — только env. |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | План тестирования в [quickstart.md](./quickstart.md) и research §13: IT с Testcontainers-PG/Redis/LiveKit (membership-отказы, guest-флоу, вебхуки, квоты, rate limiting), unit на домене, Vitest/Playwright на фронте, k6 + медиа-прогон на нагрузку (SC-004/SC-008). |
| VII | Простота (YAGNI) | ✅ PASS | Готовый SFU LiveKit вместо своего медиа-кода; официальный Kotlin server SDK + Twirp вместо gRPC-стека; без «зала ожидания», записи, рингтона, single-use ссылок (out of scope спеки); модерация — существующей моделью портов/адаптеров, без нового сервиса. |
| VIII | SOLID | ✅ PASS | DIP: домен `conferences` зависит от портов (`LiveKitAdminClient`, `RealtimeEventPublisher`, membership-порт), адаптер LiveKit — вне домена; SRP: контроллер/сервис/репозиторий по конвенции доменов; OCP: виджеты системных сообщений расширяются новыми `kind` без правки ядра чатов. |

**GATE-решение**: нарушений нет — Phase 0/Phase 1 запускаются; Complexity Tracking не требуется
(заполняется только при обосновании нарушений).

**Повторная проверка после Phase 1** (2026-10-15, артефакты [research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md)):
все ворота подтверждаются — II: расчёт [§12](./research.md) + stateless-плоскости и БД без
медиа-дублирования; III: идемпотентные `create`/`finalize`, дедуп вебхуков, системное сообщение
в одной транзакции; IV: additive-дельта №44–№55/minor 0.11.0 + расширяемый виджет `payload.type`;
V: публичная поверхность ограничена №54–№55+вебхуком (permitAll+Bucket4j), участники завершённых
конференций удаляются (Q18); VI: тест-план quickstart §4 / research §13 (IT с LiveKit-контейнером,
Playwright, k6); VII/VIII — без изменений. Нарушений не выявлено — Complexity Tracking остаётся
пустым. Phase 2 (`/speckit.tasks`) разрешена.

## Project Structure

### Documentation (this feature)

```text
specs/009-video-conferences/
├── plan.md              # Этот файл (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── api-contract.md
│   ├── realtime-conference-events.md
│   └── livekit-integration.md
└── tasks.md             # Phase 2 output (/speckit.tasks — НЕ создаётся /speckit.plan)
```

### Source Code (repository root)

```text
backend/
├── src/main/kotlin/webchat/backend/conferences/        # новый домен (образец: groups/)
│   ├── ConferenceMetrics.kt                            # webchat_conference_* (FR-018)
│   ├── api/
│   │   ├── ConferenceController.kt                     # №44–№53 (auth)
│   │   ├── GuestJoinController.kt                      # №54–№55 (public, rate-limited)
│   │   ├── ConferencesExceptionHandler.kt              # ProblemDetail + errors-коды
│   │   └── dto/ConferenceDtos.kt
│   ├── domain/
│   │   ├── model/                                      # Conference, ConferenceParticipant,
│   │   │                                               # GuestInvite, ParticipantRestrictions, Identity
│   │   ├── service/                                    # ConferenceService, JoinTokenService,
│   │   │                                               # ModerationService, GuestInviteService,
│   │   │                                               # ConferenceLifecycleService (вебхуки/финиш)
│   │   └── port/                                       # ConferenceRepository, ParticipantRepository,
│   │                                                   # GuestInviteRepository, LiveKitAdminClient,
│   │                                                   # ChatMembershipChecker (адаптер над 006)
│   ├── repository/                                     # Jdbc*-репозитории (конвенция JdbcTemplate)
│   ├── livekit/                                        # адаптеры: LiveKitServerAdminClient (Kotlin SDK),
│   │                                                   # LiveKitWebhookController (+WebhookReceiver),
│   │                                                   # GuestInviteTokens (Nimbus, ES256 nonce)
│   └── scheduler/ConferenceLifecycleScheduler.kt       # max-duration, warning, sweeper (SC-007)
├── src/main/kotlin/webchat/backend/chats/…             # эволюция: системные сообщения (порт + kind/payload)
├── src/main/kotlin/webchat/backend/groups/…            # событие MemberRemoved (in-process, для FR-003 edge)
├── src/main/kotlin/webchat/backend/config/
│   ├── SecurityConfig.kt                               # +3 permitAll: invite-info, join-by-invite, вебхук (строка вебхука открывается одновременно с его контроллером, чтобы маршрут не был публичным до обработчика)
│   ├── RateLimitFilter.kt (auth/ratelimit)             # + строки per-IP бакетов публичных маршрутов
│   └── ConferencesProperties.kt                        # conferences.* (квоты/таймеры per-env)
├── src/main/resources/db/migration/V18__conferences.sql
├── src/test/kotlin/webchat/backend/conferences/…       # IT (Testcontainers PG/Redis/LiveKit) + unit
frontend/
├── src/conferences/                                    # новый модуль (ленивый чанк)
│   ├── api/conferences.ts                              # + generated schema.d.ts
│   ├── hooks/ (useConference, useConferenceRealtime, useDevices)
│   ├── components/ (PreJoinScreen, ConferenceRoom, MediaControls,
│   │                ParticipantsPanel, ModerationPanel, ConferenceBadge)
│   ├── pages/ (ConferencePage, GuestJoinPage)
│   └── conferences.css
├── src/chats/components/ChatHeader.tsx                 # кнопки «видеозвонок»/«аудиозвонок»
├── src/chats/… (рендер системного сообщения-виджета, SSE-индикатор «звонок идёт»)
├── src/App.tsx                                         # маршруты /conference/:id (protected), /join/:inviteToken (public)
└── tests/e2e/conferences.spec.ts                       # Playwright: звонок двух вкладок против локального LiveKit
contracts/openapi.yaml                                  # аддитивно №44–№55, minor 0.11.0
deploy/
├── local/docker-compose.yml                            # + livekit (--dev, key/secret, webhook→backend)
└── k8s/{base,overlays/dev}/livekit.yaml                # Deployment/Service/порты/TURN (research §11)
load/k6/conferences.smoke.js                            # нагрузочный smoke контрольной плоскости
```

**Structure Decision**: web-приложение (backend + frontend, вариант шаблона №2) расширяется
новыми доменными модулями `backend/.../conferences/` и `frontend/src/conferences/` по
сложившейся конвенции доменов (groups/chats/presence) — без новых сборочных единиц и сервисов
(FR-017, принципы II/VII). Публичная гостевая страница и экран конференции — лениво
загружаемые чанки SPA, чтобы тяжёлый `livekit-client` не попадал в основной бандл (research §10).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица не заполняется.
