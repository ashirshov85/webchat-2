# Data Model: Видеоконференции в чатах (речь, видео, демонстрация экрана)

**Feature**: 009-video-conferences | **Date**: 2026-10-15

Новое долговременное состояние — PostgreSQL 17, миграция **V18__conferences.sql** (3 новые
таблицы `conferences`, `conference_participants`, `conference_guest_invites` + эволюция
`messages` до системных виджетов; конвенции 002/004/006: snake_case, `uuid PK`,
`timestamptz DEFAULT now()`, CHECK-инварианты, частичные уникальные индексы). Redis 7 —
только rate-limit-бакеты публичных endpoints (`rl:ip:conferences-*`, паттерн 002) и keyspace
LiveKit (dev — общий инстанс, prod — отдельный logical DB; [research.md §11](./research.md)).
**Медиа-состояние (комнаты, подключения, треки, мьюты подключений) — в LiveKit и не
дублируется в БД** ([research.md §4/§7](./research.md)): БД хранит только то, что должно
переживать реконнект (роли, ограничения) и жить после завершения (сам факт конференции).

Диаграмма связей (дельта к 001–008):

```text
users (002) ──< chat_participants >── chats (004/006) ──< messages (V18: + kind/payload)
                     │                        │                          └─ kind='system':
                     │ membership-check       │ одна активная              payload {"type":"conference_invite",…}
                     │ на каждом join         ▼
                     │                 conferences ──< conference_guest_invites (одна active)
                     │                        │
                     └──────< conference_participants (принципалы: члены по user_id,
                              гости по connection-строке; роли + ограничения)

LiveKit (не в БД): room c:{conferenceId}, participants u:{userId}:{connId} / g:{connId},
                    треки/мьюты подключений; статусы синхронизируются вебхуками (research §7)
```

---

## Сущность 1: Conference (`conferences`)

«Звонок», привязанный к чату (личным или групповым — `chats.kind`); порождает системное
сообщение (сущность 4) и mediа-комнату `c:{id}` в LiveKit. Одна **не-завершённая** конференция
на чат (edge «двойное нажатие», FR-001).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | uuid PK | серверный; имя комнаты LiveKit = `c:{id}`; публичный `conferenceId` |
| `chat_id` | uuid FK → chats `ON DELETE CASCADE` | конференция умирает вместе с чатом; E2EE-чатов (010) в v1 не бывает — проверка сервисом (граница v1) |
| `created_by` | uuid FK → users `ON DELETE CASCADE` | инициатор; его первая строка в `conference_participants` — `role='moderator'` (модератор по умолчанию, FR-008) |
| `status` | varchar(16) NOT NULL | CHECK `IN ('created','active','ended')`; переходы только вперёд (см. ниже), conditional UPDATE — идемпотентность finalize |
| `max_participants` | int NOT NULL | CHECK `> 0`; снапшот `conferences.max-participants` при создании (FR-015: применяется и как `max_participants` комнаты LiveKit, и в pre-check минта) |
| `created_at` | timestamptz NOT NULL DEFAULT now() | |
| `started_at` | timestamptz NULL | первый `participant_joined` (вебхук `room_started`); NULL ⟺ `status='created'` |
| `ended_at` | timestamptz NULL | NULL ⟺ `status <> 'ended'` |
| `end_reason` | varchar(32) NULL | CHECK в `('last_left','moderator','max_duration','room_lost','chat_deleted')`; NULL ⟺ `status <> 'ended'` |

Индексы: `CREATE UNIQUE INDEX ux_conferences_chat_active ON conferences (chat_id) WHERE status <> 'ended'`
(инвариант «одна активная на чат» гарантируется хранилищем; повторное создание возвращает
существующую — идемпотентность FR-001); `ix_conferences_status ON conferences (status)` — sweeper
и gauge-метрики (research §7/§13).

**Переходы состояний**: `created → active` — вебхук `room_started` (первый вход, conditional
UPDATE `WHERE status='created'`, дубли вебхука — no-op); `created|active → ended` —
finalize (вебхук `room_finished` / модератор №48 / scheduler max-duration / sweeper / CASCADE
чата, [research.md §7](./research.md)); обратных переходов нет. `started_at`/`ended_at`/`end_reason`
заполняются в соответствующих переходах (CHECK согласованности: `status='ended' ⟺ ended_at IS NOT NULL`).

## Сущность 2: ConferenceParticipant (`conference_participants`)

Принципал конференции — носитель роли и модерационных ограничений, **переживающий
переподключение** (Q5=A, Q9=A, Q22=A). Член чата — одна строка на `(conference_id, user_id)`;
гость — строка на каждое подключение (новый вход гостя = новая строка, Q7=A; следствие:
ограничения гостя не переживают его переподключение — [research.md §5](./research.md)).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | uuid PK | серверный |
| `conference_id` | uuid FK → conferences `ON DELETE CASCADE` | |
| `identity` | varchar(80) NOT NULL | префикс connection-identity LiveKit: `u:{userId}` (член) или `g:{connId}` (гость); UNIQUE — парсится адаптером для поиска строки по identity подключившегося ([research.md §4](./research.md)) |
| `user_id` | uuid FK → users `ON DELETE CASCADE`, NULL | NULL ⟺ гость; NOT NULL ⟺ член чата (проверка membership при создании строки) |
| `display_name` | varchar(64) NULL | гость: введённое имя (trim, 1–50, Q19=A; неуникально, Q7=A); член: NULL (имя берётся из users при минте токена) |
| `role` | varchar(16) NOT NULL DEFAULT 'participant' | CHECK `IN ('moderator','participant')`; создатель — `moderator`; роли «владелец» нет (Q21=A) |
| `mic_restricted` | boolean NOT NULL DEFAULT false | модерационное ограничение источника microphone |
| `camera_restricted` | boolean NOT NULL DEFAULT false | источника camera |
| `screen_restricted` | boolean NOT NULL DEFAULT false | источника screen_share (+ screen_share_audio) — персистентное точечное ограничение (Q22=A) |
| `speech_restricted` | boolean NOT NULL DEFAULT false | «право слова»: блокирует все публикации (Q4=A) — при минте даёт `canPublish=false` |
| `first_seen_at` | timestamptz NOT NULL DEFAULT now() | |
| `last_joined_at` | timestamptz NULL | вебхук `participant_joined` по совпадению identity-префикса |
| `last_left_at` | timestamptz NULL | вебхук `participant_left` |

Индексы: `UNIQUE (conference_id, user_id) WHERE user_id IS NOT NULL` (одна строка принципала-члена);
`ix_conference_participants_conference ON (conference_id)`. Shape-CHECK:
`(user_id IS NULL) = (display_name IS NOT NULL)` (гость обязан иметь имя, член — нет).

Семантика полей для grants (единая функция вычисления, [research.md §4/§5](./research.md)):
`speech_restricted` ⟹ `canPublish=false`; иначе `canPublishSources = {mic, camera, screen_share,
screen_share_audio} − {mic×mic_restricted, camera×camera_restricted, screen_share+
screen_share_audio×screen_restricted}`. Ограничения меняются только PUT №49 (модератор), снимаются
туда же; кик (№51) строку не трогает (кик ≠ бан — переподключение создаёт/реиспользует строку с
прежними ограничениями). **На finalize конференции все строки удаляются** (Q18=A: состав
завершённой конференции не сохраняется и не показывается — приватность, принцип V).

## Сущность 3: GuestInvite (`conference_guest_invites`)

Гостевая инвайт-ссылка: stateless-подпись (JWT-nonce, [research.md §6](./research.md)) +
минимальная строка-статус для отзыва и инварианта «одна активная» (Q17=A). Не постится в чат.

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | uuid PK | = `sub` JWT-nonce; публично в ссылке только в подписанном виде |
| `conference_id` | uuid FK → conferences `ON DELETE CASCADE` | |
| `created_by` | uuid FK → users `ON DELETE CASCADE` | модератор, сгенерировавший ссылку |
| `status` | varchar(16) NOT NULL | CHECK `IN ('active','revoked')` |
| `expires_at` | timestamptz NOT NULL | = `exp` nonce; дефолт now()+`conferences.guest-invite-ttl` (24 ч, Q19=A); CHECK `expires_at > created_at` |
| `created_at` | timestamptz NOT NULL DEFAULT now() | |
| `revoked_at` | timestamptz NULL | NULL ⟺ `status='active'`; генерация новой ссылки отзывает предыдущую (Q17=A), явный отзыв (№53) — то же |

Индексы: `CREATE UNIQUE INDEX ux_guest_invites_active ON conference_guest_invites (conference_id)
WHERE status='active'` (хранилищный инвариант «одна активная ссылка»; генерация новой —
`UPDATE … SET revoked` + `INSERT` в одной транзакции не нарушает индекс). Валидация входа:
подпись/`exp` JWT → `status='active'` → `conferences.status <> 'ended'` и комната жива; отзыв
post-commit отключает всех гостей с `metadata.inviteId = id` ([research.md §6](./research.md)).
Строки удаляются на finalize (Q18=A).

## Сущность 4: Message → системные виджеты (эволюция `messages`, V18)

Первый тип сообщений не от пользователя — карточка-приглашение (FR-002); расширяемая система
`kind`+`payload` (конституция IV: новые виджеты — новые `payload.type` без правки ядра).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `sender_id` | uuid FK → users, **NULL** (было NOT NULL) | NULL ⟺ `kind='system'`; миграция существующих строк — дефолт `kind='user'`, значения не меняются |
| `kind` | varchar(16) NOT NULL DEFAULT 'user' | CHECK `IN ('user','system')`; расширение будущими виджетами — additive |
| `payload` | jsonb NULL | CHECK: `kind='user'` ⟹ `sender_id IS NOT NULL AND payload IS NULL`; `kind='system'` ⟹ `sender_id IS NULL AND payload IS NOT NULL AND text` — служебный плейсхолдер/заголовок виджета |

Payload виджета v1: `{"type":"conference_invite","conferenceId":"<uuid>","mode":"video|audio"}`
(«Подключиться» = переход по ссылке на pre-join; состояние конференции проверяется при клике —
история не дополняется при завершении, Q20=A). Системное сообщение создаётся **в транзакции
создания конференции** с существующим выделением `seq` и post-commit фанаутом `message.created`
(идемпотентность — за счёт `ux_conferences_chat_active`: вторая попытка создания не доходит до
INSERT); id — серверный UUID (дедуп-механизм 004 не затронут). Ограничения на `payload`:
CHECK `jsonb_typeof(payload) = 'object'`, размер — сервисная валидация (≤ 4 КБ).

## Состояние вне БД (контракт соответствия)

- **LiveKit** (источник истины на время звонка): комната `c:{conferenceId}` c
  `max_participants`, `empty_timeout`/`departure_timeout` = 60 c (Q19); подключения
  `u:{userId}:{connId}` / `g:{connId}` (+ `name`, metadata `{"role","guest","inviteId"}`),
  треки и серверные мьюты подключений; квота слотов = `Room.num_participants`.
- **Redis**: бакеты `rl:ip:conferences-invite-info|join-by-invite|webhook:<ip>` (паттерн 002);
  keyspace LiveKit (infra, dev-share/prod-изоляция).
- **In-memory отсутствует**: ни один инстанс бэкенда не держит конференц-состояние (II) —
  sweeper/scheduler восстанавливают согласованность из БД + LiveKit после любого сбоя.

## Примеры инвариантов, проверяемых тестами (принцип VI)

1. Одна не-`ended` конференция на чат при конкурентных созданиях (ux-индекс; IT).
2. `join` не-члена чата → отказ до всякой строки participant (SC-002).
3. Ограничения принципала воспроизводятся в grants при повторном минте (Q5=A; unit + IT).
4. Отзыв инвайта → отказ `join-by-invite` и отключение вошедших по нему гостей (Q13=B; IT).
5. finalize идемпотентен: дублированные вебхуки `room_finished` не меняют `ended_at`/`end_reason` (IT).
6. После finalize: participant/invite-строки отсутствуют, старая ссылка отвечает
   «конференция завершена», история чата не изменена (Q18/Q20=A; IT).
