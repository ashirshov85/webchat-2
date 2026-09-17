# API Contract: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Feature**: `003-sso-identity-providers` | **Date**: 2026-09-16
Изменение `contracts/openapi.yaml`: **0.2.0 → 0.3.0, только аддитивно** (oasdiff
breaking gate проходит без `contracts/BREAKING.md`). Существующие endpoint'ы 002
(№1–10) не меняются. Конвенции наследуются: base `/api/v1`, RFC 9457
`application/problem+json` (`Problem` со схемой и `errors: map<string,string[]>`
для 400/409), 429 всегда с `Retry-After` (integer ≥1), Bearer `accessToken`
(JWT ES256) для защищённых маршрутов, `TokenPair{accessToken, refreshToken,
tokenType:"Bearer", expiresInSec}`.

Новый тег: `sso`. Публичный перечень endpoints без аутентификации (FR-008)
= 8 маршрутов 002 + `GET /auth/sso/providers`, `POST /auth/sso/authorize`,
`GET /auth/sso/callback`, `POST /auth/sso/token`.

Значения rate limit у endpoint'ов ниже — справочные; нормативный источник —
`research.md` §9 (при изменении значения синхронизируются во всех упоминаниях).

---

## 1. `GET /auth/sso/providers` — перечень включённых провайдеров

Public (rate limit: IP 30/1m).

**200** `SsoProvidersResponse`:

```yaml
providers:
  type: array
  items:
    type: object
    required: [id, displayName]
    properties:
      id:          { type: string, pattern: "^[a-z0-9][a-z0-9-]{0,63}$" }
      displayName: { type: string }
  example: [{id: google, displayName: Google}, {id: contoso, displayName: Contoso SSO}]
```

Порядок — порядок конфигурации. Выключенные (`enabled: false`) и неизвестные
провайдеры не видны. Пустой список валиден (SSO не настроен).

**429** — стандартный problem + `Retry-After`.

## 2. `POST /auth/sso/authorize` — начало флоу входа

Public (rate limit: IP 10/1m). Создаёт флоу-контекст (state/PKCE/nonce) и
возвращает URL для перенаправления браузера на IdP.

Request `SsoAuthorizeRequest`:

```yaml
required: [providerId]
properties:
  providerId: { type: string }        # код из GET /auth/sso/providers
  returnTo:   { type: string, maxLength: 512 }  # опциональный путь возврата внутри SPA ("/chat"); только относительный путь
```

**200** `SsoAuthorizeResponse`: `{ authorizationUrl: string(format uri) }` —
клиент обязан выполнить браузерный переход (`window.location.assign`).
`authorizationUrl` содержит `state`, `nonce`, PKCE-`code_challenge` (S256),
`redirect_uri = {public-base-url}/api/v1/auth/sso/callback`; verifier клиенту не
виден (server-side confidential client).

`returnTo` сохраняется SPA в `sessionStorage` перед переходом на IdP и
применяется после успешного обмена (§4) — серверных изменений схемы не требуется;
сервер валидирует только относительный формат пути на `authorize`.

Errors: **404** problem `Provider not found` (неизвестен или выключен — единый
ответ, раскрытия перечня нет, он и так публичен); **400** `errors: {returnTo:
["invalid_format"]}` для не-относительного пути или длины > 512 символов; **429**.

## 3. `GET /auth/sso/callback?state&code|error` — завершение флоу (браузерный)

Public (rate limit: IP 30/1m). Не JSON-API: вызывается браузером редиректом IdP;
документируется в OpenAPI с семантикой 302.

Поведение: изъять `sso:flow:<state>` (`GETDEL`; отсутствующий/повторный → отказ),
обменять `code` у провайдера (общий deadline 5 с на callback — сумма вызовов провайдера), верифицировать ID-токен (подпись
JWKS, `iss`, `aud`, `exp`, `nonce`), применить резолвинг идентичности
(data-model §7):

- **Успешный вход**: `302 Location: {public-base-url}/sso/callback?code=<handshake>&state=<state>`,
  где `handshake` — одноразовый код (43 симв., `^[A-Za-z0-9_-]{43}$`, TTL 2 мин)
  для обмена в §4.
- **Успешная привязка** (link-флоу): `302 {public-base-url}/settings/security?linked=<providerId>`.
- **Отказ/ошибка**: `302 {public-base-url}/sso/callback?sso_error=<code>`
  (link-флоу — на `/settings/security?sso_error=<code>`).

Публичные коды ошибок `sso_error` (без секретов и деталей владельцев):

| Код | Ситуация (US/FR) |
|---|---|
| `invalid_state` | state отсутствует/истёк/повторён (US5-2) |
| `provider_disabled` | провайдер выключен между authorize и callback |
| `provider_error` | сбой обмена/верификации (вкл. недоступность, SC-005) |
| `email_not_verified` | провайдер не подтвердил email (US2-3) |
| `email_conflict` | активный аккаунт с этим email, провайдер вне allowlist (US2-3) |
| `registration_incomplete` | pending/awaiting_password-аккаунт с этим email (US2-4) |
| `identity_taken` | идентичность уже принадлежит другому аккаунту (US3-2) |
| `rejected` | прочие отклонения флоу |

Ошибки провайдера (`error`-параметр ответа IdP) маппятся в `provider_error`.
Все исходы логируются в `auth_events` (FR-011). Тело ответа — пустое (браузер
следует редиректу); контракт фиксирует только статус 302 и параметры Location.

## 4. `POST /auth/sso/token` — обмен handshake-кода на сессию

Public (rate limit: IP 30/1m). Вызывается SPA после редиректа §3.

Request: `{ code: { type: string, pattern: "^[A-Za-z0-9_-]{43}$" } }` (required).

**200** — `LoginResponse` (идентичен §№5 контракта 002): `allOf[TokenPair,
{user {id, username, email}}]`. Сессия создаётся здесь: тот же формат токенов,
та же ротация refresh, тот же logout (US1). `429` при превышении лимита.

Errors: **400** problem `Token is invalid or expired; request a new login`
(`errors: {code: ["invalid_code"]}`) — единый ответ на неизвестный/использованный/
истёкший код (без различения причин).

## 5. `POST /auth/sso/link/authorize` — начало флоу привязки

**Bearer required** (rate limit: IP 10/1m). Аналогично §2, но purpose=link:
флоу-контекст связывается с текущим пользователем; результат callback — §3
(link-ветка). Request `SsoLinkAuthorizeRequest`: `{ providerId: string }` (required).

**200** `SsoAuthorizeResponse`; **401** standard; **404** Provider not found; **429**.

## 6. `GET /users/me/identities` — список привязок

**Bearer required**.

**200** `IdentitiesResponse`:

```yaml
identities:
  type: array
  items:
    type: object
    required: [id, providerId, email, linkedAt]
    properties:
      id:            { type: string, format: uuid }
      providerId:    { type: string }
      providerDisplayName: { type: string }   # из конфигурации; null, если провайдер удалён из конфига
      email:         { type: string, nullable: true }  # email от провайдера (последний известный)
      linkedAt:      { type: string, format: date-time }
```

Порядок — по `linkedAt`. **401** standard.

## 7. `DELETE /users/me/identities/{identityId}` — отвязка

**Bearer required**. Path: `identityId` (uuid).

- **204** — привязка удалена; существующие сессии не отзываются (clarify 2026-09-16);
  блокируются только новые входы через этого провайдера.
- **404** — идентичность не найдена или принадлежит другому пользователю
  (без раскрытия деталей — единый ответ).
- **409** problem `errors: {identity: ["last_login_method"]}` — отвязка последнего
  способа входа запрещена (US3-4); detail предлагает сначала задать пароль.
- **401** standard.

---

## Схемы/коды, переиспользуемые из 0.2.0

`TokenPair`, `LoginResponse`, `PublicUser`, `Problem`, Bearer `securityScheme`,
паттерны 43-символьных токенов. Новые схемы: `SsoProvider`, `SsoProvidersResponse`,
`SsoAuthorizeRequest`, `SsoLinkAuthorizeRequest`, `SsoAuthorizeResponse`,
`SsoTokenRequest`, `Identity`, `IdentitiesResponse`.

## Клиенты и генерация

- `frontend/src/api/schema.d.ts` регенерируется `pnpm --dir frontend generate:api`
  (drift-check в CI: `git diff --exit-code -- frontend/src/api/schema.d.ts`).
- SPA-маршруты: `/login` (кнопки провайдеров из §1), `/sso/callback` (обмен §4),
  `/settings/security` (§5–7).
- Встраиваемый виджет (фича 013) использует те же endpoints — отдельных
  механизмов не создаётся (Assumption spec).

## Проверки контракта (CI)

- `vacuum lint -e contracts/openapi.yaml`
- `oasdiff breaking origin/main:contracts/openapi.yaml contracts/openapi.yaml`
  → без breaking-изменений (аддитивные пути/схемы, minor-бамп версии).
