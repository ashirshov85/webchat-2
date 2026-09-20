# Data Model: Личный диалог в реальном времени с exactly-once доставкой

**Feature**: 004-direct-messaging-core | **Date**: 2026-09-20

Долговечное состояние — PostgreSQL 17 (миграции **V10__chats.sql**, **V11__contacts_blocks.sql**;
конвенции 002: snake_case, `uuid PK`, `timestamptz DEFAULT now()`, CHECK-инварианты, составные
индексы с ведущим `chat_id` — масштабный путь [research.md §12]). Эфемерное — Redis 7
(флуд-бакеты, pub/sub-каналы; [research.md §2, §7]). Пользователи/сессии — сущности фичи 002,
здесь только читаются.

Диаграмма связей:

```text
users (002) ──< chat_participants >── chats ──< messages
   │                                     │
   │                                     └─ (user_low_id, user_high_id) UNIQUE — ровно один
   │                                         диалог на пару (FR-001)
   ├──< user_contacts (owner → contact_user)      — односторонние (FR-016)
   └──< user_blocks  (blocker → blocked)          — односторонние (FR-020)
```

---

## Сущность 1: Chat (личный диалог)

Беседа ровно двух пользователей; для пары существует единственный диалог (FR-001).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | генерируется сервером при создании |
| `user_low_id` | UUID FK → users | `= least(a, b)` — канонический порядок пары |
| `user_high_id` | UUID FK → users | `= greatest(a, b)`; CHECK `user_low_id < user_high_id` |
| `created_at` | timestamptz | `DEFAULT now()` |
| `last_seq` | bigint NOT NULL DEFAULT 0 | кэш максимального `messages.seq` чата (поддерживается в той же транзакции INSERT-а сообщения) — для сортировки списка (FR-014), водяного знака удаления (FR-021) и видимости (§2) без полного MAX-скана |

**Уникальность пары**: `UNIQUE (user_low_id, user_high_id)` — повторное ensure возвращает тот же
chat (FR-018). Создание ленивое: `INSERT … ON CONFLICT DO NOTHING` + `SELECT` (идемпотентно,
в одной транзакции с созданием двух `chat_participants` — тоже `ON CONFLICT DO NOTHING`).

**Переходы состояний**: состояния нет — диалог иммутабелен после создания; per-user видимостью
управляет `chat_participants` (§2), сообщениями — `messages` (§3).

## Сущность 2: ChatParticipant (per-user состояние диалога)

Строка на (участник, чат) — водяные знаки и видимость ([research.md §4, §5]).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `chat_id` | UUID FK → chats | PK `(chat_id, user_id)` |
| `user_id` | UUID FK → users | — |
| `last_read_seq` | bigint NOT NULL DEFAULT 0 | CHECK `>= 0`; монотонен — только вперёд (GREATEST-UPDATE); 0 = ничего не прочитано |
| `deleted_up_to_seq` | bigint NOT NULL DEFAULT 0 | CHECK `>= 0`; водяной знак удаления истории: сообщение видно участнику ⟺ `seq > deleted_up_to_seq` |
| `hidden` | boolean NOT NULL DEFAULT false | чат исключён из списка «Чаты» (ставится при DELETE чата; снимается ensure-ом или новым входящим сообщением) |
| `created_at` | timestamptz | `DEFAULT now()` |

**Правила видимости (выводные)**:
- Сообщение видно участнику ⟺ `msg.seq > deleted_up_to_seq` (FR-021).
- Чат в списке «Чаты» ⟺ `NOT hidden AND last_seq > deleted_up_to_seq` — пустой (без видимых
  сообщений) чат показывается ниже переписок (FR-014); после `DELETE` — скрыт полностью (FR-021);
  новый входящий (`seq` выше водяного знака) снова показывает чат, история не восстанавливается.
- Непрочитано для участника ⟺ `msg.seq > last_read_seq AND msg.seq > deleted_up_to_seq AND
  msg.sender_id != user_id` (бейдж FR-014; «замирание» при блокировке — натурально, §5 сущности 5).
- «Последнее сообщение» для сортировки ⟺ сообщение с `max(seq)` по видимым (`seq >
  deleted_up_to_seq`) — исходящее тоже поднимает чат (Q40/FR-014).

**Операции**:
- `DELETE /chats/{id}` (per-user): `UPDATE … SET deleted_up_to_seq = chat.last_seq,
  hidden = true` — атомарно, O(1); строка второго участника не затрагивается.
- `POST /chats/ensure`: при конфликте существующего чата — `UPDATE … SET hidden = false`
  (водяной знак сохраняется: удалённая история остаётся скрытой).
- `POST /chats/{id}/read {upToSeq}`: `UPDATE … SET last_read_seq = GREATEST(last_read_seq,
  :upToSeq) WHERE … AND last_read_seq < :upToSeq` — идемпотентно и монотонно (rowcount = 0 —
  статус не меняется, US4-5); подавление при блокировке — §5 сущности 5.

## Сущность 3: Message (сообщение)

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | **генерируется клиентом** (FR-004); ключ дедупликации и публичный идентификатор |
| `chat_id` | UUID FK → chats | вместе с `seq` — порядок и пагинация |
| `sender_id` | UUID FK → users | один из двух участников чата (CHECK на уровне приложения: membership) |
| `text` | varchar(4096) | **нормализованный** текст: trim начальных/конечных пробелов и переводов строк (внутренние сохраняются); CHECK `length(text) > 0 AND length(text) <= 4096` (FR-003, Q42: обе проверки после trim) |
| `seq` | bigint NOT NULL | `GENERATED ALWAYS AS IDENTITY` (bigserial); серверный; **монотонность и уникальность — в рамках чата**: `UNIQUE (chat_id, seq)`, глобальный `UNIQUE (seq)` не вводится; порядок чата = `ORDER BY seq` (FR-006); аллокация при масштабировании — [research.md §12](./research.md): партиционирование совместимо с IDENTITY как есть, шардирование — per-shard sequences с watermark-инициализацией выше `max(seq)` |
| `created_at` | timestamptz | момент записи сервером (`DEFAULT now()`); «доставлено» (FR-005) |
| — (выводное) | `read_by_peer` | не хранится: сообщение прочитано ⟺ `seq ≤ peer.last_read_seq` (§2) |

**Индексы**: `UNIQUE (chat_id, seq)` — область уникальности/монотонности порядка; индекс
`(chat_id, seq DESC)` — история/пагинация/агрегаты списка; PK по `id` — дедуп.

**Запись (exactly-once эффект, [research.md §3])** — одна транзакция PG:
```text
0. дедуп-lookup по id: found → (chat_id, sender_id совпадают) → 200 существующая запись
                          (mismatch → 409 chat/message conflict) — ДО лимитов и блокировок
1. membership (FR-002) → 2. блокировки (§5) → 3. флуд-бакет (§Redis) →
4. INSERT … ON CONFLICT (id) DO NOTHING RETURNING *
   ├─ строка возвращена → 201; UPDATE chats SET last_seq = seq
   └─ пусто (гонка параллельных ретраев) → SELECT по id → 200 той же записи
5. ПОСЛЕ коммита: publish message.created в rt:user:{sender}, rt:user:{recipient} + метрики
```
Порядок «дедуп → флуд» гарантирует: ретрай/двойной клик уже записанного сообщения не
штрафуется 429 (US2-1/2); `409` на чужом id — единственное неотличимое от коллизии поведение.

**Пользовательские статусы (клиентские, выводные)** — хранятся только «отправляется»/«не
отправлено» в outbox клиента ([research.md §10]):

```text
[клиент] отправляется ──201/200──► [сервер] доставлено (✓) ──peer.last_read_seq ≥ seq──► прочитано (✓✓)
     │ 429/сеть: автоповтор тем же id          ▲ монотонно: GREATEST-водяной знак, события идемпотентны
     └─ 403 you_are_blocked / 409 / 400: ──► не отправлено (терминальный; ручной повтор тем же id
                                            или локальное удаление; автоповторы прекращены)
```

## Сущность 4: Contact (контакт)

Односторонняя запись в личном списке (FR-016–FR-019).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `owner_id` | UUID FK → users | PK `(owner_id, contact_user_id)` — дублей нет (edge spec: повторное добавление идемпотентно) |
| `contact_user_id` | UUID FK → users | CHECK `contact_user_id <> owner_id` (двойная защита; основная — сервисный слой, 422 self) |
| `created_at` | timestamptz | `DEFAULT now()` |

**Сортировка списка** (FR-015): `ORDER BY lower(username)` или `lower(email)` — по параметру
`sort` (login | email); выражения совпадают с индексами 002 на `users`.
**Независимость**: удаление контакта не трогает чат/историю (FR-017); блокировка не трогает
контакты и наоборот (FR-020, §5); нажатие на контакт → `POST /chats/ensure` (FR-018).

## Сущность 5: UserBlock (блокировка)

Одностороннее отношение; управляется блокирующим (FR-020).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `blocker_id` | UUID FK → users | PK `(blocker_id, blocked_id)`; CHECK `blocker_id <> blocked_id` |
| `blocked_id` | UUID FK → users | — |
| `created_at` | timestamptz | `DEFAULT now()` |

**Семантика действия** (все проверки — точечные запросы по PK пары в обе стороны):

| Ситуация | Поведение |
|---|---|
| Отправка при блоке отправителем | `403 chat_blocked_by_you` — записи нет, warn-лог |
| Отправка при блоке получателем | `403 you_are_blocked` — явно уведомляет заблокированного; записи нет, warn-лог |
| `POST /read` от блокирующего | водяной знак **не** двигается, `chat.read` **не** публикуется (статусы замирают, Q) |
| `POST /read` от заблокированного | водяной знак двигается (локально), событие **не** доставляется блокирующему (не раскрывать активность); после разблокировки всё корректно и монотонно |
| Бейдж у блокирующего | «замирает» натурально: новых сообщений нет (отправка запрещена обеим сторонам → числитель фиксирован), прочтения подавлены → `last_read_seq` фиксирован (знаменатель) — не растёт и не сбрасывается (Q) |
| `GET /chats`/`GET /chats/{id}` | поле `blockedByMe` (метка «заблокирован» только у блокирующего); поля «кто меня заблокировал» нет (не раскрывается, Q) |
| Контакты | независимы: ни добавление/удаление контакта, ни блок/анблок друг друга не касаются (Q) |
| `PUT/DELETE /users/{id}/block` | идемпотентны (`ON CONFLICT DO NOTHING` / безусловный `DELETE`) → 204 (edge spec) |
| Разблокировка | обычное поведение возобновляется немедленно (отправка, прочтения, бейдж) |

**Новый входящий от незнакомца** (FR-019): ensure чата не требуется заранее — отправка в паре
без чата неявно создаёт диалог (`INSERT chat ON CONFLICT` → сообщение); у получателя чат
появляется в списке с первым сообщением; контакты получателя не затрагиваются.

## Сущность 6: Redis (эфемерное)

| Ключ/канал | Тип | TTL / жизненный цикл | Назначение |
|---|---|---|---|
| `rl:user:msgsend:{userId}` | Bucket4j bucket | TTL бакета 002-семейства | флуд-лимит 30 сообщений/мин на пользователя (FR-011; [research.md §7]) |
| `rt:user:{userId}` | Pub/Sub channel | подписка инстанса на время SSE-соединений пользователя | фанаут `message.created` / `chat.read` (at-most-once; сходимость — рефетч на реконнекте, [research.md §2]) |

Сброс Redis (утрата) допустим: лимиты кратко обнуляются; pub/sub-каналы самовосстанавливаются
переподпиской; долговечное состояние целиком в PG (конституция II).

## Выводные представления API (не хранятся)

- **ChatListItem** (GET /chats): `chatId, peer{id,username,email}, lastMessage{id,text(превью),
  seq, senderId, createdAt} | null, unreadCount, blockedByMe, updatedAt(сортировка)`.
- **ChatView** (GET /chats/{id}, ensure): `chatId, peer, blockedByMe, peerReadUpToSeq,
  myReadUpToSeq, hidden(после delete)`.
- **MessageView**: `id, chatId, senderId, text, seq, createdAt` — галочки/статусы клиент
  выводит (водяной знаки + outbox; §3).
- Превью последнего сообщения — `left(text, 64)` на клиенте; сервер отдаёт полный текст
  последнего (упрощение, поле ≤ 4096).

## Миграции

- **V10__chats.sql**: `chats` (+UNIQUE пара, CHECK low<high), `chat_participants` (PK пара,
  CHECK-и), `messages` (PK id, IDENTITY seq, CHECK text, `UNIQUE (chat_id, seq)`, индекс
  `(chat_id, seq DESC)`).
- **V11__contacts_blocks.sql**: `user_contacts` (PK пара, CHECK), `user_blocks` (PK пара, CHECK).

Обе — прямые `CREATE TABLE`/`CREATE INDEX`; существующие таблицы 002 не изменяются.
