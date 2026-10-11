# API Contract: Полировка auth-флоу и управление паролем

**Feature**: `008b-auth-ux-polish` | **Date**: 2026-10-11 | Решения: [research.md](../research.md)

Изменение `contracts/openapi.yaml`: **0.9.0 → 0.10.0, только аддитивно** (oasdiff breaking
gate проходит без `contracts/BREAKING.md`). Существующие endpoints не меняются: login
(№2), refresh, logout, password-reset/request/confirm (002), SSO-флоу (003), контакты
(№20–22), `GET /users/me` расширяется аддитивно. Конвенции наследуются: base `/api/v1`,
RFC 9457 problem+json (`Problem` + `errors: map<string,string[]>` для 400), 429 всегда с
`Retry-After` (integer ≥1), Bearer `accessToken` (JWT ES256) для защищённых маршрутов,
`TokenPair{accessToken, refreshToken, tokenType:"Bearer", expiresInSec}`.

Фронтендовые механики (редирект после входа, ссылка сброса, форма смены) — клиентские,
контракт не меняют; их поведение описано в §4 как UI-контракт.

---

## 1. `POST /auth/password-change` — смена пароля из профиля

`operationId: changePassword`, `tags: [auth]`, **Bearer** (`security:
[{bearerAuth: []}]`, прецедент logout).

Rate limit: **IP 10/1m** (фильтр, pre-auth) + **user 5/1m** (per-user, после
аутентификации). Значения справочные; нормативный источник — research.md §6.

Request `PasswordChangeRequest`:

```yaml
required: [currentPassword, newPassword]
properties:
  currentPassword: { type: string, minLength: 1, maxLength: 128, format: password }
  newPassword:     { type: string, minLength: 8, maxLength: 128, format: password }
```

- `confirmPassword` в API **нет** — совпадение с подтверждением проверяется клиентом до
  отправки (FR-005); серверу поле не нужно (research.md §5-alternatives d).

**200** `TokenPair` — паритет с `POST /auth/refresh`: новая пара токенов текущей сессии
(прежний refresh отозван, выдан новый; access тоже новый). Клиент обязан применить пару
своим стандартным механизмом (`setTokenPair`) — пользователь остаётся вошедшим (FR-008).
Семантика: все прочие сессии и refresh-токены пользователя отозваны (см. data-model §3.3).

**400** `Problem` (+ `errors`):

| Код поля | Причина |
|---|---|
| `currentPassword: ["invalid"]` | неверный текущий пароль или аккаунт без пароля (JIT) — единый ответ |
| `newPassword: ["too_short" \| "too_long" \| "blank" \| "too_common" \| "same_as_username" \| "same_as_email"]` | нарушение политики 002 (коды и семантика — как в password-reset/confirm) |

**401** `Problem` — не аутентифицирован (только граница Bearer; неверный текущий пароль
не использует 401 — инвариант SecurityConfig, research.md §5).

**429** `Problem` + `Retry-After`, `errors: {flood_limit}` — превышен per-IP или per-user
лимит.

Аудит: `password_change_completed` / `password_change_failed` + `session_revoked`×N — без
секретов (FR-010).

## 2. `GET /users/me` — аддитивное поле `hasPassword`

Ответ 200 меняется с `$ref PublicUser` на inline allOf-расширение (прецедент —
login-response):

```yaml
allOf:
  - $ref: '#/components/schemas/PublicUser'
  - type: object
    additionalProperties: false
    required: [hasPassword]
    properties:
      hasPassword: { type: boolean }
```

- `hasPassword = password_hash IS NULL ? false : true`. Для JIT-аккаунтов 003 — `false`.
- Поле появляется **только** в `/users/me`: `PublicUser` шарится peer/search/member
  поверхностями — раскрытие признака чужим пользователям запрещено (research.md §4).
- Существующие поля/клиенты не затронуты (additive, required-поле добавляется в inline
  расширении, а не в общем `$ref`).

## 3. Типы

Новая схема `PasswordChangeRequest` (после `PasswordResetConfirmRequest`); `TokenPair`,
`Problem`, `bearerAuth` — без изменений.

## 4. UI-контракт (без изменений OpenAPI)

### 4.1 Редирект после парольного входа (FR-001/FR-002)

- Успешный `POST /auth/login` → `window.location.assign(loginRedirectTarget(returnTo))`:
  safe `returnTo` — относительный путь, удовлетворяющий всем критериям FR-002 (а)–(д):
  `startsWith('/') && !startsWith('//')`, без обратных слешей (браузеры нормализуют `\`
  в `/`, поэтому `/\evil.com` эквивалентен `//evil.com`), без traversal-сегментов `..`
  (pathname до `?`/`#`), без управляющих символов, не маршрут auth-раздела (`AUTH_PATHS`:
  `/login`, `/register`, `/confirm-registration`, `/set-password`, `/forgot-password`,
  `/reset-password`, `/sso/callback`) → сам путь; отсутствующий/пустой/
  небезопасный → `/chat`. Проверка — общий модуль `auth/lib/returnTo.ts`, разделяемый
  с `SsoCallbackPage` (паритет 003 с усилением для обоих флоу — задачи T003/T005,
  US1 AC1–3).
- Ошибочный логин — без перехода, прежнее поведение 002 (US1 AC4).
- SSO-флоу 003 не меняется (US1 AC5).

### 4.2 Ссылка сброса (FR-003)

Footer страницы входа: `Забыли пароль?` → `/forgot-password` (существующий маршрут 002;
API и семантика восстановления не меняются — US3).

### 4.3 Форма смены пароля в «Мой профиль» (FR-004/FR-005)

- Рендерится только при `me.hasPassword === true`; иначе подсказка «задать пароль через
  восстановление по email» со ссылкой на `/forgot-password` (JIT-аккаунты 003).
- Поля: текущий (`autoComplete="current-password"`), новый и подтверждение
  (`autoComplete="new-password"`).
- До отправки клиент проверяет: совпадение new/confirm; длину 8–128; не пустой/не из
  пробелов; ≠ username/email (case-insensitive, trim — зеркало сервера). Топ-лист слабых
  паролей — серверная проверка: 400 `too_common` рендерится понятным сообщением
  (интерпретация FR-005, research.md §8).
- Отправка: `changePassword({currentPassword, newPassword})`; успех → `setTokenPair`
  (новая пара применена, пользователь остаётся вошедшим) + toast + очистка полей; 400/429 → inline
  ошибка с `problemMessage`, без перехода.

---

## 5. Регресс-обязательства (SC-005)

| Поверхность | Обязательство |
|---|---|
| `POST /auth/login` | контракт и семантика без изменений |
| password-reset 002 | без изменений (включая отзыв всех сессий при reset-confirm) |
| SSO 003 | authorize/callback/token — без изменений; `returnTo`-флоу — только усиление валидации путей (§4.1: `\`, `..`, управляющие символы, auth-маршруты), API-контракт не меняется |
| Контакты 004 | `GET /contacts` — форма ответа без изменений (само-исцеление внутри, worst-case +1 контакт ровно один раз) |
| `TokenPair` | schema без изменений |
| Breaking | oasdiff `--fail-on ERR` против origin/main — зелёный |
