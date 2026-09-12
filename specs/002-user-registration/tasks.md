---

description: "Task list for feature 002-user-registration (register, login, sessions)"
---

# Tasks: Регистрация с email-подтверждением, вход по паролю и управление сессиями

**Input**: Design documents from `/specs/002-user-registration/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/api-contract.md, quickstart.md, `.specify/memory/constitution.md`

**Tests**: ОБЯЗАТЕЛЬНЫ — конституция VI (Test-First, NON-NEGOTIABLE) и plan.md (Constitution Check VI): каждая story содержит тест-задачи, которые пишутся ДО реализации (RED) и становятся зелёными вместе с ней (GREEN).

**Organization**: задачи сгруппированы по user stories US1–US6 из spec.md: P1 = US1, US2, US3; P2 = US4, US5, US6. Каждая story — независимо реализуемый и независимо проверяемый инкремент.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: можно исполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: принадлежность к user story (US1…US6); Setup/Foundational/Polish — без метки
- Каждый task содержит точные пути к файлам и ссылки на разделы design-документов

## Path Conventions

- Backend: `backend/src/main/kotlin/webchat/backend/`, тесты `backend/src/test/kotlin/webchat/backend/`
- Frontend: `frontend/src/`
- Контракт: `contracts/openapi.yaml`; инфраструктура: `deploy/`

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: зависимости, dev-инфраструктура и API-контракт (API-First, принцип IV) — до любого кода фичи.

- [X] T001 Добавить новые зависимости в `backend/build.gradle.kts`: `spring-boot-starter-security`, `spring-boot-starter-oauth2-resource-server`, `spring-boot-starter-jdbc`, `org.postgresql:postgresql`, `flyway-core` + `flyway-database-postgresql`, `spring-boot-starter-data-redis` (Lettuce), `spring-boot-starter-mail`, `bucket4j_jdk17-core` + `bucket4j_jdk17-redis-lettuce` (8.x, своя версия), `datasource-micrometer-spring-boot`; test: `spring-boot-testcontainers`, `org.testcontainers:postgresql`, Redis 7 — generic `GenericContainer` из `org.testcontainers:testcontainers` (без стороннего Redis-модуля — принцип VII) ([research.md §1–§6](./research.md); versions — BOM Spring Boot); критерий: `./gradlew build` exit 0, все зависимости резолвятся
- [X] T002 [P] Создать `deploy/local/docker-compose.yml`: postgres:17 (:5432), redis:7 (:6379), mailpit (:8025 UI / :1025 SMTP) ([quickstart.md §2](./quickstart.md)); критерий: `docker compose -f deploy/local/docker-compose.yml up -d` → все контейнеры запущены, Mailpit UI доступен на :8025 (quickstart.md §2)
- [X] T003 [P] Создать dev-манифесты Kustomize: `deploy/k8s/overlays/dev/postgres.yaml` (Deployment replicas:1 strategy:Recreate + PVC 5Gi RWO + Secret), `deploy/k8s/overlays/dev/redis.yaml` (Recreate + PVC 1Gi, `--maxmemory 256mb --maxmemory-policy volatile-ttl`, appendonly off), `deploy/k8s/overlays/dev/mailpit.yaml` (Deployment + Service) ([research.md §10](./research.md)); критерий: `kubectl kustomize deploy/k8s/overlays/dev` рендерится без ошибок
- [X] T004 Обновить `contracts/openapi.yaml` до версии 0.2.0 (только additive, без BREAKING.md): endpoints 1–10 из [api-contract.md §1–§2](./contracts/api-contract.md), `components.schemas` (RegisterRequest, ResendRequest, ConfirmRegistrationRequest, SetPasswordRequest, LoginRequest, RefreshRequest, LogoutRequest, PasswordResetRequest, PasswordResetConfirmRequest, TokenPair, PublicUser, Problem — все тела запросов №1–№9 именованными схемами, §4), securityScheme `bearerAuth`, единая модель ошибок problem+json (§3); проверить `vacuum lint -e contracts/openapi.yaml` и `oasdiff breaking origin/main:...` — без breaking
- [X] T005 Сгенерировать TS-типы из контракта в `frontend/src/api/schema.d.ts` (конвейер codegen 001); drift-check (regen + `git diff --exit-code`) зелёный ([api-contract.md §5](./contracts/api-contract.md))

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: миграции, конфигурация, security-цепочка и тестовая база — ДО любых user stories.

**⚠️ CRITICAL**: ни одна user story не начинается до завершения фазы.

- [X] T006 Создать Flyway SQL-миграции в `backend/src/main/resources/db/migration/`: `V1__users.sql`, `V2__one_time_tokens.sql`, `V3__sessions.sql`, `V4__refresh_tokens.sql`, `V5__email_outbox.sql`, `V6__auth_events.sql` — все поля/инварианты по [data-model.md §1–§6](./data-model.md), включая уникальные индексы `lower(username)`/`lower(email)`, UNIQUE `token_hash`, FK CASCADE, jsonb-колонки; критерий: T012 зелёный — все 6 таблиц присутствуют в information_schema
- [X] T007 Расширить `backend/src/main/resources/application.yml`: datasource + flyway, spring.data.redis, spring.mail, `auth.*` (TTL access 5 мин / refresh 3 дня / ссылки: email_verification 24 ч, password_setup 1 ч, password_reset 1 ч, лимиты и cooldown — [research.md §11](./research.md); `auth.password.blocklist-path` — топ-лист слабых паролей — [research.md §3](./research.md)), `server.forward-headers-strategy=framework`, `management.metrics.distribution.percentiles-histogram.http.server.requests=true` + `slo=500ms` (серверная p95-метрика для SC-003/T057b), `management.endpoints.web.exposure.include=health,prometheus,metrics` (metrics — для финальной проверки p95 в T057b; данные те же, что в prometheus), `auth.ip-hash-pepper` и JWT-ключи из env (секретов в репо нет); критерий: контекст стартует с env-переменными (T012); секретов в application.yml нет
- [X] T008 Создать базовый IT-класс `backend/src/test/kotlin/webchat/backend/AbstractIntegrationTest.kt`: static Testcontainers PostgreSQL 17 + Redis 7, `@ServiceConnection`, один контейнер на прогон ([research.md §9](./research.md)); критерий: первый наследник класса (T012) зелёный
- [X] T009a Создать `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt`: `SecurityFilterChain` stateless, csrf off, permitAll ТОЛЬКО 8 точных путей контракта №1–6, №8–9: `/api/v1/auth/register`, `/api/v1/auth/register/resend`, `/api/v1/auth/register/confirm`, `/api/v1/auth/register/password`, `/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/password-reset`, `/api/v1/auth/password-reset/confirm` (без wildcard `/**` — инвариант US3-4/SC-002) + технические endpoints фичи 001: `/actuator/health/**` (пробы K8s liveness/readiness), `/actuator/prometheus` (scrape), `/actuator/metrics/**` (проверка SC-003 в T057b; вне публичного API-контракта — [technical-endpoints.md 001 §1](../../001-project-foundation/contracts/technical-endpoints.md)); `anyRequest().authenticated()`; единый 401 `application/problem+json` (`authenticationEntryPoint`) ([research.md §5](./research.md); [api-contract.md §3](./contracts/api-contract.md)); примечание: в dev permitAll для `/actuator/**` унаследован от фичи 001; при появлении prod-overlay — ограничить ingress-доступом (metrics/prometheus не должны быть публично доступны извне); критерий: GREEN в T037 (единый 401; аудит публичных путей)
- [ ] T009b Добавить бин `PasswordEncoder` в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt`: Argon2id m=19456,t=2,p=1 в `DelegatingPasswordEncoder` ([research.md §3](./research.md)) (после T009a — тот же файл); критерий: GREEN в T013b/T026 (Argon2-хеш задаётся и проверяется)
- [ ] T010 [P] Создать доменные перечисления и порт времени: `backend/src/main/kotlin/webchat/backend/auth/domain/model/UserStatus.kt`, `TokenPurpose.kt`, `SessionStatus.kt` (значения и переходы по data-model.md §1–§4) и `backend/src/main/kotlin/webchat/backend/auth/domain/port/Clock.kt`; критерий: `./gradlew build` exit 0; значения/переходы соответствуют data-model.md §1–§4
- [ ] T011 [P] Создать журнал событий безопасности `backend/src/main/kotlin/webchat/backend/auth/security/AuthEvents.kt`: `AuthEventRecorder` (JdbcTemplate-запись в auth_events: event_type, user_id NULL-able, `ip_hash` = SHA-256(IP + pepper), user_agent ≤256, details jsonb, trace_id; БЕЗ паролей/токенов/сырого PII) — FR-013, [data-model.md §6](./data-model.md); критерий: GREEN в T013a/T026/T045b (записи auth_events без секретов)
- [ ] T012 Создать smoke-IT `backend/src/test/kotlin/webchat/backend/ContextLoadsIT.kt`: контекст поднимается с PG+Redis (`AbstractIntegrationTest`), Flyway применил все 6 таблиц (проверка через information_schema)

**Checkpoint**: Foundation готов — схемы в PG, security-цепочка с единым 401, тестовая база с Testcontainers. Stories можно начинать.

---

## Phase 3: User Story 1 — Регистрация с email-подтверждением и заданием пароля (Priority: P1) 🎯 MVP

**Goal**: полный путь создания аккаунта: register → письмо со ссылкой → confirm → set-password → статус `active` ([spec.md US1](./spec.md)).

**Independent Test**: API/IT: POST register → 202 и письмо с одноразовой ссылкой в outbox/Mailpit; confirm → 200 + setupToken; password → 204, аккаунт `active` в БД; занятые active username/email → 409 с полем; повторное применение ссылки → 400; resend чаще 60 с → 429 ([quickstart §4.1–4.2](./quickstart.md)).

### Tests for User Story 1 (сначала RED)

- [ ] T013a [US1] Создать IT `backend/src/test/kotlin/webchat/backend/auth/RegistrationFlowIT.kt` (US1-1, US1-4): 202 + аккаунт `pending_email_confirmation` + письмо в очередь; некорректный формат username/email (pattern RegisterRequest, контракт §4) → 400 сразу, письмо не ставится; 409 на занятые username/email аккаунтом любого статуса — активным и pending, включая частичное пересечение с незавершённым (только username / только email), без раскрытия статуса держателя (указание поля); возобновление только при полном совпадении пары на незавершённой регистрации, без дубля аккаунта; запись `register_requested` в auth_events (user_id, ip_hash, без секретов — FR-013, [data-model.md §6](./data-model.md)) — [api-contract.md №1](./contracts/api-contract.md)
- [ ] T013b [US1] Дополнить `RegistrationFlowIT.kt` (US1-2, US1-3): подтверждение до истечения → setupToken; задание пароля → `active`; отклонение пароля по политике FR-004 (<8 / >128 символов, пустой/пробельный, из топ-листа — детерминированный кейс `password123` из bundled-списка [research.md §3](./research.md), = username/email) и несовпадение confirmPassword → 400, аккаунт остаётся `awaiting_password`, setupToken не поглощается (повторная попытка валидным паролем → 204 `active`); идемпотентность ссылок FR-012 (повтор → 400); записи `email_confirmed` и `password_set` в auth_events после успешных confirm/password (FR-013, [data-model.md §6](./data-model.md)) — [api-contract.md №3–4](./contracts/api-contract.md)
- [ ] T013c [US1] Дополнить `RegistrationFlowIT.kt` (US1-6): истечение password_setup → resend → письмо `password_setup` (ссылка /set-password) → задание пароля по нему → `active`, прежний setupToken → 400; cooldown resend 60 с → 429, в т.ч. со сменой IP — PG-cooldown не зависит от Redis-бакетов, приоритет механизмов; resend ранее 60 с после исходного письма регистрации (`email_verification`) → 429 — cooldown считает семью `email_verification` ∪ `email_verification_repeat` одним шагом ([data-model.md §5](./data-model.md)) — [research.md §11](./research.md); [api-contract.md №2](./contracts/api-contract.md)
- [ ] T014 [P] [US1] Написать Vitest-тесты `frontend/src/auth/pages/__tests__/RegisterPages.test.tsx`: потоки register → confirm-registration → set-password, отображение ошибок 400/409/429 (Testing Library)

### Implementation for User Story 1

- [ ] T015 [P] [US1] Создать модель и репозиторий User: `backend/src/main/kotlin/webchat/backend/auth/domain/model/User.kt`, `auth/domain/port/UserRepository.kt`, `auth/repository/JdbcUserRepository.kt` (JdbcTemplate + row-mapper; поиск по `lower(username)` / `lower(email)`) — [data-model.md §1](./data-model.md); критерий: GREEN в составе T013a–T013c
- [ ] T016 [P] [US1] Создать модель и репозиторий OneTimeToken: `backend/src/main/kotlin/webchat/backend/auth/domain/model/OneTimeToken.kt`, `auth/domain/port/TokenRepository.kt`, `auth/repository/JdbcOneTimeTokenRepository.kt` — условное поглощение `UPDATE ... SET used_at=now() WHERE id=? AND token_hash=? AND used_at IS NULL AND expires_at>now()` (rowcount=0 → отклонение), аннулирование предыдущих active-токенов того же user+purpose — [data-model.md §2](./data-model.md), FR-012; критерий: GREEN в составе T013a–T013c
- [ ] T017 [P] [US1] Реализовать `backend/src/main/kotlin/webchat/backend/auth/domain/service/TokenService.kt`: генерация 256-bit opaque значения (SecureRandom, base64url), хранение только SHA-256-хеша, TTL по purpose (email_verification 24 ч / password_setup 1 ч / password_reset 1 ч) — [research.md §4, §11](./research.md); критерий: GREEN в составе T013a–T013c
- [ ] T018 [P] [US1] Создать порт и SMTP-адаптер: `backend/src/main/kotlin/webchat/backend/email/EmailGateway.kt` (порт домена, DIP) + `email/SmtpEmailGateway.kt` (spring-boot-starter-mail; dev — Mailpit) — [research.md §6](./research.md); критерий: GREEN в T013a (Mailpit) и T050 (fake-шлюз)
- [ ] T019 [P] [US1] Создать `backend/src/main/kotlin/webchat/backend/email/JdbcEmailOutboxRepository.kt`: вставка `pending`-строки в той же TX, что user + OneTimeToken; cooldown resend — не ранее 60 с после последней строки того же шага (семья `email_verification` ∪ `email_verification_repeat`; отдельно `password_setup`, `password_reset`) для user по `MAX(created_at)` независимо от статуса (pending/sent/failed — poller помечает `sent` за секунды, привязка к `pending` пуста); второй эшелон после бакета `rl:email:resend` — [research.md §11](./research.md) — [data-model.md §5](./data-model.md); критерий: GREEN в T013c (cooldown 429, включая resend сразу после письма регистрации)
- [ ] T020 [P] [US1] Создать `backend/src/main/kotlin/webchat/backend/email/templates/EmailTemplates.kt`: тексты писем `email_verification` / `email_verification_repeat` / `password_setup` с SPA-ссылками `{APP_PUBLIC_BASE_URL}/confirm-registration?token=...`, `/set-password?token=...` — [api-contract.md §1](./contracts/api-contract.md); критерий: GREEN в T013a/T013c (ссылки /confirm-registration, /set-password)
- [ ] T021 [US1] Реализовать `backend/src/main/kotlin/webchat/backend/email/OutboxPoller.kt`: `@Scheduled` fixed-delay 5 с, `SELECT ... FOR UPDATE SKIP LOCKED`, отправка через `EmailGateway`, пометка `sent`, базовый retry через `next_attempt_at`: интервалы 60 с для попыток 2–4 (единое расписание — [research.md §11](./research.md)), на этапе US1 максимум 3 повторные попытки, далее строка остаётся `pending` (полная политика — T052; [research.md §11](./research.md)) (зависит от T018, T019) — [data-model.md §5](./data-model.md); критерий: GREEN в T013a (письмо `sent`) и T050
- [ ] T022a [P] [US1] Создать политику паролей FR-004: bundled-ресурс `backend/src/main/resources/auth/password-blocklist.txt` (top-10k SecLists xato, lowercase — [research.md §3](./research.md)) + `backend/src/main/kotlin/webchat/backend/auth/domain/service/PasswordPolicyService.kt`: длина 8–128; не пустой/пробельный; вне топ-листа (сравнение по `trim().toLowerCase()`, конфиг `auth.password.blocklist-path`); ≠ username/email; критерий: GREEN в T013b (политика паролей)
- [ ] T022b [US1] Реализовать в `backend/src/main/kotlin/webchat/backend/auth/domain/service/RegistrationService.kt` register + resend: register (валидация формата; уникальность среди ВСЕХ аккаунтов: занятый username или email (любой статус) → 409 с указанием поля, единый для active/pending, без раскрытия статуса держателя; исключение — полное совпадение пары (username+email) на незавершённом → возобновление: аннулировать старые токены, новое письмо следующего шага, без дубля), resend по статусу аккаунта: `pending_email_confirmation` → письмо `email_verification_repeat`; `awaiting_password` (вкл. истечение setupToken) → письмо `password_setup` со ссылкой `/set-password` (аннулировать прежние password_setup-токены); `active`/несуществующий → единый 202 без письма; cooldown → 429; записи AuthEvents — FR-001, FR-002 (зависит от T015–T017, T019–T020)
- [ ] T022c [US1] Дополнить `backend/src/main/kotlin/webchat/backend/auth/domain/service/RegistrationService.kt`: confirm (поглощение email_verification → выдача password_setup в ответе), setPassword (политика FR-004 через T022a; Argon2-хеш; статус `active`); записи AuthEvents — FR-003, FR-012 (зависит от T022a, T022b — тот же файл)
- [ ] T023 [US1] Реализовать `backend/src/main/kotlin/webchat/backend/auth/api/RegisterController.kt` (+ DTO в `auth/api/dto/`): POST `/auth/register` (202/400/409/429), `/auth/register/resend` (202 всегда единообразно/400/429; для 429 от cooldown — `Retry-After` = 60 − elapsed), `/auth/register/confirm` (200 `{setupToken, setupTokenType, expiresInSec}`/400/429), `/auth/register/password` (204/400/429) — problem+json строго по контракту №1–4; GREEN по T013a–T013c
- [ ] T024 [P] [US1] Создать типизированные вызовы endpoints 1–4 в `frontend/src/api/auth.ts` (типы из `frontend/src/api/schema.d.ts`); критерий: `tsc` strict без ошибок; типы только из schema.d.ts
- [ ] T025 [P] [US1] Создать страницы `frontend/src/auth/pages/RegisterPage.tsx`, `frontend/src/auth/pages/ConfirmRegistrationPage.tsx`, `frontend/src/auth/pages/SetPasswordPage.tsx` + маршруты `/register`, `/confirm-registration`, `/set-password` в `frontend/src/App.tsx`; GREEN по T014

**Checkpoint**: US1 полностью работает и проверяется независимо (quickstart §4.1–4.2 через Mailpit).

---

## Phase 4: User Story 2 — Вход по username/email, токены сессии, refresh-ротация, logout (Priority: P1)

**Goal**: login (username ИЛИ email + пароль) → пара токенов (access JWT ES256 5 мин + opaque refresh 3 дня sliding), ротация refresh при каждом использовании с детекцией reuse, logout с мгновенным отзывом ([spec.md US2](./spec.md)).

**Independent Test**: API/IT: login обоими идентификаторами → 200 TokenPair; неверный пароль / несуществующий identifier → одинаковый 401 `Invalid credentials`; незавершённый аккаунт → 403; refresh → новая пара, старый refresh → 401; reuse ротированного → компрометация всей цепочки; logout → оба токена недействительны ([quickstart §4.3](./quickstart.md)).

### Tests for User Story 2 (сначала RED)

- [ ] T026 [P] [US2] Написать IT `backend/src/test/kotlin/webchat/backend/auth/SessionIT.kt`: US2-1…US2-5 — вход по username и по email → 200 пара; единый 401 (тайминги выравнены — фиктивный хеш), в т.ч. пароль короче 8 символов → 401 `Invalid credentials`, не 400 (контракт §4: `password: string(1..128)`); 403 «завершите регистрацию»; ротация (старый refresh повторно → 401, цепочка отозвана, denylist sid); logout → 204, оба токена отклоняются; две параллельные сессии независимы — logout одной не отзывает другую (edge spec); в auth_events зафиксированы `refresh_rotated`, `logout` и `refresh_reuse_detected` + `session_revoked{reason=logout|refresh_reuse_detected}` (без секретов) — FR-013 ([api-contract.md №5–7](./contracts/api-contract.md))
- [ ] T027 [P] [US2] Написать Vitest-тесты `frontend/src/api/__tests__/client.test.ts` и `frontend/src/auth/pages/__tests__/LoginPage.test.tsx`: Bearer-интерцептор, авто-refresh single-flight (параллельные 401 → один refresh), единый обработчик 401, поток логина

### Implementation for User Story 2

- [ ] T028 [P] [US2] Реализовать `backend/src/main/kotlin/webchat/backend/auth/security/JwtService.kt`: подпись/проверка ES256 (P-256), claims `sub`/`sid`/`jti`/`iat`/`exp`/`typ:"access"`, заголовок `kid`, ключи из `AUTH_JWT_KEYS` (kid → PEM) + `AUTH_JWT_ACTIVE_KID`, TTL 5 мин (конфиг.) — [research.md §4](./research.md); критерий: GREEN в составе T026
- [ ] T029 [P] [US2] Создать модели и репозитории сессий: `backend/src/main/kotlin/webchat/backend/auth/domain/model/Session.kt`, `auth/domain/model/RefreshToken.kt`, `auth/domain/port/SessionRepository.kt`, `auth/repository/JdbcSessionRepository.kt`, `auth/repository/JdbcRefreshTokenRepository.kt` — CAS-ротация условным `UPDATE ... WHERE id=? AND token_hash=? AND status='active' AND expires_at>now()` — [data-model.md §3–§4](./data-model.md); критерий: GREEN в составе T026
- [ ] T030 [US2] Реализовать `backend/src/main/kotlin/webchat/backend/auth/domain/service/SessionService.kt`: создание сессии при входе (access+refresh); ротация (rowcount=0 → reuse: session → `compromised`, отзыв всей цепочки, denylist); logout (revoked, reason=logout, denylist); `revokeAllForUser(password_change)` + denylist каждого sid; Redis-ключ `auth:denylist:sid:<sid>` с TTL ≤ остаток жизни access; записи AuthEvents (действие + эффект): при успешной ротации — `refresh_rotated`; `logout` + `session_revoked{reason=logout}`; при reuse — `refresh_reuse_detected` + `session_revoked{reason=refresh_reuse_detected}`; при смене пароля — `session_revoked{reason=password_change}` — FR-006, FR-010, FR-011, FR-013 (зависит от T028, T029, T011); критерий: GREEN в составе T026
- [ ] T031 [US2] Реализовать `backend/src/main/kotlin/webchat/backend/auth/domain/service/LoginService.kt`: идентификация по `lower(username)` ИЛИ `lower(email)`; Argon2-проверка; несуществующий identifier → проверка против фиктивного Argon2-хеша (тот же код-путь/тайминг/сообщение); вход только из `active` (иначе 403); AuthEvents `login_success`/`login_failed` — FR-005, [research.md §7](./research.md); критерий: GREEN в T026 (единый 401, тайминг выравнен)
- [ ] T032 [US2] Реализовать `backend/src/main/kotlin/webchat/backend/auth/security/AuthJwtDecoder.kt`: кастомный `JwtDecoder` — выбор ключа по `kid`, проверка подписи/экспирации/clock skew, проверка denylist sid (Redis), единая ошибка без деталей; зарегистрировать бином `JwtDecoder` для resource-server в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` — [research.md §4–§5](./research.md); критерий: GREEN в T026/T037
- [ ] T033 [US2] Реализовать `backend/src/main/kotlin/webchat/backend/auth/api/SessionController.kt` (+ DTO): POST `/auth/login` (200 TokenPair с `user{id,username,email}` / 401 единая / 403), POST `/auth/refresh` (200 новая пара / 401 единая без различий), POST `/auth/logout` (Bearer + `{refreshToken}` → 204 / 401) — контракт №5–7; GREEN по T026
- [ ] T034 [P] [US2] Создать `frontend/src/auth/session.ts`: хранение токенов (access — память, refresh — localStorage; трейдофф и компенсации — [research.md §4](./research.md)), чтение/обновление пары, признак истечения сессии; критерий: GREEN в T027
- [ ] T035 [P] [US2] Создать `frontend/src/api/client.ts`: Bearer-интерцептор, авто-refresh по 401 single-flight (одновременные запросы ждут один refresh), единая обработка финального 401; GREEN по T027
- [ ] T036 [US2] Создать `frontend/src/auth/pages/LoginPage.tsx` + маршрут `/login` в `frontend/src/App.tsx` + вызовы endpoints 5–7 и 10 (`GET /users/me` → `PublicUser`) в `frontend/src/api/auth.ts` (ОБЩИЕ файлы `auth.ts`/`App.tsx` с T024/T025/T044 — не параллельно с ними; последовательно после T024/T025); критерий: GREEN в T027 (LoginPage)

**Checkpoint**: вход/ротация/logout работают независимо от US3+ (проверка access-токена — на уровне AuthJwtDecoder и защищённых путей).

---

## Phase 5: User Story 3 — API чата только для аутентифицированных (Priority: P1)

**Goal**: единообразный 401 на любой защищённый путь без действительного токена; публичны ровно endpoints №1–6, №8–9 (+health); logout №7 и `GET /users/me` №10 — только с Bearer ([spec.md US3](./spec.md)).

**Independent Test**: API/IT: без токена / мусорный / истёкший / отозванный → один и тот же 401 problem+json; валидный access → 200 от `/users/me`; перечень публичных путей совпадает с контрактом и полон (SC-002).

### Tests for User Story 3 (сначала RED)

- [ ] T037 [P] [US3] Написать IT `backend/src/test/kotlin/webchat/backend/auth/AuthenticationBoundaryIT.kt`: US3-1…US3-4 — все комбинации недействительных токенов → единый 401 `Not authenticated`; валидный → 200; аудит публичных путей = ровно контракт №1–6, №8–9 + технические `/actuator/health/**`, `/actuator/prometheus`, `/actuator/metrics/**` вне контракта API (001) (№7 logout и №10 — только с Bearer) — SC-002, [api-contract.md §1–§3](./contracts/api-contract.md)

### Implementation for User Story 3

- [ ] T038 [US3] Реализовать `backend/src/main/kotlin/webchat/backend/users/api/UsersController.kt`: GET `/api/v1/users/me` → 200 `PublicUser {id, username, email, status, createdAt}` / 401 (контракт №10, схема PublicUser); GREEN по T037

**Checkpoint**: граница аутентификации зафиксирована и проверена; все P1-stories (US1+US2+US3) работают независимо.

---

## Phase 6: User Story 4 — Восстановление пароля по email-ссылке (Priority: P2)

**Goal**: forgot-password → письмо с одноразовой ссылкой (1 ч) → set нового пароля → отзыв всех сессий, старый пароль недействителен; единый 202 без раскрытия существования email ([spec.md US4](./spec.md)).

**Independent Test**: API/IT: запрос по существующему email → письмо со ссылкой; несуществующий → тот же 202, письма нет; confirm → 204, вход старым паролем 401 / новым 200, прежние refresh/access отозваны; повторное/истёкшее применение ссылки → 400 ([quickstart §4.4](./quickstart.md)).

### Tests for User Story 4 (сначала RED)

- [ ] T039 [P] [US4] Написать IT `backend/src/test/kotlin/webchat/backend/auth/PasswordResetIT.kt`: US4-1…US4-5 — 202 единообразно (существующий/несуществующий email); смена по валидной ссылке → старый пароль 401, новый 200; все сессии/refresh отозваны (denylist, событие `session_revoked{reason=password_change}` в auth_events — FR-013); повторное применение → 400 с предложением новой ссылки; отклонение нового пароля по политике FR-004 и несовпадение подтверждения → 400, ссылка восстановления остаётся действительной (повторная попытка корректным паролем по той же ссылке → 204, сессии отозваны) ([api-contract.md №8–9](./contracts/api-contract.md))
- [ ] T040 [P] [US4] Написать Vitest-тесты `frontend/src/auth/pages/__tests__/ResetPages.test.tsx`: потоки forgot → reset, ошибки 400/429

### Implementation for User Story 4

- [ ] T041 [US4] Реализовать `backend/src/main/kotlin/webchat/backend/auth/domain/service/PasswordResetService.kt`: выдача OneTimeToken(password_reset, TTL 1 ч, аннулирование предыдущих) + outbox-письмо (TX); confirm — поглощение токена (FR-012), смена пароля (политика FR-004, Argon2), `SessionService.revokeAllForUser` + denylist; AuthEvents `password_reset_requested`/`password_reset_completed` — FR-010 (зависит от T017, T019, T030); критерий: GREEN в составе T039
- [ ] T042 [US4] Реализовать `backend/src/main/kotlin/webchat/backend/auth/api/PasswordResetController.kt` (+ DTO): POST `/auth/password-reset` (202 всегда единообразно / 400 / 429), POST `/auth/password-reset/confirm` (204 / 400 / 429) — контракт №8–9; GREEN по T039
- [ ] T043 [P] [US4] Добавить шаблон `password_reset` в `backend/src/main/kotlin/webchat/backend/email/templates/EmailTemplates.kt` (ссылка `{APP_PUBLIC_BASE_URL}/reset-password?token=...`); критерий: GREEN в T039 (ссылка /reset-password)
- [ ] T044 [US4] Создать `frontend/src/auth/pages/ForgotPasswordPage.tsx`, `frontend/src/auth/pages/ResetPasswordPage.tsx` + маршруты `/forgot-password`, `/reset-password` в `App.tsx` + вызовы endpoints 8–9 в `frontend/src/api/auth.ts` (ОБЩИЕ файлы `auth.ts`/`App.tsx` с T024–T036 — после завершения frontend-интеграции US1/US2); GREEN по T040

**Checkpoint**: US1+US2+US4 работают независимо друг от друга на общем foundation.

---

## Phase 7: User Story 5 — Rate limiting и защита от подбора (Priority: P2)

**Goal**: Bucket4j+Redis лимиты на всех публичных endpoints — по источнику везде, по email/identifier где он есть в запросе; прогрессирующая задержка и блокировка подбора; лимиты общие для всех реплик ([spec.md US5](./spec.md)).

**Independent Test**: API/IT: 6-й register/час с IP → 429 + `Retry-After`; 10 неудачных логинов в один идентификатор (username/email) → возрастающие задержки (с 4-й); 11-я попытка → 429 ≈ остаток 15 мин; после истечения валидный пароль входит сразу; 31-й `refresh`/`confirm` в минуту с одного IP → 429 (FR-009); счётчики в Redis переживают рестарт backend (SC-004, [quickstart §4.5](./quickstart.md)).

### Tests for User Story 5 (сначала RED)

- [ ] T045a [P] [US5] Написать IT `backend/src/test/kotlin/webchat/backend/auth/RateLimitIT.kt` (US5-1, US5-4): 429 + Retry-After по IP на register/resend/login/password-reset; по email-бакету на register/resend/login/password-reset (для login — sha256(identifier)); resend — два эшелона ([research.md §11](./research.md)): бакет `rl:email:resend` срабатывает первым в RateLimitFilter, PG-cooldown 60 с ([data-model.md §5](./data-model.md)) — вторым (подробно покрыт T013c), оба ответа — единый 429 problem+json; по IP (без email-ключа) на confirm/password/reset-confirm/refresh — все 8 публичных маршрутов ([research.md §11](./research.md)); валидный трафик под лимитом не затронут
- [ ] T045b [US5] Дополнить `RateLimitIT.kt` (US5-2, US5-3): прогрессирующая задержка (замеряемые тайминги) и блокировка после 10-й неудачи (11-я попытка — 429 от счётчика подбора, `Retry-After` ≈ TTL 15 мин; бакеты аккаунта/IP не перехватывают — приоритет [research.md §8, §11](./research.md)); в auth_events зафиксирован `login_throttled` (без секретов) — FR-013; счётчики сохраняются при рестарте приложения — SC-004; US5-3 (консистентность между экземплярами) проверяется аппроксимацией: бакеты/счётчики живут только во внешнем Redis и переживают рестарт backend (конституция II: stateless-поды, состояние вне процесса), поэтому консистентность при горизонтальном масштабировании — свойство по построению; буквальный второй экземпляр backend в IT не поднимается (после T045a — тот же файл)

### Implementation for User Story 5

- [ ] T046 [P] [US5] Реализовать `backend/src/main/kotlin/webchat/backend/auth/ratelimit/ClientIpResolver.kt`: XFF leftmost (единый доверенный ingress-хоп), fallback `remoteAddr`, IPv6 → /64-префикс — [research.md §7](./research.md); критерий: GREEN в T045a
- [ ] T047 [US5] Реализовать `backend/src/main/kotlin/webchat/backend/auth/ratelimit/RateLimitFilter.kt`: Bucket4j `LettuceBasedProxyManager`, ключи `rl:ip:<route>:<ip>` (все 8 публичных маршрутов) и `rl:email:<route>:<sha256(email | identifier)>` (только register/resend/login/password-reset — где email/identifier есть в запросе; для login — sha256(identifier), см. [data-model.md §7](./data-model.md)), лимиты из `auth.ratelimit.*` ([research.md §11](./research.md)), ответ 429 problem+json + `Retry-After`; регистрация фильтра в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` — FR-009 (зависит от T046); критерий: GREEN в T045a
- [ ] T048 [US5] Реализовать `backend/src/main/kotlin/webchat/backend/auth/ratelimit/LoginThrottle.kt`: Redis-счётчик `login:fail:<sha256(identifier)>` (INCR + EXPIRE NX 15 мин, сброс при успехе); задержка `min(250ms × 2^(n−3), 15s)` при 4–9 накопленных неудачах; блокировка при ≥10 накопленных неудачах (11-я и последующие попытки — 429 + Retry-After) ДО бакетов и проверки пароля (проверка счётчика — в `backend/src/main/kotlin/webchat/backend/auth/ratelimit/RateLimitFilter.kt` для `/auth/login`); прогрессивная задержка — в `backend/src/main/kotlin/webchat/backend/auth/domain/service/LoginService.kt`; запись AuthEvent `login_throttled` при применении задержки/блокировки (без секретов) — FR-013 — [research.md §8, §11](./research.md), SC-004; GREEN по T045a–T045b
- [ ] T049 [US5] Обработать 429 + Retry-After в `frontend/src/auth/pages/RegisterPage.tsx`, `LoginPage.tsx`, `ForgotPasswordPage.tsx`, `ConfirmRegistrationPage.tsx`, `SetPasswordPage.tsx`, `ResetPasswordPage.tsx` — все страницы, вызывающие endpoints №1–№9 с 429 по контракту (понятное сообщение о лимите и времени повтора) (после T025/T036/T044 — редактирует уже созданные страницы; параллелен только backend-задачам US5); критерий: T014/T027/T040 дополнены кейсом 429 и зелёные

**Checkpoint**: все публичные endpoints защищены от флуда и подбора; лимиты консистентны при масштабировании.

---

## Phase 8: User Story 6 — Транзакционные письма через внешний шлюз (Priority: P2)

**Goal**: заменяемый `EmailGateway` + transactional outbox в PG: сбой email-сервиса не роняет публичные endpoints, 100% сбоев доставки наблюдаемы, повторная отправка работает после восстановления ([spec.md US6](./spec.md)).

**Independent Test**: IT с fake `EmailGateway`: отказ шлюза → endpoints без 5xx; восстановление → доставка с backoff; метрики `email_delivery_total{type,outcome}` и gauge `email_outbox_pending` фиксируют сбои; в логах нет секретов (SC-007, [quickstart §4.6](./quickstart.md)).

### Tests for User Story 6 (сначала RED)

- [ ] T050 [P] [US6] Написать IT `backend/src/test/kotlin/webchat/backend/email/EmailOutboxResilienceIT.kt` с подменяемым fake `EmailGateway`: сбой шлюза → 202 без 5xx; восстановление → письмо доставлено (backoff); 10 неудачных попыток → `failed_permanent`; логи без паролей/открытых токенов/сырого PII — SC-007, US6-1…US6-3

### Implementation for User Story 6

- [ ] T051 [US6] Добавить observability доставки в `backend/src/main/kotlin/webchat/backend/email/OutboxPoller.kt`: счётчик Micrometer `email_delivery_total{type,outcome}`, gauge `email_outbox_pending`, таймер `email_delivery_duration{type,outcome}` = `sent_at − created_at` при пометке `sent` (проверяемость SC-001), структурные логи (`outbox_id`, `email_type`, `recipient_hash`, `attempt`, `provider_error`) — FR-008, SC-001, [research.md §6](./research.md); критерий: GREEN в T050 (метрики/таймер доставки)
- [ ] T052 [US6] Достроить retry-политику (расширяет базовый retry из T021) в `backend/src/main/kotlin/webchat/backend/email/OutboxPoller.kt`: backoff для попыток 5–10: 5→15→30→60→60→60 мин (потолок 1 ч, единое расписание — research §11), суммарно 10 попыток → `failed_permanent`, `last_error` без секретов; номер попытки непрерывен с базовой политикой T021: строки `pending`, уже сделавшие ≤3 попытки этапа US1, дообрабатываются по единому расписанию до суммарных 10; GREEN по T050

**Checkpoint**: доставка писем устойчива к отказам внешнего сервиса и полностью наблюдаема.

---

## Phase 9: Polish & Cross-Cutting Concerns

**Purpose**: сквозные проверки качества, безопасности и конвейера.

- [ ] T053 Прогнать полную ручную валидацию по [quickstart.md §4.1–4.6](./quickstart.md) (docker-compose + Mailpit) и автопроверку §5 (backend/frontend тесты, lint, vacuum, oasdiff)
- [ ] T054 [P] Линт и форматирование: `./gradlew ktlintCheck detekt` (workdir `backend/`), `pnpm --dir frontend lint`; критерий: обе команды exit 0
- [ ] T055 Аудит секретов (SC-005): gitleaks по репозиторию; в логах/БД/выгрузках нет открытых паролей и токенов (только Argon2-хеши и SHA-256); креды только env/K8s Secrets
- [ ] T056 Контрактный конвейер и CI зелёные: `vacuum lint -e contracts/openapi.yaml`, drift-check TS-типов (regen + `git diff --exit-code`), `oasdiff breaking` — только additive 0.2.0; полный прогон `./gradlew test` и `pnpm --dir frontend test`
- [ ] T057a [P] Создать нагрузочный smoke-сценарий `load/k6/auth.smoke.js`: burst `login` ≈280 rps (вращающиеся identifier'ы) + burst `register` 50 rps с одного источника — профиль [research.md §13](./research.md); k6 thresholds: 0×5xx, `http_req_duration` p(95)<500 мс для валидных логинов под лимитом (coarse-guard: прогон ко-локален, сеть ≈ loopback), `/actuator/health` 200 весь прогон, 429 с `Retry-After` при превышении лимитов, валидный трафик под лимитом обслуживается (SC-008, конституция VI)
- [ ] T057b Прогнать сценарий по [quickstart.md §4.7](./quickstart.md) локально против docker-compose + финальная проверка после прогона — `GET /actuator/metrics/http.server.requests?tag=uri:/api/v1/auth/login` → percentile 0.95 ≤ 0.5 s (точное соответствие SC-003: серверная задержка без сети клиента) (зависит от T057a)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей; T004 (контракт) → T005 (генерация типов); T001 блокирует Phase 2
- **Foundational (Phase 2)**: после Phase 1 — БЛОКИРУЕТ все user stories
- **User Stories (Phase 3+)**: после Phase 2; параллельно (при наличии ресурсов) или последовательно по приоритету
- **Polish (Phase 9)**: после всех реализованных stories

### User Story Dependencies

- **US1 (P1)**: после Foundational — других зависимостей нет
- **US2 (P1)**: после Foundational; для e2e-тестов использует аккаунты US1 (в IT можно сеять данные напрямую)
- **US3 (P1)**: после US2 (нужна выдача валидных токенов для позитивного сценария)
- **US4 (P2)**: после US1 (письма/токены) и US2 (отзыв сессий через SessionService)
- **US5 (P2)**: инфраструктура после Foundational; полные проверки — после US1/US2/US4 (сами endpoints под лимитами)
- **US6 (P2)**: после US1 (outbox-механика)

### Within Each User Story

- Тесты пишутся ПЕРВЫМИ и падают (RED) — конституция VI
- Модели/репозитории → сервисы → контроллеры → frontend
- Story завершена только с зелёными тестами (GREEN), линтом и пройденным `/speckit.checklist`

### Parallel Opportunities

- Setup: T002 ∥ T003 (после T001 можно параллельно с T004)
- Foundational: T010 ∥ T011 (после T006); T007 ∥ T008 ∥ T009a; T009b — после T009a (тот же файл)
- US1: T013a → T013b → T013c (один файл) ∥ T014; T015–T020 (6 задач, разные файлы); T024 ∥ T025
- US2: T026 ∥ T027; T028 ∥ T029; T034 ∥ T035; T036 — после frontend-интеграции US1 (общие auth.ts/App.tsx)
- US4: T039 ∥ T040; T043 — параллелен (backend-файл); T044 — после frontend-интеграции US1/US2 (общие auth.ts/App.tsx)
- US5: T046 до/параллельно с T045a/T045b-тестированием; T049 — после T044, параллелен backend-задачам US5
- Разные stories — параллельно разными исполнителями (учитывая порядок зависимостей выше)

---

## Parallel Example: User Story 1

```bash
# Тесты (RED) параллельно:
Task T013a–T013c: "RegistrationFlowIT.kt" (последовательно)  # backend
Task T014: "RegisterPages.test.tsx"         # frontend

# Модели/инфраструктура параллельно (разные файлы):
Task T015: User + UserRepository
Task T016: OneTimeToken + TokenRepository
Task T017: TokenService
Task T018: EmailGateway + SmtpEmailGateway
Task T019: JdbcEmailOutboxRepository
Task T020: EmailTemplates

# Затем последовательно: T021 (poller) → T022b → T022c (service) → T023 (controller) → GREEN
# Параллельно: T022a (политика паролей — отдельный файл)
# Frontend независимо: T024 ∥ T025
```

## Parallel Example: User Story 2

```bash
# Тесты (RED) параллельно:
Task T026: "SessionIT.kt"
Task T027: "client.test.tsx" + "LoginPage.test.tsx"

# Параллельно (разные файлы):
Task T028: JwtService (security/)
Task T029: Session/RefreshToken модели + репозитории

# Затем: T030 (SessionService) → T031 (LoginService) → T032 (AuthJwtDecoder+wiring) → T033 (controller) → GREEN
# Frontend: T034 ∥ T035; T036 — после frontend-интеграции US1 (общие auth.ts/App.tsx)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (контракт 0.2.0 + зависимости + dev-инфраструктура)
2. Complete Phase 2: Foundational (миграции, SecurityConfig, тестовая база)
3. Complete Phase 3: User Story 1 — регистрация с письмом и паролем
4. **STOP and VALIDATE**: quickstart §4.1–4.2 через Mailpit; `./gradlew test` зелёный
5. Demo: пользователь доходит до статуса `active`

### P1 Core (полный вход в систему)

1. MVP (см. выше)
2. Add US2 (Phase 4) → вход/ротация/logout → проверить §4.3
3. Add US3 (Phase 5) → граница аутентификации + `/users/me` → SC-002

### Incremental Delivery (P2)

1. Add US4 (Phase 6) → восстановление пароля → §4.4
2. Add US5 (Phase 7) → лимиты/защита от подбора → §4.5 (SC-004)
3. Add US6 (Phase 8) → устойчивость доставки писем → §4.6 (SC-007)
4. Phase 9: Polish → полный quickstart + аудит секретов + CI

### Parallel Team Strategy

1. Команда вместе проходит Setup + Foundational
2. Далее: Developer A → US2→US3; Developer B → US6 (после US1); US4/US5 — по мере освобождения
3. Каждая story интегрируется независимо и не ломает предыдущие

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story]-метка связывает задачу с user story для трассируемости
- DoD каждой задачи (конституция): реализация + зелёные тесты + lint/format + обновлённый контракт (если затронут) + `/speckit.checklist`
- Реализация последовательно, статус отмечается в этом файле; параллельность — только по [P]/разным исполнителям
- Пароли/открытые токены нигде не логируются (FR-004, SC-005); секреты — только env/K8s Secrets
- Параметры (TTL, лимиты) — конфигурируемые, значения по умолчанию из [research.md §11](./research.md)
- Избегать: расплывчатых задач, конфликтов одного файла, cross-story зависимостей, ломающих независимость
