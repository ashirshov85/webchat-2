# Contract: Изменения публичного API — видеоконференции (OpenAPI)

**Feature**: 009-video-conferences | **Источник истины**: `contracts/openapi.yaml`

Изменения к контракту 008b: 12 новых endpoints (№44–№55, тег `conferences`), 2 новых `event:`-типа
SSE-канала №18 (`conference.started`, `conference.ended` — норматив в
[realtime-conference-events.md](./realtime-conference-events.md)), расширение схемы `Message`
(nullable `senderId` + `kind` + `payload` — системные виджеты). Изменение **additive** (minor
`0.10.0 → 0.11.0`, без BREAKING.md; nullable-дельта `Message` — прецедент 006 с `peer`).
Конвейер 001 (vacuum → openapi-typescript → drift-check → oasdiff) применяется без изменений;
TS-типы фронтенда — из генерации (`pnpm generate:api`). Внутренний контракт медиа-плоскости
(Twirp/webhook LiveKit) — вне публичного API: [livekit-integration.md](./livekit-integration.md).

Операции №44–№53 требуют `Authorization: Bearer <accessToken>`; авторизация — active-членство в
чате конференции на каждом запросе (FR-003) + роль модератора для управления (FR-008/FR-009).
Операции №54–№55 — **публичные** (единственное контролируемое исключение из «все API только для
аутентифицированных», FR-009): без Bearer, с per-IP rate limiting (паттерн 002). Ошибки —
RFC 9457 `Problem` (`errors: map<string, string[]>`, конвенция 002).

## 1. Приватность и авторизация

- Членские операции (№45–№53): не-член чата (включая бывшего) и несуществующая конференция
  отвечают одинаково `404 conference_not_found` — существование не раскрывается (прецедент 006).
- №44/№47 (создание и вход): отказ не-члена — `403 conference_forbidden` (прямо требуется
  FR-003/US1-3: «не-участник с чужой ссылкой получает 403»), без деталей конференции.
- Модерация (№48–№53): участник без роли модератора — `403 moderator_required`; модераторы
  равноправны, действия (включая снятие роли/кик) применимы к любому участнику, отдельной роли
  «владелец» нет (Q21=A).
- Публичные операции (№54–№55): раскрытие минимально — статус ссылки и live-флаг без деталей
  чата; подделанный/истёкший/отозванный nonce — единообразный отказ без различения причин
  подделки (edge).

## 2. Операции тега `conferences` (новые)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| №44 | `POST /api/v1/chats/{chatId}/conferences` | `{mode: "video" \| "audio"}` (стартовые дефолты pre-join, FR-001) | `201 ConferenceView` + системное сообщение-приглашение в историю чата (FR-002); при уже активной конференции в чате — `200 ConferenceView` существующей (сообщение не дублируется, edge) | `404 chat_not_found`, `403 conference_forbidden`, `409 conference_e2ee_unsupported` (граница v1 с 010), `503 conference_media_unavailable` (медиа-плоскость недоступна при создании — edge) |
| №45 | `GET /api/v1/conferences/{conferenceId}` | — | `200 ConferenceView` (включая `status='ended'` — по нему клик «Подключиться» показывает «конференция завершена») | `404 conference_not_found` |
| №46 | `GET /api/v1/conferences/{conferenceId}/me` | — | `200 MeView` — роль и ограничения принципала для блокировки тумблеров pre-join (FR-007) | `404 conference_not_found` |
| №47 | `POST /api/v1/conferences/{conferenceId}/join` | — | `200 JoinTicket` — короткоживущий LiveKit-токен + `wsUrl` + `identity` (grants вычислены из роли/ограничений, research §4) | `403 conference_forbidden`, `404 conference_not_found`, `409 conference_ended \| conference_full` (общая квота с гостями, Q23=A), `503 conference_media_unavailable` |
| №48 | `POST /api/v1/conferences/{conferenceId}/end` | — | `204` — завершение модератором: все отключаются, SSE `conference.ended` (US3-6) | `403 moderator_required`, `404 conference_not_found`, `409 conference_ended` |
| №49 | `PUT /api/v1/conferences/{conferenceId}/participants/{identity}/restrictions` | `{micRestricted, cameraRestricted, screenRestricted, speechRestricted: boolean}` (полное SET-состояние, идемпотентно) | `200 ParticipantView` — применяется немедленно (SC-005), персистентно до конца конференции (Q5/Q22=A) | `403 moderator_required`, `404 conference_not_found \| participant_not_found` |
| №50 | `PUT /api/v1/conferences/{conferenceId}/participants/{identity}/role` | `{role: "moderator" \| "participant"}` | `200 ParticipantView` — роль персистентна, включая переподключение (Q9=A) | `403 moderator_required`, `404 …` |
| №51 | `DELETE /api/v1/conferences/{conferenceId}/participants/{identity}` | — | `204` — кик: немедленный разрыв подключения; переподключение по действующей ссылке допустимо (кик ≠ бан, edge) | `403 moderator_required`, `404 …` |
| №52 | `POST /api/v1/conferences/{conferenceId}/guest-invite` | — | `201 {inviteUrl, expiresAt}` — ссылка с подписанным nonce (TTL 24 ч по умолчанию, Q19=A); генерация отзывает предыдущую (Q17=A); в чат не постится | `403 moderator_required`, `404 conference_not_found`, `409 conference_ended` |
| №53 | `DELETE /api/v1/conferences/{conferenceId}/guest-invite` | — | `204` — отзыв: вошедшие по ссылке гости отключаются немедленно (Q13=B), новые входы блокируются | `403 moderator_required`, `404 conference_not_found`, `404 guest_invite_not_found` (нет активной) |

## 3. Публичные операции тега `conferences` (без аутентификации, rate-limited)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| №54 | `GET /api/v1/conferences/invite-info?inviteToken={nonce}` | — | `200 {valid: boolean, reason?: "expired" \| "revoked" \| "ended", live?: boolean}` — минимум раскрытия, для гостевой join-страницы до ввода имени (SC-003 ≤ 3 действий) | `429 rate_limited` (пер-IP бакет, дефолт 30/мин) |
| №55 | `POST /api/v1/conferences/join-by-invite` | `{inviteToken: string, name: string}` (имя: trim, 1–50, Q19/Q7=A) | `200 JoinTicket` — гостевой LiveKit-токен: identity `g:{connId}`, пометка «гость», стартовое состояние клиента «только микрофон» (soft, Q1=C/Q3=A), прав модерации нет | `429 rate_limited` (пер-IP бакет, дефолт 10/мин); `404 invite_invalid \| invite_not_found`; `409 invite_expired \| invite_revoked \| conference_ended \| conference_full`; `422 name_invalid` |

Кик гостя и отзыв ссылки не блокируют повторный вход, пока ссылка действительна (многоразовая,
Q2=A); радикальная мера — отзыв (№53).

## 4. Схемы (components.schemas, новые/изменённые)

- **ConferenceView**: `{id: uuid, chatId: uuid, status: "created"|"active"|"ended", mode:
  "video"|"audio", createdBy: uuid, createdAt, startedAt?, endedAt?, endReason?,
  maxParticipants: int, activeParticipants: int}` (число подключений — из LiveKit на момент
  запроса).
- **MeView**: `{role, micRestricted, cameraRestricted, screenRestricted, speechRestricted}`.
- **ParticipantView**: `{identity, name, role, guest: boolean, micRestricted, cameraRestricted,
  screenRestricted, speechRestricted, online: boolean}`.
- **JoinTicket**: `{token: string (LiveKit JWT, TTL = conferences.token-ttl, дефолт 900 c),
  wsUrl: string, identity: string, name: string, expiresAt}`.
- **Message (изменение, additive)**: `senderId` → nullable (NULL у `kind='system'`); новое поле
  `kind: "user"|"system"` (default `"user"` — старые ответы семантически совместимы); новое поле
  `payload: object | null` — для `kind='system'`: `{"type":"conference_invite","conferenceId":
  uuid,"mode":"video"|"audio"}`; неизвестные клиентам `payload.type` обязаны рендериться
  нейтральным плейсхолдером (OCP, конституция IV).
- **GuestInviteView**: `{inviteUrl: string, expiresAt}`.

## 5. Фиксированные константы фичи (дублируются в контракте)

Все переопределяются конфигурацией per-env (Q19=A, research §9): таймаут пустой комнаты —
60 c (комната LiveKit `departure_timeout`/`empty_timeout`); TTL гостевой ссылки — 24 ч; максимум
длительности конференции — 4 ч (+предупреждение за 5 мин, Q16=B); имя гостя — ≤ 50 символов;
TTL токена подключения — 900 c (дефолт); `maxParticipants` — per-env (дефолт 16 — канон чисел; dev-стенд ≤ 16, SC-008);
гости и члены чата делят общую квоту (Q23=A).

## 6. Совместимость и генерация

- №44–№55 — новые пути; №18 (SSE) — новые `event:`-типы; `Message` — nullable/new поля:
  oasdiff `breaking --fail-on ERR` проходит как minor (прецедент 006 `peer`).
- Frontend: `pnpm generate:api` обновляет `schema.d.ts`; рендер `kind='system'` добавляется в
  компоненты сообщений; старые виджеты-клиенты (без `kind`) продолжают работать (default).
- Публичные пути №54–№55 и вебхук (внутренний, вне контракта) добавляются в `permitAll`
  SecurityConfig и в таблицу бакетов `RateLimitFilter` (research §6) — вместе это и есть
  «контролируемое исключение» FR-009.
