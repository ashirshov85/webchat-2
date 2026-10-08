# Implementation Plan: Социальные сигналы — typing-индикатор, lastSeen, отображаемые имена, per-chat звук

**Branch**: `008a-social-signals` | **Date**: 2026-10-08 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008a-social-signals/spec.md`

## Summary

Фича 008a добавляет четыре «социальных» сигнала мессенджера поверх готовых механизмов 004–008: (1) отображаемые имена — профильное `displayName` (№39, колонка `users.display_name`) и персональный alias контакта (№40, колонка `user_contacts.alias`) с единой цепочкой разрешения `alias → displayName → username` на всех поверхностях (чаты, контакты, группы, поиск) и инициалами аватаров из отображаемого имени при цвете от стабильного `username`; (2) эфемерный typing-индикатор — операция №41 `POST /chats/{chatId}/typing` с флуд-лимитом, серверное состояние `typing:*` в Redis с самоистечением (ZSET + poller по образцу offq-модели 007), доставка только живым участникам чата новыми событиями `typing.started` / `typing.stopped` канала №18 (без персистентности и реплея); (3) lastSeen — фиксация времени последней активности presence-канала в Redis-ключе `presence:lastseen:{userId}` (обновление в Lua-ногах register/renew/unregister), раскрытие опциональным полем `lastSeenAt` в №36-снапшоте и событии `presence.updated` строго в рамках приватности 007 (инкогнито/блок-пары/нет данных — единый нейтральный фолбэк «Был в сети — давно»); (4) per-chat звук — колонка `chat_participants.sound_enabled` (умолчание TRUE), операция №42 `PUT /chats/{chatId}/sound` + событие `chat.sound.updated` в собственный канал пользователя для синхронизации устройств. Контракт 0.8.0 → 0.9.0 строго аддитивно (новые опциональные поля, 4 операции, 3 типа событий), TS-типы регенерируются конвейером 001. Подход: максимальное переиспользование — SSE-канал №18 (игнорирование неизвестных `event:` — механизм расширения), `RealtimeEventPublisher` + Redis Pub/Sub `rt:user:{id}`, `UserRateLimiter` (429 `flood_limit` + Retry-After), poller-паттерн `PresenceTransitionScheduler`, миграционная конвенция V12/V14/V15 (аддитивные NOT NULL DEFAULT колонки).

## Technical Context

**Language/Version**: Kotlin 2.2.21 (JVM 21, virtual threads), Spring Boot 3.5.16; TypeScript 5.9.3 strict (React 19.2.8)

**Primary Dependencies**: существующие — Spring Boot (web/security/jdbc/actuator/data-redis), Bucket4j (флуд-лимиты), Flyway + PostgreSQL 17, Redis 7 (Lettuce + Pub/Sub), Micrometer/Prometheus; фронтенд — React, Vitest + Testing Library, Playwright (visual), openapi-typescript. Новых зависимостей нет.

**Storage**: PostgreSQL 17 — новая миграция `V16__social_signals.sql`: `users.display_name VARCHAR(64) NULL`, `user_contacts.alias VARCHAR(64) NULL` (+ CHECK btrim), `chat_participants.sound_enabled BOOLEAN NOT NULL DEFAULT TRUE`; Redis 7 — эфемерное состояние набора: `typing:active:{chatId}` (ZSET userId→expiresAt) + `typing:watch` (глобальный ZSET для poller'а), ключ `presence:lastseen:{userId}` (epoch ms, TTL 30 дней); персистентного хранения typing-состояния нет (SC-003).

**Testing**: backend — JUnit 5 + AssertJ + Spring Boot Test + Testcontainers (PG 17 / Redis 7), паттерны `AbstractIntegrationTest` / `MessagingTestSupport.openUserEvents` (raw SSE-фреймы) / `PresenceTestSupport` (быстрые окна) / `assertConformsToSchema` против живого `contracts/openapi.yaml`; frontend — Vitest + Testing Library (мок-паттерн `MessengerPage.sound.test.tsx`: vi.hoisted-мешок + ручной SSE-стаб `installStream`), Playwright visual (ru-RU, Europe/Moscow, допуск 2% пикселей).

**Target Platform**: Linux-сервер (stateless-поды, Kubernetes) + браузеры SPA (последние 2 версии Chrome/Edge/Firefox/Safari, desktop+мобильные).

**Project Type**: web-app — полный стек существующего monorepo (backend + frontend + contracts).

**Performance Goals**: SC-001 — 95% сигналов начала/завершения набора отображаются/исчезают ≤ 2 с; SC-006 — синхронизация звука между устройствами ≤ 2 с (95%); SC-008 — нагрузочный smoke с активным набором текста: Δp99 push ≤ 10% к базовому прогону (шаблон `load/k6/presence.smoke.js`).

**Constraints**: строго аддитивные изменения публичного контракта (SC-007, oasdiff breaking-gate в CI); эфемерность typing (0 следов в персистентном хранилище, SC-003); приватность lastSeen — 0 утечек (SC-002/SC-005); существующие проверки 004–008 без изменения ожиданий; флуд-лимиты по дом-конвенции 429 `flood_limit` + Retry-After; блок-пары не обмениваются новыми событиями.

**Scale/Scope**: бюджеты конституции II — расчёт нагрузки typing-событиями приведён в [research.md §E](./research.md) (пик ~50k typing-событий/с при 1M CCU ≈ 50% бюджетa сообщений на ~60-байтовые кадры; горизонтальное масштабирование Pub/Sub + stateless-поды). Объём: ~1 миграция, 4 новых REST-операции (№39–№42), 3 новых SSE-события, ~6 затронутых представлений, ~10 backend-классов, ~12 frontend-файлов.

## Constitution Check

*GATE: Обязателен до research-раунда SDD; повторная проверка — после design-раунда.*

| Принцип | Статус | Обоснование |
|---|---|---|
| I. Spec-Driven | PASS | Артефакты spec→plan→research→data-model→contracts→quickstart до кода; задачи — в tasks.md (раунд /speckit.tasks) |
| II. Stateless + бюджеты | PASS | Поды остаются stateless: typing-состояние и lastSeen — только в Redis (внешнее хранилище), sound/displayName/alias — в PG; все новые ключи TTL-ограничены или durable в PG. Расчёт нагрузки typing приведён в research §E (≤ ~50k событий/с при пиковых допущениях — в пределах бюджетов, кадр ~60 байт против килобайтных сообщений); подтверждение — нагрузочный smoke SC-008 (Δp99 push ≤ 10%, шаблон 007) |
| III. Exactly-once доставки | PASS | Пути доставки сообщений не меняются; новые typing/sound-события — по конвенции канала №18 at-most-once (эфемерные, дребезг исключён флуд-лимитом + серверным окном); идемпотентность операций №39–№42 (последний-write-wins, repeat-safe); тесты дубли/порядка 004–005 не затрагиваются |
| IV. API-First | PASS | Все изменения — в `contracts/openapi.yaml` (0.8.0 → 0.9.0, additive): новые опциональные поля существующих схем, 4 новые операции, 3 новых типа событий канала №18; TS-типы регенерируются `generate:api`; oasdiff breaking-gate CI гарантирует 0 breaking (SC-007); клиенты 008, не знающие новых полей/событий, работают без изменений (игнорирование неизвестных `event:` — контракт №18) |
| V. Security/Privacy | PASS | Авторизация membership на №41/№42 (проверка участника чата); typing не доставляется блок-парам (расширение семантики 004 FR-020) и не покидает участников чата; lastSeen раскрывается только аудитории видимости 007, инкогнито/блок-пары — нейтральное отсутствие поля (неотличимо от «нет данных»); sound-настройка видна только владельцу; теги метрик — только словарные значения, без id |
| VI. Test-First | PASS | IT параллельно реализации: TypingIT (фрейминг/приватность/самоистечение/флуд), ProfileAliasIT, ChatSoundIT, PresenceLastSeenIT (приватность по образцу PresencePrivacyIT); контрактная валидация новых событий против живого openapi.yaml (`assertConformsToSchema`); Vitest-тесты клиентской цепочки имён/типинга/звука; Playwright-сценарии (typing-строка, «был в сети», bell вкл/выкл); нагрузочный smoke SC-008 |
| VII. YAGNI | PASS | Не вводятся: серверный поиск по displayName, realtime-событие смены имени (рефетч — допущение спеки), i18n, офлайн-очередь переключений звука, настройки звука «по участникам», реплей typing при реконнекте; отвергнутые альтернативы (keyspace-уведомления, WebSocket, отдельная таблица sound, DELETE-операция сброса alias) — research.md |
| VIII. SOLID | PASS | Typing — отдельный feature-срез в границах `chats/` (SRP: store-port + service + scheduler + controller), переиспользует порты `RealtimeEventPublisher`/`BlockRepository` (DIP); цепочка разрешения имени — единственная функция `resolveDisplayName` (одна причина изменения); никакого дублирования DTO-логики сверх уже существующей конвенции проекций `PublicUser` |

Нарушений нет — Complexity Tracking не заполняется.

## Project Structure

### Documentation (this feature)

```text
specs/008a-social-signals/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── api-contract.md        # REST-операции №39–№42, изменения схем, флуд-лимиты
│   ├── realtime-events.md     # события typing.started/stopped, chat.sound.updated,
│   │                          # расширенные presence.updated/№36 (lastSeenAt)
│   └── ui-behavior.md         # цепочка имён, типинг-строка, lastSeen-формат, bell
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
contracts/openapi.yaml                    # 0.8.0 → 0.9.0 additive: №39–№42, события,
                                          # опциональные поля PublicUser/ContactView/
                                          # ChatView/ChatListItem/PresenceStatusItem/
                                          # PresenceUpdatedEvent, новые схемы

backend/src/main/resources/db/migration/
└── V16__social_signals.sql               # users.display_name, user_contacts.alias (+CHECK),
                                          # chat_participants.sound_enabled DEFAULT TRUE

backend/src/main/kotlin/webchat/backend/
├── users/
│   ├── api/UsersController.kt            # + PUT /me/profile (№39): displayName set/clear
│   ├── api/dto/PublicUserResponse.kt     # + displayName?
│   └── domain/…                          # валидация (trim, 1..64), store-метод profиля
├── contacts/
│   ├── api/ContactController.kt          # + PUT /{userId}/alias (№40): set/reset
│   ├── api/dto/ContactDtos.kt            # ContactView + alias?; PublicUserView + displayName?
│   ├── domain/model/Contact.kt           # + alias
│   ├── domain/port/ContactRepository.kt  # + storeAlias, + aliasesOf(owner, ids)
│   └── repository/JdbcContactRepository.kt
├── chats/
│   ├── api/TypingController.kt           # НОВОЕ: POST /chats/{chatId}/typing (№41)
│   ├── api/ChatSoundController.kt        # НОВОЕ: PUT /chats/{chatId}/sound (№42)
│   ├── api/dto/ChatDtos.kt               # ChatPeerView + displayName?/alias?;
│   │                                     # ChatView/ChatListItemView + soundEnabled
│   ├── domain/service/TypingService.kt   # НОВОЕ: membership+block-гейт, продление/завершение,
│   │                                     # публикация typing.* участникам (без отправителя)
│   ├── domain/port/TypingStore.kt        # НОВОЕ: ZSET-состояние + watch-индекс (Redis)
│   ├── domain/port/RealtimeEventPublisher.kt  # + fanoutTypingEvent(toUserIds, event)
│   ├── repository/RedisTypingStore.kt    # НОВОЕ: Lua CAS/ZADD, самоистечение по score
│   ├── scheduler/TypingTransitionScheduler.kt # НОВОЕ: poller истёкших состояний → typing.stopped
│   │                                     # (образец PresenceTransitionScheduler.offq)
│   ├── TypingMetrics.kt                  # НОВОЕ: typing_events/flood_suppressed/state_expired
│   ├── domain/service/ChatService.kt     # + soundEnabled в проекциях; peer-алиасы в directView
│   └── repository/…                      # sound_enabled в chat_participants-чтении/записи
├── groups/domain/service/GroupService.kt # memberView + displayName?/alias? запросчика
├── presence/
│   ├── domain/PresenceService.kt         # lastSeen-фильтр в snapshot (hidden → без поля);
│   │                                     # lastSeenAt в offline-событии в рамках аудитории
│   ├── repository/RedisPresenceStore.kt  # presence:lastseen:{userId}: stamp в REGISTER/
│   │                                     # RENEW/UNREGISTER/CLEAR_SESSION Lua-ногах; чтение в SNAPSHOT
│   └── …                                 # (инкогнито-пакетное чтение presence_hidden)
└── config/…                              # chats.typing.* (окно повтора, TTL, poller), rate-limit-константы

backend/src/test/kotlin/webchat/backend/
├── chats/TypingIT.kt                     # НОВОЕ: фрейминг, эфемерность, блок-пары, флуд,
│                                         # самоистечение (быстрые окна), контракт-валидация
├── chats/ChatSoundIT.kt                  # НОВОЕ: идемпотентность, sync-событие, приватность
├── users/ProfileDisplayNameIT.kt         # НОВОЕ: валидация, представления, обратная совместимость
├── contacts/ContactAliasIT.kt            # НОВОЕ: set/reset, видимость только владельцу
└── presence/PresenceLastSeenIT.kt        # НОВОЕ: приватность (инкогнито/блок/посторонний),
                                          # точность, фолбэк-неотличимость

frontend/src/
├── api/schema.d.ts                       # регенерация (pnpm generate:api)
├── api/chats.ts                          # + типы №39–№42, событий; API-функции
│                                         # updateProfile, setContactAlias, sendTyping, setChatSound
├── ui/names.ts                           # НОВОЕ: resolveDisplayName(alias, displayName, username)
├── ui/avatar.ts / Avatar.tsx             # инициалы из отображаемого имени, цвет — от username
├── ui/sound.ts                           # per-chat mute в chime*, + «глухой щелчок» выключения
├── ui/time.ts                            # + lastSeenFormat(v, now): время сегодня / «D месяца, HH:MM»
├── presence/presenceStore.ts             # PresenceEntry + lastSeenAt?; merge из №36 и событий
├── presence/usePresence.ts               # + usePresenceEntry (status + lastSeenAt)
├── chats/hooks/useRealtime.ts            # + onTypingEvent(chatId,…), onChatSoundUpdated(…)
├── chats/hooks/useTyping.ts              # НОВОЕ: состояние «кто печатает» активного чата,
│                                         # клиентский дебаунс отправки start (первый символ,
│                                         # окно повтора 3 с) / stop (отправка/очистка/молчание)
├── chats/pages/MessengerPage.tsx         # проводка typing/sound-событиев, per-chat подсчёт
│                                         # входящих sync-батчей для звука, звук-состояние
├── chats/components/ChatHeader.tsx       # + bell-кнопка (.ch-btn, aria-pressed, .off),
│                                         # статус «Был в сети — …»
├── chats/components/TypingRow.tsx        # НОВОЕ: пузырь .typing-b с .tlamp-точками по прототипу
├── chats/components/MessageList.tsx      # подключение TypingRow после ленты
├── chats/components/ContactsModal.tsx    # + «Переименовать» (форма alias), сортировка/фильтр
│                                         # по цепочке имён
├── chats/components/ProfileModal.tsx     # + поле «Имя» (displayName)
└── chats/components/chat-header.css      # + .ch-btn.off, .typing-b/.tlamp/lampBlink

frontend/tests/visual/
└── social-signals.spec.ts                # НОВОЕ: typing-строка, «Был в сети — …», bell вкл/выкл,
                                          # переименование/профиль (снятки + e2e-фреймы)

load/k6/social-signals.smoke.js           # НОВОЕ: набор текста при сообщечном трафике (SC-008)
```

**Structure Decision**: Следуем конвенции monorepo 001–008 — package-by-feature в backend (`users`/`contacts`/`chats`/`presence` расширяются в своих границах; typing — новый вертикальный срез внутри `chats/`, т.к. привязан к чату и переиспользует его порты), фронтенд расширяет существующие модули (`ui` — примитивы имён/времени/звука, `presence` — store, `chats` — поверхности) плюс минимальные новые файлы (`useTyping`, `TypingRow`, `names.ts`). Realtime-расширение идёт через существующий демукситор `useRealtime` (рецепт добавления `presence.updated` из 007). Визуальные тесты — в `frontend/tests/visual/` по конвенции 008; нагрузочный smoke — в `load/k6/` по образцу `presence.smoke.js`. Docs-структура фичи — стандартная.

## Complexity Tracking

> Не заполняется — нарушений конституционной проверки нет.
