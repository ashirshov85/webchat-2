# Data Model: Регистрация, вход и управление сессиями

**Feature**: 002-user-registration | **Date**: 2026-09-11

Первое персистентное состояние проекта. Долговечные данные — PostgreSQL 17 (миграции Flyway,
[research.md §1](./research.md)); эфемерные (лимиты, denylist) — Redis ([research.md §2, §4, §8]).
Все пароли/токены хранятся и логируются **только** в виде хеша (FR-004, SC-005).

---

## Сущность 1: User (аккаунт)

Идентичность пользователя (FR-001, FR-003).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | генерируется сервером |
| `username` | varchar(32) | 3–32 симв., `^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$`; **уникален**: уникальный индекс `lower(username)` |
| `email` | varchar(254) | формат email, ≤ 254 симв.; **уникален**: уникальный индекс `lower(email)`; хранится как введён |
| `password_hash` | varchar | NULL до задания пароля; формат `{argon2}...` (DelegatingPasswordEncoder), Argon2id m=19456,t=2,p=1, соль индивидуальная (внутри хеша) |
| `status` | enum | `pending_email_confirmation` → `awaiting_password` → `active` (переходы ниже) |
| `email_confirmed_at` | timestamptz NULL | момент подтверждения email |
| `password_set_at` | timestamptz NULL | момент задания пароля |
| `created_at` / `updated_at` | timestamptz | — |

**Переходы состояний** (FR-003):

```text
pending_email_confirmation ──(подтверждение по ссылке)──► awaiting_password
awaiting_password ──(задание пароля, политика FR-004)──► active
```

- `active` ⟺ `email_confirmed_at IS NOT NULL AND password_hash IS NOT NULL`.
- Вход разрешён **только** из `active`; иначе — «завершите регистрацию» (US1-5).
- Повторная регистрация: конфликт с `active`-аккаунтом → 409 (US1-4); полное совпадение пары
  (username+email) на **незавершённом** аккаунте → возобновление: аннулируются старые токены,
  высылается новое письмо, дубль не создаётся (edge spec).

## Сущность 2: OneTimeToken (одноразовые ссылки из писем)

Единая таблица для трёх логических типов одноразовых токенов (FR-002, FR-010, FR-012):

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | — |
| `user_id` | UUID FK → users | CASCADE |
| `purpose` | enum | `email_verification` (TTL 24 ч) / `password_setup` (TTL 1 ч) / `password_reset` (TTL 1 ч) |
| `token_hash` | char(64) | **SHA-256** от 256-bit opaque-значения; UNIQUE; открытое значение существует только в ссылке письма |
| `expires_at` | timestamptz | по purpose (таблица [research.md §11](./research.md)) |
| `used_at` | timestamptz NULL | single-use |
| `created_at` | timestamptz | — |

**Инварианты / идемпотентность (FR-012)**: поглощение — условный
`UPDATE ... SET used_at = now() WHERE id = ? AND token_hash = ? AND used_at IS NULL AND expires_at > now()`;
rowcount = 0 → «ссылка использована/истекла — запросите новую» (edge spec), дублей и
side-effects нет. Новая ссылка того же purpose аннулирует предыдущие активные того же user
(одна живая ссылка на пользователя+purpose). `password_setup` выдаётся ответом endpoint
подтверждения email ИЛИ письмом при возобновлении незавершённой регистрации (resend/повторный
register в статусе `awaiting_password`, включая истечение setupToken — edge spec); письмо — тип
`password_setup` со ссылкой `/set-password?token=...`. `password_reset`: применение меняет пароль и отзывает
все сессии пользователя (FR-010).

## Сущность 3: Session (сессия = цепочка refresh-поколений)

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | значение claim `sid` access-токена |
| `user_id` | UUID FK → users | параллельные сессии одного user разрешены (edge spec) |
| `status` | enum | `active` → `revoked` \| `compromised` (переходы ниже) |
| `created_at` / `last_refreshed_at` | timestamptz | — |
| `revoked_at` | timestamptz NULL | — |
| `revoked_reason` | enum NULL | `logout` / `password_change` / `refresh_reuse_detected` |

**Переходы состояний**:

```text
active ──logout (FR-011)───────────► revoked(reason=logout)
active ──смена пароля (FR-010)─────► revoked(reason=password_change)   # ВСЕ сессии user
active ──reuse ротированного refresh► compromised(reason=refresh_reuse_detected)
```

Каждый отзыв: denylist `auth:denylist:sid:<sid>` в Redis с TTL = max остаток жизни access-токенов
этой сессии → мгновенное отклонение уже выданных access-токенов (FR-006, FR-011).

## Сущность 4: RefreshToken (поколение refresh-токена)

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | — |
| `session_id` | UUID FK → sessions | цепочка поколений |
| `token_hash` | char(64) UNIQUE | SHA-256 от 256-bit opaque-значения (открытое значение — только у клиента) |
| `status` | enum | `active` → `rotated` \| `revoked` |
| `expires_at` | timestamptz | TTL 3 дня, sliding (новое поколение — новые 3 дня) |
| `replaced_by` | UUID NULL FK → refresh_tokens | ротация |
| `created_at` | timestamptz | — |

**Ротация (FR-006)** — атомарно в одной транзакции PG:

```text
UPDATE refresh_tokens SET status='rotated', replaced_by=:newId
 WHERE id=:id AND token_hash=:hash AND status='active' AND expires_at > now()
rowcount=1 → INSERT новое активное поколение, выдать новую пару токенов
rowcount=0 → ПОВТОРНОЕ ИСПОЛЬЗОВАНИЕ: session→compromised, отзыв цепочки, denylist sid, 401
```

Смена пароля: `UPDATE sessions SET status='revoked', revoked_reason='password_change'
WHERE user_id=:u AND status='active'` + denylist каждого `sid` (FR-010, US4-2).

## Сущность 5: EmailOutbox (транзакционные письма)

Долговечная очередь доставки через заменяемый шлюз (FR-008, SC-007; [research.md §6](./research.md)).

| Поле | Тип | Валидация / инварианты |
|---|---|---|
| `id` | UUID PK | — |
| `recipient_email` | varchar(254) | адрес из аккаунта |
| `email_type` | enum | `email_verification` / `email_verification_repeat` / `password_setup` / `password_reset` |
| `payload` | jsonb | параметры рендера: ссылка (open-значение токена), username, base-url |
| `status` | enum | `pending` → `sent` \| `failed_permanent` |
| `attempts` | int | максимум 10 |
| `next_attempt_at` | timestamptz | поллинг 5 с; backoff 1→5→15 мин, потолок 1 ч |
| `last_error` | text NULL | без секретов |
| `sent_at` | timestamptz NULL | — |
| `created_at` | timestamptz | — |

**Инварианты**: вставка — в одной транзакции с user/OneTimeToken (письмо не теряется при сбое
между commit и отправкой); поллер — `SELECT ... FOR UPDATE SKIP LOCKED` (безопасен при N реплик);
сбой SMTP ≠ 5xx публичного endpoint; каждая попытка — метрика `email_delivery_total{type,outcome}`
и структурный лог (`outbox_id`, `recipient_hash`). Повторная отправка (resend) — новая outbox-строка
только если последняя строка того же типа для того же user старше 60 с (`MAX(created_at)` независимо
от статуса pending/sent/failed — poller помечает `sent` за секунды, привязка к `pending` делала бы
cooldown пустым); это второй эшелон после бакета `rl:email:resend`, приоритет механизмов —
[research.md §11](./research.md) (US1-6).

## Сущность 6: AuthEvent (журнал событий безопасности)

FR-013. Без секретов: ни паролей, ни открытых токенов, ни полных PII.

| Поле | Тип | Валидация |
|---|---|---|
| `id` | UUID PK | — |
| `user_id` | UUID NULL FK | NULL для событий до создания/поиска аккаунта |
| `event_type` | enum | `register_requested`, `email_confirmed`, `password_set`, `login_success`, `login_failed`, `login_throttled`, `refresh_rotated`, `refresh_reuse_detected`, `logout`, `password_reset_requested`, `password_reset_completed`, `session_revoked` |
| `occurred_at` | timestamptz | — |
| `ip_hash` | varchar | SHA-256(IP + server-side pepper) — не сырой IP |
| `user_agent` | varchar(256) NULL | усечён |
| `details` | jsonb | контекст (например, `{"reason":"logout"}`); без секретов |
| `trace_id` | varchar NULL | корреляция с трейсами LGTM |

## Сущность 7: Эфемерные структуры Redis (не мигрируются)

| Ключ | Значение | TTL | Назначение |
|---|---|---|---|
| `rl:ip:<route>:<ip>` | бакет Bucket4j | по лимиту маршрута | лимит на источник (FR-009) |
| `rl:email:<route>:<sha256(email)>` | бакет Bucket4j | по лимиту маршрута | лимит на email/аккаунт (FR-009) |
| `login:fail:<sha256(identifier)>` | счётчик INCR | 15 мин (EXPIRE NX), сброс при успехе | прогрессивная задержка/блокировка ([research.md §8](./research.md)) |
| `auth:denylist:sid:<sid>` | маркер | ≤ остаток TTL access-токена | мгновенный отзыв (FR-006/010/011) |

Инвариант: потеря Redis не ломает аутентичность данных (PG) — сбрасываются только лимиты
(краткое окно) и denylist (отозванные access живут ≤ 5 мин — граница, заданная TTL).

## Связи

```text
User 1 ──n OneTimeToken          (подтверждение/установка/сброс пароля)
User 1 ──n Session ──n RefreshToken (цепочка ротации: replaced_by)
User 1 ──n EmailOutbox           (доставка писем)
User 1 ──n AuthEvent             (журнал безопасности)
EmailOutbox n ──1 OneTimeToken   (ссылка в письме — открытое значение токена, создаётся в одной TX)
```

## Проверка требований спека

| Требование | Покрытие |
|---|---|
| FR-001 | User: уникальные lower-индексы username/email, валидация формата |
| FR-002 | OneTimeToken(email_verification) + EmailOutbox + resend-cooldown |
| FR-003 | переходы статусов User; вход только из `active` |
| FR-004 | password_hash: Argon2id с индивидуальной солью; политика 8–128 |
| FR-005 | единый код-путь входа, фиктивный хеш (тайминг), единая ошибка |
| FR-006 | RefreshToken CAS-ротация; reuse → compromised + отзыв цепочки |
| FR-007 | Session.sid в JWT + публичный список путей (контракт) + denylist |
| FR-008 | EmailOutbox + заменяемый EmailGateway + метрики |
| FR-009 | Redis-структуры §7 (общие для всех реплик) |
| FR-010 | OneTimeToken(password_reset) + отзыв всех Session при смене |
| FR-011 | Session → revoked(logout) + denylist |
| FR-012 | условное поглощение OneTimeToken (идемпотентность) |
| FR-013 | AuthEvent без секретов, с trace_id |
