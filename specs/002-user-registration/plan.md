# Implementation Plan: Регистрация с email-подтверждением, вход по паролю и управление сессиями

**Branch**: `002-user-registration` | **Date**: 2026-09-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-user-registration/spec.md`

## Summary

Первая бизнес-фича и первое персистентное состояние проекта. Полный цикл идентичности:
регистрация (username + email) → письмо-подтверждение → задание пароля → вход по
username/email → пара токенов (короткоживущий access JWT ES256 5 мин + opaque refresh 256-bit
с ротацией при каждом использовании и детекцией повторного применения) → logout/смена пароля с
мгновенным отзывом через Redis denylist. Весь API чата закрыт Bearer-аутентификацией, публичны
только 8 зафиксированных auth-endpoints в OpenAPI-контракте (№1–6, №8–9; minor 0.2.0, additive);
logout (№7) и `/users/me` (№10) — аутентифицированные.
Вводится инфраструктура: PostgreSQL 17 (Flyway SQL-миграции, Spring JDBC) для User/токенов/
сессий/outbox, Redis 7 (Bucket4j) для rate limiting всех публичных endpoints — ключи по
источнику и аккаунтам/email, токен-consuming маршруты и refresh по IP (FR-009) — + счётчиков
подбора, Mailpit + заменяемый SMTP-шлюз с transactional outbox в PG для транзакционных писем.
Пароли — Argon2id (DelegatingPasswordEncoder). Spring Security: resource-server с кастомным
JwtDecoder, stateless, единый 401 problem+json. Frontend: страницы register/confirm/
set-password/login/reset + типизированный клиент с интерцептором refresh. Детальные решения с
обоснованием и расчётом нагрузки — [research.md](./research.md).

## Technical Context

*Исходные неизвестные (DB, email-сервис, rate limiting, алгоритм хеширования, формат токенов)
разрешены в Phase 0 — [research.md](./research.md).*

**Language/Version**: без изменений относительно 001: Kotlin 2.2.x + JDK 21 (backend), TypeScript 5.x strict (frontend), Node 24 + pnpm.

**Primary Dependencies** (новые; версии — из BOM Spring Boot, кроме отмеченных):
- Backend: `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server` (nimbus-jose-jwt), `spring-boot-starter-jdbc`, `org.postgresql:postgresql`, `flyway-core` + `flyway-database-postgresql`, `spring-boot-starter-data-redis` (Lettuce), `spring-boot-starter-mail`, `bucket4j_jdk17-core` + `bucket4j_jdk17-redis-lettuce` (8.x, своя версия), `net.ttddyy.observation:datasource-micrometer-spring-boot` (JDBC-трейсинг).
- Test: `spring-boot-testcontainers`, `org.testcontainers:postgresql` — BOM; Redis 7 — generic `GenericContainer` (`org.testcontainers:testcontainers`, уже в BOM) без стороннего Redis-модуля (VII).
- Frontend: без новых runtime-зависимостей (типы — из codegen).

**Storage**: **PostgreSQL 17** (долговечное: users, one_time_tokens, sessions, refresh_tokens, email_outbox, auth_events; уникальные lower()-индексы username/email) + **Redis 7** (эфемерное: бакеты лимитов, счётчики подбора, denylist sid). Dev-манифесты Kustomize + локальный docker-compose (Mailpit). Пароли/токены — только в виде хешей.

**Testing**: Backend — JUnit 5 + Testcontainers (PG+Redis, `@ServiceConnection`, static-контейнер в базовом классе): контракты endpoints, ротация/reuse, отзыв, 401-единообразие, 429/Retry-After, прогрессирующая задержка, идемпотентность ссылок, outbox на fake-шлюзе, отсутствие секретов в логах. Frontend — Vitest + Testing Library (потоки страниц, refresh-интерцептор). Контракт — конвейер 001 без изменений (vacuum/drift/oasdiff). Нагрузочный smoke — k6 (`load/k6/auth.smoke.js`, профиль research §13): 429 вместо 5xx при превышении лимитов (SC-008).

**Target Platform**: без изменений: Linux-контейнеры в K8s dev (stateless-поды backend/frontend; PG/Redis/Mailpit — dev-only Deployment+PVC).

**Project Type**: web-service (монорепозиторий, структура 001 сохраняется).

**Performance Goals** (спека): письмо ≤ 2 мин в 95% (SC-001); p95 ≤ 500 мс серверной задержки успешного login (без throttle-задержек), прозрачный refresh (SC-003); 10+ неудачных входов в один аккаунт в пределах окна счётчика 15 мин → эффективное ограничение подбора (SC-004); доступность публичных endpoints при отказе email-сервиса, 100% сбоев доставки в observability (SC-007); контролируемая деградация под профилем нагрузки (SC-008). Расчёт соответствия бюджетам конституции (280 rps логинов, ~100k Redis GET/s на denylist при 100k msg/s) — [research.md §13](./research.md).

**Constraints**: stateless-поды (состояние только в PG/Redis); пароли только Argon2id-хеш, нигде не логируются (FR-004, SC-005); ответы не раскрывают существование аккаунтов (FR-005, US2-3); публичные endpoints — rate limiting во внешнем хранилище (FR-009); замена email-шлюза без правки ядра (FR-008); SSO-фича 003 не должна требовать переделки сессий (Assumptions); секреты — только env/K8s Secrets.

**Scale/Scope**: ~8 публичных + 2 защищённых endpoint (№7 `logout`, №10 `/users/me`); 6 таблиц PG + 4 семейства Redis-ключей; 6 Flyway-миграций (по одной на таблицу, data-model.md §1–§6); страницы SPA: register, confirm-registration, set-password, login, forgot/reset-password; 3 dev-манифеста + 1 compose-файл + 1 k6-сценарий (нагрузочный smoke).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Статус | Комментарий |
|---|---------|--------|-------------|
| I | Spec-Driven (NON-NEGOTIABLE) | ✅ PASS | Спека утверждена (checklist пройден); план — этот документ; `tasks.md` — следующий шаг (`/speckit.tasks`). Код только по задачам. |
| II | Масштабируемость / stateless (NON-NEGOTIABLE) | ✅ PASS | Поды stateless: все состояния (сессии, лимиты, denylist) — во внешних PG/Redis. Расчёт под бюджеты (1M users / 100k msg/s): верификация JWT — CPU in-memory, записи в PG — сотни rps, Redis — O(1) на запрос ([research.md §13](./research.md)); PgBouncer/кластеризация — отложены с критериями возврата (YAGNI). |
| III | Доставка без дублей (NON-NEGOTIABLE) | ✅ N/A | Пути доставки сообщений чата в этой фиче нет. Идемпотентность по месту применения: поглощение one-time-ссылок условным UPDATE (FR-012), outbox-поллер `FOR UPDATE SKIP LOCKED` — без дублей писем. |
| IV | API-First | ✅ PASS | Все endpoints — сначала в `contracts/openapi.yaml` (minor 0.2.0, additive, без BREAKING.md); TS-типы — codegen; drift/breaking-детекция CI 001 действует. |
| V | Безопасность и приватность | ✅ PASS | Ядро фичи: Argon2id с солью (FR-004), ES256 + ротация refresh + denylist (FR-006), единые ошибки без перечисления аккаунтов (FR-005), rate limiting во внешнем хранилище (FR-009), секреты в env/K8s Secrets, логи без паролей/токенов/сырого PII (ip_hash, recipient_hash) (FR-013, SC-005). Вне scope этой фичи: E2EE (фичи обмена сообщениями) и SSO/OIDC — отдельная фича 003 (ROADMAP); модель сессий/токенов подготовлена под добавление SSO без переделки (Assumptions, [research.md §12](./research.md)). |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | Тесты обязательны в каждой задаче (DoD): Testcontainers IT по всем acceptance-сценариям, контрактные проверки, brute-force-тесты ([research.md §9](./research.md)). Нагрузочный smoke-тест (k6) обязателен — SC-008: профиль [research.md §13](./research.md), критерий — контролируемая деградация (429 + `Retry-After`, без 5xx); расчёт несущей способности — там же. |
| VII | Простота (YAGNI) | ✅ PASS | Минимально: JdbcTemplate вместо JPA/jOOQ; Bucket4j+Redis без самописного Lua; один SMTP-адаптер (HTTP-API — позже); outbox в PG вместо брокера; Deployment+PVC вместо операторов; без `user_identities` до фичи 003. Все middleware-выборы обоснованы в research.md. |
| VIII | SOLID на уровне кода | ✅ PASS | SRP: package-by-feature (`auth/…`); DIP: домен зависит от порта `EmailGateway` (адаптер SMTP снаружи), порты репозиториев; OCP: новые email-адаптеры/провайдеры — новыми классами (точка расширения IV); без спекулятивных абстракций (VII). |

**Нарушений нет** — Complexity Tracking не требуется.

**Re-check после Phase 1 (дизайн)**: data-model.md не вводит in-process состояния (все сущности — PG/Redis, эфемерные ключи TTL-ированы); contracts/api-contract.md — additive к публичному контракту, полный закрытый список публичных endpoints зафиксирован (US3-4); quickstart.md проверяем без ручных правок артефактов. Выводы таблицы не меняются — **все гейты PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/002-user-registration/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/
│   └── api-contract.md  # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/src/main/kotlin/webchat/backend/
├── BackendApplication.kt                    # без изменений
├── RequestLoggingFilter.kt                  # без изменений
├── config/
│   └── SecurityConfig.kt                    # SecurityFilterChain (stateless, публичные пути), ProblemDetails 401/429
├── auth/
│   ├── api/                                 # REST-контроллеры + DTO (по контрактy openapi.yaml)
│   │   ├── RegisterController.kt
│   │   ├── SessionController.kt             # login / refresh / logout
│   │   └── PasswordResetController.kt
│   ├── domain/                              # домен без технологий (DIP)
│   │   ├── model/                           # User, OneTimeToken(purpose), Session, RefreshToken, статусы
│   │   ├── port/                            # UserRepository, TokenRepository, SessionRepository, Clock
│   │   └── service/                         # RegistrationService, LoginService, SessionService (ротация/reuse),
│   │                                        # PasswordResetService, TokenService
│   ├── repository/                          # JDBC-адаптеры портов (JdbcTemplate): JdbcUserRepository,
│   │                                        # JdbcOneTimeTokenRepository, JdbcSessionRepository,
│   │                                        # JdbcRefreshTokenRepository
│   ├── security/
│   │   ├── JwtService.kt                    # ES256 подпись/проверка, kid, claims (sub,sid,jti,typ)
│   │   ├── AuthJwtDecoder.kt                # JwtDecoder по kid + denylist sid (Redis)
│   │   └── AuthEvents.kt                    # журнал событий безопасности (FR-013)
│   └── ratelimit/
│       ├── RateLimitFilter.kt               # Bucket4j (Lettuce), ключи ip/email, 429 + Retry-After
│       ├── ClientIpResolver.kt              # XFF leftmost, IPv6 /64
│       └── LoginThrottle.kt                 # счётчик неудач, прогрессивная задержка, блокировка
├── email/
│   ├── EmailGateway.kt                      # ПОРТ (домен)
│   ├── SmtpEmailGateway.kt                  # адаптер spring-boot-starter-mail
│   ├── JdbcEmailOutboxRepository.kt         # вставка pending-строк (TX), cooldown resend 60 с
│   ├── OutboxPoller.kt                      # @Scheduled, FOR UPDATE SKIP LOCKED, backoff
│   └── templates/                           # тексты писем + ссылки (SPA-маршруты)
├── users/api/UsersController.kt             # GET /api/v1/users/me
backend/src/main/resources/
├── db/migration/                            # Flyway SQL: users, one_time_tokens, sessions,
│                                            # refresh_tokens, email_outbox, auth_events + индексы
└── application.yml                          # + datasource, redis, mail, auth.* (TTL/лимиты), forward-headers
backend/src/test/kotlin/webchat/backend/     # unit + Testcontainers IT (базовый класс с @ServiceConnection)

frontend/src/
├── api/
│   ├── schema.d.ts                          # GENERATED (regen из нового контракта)
│   ├── client.ts                            # Bearer-интерцептор, авто-refresh (single-flight), единый 401
│   └── auth.ts                              # типизированные вызовы endpoints 1–10
├── auth/
│   ├── pages/                               # RegisterPage, ConfirmRegistrationPage, SetPasswordPage,
│   │                                        # LoginPage, ForgotPasswordPage, ResetPasswordPage
│   └── session.ts                           # хранение токенов (access — память, refresh — localStorage; обоснование — research §4)
└── App.tsx                                  # маршруты

contracts/openapi.yaml                       # + endpoints 1–10, components.schemas, bearerAuth (0.2.0)

deploy/
├── k8s/overlays/dev/
│   ├── postgres.yaml                        # Deployment(Recreate)+PVC+Secret (dev-only)
│   ├── redis.yaml                           # Deployment(Recreate)+PVC, volatile-ttl
│   └── mailpit.yaml                         # Deployment+Service
└── local/docker-compose.yml                 # postgres:17 + redis:7 + mailpit (локальная разработка)

load/
└── k6/
    └── auth.smoke.js                        # нагрузочный smoke SC-008: профиль research §13
                                             # (docker run grafana/k6 — quickstart §4.7)

.github/workflows/ci.yml                     # без изменений (IT — Testcontainers: PG/Redis поднимаются через Docker
                                              # раннера ubuntu-latest, отдельные CI-сервисы не нужны; k6 smoke — локально, вне CI)
```

**Structure Decision**: структура 001 сохранена (плоский монорепозиторий `backend/` +
`frontend/` + `contracts/`, без apps/packages — YAGNI). Backend — package-by-feature поверх
существующего пакета `webchat.backend`: новая фича = пакеты `auth/`, `email/`, `users/`,
`config/`; dependencies фичи 001 не трогаются. Инфраструктурные stateful-сервисы — только в
dev-overlay и local-compose (prod-managed — вне scope). JWT-ключи и креды PG/Redis/SMTP —
K8s Secret / env, в репо их нет (gitleaks).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица пуста.
