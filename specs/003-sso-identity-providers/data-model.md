# Data Model: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Feature**: `003-sso-identity-providers` | **Date**: 2026-09-16
Ссылки: [research.md](research.md) (решения), [contracts/sso-api.md](contracts/sso-api.md) (интерфейсы).

Расширяет модель фичи 002 (`specs/002-user-registration/data-model.md`): `users`,
`sessions`, `one_time_tokens`, `auth_events` — без breaking-изменений; добавляет
`external_identities`, эфемерные Redis-структуры SSO и конфигурационную сущность
провайдера.

---

## 1. Entity: External Identity (внешняя идентичность)

Привязка внешнего аккаунта к аккаунту WebChat. Принадлежит ровно одному User;
пара (провайдер, идентификатор) уникальна глобально (FR-006).

| Поле | Тип | Ограничения / описание |
|---|---|---|
| `id` | UUID PK | генерируется приложением |
| `user_id` | UUID NOT NULL | FK → `users(id)` ON DELETE CASCADE |
| `provider_id` | varchar(64) NOT NULL | код провайдера из конфигурации (§4); FK нет намеренно — привязки переживают выключение/удаление провайдера из конфига (US4-3) |
| `subject` | varchar(255) NOT NULL | уникальный идентификатор пользователя у провайдера (`sub` из ID-токена; OIDC `sub` ≤255 ASCII) |
| `provider_email` | varchar(254) NULL | email от провайдера; NULL, если провайдер не вернул; обновляется при каждом успешном входе (US3: отображение в настройках) |
| `provider_email_verified` | boolean NOT NULL DEFAULT false | признак `email_verified` от провайдера на момент последнего входа/привязки |
| `linked_at` | timestamptz NOT NULL | момент привязки |

**Уникальность**: `UNIQUE (provider_id, subject)` — глобальная единственность
владельца идентичности (FR-006, edge «один внешний аккаунт у двух пользователей»).

**Индекс**: `ix_external_identities_user (user_id)` — список привязок пользователя.

**Правила валидации** (приложение):
- `provider_id` соответствует `^[a-z0-9][a-z0-9-]{0,63}$` и обязан ссылаться на
  известный конфигурации код при создании (отвязка/чтение работают и для
  исчезнувших из конфига кодов).
- `subject` непустой; провайдер без уникального `sub` считается
  некорректно настроенным — флоу отклоняется (edge spec).
- `provider_email` нормализуется trimming'ом (хранение as-is, регистр сохраняется —
  конвенция 002); сопоставление с email аккаунтов — всегда через `lower()`.

## 2. Entity: User (расширение фичи 002)

Без изменений колонок. Изменяются инварианты:

- **Было (V1)**: `ck_users_active_implies_credentials`:
  `(status='active') = (email_confirmed_at IS NOT NULL AND password_hash IS NOT NULL)`.
- **Стало (V9)**: `ck_users_active_implies_email` (импликация, не равенство:
  `awaiting_password` фичи 002 имеет подтверждённый email при status ≠ 'active'):
  `status='active' → email_confirmed_at IS NOT NULL`.
- Новый инвариант (app + DB-триггер, §6): **у аккаунта с `password_hash IS NULL`
  всегда ≥1 внешняя привязка** (нельзя удалить последнюю — US3-4, edge
  «не осталось ни пароля, ни привязок»).

**JIT-создание** (FR-003): вставка сразу `status='active'`,
`email_confirmed_at = now()` (email подтверждён провайдером), `password_hash = NULL`,
email — от провайдера (регистронезависимая уникальность через существующий
`ux_users_email_lower`), username — из `UsernameGenerator` (research §7).
Письма не отправляются. Переходы `UserStatus` не задействованы (нет
pending-предыстории); вход по паролю для JIT — uniform 401 (fictitious-hash путь
`LoginService`, FR-013); задание пароля — через password-reset 002 (активный
аккаунт подходит).

## 3. Entity: Session (расширение фичи 002)

| Новое поле | Тип | Описание |
|---|---|---|
| `auth_method` | varchar(16) NULL | `'password' \| 'sso'`; NULL = legacy-строки 002 (трактуется как password) |
| `identity_id` | UUID NULL | FK → `external_identities(id)` ON DELETE SET NULL; какая идентичность создала сессию |

Формат access-JWT (ES256, клеймы `sub/sid/jti/iat/exp/typ`), ротация refresh (CAS),
denylist, причины отзыва — **без изменений** (FR-002: способ входа не влияет на
права). `ON DELETE SET NULL` реализует clarify-правило «отвязка не отзывает
сессии».

## 4. Entity: Identity Provider (конфигурационная, вне БД)

Источник — `sso.providers.<id>` в YAML (research §4); сущность валидируется на
старте (fail-fast).

| Атрибут | Тип/формат | Default | Описание |
|---|---|---|---|
| `id` | `^[a-z0-9][a-z0-9-]{0,63}$` | — | код провайдера (UI, external_identities) |
| `display-name` | string | — | отображаемое имя на экране входа |
| `enabled` | boolean | `true` | выключенный скрыт с экрана входа; его authorize/callback отклоняются; привязки сохраняются |
| `trusted-for-email-linking` | boolean | **`false`** | opt-in allowlist автосвязывания по email (research §8) |
| `client-id` | string | — | — |
| `client-secret` | `${SSO_<ID>_CLIENT_SECRET}` | — | плейсхолдер env/K8s Secret; в репозитории/БД/логах отсутствует (SC-004) |
| `issuer-uri` | URI \| null | null | OIDC discovery (lazy-кэш) — альтернатива явным endpoints |
| `authorization-uri`, `token-uri`, `userinfo-uri`, `jwks-uri` | URI \| null | null | явные endpoints (обязательны, если нет `issuer-uri`) |
| `scopes` | list | `openid, email` | — |

Порядок провайдеров в `GET /auth/sso/providers` (contracts/sso-api.md §1) = порядок
объявления в YAML: байндинг `@ConfigurationProperties` выполняется в упорядоченный
`Map` (LinkedHashMap, insertion-order), а не в алфавитный; механизм зафиксирован
тестом в T007.

Общие настройки: `sso.flow-ttl` (10m), `sso.handshake-ttl` (2m),
`sso.callback-url` (по умолчанию `${app.public-base-url}/api/v1/auth/sso/callback`),
IdP-таймауты: общий deadline 5 с на callback (SC-005); connect 1 с / read 2 с на вызов.

## 5. Эфемерные структуры Redis (stateless, конституция II)

| Ключ | Значение | TTL | Семантика |
|---|---|---|---|
| `sso:flow:<state>` | JSON `{providerId, purpose: login\|link, userId?, returnTo?, nonce, codeVerifier, createdAt}` | 10m (`sso.flow-ttl`) | single-use: запись на authorize, атомарное изъятие `GETDEL` на callback; повтор/подмена/чужая вкладка → отказ до эффектов |
| `sso:handshake:<sha256(code)>` | JSON `{userId, identityId, providerId}` | 2m (`sso.handshake-ttl`) | single-use (`GETDEL`) на `POST /auth/sso/token`; сессия и токены создаются только здесь |

`returnTo` хранится во флоу-контексте для аудита/отладки; к SPA возвращается через
`sessionStorage` (клиент сохраняет до перехода на IdP), не через handshake
(contracts/sso-api.md §2).

Существующие структуры 002 (`rl:ip:*`, `rl:email:*`, `login:fail:*`,
`auth:denylist:sid:*`) — без изменений; `rl:ip:sso:*` добавляется таблицей
маршрутов (research §9). Инвариант 002 сохранён: потеря Redis никогда не ломает
долговечность PG (живые флоу просто истекают, пользователь повторяет вход).

## 6. Миграции (Flyway, SQL)

**V8__sso_enums.sql** — только ALTER TYPE (отдельно от использования значений):

```sql
ALTER TYPE auth_event_type ADD VALUE 'sso_login_success';
ALTER TYPE auth_event_type ADD VALUE 'sso_login_failed';
ALTER TYPE auth_event_type ADD VALUE 'sso_identity_linked';
ALTER TYPE auth_event_type ADD VALUE 'sso_identity_unlinked';
ALTER TYPE auth_event_type ADD VALUE 'sso_account_created';
ALTER TYPE auth_event_type ADD VALUE 'sso_flow_error';
```

**V9__sso_identities.sql** (схематично; финальный DDL — в tasks/implementation):

```sql
CREATE TABLE external_identities (
  id           uuid PRIMARY KEY,
  user_id      uuid NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  provider_id  varchar(64)  NOT NULL,
  subject      varchar(255) NOT NULL,
  provider_email         varchar(254),
  provider_email_verified boolean NOT NULL DEFAULT false,
  linked_at    timestamptz NOT NULL,
  CONSTRAINT ux_external_identities_provider_subject UNIQUE (provider_id, subject)
);
CREATE INDEX ix_external_identities_user ON external_identities(user_id);

ALTER TABLE sessions
  ADD COLUMN auth_method varchar(16),
  ADD COLUMN identity_id uuid REFERENCES external_identities(id) ON DELETE SET NULL;

ALTER TABLE users DROP CONSTRAINT ck_users_active_implies_credentials;
ALTER TABLE users ADD CONSTRAINT ck_users_active_implies_email
  CHECK (status <> 'active' OR email_confirmed_at IS NOT NULL);

CREATE FUNCTION keep_at_least_one_login_method() RETURNS trigger AS $$
BEGIN
  IF (SELECT u.password_hash FROM users u WHERE u.id = OLD.user_id) IS NULL
     AND (SELECT count(*) FROM external_identities e
          WHERE e.user_id = OLD.user_id AND e.id <> OLD.id) = 0 THEN
    RAISE EXCEPTION 'last login method cannot be removed';
  END IF;
  RETURN OLD;
END $$ LANGUAGE plpgsql;

CREATE TRIGGER trg_external_identities_keep_login_method
  BEFORE DELETE ON external_identities
  FOR EACH ROW EXECUTE FUNCTION keep_at_least_one_login_method();
```

(Точная версия `one_time_token_purpose`/`auth_event_type`-имён и стиль DDL —
по фактическим V1–V7; enum `one_time_token_purpose` не расширяется: handshake
живёт в Redis, research §2.)

## 7. Identity Resolution — решающая таблица (ядро домена)

Вход через провайдера, идентичность `(provider_id, subject)`:

| # | Ситуация | Результат |
|---|---|---|
| 1 | Идентичность найдена, пользователь `active` | Вход (существующая идентичность); обновить `provider_email*`; `sso_login_success {resolution: existing_identity}` |
| 2 | Идентичность найдена, пользователь не `active` | Отказ `rejected` (защитный; штатно невозможно) |
| 3 | Идентичности нет; email отсутствует/не verified | Отказ `email_not_verified` — без привязки и JIT (FR-004) |
| 4 | Идентичности нет; есть **active** аккаунт `lower(email)`, provider **trusted** | Автосвязывание + вход, `resolution: auto_linked` (пароль не запрашивается) |
| 5 | Идентичности нет; есть **active** аккаунт, provider **не** trusted | Отказ `email_conflict` — войти паролем, привязать вручную |
| 6 | Идентичности нет; есть **pending/awaiting_password** аккаунт `lower(email)` | Отказ `registration_incomplete` — завершить регистрацию письмом 002 (US2-4) |
| 7 | Идентичности нет; аккаунта нет; email verified | JIT: создать active-аккаунт (username из `UsernameGenerator`) + привязка + вход; `sso_account_created` + `resolution: jit` |
| 8 | Любой отказ | `sso_login_failed`/`sso_flow_error` + redirect `?sso_error=<code>`; аккаунты/привязки/сессии не создаются |

**Link-флоу** (Bearer, purpose=link): идентичность свободна → привязка к текущему
пользователю (email может отличаться от email аккаунта; email аккаунта не
меняется) → `sso_identity_linked`; идентичность принадлежит самому пользователю →
no-op success (идемпотентность, research §13); чужая → `identity_taken` без
деталей владельца (FR-006).

**Unlink**: DELETE identity; проверки: владелец = текущий пользователь (иначе
404), не последний способ входа (app-проверка пароль/привязки + триггер §6, иначе
409 `last_login_method`); сессии не отзываются; `sso_identity_unlinked`.

## 8. Инварианты и идемпотентность (сводно)

- `(provider_id, subject)` глобально уникален → идентичность ≤1 владельца (FR-006).
- `lower(email)` уникален у users (002) → дубль JIT невозможен (FR-012).
- У аккаунта без пароля ≥1 привязка — app + BEFORE DELETE-триггер (US3-4).
- Активный аккаунт ⟹ подтверждённый email (CHECK V9, импликация — состояния
  регистрации 002 не нарушаются); способ входа не влияет на
  права (FR-002: одна модель сессий).
- Все мутирующие шаги флоу — за single-use ключами Redis (`GETDEL`) и DB-уникальностями:
  повтор любого запроса (callback, token, link) не создаёт дублей аккаунтов,
  привязок, сессий (FR-012, edge «рестарт во время callback»).
- Секреты провайдеров существуют только в env/K8s Secrets; в БД, логах, контракте,
  `auth_events.details` — отсутствуют (SC-004, FR-011).
