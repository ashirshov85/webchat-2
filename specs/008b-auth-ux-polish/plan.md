# Implementation Plan: Полировка auth-флоу и управление паролем

**Branch**: `008b-auth-ux-polish` | **Date**: 2026-10-11 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/008b-auth-ux-polish/spec.md`

## Summary

Четыре улучшения auth-пути на готовой инфраструктуре фич 002–008: (1) редирект на чат после
парольного входа с поддержкой `returnTo` (клиентская механика по образцу 003, без изменения
login-endpoint); (2) ссылка «Забыли пароль?» на странице входа в существующий флоу 002; (3)
аддитивный endpoint `POST /api/v1/auth/password-change` (проверка текущего пароля, политика 002,
Argon2id, rate limiting, аудит, отзыв прочих сессий + ротация refresh-токена текущей сессии)
и форма смены пароля в «Мой профиль» (скрыта для JIT-аккаунтов без пароля — требуется
аддитивное поле `hasPassword` в `GET /users/me`); (4) seed системного аккаунта admin при
развёртывании (идемпотентный) и асинхронное автодобавление admin в контакты новых
пользователей с идемпотентным self-healing fallback при первом запросе контактов.

## Technical Context

**Language/Version**: Kotlin 2.2 / JDK 21 (backend); TypeScript 5.9 strict / React 19 (frontend)

**Primary Dependencies**: Spring Boot 3.5, Spring Security, Flyway, Argon2id
(`DelegatingPasswordEncoder`), JWT ES256, Bucket4j + Lettuce (Redis); Vite 8, Vitest,
Testing Library, Playwright, openapi-typescript

**Storage**: PostgreSQL 17 (`users`, `sessions`, `refresh_tokens`, `auth_events`,
`user_contacts`, `email_outbox`); Redis 7 (rate-limit buckets, sid denylist, SSO flow
context, realtime pub/sub)

**Testing**: JUnit 5 + Testcontainers (PG 17 + Redis 7); Vitest + Testing Library +
Playwright visual; контрактный конвейер (vacuum lint, regen drift-check, `oasdiff breaking`)

**Target Platform**: Linux/Kubernetes (deploy/k8s), локально docker-compose; standalone SPA

**Project Type**: web-service + SPA (существующие `backend/`, `frontend/`, `contracts/`)

**Performance Goals**: auth-операции низкой частоты; rate limiting смены пароля в Redis
(консистентно при масштабировании); прочие бюджеты — по конституции II без новых
высоконагруженных путей

**Constraints**: публичный API-контракт — только аддитивные изменения (minor bump
0.9.0 → 0.10.0); секреты только в env/secret manager; stateless-сервисы

**Scale/Scope**: до 1 000 000 одновременных пользователей (наследование конституции II);
новых таблиц нет — одна колонка + расширение enum аудита

### Разрешённые неопределённости (Phase 0 — см. research.md)

1. **Seed admin**: `ApplicationRunner` + `INSERT … ON CONFLICT DO NOTHING`; email/пароль
   из env (`ADMIN_SEED_EMAIL`/`ADMIN_SEED_PASSWORD`); без пароля — JIT-форма + задание
   пароля флоу 002; ротация — только при создании строки, далее штатные флоу 002/008b
   (research.md §1).
2. **Событие регистрации**: Spring `ApplicationEvent` + `@TransactionalEventListener(AFTER_COMMIT)`
   + `@Async` из двух точек создания активных аккаунтов; дискриминатор —
   `users.starter_contact_added` (V17 помечает существующих без добавления контактов);
   self-healing `ensureOnboarded` в `GET /contacts`, best-effort (research.md §2–3).
3. **hasPassword**: только `GET /users/me` (inline allOf над `PublicUser`; не в
   `LoginResponse`, не в `PublicUser` — утечка JIT-признака чужим поверхностям исключена)
   (research.md §4).
4. **Rate limiting**: per-IP 10/1m в `RateLimitFilter` + per-user 5/1m через
   `UserRateLimiter` в контроллере, оба Redis/Bucket4j, fails-open наследуется
   (research.md §6).
5. **Ответ смены**: `TokenPair` (паритет `POST /auth/refresh`); неверный текущий пароль —
   400 `currentPassword: invalid` (401 зарезервирован под границу Bearer); сессии —
   новый метод `SessionService.revokeOthersAndRotateCurrent` поверх существующих port'ов
   (research.md §5).
6. **SSO JIT**: `IdentityResolutionService.provisionJitAccount` публикует то же
   `RegistrationCompletedEvent` (research.md §2).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Оценка | Обоснование |
|---|---------|--------|-------------|
| I | Spec-Driven | PASS | `spec.md` утверждён (3 раунда clarify), план создаётся до `tasks.md`; код только по задачам |
| II | Масштабируемость | PASS | Новых stateful-элементов нет; rate limiting в Redis, seed идемпотентен и одноразов при старте; self-healing — дешёвая проверка в существующем запросе контактов |
| III | Доставка без дублей | PASS | Автодобавление контакта идемпотентно (`INSERT ... ON CONFLICT DO NOTHING`, V11); повторная доставка события/фолбэк не создают дублей (SC-003) |
| IV | API-First | PASS | Изменения OpenAPI только аддитивные (minor 0.10.0), TS-типы генерируются, breaking-проверка в CI |
| V | Безопасность | PASS | Argon2id + индивидуальная соль (наследуется), rate limiting, аудит без секретов, авторизация Bearer на endpoint, секрет admin только в env |
| VI | Test-First | PASS | IT для endpoint/контракта/лимитов/сессий, unit для валидации, фронтенд-тесты редиректа/форм; планируются в tasks.md до реализации |
| VII | Простота (YAGNI) | PASS | Максимум переиспользования: `User.changePassword()`, `PasswordPolicyService`, `JdbcContactRepository.add` (идемпотентен), `AuthEventRecorder`, паттерн `returnTo` из 003; новый брокер/таблица не вводятся |
| VIII | SOLID | PASS | Новый endpoint — тонкий адаптер в `auth/api`, логика в доменном сервисе (SRP); расширение enum аудита — точка расширения данными, не кодом |

**Вывод**: нарушений нет, Complexity Tracking не требуется.

**Re-check после Phase 1 (post-design)**: дизайн ([research.md](./research.md),
[data-model.md](./data-model.md), [contracts/](./contracts/)) перепроверен по всем восьми
принципам — оценок не изменил: аддитивный контракт 0.10.0 (IV), идемпотентность онбординга
на уровне PK+ON CONFLICT и монотонный флаг (III), все новые лимиты/отзывы в Redis/PG без
in-process состояния, seed одноразов на старт (II), Argon2id/аудит/секреты-env (V),
переиспользование `TokenPair`/`ContactService`/`PasswordPolicyService` вместо новых
абстракций (VII), блоклист слабых паролей остаётся серверным (без утечки в бандл).
Поведение из clarify-сессии зафиксировано в самом FR-005 (Q6, spec.md): топ-лист слабых
паролей проверяется сервером, клиент проверяет детерминированные правила до отправки
(research.md §8), понятность сообщений сохраняется. Отклонений от спеки нет. Гейт пройден.

## Project Structure

### Documentation (this feature)

```text
specs/008b-auth-ux-polish/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/
├── src/main/kotlin/webchat/backend/
│   ├── auth/
│   │   ├── api/SessionController.kt, RegisterController.kt, PasswordResetController.kt
│   │   │   └── PasswordChangeController.kt        # NEW: POST /api/v1/auth/password-change
│   │   ├── domain/model/User.kt                   # changePassword() — reuse
│   │   ├── domain/service/
│   │   │   ├── PasswordChangeService.kt           # NEW: проверка текущего, политика, сессии
│   │   │   ├── PasswordPolicyService.kt           # reuse (политика 002)
│   │   │   ├── SessionService.kt                  # extend: revokeOthers + rotate current
│   │   │   ├── RegistrationService.kt             # hook: событие завершения регистрации
│   │   │   └── LoginService.kt                    # без изменений
│   │   ├── ratelimit/RateLimitFilter.kt           # route table + per-user bucket
│   │   ├── security/AuthEvents.kt                 # NEW enum values PASSWORD_CHANGE_*
│   │   └── onboarding/                            # NEW: StarterContactListener, AdminSeed
│   ├── users/
│   │   ├── api/dto/MeUserResponse.kt               # NEW: PublicUser + hasPassword
│   │   └── api/UsersController.kt                  # extend: me() → MeUserResponse
│   ├── contacts/
│   │   ├── api/ContactController.kt               # hook: self-healing fallback
│   │   ├── domain/service/ContactService.kt       # reuse add (идемпотентен)
│   │   └── repository/JdbcContactRepository.kt    # reuse ON CONFLICT DO NOTHING
│   ├── sso/domain/service/IdentityResolutionService.kt  # hook: JIT-аккаунты
│   └── config/                                    # AsyncConfig, свойства seed/лимитов
├── src/main/resources/
│   ├── db/migration/V17__password_change_onboarding.sql  # NEW
│   └── application.yml                            # admin.seed.*, rate limits
└── src/test/kotlin/webchat/backend/
    ├── auth/PasswordChangeIT.kt                   # NEW
    ├── onboarding/AdminSeedIT.kt, StarterContactIT.kt  # NEW
    └── auth/SessionIT.kt, RateLimitIT.kt          # extend

frontend/
├── src/
│   ├── App.tsx                                    # без изменений (роут /forgot-password есть)
│   ├── auth/pages/
│   │   ├── LoginPage.tsx                          # redirect + «Забыли пароль?»
│   │   └── SsoCallbackPage.tsx                    # isSafeReturnPath → shared util
│   ├── auth/lib/returnTo.ts                       # NEW: shared safe-path util
│   ├── chats/components/ProfileModal.tsx          # форма смены пароля
│   ├── api/auth.ts, client.ts, session.ts         # passwordChange(), токены
│   └── api/schema.d.ts                            # regen из openapi.yaml
└── src/.../__tests__/                             # LoginPage, ProfileModal tests

contracts/
└── openapi.yaml                                   # additive: 0.9.0 → 0.10.0

deploy/
├── local/docker-compose.yml                       # без изменений (только infra: PG/Redis/Mailpit/dex)
└── k8s/                                           # env admin seed: ADMIN_SEED_EMAIL + secret ADMIN_SEED_PASSWORD
```

**Structure Decision**: существующая структура `backend/` + `frontend/` + `contracts/`
(Option 2 web application) сохраняется; новые артефакты встроены в пакеты-by-feature
(`auth`, `contacts`, новый `auth/onboarding`), миграция Flyway продолжается нумерацией
V17.
