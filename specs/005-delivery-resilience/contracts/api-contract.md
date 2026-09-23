# Contract: Изменения публичного API — устойчивость доставки (OpenAPI)

**Feature**: 005-delivery-resilience | **Источник истины**: `contracts/openapi.yaml`

Изменения к контрактy 004: 2 новых endpoints (№25–26), расширение №15, новый код отказа №16,
новые схемы. Изменение — **только additive** (minor `0.4.0 → 0.5.0`, без BREAKING.md);
конвейер 001 (vacuum → openapi-typescript → drift-check → oasdiff) применяется без изменений.
TS-типы фронтенда — из генерации. Нормативный протокол синхронизации (курсоры/ack/циклы) —
[sync-protocol.md](./sync-protocol.md).

Все операции требуют `Authorization: Bearer <accessToken>`; авторизация per-user (`/users/me/*`)
и membership диалога (№15/№16 — без изменений 004). Ошибки — RFC 9457 `Problem` (`errors:
map<string, string[]>`, конвенция 002). Фиксированные константы фичи (дублируются в контракте):
страница дельты/истории — **50 сообщений**; чатов на ответ sync — **20** (max 50); элементов
ack-батча — **100**; лимит оффлайн-очереди клиента — **1000** (клиентская константа, в контракте
описана семантикой); флуд-лимит 30/мин (004, действует под пиком — FR-010).

## 1. Позиция доставки и синхронизация (tag `sync`, новые)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| 25 | `POST /users/me/delivery-ack` | `{acks: [{chatId: uuid, upToSeq: int64 ≥ 1}], 1..100 эл.}` | `204` — батчевое монотонное продвижение позиции доставки вызывающего (GREATEST; повтор/меньшее — без эффекта, идемпотентно); весь батч атомарен | `400` `invalid_up_to_seq` (upToSeq > seq последнего сообщения чата) \| `invalid_uuid`; `401`; `403` `not_participant` (чужой чат — весь батч отклонён); `404` `chat_not_found` |
| 26 | `POST /users/me/sync` | `{cursors: [{chatId: uuid, upToSeq: int64 ≥ 0}], chatLimit?: 1–50 (20), messageLimit?: 1–50 (50)}` | `200` `SyncResponse`: `chats: [SyncChatDelta]` — только чаты с недоставленным, по последней активности DESC (первыми — с самыми новыми сообщениями), до chatLimit; `moreChats: boolean`. Дельта: `startAfterSeq` (эффективный курсор), `truncatedUpToSeq?` (точка обрезки — сообщения ниже недоступны, не непрочитаны; позиция/курсор продвигаются на неё ack'ом), `messages: [Message]` ascending ≤ messageLimit, `hasMore`, `peerReadUpToSeq` (накопленные оффлайн ✓✓ собственных сообщений), `unreadCount` (серверный счётчик с учётом страницы), `lastSeq`, `desynced? + serverUpToSeq` (курсор «из будущего» — per-chat ремонт без ошибки запроса). Операция не двигает позицию доставки (только ack №25); один снимок чтения — «частичный список как полный» исключён | `400` `invalid_uuid` \| `limit_out_of_range`; `401`. Чужие/несуществующие chatId в `cursors` молча игнорируются: per-user операция возвращает только чаты вызывающего и не роняет запрос из-за посторонних идентификаторов (в отличие от №25, где батч-ack атомарен и проверяет membership каждого элемента) |

Примечание к №26: `cursors` — карта клиентских курсоров; сервер применяет их как нижнюю границу
своей позиции (`эффективный = max(клиентский, серверный)`); «будущий» курсор ремонтируется
`desynced/serverUpToSeq` (sync-protocol.md §5).

## 2. Расширение истории №15 (tag `chats`, additive)

| # | Метод и путь | Изменение |
|---|---|---|
| 15 | `GET /chats/{chatId}/messages?after=<seq>&limit=<1..50>` | Новый опциональный параметр `after` (int64 ≥ 0, эксклюзивный): ascending-страница `(after, after+limit]` по `seq ASC` — дозагрузка новее курсора (догоняющая синхронизация, sync-protocol.md §4). Ответ дополняется полем `nextAfter?: int64` (seq последней записи страницы; отсутствует, когда новее нет — пустая страница на границе корректна). Режим `before` (DESC, 004) — без изменений; `after` + `before` одновременно → `400 mixed_cursors`. Видимость по `deleted_up_to_seq` вызывающего — как в 004 |

Существующие ответы без `after` не меняются (`nextAfter` отсутствует в DESC-режиме) — обратная
совместимость сохранена.

## 3. Backpressure на отправке №16 (tag `chats`, additive)

| # | Метод и путь | Изменение |
|---|---|---|
| 16 | `POST /chats/{chatId}/messages` | Новый ответ `503`: `errors: {chat: [server_busy]}` + заголовок `Retry-After` (сек ≥ 1) — временный отказ при перегрузке пути отправки (backpressure, FR-009). Идемпотентность сохранена: дедуп-фастпас (200 уже записанного) выполняется ДО admission — повтор принятого сообщения никогда не получает 503. Флуд-лимит 429 (004) действует без изменений и не обходится повторами (FR-010). Порядок проверок: дедуп → admission (503) → membership → валидация → флуд (429) → блокировки → INSERT |

## 4. Уточнения описаний (без смены схем)

- `ChatListItem.unreadCount`: счётчик серверно-авторитарный и ограничен позицией доставки —
  входящие видимые сообщения с `max(last_read_seq, deleted_up_to_seq) < seq ≤
  min(last_seq, delivered_up_to_seq)`; realtime и дозагруженные синхронизацией сообщения
  увеличивают его одинаково (после ack доставки); ниже точки обрезки — недоступные, не
  непрочитанные (FR-007). Формат поля прежний; «99+» — клиентский рендер.
- `GET /users/me/events` (№18): без изменений — кадры `message.created`/`chat.read` и семантика
  004; доставка позиции по-прежнему не пишется кадром (только ack №25), клиент дедуплицирует
  по `message.id`.

## 5. Схемы (components.schemas)

Новые:
- `DeliveryAckItem` (`chatId: uuid, upToSeq: int64 ≥ 1`), `DeliveryAckRequest`
  (`acks: DeliveryAckItem[], 1..100`).
- `SyncCursor` (`chatId: uuid, upToSeq: int64 ≥ 0`), `SyncRequest` (`cursors: SyncCursor[],
  chatLimit? 1–50 default 20, messageLimit? 1–50 default 50`).
- `SyncChatDelta` (`chatId, peer: PublicUser, blockedByMe: boolean, startAfterSeq: int64 ≥ 0,
  truncatedUpToSeq?: int64 ≥ 1, messages: Message[], hasMore: boolean, peerReadUpToSeq:
  int64 ≥ 0, unreadCount: int64 ≥ 0, lastSeq: int64 ≥ 0, desynced?: boolean, serverUpToSeq?:
  int64 ≥ 0`), `SyncResponse` (`chats: SyncChatDelta[], moreChats: boolean`).

Изменённые (additive-optional):
- `MessagePage`: + `nextAfter?: int64 ≥ 1` (только ascending-режим `after`).

Переиспользуются: `PublicUser`, `Message`, `Problem`, `bearerAuth`.

Инвариант состава: все новые пути — аутентифицированные (`/users/me/*` — per-user, членство
проверяется по строкам самого вызывающего); публичный перечень 002–003 не расширяется;
проекций блокировок не добавляется (заморозка бейджа/прочтений — семантика 004 без изменений).
