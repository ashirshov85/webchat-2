---

description: "Task list for 008b-auth-ux-polish implementation"

---

# Tasks: Полировка auth-флоу и управление паролем

**Input**: Design documents from `/specs/008b-auth-ux-polish/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/auth-ux-polish-api.md, quickstart.md

**Tests**: ВКЛЮЧЕНЫ — конституция VI (Test-First, NON-NEGOTIABLE) и plan.md явно требуют IT/ unit/ фронтенд-тестов до/одновременно с реализацией. Тест-задачи стоят в начале каждой истории и обязаны «краснить» до реализации.

**Organization**: Задачи сгруппированы по user stories (US1–US4 из spec.md); каждая история независимо реализуема и проверяема после завершения Phase 2.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно параллельно с другими [P]-задачами своей группы (разные файлы); явная текстовая зависимость в описании задачи («после TXXX», «зависит от TXXX») приоритетнее маркера
- **Один агент — строго последовательно**: маркеры [P] и секции параллельности адресуют команде из нескольких исполнителей/агентов; одиночный исполнитель (один AI-агент, конституция Development Workflow) идёт по порядку фаз без перескоков — [P] не разрешает параллельную реализацию несвязанных задач одним агентом
- **[Story]**: Принадлежность к user story (US1, US2, US3, US4)
- Все пути — от корня репозитория; backend-пакет `backend/src/main/kotlin/webchat/backend/` сокращён до `webchat/backend/`

## Path Conventions

- **Backend**: `backend/src/main/kotlin/webchat/backend/`, миграции `backend/src/main/resources/db/migration/`, тесты `backend/src/test/kotlin/webchat/backend/`
- **Frontend**: `frontend/src/`
- **Contract**: `contracts/openapi.yaml` (0.9.0 → 0.10.0, только аддитивно)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Базлайн-верификация готовой инфраструктуры фич 001–008a на ветке фичи

- [X] T001 Verify green baseline на ветке `008b-auth-ux-polish`: `docker compose -f deploy/local/docker-compose.yml up -d`, затем `./gradlew check` (из `backend/`), `pnpm --dir frontend install && pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`, `scripts/validate-contracts.sh` — все суиты зелёные до начала работ (фиксация базлайна для SC-005)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Единая миграция V17, разделяемая US2 (enum аудита) и US4 (флаг онбординга) — один файл, не делится между фазами историй

**⚠️ CRITICAL**: Задачи US2/US4 не начинаются до завершения этой фазы

- [X] T002 Создать миграцию `backend/src/main/resources/db/migration/V17__password_change_onboarding.sql`: (а) `ALTER TABLE users ADD COLUMN starter_contact_added BOOLEAN NOT NULL DEFAULT FALSE` (прецедент V15); (б) `UPDATE users SET starter_contact_added = TRUE` — существующие аккаунты помечаются «обработанными» без добавления контактов (бэкфилла нет, clarify spec.md); (в) `ALTER TYPE auth_event_type ADD VALUE 'password_change_completed'` и `ADD VALUE 'password_change_failed'` (прецедент V8__sso_enums.sql:19-24). Соответствие data-model.md §1.1–1.2

**Checkpoint**: Миграция применяется (`./gradlew check` прогоняет Flyway); фундамент готов — истории US1–US4 можно начинать (US1/US3 не зависят от V17)

---

## Phase 3: User Story 1 - Редирект на чат после парольного входа с учётом returnTo (Priority: P1) 🎯 MVP

**Goal**: После успешного парольного входа — переход на `/chat` или безопасный `returnTo`-путь (FR-001/FR-002, SC-001); чисто клиентская механика, login-endpoint 002 не меняется

**Independent Test**: (а) вход валидными credentials → страница чата; (б) вход с `returnTo` (ProtectedRoute `/login?returnTo=…`) → возврат по пути; (в) небезопасный `returnTo` (`https://evil.example`, `//evil.example`) → `/chat`; (г) неверные credentials → остаётся на /login с ошибкой; SSO-флоу 003 без регрессий

### Tests for User Story 1 (Test-First) ⚠️

> **NOTE**: Написать ПЕРВЫМИ, убедиться в FAIL до реализации

- [X] T003 [P] [US1] Unit-тесты общего модуля в `frontend/src/auth/lib/__tests__/returnTo.test.ts`: `isSafeReturnPath` — true для `/chat`, `/settings/security`; false для `//evil.example`, `https://evil.example`, ``, `null`, `/\evil.com`, `/a/../b`, `/..`, `/foo\nbar` (управляющий символ), `/login`, `/login?x=1`, `/forgot-password`, `/register`, `/sso/callback` (критерии (б)–(д) FR-002); `loginRedirectTarget` — safe → сам путь, отсутствующий/пустой/небезопасный → `/chat`
- [X] T004 [P] [US1] Расширить `frontend/src/auth/pages/__tests__/LoginPage.test.tsx`: успешный вход → `window.location.assign('/chat')`; c `?returnTo=/settings/security` → возврат по пути; небезопасный `returnTo` → `/chat`; ошибочный вход → переходов нет, остаётся ошибка 002

### Implementation for User Story 1

- [X] T005 [US1] Создать общий модуль `frontend/src/auth/lib/returnTo.ts`: `isSafeReturnPath(value)` (перенос из SsoCallbackPage.tsx:45-47 с усилением по FR-002: `startsWith('/') && !startsWith('//') && !includes('\\')`, отклонение traversal-сегментов `..` (разбор pathname до `?`/`#`), отклонение управляющих символов и маршрутов auth-раздела (константа `AUTH_PATHS`: `/login`, `/register`, `/confirm-registration`, `/set-password`, `/forgot-password`, `/reset-password`, `/sso/callback`; сравнение по pathname) — браузеры нормализуют `\` в `/`, поэтому `/\evil.com` эквивалентен `//evil.com`; без decodeURIComponent — %-кодированные варианты (`%2e%2e`, `%5c`) браузером не нормализуются (research.md §7); MUST по FR-002, ожидание теста T003) и `loginRedirectTarget(returnTo)` (safe → путь; null/пустой/unsafe → `/chat`) — зависит от T003
- [X] T006 [P] [US1] Переключить `frontend/src/auth/pages/SsoCallbackPage.tsx` на импорт `isSafeReturnPath`/`loginRedirectTarget` из `frontend/src/auth/lib/returnTo.ts` (для валидных путей поведение не меняется; пути с `\`, traversal-сегментами `..`, управляющими символами и маршруты auth-раздела теперь отклоняются — усиление защиты от open redirect и для флоу 003). Расширить `frontend/src/auth/pages/__tests__/SsoCallbackPage.test.tsx` кейсами `/\evil.com` и `/login` → фолбэк `/chat` (санкционировано US1 AC5; регрессия прикрыта T003 + обновлёнными интеграционными кейсами) — после T005
- [X] T007 [P] [US1] В `frontend/src/auth/pages/LoginPage.tsx` после `setTokenPair(pair)` (успешный submit) выполнить `window.location.assign(loginRedirectTarget(currentReturnTo()))` — параметр ещё в URL на момент submit, sessionId-хранилище не используется (research.md §7) — после T005

**Checkpoint**: US1 полностью функционален и проверяется независимо (quickstart.md §US1, сценарии 1–4)

---

## Phase 4: User Story 2 - Смена пароля в «Мой профиль» (Priority: P1)

**Goal**: Аутентифицированный пользователь меняет пароль из профиля: серверная проверка текущего, политика 002, Argon2id, rate limiting (per-IP + per-user), аудит, отзыв прочих сессий + ротация refresh текущей (FR-004–FR-010); JIT-аккаунтам форма скрыта (`hasPassword` только в `GET /users/me`)

**Independent Test**: Смена пароля из профиля → выход → вход по новому успешен, по старому 401; неверный текущий 400; клиентская валидация до отправки; 6-я попытка/мин → 429 + Retry-After; второе устройство отозвано, текущее осталось вошедшим с ротированным refresh; в `auth_events` записи без секретов

### Contract for User Story 2 (API-First)

- [X] T008 [US2] Обновить `contracts/openapi.yaml` (0.9.0 → 0.10.0, строго аддитивно, contracts/auth-ux-polish-api.md §1–3): добавить `POST /api/v1/auth/password-change` (`operationId: changePassword`, `security: [{bearerAuth: []}]`, request-схема `PasswordChangeRequest` c `currentPassword`/`newPassword` без `confirmPassword`; ответы: 200 `TokenPair`, 400 `Problem` c `errors` `currentPassword: invalid` / `newPassword: too_short|too_long|blank|too_common|same_as_username|same_as_email`, 401, 429 + `Retry-After`); расширить `GET /users/me` inline `allOf` над `$ref PublicUser` c required `hasPassword: boolean` (НЕ в самом `PublicUser`); прогнать `scripts/validate-contracts.sh` (vacuum + drift + oasdiff breaking = 0)
- [X] T009 [US2] Регенерировать типы `frontend/src/api/schema.d.ts` командой `pnpm --dir frontend generate:api` (после T008); drift-check зелёный, `pnpm --dir frontend typecheck` проходит

### Tests for User Story 2 (Test-First) ⚠️

> **NOTE**: Написать ПЕРВЫМИ, убедиться в FAIL до реализации

- [X] T010 [P] [US2] Интеграционные тесты `backend/src/test/kotlin/webchat/backend/auth/PasswordChangeIT.kt` (Testcontainers, quickstart.md §US2): успех → вход по новому паролю успешен / по старому 401; неверный текущий → 400 `currentPassword: invalid` и хеш не изменился; JIT-аккаунт (без пароля) → тот же единый 400; нарушения политики → 400 с полевыми кодами; 6-я смена в минуту одним пользователем → 429 + `Retry-After` (per-user 5/1m); per-IP 10/1m — отдельным сценарием по паттерну RateLimitIT («beyond ip limit» / «independently of source ip»): два пользователя с одного IP, 11-й запрос в минуту → 429, при этом per-user бакеты каждого не исчерпаны; мультидевайс: прочие сессии revoked + sid в denylist + `session_revoked` c `reason=password_change`, текущая — жива, refresh ротирован (старый refresh 401, новый работает), ответ = `TokenPair`; SSO-сессия (фича 003) второго устройства отзывается наравне с парольной (единообразие FR-008); совпадающий новый пароль → отзыв прочих сессий всё равно выполняется; `auth_events` содержит `password_change_completed`/`password_change_failed` без паролей/токенов; ассерт деталей: completed → `details={reason:"profile"}`, failed → `details={reason:"invalid_current_password"}` (в т.ч. для JIT-попытки)
- [X] T011 [P] [US2] Unit-тесты `frontend/src/auth/lib/__tests__/passwordPolicy.test.ts`: длина вне 8–128 → ошибка; пустой/из одних пробелов; new ≠ confirm; совпадение с username/email (case-insensitive + trim)
- [X] T012 [P] [US2] Тесты формы: новый `frontend/src/chats/components/__tests__/PasswordChangeForm.test.tsx` + расширить `frontend/src/chats/components/__tests__/ProfileModal.test.tsx`: клиентская валидация блокирует отправку (`changePassword` не вызывается); успех → `setTokenPair` + toast + очистка полей; 400 `currentPassword: invalid` → inline `role="alert"` без toast; серверное `too_common` → понятное сообщение через `problemMessage`; `hasPassword === false` → форма скрыта, подсказка со ссылкой `/forgot-password`
- [X] T012a [P] [US2] Расширить `backend/src/test/kotlin/webchat/backend/users/ProfileDisplayNameIT.kt` (существующий IT контракта №10 `GET /api/v1/users/me`, ME_PATH:739): `hasPassword=true` для аккаунта с паролем; `hasPassword=false` для JIT-аккаунта (password_hash IS NULL); поле required согласно контракту 0.10.0 (inline allOf над PublicUser, research.md §4); поверхности peer/search/member не содержат `hasPassword` (FR-004, JIT-признак не раскрывается) — краснит до T014

### Implementation for User Story 2 (Backend)

- [X] T013 [P] [US2] Расширить enum `AuthEventType` в `backend/src/main/kotlin/webchat/backend/auth/security/AuthEvents.kt` значениями `PASSWORD_CHANGE_COMPLETED("password_change_completed")` и `PASSWORD_CHANGE_FAILED("password_change_failed")` (строки = значения V17; прецедент пары LOGIN_SUCCESS/LOGIN_FAILED)
- [X] T014 [P] [US2] Создать DTO `backend/src/main/kotlin/webchat/backend/users/api/dto/MeUserResponse.kt` (поля `PublicUserResponse` + `hasPassword: Boolean`) и вернуть его из `UsersController.me` (`backend/src/main/kotlin/webchat/backend/users/api/UsersController.kt`): `hasPassword = user.passwordHash != null`; только `/users/me`, не в peer/search/member поверхностях (research.md §4; ожидание теста T012a)
- [X] T015 [P] [US2] Rate limiting: добавить класс лимитов `PasswordChangeLimits(ip, userPerMinute)` в `backend/src/main/kotlin/webchat/backend/config/AuthRateLimitProperties.kt`; блок `auth.ratelimit.password-change` (`ip: "10/1m"`, `user-per-minute: 5`) в `backend/src/main/resources/application.yml`; строку маршрута per-IP 10/1m (ключ `rl:ip:password-change:<ip>`) в таблицу `backend/src/main/kotlin/webchat/backend/auth/ratelimit/RateLimitFilter.kt`; если keyFamily `pwdchange` ещё не зарегистрирована — регистрацию сборки bucket в `backend/src/main/kotlin/webchat/backend/config/RateLimitConfig.kt` (прецедент RateLimitConfig.kt:109-169, research.md §6); существующая keyFamily — переиспользовать без правок; fails-open на отказе Redis наследуется
- [X] T016 [P] [US2] Port-метод `revokeAllForUserExcept(userId, keepSid, reason): List<UUID>` в `backend/src/main/kotlin/webchat/backend/auth/domain/port/SessionRepository.kt` + реализация в `backend/src/main/kotlin/webchat/backend/auth/repository/JdbcSessionRepository.kt` (зеркало `revokeAllForUser`, причина `RevokedReason.PASSWORD_CHANGE` уже существует)
- [X] T017 [US2] Метод `revokeOthersAndRotateCurrent(userId, currentSessionId): IssuedTokenPair` в `backend/src/main/kotlin/webchat/backend/auth/domain/service/SessionService.kt` (зависит от T016): guard активности сессии (requireSession-паттерн); `refreshTokenRepository.revokeActiveForUser(userId)` — все активные generations; для каждой прочей сессии — `denylistSid` + журнал `SESSION_REVOKED` c `details={reason:"password_change"}`; `issueGeneration(currentSessionId)` — новый refresh + новый access БЕЗ denylist текущего sid; существующий `IssuedTokenPair.toResponse()` для ответа (зависит от T016)
- [X] T018 [US2] Доменный сервис `backend/src/main/kotlin/webchat/backend/auth/domain/service/PasswordChangeService.kt` (зависит от T013, T015, T017; оркестрация research.md §5): JIT (`passwordHash == null`) → сверка с фиктивным хешем (timing-parity LoginService) → единый 400; `passwordEncoder.matches` неверен → журнал `PASSWORD_CHANGE_FAILED` вне откатываемой tx → 400 `currentPassword: invalid` через `RegistrationValidationException`; `PasswordPolicyService.validate` ДО мутаций → 400 с полевыми кодами `newPassword`; `userRepository.update(user.changePassword(hash, now))` (Argon2id, механизм 002); `revokeOthersAndRotateCurrent`; журнал `PASSWORD_CHANGE_COMPLETED` c `details={reason:"profile"}` (data-model §1.2); совпадение нового со старым не ветвится (полный путь FR-008); JIT-ветка после фиктивной сверки также пишет `PASSWORD_CHANGE_FAILED` c `details={reason:"invalid_current_password"}` (data-model §3.3 шаг 2)
- [X] T019 [US2] Тонкий контроллер `backend/src/main/kotlin/webchat/backend/auth/api/PasswordChangeController.kt` (зависит от T018): `POST /api/v1/auth/password-change`, Bearer; `userId = accessToken.subject`, `sid = accessToken.getClaimAsString("sid")` (прецедент RealtimeController); per-user лимит через `UserRateLimiter.tryAcquire` (5/1m) → 429 problem+json + `Retry-After`; ответ `TokenPairResponse`

### Implementation for User Story 2 (Frontend)

- [X] T020 [P] [US2] Создать `frontend/src/auth/lib/passwordPolicy.ts`: `validateNewPassword(password, {username, email, confirm}): string | null` — длина 8–128, не пустой/не из пробелов, совпадение с подтверждением, ≠ username/email (case-insensitive + `trim().toLowerCase()` — зеркало сервера); топ-лист слабых паролей на клиент НЕ ставится — `too_common` приходит серверским 400 (research.md §8) — зависит от T011
- [X] T021 [P] [US2] Добавить `changePassword({currentPassword, newPassword})` в `frontend/src/api/auth.ts` через `authedPost` → типизированный `TokenPair` из `frontend/src/api/schema.d.ts` (после T009)
- [X] T022 [US2] Создать компонент `frontend/src/chats/components/PasswordChangeForm.tsx` (зависит от T020, T021): отдельная `<form>` (submit не затрагивает «Сохранить» профиля); поля current (`autoComplete="current-password"`), new + confirm (`autoComplete="new-password"`); валидация `validateNewPassword` до отправки; успех → `setTokenPair` (единая точка ротации, `frontend/src/auth/session.ts`) + один toast (`useToast`) + очистка полей; ошибка — inline `.modal-err` c `role="alert"` без toast; дизайн-язык 008 (`profile-form`, `modal-btns`/`m-btn`)
- [X] T023 [US2] Интегрировать форму в `frontend/src/chats/components/ProfileModal.tsx`: рендер `PasswordChangeForm` между readonly-строками и кнопками; гейтинг по `me.hasPassword === true` (тип из T009), иначе — подсказка «задать пароль через восстановление по email» со ссылкой `/forgot-password` (FR-004, JIT-аккаунты 003)

**Checkpoint**: US2 полностью функционален: смена из профиля, мультидевайс-отзыв, лимиты, аудит, JIT-гейтинг (quickstart.md §US2, сценарии 1–7)

---

## Phase 5: User Story 3 - Ссылка сброса пароля на экране входа (Priority: P2)

**Goal**: Ссылка «Забыли пароль?» в футере страницы входа → существующий флоу 002 `/forgot-password`; API и семантика восстановления не меняются (FR-003)

**Independent Test**: Ссылка видна на `/login`; клик открывает форму запроса восстановления; прохождение флоу до входа с новым паролем идентично 002 (PasswordResetIT зелёный)

⚠️ NOTE: Задачи US3 редактируют те же файлы, что и US1 (`LoginPage.tsx` / `LoginPage.test.tsx`) — выполнять после T004/T007 или координировать в одной ветке

### Tests for User Story 3 (Test-First) ⚠️

- [X] T024 [US3] Расширить `frontend/src/auth/pages/__tests__/LoginPage.test.tsx`: ссылка «Забыли пароль?» видима и различима, `href="/forgot-password"` (после T004 — тот же файл)

### Implementation for User Story 3

- [X] T025 [US3] Добавить в футер `frontend/src/auth/pages/LoginPage.tsx` (рядом с «Зарегистрироваться», plain `<a href="/forgot-password">`) ссылку «Забыли пароль?» (research.md §10; маршрут/страница 002 существуют, API не трогается) — после T007 (тот же файл)

**Checkpoint**: US3 работает независимо; регресс 002 отсутствует (PasswordResetIT зелёный)

---

## Phase 6: User Story 4 - Стартовый контакт admin для новых пользователей (Priority: P2)

**Goal**: Seed аккаунта admin при старте (идемпотентный) + асинхронное автодобавление admin в контакты новых регистраций (парольных 002 и SSO JIT 003) + self-healing фолбэк в `GET /contacts`; 0 дублей, бэкфилла нет (FR-011–FR-013, SC-003)

**Independent Test**: После деплоя admin существует (повторные старты — без дублей); новая регистрация → admin в контактах ровно один раз при первом входе; повторная доставка события — без дубля; потеря события — фолбэк добавляет ровно один раз; удалённый admin не возвращается; существовавшие до деплоя пользователи контакт не получают

### Tests for User Story 4 (Test-First) ⚠️

> **NOTE**: Написать ПЕРВЫМИ, убедиться в FAIL до реализации

- [X] T026 [P] [US4] Интеграционные тесты `backend/src/test/kotlin/webchat/backend/onboarding/AdminSeedIT.kt`: старт без admin → создан ровно один active `admin` (email из конфига, `starter_contact_added = TRUE`); повторный старт приложения → без дублей и перезаписи; занятый username/email → warn, существующие данные не тронуты; без `ADMIN_SEED_PASSWORD` → беспарольная JIT-форма (active, `password_hash IS NULL`, проходит ck_users_active_implies_email); с паролем → Argon2id
- [X] T027 [P] [US4] Интеграционные тесты `backend/src/test/kotlin/webchat/backend/onboarding/StarterContactIT.kt`: завершение регистрации 002 → admin в контактах ровно один раз; SSO JIT-регистрация → тот же результат; повторная доставка события → без дубля; симуляция потери события → первый `GET /contacts` добавляет admin ровно один раз (self-healing); seed не выполнен (admin отсутствует) → GET /contacts не ломается (best-effort), флаг starter_contact_added остаётся FALSE; после появления admin (восстановление seed) следующий запрос добавляет контакт ровно один раз (spec.md edge, research.md §1/§3); пользователь удалил admin → повторные входы/запросы не возвращают; аккаунты, существовавшие до V17 (`starter_contact_added = TRUE` миграцией) → контактов не получают; admin не добавляется самому себе; AC6 (диалог с admin — обычная пара 004) — особых веток кода нет, покрывается регрессом 004 в T037

### Implementation for User Story 4

- [X] T028 [P] [US4] Конфигурация seed: новый `backend/src/main/kotlin/webchat/backend/config/AdminSeedProperties.kt` (`admin.seed.enabled` default true, `admin.seed.email` ← env `ADMIN_SEED_EMAIL`, dev-умолчание `admin@webchat.local`; пароль — только env `ADMIN_SEED_PASSWORD`, в конфиге не хранится) + блок `admin.seed` в `backend/src/main/resources/application.yml`
- [X] T029 [P] [US4] Инфраструктура событий: `@EnableAsync` в `backend/src/main/kotlin/webchat/backend/BackendApplication.kt` (рядом с `@EnableScheduling`; executor — автоконфигурация Boot на виртуальных потоках) + класс события `backend/src/main/kotlin/webchat/backend/auth/onboarding/RegistrationCompletedEvent.kt` (`userId: UUID`)
- [X] T030 [US4] Создать `backend/src/main/kotlin/webchat/backend/auth/onboarding/AdminSeedRunner.kt` (зависит от T002, T028): `ApplicationRunner`; `INSERT … ON CONFLICT DO NOTHING` (без arbiter — expression-индексы `lower(username)`/`lower(email)`, UPDATE никогда не выполняется); `password_hash` = Argon2id(env) или `NULL`; `status=active`, `email_confirmed_at=now()`, `password_set_at` при заданном пароле, `display_name='Administrator'`, `starter_contact_added=TRUE`; контрольный `SELECT id … WHERE lower(username)='admin'` → NULL ⇒ `log.warn` + счётчик, старт не ломается; метрики `admin_seed_total{outcome=created|skipped|failed}` (research.md §1); если строка admin фактически создаётся с dev-умолчанием email (`ADMIN_SEED_EMAIL` не задан → `admin@webchat.local`) — `log.warn("admin seed created with default email")` + метка `email=default` в метрике: prod-мисконфигурация наблюдаема, конвенция dev-дефолтов (application.yml) не ломается; prod обязан задать `ADMIN_SEED_EMAIL` через env/secret (T036)
- [ ] T031 [US4] Создать `backend/src/main/kotlin/webchat/backend/auth/onboarding/StarterContactService.kt` (зависит от T002): `ensureOnboarded(userId)` в новой `@Transactional` — точечное чтение флага по PK (`TRUE` ⇒ выход); резолв admin id по `lower(username)='admin'` (NULL ⇒ флаг остаётся FALSE, warn + счётчик, повтор при следующем запросе); идемпотентный `INSERT … ON CONFLICT DO NOTHING` (reuse `JdbcContactRepository.add`) + условный `UPDATE users SET starter_contact_added = TRUE WHERE id = ? AND NOT starter_contact_added`; self-guard: admin сам себе не добавляется; всё тело — best-effort `try/catch`
- [ ] T032 [US4] Создать `backend/src/main/kotlin/webchat/backend/auth/onboarding/StarterContactEventListener.kt` (зависит от T029, T031): `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` → `ensureOnboarded(userId)`; `try/catch` c `log.warn` + счётчиком (асинхронный void не роняет вызывающего); логирует `user_id={}` явно (MDC traceId в @Async-пул не попадает)
- [ ] T033 [P] [US4] Опубликовать `RegistrationCompletedEvent(userId)` внутри транзакции в `backend/src/main/kotlin/webchat/backend/auth/domain/service/RegistrationService.kt` (`setPassword`, сразу после активирующего write рядом с журналом `PASSWORD_SET`)
- [ ] T034 [P] [US4] Опубликовать `RegistrationCompletedEvent(userId)` внутри транзакции в `backend/src/main/kotlin/webchat/backend/sso/domain/service/IdentityResolutionService.kt` (`provisionJitAccount`, после insert + `bindIdentity` рядом с `SSO_ACCOUNT_CREATED`)
- [ ] T035 [US4] Self-healing фолбэк: в `backend/src/main/kotlin/webchat/backend/contacts/api/ContactController.kt` (`list`) вызвать `ensureOnboarded(callerId)` сразу после разрешения ownerId, ДО выборки контактов, в best-effort `try/catch` — фолбэк никогда не ломает выдачу (зависит от T031)
- [ ] T036 [P] [US4] Env для деплоя: расширить `deploy/k8s/overlays/dev/backend-auth-env.yaml` — `ADMIN_SEED_EMAIL` + optional `secretKeyRef` секрета `admin-seed` для `ADMIN_SEED_PASSWORD` (секреты только в secret manager, конституция V); docker-compose не меняется (бэкенд в нём не сервис)

**Checkpoint**: US4 работает независимо: seed идемпотентен, онбординг новых регистраций (обе точки), self-healing, 0 дублей (quickstart.md §US4, сценарии 1–6)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Регресс, контрактные гейты, аудит безопасности, сквозная проверка (SC-004/SC-005)

- [ ] T037 Полный регрессионный прогон: `./gradlew check` из `backend/` (включая новые `PasswordChangeIT`, `AdminSeedIT`, `StarterContactIT` и существующие `SessionIT`, `PasswordResetIT`, `RateLimitIT`, `SsoFlowIT`, `ContactsIT`) + `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck` — SC-005
- [ ] T038 Контрактные гейты: `scripts/validate-contracts.sh` — vacuum lint чист, regen drift = 0, `oasdiff breaking --fail-on ERR` против origin/main зелёный, версия 0.10.0, `contracts/BREAKING.md` не требуется (только аддитивно)
- [ ] T039 Верификация аудита безопасности (SC-004): в `auth_events` записи смены пароля (успешные + неверный текущий, включая JIT) без паролей/токенов; `users.password_hash` — Argon2id с индивидуальной солью; 0 открытых паролей в PG и логах; 429 с `Retry-After` вместо отказа сервиса
- [ ] T040 Сквозная проверка по `specs/008b-auth-ux-polish/quickstart.md`: все сценарии US1–US4 вручную (два браузерных профиля для мультидевайс US2; Mailpit для писем сброса и беспарольного seed-admin)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Нет зависимостей — старт немедленно
- **Foundational (Phase 2)**: Зависит от Phase 1 — BLOCKS задачи US2 (enum аудита V17) и US4 (колонка V17); US1/US3 от V17 не зависят, но начинаются после Phase 1
- **User Stories (Phases 3–6)**: После своих предпосылок истории независимы; приоритет P1 → P2
- **Polish (Phase 7)**: После всех завершённых историй

### User Story Dependencies

- **User Story 1 (P1)**: Только Phase 1 — не зависит от других историй и V17
- **User Story 2 (P1)**: Phase 1 + T002 (enum V17); контракт (T008→T009) до фронтенд-реализации; backend: T013/T016 параллельны → T017 → T018 → T019; frontend: T009 → T020/T021 → T022 → T023
- **User Story 3 (P2)**: Phase 1; формально независима, но редактирует те же файлы, что US1 (`LoginPage.tsx`, `LoginPage.test.tsx`) → после T004/T007 (или в одной ветке с US1)
- **User Story 4 (P2)**: Phase 1 + T002 (колонка V17); T028/T029 параллельны → T030 (зависит T028), T031 → T032 (зависит T029+T031) и T035 (зависит T031); T033/T034 параллельны после T029

### Within Each User Story

- Тесты пишутся ПЕРВЫМИ и «краснят» до реализации (конституция VI)
- Контракт до кода (US2: T008 → T009 → impl)
- Port/модель → доменный сервис → контроллер (backend); util/API-клиент → компонент → интеграция (frontend)
- История завершена до перехода к следующей по приоритету

### Parallel Opportunities

- T003 ∥ T004 (US1, тесты); T006 ∥ T007 (после T005, разные файлы)
- T010 ∥ T011 ∥ T012 ∥ T012a (US2, тесты backend/frontend); T013 ∥ T014 ∥ T015 ∥ T016 (US2, независимые backend-файлы); T020 ∥ T021 (frontend utils/API)
- T026 ∥ T027 (US4, тесты); T028 ∥ T029 (US4); T030 ∥ T031 (после T028/T029); T033 ∥ T034 (US4, точки публикации)
- US1 и US2 (обе P1) могут вестись параллельно разными исполнителями после Phase 2; US4 параллельно с US1/US2 после T002

---

## Parallel Example: User Story 1

```bash
# Тесты (параллельно):
Task T003: "Unit-тесты frontend/src/auth/lib/__tests__/returnTo.test.ts"
Task T004: "Расширение frontend/src/auth/pages/__tests__/LoginPage.test.tsx"

# После T005 (returnTo.ts) — параллельно (разные файлы):
Task T006: "SsoCallbackPage.tsx → импорт из auth/lib/returnTo.ts"
Task T007: "LoginPage.tsx submit → window.location.assign(loginRedirectTarget(...))"
```

## Parallel Example: User Story 2

```bash
# Тесты (параллельно):
Task T010: "backend/src/test/kotlin/webchat/backend/auth/PasswordChangeIT.kt"
Task T011: "frontend/src/auth/lib/__tests__/passwordPolicy.test.ts"
Task T012: "PasswordChangeForm.test.tsx + ProfileModal.test.tsx"
Task T012a: "ProfileDisplayNameIT.kt — hasPassword (контракт №10)"

# Backend-фундамент истории (параллельно, разные файлы):
Task T013: "AuthEvents.kt: PASSWORD_CHANGE_*"
Task T014: "MeUserResponse.kt + UsersController.me"
Task T015: "AuthRateLimitProperties + application.yml + RateLimitFilter"
Task T016: "SessionRepository.revokeAllForUserExcept + Jdbc impl"
# Затем последовательно: T017 → T018 → T019

# Frontend (после T009, параллельно):
Task T020: "frontend/src/auth/lib/passwordPolicy.ts"
Task T021: "frontend/src/api/auth.ts changePassword()"
# Затем: T022 → T023
```

## Parallel Example: User Story 4

```bash
# Тесты (параллельно):
Task T026: "backend/src/test/kotlin/webchat/backend/onboarding/AdminSeedIT.kt"
Task T027: "backend/src/test/kotlin/webchat/backend/onboarding/StarterContactIT.kt"

# Затем (параллельно, разные файлы):
Task T028: "config/AdminSeedProperties.kt + application.yml"
Task T029: "BackendApplication @EnableAsync + RegistrationCompletedEvent.kt"
# Затем: T030 (после T028) ∥ T031; далее T032/T035 (после T031); T033 ∥ T034 (после T029)
```

---

## Implementation Strategy

### MVP First (User Story 1 Only)

1. Complete Phase 1: Setup (baseline)
2. Complete T002: V17 (для US1 не требуется, но выполняется как общий фундамент)
3. Complete Phase 3: User Story 1 (редирект + returnTo)
4. **STOP and VALIDATE**: quickstart.md §US1 — вход → /chat, returnTo, unsafe → /chat, SSO-регресс
5. Deploy/demo если готово

### Incremental Delivery

1. Setup + V17 → фундамент готов
2. Add US1 (редирект входа) → проверка → MVP-демо
3. Add US2 (смена пароля, самый большой объём: контракт + backend + frontend) → мультидевайс-проверка → демо
4. Add US3 (ссылка сброса, минуты) → регресс 002 → демо
5. Add US4 (seed + онбординг) → проверка дублей/фолбэка → демо
6. Polish: регресс + контрактные гейты + аудит + quickstart end-to-end

### Parallel Team Strategy

1. Команда завершает Phase 1 + T002 вместе
2. Далее параллельно: Developer A → US1 → US3 (общие файлы LoginPage); Developer B → US2 (контракт → backend → frontend); Developer C → US4 (seed + события + фолбэк)
3. Polish выполняется совместно после слияния историй

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- [Story]-метки связывают задачи с US1–US4 из spec.md
- US2 — единственная история, меняющая публичный контракт (строго аддитивно, 0.10.0); US1/US3 — чисто клиентские, US4 — без изменений контракта
- Тесты обязательны (конституция VI): IT — Testcontainers PG17+Redis7; фронтенд — Vitest + Testing Library; тест-задачи «краснят» до реализации
- Каждая задача атомарна и самодостаточна для одной итерации агента (конституция I)
- Коммит после каждой задачи или логической группы; отметка статуса в tasks.md
- Проверка чекпоинтов историй — quickstart.md соответствующего раздела
- Избегать: расплывчатых задач, конфликтов одного файла в параллельных задачах (US1/US3 — LoginPage), межисторических зависимостей, ломающих независимость
