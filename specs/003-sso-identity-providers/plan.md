# Implementation Plan: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Branch**: `003-sso-identity-providers` | **Date**: 2026-09-16 | **Spec**: `specs/003-sso-identity-providers/spec.md`

**Input**: Feature specification from `specs/003-sso-identity-providers/spec.md`

## Summary

Равноправная альтернатива парольному входу: вход через внешние OIDC-провайдеры
(authorization code flow + PKCE), JIT-создание аккаунтов и автосвязывание по
подтверждённому email (opt-in allowlist), ручное управление привязками. Сессии,
выданные через IdP, полностью переиспользуют модель фичи 002 (`SessionService.startSession`
— identity-agnostic шов, определённый в research §12 фичи 002): ES256 access + ротируемый
opaque refresh без изменений; `sessions` расширяется признаком способа входа.

Технический подход (детали и альтернативы — `research.md`):

- OIDC-протокол реализуется компонентами `spring-security-oauth2-client`
  (построение authorization request с PKCE, обмен code→token, верификация ID-токена
  через Nimbus) **без** servlet-фильтров oauth2-login — вместо них тонкие
  JSON-контроллеры, совместимые со stateless-архитектурой.
- Флоу-контекст (state, nonce, code_verifier, purpose) — в Redis, single-use (`GETDEL`),
  TTL 10 мин; браузер никогда не видит verifier/secret.
- Токены SPA передаются через одноразовый handshake-code (302 на маршрут SPA →
  `POST /auth/sso/token` → TokenPair): refresh-токен существует только в ответе
  финального POST, на сервере хранится лишь SHA-256, как в 002.
- Конфигурация провайдеров — `@ConfigurationProperties` (YAML), client secret — только
  плейсхолдеры `${SSO_*}` из K8s Secrets; в БД и репозитории секретов нет.
- Новая таблица `external_identities` (UNIQUE `(provider_id, subject)`), релаксация
  CHECK `users` для JIT-аккаунтов без пароля + DB-триггер «последнего способа входа»,
  расширение enum `auth_event_type` (+6 SSO-событий).

## Technical Context

**Language/Version**: Kotlin 2.2.21, JVM 21 (Temurin); TypeScript 5.9 strict, React 19, Vite 8 (фронтенд по стеку фичи 001)

**Primary Dependencies**: Spring Boot 3.5.16 (web, security, actuator, jdbc, data-redis, oauth2-resource-server — уже в `gradle/libs.versions.toml`) + **новый** `spring-boot-starter-oauth2-client` (компоненты OIDC: PKCE, token exchange, верификация ID-токена; Nimbus уже транзитивно); Flyway (SQL-миграции), JdbcTemplate (без JPA — по конвенции 002), Bucket4j 8.19 + Redis/Lettuce (rate limiting), Nimbus JOSE JWT (уже в classpath через oauth2-resource-server)

**Storage**: PostgreSQL 17 — расширение существующей схемы: новая `external_identities`, колонки `sessions.auth_method/identity_id`, релаксация CHECK `users` (миграции V8–V9); Redis 7 — ephemeral: `sso:flow:<state>` (контекст флоу, single-use), `sso:handshake:<sha256(code)>` (контекст выдачи токенов, single-use), существующие `rl:*`, `auth:denylist:sid:*`

**Testing**: JUnit 5 + Testcontainers (PostgreSQL 17, Redis 7 — база `AbstractIntegrationTest`) + **MockIdP** — эмулируемый OIDC-провайдер как test-scope `@RestController` (authorization endpoint, token endpoint, JWKS, управляемые сбои); фронтенд — Vitest + Testing Library; контракт — vacuum + oasdiff breaking; нагрузочный smoke — k6 (расширение `load/k6/auth.smoke.js`)

**Target Platform**: Linux/K8s, stateless-сервисы (конституция II); SPA + встраиваемый виджет — клиенты одного публичного API

**Project Type**: web-service (backend) + SPA (frontend), монорепо по решению фичи 001

**Performance Goals**: бюджеты конституции II (1M CCU); SSO-вход добавляет 2 HTTP-редиректа и ≤2 вызова IdP (внешняя задержка вне нашего бюджета); таймауты к IdP: общий deadline 5 с на callback (SC-005: понятная ошибка ≤5 с), connect 1 с / read 2 с на вызов; деградация одного провайдера не влияет на парольный вход и остальных провайдеров (изоляция по построению — нет общего состояния)

**Constraints**: stateless (никакого флоу-состояния в памяти процесса — только Redis); секреты только в K8s Secrets/env (SC-004, gitleaks); обратная совместимость публичного API: только аддитивные изменения `contracts/openapi.yaml` (0.2.0 → 0.3.0, oasdiff breaking gate); модель сессий/токенов 002 не меняется — только расширяется; нормализация email — та же `lower()`-канонизация, что в 002

**Scale/Scope**: несколько одновременно включённых провайдеров (FR-007); флоу-ключи в Redis — короткоживущие (TTL 10 мин), не создают долговременной нагрузки

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Гейт | Статус |
|---|---------|------|--------|
| I | Spec-Driven | `spec.md` утверждён (clarify-сессия 2026-09-16), план/дизайн создаются до кода | PASS |
| II | Stateless/масштаб | Флоу-контекст и handshake — в Redis (single-use, TTL), учёт лимитов — существующие Redis-бакеты Bucket4j; процесс stateless, горизонтальное масштабирование без изменений | PASS |
| III | Доставка без дублей | К фиче доставки сообщений не относится; идемпотентность SSO-эффектов обеспечена (FR-012, см. data-model §Инварианты) | PASS (N/A для путей доставки) |
| IV | API-First | Новые endpoints — только аддитивно в `contracts/openapi.yaml` (minor 0.3.0), TS-типы регенерируются `openapi-typescript` (drift-check в CI); breaking-changes нет | PASS |
| V | Безопасность | Секреты — плейсхолдеры env/K8s Secrets, в БД/логах/контрактах отсутствуют (SC-004, gitleaks); state+nonce+PKCE против CSRF/подмены (FR-009); rate limiting на всех публичных SSO-endpoints; события — в `auth_events` без секретов (FR-011) | PASS |
| VI | Test-First | Интеграционные тесты SSO обязательны: MockIdP (полный флоу, подмены/повторы, JIT/автосвязывание, привязки, сбои провайдера, лимиты) пишутся вместе с реализацией | PASS |
| VII | YAGNI | Без admin-UI провайдеров (конфиг YAML); без хранения внешних токенов; без single logout; без SAML/чистого OAuth2; готовые компоненты spring-security-oauth2-client вместо самописного OIDC | PASS |
| VIII | SOLID | Порты/адаптеры по конвенции 002: `ExternalIdentityRepository`, `SsoFlowStore`, `OidcClient` — за портами; домен (резолвинг идентичности) не зависит от HTTP-деталей провайдера | PASS |

Нарушений нет — раздел Complexity Tracking не заполняется.

### Post-design re-check (после Phase 1)

Перепроверено по итогам `research.md` / `data-model.md` / `contracts/sso-api.md`:

- **II (stateless)**: все новые состояния — short-lived Redis-ключи single-use
  (`sso:flow:*`, `sso:handshake:*`), лимиты — существующие Redis-бакеты; флоу
  завершает любой инстанс. PASS.
- **IV (API-first, обратная совместимость)**: только аддитивные endpoint'ы,
  minor-бамп 0.3.0, oasdiff breaking без marker-файла; TS-регенерация под
  drift-check. PASS.
- **V (безопасность)**: секреты — только `${SSO_*}` env-плейсхолдеры (K8s Secrets);
  state+nonce+PKCE (verifier не покидает backend) — CSRF/replay защищены;
  handshake-code одноразовый и хэширован; аудит через `auth_events` без секретов. PASS.
- **VI (test-first)**: спроектирован MockIdP + 6 IT-suite'ов до реализации
  (research §12); quickstart фиксирует команды проверки. PASS.
- **VII (YAGNI)**: без DB-таблицы провайдеров, без хранения внешних токенов, без
  single logout, admin-UI; переиспользованы компоненты oauth2-client, Bucket4j,
  AuthEventRecorder, `one_time_tokens`-паттерн. PASS.
- **VIII (SOLID)**: домен (`IdentityResolutionService`, `IdentityLinkService`) — за
  портами (`ExternalIdentityRepository`, `SsoFlowStore`, `OidcClient`); технологии
  (JDBC/Redis/HTTP IdP) — в адаптерах. PASS.

Нарушений после дизайна не выявлено.

## Project Structure

### Documentation (this feature)

```text
specs/003-sso-identity-providers/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   └── sso-api.md       # Аддитивные изменения публичного API-контракта
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

Существующий монорепо (фича 001) расширяется package-by-feature; новые элементы помечены `+`:

```text
backend/
├── src/main/kotlin/webchat/backend/
│   ├── auth/                        # фича 002 — расширяется, не переписывается
│   │   ├── api/SessionController.kt          # без изменений (контракт стабилен)
│   │   ├── domain/service/LoginService.kt    # + guard: password_hash NULL → fictitious hash → 401 (FR-013)
│   │   ├── domain/service/SessionService.kt  # + startSession(userId, authMethod, identityId?) — перегрузка, обратная совместима
│   │   ├── domain/service/TokenService.kt    # без изменений (handshake — Redis, см. research #2)
│   │   ├── ratelimit/RateLimitFilter.kt      # + SSO-маршруты в таблицу (в т.ч. GET callback/providers)
│   │   ├── repository/JdbcSessionRepository.kt # + запись auth_method/identity_id
│   │   └── security/AuthEvents.kt            # + 6 SSO-значений AuthEventType
│   ├── sso/                         # + НОВАЯ фича-пакет
│   │   ├── api/SsoController.kt              # + providers / authorize / callback / token
│   │   ├── api/SsoIdentitiesController.kt    # + GET/DELETE /users/me/identities (вынесено в sso-пакет)
│   │   ├── api/dto/SsoDtos.kt                # + запросы/ответы
│   │   ├── domain/model/ExternalIdentity.kt  # + сущность
│   │   ├── domain/port/ExternalIdentityRepository.kt # + порт
│   │   ├── domain/port/SsoFlowStore.kt       # + порт (Redis-адаптер рядом)
│   │   ├── domain/service/IdentityResolutionService.kt # + ядро: вход/JIT/автосвязывание
│   │   ├── domain/service/IdentityLinkService.kt      # + ручная привязка/отвязка + guard последнего способа
│   │   ├── domain/service/UsernameGenerator.kt        # + JIT-username из email, уникальность
│   │   ├── oidc/OidcClient.kt                # + authorization URL+PKCE, code→token, верификация ID-токена, JWKS-кэш
│   │   ├── oidc/SsoProviderRegistry.kt       # + конфигурация провайдеров → ClientRegistration
│   │   └── repository/JdbcExternalIdentityRepository.kt # + адаптер
│   ├── config/SecurityConfig.kt    # + permitAll: /api/v1/auth/sso/{providers,authorize,callback,token}
│   └── config/SsoProperties.kt     # + @ConfigurationProperties("sso"): провайдеры, TTL, trusted-флаги
├── src/main/resources/
│   ├── application.yml             # + секция sso.* (секреты — только ${SSO_*_CLIENT_SECRET})
│   └── db/migration/
│       ├── V8__sso_enums.sql       # + ALTER TYPE auth_event_type (+6 SSO-значений; one_time_token_purpose не расширяется — data-model §6)
│       └── V9__sso_identities.sql  # + external_identities, sessions.auth_method, CHECK users, триггер
└── src/test/kotlin/webchat/backend/
    ├── sso/MockIdP.kt              # + эмулируемый OIDC-провайдер (test-scope controller)
    ├── sso/SsoFlowIT.kt            # + US1/US2: полный флоу, refresh-ротация, logout
    ├── sso/SsoIdentityResolutionIT.kt # + JIT, автосвязывание trusted/untrusted, unverified, pending
    ├── sso/SsoLinkingIT.kt         # + US3: привязка/отвязка, последний способ входа
    ├── sso/SsoSecurityIT.kt        # + US5: replay state, подделка, отключённый провайдер
    ├── sso/SsoResilienceIT.kt      # + US4/US5: сбой/таймаут провайдера, изоляция
    └── sso/SsoRateLimitIT.kt       # + лимиты на публичных SSO-endpoints

frontend/
└── src/
    ├── api/sso.ts                  # + типизированные клиенты новых endpoints
    ├── auth/pages/LoginPage.tsx    # + кнопки включённых провайдеров
    ├── auth/pages/SsoCallbackPage.kt → .tsx # + обмен handshake-code, редирект в чат
    ├── settings/pages/SecurityPage.tsx     # + список привязок, привязка/отвязка
    └── api/schema.d.ts             # регенерация из контракта (committed, drift-check)

contracts/
└── openapi.yaml                    # 0.2.0 → 0.3.0, аддитивно (см. contracts/sso-api.md)

deploy/local/
└── dex/                            # + локальный IdP для ручной проверки (docker-compose профиль)
load/k6/auth.smoke.js               # + sso-сценарии (providers/authorize/token-error)
```

**Structure Decision**: монорепо `backend/` + `frontend/` + `contracts/` из плана 001; backend —
package-by-feature (`webchat.backend.sso`), порты в `domain/port`, адаптеры JDBC/Redis рядом,
внешние вызовы IdP изолированы в `sso/oidc/`. Сквозных структурных изменений нет.

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица не заполняется.
