# Contract: Публичный API личных диалогов (OpenAPI)

**Feature**: 004-direct-messaging-core | **Источник истины**: `contracts/openapi.yaml`

Новые endpoints (REST, №11–24) + реалтайм-канал SSE (№18, детально —
[realtime-channel.md](./realtime-channel.md)). Изменение контракта — **только additive**
(minor `0.3.0 → 0.4.0`, без `BREAKING.md`); конвейер 001 (vacuum → openapi-typescript →
drift-check → oasdiff) применяется без изменений. TS-типы фронтенда — из генерации.

Все новые endpoints требуют `Authorization: Bearer <accessToken>` (scheme `bearerAuth`);
после них `anyRequest().authenticated()` SecurityConfig 002 не меняется. Ошибки — RFC 9457
`application/problem+json` (схема `Problem` с `errors: map<string, string[]>`, конвенция 002).
Авторизация на уровне ресурса — **membership диалога** (FR-002): не-участник получает `403`
(`not_participant`) на существующем чате; несуществующий — `404`.

Фиксированные константы (FR-003/008/011, дублируются в контракте): максимум текста — **4096
символов после trim**; страница истории — **50 сообщений**; флуд-лимит — **30 сообщений/мин
на пользователя**.

## 1. Диалоги и сообщения (tag `chats`)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| 11 | `POST /chats/ensure` | `{peerUserId: uuid}` | `201` `ChatView` (диалог создан) \| `200` `ChatView` (существующий; снимает `hidden` у вызывающего, удалённая история остаётся скрытой — водяной знак) | `404` `peer_not_found`; `422` `self_forbidden` (диалог с самим собой, FR-001/edge) |
| 12 | `GET /chats` | — | `200` `{chats: [ChatListItem]}` — серверная сортировка: последнее видимое сообщение (входящее **или** исходящее) по убыванию `createdAt`; чаты без видимых сообщений — ниже; исключены только удалённые без новых сообщений (`hidden` ∧ `last_seq ≤ deleted_up_to_seq`) — новый входящий снимает `hidden` и возвращает чат в список (FR-021) | `401` |
| 13 | `GET /chats/{chatId}` | — | `200` `ChatView` | `403` `not_participant`; `404` `chat_not_found` |
| 14 | `DELETE /chats/{chatId}` | — | `204` — per-user удаление: история скрывается водяным знаком только у удалившего, второй участник не затронут (FR-021); повторный вызов — `204` (идемпотентно) | `403` `not_participant`; `404` `chat_not_found` |
| 15 | `GET /chats/{chatId}/messages?before=<seq>&limit=<1..50>` | — | `200` `MessagePage`: `messages` — по `seq DESC`, ровно те, что `seq < before` (без `before` — последние видимые); `nextBefore` — `seq` самой старой записи страницы, отсутствует, когда старее нет (пустая страница на границе — корректный ответ, US3-4) | `400` `limit_out_of_range` (1–50, фиксируется 50); `403` `not_participant`; `404` `chat_not_found` |
| 16 | `POST /chats/{chatId}/messages` | `{clientMessageId: uuid, text: string}` | `201` `Message` (записано) \| `200` `Message` (дедупликация: тот же `clientMessageId` уже записан — возвращается существующая запись, дубля нет — FR-004) | `400` `text_blank` \| `text_too_long` (>4096 после trim) \| `invalid_uuid`; `403` `not_participant` \| `chat_blocked_by_you` (отправитель блокирует получателя) \| `you_are_blocked` (получатель блокирует отправителя — явное уведомление, FR-020); `404` `chat_not_found`; `409` `message_id_conflict` (`clientMessageId` принадлежит другой записи); `429` `flood_limit` + `Retry-After` (сек до доступного токена, FR-011) |
| 17 | `POST /chats/{chatId}/read` | `{upToSeq: int64 ≥ 1}` | `204` — идемпотентно и монотонно (меньшее/повторное `upToSeq` не откатывает, US4-5); при действующей блокировке в паре: вызывающий — блокирующий → `204` без эффекта (водяной знак не двигается, событие не публикуется — «замирание» FR-020); вызывающий — заблокированный → `204`, водяной знак двигается локально, событие блокирующему не публикуется (FR-020) | `403` `not_participant`; `404` `chat_not_found`; `400` `invalid_up_to_seq` |

Отправка в паре без существующего диалога: `POST /chats/{chatId}/messages` предполагает
созданный чат (№11); первый входящий от незнакомца (FR-019) создаёт диалог неявно на стороне
отправителя (`ensure` перед первой отправкой) — у получателя чат появляется в списке с первым
сообщением. Превью последнего сообщения в `ChatListItem.lastMessage.text` — полный текст
(обрезка ≤64 симв. — клиентский рендер).

## 2. Реалтайм-канал (tag `realtime`)

| # | Метод и путь | Успех | Ошибки |
|---|---|---|---|
| 18 | `GET /users/me/events` (`Accept: text/event-stream`) | `200` SSE-поток событий пользователя: `message.created`, `chat.read`, heartbeat — фрейминг, схемы и семантика в [realtime-channel.md](./realtime-channel.md) | `401` |

## 3. Контакты, поиск, блокировки (tags `contacts`, `users`)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| 19 | `GET /users/search?query=<string>` | — | `200` `{users: [PublicUser]}` — точное совпадение полного email ИЛИ полного логина, без учёта регистра (FR-016): `query` содержит `@` → сравнение `lower(email)`, иначе `lower(username)` (username не допускает `@` — правило 002); 0..1 элемент (пустой результат — корректный, edge) | `400` `query_missing` (\|query\| < 1 или > 254); `401` |
| 20 | `GET /contacts?sort=login\|email` (default `login`) | — | `200` `{contacts: [ContactView]}` — серверная алфавитная сортировка по выбранному полю без учёта регистра (FR-015) | `400` `invalid_sort`; `401` |
| 21 | `POST /contacts` | `{userId: uuid}` | `201` `ContactView` \| `200` `ContactView` (уже добавлен — дубль не создаётся, edge) | `404` `user_not_found`; `422` `self_forbidden` (FR-016); `401` |
| 22 | `DELETE /contacts/{userId}` | — | `204` — идемпотентно (несуществующий — тоже `204`); чат и история пары не затрагиваются (FR-017) | `401` |
| 23 | `PUT /users/{userId}/block` | — | `204` — идемпотентно (повторная блокировка — без изменений, edge); семантика действия — FR-020 (запрет отправки в обе стороны, подавление прочтений/бейджа у блокирующего; контакты не затрагиваются) | `404` `user_not_found`; `422` `self_forbidden`; `401` |
| 24 | `DELETE /users/{userId}/block` | — | `204` — идемпотентно; возвращает обычное поведение диалога (отправка, прочтения, бейдж) | `401` |

## 4. Схемы (components.schemas)

Новые: `EnsureChatRequest`, `ChatView` (`chatId, peer: PublicUser, blockedByMe: boolean,
peerReadUpToSeq: int64, myReadUpToSeq: int64`), `ChatListItem` (`chatId, peer, lastMessage:
Message \| null, unreadCount: int64, blockedByMe`), `Message` (`id: uuid (=clientMessageId),
chatId, senderId, text, seq: int64, createdAt`), `MessagePage` (`messages: Message[],
nextBefore?: int64`), `SendMessageRequest`, `ReadRequest`, `ContactView`
(`user: PublicUser, createdAt`), `ContactsResponse`, `UsersSearchResponse`,
`MessageCreatedEvent`, `ChatReadEvent` (события — [realtime-channel.md](./realtime-channel.md)).
Переиспользуются: `PublicUser`, `Problem`, `bearerAuth`.

Инвариант состава: все новые пути — аутентифицированные; публичный перечень 002–003
не расширяется. `blockedByMe` — единственная проекция блокировок в API: поле «кто заблокировал
меня» отсутствует (FR-020: заблокированный узнаёт о блокировке только из `403 you_are_blocked`
при отправке).
