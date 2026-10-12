# Data Model: 008b-auth-ux-polish

**Date**: 2026-10-11 | Спека: [spec.md](./spec.md) | Решения: [research.md](./research.md)

Изменения строго аддитивные. Три измерения: durable (PG, миграция `V17__password_change_onboarding.sql`),
эфемерные (Redis, TTL), контрактные представления ([contracts/auth-ux-polish-api.md](./contracts/auth-ux-polish-api.md)).
Новых таблиц нет; одно новое поведение на стыке существующих сущностей 002/003/004.

---

## 1. Durable-модель (PostgreSQL, миграция V17)

### 1.1 `users` — + `starter_contact_added`

| Поле | Тип | Ограничения | Описание |
|---|---|---|---|
| `starter_contact_added` | `BOOLEAN NOT NULL DEFAULT FALSE` | — | Дискриминатор онбординга (FR-011): получал ли аккаунт стартовый контакт admin. |

- **Начальное состояние**: V17 выполняет `UPDATE users SET starter_contact_added = TRUE`
  — все аккаунты, существовавшие на момент развёртывания, помечаются «обработанными»
  **без добавления контактов** (бэкфилла нет — clarify spec.md:16).
- **Запись**: `TRUE` ставится атомарно (одна `@Transactional`) с идемпотентным
  `INSERT … ON CONFLICT DO NOTHING` в `user_contacts` — либо событием регистрации
  (§3.1), либо self-healing фолбэком (§3.2); `UPDATE … WHERE id = ? AND NOT
  starter_contact_added` — повторяемо без эффекта.
- **Чтение**: точечный SELECT по PK в `ensureOnboarded` при каждом `GET /contacts`;
  после `TRUE` — вечный short-circuit.
- **Обратный переход запрещён**: `TRUE → FALSE` не существует ни в одном пути. Удаление
  пользователем контакта admin флаг не сбрасывает — повторного автодобавления нет
  (US4 AC5). Сбросить онбординг нельзя и вручную — API для флага не вводится (YAGNI).
- **Seed**: строка admin создаётся сразу с `starter_contact_added = TRUE` (сам admin
  никогда не цель онбординга).

### 1.2 Расширение enum `auth_event_type`

```sql
ALTER TYPE auth_event_type ADD VALUE 'password_change_completed';
ALTER TYPE auth_event_type ADD VALUE 'password_change_failed';
```

| Значение | Событие | Поля записи |
|---|---|---|
| `password_change_completed` | Успешная смена пароля из профиля | `user_id`, IP (SHA-256+pepper), UA, `details={reason:"profile"}` |
| `password_change_failed` | Неверный текущий пароль при смене | то же, `details={reason:"invalid_current_password"}` |

- Прецедент пары `LOGIN_SUCCESS`/`LOGIN_FAILED` (AuthEvents.kt:12-38); миграция enum —
  по образцу V8__sso_enums.sql:19-24. Нарушения политики (`too_short` и пр.) журналом
  не фиксируются отдельно — это обычная 400-валидация, как в reset-confirm 002.
- Отзыв прочих сессий при смене фиксируется существующими записями `SESSION_REVOKED` c
  `details={reason:"password_change"}` (одна на каждую отозванную сессию — паттерн
  SessionService.revokeAllForUser). Записи неразличимы с reset-флоу 002 — корреляция
  смены из профиля по соседней записи `password_change_completed` (у reset её нет);
  принято осознанно.
- Ни в одной записи — паролей, хешей или токенов (FR-010, SC-004).

### 1.3 Системный аккаунт admin (seed, не новая сущность)

Строка `users`, создаваемая `AdminSeedRunner` при каждом старте, если отсутствует:

| Поле | Значение |
|---|---|
| `username` | `'admin'` |
| `email` | `admin.seed.email` (env `ADMIN_SEED_EMAIL`; dev: `admin@webchat.local`) |
| `password_hash` | Argon2id(env `ADMIN_SEED_PASSWORD`) или `NULL`, если env не задан |
| `status` / `email_confirmed_at` | `'active'` / `now()` |
| `password_set_at` | `now()` при заданном пароле, иначе `NULL` |
| `display_name` | `'Administrator'` |
| `starter_contact_added` | `TRUE` |

- **Идемпотентность**: `INSERT … ON CONFLICT DO NOTHING` (expression-индексы
  `lower(username)`/`lower(email)` V1:23-24 → arbiter не указывается); UPDATE никогда
  не выполняется — занятый username/email не перезаписывается (FR-012, edge spec.md:103).
  Конкурентный старт реплик безопасен (ожидание на индексной записи → DO NOTHING).
- **Беспарольная форма** = JIT-форма 003: проходит `ck_users_active_implies_email`;
  пароль задаётся позже флоу 002 (reset принимает активные аккаунты без пароля,
  PasswordResetService.kt:74-78). Логин по паролю для неё — timing-safe 401.
- **Наблюдаемость**: `admin_seed_total{outcome=created|skipped|failed}` +
  k=v-логирование; пропуск (занято имя/почта) — warn, не ошибка старта.

### 1.4 Стартовый контакт (не новая сущность)

Строка `user_contacts (owner_id = новый пользователь, contact_user_id = admin)` модели
004: создаётся идемпотентно, дальше — обычный контакт (единый диалог пары, alias,
удаление №22, FR-013). Никаких особых правил/полей.

---

## 2. Эфемерная модель (Redis)

Новых постоянных ключей нет. Временно живут (наследованные механизмы):

| Ключ | Механизм | TTL/семантика |
|---|---|---|
| `rl:ip:password-change:<ip>` | Bucket4j (RateLimitFilter) | окно 1 мин; см. §5 контракта |
| `rl:user:pwdchange:<userId>` | Bucket4j (UserRateLimiter) | окно 1 мин |
| `auth:denylist:sid:<sid>` | существующий denylist | TTL = access TTL; пишется **только для отзываемых прочих сессий**; текущая сессия NEVER (иначе гасится новый access) |

Событие регистрации in-process (Spring `ApplicationEvent`), durable-очереди нет — потеря
покрыта фолбэком §3.2 (решение research.md §2, YAGNI).

---

## 3. Операция «смена пароля» (переходы состояния)

### 3.1 Путь события онбординга

```text
RegistrationService.setPassword (tx)            IdentityResolutionService.provisionJitAccount (tx)
  publish RegistrationCompletedEvent(userId)      publish RegistrationCompletedEvent(userId)
            │ (commit)                                      │ (commit)
            ▼ AFTER_COMMIT @Async                          ▼
  StarterContactEventListener → StarterContactService.ensureOnboarded(userId)
    flag FALSE? → [INSERT contact ON CONFLICT DO NOTHING + SET flag TRUE] (одна tx)
    любое исключение → log.warn + counter, не роняет вызывающего (потеря → §3.2)
```

### 3.2 Путь self-healing фолбэка

```text
GET /contacts (ContactController.list)
  → ensureOnboarded(userId) [best-effort try/catch]
      flag TRUE → выход (навсегда)
      flag FALSE → resolve admin id:
          NULL (seed пропущен) → flag FALSE, warn+counter, повтор при следующем GET
          id → [INSERT ... ON CONFLICT DO NOTHING + условный SET flag TRUE] (одна tx)
```

Гонки «событие × фолбэк × повторная доставка» схлопываются в `ON CONFLICT DO NOTHING`
+ условный UPDATE: контакт admin у пользователя — ровно 0 или 1 запись (SC-003).

### 3.3 Переходы `users.password_hash` / сессий при смене пароля

```text
POST /auth/password-change (Bearer, sid = claim текущего access)
  1 rate limit (per-IP + per-user) ── превышен ──→ 429 (Retry-After), состояние не меняется
  2 passwordHash == NULL (JIT)? ── фиктивная сверка ──→ 400 currentPassword:invalid + PASSWORD_CHANGE_FAILED
  3 !matches(current, hash)? ──→ 400 currentPassword:invalid + PASSWORD_CHANGE_FAILED
  4 политика нового пароля (002) ── нарушение ──→ 400 newPassword:<code>, состояние не меняется
  5 user.changePassword(newHash, now) → users.password_hash/password_set_at обновлены
  6 revokeActiveForUser(userId)          — все активные refresh-генерации отозваны
    revokeAllForUserExcept(userId, sid)  — прочие сессии revoked + denylist + SESSION_REVOKED×N
    issueGeneration(sid)                 — текущая сессия: НОВЫЙ refresh + НОВЫЙ access
  7 PASSWORD_CHANGE_COMPLETED; ответ TokenPair{новые токены}
```

- Инвариант FR-008: после шага 6 активна ровно одна сессия пользователя — текущая;
  вход по старому паролю невозможен (шаг 5), по новому — успешен.
- Совпадение нового пароля со старым не ветвится: полный путь 6–7 выполняется
  (clarify spec.md:17).
- Восстановление пароля по email-ссылке (002) сохраняет семантику «отзыв всех сессий»
  без исключений — пути не пересекаются (edge spec.md:106).

---

## 4. Контрактные представления

См. [contracts/auth-ux-polish-api.md](./contracts/auth-ux-polish-api.md):
`GET /users/me` → `PublicUser + hasPassword` (inline allOf); `POST /auth/password-change`
→ `TokenPair`; фронтендовые механики `returnTo` (клиентские, контракт не меняют).

---

## 5. Проверяемые инварианты (входят в приёмку задач)

1. `starter_contact_added` монотонен (FALSE→TRUE), обратного перехода нет.
2. Контакт admin существует у пользователя ⇔ не более одной строки
   `user_contacts(owner, admin)` (PK V11 + ON CONFLICT).
3. После успешной смены пароля: `password_hash` = Argon2id c индивидуальной солью;
   активных refresh-генераций пользователя ровно 1 (текущая, новая); прочие sid'ы в
   denylist и `sessions.status = revoked (password_change)`.
4. В `auth_events` по смене — записи без секретов; `-0-` открытых паролей/токенов в
   PG и логах (SC-004).
5. Seed: повторные запуски runner'а не меняют строку admin (idempotent, no-overwrite).
