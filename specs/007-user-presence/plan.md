# Implementation Plan: Присутствие пользователей (статус «онлайн»)

**Branch**: `007-user-presence` | **Date**: 2026-10-01 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/007-user-presence/spec.md`

## Summary

Присутствие пользователя = «онлайн» ⟺ ≥1 живой регистрации подключения к существующему SSE-каналу №18 (мультидевайс). Регистрации — записи с ограниченным сроком во внешнем хранилище (Redis), продлеваемые client-heartbeat'ом, с самоистечением и гистерезисом публикации «офлайн» (45 c) против флапа; инстансы stateless (конституция II). Изменения публикуются событием `presence.updated` в существующие per-user каналы `rt:user:{id}` только аудитории видимости (общий чат ∪ «есть в контактах наблюдающего», минус блок-пары); режим «невидимки» (per-user, в PG) замораживает статус окружения как «офлайн». Снимок для отображаемых поверхностей — batch REST-запросом в цикле (re)connect 005; сходимость «событие/снимок» — по монотонной ревизии `rev`. Терминология: «снапшот» ≡ «снимок» (spec.md) — один и тот же batch-запрос №36.

## Technical Context

**Language/Version**: Kotlin 2.2.21 (JVM 21), TypeScript 5.9.3 strict (React 19.2.8)

**Primary Dependencies**: Spring Boot 3.5.16 (web/SSE `SseEmitter`, security OAuth2 RS, data-redis Lettuce, JDBC, actuator/Micrometer), Flyway, Vite 8, Vitest 5, openapi-typescript codegen

**Storage**: Redis 7 — эфемерное состояние присутствия (регистрации ZSET с expiry-score, очереди-триггеры, опубликованный статус, ревизии); PostgreSQL 17 — durable: `users.presence_hidden` (настройка «невидимки», V15), чтение аудитории видимости (чаты/контакты/блокировки)

**Testing**: JUnit 5 + Testcontainers (PG+Redis, как `AbstractIntegrationTest`), параметризованные окна (TTL/гистерезис) в тест-профиле; Vitest для фронтенда; k6 smoke

**Target Platform**: Linux/K8s (stateless-поды), браузеры SPA + встраиваемый виджет

**Project Type**: web-service (backend-пакет `presence/`) + React SPA/widget (общий API-клиент)

**Performance Goals**: SC-001 — 95% переключений видимы ≤2 c; SC-006 — снапшот ≤2 c (95%); heartbeat-нагрузка и фанаут — в пределах бюджетов конституции II (расчёт ниже)

**Constraints**: stateless-инстансы (II); additive-only OpenAPI 0.6.0 → 0.7.0 (IV); события — at-most-once канал с идемпотентным клиентским слиянием (как 004); RFC 9457 problem codes; Bearer-only (V)

**Scale/Scope**: 1M CCU, ~1.3 регистрации/пользователя; аудитория видимости ~50 (медиана; консервативная оценка для расчёта — см. §Load Estimate), до ~2000 у участника многих групп

## Constitution Check

*GATE: Обязателен до research-раунда SDD; повторная проверка — после design-раунда.*

| Принцип | Статус | Обоснование |
|---|---|---|
| I. Spec-Driven | PASS | Артефакты spec→plan→research→data-model→contracts→quickstart до кода; задачи — в tasks.md (раунд /speckit.tasks) |
| II. Stateless + бюджеты | PASS | Всё состояние присутствия — Redis (TTL/score-expiry) + одна колонка PG; инстансы не держат статус; расчёт нагрузки — §Load Estimate. Падение инстанса не искажает сигнал (US3): регистрации самоистекают ≤ TTL+гистерезис |
| III. Exactly-once доставки | PASS | Принцип регламентирует доставку сообщений (durable-сущности); события присутствия — сигналы состояния в at-most-once канале №18 (конвенция 004 `chat.read`). Мандат «тесты на потерю/дубль/порядок для любого пути доставки» исполнен: дубль и потеря presence-кадра покрыты PresenceIT/Vitest (T011/T012: сходимость снапшотом по max(rev)); сам путь №18 покрыт loss/order-тестами 004/005 (delivery-resilience.js, реконнект-цикл). История не накапливается (FR-001) |
| IV. API-First | PASS | Новые эндпоинты №36–38 + SSE-событие `presence.updated` + opening-фрейм `connected` — только аддитивно в `contracts/openapi.yaml` 0.7.0; TS-типы генерируются; unknown `event:` игнорируются (обратная совместимость) |
| V. Security/Privacy | PASS | Аудитория видимости вычисляется на сервере (PG: чаты ∪ контакты − блок-пары); «невидимка» неотличима от офлайна; «нет доступа» без различения «офлайн»/«скрыто» (FR-007); Bearer-only, токен не в URL |
| VI. Test-First | PASS | Интеграционные тесты: мультидевайс, метро-серия, self-expiry, приватность/адресация, невидимка, гонка снапшот/событие; Vitest на rev-merge и нейтральный индикатор; k6 presence-smoke (SC-007) |
| VII. YAGNI | PASS | Без нового брокера/канала (reuse Redis Pub/Sub + SSE №18); без группового UI, истории, last-seen; отвергнутые альтернативы — research.md §A1–A3 (хранение, механика истечения, heartbeat) и §B1/§C1/§C3/§D1 |
| VIII. SOLID | PASS | Пакет `presence/`: домен зависит от портов `PresenceStore` / `VisibilityAudienceReader` (DIP), Redis/JDBC — адаптеры; один пакет — одна фича (SRP); новое событие — расширение существующего канала без правки ядра доставки |

*Примечание к принципу III* — не является нарушением: принцип III регламентирует доставку сообщений (durable-сущности); для эфемерных сигналов состояния конвенция at-most-once + сходимость установлена 004 и переиспользуется. Complexity Tracking не заполняется — нарушение конституции отсутствует, интерпретация зафиксирована здесь.

## Load Estimate (SC-007, конституция II)

Пиковая модель: 1M CCU, 1.3 устройства/пользователя, медианная аудитория видимости ~50, хвост (участник 10 групп по 200) ~2000.

| Поток | Интенсивность (пик) | Нагрузка | Оценка |
|---|---|---|---|
| Client heartbeat (TTL 90 c, интервал 30 c) | ~43k RPS | Lua: ZADD `presence:alive:{u}` + ZADD `presence:watch` (O(log n), мелкие ZSET) | Redis-кластер: сопоставимо с существующим Bucket4j/RL-потоком; на порядок ниже msg-бюджета 100k/s |
| Публикации переходов | ~14/s средне, всплески при штормах | 1 SQL аудитории (PG, индексы V10/V11/V14) + ~50…2000 PUBLISH `rt:user:{id}` | ≪ фанаута сообщений групп (006: до 200 публикует на сообщение) |
| Шторм реконнектов (падение инстанса с 50k коннектов) | 50k offq-записей; возврат в окно 45 c отменяет | Гистерезис агрегирует: публикации ≈ только числу невозвратившихся; счётчик подавленных — метрика FR-009 | Погашен design'ом (FR-004), поглощается Redis PUBLISH-бюджетом |
| Снапшот при (re)connect | ~300 RPS batch (≤200 userIds) | MGET-подобное чтение pub/rev + PG-проверка политики batch'ем | Ниже sync-эндпоинта №26 005 |
| Deadline/offq-поллеры | 2 × ZRANGEBYSCORE (LIMIT batch) в 1 c на инстанс | Дешёвые сканы маленьких голов ZSET | Идемпотентны, конкуренция инстансов безопасна (CAS по `presence:pub`) |

Вывод: бюджеты II соблюдены; полномасштабная валидация пиков — платформенная фича 016, здесь — k6 smoke без деградации messaging (SC-007).

## Project Structure

### Documentation (this feature)

```text
specs/007-user-presence/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── presence-api.md
│   └── presence-events.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/webchat/backend/presence/
├── api/
│   ├── PresenceController.kt          # №36 GET /users/me/presence?userIds= (batch-снапшот), №37 heartbeat
│   └── PresenceSettingsController.kt  # №38 GET/PUT /users/me/presence/settings («невидимка»)
├── domain/
│   ├── PresenceService.kt             # переходы статуса, гистерезис, CAS публикаций, «невидимка»
│   └── port/
│       ├── PresenceStore.kt           # регистрации/статусы/очереди (атомарные операции)
│       ├── VisibilityAudienceReader.kt # аудитория видимости пользователя
│       └── PresenceEventPublisher.kt  # событие присутствия в rt:user:{id} (расширение RealtimeEventPublisher)
├── repository/
│   ├── RedisPresenceStore.kt          # ZSET-структуры, Lua-CAS (см. data-model §3)
│   └── JdbcVisibilityAudienceReader.kt # SQL: chat_participants ∪ user_contacts − user_blocks
├── scheduler/
│   └── PresenceTransitionScheduler.kt # поллеры presence:watch (истечения) и presence:offq (гистерезис)
└── PresenceMetrics.kt                 # FR-009: gauges/counters

backend/src/main/resources/db/migration/
└── V15__presence_settings.sql         # users.presence_hidden BOOLEAN NOT NULL DEFAULT FALSE

backend/src/test/kotlin/webchat/backend/presence/    # PresenceIT (вкл. снапшот-сценарии), PresenceHysteresisIT («метро»),
                                                    # PresenceExpiryIT, PresencePrivacyIT, PresenceInvisibleIT

frontend/src/presence/
├── PresenceIndicator.tsx             # точка + доступная подпись (online/offline/unknown), aria
├── presenceStore.ts                  # Map<userId,{status,rev}> — слияние по max(rev)
├── usePresence.ts                    # снапшот видимых поверхностей на (re)connect + события №18
├── presenceApi.ts                    # №36–38 + heartbeat-планировщик (connectionId из фрейма connected)
└── __tests__/

frontend/src/chats/components/        # интеграция: ChatListItem (direct), ContactList; заголовок 1:1 — DirectChatHeader в chats/pages/MessengerPage.tsx
frontend/src/settings/pages/
└── PresenceSettingsPage.tsx          # №38: переключатель «невидимки» в настройках профиля (T034; по образцу SecurityPage.tsx, регистрация в роутинге настроек)
load/k6/
└── presence.smoke.js                 # k6: подключения+heartbeat+переключения; отсутствие деградации messaging
```

**Structure Decision**: Конвенция monorepo 001–006: package-by-feature в `backend/…/webchat/backend/`, фича-модуль `frontend/src/presence/` с точечной интеграцией в существующие компоненты; contracts — аддитивная правка корневого `contracts/openapi.yaml` (0.6.0 → 0.7.0). Точечные интеграции вне `presence/`: `backend/…/realtime/` (opening-фрейм `connected` + хуки жизненного цикла подключений — T013), `backend/…/auth/` (logout-очистка регистраций сессии — T029), `frontend/src/settings/pages/` (страница «невидимки» №38 — T034). Единообразие виджета — shared-модулями `frontend/src/presence/` (общая библиотека, конституция IV), отдельная widget-задача не выделяется.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений нет — таблица пуста.
