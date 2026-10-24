# Contract: Интеграция с медиа-плоскостью LiveKit (внутренний)

**Feature**: 009-video-conferences | **Статус**: внутренний контракт — НЕ входит в публичный
OpenAPI (FR-016 регулирует публичное API; данный документ — контракт бэкенд↔медиа-плоскость)

Фиксирует: конфигурацию сервера LiveKit, комнат, токенов, используемые серверные API (Twirp),
вебхуки и data-канал. Основание решений — [research.md §3–§7/§11](../research.md); проверено по
официальной документации LiveKit (access tokens & grants; Room service API; Webhooks & events;
Self-hosting) на 2026-10-15.

## 1. Конфигурация (per-env, секреты — только env)

| Параметр | Где | Значение v1 |
|---|---|---|
| `LIVEKIT_URL` (wss/http) | backend `livekit.url` | ингресс LiveKit (dev: `http://livekit:7880`) |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | backend + конфиг LiveKit | пара для подписи токенов/вебхуков; ротация — перезапуском сторон (v1) |
| `webhook.api_key` + `webhook.urls` | конфиг LiveKit | ключ подписи + `http://backend:8080/api/internal/conferences/livekit/webhook` |
| `enable_remote_unmute: true` | конфиг LiveKit | обязательно: без него `MutePublishedTrack` запрещён (FR-008) |
| `rtc` порты | деплой | 7880 (сигналинг/Twirp, за ингрессом), 7881/tcp (ICE/TCP), UDP range или mux 7882, TURN/TLS 5349 (prod) |
| Redis | конфиг LiveKit (distributed mode) | prod — отдельный logical DB; dev — общий инстанс compose |

Флаги/порты/размещение в K8s (hostNetwork, один pod на ноду, drain ~5 ч) —
[research.md §11](../research.md), манифесты `deploy/k8s/{base,overlays/dev}/livekit.yaml`.

## 2. Комнаты

| Аспект | Правило |
|---|---|
| Имя | `c:{conferenceId}` — непрозрачно, без PII |
| Создание | при создании конференции (№44): `CreateRoom(name, empty_timeout={conferences.empty-room-timeout}, departure_timeout={conferences.departure-timeout}, max_participants={conferences.max-participants})` — Q19/Q23; self-heal: sweeper досоздаёт при утере |
| Завершение | `DeleteRoom` (модератор/лимит длительности) либо автоматически LiveKit (последний вышел + `departure_timeout`); следствие — вебхук `room_finished` |
| Источник истины подключений | LiveKit (`Room.num_participants`); БД подключений не хранит |

## 3. Токены подключений (HS256, `iss = LIVEKIT_API_KEY`)

| Клейм/grant | Член чата (№47) | Гость (№55) |
|---|---|---|
| `sub` (identity) | `u:{userId}:{connId}` (connId = свежий uuid на каждый минт) | `g:{connId}` |
| `name` | username пользователя | введённое имя (валидировано: trim, 1–50) |
| `video.room` / `roomJoin` | `c:{conferenceId}` / `true` | то же |
| `video.canSubscribe` | `true` | `true` |
| `video.canPublish` | `true`, либо `false` при `speech_restricted` (право слова, Q4=A) или при всех трёх точечных одновременно (T094: пустой остаток источников означал бы «разрешено всё») | `true` |
| `video.canPublishSources` | все источники минус ограниченные: `mic_restricted` → − microphone; `camera_restricted` → − camera; `screen_restricted` → − screen_share, screen_share_audio (Q22=A); пустой набор = «все разрешены» (семантика LiveKit) — из домена не возникает: все три точечных кодируются canPublish=false (T094) | не урезаются (Q1=C: видео/экран включаются в звонке; старт «только микрофон» — клиентское состояние, soft как Q3=A) |
| `video.canPublishData` | `false` (v1) | `false` |
| админ-grants (`roomAdmin/roomCreate/roomList/roomRecord/ingressAdmin`) | **никогда клиенту** — вся модерация серверная (FR-008) | никогда |
| `metadata` (participant) | `{"role":"moderator"|"participant","guest":false}` | `{"role":"participant","guest":true,"inviteId":"<uuid>"}` — поле для отзыва ссылки (§6) |
| TTL | `conferences.token-ttl` (дефолт 900 c): self-hosted LiveKit **не ревоцирует** токены — короткий TTL обязателен; подключённым LiveKit продлевает токен сам (token refresh) | то же |

Админ-токен бэкенда для Twirp (grants `roomCreate`+`roomList`+`roomAdmin`) минтуется тем же
ключом, живёт в памяти адаптера, клиентам не выдаётся.

## 4. Серверные API (Twirp over HTTP, `POST /twirp/livekit.RoomService/<Method>`)

| Метод | Использование |
|---|---|
| `CreateRoom` | создание комнаты с таймаутами/квотой (§2) |
| `DeleteRoom` | завершение модератором (№48) и по max-duration (research §7) |
| `ListRooms` | sweeper: существование комнаты (SC-007), восстановление `created` без комнаты |
| `ListParticipants` | pre-check квоты при минте; поиск гостей по `metadata.inviteId` при отзыве; отключение при исключении из чата (по префиксу `u:{userId}:`) |
| `RemoveParticipant` | кик (№51); отключение при отзыве ссылки (Q13=B); отключение при исключении из чата (FR-003 edge); разрыв при превышении max-duration |
| `UpdateParticipant` | применение ограничений живому подключению: `permission.can_publish_sources`/`can_publish` (§3) + обновление `metadata` (broadcast ролей/пометок в UI); права при переподключении — из БД при новом минте (Q5=A) |
| `MutePublishedTrack` | немедленный мьют уже опубликованных треков (микрофон/камера/экран; требует `enable_remote_unmute`, §1) |
| `SendData` | предупреждение о лимите длительности: topic `conference.warning`, payload `{"type":"conference.warning","endsAt":…}`, всем участникам (достаёт гостей без SSE; Q16=B) |

Идемпотентность: все вызовы безопасно повторять (SET-семантика); адаптер ретраит с backoff.

## 5. Вебхуки: `POST /api/internal/conferences/livekit/webhook`

| Аспект | Правило |
|---|---|
| Доступ | `permitAll` в SecurityConfig (LiveKit не умеет Bearer) + per-IP бакет `RateLimitFilter` |
| Аутентификация | header `Authorization: Bearer <JWT>` (ключ вебхука), JWT содержит sha256-хеш тела; проверка `WebhookReceiver.receive(rawBody, authHeader)` по **сырому** телу (`Content-Type: application/webhook+json`, приём как `byte[]`) |
| Обрабатываемые события | `room_started` → 200/no-op (комната создаётся eagerly при №44 — вебхук приходит до входа участников); первый `participant_joined` при статусе `created` → `created→active` + SSE `conference.started`; `room_finished` → `finalize` + SSE `conference.ended`; `participant_joined`/`participant_left` → метки `last_joined_at`/`last_left_at` (principals по префиксу identity) и метрики; прочие события игнорируются (200) |
| Идемпотентность | условные переходы статуса в БД + `event.id` (дедуп в рамках окна ретраев LiveKit); ретраи доставки LiveKit безопасны |
| Доступность | 200 всегда, кроме невалидной подписи (401) — валидные события не теряются из-за ошибок обработки |
| Приватность | identity/room name без PII (§2/§3); payload вебхука не логируется |

## 6. Отзыв гостевой ссылки и исключение из чата (быстрые пути)

1. Отзыв/перегенерация инвайта (№52/№53): транзакция статуса → post-commit `ListParticipants`
   → все с `metadata.inviteId = {id}` → `RemoveParticipant` каждому (Q13=B/Q17=A).
2. Исключение/выход из чата (006): in-process `MemberRemoved(chatId, userId)` → post-commit
   `ListParticipants` → все с префиксом `u:{userId}:` → `RemoveParticipant` (FR-003 edge);
   повторный минт токена блокируется membership-check'ем №47.

## 7. Тестовый контракт

- IT (Testcontainers): контейнер `livekit/livekit-server --dev` (single-node без Redis),
  `LIVEKIT_API_KEY/SECRET` в тестовом профиле; проверяются: минт→подключение не выполняется
  (медиа из CI не гоняем), grants-вычисление, `CreateRoom`-параметры, подпись вебхука
  (валидный/невалидный Authorization), идемпотентные переходы, отзыв по `metadata.inviteId`.
- Полный медиа-путь (подключение, треки, мьюты) — Playwright e2e и ручная проверка против
  `deploy/local` LiveKit: [quickstart.md](../quickstart.md).
