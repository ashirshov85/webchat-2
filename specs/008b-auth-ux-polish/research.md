# Research: Полировка auth-флоу и управление паролем

**Feature**: `008b-auth-ux-polish` | **Date**: 2026-10-11
Источник ввода: `spec.md` (clarify от 2026-10-11), `plan.md` §Technical Context (NEEDS
CLARIFICATION 1–6), исследование кодовой базы фич 001–008a. Все ссылки `file:line`
проверены по текущему состоянию ветки.

---

## 1. Seed системного аккаунта admin (NC-1)

**Decision**: `AdminSeedRunner : ApplicationRunner` в новом пакете
`webchat.backend.auth.onboarding`, выполняется на каждом старте приложения, вставка
`INSERT … ON CONFLICT DO NOTHING` (без arbiter — unique-индексы `users` являются
expression-index по `lower(username)`/`lower(email)`, V1__users.sql:23-24, arbiter по
constraint недоступен). Гейт `admin.seed.enabled` (default `true`). После вставки —
`SELECT id … WHERE lower(username) = 'admin'`: `NULL` ⇒ username/email занят ⇒
`log.warn` + счётчик, старт не ломается.

Атрибуты seed-аккаунта (форма = JIT-аккаунт 003, проходит `ck_users_active_implies_email`
V9__sso_identities.sql:37-38):

| Поле | Значение |
|---|---|
| username | `admin` (проходит `ck_users_username_format`, V1:17-18) |
| email | `admin.seed.email`, env `ADMIN_SEED_EMAIL`, dev-умолчание `admin@webchat.local` |
| password_hash | env `ADMIN_SEED_PASSWORD` → Argon2id в рантайме (`PasswordEncoder`, SecurityConfig.kt:37-39); env отсутствует → `NULL` (беспарольный активный аккаунт) |
| status / email_confirmed_at | `active` / `now()` |
| password_set_at | `now()` только если пароль задан |
| display_name | `Administrator` (V16, CHECK 1–64) |
| starter_contact_added | `TRUE` — сам admin никогда не является целью онбординга (плюс self-guard ContactService.kt:75) |

Пароль seed'у задаётся через env/secret; **беспарольная форма** — штатный путь: логин по
паролю остаётся timing-safe 401 (LoginService.kt:118-124), а пароль задаётся существующим
флоу 002 (`PasswordResetService.requestPasswordReset` требует только `ACTIVE`,
PasswordResetService.kt:74-78; локально письмо падает в Mailpit). В k8s env подводится
секретом `admin-seed` (optional ref для пароля), overlay
`deploy/k8s/overlays/dev/backend-auth-env.yaml`; docker-compose не меняется (бэкенд в нём
не сервис, только инфраструктура PG/Redis/Mailpit/dex).

**Ротация пароля admin**: seed задаёт пароль только при создании строки и никогда не
перезаписывает его (UPDATE в `AdminSeedRunner` отсутствует); дальнейшая ротация —
штатными флоу: смена из «Мой профиль» (008b, FR-004–FR-008) или восстановление по
email-ссылке 002 (доступно и беспарольной JIT-форме). Смена значения секрета
`ADMIN_SEED_PASSWORD` в окружении не влияет на существующую строку admin — только на
свежие развёртывания без неё. Этим параграфом закрывается assumption spec.md о фиксации
«способа задания и ротации пароля» на этапе планирования.

Конкурентный старт 2 реплик (rolling deploy, `replicas: 2`): вставка реплики B блокируется
на индексной записи реплики A до её commit, затем уходит в DO NOTHING — дедлок-профиль
отсутствует, advisory-locks не нужны. Существующий username «admin» не перезаписывается
никогда (UPDATE не выполняется) — FR-012.

**Rationale**: конституция V — секреты только в env/secret manager: фиксированный хеш в
Flyway SQL = секрет в VCS; Java-callback Flyway теряет DI-удобства Spring. Runner даёт
`PasswordEncoder`, `@ConfigurationProperties`, метрики; повторные запуски дёшевы и
ре-алертят пропажу аккаунта.

**Alternatives**: (a) `afterMigrate` SQL с хешем — отвергнут (секрет в репозитории);
(b) Flyway Java callback — отвергнут (вне Spring-контекста, смешение миграций и рантайма);
(c) «пароль по умолчанию захардкожен» — отвергнут (конституция V).

## 2. Событие завершения регистрации (NC-2)

**Decision**: Spring `ApplicationEvent` + `@TransactionalEventListener(phase = AFTER_COMMIT)`
+ `@Async`. Событие `RegistrationCompletedEvent(userId: UUID)` публикуется **внутри**
транзакции в двух точках создания активных аккаунтов («единая точка» по Assumptions):

1. `RegistrationService.setPassword` — после активирующего write
   (RegistrationService.kt:241, рядом с журналом `PASSWORD_SET`);
2. `IdentityResolutionService.provisionJitAccount` — после insert + `bindIdentity`
   (IdentityResolutionService.kt:285-312, рядом с `SSO_ACCOUNT_CREATED`).

Слушатель `StarterContactEventListener` (`auth/onboarding`) вызывает
`StarterContactService.ensureOnboarded(userId)` в новой `@Transactional`; всё тело —
best-effort `try/catch` c `log.warn` + счётчиком (асинхронный void никогда не роняет
вызывающего). `@EnableAsync` добавляется в `BackendApplication.kt:8` (рядом с
`@EnableScheduling`); executor — автоконфигурация Boot на виртуальных потоках
(`spring.threads.virtual.enabled: true`, application.yml:6-8), новых stateful-элементов
нет (конституция II). Замечание к наблюдаемости: MDC `traceId` в `@Async`-пул не
попадает — слушатель логирует `user_id={}` явно; `auth_events` для онбординга не
используется (это журнал auth-событий с `trace_id`, не onboarding).

**Rationale**: потеря доставки покрыта self-healing fallback (§3) — это зафиксировано
clarify (spec.md:18), durable-очередь не требуется (YAGNI, конституция VII).

**Alternatives**: (a) reuse `email_outbox` — отвергнут: таблица семантически SMTP-очередь
(`email_type` PG enum, EmailTemplates/EmailGateway), потребовались бы extension enum +
не-email ветка поллера + фейковый шаблон; (b) синхронный вызов после commit — отвергнут
clarify («асинхронно по событию»); (c) Redis Pub/Sub — отвергнут: не durable (реалтайм-fanout
004), выигрыша перед AFTER_COMMIT-событием нет.

## 3. Self-healing fallback и дискриминатор «новый пользователь» (NC-2)

**Decision**: колонка `users.starter_contact_added BOOLEAN NOT NULL DEFAULT FALSE`
(миграция V17, прецедент V15__presence_settings.sql:2). V17 тут же выполняет
`UPDATE users SET starter_contact_added = TRUE` — все существующие на момент деплоя
аккаунты помечаются «обработанными» **без добавления контактов** (бэкфилла нет,
clarify spec.md:16).

`StarterContactService.ensureOnboarded(userId)`, вызывается из `ContactController.list`
(ContactController.kt:76-77, сразу после `ownerId = callerId(...)`, до выборки):
- точечное чтение флага по PK → `TRUE` ⇒ выход (вечное short-circuit после онбординга);
- `FALSE` ⇒ в одной `@Transactional`: идемпотентный `INSERT … ON CONFLICT DO NOTHING`
  (JdbcContactRepository.kt:46-59, INSERT_SQL :129-134) + идемпотентный
  `UPDATE … SET starter_contact_added = TRUE WHERE id = ? AND NOT starter_contact_added`.
  Гонки «событие vs фолбэк vs повторная доставка» схлопываются в `ON CONFLICT DO NOTHING`
  + условный UPDATE (0 дублей — SC-003);
- admin не резолвится (seed пропущен/провален) ⇒ флаг остаётся `FALSE`, `log.warn` +
  счётчик — повтор на следующем запросе контактов (edge spec.md:108);
- всё тело — best-effort `try/catch`: фолбэк никогда не ломает выдачу контактов.

Сценарий «пользователь удалил admin» (spec.md:92): флаг уже `TRUE`, контакта нет —
повторного добавления нет. Удаление контакта флаг не сбрасывает никогда.

**Rationale**: одна колонка закрывает три требования: отсутствие бэкфилла, идемпотентность
фолбэка, запрет re-add после удаления. Размещение в контроллере (оркестратор) + сервисе
(правила) сохраняет `ContactService` (004) нетронутым.

**Alternatives**: (a) `created_at >= дата деплоя` — отвергнуто (хрупко, часовые пояса);
(b) отдельная таблица онбординга — отвергнута (лишняя сущность, YAGNI); (c) проверка
существования контакта вместо флага — отвергнута: не отличает «не добавляли» от «добавили
и удалили» → нарушала бы сценарий 5.

## 4. Признак «аккаунт без пароля» в контракте (NC-3)

**Decision**: аддитивное поле `hasPassword: boolean` (required) **только** в ответе
`GET /users/me` — inline `allOf`-расширение над `$ref PublicUser` (прецедент:
login-response, openapi.yaml:346-366). Kotlin: новый DTO `MeUserResponse` (поля
`PublicUserResponse` + `hasPassword`), возвращается только `UsersController.me`
(UsersController.kt:69-72). `LoginResponse` не расширяется.

**Rationale**: `PublicUser` шарится между peer/search/group-member поверхностями
(openapi.yaml:3055) — поле внутри `PublicUser` раскрывало бы факт JIT-происхождения
аккаунта **другим** пользователям. Профиль-форме признак нужен в произвольный момент
после входа, не только в момент логина.

**Alternatives**: (a) `hasPassword` в `PublicUser` — отвергнуто (утечка по чужим
поверхностям); (b) `hasPassword` в `LoginResponse` — отвергнуто (недостаточно: форма
открывается в любой момент сессии); (c) отдельный `GET /users/me/password-status` —
отвергнут (лишний round-trip, YAGNI).

## 5. Endpoint смены пароля: контракт и семантика сессий (NC-5)

**Decision**: `POST /api/v1/auth/password-change`, Bearer (`security: [{bearerAuth: []}]`,
прецедент logout openapi.yaml:442-443). Контроллер — тонкий адаптер
`PasswordChangeController` (`auth/api`), оркестрация — `PasswordChangeService`
(`auth/domain/service`):

1. `UserRateLimiter.tryAcquire` (§6) → загрузка пользователя;
2. JIT-аккаунт (`passwordHash == null`): сверка с фиктивным хешем (timing-parity,
   LoginService.kt:118-124) → тот же 400 `currentPassword: invalid`;
3. `passwordEncoder.matches(currentPassword, hash)` — неверный ⇒ журнал
   `PASSWORD_CHANGE_FAILED` (вне откатываемой tx, прецедент LoginService.kt:137-152) ⇒
   **400** `Problem.errors = {currentPassword: ["invalid"]}` через
   `RegistrationValidationException` (ApiExceptionHandler.kt:31-34);
4. политика: `PasswordPolicyService.validate` (PasswordPolicyService.kt:37-47) **до**
   мутаций → 400 с полевыми кодами `newPassword: too_short|too_long|blank|too_common|
   same_as_username|same_as_email` (прецедент PasswordResetService.kt:151-173);
5. `userRepository.update(user.changePassword(hash, now))` (User.kt:61-73, Argon2id —
   механизм 002, FR-009);
6. новый метод `SessionService.revokeOthersAndRotateCurrent(userId, currentSessionId, …):
   IssuedTokenPair`:
   - guard: сессия активна (`requireSession`-паттерн, SessionService.kt:240-248);
   - `refreshTokenRepository.revokeActiveForUser(userId)` — гасит все активные
     generations, включая текущую (SessionRepository.kt:67-70);
   - новый port-метод `revokeAllForUserExcept(userId, keepSid, PASSWORD_CHANGE)` (зеркало
     revokeAllForUser, SessionRepository.kt:36-39): прочие сессии → revoked, возвращает
     sid'ы; для каждого — `denylistSid` + журнал `SESSION_REVOKED` c
     `details={reason:"password_change"}` (паттерн :227-236). Причина
     `RevokedReason.PASSWORD_CHANGE` уже существует (SessionStatus.kt:16-20, V3:7);
   - `issueGeneration(currentSessionId, userId)` (:296-315) — ротация текущей сессии:
     новый refresh + новый access **без** denylist текущего sid (denylist sid-scoped,
     AuthJwtDecoder.kt:60-63 — гашение убило бы новый access);
   - совпадение нового пароля со старым не ветвится: any successful change ⇒ полный путь
     FR-008 (clarify spec.md:17).
7. журнал `PASSWORD_CHANGE_COMPLETED`; ответ — `TokenPairResponse` через существующий
   `IssuedTokenPair.toResponse()` (SessionController.kt:101-107) — паритет с
   `POST /auth/refresh` (schema `TokenPair`, openapi.yaml:409-414).

sid текущей сессии: `accessToken.getClaimAsString("sid")` (прецедент
RealtimeController.kt:79); userId — `accessToken.subject`.

**Rationale**: 401 на Bearer-маршрутах зарезервирован exclusively под «Not authenticated»
(SecurityConfig.kt:97-102,147-163); 400 с полевым кодом — единый репозиторский стиль
валидации (422 в репо не существует). Повторное использование `TokenPair` сохраняет
клиентский механизм `setTokenPair` без ветвления.

**Alternatives**: (a) 401 на неверный текущий пароль — отвергнут (ломает инвариант
границы аутентификации); (b) отдельная «смена без текущего пароля» для JIT — отвергнута
FR-004 (серверная ветка не вводится); (c) ротация через logout+login — отвергнута
(нарушает «пользователь остаётся вошедшим», FR-008); (d) подтверждение пароля
(`confirmPassword`) в API — не вводится: совпадение — клиентская проверка FR-005, серверу
поле не нужно.

## 6. Rate limiting смены пароля (NC-4)

**Decision**: два уровня, оба на Redis (Bucket4j/Lettuce), консистентно при
масштабировании (конвенция 002, FR-007):

| Уровень | Механизм | Ключ | Лимит |
|---|---|---|---|
| per-IP (pre-auth flood guard) | `RateLimitFilter`, строка таблицы маршрутов RateLimitFilter.kt:87-103 | `rl:ip:password-change:<ip>` | `10/1m` (паритет login-IP, application.yml:81) |
| per-user (authenticated) | `UserRateLimiter.tryAcquire(keyFamily, userId, n)` в контроллере (RateLimitConfig.kt:109-169; прецеденты UsersController.kt:130-147, ChatSoundController.kt:109) | `rl:user:pwdchange:<userId>` | `5/1m` |

Отказ → typed exception → 429 problem+json + `Retry-After` + `errors: flood_limit`
(существующий handler-путь). Конфиг: блок `auth.ratelimit.password-change`
(`ip: "10/1m"`, `user-per-minute: 5`) — расширенный класс лимитов
`PasswordChangeLimits(ip, userPerMinute)` в `AuthRateLimitProperties`
(AuthRateLimitProperties.kt:23-34), application.yml после `refresh:` (:100-101).
Fails-open на отказе Redis наследуется обоими механизмами.

**Rationale**: точное воспроизведение двух существующих паттернов (неаутентифицированные
auth-маршруты / аутентифицированные per-user), 5/1m — операция реже и чувствительнее
обычных write-лимитов 30/1m (верифицирует credential + отзывает сессии).

**Alternatives**: (a) только per-IP — отвергнуто (NAT-маскирование злоумышленника);
(b) только per-user — отвергнуто (pre-auth флуд тем более бессмысленных попыток);
(c) LoginThrottle-стиль прогрессивной задержки — отвергнут: он для неаутентифицированного
brute-force identifier'ов; здесь пользователь уже аутентифицирован и имеет свой Bearer.

## 7. Редирект после парольного входа и returnTo (NC из Assumptions spec.md:150)

**Decision**: чисто клиентская механика, login-endpoint 002 не меняется. Общий модуль
`frontend/src/auth/lib/returnTo.ts`: `isSafeReturnPath(value)` (перенос 1:1 из
SsoCallbackPage.tsx:45-47: `startsWith('/') && !startsWith('//')`) и
`loginRedirectTarget(returnTo)` (safe → сам путь; null/пустой/unsafe → `/chat`).
`SsoCallbackPage` переключается на импорт из общего модуля (регрессия прикрыта его
тестами). `LoginPage.submit` после `setTokenPair(pair)` (LoginPage.tsx:139) выполняет
`window.location.assign(loginRedirectTarget(currentReturnTo()))` — тот же механизм, что
SsoCallbackPage.tsx:87-89; sessionId-хранилище не используется (параметр ещё в URL на
момент submit). `ProtectedRoute` уже генерирует `/login?returnTo=…` (App.tsx:22-33) —
паритет с 003 достигается автоматически. Замечено на будущее (вне scope): правило не
отклоняет `/\evil.com` — сохранён точный паритет с 003, ужесточение обеих точек —
отдельным решением.

**Rationale**: минимальная механика; изменение `POST /auth/login` не требуется, что
подтверждает Assumption spec.md:150.

**Alternatives**: (a) серверный returnTo в login — отвергнут (нет необходимости, YAGNI);
(b) router-state навигация — отвергнута: роутинг App.tsx — pathname-snapshot, полный
переход — установленный паттерн.

## 8. Клиентская валидация пароля и форма смены (FR-004/FR-005)

**Decision**: новый `frontend/src/auth/lib/passwordPolicy.ts`:
`validateNewPassword(password, {username, email, confirm}): string | null` — длина 8–128,
не пустой/не из пробелов, совпадение с подтверждением, ≠ username/email (сравнение
case-insensitive с `trim().toLowerCase()` — зеркало сервера,
PasswordPolicyService.kt:37-61). Топ-лист слабых паролей (ресурс бэкенда ~10k строк)
**остаётся серверной проверкой** — на клиент не поставляется; код `too_common` приходит
серверным 400 и рендерится существующим `problemMessage` (auth/problem.ts:42-45,
прецедент RegisterPages.test.tsx:204-221). Таким образом, FR-005 выполняется клиентом для
всех дешёвых проверок до отправки, а полный энумеративный список — сервером; понимание
нарушений сохраняется (понятные сообщения в обоих случаях).

Компонент `frontend/src/chats/components/PasswordChangeForm.tsx` — рендерится в
`ProfileModal` между readonly-строками и кнопками: поля current (`autoComplete=
"current-password"`), new + confirm (`autoComplete="new-password"`), дизайн-язык 008
(`profile-form`, `modal-err` c `role="alert"`, `modal-btns`/`m-btn`). Отдельная `<form>` —
submit не затрагивает цепочку «Сохранить» профиля (ProfileModal.tsx:107). API:
`changePassword({currentPassword, newPassword})` через `authedPost` (api/auth.ts:117-131);
успех → `setTokenPair(pair)` (session.ts:42-45 — единая точка ротации localStorage-refresh
и in-memory access) + один toast (`useToast`, паттерн ProfileModal.tsx:112-115) + очистка
полей; ошибка — inline `.modal-err` без toast (паттерн ProfileModal.tsx:118-121).

Gating: после регенерации контракта `me.hasPassword` (additive, schema.d.ts);
`hasPassword === false` ⇒ форма скрыта, показана подсказка со ссылкой на
`/forgot-password` (та же цель, что SecurityPage.tsx:183-185).

**Rationale**: блоклист в бандле SPA — лишние ~100 KB и расхождение списков; сервер всё
равно обязан валидировать (FR-006, доверять клиенту нельзя). Разделение зафиксировано как
интерпретация FR-005: клиент проверяет всё, что дёшево и детерминированно.

**Alternatives**: (a) полная копия блоклиста на клиент — отвергнута (вес, рассинхрон,
безопасность не растёт); (b) переиспользование inline-проверок Set/ResetPasswordForm —
отвергнуто: их проверки неполны (только «new ≠ confirm», атрибуты minLength инертны из-за
`noValidate`); (c) размещение формы на SecurityPage — отвергнуто: FR-004 требует «Мой
профиль».

## 9. Аудит безопасности (FR-010)

**Decision**: расширение enum `AuthEventType` (AuthEvents.kt:12-38) значениями
`PASSWORD_CHANGE_COMPLETED("password_change_completed")` и
`PASSWORD_CHANGE_FAILED("password_change_failed")` (прецедент пары
`LOGIN_SUCCESS/LOGIN_FAILED`); миграция `ALTER TYPE auth_event_type ADD VALUE …` в V17
(прецедент V8__sso_enums.sql:19-24). Записи — через существующий `AuthEventRecorder.record`
(AuthEvents.kt:53-70): IP только SHA-256+pepper, без паролей/токенов; `SESSION_REVOKED`
переиспользуется для отзываемых прочих сессий (`reason=password_change`).

## 10. Ссылка сброса пароля на экране входа (FR-003)

**Decision**: ссылка «Забыли пароль?» (`<a href="/forgot-password">`, plain `<a>` — стиль
карточки логина) в footer LoginPage.tsx:287-289 рядом с «Зарегистрироваться». Маршрут и
страница существуют (App.tsx:52-53, ForgotPasswordPage.tsx:39-56); API и семантика 002 не
трогаются.

---

## Сводка разрешённых неопределённостей

| NC | Вопрос | Решение |
|---|---|---|
| 1 | Seed admin: механизм и атрибуты | ApplicationRunner + ON CONFLICT DO NOTHING; email/password из env; без пароля — JIT-форма + сброс 002 (§1) |
| 2 | Событие регистрации + fallback | AFTER_COMMIT @Async event (2 точки) + starter_contact_added флаг + ensureOnboarded в GET /contacts (§2–3) |
| 3 | hasPassword | только `GET /users/me`, inline allOf над PublicUser (§4) |
| 4 | Rate limiting смены | per-IP фильтр 10/1m + per-user UserRateLimiter 5/1m, оба Redis (§6) |
| 5 | Ответ смены пароля | `TokenPair` (паритет refresh); 400 на неверный текущий/политику; revoke-others + rotate-current (§5) |
| 6 | Точка SSO JIT | `IdentityResolutionService.provisionJitAccount` публикует то же событие (§2) |

Открытых NEEDS CLARIFICATION не осталось.
