# Contract: Публичный API аутентификации (OpenAPI)

**Feature**: 002-user-registration | **Источник истины**: `contracts/openapi.yaml`

Первые бизнес-endpoints в контракте (было `paths: {}`). Изменение — **только additive**
(minor `0.1.0 → 0.2.0`, без `BREAKING.md`); конвейер 001 (vacuum → openapi-typescript →
drift-check → oasdiff) применяется без изменений. TS-типы фронтенда — из генерации.

## 1. Публичные endpoints (полный список — US3-4)

База: `/api/v1`. Аутентификация — `Authorization: Bearer <accessToken>` (scheme `bearerAuth`).
Все ответы об ошибках — RFC 9457 `application/problem+json` (см. §3).

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| 1 | `POST /auth/register` | `{username, email}` | `202` — аккаунт создан/возобновлён, письмо поставлено в очередь | `400` валидация полей; `409` username/email занят **активным** аккаунтом (поля `username`/`email`); `429` |
| 2 | `POST /auth/register/resend` | `{email}` | `202` **всегда** единообразно (не раскрывает существование/статус, US4-4-стиль) | `400`; `429` (cooldown 60 с / лимиты) |
| 3 | `POST /auth/register/confirm` | `{token}` | `200` → `{setupToken, setupTokenType:"password_setup", expiresInSec}` | `400` «token invalid or expired» (единообразно; предложение запросить новый) ; `429` |
| 4 | `POST /auth/register/password` | `{setupToken, password, confirmPassword}` | `204` — аккаунт `active` | `400` (токен недействителен / политика пароля / несовпадение подтверждения); `429` |
| 5 | `POST /auth/login` | `{identifier, password}` — identifier = username ИЛИ email | `200` → `{accessToken, refreshToken, tokenType:"Bearer", expiresInSec, user:{id, username, email}}` | `401` единая обобщённая ошибка (US2-3); `403` «завершите регистрацию» (US1-5); `429` + `Retry-After` (лимит/блокировка подбора) |
| 6 | `POST /auth/refresh` | `{refreshToken}` | `200` → новая пара `{accessToken, refreshToken, ...}` (ротация) | `401` единая (истёк/отозван/reuse-detected — без различий); `429` + `Retry-After` (IP-лимит, FR-009) |
| 8 | `POST /auth/password-reset` | `{email}` | `202` **всегда** единообразно (US4-4) | `400`; `429` |
| 9 | `POST /auth/password-reset/confirm` | `{token, password, confirmPassword}` | `204` — пароль изменён, все сессии отозваны | `400` (токен/политика); `429` |

Ссылки в письмах указывают на маршруты SPA (не на API): `{APP_PUBLIC_BASE_URL}/confirm-registration?token=...`,
`/set-password?token=...`, `/reset-password?token=...` — SPA вызывает endpoints 3/4/9.
Открытое значение токена существует только в ссылке; сервер хранит SHA-256 ([data-model.md §2](../data-model.md)).

№7 (`POST /auth/logout`) перенесён в §2: требует Bearer и не входит в публичный перечень US3-4
(US2-5, FR-011). Нумерация 1–10 сохранена без изменений для стабильности ссылок.

## 2. Аутентифицированные endpoints

| # | Метод и путь | Успех | Ошибки |
|---|---|---|---|
| 7 | `POST /auth/logout` (тело: `{refreshToken}`) | `204` — сессия отозвана | `401` |
| 10 | `GET /users/me` | `200` → `{id, username, email, status, createdAt}` | `401` |

`POST /auth/logout` — защищённый auth-endpoint: отзыв сессии выполняется только при
действительном access-токене (US2-5, FR-011); в публичный перечень US3-4 не входит.
`GET /users/me` — минимальный защищённый ресурс для проверки границы аутентификации (US3,
SC-002); все последующие endpoints чата (следующие фичи) — только за Bearer.

**Инвариант US3-4**: публичны ТОЛЬКО endpoints №1–6, №8, №9 — 8 путей (+ технические
health/metrics вне контракта —
[technical-endpoints.md фичи 001](../../001-project-foundation/contracts/technical-endpoints.md));
№7 `logout` и №10 `/users/me` — только с Bearer; `anyRequest().authenticated()` — всё остальное.

## 3. Модель ошибок

```json
// 401 — единообразно для: нет токена / истёк / отозван / недействителен (US3-1/2)
{ "title": "Unauthorized", "status": 401, "detail": "Not authenticated" }

// 401 login (US2-3) — единообразно для: неверный пароль / несуществующий идентификатор
{ "title": "Unauthorized", "status": 401, "detail": "Invalid credentials" }

// 429 — с заголовком Retry-After (сек); лимит источника или блокировка подбора
{ "title": "Too Many Requests", "status": 429, "detail": "..." }

// 400 — валидация (пример)
{ "title": "Bad Request", "status": 400, "errors": { "email": ["invalid_format"] } }
```

Тайминговая защита от перечисления: несуществующий идентификатор → проверка против фиктивного
Argon2-хеша, тот же код-путь и сообщение ([research.md §7](../research.md)).

## 4. Схемы (components.schemas)

| Схема | Поля |
|---|---|
| `RegisterRequest` | `username: string(3..32, pattern)`, `email: string(email, ≤254)` |
| `LoginRequest` | `identifier: string`, `password: string(8..128)` |
| `TokenPair` | `accessToken: string(jwt)`, `refreshToken: string(base64url, ~43)`, `tokenType: "Bearer"`, `expiresInSec: int` |
| `PublicUser` | `id: uuid`, `username`, `email`, `status: enum`, `createdAt` |
| `Problem` | RFC 9457 + опционально `errors: map<string, string[]>` |
| `PasswordPolicy` (описание) | 8–128 символов, не пустой/пробельный, вне топ-листа тривиальных, ≠ username/email |

Токены в payload — только эти; никакие endpoint не возвращают хеши, salt, внутренние id токенов.

## 5. Обратная совместимость

- Добавление paths/schemas — minor (`0.2.0`); breaking-детекция oasdiff в CI должна пройти без
  маркера `BREAKING.md`.
- Типы фронтенда: `frontend/src/api/schema.d.ts` регенерируется; drift-check (regen +
  `git diff --exit-code`) обязателен зелёным.
- Серверная реализация (Spring) сверяется с контрактом; расхождение контракт↔код — баг
  (проверяется интеграционными тестами по схемам).
