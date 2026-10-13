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
- [X] T031 [US4] Создать `backend/src/main/kotlin/webchat/backend/auth/onboarding/StarterContactService.kt` (зависит от T002): `ensureOnboarded(userId)` в новой `@Transactional` — точечное чтение флага по PK (`TRUE` ⇒ выход); резолв admin id по `lower(username)='admin'` (NULL ⇒ флаг остаётся FALSE, warn + счётчик, повтор при следующем запросе); идемпотентный `INSERT … ON CONFLICT DO NOTHING` (reuse `JdbcContactRepository.add`) + условный `UPDATE users SET starter_contact_added = TRUE WHERE id = ? AND NOT starter_contact_added`; self-guard: admin сам себе не добавляется; всё тело — best-effort `try/catch`
- [X] T032 [US4] Создать `backend/src/main/kotlin/webchat/backend/auth/onboarding/StarterContactEventListener.kt` (зависит от T029, T031): `@TransactionalEventListener(phase = AFTER_COMMIT)` + `@Async` → `ensureOnboarded(userId)`; `try/catch` c `log.warn` + счётчиком (асинхронный void не роняет вызывающего); логирует `user_id={}` явно (MDC traceId в @Async-пул не попадает)
- [X] T033 [P] [US4] Опубликовать `RegistrationCompletedEvent(userId)` внутри транзакции в `backend/src/main/kotlin/webchat/backend/auth/domain/service/RegistrationService.kt` (`setPassword`, сразу после активирующего write рядом с журналом `PASSWORD_SET`)
- [X] T034 [P] [US4] Опубликовать `RegistrationCompletedEvent(userId)` внутри транзакции в `backend/src/main/kotlin/webchat/backend/sso/domain/service/IdentityResolutionService.kt` (`provisionJitAccount`, после insert + `bindIdentity` рядом с `SSO_ACCOUNT_CREATED`)
- [X] T035 [US4] Self-healing фолбэк: в `backend/src/main/kotlin/webchat/backend/contacts/api/ContactController.kt` (`list`) вызвать `ensureOnboarded(callerId)` сразу после разрешения ownerId, ДО выборки контактов, в best-effort `try/catch` — фолбэк никогда не ломает выдачу (зависит от T031)
- [X] T036 [P] [US4] Env для деплоя: расширить `deploy/k8s/overlays/dev/backend-auth-env.yaml` — `ADMIN_SEED_EMAIL` + optional `secretKeyRef` секрета `admin-seed` для `ADMIN_SEED_PASSWORD` (секреты только в secret manager, конституция V); docker-compose не меняется (бэкенд в нём не сервис)

**Checkpoint**: US4 работает независимо: seed идемпотентен, онбординг новых регистраций (обе точки), self-healing, 0 дублей (quickstart.md §US4, сценарии 1–6)

---

## Phase 7: Polish & Cross-Cutting Concerns

**Purpose**: Регресс, контрактные гейты, аудит безопасности, сквозная проверка (SC-004/SC-005)

- [X] T037 Полный регрессионный прогон: `./gradlew check` из `backend/` (включая новые `PasswordChangeIT`, `AdminSeedIT`, `StarterContactIT` и существующие `SessionIT`, `PasswordResetIT`, `RateLimitIT`, `SsoFlowIT`, `ContactsIT`) + `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck` — SC-005
- [X] T038 Контрактные гейты: `scripts/validate-contracts.sh` — vacuum lint чист, regen drift = 0, `oasdiff breaking --fail-on ERR` против origin/main зелёный, версия 0.10.0, `contracts/BREAKING.md` не требуется (только аддитивно)
- [X] T039 Верификация аудита безопасности (SC-004): в `auth_events` записи смены пароля (успешные + неверный текущий, включая JIT) без паролей/токенов; `users.password_hash` — Argon2id с индивидуальной солью; 0 открытых паролей в PG и логах; 429 с `Retry-After` вместо отказа сервиса
- [X] T040 Сквозная проверка по `specs/008b-auth-ux-polish/quickstart.md`: все сценарии US1–US4 вручную (два браузерных профиля для мультидевайс US2; Mailpit для писем сброса и беспарольного seed-admin)

---

## Phase 8: Follow-up — экраны флоу восстановления пароля в дизайн-языке auth-экранов

**Purpose**: Прямые запросы пользователя после закрытия фичи: T041 — стилизация окна «Forgot password» как логин/регистрация + возврат на страницу логина; T042 — русификация его текстов; T043 — стилизация и русификация окна «Reset password»; spec.md 008b не меняется — задачи добавлены по прямому запросу пользователя

- [X] T041 Test-First — сначала расширить `frontend/src/auth/pages/__tests__/ResetPages.test.tsx` ожиданиями нового DOM (убедиться в FAIL до реализации; существующие ассерты `getByLabelText('Email')` / кнопка «Send reset link» сохраняются — sr-метка остаётся программно связанной): рендер обёртки `auth-card` с `AuthBadge` и бренд-блоком «Aethergram»; поле email — паттерн `auth-field` (SVG-иконка + sr-метка); кнопка отправки `auth-submit`; в футере `auth-footer` — ссылка возврата с `href="/login"`, видимая и на экране успеха после 202 (проверить кейс после `mockRequestPasswordReset` resolve). Затем перестроить `frontend/src/auth/pages/ForgotPasswordPage.tsx` по образцу `RegisterPage.tsx` (единственный эталон паттерна): обёртка `auth-screen` → `auth-card` + четыре `auth-rivet` → `AuthBadge` → `auth-brand-title`/`auth-brand-sub` (CONNECT · CHAT · EXPLORE) → `auth-form-title` «Forgot password» → форма: `auth-field` с SVG-иконкой конверта (копия register-email), `auth-label-sr` «Email», placeholder, `autoComplete="email"`; кнопка `auth-submit` c `disabled={submitting}`; футер `auth-footer`: `<a href="/login">Back to login</a>` (язык текстов экрана не меняется — меняется только разметка/стили); состояния ошибки (`role="alert"`) и успеха (`output`) — внутри карточки, стили наследуются от `auth-theme.css` без новых правил; функциональность без изменений: `requestPasswordReset`, сводный 202, полевые коды 400, 429, блокировка повторной отправки. Критерии приёмки: `/forgot-password` визуально идентичен семейству auth-экранов 008 (тот же набор классов/компонентов, что `LoginPage`/`RegisterPage`); ссылка возврата на `/login` доступна во всех состояниях; API/бэкенд не затронуты. Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`; ручная проверка цикла `/login` ↔ «Забыли пароль?» ↔ `/forgot-password` → «Back to login»

- [X] T042 Русификация текстов экрана `/forgot-password` — паритет по языку с `/login` и `/register` (запрос пользователя; выполняется после T041 — тот же файл). Test-First — сначала обновить `frontend/src/auth/pages/__tests__/ResetPages.test.tsx` (единственный файл с англоязычными ассертами экрана: строки с 'Send reset link', 'Forgot password', 'Back to login'; убедиться в FAIL до реализации): заголовок — `getByRole('heading', { name: 'Восстановление пароля' })` c классом `auth-form-title`; кнопка — `getByRole('button', { name: 'ОТПРАВИТЬ ССЫЛКУ' })` c классом `auth-submit`; ссылка возврата — `getByRole('link', { name: 'Вернуться ко входу' })` с `href="/login"` (в т.ч. на экране успеха); сообщение успеха содержит email и «действует 1 час». Затем заменить тексты в `frontend/src/auth/pages/ForgotPasswordPage.tsx`: `auth-form-title` → «Восстановление пароля»; кнопка `auth-submit` → «ОТПРАВИТЬ ССЫЛКУ» (стиль «ВОЙТИ»/«ЗАРЕГИСТРИРОВАТЬСЯ»); `output` успеха → «Если {email} принадлежит зарегистрированному аккаунту, мы отправили ссылку для восстановления пароля. Она действует 1 час.»; футер `auth-footer` → «Вернуться ко входу»; sr-метка/placeholder «Email» не меняются (паритет с register). Ограничения: меняются только текстовые узлы — разметка, классы, `requestPasswordReset`, сводный 202 (без раскрытия существования аккаунта), полевые коды 400, 429 и `autoComplete="email"` без изменений; смежные экраны (`/reset-password`, `/set-password`, `/confirm-registration`) и `problemMessage`-сообщения — вне scope. Критерии приёмки: все видимые тексты экрана на русском в терминологии существующих auth-экранов; ассерты теста зелёные. Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`; ручная проверка: отправка формы → русское сводное сообщение; `/login` ↔ «Забыли пароль?» ↔ `/forgot-password` → «Вернуться ко входу»

- [x] T043 Стилизация и русификация экрана `/reset-password` (запрос пользователя; после T042 — общий тест-файл). Test-First — сначала обновить/расширить `frontend/src/auth/pages/__tests__/ResetPages.test.tsx` (убедиться в FAIL до реализации): `fillResetPasswordForm` — метки «Новый пароль»/«Подтвердите пароль» (sr-метки остаются программно связанными), кнопка «СБРОСИТЬ ПАРОЛЬ»; заголовок — `getByRole('heading', { name: 'Сброс пароля' })` c классом `auth-form-title`; обёртка `auth-card` с `AuthBadge` и бренд-блоком «Aethergram»; оба поля — паттерн `auth-field` (SVG-иконка замка, `auth-submit`); сообщение успеха — русский текст со ссылкой «войти» на `/login` (замена `/log in/i`); интро содержит «действует 1 час»; НОВЫЙ кейс отсутствия токена: рендер без `?token=` → `role="alert"` с «отсутствует токен» + ссылка на `/forgot-password`. Затем перестроить `frontend/src/auth/pages/ResetPasswordPage.tsx` и `frontend/src/auth/pages/ResetPasswordForm.tsx` по образцу `ForgotPasswordPage.tsx`/`RegisterPage.tsx`: обёртка `auth-screen` → `auth-card` + четыре `auth-rivet` → `AuthBadge` → `auth-brand-title`/`auth-brand-sub` → `auth-form-title` «Сброс пароля» в обеих ветках; ветка без токена: `role="alert"` «Ссылка недействительна: отсутствует токен.» + текст «Запросите новую на странице восстановления пароля.» со ссылкой `<a href="/forgot-password">` (стиль `auth-footer`); ветка с токеном: интро «Придумайте новый пароль. Ссылка действует 1 час.» + форма: два `auth-field` c SVG-иконками замка (копия login-password), `auth-label-sr` «Новый пароль»/«Подтвердите пароль», placeholders, `autoComplete="new-password"`; кнопка `auth-submit` «СБРОСИТЬ ПАРОЛЬ» c `disabled={submitting}`; футер `auth-footer`: `<a href="/login">Вернуться ко входу</a>`; клиентская ошибка несовпадения → «Пароли не совпадают. Ссылка остаётся действующей — исправьте и попробуйте снова.»; экран успеха (`output`) → «Пароль сброшен — все сессии отозваны. Теперь вы можете войти с новым паролем.» со ссылкой «войти» на `/login`, внутри карточки. Ограничения: eye-toggle не добавляется (стилизация не вводит новую функциональность — его нет в register/set-password); `confirmPasswordReset`, семантика 204/отзыв сессий, полевые коды 400 (вкл. `too_common`), 429 + Retry-After, `minLength`/`maxLength` 8/128, блокировка повторной отправки — без изменений; смежные экраны `/set-password`, `/confirm-registration` — вне scope; API/бэкенд не затронуты. Критерии приёмки: `/reset-password` визуально идентичен семейству auth-экранов 008 во всех трёх состояниях (нет токена / форма / успех); все видимые тексты на русском; навигация замкнута (`/forgot-password` ↔ `/reset-password` → `/login`). Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`; ручная проверка: ссылка из письма без токена → alert + ссылка на восстановление; сброс пароля → русский экран успеха → вход новым паролем

---

## Phase 9: Follow-up — удаление заголовка «WebChat» из UI (ребрендинг на Aethergram)

**Purpose**: Прямой запрос пользователя: убрать главный заголовок приложения «WebChat», чтобы его нигде не было видно на UI; все вхождения WebChat удалить, где удалить нельзя — переименовать в Aethergram; spec.md 008b не меняется. Инвентаризация (2026-10-12): UI-видимые вхождения — `<h1>WebChat</h1>` в `App.tsx:85` (все страницы, кроме /login и /register, через `hideHeading`), `<title>WebChat</title>` в `frontend/index.html:7`, 4 темы писем «Webchat — …» в `backend/.../email/templates/EmailTemplates.kt:83,105,126,151`, имя static-клиента `name: WebChat` в `deploy/local/dex/config.yaml:58` (видно на локальной Dex-странице логина)

- [X] T044 Удаление/переименование UI-вхождений «WebChat» (запрос пользователя). Frontend, Test-First — сначала обновить `frontend/src/App.test.tsx`: заменить кейс «renders the application heading on public pages» (строки 32–40, ассерты `getByRole('heading', { level: 1, name: 'WebChat' })`) на «не рендерит заголовок приложения ни на одном маршруте» — `queryByRole('heading', { level: 1 })` → null на публичной странице (например, `/forgot-password`) и на защищённой (с аутентификацией, по образцу существующих кейсов `App protected messenger route`); убедиться в FAIL до реализации. Затем: (а) в `frontend/src/App.tsx` удалить строку `{hideHeading ? null : <h1>WebChat</h1>}` и ставшую мёртвой константу `hideHeading` (строки 81, 85) — `<main>` рендерит только `renderRoute(pathname)`; (б) в `frontend/index.html` заменить `<title>WebChat</title>` → `<title>Aethergram</title>` (удалить нельзя — обязательный элемент, влияет на вкладку браузера/историю). Backend: (в) в `backend/src/main/kotlin/webchat/backend/email/templates/EmailTemplates.kt` переименовать 4 темы писем «Webchat — подтверждение регистрации», «Webchat — новая ссылка для подтверждения регистрации», «Webchat — задайте пароль», «Webchat — восстановление пароля» → «Aethergram — …» (сохранить формулировки после тире); (г) в `backend/src/test/kotlin/webchat/backend/email/EmailOutboxResilienceIT.kt:171` ассерт `subject).contains("Webchat")` → `contains("Aethergram")` (краснит до (в)). Инфра: (д) в `deploy/local/dex/config.yaml` `name: WebChat` → `name: Aethergram` (отображается на Dex-логине; `id: webchat` НЕ трогать — технический client-id, синхронизирован с backend-конфигом). Вне scope (не UI, переименование ломающее — зафиксировано как ограничение): префиксы localStorage `webchat.*` (`auth.refreshToken`, `chats.outbox`, `sync.pendingReads`, `sync.cursors` — переименование разлогинит пользователей и потеряет их sync-данные), package `webchat.backend.*`, DB url/credentials `webchat` в `application.yml`, keycloak/dex client-id `webchat`, домены `noreply@webchat.local`/`admin@webchat.local`, `title: WebChat Public API` в `contracts/openapi.yaml` (dev-facing контракт, не UI), README/specs. Критерии приёмки: 0 видимых пользователю вхождений «WebChat»/«Webchat» в UI SPA и письмах; тема писем и title вкладки — «Aethergram»; удаление заголовка не ломает layout маршрутов. Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck` + `./gradlew check` из `backend/` (EmailOutboxResilienceIT); ручная проверка: вкладка браузера, все маршруты без h1-заголовка, письмо из Mailpit с темой «Aethergram — …»

---

## Phase 10: Follow-up — пункт «Выйти» в главном меню

**Purpose**: Прямой запрос пользователя: в menu-btn добавить пункт «Выйти» в конце списка; операция разлогинивает текущую сессию и редиректит на страницу логина. Вся инфраструктура существует: `POST /api/v1/auth/logout` (002, FR-011 — ревока сессии представленного refresh-поколения, параллельные сессии живы), клиент `logout(body)` в `frontend/src/api/auth.ts:139` (`LogoutRequest = { refreshToken }`), `getRefreshToken()`/`clearTokens()` в `frontend/src/auth/session.ts`; spec.md 008b не меняется

- [x] T045 Пункт «Выйти» в главном меню (MainMenuButton) — logout текущей сессии + редирект на /login (запрос пользователя). Test-First — сначала обновить/расширить тесты (убедиться в FAIL до реализации): (а) `frontend/src/chats/components/__tests__/ChatListPanel.test.tsx` — кейс «opens the «Меню» button menu with the three prototype items» (ассерт массива `['Мой профиль', 'Контакты', 'Создать групповой чат']`) расширить четвёртым пунктом `'Выйти'` в конце; (б) НОВЫЙ файл `frontend/src/chats/components/__tests__/MainMenuButton.test.tsx` (по образцу ChatGearMenu.test.tsx): рендер MainMenuButton с `onLogout`-колбэком → открытие меню → клик по пункту «Выйти» вызывает `onLogout` ровно один раз, меню закрывается; пункт — последний в `getAllByRole('menuitem')`; (в) обработчик logout (где реализован — см. ниже): mock `logout` из `../../api/auth` и `stubLocationAssign` (`frontend/src/test/location`, паттерн App.test.tsx) — успех → `logout` вызван с `{ refreshToken: <значение из localStorage> }`, затем `clearTokens()` (localStorage `webchat.auth.refreshToken` удалён) и `window.location.assign('/login')`; ошибка сети `logout` → токены всё равно очищены, редирект выполнен (best-effort revoke). Затем реализация: (1) `frontend/src/chats/components/MainMenuButton.tsx` — новый проп `readonly onLogout?: () => void` и пункт `{ label: 'Выйти', onSelect: onLogout }` ПОСЛЕДНИМ в `items` (паттерн колбэков сохраняется — кнопка без бизнес-логики, как «Мой профиль»/«Контакты»/«Создать групповой чат»); (2) `frontend/src/chats/components/ChatListPanel.tsx` — прокинуть `onLogout` через существующий проп-канал к MainMenuButton (строка ~184); (3) `frontend/src/chats/pages/MessengerPage.tsx` — обработчик рядом с колбэками `onOpenProfile`/`onCreateGroup` (~2443): `async`: `try { await logout({ refreshToken: getRefreshToken() ?? '' }) } finally { clearTokens(); window.location.assign('/login') }` — ошибка revoke (сеть/401) не блокирует локальный выход: refresh-токен удалён из localStorage, ротация недоступна, серверная сессия истечёт по TTL; редирект БЕЗ `returnTo` (auth-маршруты исключены из returnTo — FR-002 008b). Ограничения: ревока ТОЛЬКО текущей сессии (параллельные устройства живы — семантика 002 FR-011, без «выйти везде»); пункт не добавляется в кебаб чата (ChatGearMenu) и иные меню; UI-текст «Выйти» без подтверждения-диалога (не запрошено); API/бэкенд не затрагиваются. Критерии приёмки: «Выйти» — последний пункт меню menu-btn; клик → POST /auth/logout с refreshToken текущей сессии → локальные токены очищены → редирект на /login; повторный вход работает; второе устройство остаётся вошедшим. Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`; ручная проверка: два браузерных профиля → «Выйти» в первом → первый на /login, второй работает дальше

---

## Phase 11: Follow-up — вынос смены пароля из «Мой профиль» в отдельную модаль

**Purpose**: Прямой запрос пользователя: рефакторинг смены пароля через профиль — ввод старого пароля, нового и подтверждения, кнопка «Сменить пароль» переносятся в отдельное модальное окно (житель единой оболочки ModalShell по образцу «Новый групповой чат» = CreateGroupDialog); добавляется кнопка «Отмена»; все ошибки — в `.modal-err` как в «Новый групповой чат», с обработкой всех вариантов и русскими сообщениями; в «Мой профиль» добавляется кнопка «Смена пароля» (полная ширина, менее акцентная, чем «Сохранить»), открывающая модаль. Endpoint `POST /auth/password-change` и семантика 008b US2 (FR-004–FR-010) не меняются — чисто фронтендовая перекомпоновка; spec.md 008b не меняется. Важно: `problemMessage` и `validateNewPassword` сейчас выдают английские тексты/сырые коды — русификация локальна для этого флоу

- [x] T046 Вынос формы смены пароля в отдельную модаль + русификация сообщений (запрос пользователя; после T045 — общий файл `MessengerPage.tsx`). Test-First — сначала обновить/создать тесты (убедиться в FAIL до реализации): (а) переписать `frontend/src/chats/components/__tests__/PasswordChangeForm.test.tsx` под нового обитателя оболочки (далее — PasswordChangeModal): рендер полей «Текущий пароль»/«Новый пароль»/«Повторите новый пароль» (autoComplete current-password/new-password/new-password); футер `.modal-btns` = «Отмена» (`m-btn`) + «Сменить пароль» (`m-btn primary`); отправляется ровно `{currentPassword, newPassword}`; клиентская валидация ДО запроса с русскими текстами (пустой/пробельный, длина <8/>128, несовпадение, совпадение с username/email); 400 `currentPassword: invalid` → русский текст («Текущий пароль неверен»), поля сохранены, без toast; 400 `newPassword: too_common|too_short|too_long|blank|same_as_username|same_as_email` → русские формулировки; 429 + `retryAfterSec` → русское «Слишком много попыток — попробуйте через N» (склонение минут/часов); 401 → «Сессия истекла — войдите заново»; сетевой сбой/неизвестная ошибка → русский fallback; успех → `setTokenPair` + один toast «Пароль изменён» + закрытие модали (onSuccess-колбэк владельцу); «Отмена» → закрытие без запроса; все кнопки `disabled` на время отправки; ошибка — постоянная строка `.modal-err` c `role={error !== null ? 'alert' : undefined}` (паттерн CreateGroupDialog, min-height держит компоновку); (б) обновить `frontend/src/chats/components/__tests__/ProfileModal.test.tsx`: инлайн-форма пароля отсутствует; кнопка «Смена пароля» присутствует при `hasPassword === true`, полная ширина (модификатор класса в `profile-modal.css`), клик → вызов `onOpenPasswordChange`; `hasPassword === false` (JIT) → кнопка скрыта, подсказка «задать пароль через восстановление по email» со ссылкой `/forgot-password` сохранена (FR-004 008b); (в) обновить `frontend/src/auth/lib/__tests__/passwordPolicy.test.ts` под русские строки; (г) расширить `frontend/src/chats/pages/__tests__/MessengerPage.modal.test.tsx`: из «Мой профиль» по кнопке «Смена пароля» открывается житель оболочки с заголовком «Смена пароля» (MODAL_TITLES). Затем реализация: (1) НОВЫЙ `frontend/src/chats/components/PasswordChangeModal.tsx` (перенос логики PasswordChangeForm, по образцу `frontend/src/groups/components/CreateGroupDialog.tsx`: локальный state полей, submit → валидация → POST, футер «Отмена»/«Сменить пароль», `.modal-err`-строка; стили — перенос/переиспользование `profile-pass`/`profile-form` или новый класс по образцу `grp-form` в `profile-modal.css`); ЛОКАЛЬНЫЙ маппинг problem → русские сообщения (функция в модуле модали; глобальный `problemMessage` НЕ трогать — другие экраны не меняются): полевые коды 400, 401, 429+retryAfterSec (склонение минут/часов), сеть/unknown fallback «Не удалось изменить пароль. Проверьте соединение и попробуйте ещё раз»; успех → `setTokenPair` + toast + закрытие (очистка полей не нужна — модаль закрывается); (2) `frontend/src/auth/lib/passwordPolicy.ts` — перевести строки `validateNewPassword` на русский (единственный потребитель — этот флоу; тесты из (в)); (3) `frontend/src/chats/pages/MessengerPage.tsx`: `ModalFormId` + `'password-change'`, `MODAL_TITLES` + `'Смена пароля'`, слот-рендер PasswordChangeModal, открытие из ProfileModal закрывает форму профиля (единая оболочка — один житель; после «Отмена»/успеха возврат в профиль — вручную, не автоматически); (4) `frontend/src/chats/components/ProfileModal.tsx`: убрать рендер PasswordChangeForm (строка ~202), добавить кнопку «Смена пароля»: полная ширина окна профиля, менее акцентная чем «Сохранить» — вторичный стиль в духе «Отмена» (`m-btn` без `primary` + модификатор в `profile-modal.css`, высота/паддинг скромнее primary; конкретика — на усмотрение исполнителя в рамках дизайн-системы 008, органично); позиция — между readonly-строками и блоком «Сохранить»; JIT-гейтинг сохранён; (5) удалить `PasswordChangeForm.tsx` после переноса (файл поглощён модалью) вместе с импортом в ProfileModal. Ограничения: endpoint/контракт/бэкенд не меняются; поведение FR-008 008b (ротация пары, отзыв прочих сессий, toast) сохраняется; цепочка «Сохранить» профиля не затрагивается; глобальный `problemMessage` и сообщения прочих экранов — вне scope. Критерии приёмки: смена пароля доступна ТОЛЬКО из модали «Смена пароля» (заголовок оболочки), открываемой кнопкой из «Мой профиль»; все ошибки (клиентские + 400 полевые + 401 + 429 + сеть) — русские, в `.modal-err`; «Отмена» закрывает без запроса; JIT-аккаунты видят подсказку вместо кнопки; успех — toast + закрытие, пользователь остаётся вошедшим. Верификация: `pnpm --dir frontend test && pnpm --dir frontend lint && pnpm --dir frontend typecheck`; ручная проверка: профиль → «Смена пароля» → модаль по образцу «Новый групповой чат»; неверный текущий → русская ошибка; смена → toast, второе устройство требует повторный вход (FR-008)

---

## Phase 12: Follow-up — русификация ошибок в окне «Сброс пароля»

**Purpose**: Прямой запрос пользователя: ошибки в окне «Сброс пароля» (`/reset-password?token=…` — `ResetPasswordPage.tsx` + `ResetPasswordForm.tsx`, flow 002 US4) должны выводиться по-русски — тексты аналогичные окну «Смена пароля» (T046). Сейчас `ResetPasswordForm.tsx:42` использует глобальный `problemMessage` (`frontend/src/auth/problem.ts`) → английские тексты и сырые коды: «Something went wrong. Please try again.», «password: too_common», «Try again in 5 minutes». Серверные коды confirm-этапа (контракт №9): 400 uniform link failure (`InvalidRegistrationTokenException`, неизвестная/использованная/истёкшая ссылка — единый ответ); 400 field errors `password: missing|blank|too_short|too_long|too_common|same_as_username|same_as_email`, `confirmPassword: missing|mismatch` (политика FR-004, отклонённый пароль НЕ расходует ссылку — T039); 429 + `retryAfterSec` (RateLimitFilter, бакет `password-reset-confirm`). Паттерн T046: русские тексты НОВЫЙ локальный/общий маппинг, глобальный `problemMessage` НЕ трогается, прочие экраны не меняются; spec.md 008b не меняется, backend готов

- [ ] T047 Русификация ошибок confirm-этапа сброса пароля — тексты паритет с «Сменой пароля» (T046) (запрос пользователя; после T046 — извлечение общего модуля). Test-First — сначала обновить/создать тесты (убедиться в FAIL до реализации): (а) НОВЫЙ файл `frontend/src/auth/lib/__tests__/passwordProblemRu.test.ts` под общий модуль (см. ниже): маппинг кодов `password` → русские строки ДОСЛОВНО из T046 (`NEW_PASSWORD_MESSAGES`: blank → «Пароль не должен быть пустым или состоять только из пробелов», too_short → «Пароль должен быть не короче 8 символов», too_long → «Пароль должен быть не длиннее 128 символов», too_common → «Этот пароль слишком распространён — выберите другой», same_as_username → «Пароль не должен совпадать с именем пользователя», same_as_email → «Пароль не должен совпадать с email»); `formatRetryAfterRu` — склонение секунд/минут/часов (1 секунда/2 секунды/5 секунд, 1 минута/2 минуты/5 минут, 1 час/2 часа/5 часов); (б) расширить `frontend/src/auth/pages/__tests__/ResetPages.test.tsx` (ResetPasswordForm): 400 uniform link failure (title/detail `one-time token is invalid or expired`, без errors) → «Ссылка недействительна или истекла — запросите новое письмо на странице восстановления пароля»; 400 `errors.password` с каждым кодом → соответствующая русская строка паритета; 400 `errors.confirmPassword: mismatch` → «Пароли не совпадают» (базовый текст как в T046; информационный хвост «Ссылка остаётся действующей…» текущего клиентского текста сохраняется ТОЛЬКО в клиентской проверке до запроса); 429 + `retryAfterSec` → «Слишком много попыток — попробуйте через N» (склонение); сетевой сбой/неизвестная ошибка → «Не удалось сбросить пароль. Проверьте соединение и попробуйте ещё раз»; 401 НЕ кейсируется (анонимный флоу — маппинг 401 из T046 в общий модуль НЕ переносится, остаётся локальным для смены пароля); (в) регресс `frontend/src/chats/components/__tests__/PasswordChangeModal.test.tsx` — все кейсы остаются зелёными после перевода модали на общий модуль (поведение/тексты не меняются). Затем реализация: (1) НОВЫЙ общий модуль `frontend/src/auth/lib/passwordProblemRu.ts` — перенести из `PasswordChangeModal.tsx` (строки ~59, ~78–108): `NEW_PASSWORD_MESSAGES`, `formatRetryAfterRu`, `isFieldErrors`-хелпер и generic-обёртку `passwordProblemMessageRu(problem, fallback)` (400 field-codes → русские строки; 429 + retryAfterSec → русское «Слишком много попыток…»; иначе fallback) — единый источник текстов для обоих окон; (2) `frontend/src/chats/components/PasswordChangeModal.tsx` — импорт из общего модуля вместо локальных копий, ЛОКАЛЬНО остаются: 401 → «Сессия истекла — войдите заново», `currentPassword: invalid` → «Текущий пароль неверен», свой fallback «Не удалось изменить пароль…» (обёртка вызывает общий модуль, затем свои специфичные ветки); (3) `frontend/src/auth/pages/ResetPasswordForm.tsx` — ЛОКАЛЬНАЯ обёртка `resetPasswordProblemMessage(cause)`: 400 без `errors` + detail/token-семантика → «Ссылка недействительна или истекла — запросите новое письмо на странице восстановления пароля» (ссылка `/forgot-password` уже есть на странице при отсутствии токена — текст без `<a>`, plain string в `role="alert"`); `confirmPassword: mismatch|missing` и `password: missing` → русские строки; остальные через общий модуль; fallback «Не удалось сбросить пароль…»; глобальный `problemMessage` из файла убирается (единственный потребитель ошибки — эта обёртка). Acceptance: все тексты ошибок confirm-этапа сброса — русские, паритет формулировок с «Сменой пароля» дословный; клиентский текст несовпадения паролей до запроса сохранён; successes («Пароль сброшен — все сессии отозваны…») не меняются; vitest зелёный (`passwordProblemRu`, `ResetPages`, `PasswordChangeModal`, `passwordPolicy`); глобальный `auth/problem.ts` НЕ тронут. Вне scope: `SetPasswordForm.tsx` (JIT «задайте пароль», тот же английский `problemMessage`) — отдельная задача при запросе; `ForgotPasswordPage` (202 ALWAYS без user-facing ошибок, кроме 429/сети) — не трогается; backend-коды и problem+json-контракт не меняются

---

## Phase 13: Follow-up — русификация текстов ошибок на странице логина

**Purpose**: Прямой запрос пользователя: ошибки на странице логина (`/login` — `LoginPage.tsx`, флоу 001/003) должны выводиться по-русски — паттерн T046/T047 (локальный маппинг, глобальный `problemMessage` НЕ трогается). Сейчас `LoginPage.tsx:144` (парольный вход, `POST /auth/login` контракт №5) и `LoginPage.tsx:161` (SSO, `POST /auth/sso/authorize`) используют глобальный `problemMessage` → английские detail-строки и сырые коды: «Invalid credentials» (401 uniform — неверный пароль/неизвестный идентификатор/отсутствующие поля неразличимы, US2-3), «Registration is not completed: confirm your email and set a password» (403, US1-5), 400 `errors` (`identifier`/`password`: `missing` — форма `noValidate`, пустые поля уходят на сервер), 429 + `retryAfterSec` (RateLimitFilter: бакеты login ip/account + lockout-нога research.md §8; header `Retry-After` → `retryAfterSec` в `toApiProblem`, `api/auth.ts:65`); SSO-нога: 404 «Provider not found» (неизвестный/отключённый провайдер неразличимы), 502 «Identity provider is temporarily unavailable», 400 `returnTo: invalid_format`, 429 бакет `sso-authorize`. spec.md 008b не меняется, backend готов

- [ ] T048 Русификация ошибок парольного входа и SSO на `/login` (запрос пользователя; после T047 — переиспользование общего модуля). Test-First — сначала обновить/создать тесты (убедиться в FAIL до реализации): (а) расширить `frontend/src/auth/pages/__tests__/LoginPage.test.tsx`: 401 uniform (title «Unauthorized», detail «Invalid credentials») → «Неверный логин или пароль»; 403 (detail «Registration is not completed…») → «Регистрация не завершена — подтвердите email и задайте пароль» (plain string в `role="alert"`); 400 `errors.identifier: [missing]` → «Введите email или имя пользователя», 400 `errors.password: [missing]` → «Введите пароль»; 429 + `retryAfterSec` → «Слишком много попыток — попробуйте через N» (склонение минут/часов — `formatRetryAfterRu`), 429 без `retryAfterSec` → «Слишком много попыток — попробуйте позже»; сетевой сбой/неизвестная ошибка → «Не удалось войти. Проверьте соединение и попробуйте ещё раз»; SSO-кнопки: 404 → «Провайдер входа не найден или недоступен», 502 → «Сервис входа временно недоступен — попробуйте позже», 429 → «Слишком много попыток…», сетевой сбой → «Не удалось подключиться к сервису входа — попробуйте позже»; `ssoStartingId` сбрасывается, повторный клик возможен; (б) при переименовании/обобщении модуля — обновить `frontend/src/auth/lib/__tests__/passwordProblemRu.test.ts` (имена импортов, кейсы сохраняются). Затем реализация: (1) `frontend/src/auth/lib/passwordProblemRu.ts` (создан в T047) — при целесообразности переименовать в `problemRu.ts` (общие утилиты для всех auth-экранов: `formatRetryAfterRu`, `isFieldErrors`, generic-обёртка) — ЛИБО оставить имя и экспортировать утилиты; решение зафиксировать в комментарии модуля; PasswordChangeModal/ResetPasswordForm (T046/T047) при переименовании переводятся на новые импорты БЕЗ изменения текстов/поведения (их тесты — регресс, зелёные); (2) НОВЫЙ локальный маппинг в `LoginPage.tsx` — `loginProblemMessage(cause)` для парольной ноги: 401/403/400-коды/429/fallback по списку выше; клиентская проверка пустых полей ДО запроса по образцу T046 (пустой/пробельный identifier → «Введите email или имя пользователя»; пустой password → «Введите пароль» — запроса нет вовсе); 400 `returnTo` на этой ноге невозможен; (3) `ssoProblemMessage(cause)` для SSO-ноги: 404/502/429/fallback; 400 `errors.returnTo: invalid_format` → «Недопустимый адрес возврата»; глобальный `problemMessage` из импортов `LoginPage.tsx` убирается (обе ноги — только локальные обёртки); «Welcome, {username}!» (успех) и прочие существующие русские тексты не меняются. Acceptance: все ошибки обеих ног — русские; формулировки 429/fallback консистентны с T046/T047 (дословный паритет «Слишком много попыток — попробуйте через N»); клиентская проверка пустых полей предотвращает запрос; vitest зелёный (`LoginPage`, `passwordProblemRu`/`problemRu`, регресс `PasswordChangeModal` + `ResetPages`); глобальный `auth/problem.ts` НЕ тронут. Вне scope: `RegisterPage.tsx`, `ForgotPasswordPage.tsx`, `SetPasswordForm.tsx`, `ConfirmRegistrationPage.tsx`, `SsoCallbackPage.tsx` (аналогичная русификация — отдельные задачи при запросе); backend-детали и problem+json-контракт не меняются

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Нет зависимостей — старт немедленно
- **Foundational (Phase 2)**: Зависит от Phase 1 — BLOCKS задачи US2 (enum аудита V17) и US4 (колонка V17); US1/US3 от V17 не зависят, но начинаются после Phase 1
- **User Stories (Phases 3–6)**: После своих предпосылок истории независимы; приоритет P1 → P2
- **Polish (Phase 7)**: После всех завершённых историй
- **Follow-up (Phase 8)**: После Phase 7; вне утверждённого scope spec.md 008b (прямые запросы пользователя); только frontend; внутри фазы строго T041 → T042 → T043 (общие файлы `ForgotPasswordPage.tsx`/`ResetPasswordPage.tsx`/`ResetPasswordForm.tsx` + один тест-файл `ResetPages.test.tsx`)
- **Follow-up (Phase 9)**: Независима от Phase 8 (нет общих файлов; `App.tsx`/`App.test.tsx` фазой 8 не трогаются) — может выполняться параллельно с ней или после; frontend + backend-тексты писем + локальный dex-конфиг
- **Follow-up (Phase 10)**: Независима от Phase 8/9 (файлы `MainMenuButton.tsx`/`ChatListPanel*`/`MessengerPage.tsx` ими не трогаются); frontend-only, API и бэкенд готовы (logout 002)
- **Follow-up (Phase 11)**: После Phase 10 (общий файл `MessengerPage.tsx` — разные участки: пункт меню logout vs слот модали смены пароля; в одной ветке или последовательно); остальные файлы (`PasswordChangeForm*`, `ProfileModal*`, `passwordPolicy*`) фазами 8–10 не трогаются; frontend-only, endpoint 0.10.0 готов
- **Follow-up (Phase 12)**: После Phase 11 (T047 извлекает тексты T046 из `PasswordChangeModal.tsx` в общий модуль — менять после стабилизации T046, не параллельно с ним); общий тест-файл `ResetPages.test.tsx` с фазой 8 НЕ конфликтует (расширение, не перезапись); frontend-only, backend-контракт №9 и `auth/problem.ts` не трогаются
- **Follow-up (Phase 13)**: После Phase 12 (T048 переиспользует/обобщает общий модуль `passwordProblemRu.ts` из T047 — `formatRetryAfterRu` и field-хелпер; при переименовании в `problemRu.ts` регресс-прогоны тестов T046/T047); `LoginPage*` фазами 8–12 не трогался (в Phase 5/US1 менялись редиректы, не ошибки); frontend-only, backend-контракты №5 и sso-api не трогаются

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
