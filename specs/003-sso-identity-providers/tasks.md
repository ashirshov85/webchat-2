# Tasks: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Input**: Design documents from `/specs/003-sso-identity-providers/`

**Prerequisites**: plan.md ✅, spec.md ✅, research.md ✅, data-model.md ✅, contracts/sso-api.md ✅, quickstart.md ✅

**Tests**: Включены — конституция VI (Test-First) делает интеграционные тесты SSO обязательными; IT пишутся первыми в каждой фазе истории (MockIdP, research §12).

**Organization**: Задачи сгруппированы по user stories (US1–US5 из spec.md) для независимой реализации и проверки каждой истории.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность к user story (US1–US5)
- Все пути указаны от корня монорепо (`backend/`, `frontend/`, `contracts/`, `deploy/`)

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Зависимости, конфигурация, контракт — базис для всех историй

- [x] T001 Добавить `spring-boot-starter-oauth2-client` в `backend/gradle/libs.versions.toml` и `backend/build.gradle.kts` — Приёмка: `./gradlew compileKotlin` OK (workdir `backend/`); `spring-security-oauth2-client` разрешается в classpath (тест: T019/T026 — SsoFlowIT строит флоу PKCE/token exchange на компонентах стартера)
- [x] T002 [P] Добавить секцию `sso.*` в `backend/src/main/resources/application.yml`: схема провайдеров (id/display-name/enabled/trusted-for-email-linking/client-id/issuer-uri|endpoints/scopes), `flow-ttl: 10m`, `handshake-ttl: 2m`, `callback-url`; client-secret — ТОЛЬКО плейсхолдеры `${SSO_<ID>_CLIENT_SECRET}` (для провайдеров, выключенных по умолчанию, допускается форма с пустым default `${SSO_<ID>_CLIENT_SECRET:}`) (data-model §4, SC-004) — Приёмка: `./gradlew compileKotlin` OK; literal-секретов нет, только `${SSO_*}`-плейсхолдеры (структурно — отсутствие literal-секретов проверяется в рамках задачи T002; полный аудит гигиены секретов — якорь T043 в Phase 6, не блокирует отметку T002)
- [x] T003 [P] Обновить `contracts/openapi.yaml` 0.2.0 → 0.3.0 аддитивно по `specs/003-sso-identity-providers/contracts/sso-api.md`: 7 новых endpoints, схемы `SsoProvider`, `SsoProvidersResponse`, `SsoAuthorizeRequest`, `SsoLinkAuthorizeRequest`, `SsoAuthorizeResponse`, `SsoTokenRequest`, `Identity`, `IdentitiesResponse`, коды `sso_error`; проверить `vacuum lint -e contracts/openapi.yaml` и `oasdiff breaking origin/main:contracts/openapi.yaml contracts/openapi.yaml` (без breaking)
- [x] T004 Регенерировать `frontend/src/api/schema.d.ts` из контракта (`pnpm --dir frontend generate:api`); drift-check: `git diff --exit-code -- frontend/src/api/schema.d.ts` (после T003)

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Миграции, домен/порты/адаптеры, OIDC-инфраструктура — MUST быть готово до любой истории

**⚠️ CRITICAL**: Ни одна user story не может начаться до завершения фазы

**DoD фазы (конституция VI)**: задачи T005–T018 не помечаются выполненными до
зелёного якорного теста, указанного в их приёмке («тест: T…»). Якорь — первый
зелёный IT, использующий задачу: T011, T013, T014 → T019/T026 (Phase 3); T006 →
T019/T026 (применение миграций), инварианты DDL подтверждаются дополнительно в
T032/T033 (CHECK/JIT, триггер/UNIQUE). Поздние тесты (T040, T045/T046) —
дополнительные проверки: подтверждают отметку [x], не блокируют её.

Гейт фазы («ни одна user story не может начаться») = все задачи T005–T018
реализованы (код написан, `compileKotlin` OK, миграции применяются в контексте
`AbstractIntegrationTest`), а не все `[x]` проставлены: отметки задач с якорем в
Phase 3 (T006, T011, T013, T014) ставятся post-factum после зелёного T026 и
не блокируют старт Phase 3.

- [x] T005 [P] Создать миграцию `backend/src/main/resources/db/migration/V8__sso_enums.sql`: `ALTER TYPE auth_event_type ADD VALUE` × 6 (`sso_login_success`, `sso_login_failed`, `sso_identity_linked`, `sso_identity_unlinked`, `sso_account_created`, `sso_flow_error`) — отдельной миграцией до использования (data-model §6) — Приёмка: миграции применяются в контексте `AbstractIntegrationTest`, значения enum = data-model §6 (тест: T019/T026)
- [x] T006 [P] Создать миграцию `backend/src/main/resources/db/migration/V9__sso_identities.sql`: таблица `external_identities` (UNIQUE `(provider_id, subject)`, индекс по `user_id`), `sessions.auth_method varchar(16)` + `sessions.identity_id → external_identities ON DELETE SET NULL`, замена CHECK `ck_users_active_implies_credentials` → `ck_users_active_implies_email` (импликация `status<>'active' OR email_confirmed_at IS NOT NULL` — равенство нарушило бы `awaiting_password` из 002), функция+триггер `keep_at_least_one_login_method` BEFORE DELETE (data-model §6) — Приёмка: миграция применяется в контексте `AbstractIntegrationTest`; DDL = data-model §6 — UNIQUE `(provider_id, subject)`, триггер, CHECK (тест: T019/T026 — применение миграций; инварианты DDL — дополнительно T032/T033)
- [x] T007 [P] Создать `backend/src/main/kotlin/webchat/backend/config/SsoProperties.kt` — `@ConfigurationProperties("sso")` со списком провайдеров и общими настройками; fail-fast валидация на старте: формат id `^[a-z0-9][a-z0-9-]{0,63}$`, наличие clientId/secret/endpoints у включённых (research §4) — Приёмка: старт с валидной конфигурацией успешен; невалидная (пустой clientId у включённого) — fail-fast (тест: ApplicationContextRunner — невалидная конфигурация → контекст не стартует);
порядок провайдеров = порядок объявления в YAML (байндинг в LinkedHashMap — покрыть
тестом, напр. порядок id из тестовой конфигурации сохраняется)
- [x] T008 [P] Расширить `AuthEventType` шестью SSO-значениями в `backend/src/main/kotlin/webchat/backend/auth/security/AuthEvents.kt`; запись через существующий `AuthEventRecorder`, `details` — только несекретные маркеры (research §10) — Приёмка: `compileKotlin` OK; новые значения используются в T021/T029/T034
- [x] T009 [P] Создать сущность `backend/src/main/kotlin/webchat/backend/sso/domain/model/ExternalIdentity.kt` (id/userId/providerId/subject/providerEmail/providerEmailVerified/linkedAt, правила валидации — data-model §1) — Приёмка: `compileKotlin` OK; поля/валидация = data-model §1 (тест: T011)
- [x] T010 [P] Создать порт `backend/src/main/kotlin/webchat/backend/sso/domain/port/ExternalIdentityRepository.kt`: findByProviderAndSubject, findByUserId, insert, delete, updateProviderEmail — Приёмка: `compileKotlin` OK; сигнатуры покрывают запросы T011/T022/T034
- [x] T011 Реализовать адаптер `backend/src/main/kotlin/webchat/backend/sso/repository/JdbcExternalIdentityRepository.kt` на JdbcTemplate (зависит от T006, T009, T010) — Приёмка: чтение/обновление идентичностей в флоу US1 (тест: T026); insert/delete — дополнительное подтверждение (тест: T032/T033)
- [x] T012 [P] Создать порт `backend/src/main/kotlin/webchat/backend/sso/domain/port/SsoFlowStore.kt`: контекст флоу `sso:flow:<state>` (single-use, TTL 10m) и handshake `sso:handshake:<sha256(code)>` (single-use, TTL 2m) — data-model §5 — Приёмка: `compileKotlin` OK; контракт используется T013/T022
- [x] T013 Реализовать Redis-адаптер `backend/src/main/kotlin/webchat/backend/sso/repository/RedisSsoFlowStore.kt`: атомарное изъятие `GETDEL`, сериализация JSON (зависит от T012) — Приёмка: изъятие флоу/handshake в полном флоу US1 (тест: T019/T026); повторный state → отказ — дополнительное подтверждение (тест: T045)
- [x] T014 Создать `backend/src/main/kotlin/webchat/backend/sso/oidc/SsoProviderRegistry.kt`: построение `ClientRegistration` из `SsoProperties` программно, lookup по id c учётом `enabled` (зависит от T007) — Приёмка: единый 404 на authorize (тест: T026); provider_disabled на callback — дополнительное подтверждение (тест: T040)
- [x] T015 Создать `backend/src/main/kotlin/webchat/backend/sso/oidc/OidcClient.kt`: построение authorization URL с PKCE S256 + state/nonce (компоненты spring-security-oauth2-client, БЕЗ servlet-фильтров), обмен code→token, верификация ID-токена (Nimbus: подпись JWKS, `iss`/`aud`/`exp`/`nonce`, непустой уникальный `sub` — иначе отклонение флоу с событием `sso_flow_error`, spec Edge Cases), lazy discovery-кэш по issuer-uri, таймауты: общий deadline 5 с на callback — сумма вызовов провайдера (SC-005), connect 1 с / read 2 с на вызов (research §1, §5) — Приёмка: полный флоу (тест: T026); таймауты/сбои (тест: T040/T044)
- [x] T016 Расширить `backend/src/main/kotlin/webchat/backend/auth/domain/service/SessionService.kt` перегрузкой `startSession(userId, authMethod, identityId?)` (обратная совместима) и `backend/src/main/kotlin/webchat/backend/auth/repository/JdbcSessionRepository.kt` записью `auth_method`/`identity_id`; перевести парольную ветку `LoginService` на запись `auth_method='password'` — NULL остаётся только для legacy-строк 002 (research §11); формат JWT и ротация refresh — без изменений (зависит от T006 — колонки миграции V9) — Приёмка: существующие IT фичи 002 без регрессий; SSO-вход пишет `auth_method='sso'` + `identity_id` (тест: T019); парольный вход пишет `auth_method='password'` (ассерт в существующих IT 002)
- [x] T017 Добавить permitAll для `/api/v1/auth/sso/providers`, `/api/v1/auth/sso/authorize`, `/api/v1/auth/sso/callback`, `/api/v1/auth/sso/token` в `backend/src/main/kotlin/webchat/backend/config/SecurityConfig.kt` — Приёмка: 4 маршрута доступны без токена (тест: T019/T046)
- [x] T018 Создать MockIdP в `backend/src/test/kotlin/webchat/backend/sso/MockIdP.kt` — test-scope `@RestController`: authorization endpoint (302 с code), token endpoint (подписанный RSA ID-токен с управляемыми клеймами sub/email/email_verified/nonce), JWKS endpoint; управляемые сбои: HTTP 500, задержка > таймаута, неверный secret, подменённый код, отмена согласия пользователем (302 на callback с error=access_denied) (research §12) — Приёмка: MockIdP поднимается в контексте `AbstractIntegrationTest`; полный флоу (authorize → 302 с code → token endpoint → верификация JWKS) работает с управляемыми клеймами `sub/email/email_verified/nonce`; сбои (HTTP 500, задержка > таймаута, неверный secret, подменённый код, access_denied) воспроизводятся по флагу (тест: T019/T026 — первый прогон флоу через MockIdP)

**Checkpoint**: Фундамент готов — реализации исторей могут идти параллельно

---

## Phase 3: User Story 1 — Вход через внешнего провайдера с единой сессией (Priority: P1) 🎯 MVP

**Goal**: Пользователь с привязанной внешней идентичностью входит через провайдера и получает пару токенов, полностью идентичную парольной (refresh-ротация, logout, доступ к API)

**Independent Test**: SsoFlowIT с MockIdP: полный флоу → TokenPair; `POST /auth/refresh` ротирует refresh; `POST /auth/logout` отзывает оба; защищённое API доступно по токену сессии (quickstart S1)

### Tests for User Story 1 (Test-First) ⚠️

- [x] T019 [P] [US1] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoFlowIT.kt` (на базе `AbstractIntegrationTest` + MockIdP): полный флоу входа, идентичность свойств TokenPair с парольным входом, ротация refresh, отзыв logout, доступ к защищённому API, валидация `returnTo` на authorize (относительный путь → 200, не-относительный или >512 символов → 400 `errors: {returnTo: ["invalid_format"]}` без создания флоу-контекста — US1-6), параметризованная проверка доступа SSO-сессии ко **всем** защищённым endpoints, существовавшим до 0.3.0 (перечень — из `contracts/openapi.yaml` версии `origin/main` (0.2.0), исключая новые SSO-маршруты 0.3.0) — паритет с парольной сессией (SC-002); убедиться, что тесты FAIL до реализации; ассерт `auth_events`: запись `sso_login_success` с несекретным `details` (FR-011)

### Implementation for User Story 1

- [x] T020 [P] [US1] Создать DTO в `backend/src/main/kotlin/webchat/backend/sso/api/dto/SsoDtos.kt`: `SsoProvidersResponse`, `SsoAuthorizeRequest/Response`, `SsoTokenRequest` по contracts/sso-api.md §1–2, §4 — Приёмка: `compileKotlin` OK; схемы = contracts/sso-api.md §1–2, §4 (тест: T022)
- [x] T021 [US1] Реализовать `backend/src/main/kotlin/webchat/backend/sso/domain/service/IdentityResolutionService.kt` — ветка существующей идентичности (data-model §7 строки 1–2): вход в active-аккаунт, обновление `provider_email*`, событие `sso_login_success {resolution: existing_identity}` (зависит от T011, T016) — Приёмка: ветка existing_identity + обновление `provider_email*` (тест: T026)
- [x] T022 [US1] Реализовать login-флоу в `backend/src/main/kotlin/webchat/backend/sso/api/SsoController.kt`: `GET /auth/sso/providers` (только включённые, порядок конфигурации), `POST /auth/sso/authorize` (создание флоу-контекста в Redis — состав по data-model §5, вкл. `returnTo` при наличии; возврат authorizationUrl; 404 для неизвестных/выключенных), `GET /auth/sso/callback` (GETDEL state, обмен code, верификация ID-токена, резолвинг, handshake-код, 302 на SPA `/sso/callback`), `POST /auth/sso/token` (GETDEL handshake → `SessionService.startSession` → LoginResponse; 400 `invalid_code`) (зависит от T013–T021) — Приёмка: поведение 4 endpoint'ов = contracts/sso-api.md §1–4: providers — только включённые, порядок конфигурации; authorize — 404 для неизвестных/выключенных, 400 `errors: {returnTo: ["invalid_format"]}` для не-относительного пути или длины >512 символов; флоу-контекст содержит `returnTo` (data-model §5); callback — GETDEL state (повтор → отказ до эффектов), 302 с handshake; token — 200 LoginResponse / 400 `invalid_code` (тест: T026)
- [x] T023 [P] [US1] Добавить типизированные клиенты в `frontend/src/api/sso.ts` (providers/authorize/token из schema.d.ts) и кнопки включённых провайдеров в `frontend/src/auth/pages/LoginPage.tsx` (переход `window.location.assign(authorizationUrl)`, перед переходом — сохранение `returnTo` в `sessionStorage` (contracts/sso-api.md §2)) — Приёмка: `tsc` strict OK; кнопки рендерятся из `GET /auth/sso/providers`, клик → `window.location.assign(authorizationUrl)`; при заданном returnTo — сохранён в sessionStorage до перехода (тест: T025)
- [x] T024 [US1] Создать `frontend/src/auth/pages/SsoCallbackPage.tsx` + маршрут `/sso/callback`: обмен handshake-code через `POST /auth/sso/token`, редирект в чат, отображение `sso_error`, применение `returnTo` из `sessionStorage` после успешного обмена (fallback `/chat`, очистка после применения) (зависит от T023) — Приёмка: маршрут `/sso/callback`: обмен кода через `POST /auth/sso/token` → редирект в чат; при `sso_error` — экран ошибки без обмена; returnTo из sessionStorage применяется как путь редиректа, при отсутствии — /chat (тест: T025)
- [x] T025 [P] [US1] Добавить Vitest + Testing Library тесты в `frontend/src/auth/pages/__tests__/` для LoginPage (список провайдеров, клик → redirect, пустой список провайдеров → SSO-секция скрыта) и SsoCallbackPage (обмен кода, ошибка), а также кейсы returnTo (сохранение до перехода, применение после обмена)
- [x] T026 [US1] Довести `SsoFlowIT` до зелёного; проверить сценарии US1-1..US1-6 (US1-5: отмена согласия у провайдера → понятная ошибка, состояние аккаунта не меняется; US1-6: returnTo — относительный путь принимается на authorize и применяется клиентом после обмена — T024/T025, не-относительный → 400 `invalid_format` — T019/T022)

**Checkpoint**: User Story 1 полностью функционален и проверяем независимо — MVP

---

## Phase 4: User Story 2 — Первый вход: автосвязывание или JIT-создание аккаунта (Priority: P1)

**Goal**: Первый вход через IdP с verified email: JIT-аккаунт при отсутствии аккаунта, автосвязывание с активным аккаунтом для trusted-провайдеров, отказы без side-effects в остальных случаях

**Independent Test**: SsoIdentityResolutionIT с MockIdP: verified email без аккаунта → активный аккаунт с привязкой; verified email активного аккаунта + trusted → вход в него; unverified email → ничего не создаётся (quickstart S2–S3)

### Tests for User Story 2 (Test-First) ⚠️

- [x] T027 [P] [US2] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoIdentityResolutionIT.kt`: JIT (US2-1; отдельный кейс US2-7 — JIT через **не**-trusted провайдера: allowlist требуется только для автосвязывания, FR-004), автосвязывание trusted (US2-2), отказы unverified/email_conflict (US2-3), pending- И awaiting_password-аккаунт → `registration_incomplete` без side-effects (US2-4, data-model §7 строка 6), парольный вход JIT → 401 (US2-5), конфликт username (US2-6), два trusted-провайдера с одним подтверждённым email → обе идентичности на одном аккаунте, второй вход без создания дубля (spec Edge Cases, FR-003), JIT-аккаунт → password-reset (письмо в тестовый outbox) → задание пароля → парольный вход 200 (вторая часть FR-013); перед сбросом пароля создать SSO-сессию → после задания пароля старая сессия и её refresh отозваны (FR-002 «полный отзыв при смене пароля»); FAIL до реализации; ассерты `auth_events`: `sso_account_created` при JIT, `sso_login_failed` на отказных ветках, без секретов/токенов (FR-011)

### Implementation for User Story 2

- [x] T028 [P] [US2] Создать `backend/src/main/kotlin/webchat/backend/sso/domain/service/UsernameGenerator.kt`: local-part email → lowercase → `[a-z0-9._-]` → ≤32 → паттерн 002 (префикс `user` при невыполнении); коллизии: `-2..-99`, затем `-<4 random>`; вставка с ретраем при unique violation ≤5 попыток (research §7) — Приёмка: генерация/коллизии username (тест: T027, US2-6)
- [x] T029 [US2] Расширить `backend/src/main/kotlin/webchat/backend/sso/domain/service/IdentityResolutionService.kt` полной матрицей первого входа (data-model §7 строки 3–8): `email_not_verified` / auto_linked (trusted + verified, lower()-канонизация email) / `email_conflict` (не trusted) / `registration_incomplete` (pending и awaiting_password) / JIT (active-аккаунт без пароля, `email_confirmed_at=now()`, письмо не отправляется); события `sso_account_created`, `sso_login_failed`; идемпотентность FR-012 (зависит от T028) — Приёмка: полная матрица первого входа (тест: T032)
- [x] T030 [US2] Добавить guard в `backend/src/main/kotlin/webchat/backend/auth/domain/service/LoginService.kt`: `password_hash IS NULL` → fictitious hash → uniform 401 (FR-013, без различения JIT-аккаунтов) — Приёмка: парольный вход для `password_hash IS NULL` → uniform 401, неотличимый от неверного пароля; существующие IT фичи 002 без регрессий (тест: T027, US2-5)
- [ ] T031 [US2] Доработать сообщения об ошибках первого входа в `frontend/src/auth/pages/SsoCallbackPage.tsx`: `email_not_verified`/`email_conflict` → понятное сообщение + CTA «войти по паролю (или зарегистрироваться) и привязать провайдера в настройках»; `registration_incomplete` → понятное сообщение + CTA «завершить регистрацию по ссылке из письма» (US2-4, FR-003: у pending/awaiting_password-аккаунта пароля нет) — Приёмка: `email_not_verified`/`email_conflict` → сообщение + CTA «войти по паролю (или зарегистрироваться) и привязать провайдера в настройках»; `registration_incomplete` → сообщение + CTA «завершить регистрацию по ссылке из письма» (тест: дополнить `frontend/src/auth/pages/__tests__/` кейсом каждого кода; вручную — quickstart S3 шаг 4 для email_conflict; email_not_verified/registration_incomplete — только frontend-тестами)
- [ ] T032 [US2] Довести `SsoIdentityResolutionIT` до зелёного; проверить отсутствие дублей аккаунтов/привязок при повторных callback (FR-012)

**Checkpoint**: US1 и US2 работают независимо — полный P1-сценарий бесшовного входа

---

## Phase 5: User Story 3 — Ручное управление привязками внешних IdP (Priority: P2)

**Goal**: Аутентифицированный пользователь привязывает/отвязывает провайдеров через полный флоу у IdP; запрет удаления последнего способа входа; сессии при отвязке не отзываются

**Independent Test**: SsoLinkingIT: привязка → идентичность в списке и вход работает; отвязка → вход отклоняется; отвязка последнего способа → 409 `last_login_method` (quickstart S4)

### Tests for User Story 3 (Test-First) ⚠️

- [ ] T033 [P] [US3] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoLinkingIT.kt`: link-флоу (US3-1), повторный link-флоу уже привязанной своей идентичности → no-op success без дубля привязки и без `sso_identity_linked`-события (FR-012, data-model §7 Link), `identity_taken` без деталей владельца (US3-2), отвязка при наличии пароля + повторный вход по правилам первого входа: не-trusted → `email_conflict`, trusted с совпадающим verified email → повторная автопривязка и успешный вход (US3-3), 409 на последнюю привязку JIT-аккаунта (US3-4), email провайдера ≠ email аккаунта (US3-5), сессии живут после отвязки (US3-6), параллельная отвязка двух привязок JIT-аккаунта без пароля → ровно одна 204, вторая 409 `last_login_method` (гонка закрывается DB-триггером), параметризованный доступ SSO-сессии к новым Bearer-маршрутам 0.3.0 (/users/me/identities, link/authorize) — паритет SC-002; FAIL до реализации; ассерты `auth_events`: `sso_identity_linked` / `sso_identity_unlinked` (FR-011)

### Implementation for User Story 3

- [ ] T034 [US3] Реализовать `backend/src/main/kotlin/webchat/backend/sso/domain/service/IdentityLinkService.kt`: link (purpose=link, идентичность свободна → привязка; своя → no-op success; чужая → `identity_taken`), unlink (владелец = текущий пользователь иначе 404; app-проверка «не последний способ входа» + маппинг DB-триггера → 409 `last_login_method`), события `sso_identity_linked`/`sso_identity_unlinked`, email аккаунта не меняется (data-model §7 Link/Unlink; зависит от T011) — Приёмка: link/unlink/guard последнего способа входа (тест: T039)
- [ ] T035 [US3] Реализовать `backend/src/main/kotlin/webchat/backend/sso/api/SsoIdentitiesController.kt`: `POST /auth/sso/link/authorize` (Bearer, purpose=link), `GET /users/me/identities` (порядок по linkedAt, `providerDisplayName` из конфига), `DELETE /users/me/identities/{identityId}` (204/404/409); link-ветка callback → 302 `/settings/security?linked=<providerId>` (зависит от T022, T034) — Приёмка: поведение = contracts/sso-api.md §5–7 (тест: T033)
- [ ] T036 [P] [US3] Добавить клиенты link/list/delete в `frontend/src/api/sso.ts` — Приёмка: `tsc` strict OK; используются в T037 (тест: T038)
- [ ] T037 [US3] Создать `frontend/src/settings/pages/SecurityPage.tsx` + маршрут `/settings/security` в `frontend/src/App.tsx` (`renderRoute`, вручную разбирает query: `?linked=<providerId>` → сообщение об успехе, `?sso_error=<code>` → экран ошибки — contracts/sso-api.md §3): список привязок (провайдер, email, дата), кнопки «Привязать провайдера» и «Отвязать» с обработкой 409 (предложение задать пароль) (зависит от T036) — Приёмка: маршрут `/settings/security` открывает страницу (в т.ч. с `?linked=`/`?sso_error=`); сценарии списка/привязки/отвязки/409 (тест: T038)
- [ ] T038 [P] [US3] Добавить Vitest-тесты в `frontend/src/settings/pages/__tests__/` для SecurityPage (список, привязка, отвязка, last-method ошибка; кейсы `?linked=` и `?sso_error=`)
- [ ] T039 [US3] Довести `SsoLinkingIT` до зелёного

**Checkpoint**: US1–US3 независимо функциональны

---

## Phase 6: User Story 4 — Конфигурация нескольких провайдеров, секреты в secret manager (Priority: P2)

**Goal**: Одновременная работа ≥2 провайдеров; выключение скрывает провайдера без влияния на привязки и другие способы входа; сбой конфигурации одного не влияет на остальных; секреты только в env/secret manager

**Independent Test**: SsoResilienceIT: при ≥2 провайдерах оба в перечне и работают; недоступность одного → понятная ошибка ≤5s, остальные и пароль работают; аудит секретов (quickstart S5)

### Tests for User Story 4 (Test-First) ⚠️

- [ ] T040 [P] [US4/US5] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoResilienceIT.kt` с двумя провайдерами на MockIdP: оба включены и работают (US4-1), выключенный → скрыт + 404 authorize + `provider_disabled` callback (US4-3), сбой/таймаут/500/неверный secret одного → `provider_error` ≤5s при работающих остальных и парольном входе (US4-4, SC-005), провайдер с недоступным endpoint (token-uri на закрытый порт) → `provider_error` ≤5 с (US4-4 «недоступные endpoints»); FAIL до реализации

### Implementation for User Story 4

- [ ] T041 [US4] Доработать обработку состояний провайдеров в `backend/src/main/kotlin/webchat/backend/config/SsoProperties.kt` и `backend/src/main/kotlin/webchat/backend/sso/oidc/SsoProviderRegistry.kt`: выключенный/неизвестный → единый 404 на authorize; `provider_disabled` на callback при выключении между authorize и callback; изоляция ошибок по провайдерам — Приёмка: состояния провайдеров и изоляция (тест: T040/T044)
- [ ] T042 [US4] Создать локальный dex IdP в `deploy/local/dex/` (docker-compose профиль `sso`, порт 5556): конфиг со статическими пользователями `alice@example.com` (S1), `bob@example.com` (S2 — JIT), `carol@example.com` (S3 — автосвязывание), `dave@example.com` (S3 шаг 4 — email_conflict), redirect-uri `http://localhost:8080/api/v1/auth/sso/callback` (quickstart «Предусловия») и staticClient `webchat` с dev-only dummy-секретом `dev-secret` (= env `SSO_DEX_CLIENT_SECRET`; документированное исключение SC-004 — spec Assumptions); добавить блок `sso.providers.dex.*` в `backend/src/main/resources/application.yml`: `enabled: ${SSO_DEX_ENABLED:false}` (opt-in — запуск без SSO-env не падает на fail-fast T007; дефолт схемы `true` из data-model §4 переопределяется для dex), явные endpoints dex, `client-id: webchat`, `trusted-for-email-linking: true` (S3), secret — плейсхолдер с пустым default `${SSO_DEX_CLIENT_SECRET:}` (валидация T007 требует непустой secret только у включённых) (зависит от T002 — дополняет созданную им секцию `sso.*` в application.yml) — Приёмка: `docker compose -f deploy/local/docker-compose.yml --profile sso up -d` поднимает dex на :5556; вход каждого статического пользователя возможен; redirect-uri совпадает с `sso.callback-url`; в конфиге dex — только dummy-секрет из Assumptions; `SSO_DEX_ENABLED=true SSO_DEX_CLIENT_SECRET=dev-secret ./gradlew bootRun` (workdir `backend/`) поднимает backend с видимым провайдером dex; запуск без `SSO_DEX_ENABLED` стартует без провайдера dex (fail-fast не срабатывает) (проверка: quickstart «Настройка и запуск», T053)
- [ ] T043 [US4] Проверить гигиену секретов (SC-004): `rg -i "client.?secret" --hidden -g '!node_modules' -g '!deploy/local/dex/**'` → только плейсхолдеры `${SSO_*_CLIENT_SECRET}`; в `deploy/local/dex/` — только документированный dummy `dev-secret` (spec Assumptions); добавить в `.gitleaks.toml` узкий path-allowlist `deploy/local/dex/.*` (создание — часть задачи) и прогнать gitleaks (документированное dev-исключение — spec Assumptions, SC-004); убедиться, что allowlist ограничен путём `deploy/local/dex/.*` и не содержит иных путей; убедиться, что секреты отсутствуют в логах backend и `auth_events.details` (частично зависит от T042 — аудит содержимого `deploy/local/dex/`) — Приёмка: gitleaks зелёный (находки только внутри allowlist); `rg`-проверка без изменений
- [ ] T044 [US4/US5] Довести `SsoResilienceIT` до зелёного; проверить фиксацию сбоев провайдера в observability (лог с trace_id, `sso_flow_error`)

**Checkpoint**: US1–US4 независимо функциональны

---

## Phase 7: User Story 5 — Защита SSO-флоу и устойчивость при сбоях провайдера (Priority: P2)

**Goal**: Rate limiting на 5 SSO-маршрутах — 4 публичных + аутентифицированный link/authorize, консистентно при масштабировании (FR-009); защита от CSRF/replay; graceful-обработка сбоев; аудит всех SSO-событий без секретов

**Independent Test**: SsoSecurityIT + SsoRateLimitIT: сверхлимитные запросы → 429 + Retry-After; повторённый/подделанный callback отклоняется без создания сессии (quickstart S6); недоступность провайдера (US5-3, SC-005) — SsoResilienceIT фазы US4 (T040/T044)

### Tests for User Story 5 (Test-First) ⚠️

- [ ] T045 [P] [US5] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoSecurityIT.kt`: повторное использование state (US5-2), подменённый state/nonce, чужая вкладка, повторный callback → отказ до любых эффектов, сессии/привязки не создаются (SC-006), повторный `POST /auth/sso/token` с тем же handshake-кодом → 400 `invalid_code` без дубля сессии (FR-012, single-use GETDEL), ID-токен без `sub` (пустой клейм через MockIdP) → отклонение + `sso_flow_error`, ротация client secret провайдера между authorize и callback (смена тестовой конфигурации) → callback отклоняется, сессия не создаётся (spec Edge Cases «секрет скомпрометирован/ротирован»); FAIL до реализации
- [ ] T046 [P] [US5] Написать `backend/src/test/kotlin/webchat/backend/sso/SsoRateLimitIT.kt`: лимиты на 5 SSO-маршрутов — 4 публичных (providers 30/1m, authorize 10/1m, callback 30/1m, token 30/1m) + аутентифицированный Bearer-маршрут link/authorize 10/1m → 429 + `Retry-After ≥ 1` (US5-1, SC-007; сверх минимума FR-009; значения = research §9 — нормативный источник, при рассинхронизации правится research и синхронно contracts §1–5 и quickstart S6.2); FAIL до реализации

### Implementation for User Story 5

- [ ] T047 [US5] Расширить `backend/src/main/kotlin/webchat/backend/auth/ratelimit/RateLimitFilter.kt` и `AuthRateLimitProperties` таблицей SSO-маршрутов, включая поддержку GET-маршрутов (сейчас только POST); те же 429 + Retry-After problem+json, Redis ProxyManager, fail-open (research §9) — Приёмка: 429 + Retry-After на 5 маршрутах со значениями = research §9, GET поддержан (тест: T046/T049)
- [ ] T048 [P] [US5] Добавить метрики Micrometer `sso_flow_total{provider, outcome}` и `sso_idp_call_duration{provider, kind}` в `backend/src/main/kotlin/webchat/backend/sso/` (research §14); `/actuator/prometheus` отражает sso-события — Приёмка: `/actuator/prometheus` содержит `sso_flow_total` (тест: quickstart S6; сквозная проверка в T049/T052)
- [ ] T049 [US5] Довести `SsoSecurityIT` и `SsoRateLimitIT` до зелёного

**Checkpoint**: Все user stories независимо функциональны

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные улучшения и финальная валидация

- [ ] T050 [P] Расширить `load/k6/auth.smoke.js` SSO-сценариями: providers → authorize → token-error (полный флоу в smoke невозможен без реального IdP — задокументировано в скрипте) (research §12) — Приёмка: `k6 run load/k6/auth.smoke.js` зелёный с SSO-сценариями (providers 200; authorize 200/404; token-error 400), 0×5xx; ограничение полного флоу задокументировано в скрипте (проверка: quickstart «Автоматизированные проверки»)
- [ ] T051 [P] Финальный аудит безопасности — сверка с чек-листом конституции V (без повтора прогонов T043/T045): подтверждены зелёные T043 и T045; спот-проверка `contracts/openapi.yaml` и `backend/src/main/kotlin/webchat/backend/sso/`: `rg -i "client.?secret"` → только плейсхолдеры `${SSO_*}`; в `backend/src/main/kotlin/webchat/backend/sso/oidc/OidcClient.kt` в построении authorization URL присутствуют state/nonce/PKCE S256 — Приёмка: чек-лист конституции V пройден без замечаний (результат фиксируется в PR-описании)
- [ ] T052 Полный прогон валидации (quickstart «Автоматизированные проверки»): `./gradlew check` (workdir `backend/`), `pnpm --dir frontend test && pnpm --dir frontend lint`, `pnpm --dir frontend generate:api` + drift-check, `vacuum lint`, `oasdiff breaking` — все зелёные
- [ ] T053 Ручная проверка по `specs/003-sso-identity-providers/quickstart.md` сценарии S1–S6 с локальным dex (docker compose `--profile sso`): единая сессия, JIT, автосвязывание, привязки, несколько провайдеров, защита флоу; S5 требует преднастройки (quickstart S5.1): staticClient `webchat2` в `deploy/local/dex/`, блок `sso.providers.dex2.*` в `application.yml` (`enabled: ${SSO_DEX2_ENABLED:false}`, секрет — `${SSO_DEX2_CLIENT_SECRET:}`), env `SSO_DEX2_ENABLED=true SSO_DEX2_CLIENT_SECRET=dev-secret` — шаг входит в сценарий S5 (T042 предсоздаёт только dex; мульти-провайдерность автоматически покрыта T040)

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: без зависимостей — старт немедленно
- **Foundational (Phase 2)**: зависит от Phase 1 — БЛОКИРУЕТ все истории
- **User Stories (Phases 3–7)**: все зависят от Phase 2
  - Могут идти параллельно (при наличии ресурсов) или последовательно по приоритету (US1 → US2 → US3 → US4 → US5)
- **Polish (Phase 8)**: зависит от завершения всех историй

### User Story Dependencies

- **US1 (P1)**: после Phase 2; старт immediately — вход через существующую идентичность
- **US2 (P1)**: после Phase 2; расширяет `IdentityResolutionService` из US1 (T029 поверх T021) — рекомендуется после US1, независимо тестируема
- **US3 (P2)**: после Phase 2; использует callback/login-механику US1 (T035 поверх T022), независимо тестируема
- **US4 (P2)**: после Phase 2; независима (конфигурация/изоляция), тестируется с MockIdP
- **US5 (P2)**: после Phase 2; rate limiting поверх конечного набора SSO-маршрутов (US1/US3 endpoints), тесты независимы

### Within Each User Story

- Тесты пишутся ПЕРВЫМИ и FAIL до реализации (конституция VI)
- Модели/порты → сервисы → контроллеры → фронтенд
- История завершена checkpoint-проверкой перед следующей

### Parallel Opportunities

- T002, T003 параллельны между собой и не зависят от T001; T004 — строго после T003
- T005–T010, T012 (разные файлы) параллельны внутри Phase 2
- T019 / T027+T028 / T033 / T040 / T045+T046 — тесты разных исторей параллельны после Phase 2
- Внутри истории: модели/DTO/фронтенд-клиенты [P] параллельны
- Разные истории — разные разработчики после Phase 2

---

## Parallel Example: User Story 1

```bash
# Тесты и независимые файлы US1 параллельно:
Task: "T019 [US1] SsoFlowIT в backend/src/test/kotlin/webchat/backend/sso/SsoFlowIT.kt"
Task: "T020 [US1] DTO в backend/src/main/kotlin/webchat/backend/sso/api/dto/SsoDtos.kt"
Task: "T023 [US1] frontend/src/api/sso.ts + LoginPage.tsx"

# Затем последовательно: T021 (IdentityResolutionService) → T022 (SsoController) → T024 (SsoCallbackPage) → T026 (зелёный IT)
```

## Parallel Example: User Story 5

```bash
Task: "T045 [US5] SsoSecurityIT"
Task: "T046 [US5] SsoRateLimitIT"
Task: "T048 [US5] Метрики Micrometer"

# Затем: T047 (RateLimitFilter) → T049 (зелёные IT)
```

---

## Implementation Strategy

### MVP First (User Story 1)

1. Complete Phase 1: Setup (зависимости + контракт 0.3.0)
2. Complete Phase 2: Foundational (миграции, порты, OidcClient, MockIdP)
3. Complete Phase 3: User Story 1 — вход через провайдера с единой сессией
4. **STOP and VALIDATE**: SsoFlowIT зелёный, quickstart S1 вручную
5. Deploy/demo при готовности

### Incremental Delivery

1. Setup + Foundational → фундамент
2. US1 → MVP: равноправный вход через IdP
3. US2 → бесшовный первый вход (JIT/автосвязывание) — завершает P1-ценность
4. US3 → ручные привязки в настройках
5. US4 → мульти-провайдерность + секреты
6. US5 → защита и устойчивость
7. Polish → k6, аудит, полный прогон, quickstart

### Parallel Team Strategy

1. Команда вместе завершает Phase 1–2
2. После Phase 2:
   - Developer A: US1 → US2 (домен резолвинга)
   - Developer B: US3 → US5 (привязки, защита)
   - Developer C: US4 + фронтенд-интеграция
3. Polish — совместно

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач; маркер информационный
- При реализации одним агентом (OpenCode; конституция, «Development Workflow») задачи
  выполняются строго последовательно в порядке файла — Parallel-стратегии применимы
  только к командной работе
- [Story] метки связывают задачи с US1–US5 из spec.md
- Тесты каждой истории писать первыми, проверять FAIL перед реализацией
- Коммит после каждой задачи или логической группы
- DoD каждой задачи (конституция, «Development Workflow»): зелёные тесты задачи
  + линт/форматирование + обновлённый API-контракт (если затронут) + пройденный
  `/speckit.checklist`; отметка `[x]` в tasks.md ставится только после DoD
- Останавливаться на checkpoint историй для независимой валидации
- Секреты — только `${SSO_*}` плейсхолдеры (SC-004, gitleaks в CI)
- Формат access-JWT и модель ротации refresh из 002 не меняются (FR-002, конституция IV)
