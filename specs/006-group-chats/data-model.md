# Data Model: Групповые чаты с ролями и member-авторизацией

**Feature**: 006-group-chats | **Date**: 2026-09-25

Новое долговечное состояние — PostgreSQL 17, миграция **V14__group_chats.sql** (эволюция `chats` и
`chat_participants` + новая таблица `group_admin_log`; конвенции 002/004/005: snake_case, `uuid PK`,
`timestamptz DEFAULT now()`, CHECK-инварианты, частичные индексы, GREATEST-монотонность водяных
знаков). Redis 7 — **без изменений** (`rl:user:msgsend:*`, `rt:user:{userId}` из 002/004; фанаут
групп — публикации в существующие per-user каналы, [research.md §4](./research.md)). Сообщения,
курсоры, ack, счётчики — сущности 004/005, здесь только переиспользуются. Клиентские хранилища —
без изменений (localStorage 005; локальное состояние групп — in-memory хуков).

Диаграмма связей (дельта к 004/005):

```text
users (002) ──< chat_participants >── chats ──< messages
                     │  + role (NULL у direct)      (kind='group': title/description,
                     │  + state ('active'|'removed')  пара NULL — та же таблица)
                     └── выводно: othersReadUpToSeq = MIN(last_read_seq активных, кроме user)

user_contacts (004) ── (проверка «участник из контактов добавляющего», FR-002)

group_admin_log ── append-only журнал административных операций (FR-017),
                   БЕЗ FK на chats — переживает hard-delete группы (FR-006)
```

---

## Сущность 1: Chat → групповая проекция (эволюция `chats`)

Группа — строка `chats` с `kind='group'`: общие метаданные, единая история, ровно один владелец
(FR-001); личный диалог — прежняя строка `kind='direct'`.

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | uuid PK | генерируется сервером; публичный `chatId` группы во всех операциях 004/005/006 |
| `kind` | varchar(16) NOT NULL DEFAULT 'direct' | CHECK `IN ('direct','group')`; существующие строки — 'direct' (миграция) |
| `user_low_id`, `user_high_id` | uuid FK → users, **nullable** | только direct: CHECK-shape (§ниже); парный UNIQUE не мешает группам (PG `NULLS DISTINCT`) |
| `title` | varchar(64) NULL | только group; нормализуется сервисом (btrim); CHECK: `kind='direct'` ⟹ NULL; `kind='group'` ⟹ `length(btrim(title)) BETWEEN 1 AND 64` (FR-001) |
| `description` | varchar(256) NULL | только group; CHECK `length(description) <= 256` (опционально, FR-001) |
| `created_at` | timestamptz | `DEFAULT now()` |
| `last_seq` | bigint NOT NULL DEFAULT 0 | без изменений 004: кэш `MAX(messages.seq)` в INSERT-транзакции сообщения — сортировка списка, `upToSeq`-валидация ack/read, инициализация водяных знаков при добавлении |

**Shape-CHECK** (единый): `(kind='direct' AND user_low_id IS NOT NULL AND user_high_id IS NOT NULL)
OR (kind='group' AND user_low_id IS NULL AND user_high_id IS NULL)`. Существующий CHECK
`user_low_id < user_high_id` на NULL-ах — UNKNOWN → проходит (группы не затронуты).

**Переходы состояний**: `direct`-строки иммутабельны (004). `group`: создаётся (№27) → метаданные
мутируют (№29, атомарно — «последняя подтверждённая операция», edge одновременного переименования) →
удаляется (№30, hard). Живой цикл состава — в членствах (§2).

## Сущность 2: Membership (эволюция `chat_participants`)

Строка на (пользователь, чат) — уже несёт водяные знаки 004/005; 006 добавляет роль и состояние.

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `(chat_id, user_id)` | uuid PK | без изменений |
| `role` | varchar(16) NULL | CHECK `IN ('owner','admin','member') OR NULL`; NULL ⟺ direct-чат (роль бессмысленна для пары); у группы каждая active-строка имеет роль |
| `state` | varchar(16) NOT NULL DEFAULT 'active' | CHECK `IN ('active','removed')`; **членство группы ⟺ active-строка**; direct-строки всегда 'active' (миграцией и путями 004 не меняются) |
| `last_read_seq` | bigint NOT NULL DEFAULT 0 | без изменений 004; при **первом** добавлении в группу инициализируется `chats.last_seq` (FR-013: бейдж с 0); при повторном — сохраняется (FR-002) |
| `delivered_up_to_seq` | bigint NOT NULL DEFAULT 0 | без изменений 005; движется только ack №25; первая инициализация — `chats.last_seq` (история до добавления не «доставляется» повторно) |
| `deleted_up_to_seq` | bigint NOT NULL DEFAULT 0 | без изменений 004; у групп — 0 (per-user сокрытие групповой переписки вне объёма, Assumptions) |
| `hidden` | boolean NOT NULL DEFAULT false | у групп не используется (группа видна, пока active-членство); при реактивации сбрасывается false |
| `created_at` | timestamptz | `DEFAULT now()` |

**Частичные индексы (V14)**:
- `CREATE UNIQUE INDEX ux_chat_participants_owner ON chat_participants (chat_id) WHERE role='owner'`
  — инвариант «ровно один owner» на уровне хранилища (FR-003); direct-строки (role NULL) не
  попадают. Передача владения — в одной транзакции **demote THEN promote** (индекс не нарушается
  ни в одной промежуточной точке).
- `CREATE INDEX ix_chat_participants_chat_active ON chat_participants (chat_id) INCLUDE (user_id,
  role, last_read_seq) WHERE state='active'` — состав/фанаут/MIN-водяной знак/счётчик лимита: все
  читают активный состав группы одним index-only-scan (≤200 строк).

**Операции (группа)**:
- **№27 создание**: tx = `INSERT chats (kind='group', title, description)` + `INSERT participant
  (creator, role='owner', state='active')` + начальный состав из контактов (каждый: `INSERT` с
  инициализацией §выше, проверка `user_contacts` пары (creator, userId)) + журнал `group_created` +
  `member_added`×N. Post-commit: `group.member.added` каждому добавленному (группа появляется в
  «Чатах», US1-2).
- **№31 добавление**: tx = `SELECT … FOR UPDATE` строки `chats` (сериализация конкурентных
  добавлений) → `count(active) + batch ≤ 200` иначе `422 group_full` → для каждого userId: контакт
  добавляющего (`422 not_in_contacts`, весь batch атомарен), self → `400 self_forbidden`;
  active-строка уже есть → пропуск (идемпотентно, дубля нет); removed-строка → реактивация
  (`state='active'`, `role='member'`, `hidden=false`, **водяные знаки сохранены**); иначе INSERT с
  инициализацией `last_read_seq = delivered_up_to_seq = chats.last_seq`. Журнал `member_added`×K;
  post-commit `group.member.added` (всем активным, включая добавленных).
- **№32 исключение / №33 выход**: единый оператор `UPDATE chat_participants SET state='removed',
  role='member' WHERE chat_id=? AND user_id=? AND state='active'` — rowcount 1 → успех (журнал
  `member_removed`/`member_left`; post-commit: `group.you_removed {reason}` адресату + 
  `group.member.removed` остальным); rowcount 0 → конкуренция «исключение×выход» уже разрешилась —
  сходимость без дублей (edge). Иерархия (FR-004) проверяется до UPDATE: admin исключает только
  member (`403 role_hierarchy_violation` иначе); owner не выходит (№33 → `403
  owner_must_transfer`); исключить owner нельзя (только передача + выход). Сообщения
  исключённого/вышедшего остаются с атрибуцией (FR-005 — строки `messages` не трогаются).
- **№34 роли**: `UPDATE … SET role=:role WHERE … AND state='active'` (только owner, только
  'admin'/'member'; не-участнику → `409 target_not_member`; себе → `400 self_forbidden` — owner не
  «снимает» сам себя). Журнал `admin_granted`/`admin_revoked`; событие `group.role.changed`.
- **№35 передача владения**: tx = demote(old owner → 'admin') → promote(target → 'owner')
  (target — active-участник, не owner: `409 target_not_member` / `400 self_forbidden`). Журнал
  `ownership_transferred`; событие `group.role.changed` дважды (или одно составное — контрактом
  зафиксировано два кадра, идемпотентно применяются).

**Правила видимости/выводные (группа)**:
- Доступ к ресурсу группы ⟺ active-строка (FR-008); не-участник (никогда не был / removed) —
  единый `404 group_not_found`.
- `othersReadUpToSeq(user, chat) = MIN(last_read_seq) active-участников, кроме user` (0 при
  отсутствии других — ✓✓ недостижим, FR-012/edge).
- Непрочитано участника — формула 005 без изменений по его строке; инициализация добавления
  даёт «бейдж с 0» (FR-013), реактивация — счётчик за период отсутствия (FR-002).

## Сущность 3: Message (группа — без изменений 004)

Те же `messages` (клиентский uuid PK = дедуп, `UNIQUE (chat_id, seq)`, IDENTITY `seq`,
varchar(4096) trim-CHECK, `created_at`). Путь записи 004/005 сохранён: дедуп-фастпас → admission →
membership (для group — active-строка) → валидация → флуд → INSERT + `chats.last_seq`. Отличия
только post-commit: `message.created` — **всем активным участникам, кроме отправителя** (FR-011;
снимок состава в tx); блокировки 004 не проверяются для kind='group' (Assumptions). `chat.read`
(№17) — всем активным, кроме читавшего (§2 сущности 5 004 — расширение адресатов, не схемы).

## Сущность 4: GroupAdminLog (журнал административных операций, FR-017)

Append-only аудит управления группой; публичного API чтения нет (наблюдаемость, [research.md §7]).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | uuid PK | генерируется сервером |
| `group_id` | uuid NOT NULL | **без FK** — записи переживают hard-delete группы (FR-006: факт удаления сохраняется) |
| `actor_id` | uuid FK → users | кто выполнил |
| `target_user_id` | uuid NULL FK → users | объект действия (roles/members; NULL для метаданных/удаления) |
| `action` | varchar(32) NOT NULL | CHECK `IN ('group_created','title_changed','description_changed','member_added','member_removed','member_left','admin_granted','admin_revoked','ownership_transferred','group_deleted')` |
| `created_at` | timestamptz | `DEFAULT now()` |

Индекс `(group_id, created_at)` — хронология по группе. Содержимое сообщений НЕ журналируется
(только факты, clarify 2026-09-25). События группы в историю чата НЕ пишутся (FR-015: история —
только пользовательские сообщения, модель 004 не расширяется).

## Сущность 5: Realtime-фанаут группы (выводная, transport 004)

| Аспект | Правило |
|---|---|
| Каналы | существующие `rt:user:{userId}` (+ конверт `{"event","data"}`); новых ключей/каналов Redis нет |
| Адресаты | снимок активного состава в tx операции; публикация строго post-commit ([research.md §4]) |
| `message.created` | все активные, кроме отправителя (FR-011); клиент дедуплицирует по `message.id` (004) |
| `chat.read` | все активные, кроме читавшего; клиент пересчитывает ✓✓ по MIN (§2) |
| `group.updated` / `group.role.changed` | всем активным |
| `group.member.added` / `group.member.removed` | всем активным (добавленным — включая их самих) |
| `group.you_removed` | адресату (его канал), reason 'kicked'\|'left' — финальное событие группы по его подписке (FR-015) |
| `group.deleted` | всем бывшим активным (снимок до CASCADE); post-commit после tx удаления |
| «Закрытие подписки» ≤5 с (FR-010) | логическое: после коммита удаления состава события группы адресату больше не публикуются; поток пользователя (№18) не рвётся — мультиплексирует все чаты ([research.md §4]) |
| Порядок/дубли | at-most-once канал 004; гонка «сообщение до исключения опубликовано после `you_removed`» — клиент после `you_removed` игнорирует события группы и удаляет её локально (идемпотентная сходимость, FR-015) |

## Сущность 6: Redis (без изменений — перечисление)

| Ключ/канал | Роль в 006 |
|---|---|
| `rl:user:msgsend:{userId}` | флуд-30/мин действует в группах без изменений (FR-011) |
| `rt:user:{userId}` | доставка `message.created`/`chat.read`/`group.*` (фанаут ≤199 ног на операцию; расчёт SC-007 — [research.md §4]) |

Сброс Redis допустим (durability — PG, конституция II). In-process состояния фича не добавляет
(лимиты/состав/роли — в PG; admission-лимитер 005 не затронут).

## Выводные представления API (не хранятся)

- **GroupView** (№27/№28/№29/№35): `chatId, title, description|null, myRole, members:
  [GroupMember{user: PublicUser, role, joinedAt}]` (≤200, сортировка `joined_at, user_id`).
- **ChatListItem** (№12): + `type ('direct'|'group')`; group-элемент: `title, memberCount, myRole`,
  `peer`/`blockedByMe` = null (nullable, [research.md §5]); `unreadCount`/`lastMessage`/сортировка —
  004 без изменений.
- **ChatView** (№13): group-вариант — `type:'group', title, description, myRole, myReadUpToSeq,
  othersReadUpToSeq, memberCount`.
- **SyncChatDelta** (№26): group-дельта — `type:'group', title, memberCount, othersReadUpToSeq`
  (вместо `peer`/`peerReadUpToSeq`/`blockedByMe` = null); `messages`/`hasMore`/`unreadCount`/
  `startAfterSeq`/`truncatedUpToSeq` — 005 без изменений.
- **События №18** — [contracts/realtime-group-events.md](./contracts/realtime-group-events.md).

## Миграция

**V14__group_chats.sql** (additive; существующие строки не меняют поведения):

```sql
ALTER TABLE chats
    ADD COLUMN kind varchar(16) NOT NULL DEFAULT 'direct',
    ADD COLUMN title varchar(64),
    ADD COLUMN description varchar(256),
    ALTER COLUMN user_low_id DROP NOT NULL,
    ALTER COLUMN user_high_id DROP NOT NULL;
ALTER TABLE chats ADD CONSTRAINT ck_chats_kind CHECK (kind IN ('direct','group'));
ALTER TABLE chats ADD CONSTRAINT ck_chats_shape CHECK (
    (kind='direct' AND user_low_id IS NOT NULL AND user_high_id IS NOT NULL)
    OR (kind='group' AND user_low_id IS NULL AND user_high_id IS NULL));
ALTER TABLE chats ADD CONSTRAINT ck_chats_title CHECK (
    kind='direct' OR (title IS NOT NULL AND length(btrim(title)) BETWEEN 1 AND 64));
ALTER TABLE chats ADD CONSTRAINT ck_chats_description CHECK (
    kind='direct' OR description IS NULL OR length(description) <= 256);

ALTER TABLE chat_participants
    ADD COLUMN role varchar(16),
    ADD COLUMN state varchar(16) NOT NULL DEFAULT 'active';
ALTER TABLE chat_participants ADD CONSTRAINT ck_chat_participants_role
    CHECK (role IN ('owner','admin','member') OR role IS NULL);
ALTER TABLE chat_participants ADD CONSTRAINT ck_chat_participants_state
    CHECK (state IN ('active','removed'));

CREATE UNIQUE INDEX ux_chat_participants_owner
    ON chat_participants (chat_id) WHERE role = 'owner';
CREATE INDEX ix_chat_participants_chat_active
    ON chat_participants (chat_id) INCLUDE (user_id, role, last_read_seq)
    WHERE state = 'active';

CREATE TABLE group_admin_log (
    id             uuid PRIMARY KEY,
    group_id       uuid NOT NULL,             -- без FK: переживает hard-delete группы
    actor_id       uuid NOT NULL REFERENCES users (id),
    target_user_id uuid REFERENCES users (id),
    action         varchar(32) NOT NULL,
    created_at     timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_group_admin_log_action CHECK (action IN
        ('group_created','title_changed','description_changed','member_added',
         'member_removed','member_left','admin_granted','admin_revoked',
         'ownership_transferred','group_deleted'))
);
CREATE INDEX ix_group_admin_log_group ON group_admin_log (group_id, created_at);
```

Backfill не нужен (default'ы корректны: все существующие чаты — direct, все участники — active,
role NULL). Индекс `ix_chat_participants_user` (V12) остаётся для loadForSync №26 — отбор
дорабатывается фильтром `state='active'`. Down-path не предусмотрен (Flyway, конвенция 002).
