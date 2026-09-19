# Research: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Feature**: `003-sso-identity-providers` | **Date**: 2026-09-16

Контекст: фича 002 (регистрация/сессии) реализована и слита; её research §12 явно
зафиксировал шов для SSO: «проверка учётных данных → верифицированный userId →
выдача сессии», `SessionService.startSession(userId)` identity-agnostic,
`users.password_hash` nullable by design. Ниже — решения для фичи 003; каждое в
формате Decision / Rationale / Alternatives considered.

---

## 1. OIDC-движок: компоненты spring-security-oauth2-client без servlet-фильтров

**Decision**: добавить `spring-boot-starter-oauth2-client` и использовать его
компоненты — `ClientRegistration`/`ClientRegistrationRepository` (модель провайдера),
построение authorization request с PKCE S256 (генерация code_verifier/challenge),
`DefaultAuthorizationTokenTokenResponseClient` (обмен code→token),
OIDC-верификация ID-токена (Nimbus: подпись JWKS, `iss`, `aud`, `exp`, `nonce`) —
но **не** использовать встроенные фильтры `OAuth2LoginProcessingFilter`/
`OAuth2AuthorizationRequestRedirectFilter` и session-based
`AuthorizationRequestRepository`. Вместо них — тонкие JSON-контроллеры
(`SsoController`) и собственный Redis-backed стор флоу-контекста.

**Rationale**: протокольные детали (PKCE, nonce, валидация ID-токена, JWKS) —
проверенное решение (конституция VII); встроенный oauth2-login заточен под
HttpSession и собственную модель аутентификации, что конфликтует со stateless-
архитектурой (конституция II) и кастомной моделью сессий 002 (ES256 access +
opaque rotating refresh); переопределение трёх фильтров/репозиториев сложнее и
хрупче, чем прямое использование компонентов.

**Alternatives considered**:
- *Полный oauth2-login Spring Security* — отвергнут: требует HttpSession (state в
  памяти → нарушение II), навязывает свой success-flow, не выдаёт нашу пару токенов;
  кастомизация сравнима по объёму с прямой реализацией, но с меньшим контролем.
- *Самописный OIDC на Nimbus* — отвергнут: PKCE/nonce/c_hash/issuer-валидация —
  типовые места ошибок безопасности; библиотека уже даёт корректные реализации,
  Nimbus и так в classpath (oauth2-resource-server).

## 2. Передача токенов SPA: одноразовый handshake-code → POST exchange

**Decision**: callback (браузерный GET) **не создаёт сессию**. Он валидирует флоу,
резолвит идентичность (вход/JIT/автосвязывание) и кладёт в Redis контекст
`sso:handshake:<sha256(code)> = {userId, identityId, providerId}` (single-use
`GETDEL`, TTL 2 мин, конфиг `sso.handshake-ttl`), затем 302 на маршрут SPA
`/sso/callback?code=<43-char>&state=…`. SPA вызывает публичный
`POST /api/v1/auth/sso/token {code}` → backend по контексту вызывает
`SessionService.startSession(userId, SSO, identityId)` → возвращает `LoginResponse`
(TokenPair + user) — ровно как парольный вход.

**Rationale**: refresh-токен в 002 — opaque 256-bit, на сервере хранится только
SHA-256; выдать его в callback и «вернуть позже» невозможно без хранения открытого
значения (нарушение модели безопасности). Отложенное создание сессии на финальном
POST решает это: токены существуют только в ответе POST. Handshake-code —
одноразовый, короткоживущий, хранится хэшированным ключом; утечка в URL логов
бесполезна после обмена/истечения. Паттерн «OTT-код → обмен» уже используется в 002
(register/confirm → setupToken), UX и виджет (фича 013) используют один механизм.

**Alternatives considered**:
- *Токены в URL-фрагменте редиректа* — отвергнуто: refresh-токен в браузерной
  истории/логах; расширение поверхности утечки.
- *Токены в HttpOnly cookie* — отвергнуто: ломает без stateful-сессий, конфликтует
  с localStorage-моделью 002 и виджетом в чужом DOM.
- *Генерация сессии в callback + хранение TokenPair в PG до обмена* — отвергнуто:
  потребовало бы хранить открытый refresh-токен на сервере.
- *Переиспользование таблицы `one_time_tokens`* — отвергнуто: у OTT нет payload
  (нужны identityId/providerId), а сущность живёт ≤2 минуты — PG-надёжность
  избыточна (потеря Redis = повторный вход, durability не страдает; инвариант 002
  «потеря Redis не ломает PG» сохранён).

## 3. Хранение флоу-контекста (state/nonce/code_verifier): Redis, single-use

**Decision**: `sso:flow:<state>` = JSON `{providerId, purpose(login|link), userId?,
returnTo?, nonce, codeVerifier, createdAt}`, TTL 10 мин (`sso.flow-ttl`), запись при
authorize, изъятие атомарным `GETDEL` при callback. state — 256-bit base64url.
PKCE — **server-side confidential client**: verifier хранится только в Redis,
браузер его не видит; client_secret — только на backend.

**Rationale**: stateless при масштабировании (II): любой инстанс завершит флоу;
`GETDEL` даёт single-use → повтор/перепутанная вкладка/повторный callback
отклоняются (FR-009, FR-012, edge «несколько вкладок»). 10 мин покрывает любой
реалистичный флоу; секретов в значении нет (verifier после завершения флоу
бесполезен — code уже погашен у провайдера).

**Alternatives considered**:
- *Signed cookie со state* — отвергнуло бы server-side PKCE (verifier пришлось бы
  класть в cookie) либо потребовало шифрования; сложнее и хуже для виджета
  (cross-site cookie-сценарии).
- *PostgreSQL* — отвергнуто: сущность живёт минуты, single-use-семантика с CAS на
  Redis дешевле; потеря Redis не создаёт дублей эффектов (см. §13).

## 4. Конфигурация провайдеров: YAML + @ConfigurationProperties, секреты — env

**Decision**: собственный `SsoProperties` (prefix `sso`) со списком провайдеров:
`{id (slug), displayName, enabled, trustedForEmailLinking (default false — opt-in),
clientId, clientSecret (плейсхолдер `${SSO_<ID>_CLIENT_SECRET}`), issuerUri |
явные endpoints (authorization/token/userinfo/jwks), scopes (default openid,email)}`.
Валидация на старте (fail-fast): формат id, наличие clientId/secret/endpoints у
включённых, коллизии redirect_uri. `SsoProviderRegistry` строит `ClientRegistration`-ы
программно. **Отдельной таблицы `identity_providers` в БД нет**: `external_identities.provider_id` — varchar
со ссылкой на код провайдера из конфигурации, валидируется приложением.

**Rationale**: spec прямо выводит админ-UI за scope («конфигурирование —
операционная процедура», Assumptions) → источник конфигурации — деплой-манифест,
что совпадает с механизмом секретов 002 (K8s Secrets → env): секрет не попадает ни
в БД, ни в репозиторий (SC-004, FR-007). Выключенный провайдер остаётся в конфиге
(`enabled: false`) → привязки сохраняются (FR-007, US4-3), перечень на экране входа
динамичен без релиза кода.

**Alternatives considered**:
- *Таблица `identity_providers` в PG + секрет по ссылке* — отвергнуто: потребовало бы
  CRUD-механику/миграции без требования spec (YAGNI); allowlist-флаг и enabled
  прекрасно живут в YAML.
- *Стандартный `spring.security.oauth2.client.*`* — отвергнуто: туда не ложатся
  displayName/enabled/trusted; вторая секция конфига = рассинхрон.

## 5. Discovery: lazy-кэш metadata по issuer-uri, явные endpoints как альтернатива

**Decision**: провайдер конфигурируется либо `issuerUri` (OIDC discovery:
`/.well-known/openid-configuration` — fetching **ленивый**, кэш в памяти с TTL,
refresh при unknown kid в JWCS/ошибке), либо явными endpoint'ами (без discovery).
На старте сетевых вызовов к IdP нет.

**Rationale**: eager-discovery на старте (поведение Spring по умолчанию) создаёт
зависимость бута от доступности внешнего IdP — рестарт всего backend лёг бы из-за
чужого сбоя (нарушение изоляции US4-4). Явные endpoints обязательны для тестового
MockIdP и полезны для провайдеров с нестандартным well-known.

**Alternatives considered**:
- *Только issuer-uri, eager* — отвергнуто (зависимость бута от IdP, см. выше).
- *Только явные endpoints* — отвергнуто: для публичных провайдеров discovery
  устраняет ручные ошибки конфигурации.

## 6. JIT-аккаунты: релаксация CHECK `users` + DB-триггер последнего способа входа

**Decision**: миграция V9: (а) CHECK `ck_users_active_implies_credentials` заменяется
на `ck_users_active_implies_email` — **импликацией, не равенством**:
`CHECK (status <> 'active' OR email_confirmed_at IS NOT NULL)`;
равенство `(status='active') = (email_confirmed_at IS NOT NULL)` нарушило бы
существующие `awaiting_password`-аккаунты 002 (status ≠ 'active' при
подтверждённом email; см. data-model §2);
(б) инвариант «у активного аккаунта ≥1 способ входа» обеспечивается приложением
(все пути создания/отвязки идут через сервисы) **и** страхуется BEFORE DELETE-триггером
на `external_identities` (запрещает удаление последней привязки, если
`password_hash IS NULL`). JIT-вставка: `status='active'`, `email_confirmed_at=now()`
(email подтверждён провайдером), `password_hash=NULL`, письмо не отправляется.

**Rationale**: старый CHECK `[active ⟺ email_confirmed AND password]` блокирует
JIT (FR-003); подзапросы в CHECK PG не поддерживает. Триггер закрывает race «две
параллельные отвязки последних двух привязок», который app-проверка не ловит
атомарно, и стоит ~10 строк. `email_confirmed_at=now()` сохраняет семантику «active
⟺ email подтверждён» (для JIT это делает провайдер — Assumption spec).

**Alternatives considered**:
- *Только app-проверка без триггера* — отвергнуто: окно гонки при параллельных
  отвязках; DB-инвариант надёжнее и дёшево.
- *CHECK через функцию с обращением к другой таблице* — отвергнуто: не для
  cross-table ограничений, ломается при restore.

## 7. Генерация username для JIT

**Decision**: `UsernameGenerator`: local-part email → lowercase → оставить
`[a-z0-9._-]` → обрезать до 32 → обеспечить паттерн 002
`^[a-zA-Z0-9]([a-zA-Z0-9_.-]{1,30}[a-zA-Z0-9])$` (lead/trail alnum, длина ≥3; при
невыполнении — префикс `user`); коллизии (проверка через `lower()`-уникальность) →
суффикс `-2..-99`, затем `-<4 random chars>`; вставка в транзакции с ретраем при
unique violation (≤5 попыток).

**Rationale**: требования US2-1/US2-6 (уникальность, незаметность для
пользователя); паттерн 002 не содержит `@` → коллизия с email-логином невозможна.
Ретрай вместо SELECT-then-INSERT закрывает гонку двух одновременных JIT.

**Alternatives considered**:
- *Полностью случайный username* — отвергнуто: хуже для отображения/поиска
  пользователей в будущем чате.
- *Только суффикс-цикл без рандома* — отвергнуто: предсказуемо при целенаправленном
  занятии вариантов.

## 8. Семантика allowlist автосвязывания

**Decision**: per-provider флаг `trustedForEmailLinking` (default **false**).
Матрица резолвинга первого входа (подтверждённая IdP идентичность `(provider, sub)`
не найдена):

| Условия | Действие |
|---|---|
| email отсутствует или `email_verified≠true` | отказ: `email_not_verified` (вход по паролю + ручная привязка); JIT запрещён (FR-004) |
| есть **активный** аккаунт с `lower(email)` и provider trusted | автосвязывание + вход (без запроса пароля — clarify 2026-09-16) |
| есть **активный** аккаунт, provider **не** trusted | отказ: `email_conflict` (войти по паролю, привязать вручную) |
| есть pending/awaiting_password аккаунт с `lower(email)` | отказ: `registration_incomplete` (завершить регистрацию по ссылке из письма 002) |
| аккаунта нет, email verified | JIT: создать active + привязка + вход |

Существующая идентичность → вход всегда (email-клеймы не влияют; edge «провайдер
сменил email»). Ручная привязка (US3) не требует verified/trusted — контроль обеих
сторон доказан самим флоу; email аккаунта не меняется.

**Rationale**: opt-in trust — защита от захвата аккаунта через «удобный» провайдер
(Assumption spec); матрица покрывает US2-1..US2-6 и edge-кейсы email-нормализации
(сопоставление через ту же `lower()`-канонизацию, что в 002).

## 9. Rate limiting: расширение существующего Bucket4j/Redis-механизма

**Decision**: расширить таблицу маршрутов `RateLimitFilter`/`AuthRateLimitProperties`
(IP-бакеты, внешнее состояние в Redis — консистентность при масштабировании, FR-009):
`GET /auth/sso/providers` 30/1m; `POST /auth/sso/authorize` 10/1m;
`POST /auth/sso/link/authorize` 10/1m (Bearer); `GET /auth/sso/callback` 30/1m;
`POST /auth/sso/token` 30/1m (token-consuming, как confirm/refresh в 002).
Фильтр расширить поддержкой GET-маршрутов (сейчас публичные POST-маршруты).

**Rationale**: переиспользование готового механизма (VII): те же 429 + Retry-After
problem+json, тот же Redis ProxyManager, fail-open при недоступности Redis.
authorize создаёт записи в Redis → лимит строже; callback прилетает браузерным
редиректом → 30/1m достаточно и не блокирует легитимные ретраи.

**Alternatives considered**:
- *Отдельный лимитер для SSO* — отвергнуто: дублирование механики без требований.
- *Лимит по state/email на callback* — отвергнуто: state — секрет одноразового
  флоу, не идентификатор источника; IP достаточно.

## 10. Аудит SSO-событий: расширение `auth_events`

**Decision**: ALTER TYPE `auth_event_type` (V8, отдельной миграцией до
использования): `sso_login_success`, `sso_login_failed`, `sso_identity_linked`,
`sso_identity_unlinked`, `sso_account_created` (JIT), `sso_flow_error`. Запись через
существующий `AuthEventRecorder` в TX вызывающего; `details` jsonb — только
несекретные маркеры: `{provider, resolution: existing_identity|auto_linked|jit,
reason, state}`; ip_hash/UA/trace_id — по конвенции 002; токены/секреты — никогда.

**Rationale**: FR-011 одной строкой ложится на готовый журнал; единое место аудита
для security-инцидентов. `sso_flow_error` покрывает отклонённые флоу (replay,
подмена, ошибки провайдера) — US5-4.

## 11. Расширение сессий: `auth_method`/`identity_id`, JWT не меняется

**Decision**: `sessions` += `auth_method varchar(16) NULL` (`'password' | 'sso'`;
NULL = legacy-парольные строки 002) и `identity_id uuid NULL → external_identities
ON DELETE SET NULL`. `SessionService.startSession` получает перегрузку
`startSession(userId, authMethod, identityId?)`; формат access-JWT и ротация refresh
— без изменений.

**Rationale**: spec требует только «признак способа создания для журнала и
отображения»; изменение JWT-клеймов ломало бы контракт/клиентов без нужды (IV).
`ON DELETE SET NULL` сохраняет историю сессий при отвязке идентичности (отвязка
не трогает сессии — clarify 2026-09-16).

**Alternatives considered**:
- *Клейм `amr` в access-токене* — отвергнуто: Contract churn без требования.
- *Отдельная таблица `sso_sessions`* — отвергнуто: нарушало бы единую модель (FR-002).

## 12. Тестирование: MockIdP в test-scope + Testcontainers

**Decision**: `MockIdP` — `@RestController` в test-sources (профиль IT):
authorization endpoint (302 c code), token endpoint (подписанный RSA ID-токен c
управляемыми клеймами: sub/email/email_verified/nonce), JWKS endpoint; управление
сценариями: HTTP 500, задержка > таймаута, неверный secret, подменённый код.
Провайдер в тестах регистрируется явными endpoints на MockIdP. Полный набор IT:
`SsoFlowIT`, `SsoIdentityResolutionIT`, `SsoLinkingIT`, `SsoSecurityIT` (replay/
подмена state/nonce), `SsoResilienceIT`, `SsoRateLimitIT` — поверх существующей
базы `AbstractIntegrationTest` (PG17 + Redis7 контейнеры).

**Rationale**: конституция VI делает интеграционные тесты SSO обязательными;
эмулятор даёт детерминированный полный флоу (включая JWKS/nonce) без внешних IdP и
без WireMock (контроллер типизированнее, sigh). Frontend: Vitest для новых
страниц; k6-smoke расширяется сценарием providers→authorize→token-error (полный
флоу в smoke невозможен без реального IdP — задокументировано).

**Alternatives considered**:
- *WireMock для IdP* — отвергнуто: больше boilerplate для JWKS/динамики nonce.
- *Keycloak/dex testcontainer* — отвергнуто: тяжёлее, медленнее, сложнее
  программное управление клеймами; dex оставлен для ручной проверки (quickstart).

## 13. Идемпотентность эффектов (FR-012)

**Decision**: (а) state — single-use `GETDEL`: повторный/чужой callback отклоняется
до любых эффектов; (б) handshake-code — single-use `GETDEL` + сессия создаётся один
раз; (в) дубль привязки невозможен — UNIQUE `(provider_id, subject)`, повторная
привязка владельцем = no-op success, чужим = `identity_taken`; (г) дубль JIT
невозможен — UNIQUE `lower(email)`: проигравший гонку получает `email_conflict`,
повторный вход уже проходит как existing identity; (д) рестарт между callback и
token-обменом: Redis-ключи TTL-сятся сами, «осиротевших» эффектов нет (сессия не
создавалась).

**Rationale**: все мутирующие операции завершения флоу сходятся к single-use
ключам и DB-уникальностям; повторное выполнение любого шага не создаёт дублей
аккаунтов/привязок/сессий.

## 14. Observability SSO

**Decision**: метрики Micrometer: `sso_flow_total{provider, outcome}`
(started/completed/login_failed/flow_rejected/provider_error),
`sso_idp_call_duration{provider, kind=token|userinfo|jwks}` (тайминги внешних
вызовов, timeout: общий deadline 5 s на callback — SC-005, connect 1 s / read 2 s на вызов); structured-логи с trace_id (существующий
стек OTel→LGTM); сбой провайдера = `sso_flow_error` в `auth_events` + метрика —
US4-4/US5-3/SC-005.

**Rationale**: переиспользование стека 001/002; общий deadline 5 s на callback прямо из SC-005 (per-call connect 1 s / read 2 s);
изоляция провайдеров = отсутствие общего состояния (метрики per-provider).

## 15. Версия контракта и публичный перечень

**Decision**: `contracts/openapi.yaml` 0.2.0 → **0.3.0**, только аддитивно —
7 endpoints: `GET /auth/sso/providers`, `POST /auth/sso/authorize`,
`GET /auth/sso/callback` (браузерный 302 — документируется с семантикой
редиректов), `POST /auth/sso/token`, `POST /auth/sso/link/authorize`,
`GET /users/me/identities`, `DELETE /users/me/identities/{identityId}`.
oasdiff breaking gate проходит без `BREAKING.md`; TS-типы регенерируются
(drift-check CI). Полный публичный перечень (без аутентификации) = 8 маршрутов 002
+ 4 новых SSO; link-endpoints — Bearer.

**Rationale**: FR-008 требует фиксации перечня в контракте; виджет (013) и SPA —
клиенты одного API; error-конвенция — RFC 9457 problem+json + `errors`-коды 002.

---

## Сводка NEEDS CLARIFICATION → разрешено

| Открытый вопрос (Technical Context) | Решение |
|---|---|
| OIDC-библиотека/подход | §1: компоненты spring-security-oauth2-client, без servlet-фильтров |
| Способ выдачи токенов SPA после redirect | §2: handshake-code → `POST /auth/sso/token` |
| Хранение флоу-состояния при stateless | §3: Redis single-use (state/PKCE/nonce) |
| Источник конфигурации провайдеров + секреты | §4: YAML + env-плейсхолдеры (K8s Secrets); без DB-таблицы |
| JIT vs CHECK-ограничение `users` | §6: релаксация CHECK + триггер последнего способа входа |
| Тестовый IdP | §12: MockIdP test-controller + Testcontainers |

Неразрешённых NEEDS CLARIFICATION нет.

---

# Дополнение 2026-09-19: OAuth2-провайдеры без OIDC — режим `oauth2-userinfo`

Контекст: фича 003 реализована и слита; на dev-стенде подключены Google (OIDC,
работает) и Яндекс. Уточнение по факту: **Яндекс ID не поддерживает OIDC** — только
OAuth 2.0 (authorize/token на `oauth.yandex.ru`, профиль через `GET login.yandex.ru/info`;
`id_token` не выдаётся, discovery/JWKS нет; режим `?format=jwt` подписан HS256 без
публичных ключей и для OIDC-верификации непригоден). Исходная Assumption спеки
(«чистый OAuth2 не поддерживается — YAGNI») ревизуется: целевой провайдер без
OIDC существует, конституция V требует «SSO (OIDC/**OAuth2**)». Ниже — решения
расширения; нумерация продолжает основную часть.

## 16. Протокольный режим провайдера: `protocol: oidc | oauth2-userinfo`

**Decision**: в `SsoProperties.Provider` вводится атрибут `protocol` (default
`oidc` — обратная совместимость: dex/google не меняют поведения). Значение
`oauth2-userinfo` активирует альтернативную ветку флоу: тот же authorization code
flow, но вместо верификации `id_token` — backchannel-запрос userinfo с полученным
access-токеном и маппинг клеймов в `OidcIdentityClaims`. Для `oauth2-userinfo`
обязательны явные `authorization-uri`, `token-uri`, `userinfo-uri` + client-id/secret;
`issuer-uri`/`jwks-uri` не требуются и игнорируются (fail-fast T007 расширяется
соответственно). Маппинг клеймов — конфигурируемые имена: `subject-claim`
(default `sub`; Яндекс: `psuid`), `email-claim` (default `email`; Яндекс:
`default_email`), `email-verified-mode: claim | provider-guaranteed` (default
`claim` — читать булев клейм `email_verified`; `provider-guaranteed` — считать
email подтверждённым по определению провайдера; Яндекс: `default_email` всегда
подтверждён — неподтверждённые адреса не могут стать основными).

**Rationale**: единая решающая матрица первого входа (§8, data-model §7) и весь
домен резолвинга оперируют `OidcIdentityClaims{subject, email, emailVerified}` —
источник клеймов (подписанный токен vs userinfo-эндпоинт) ортогонален домену;
переиспользуем сессии/handshake/resolution/limы/audit без изменений, публичный
контракт API не меняется (FR-008 не затронут — сильная сторона подхода).
Декларативные имена клеймов вместо кодовых адаптеров «под Яндекса» — GitHub
(`id`→sub, email из `/user/emails`) и VK закрываются конфигурацией или минимальным
расширением, без новой ветки в домене.

**Alternatives considered**:
- *Брокер (dex/keycloak с yandex-коннектором)* — отвергнут: дополнительная
  stateful-инфраструктура на стенд, двойной redirect, нестандартный коннектор;
  конституция VII (готовые решения для протоколов) здесь о транспортной
  библиотеке, а не о введении посредника.
- *Перевод всего SSO на «userinfo-подход»* (без ID-токенов вообще) — отвергнут:
  теряет криптографическую защиту OIDC-провайдеров, уже работающую (Google/dex).

## 17. Доверие без ID-токена: компенсирующие меры

**Decision**: для `oauth2-userinfo` границы доверия: (1) browser-leg связан с
флоу одноразовым `state` в Redis (проверка до обмена — уже реализовано, US5-2);
(2) код обменивается строго server-to-server по TLS с `client_secret`
(confidential client) — access-токен не проходит через браузер; (3) userinfo
запрашивается backchannel'ом с этим токеном — клеймы приходят от источника,
который выдал токен именно нашему client_id; (4) nonce и подпись JWT отсутствуют
как класс — их роль (связь кода с инициатором) выполняет state + привязка кода к
redirect_uri. Уровень защиты = классический OAuth2 web-server flow —
промышленный стандарт до появления OIDC; фиксируется как осознанное ограничение.

**Rationale**: повторное использование кода/ответа исключено одноразовостью state
и кода; перехват кода бесполезен без client_secret; подмена userinfo невозможна
без компрометации TLS или secret. Email-гейт FR-004 остаётся без послаблений:
`email-verified-mode` лишь выбирает источник факта подтверждения, `claim`-режим
отклоняет неподтверждённые адреса ровно как OIDC-ветка.

**Alternatives considered**:
- *Принудительно требовать OIDC у всех провайдеров* — эквивалент отказу от
  Яндекса (см. контекст).
- *`?format=jwt` Яндекса + HS256* — отвергнуто: симметричная подпись без
  распределения ключей нашему клиенту не верифицируется; отдельный механизм ради
  отсутствия пользы.

## 18. PKCE per-provider: `pkce: true | false`

**Decision**: атрибут `pkce` (default `true`). Для Яндекса — `false`: OAuth-сервер
Яндекса не поддерживает RFC 7636 (code_challenge игнорируется, code_verifier в
token-запросе отвергается). Риск смягчён confidential-моделью: обмен кода с
client_secret по TLS + точный redirect_uri + single-use state.

**Rationale**: PKCE для public-клиентов — MUST, для confidential — defense in
depth; сохраняем его везде, где провайдер поддерживает (OIDC-режим не меняется),
но не жёстим для несовместимых провайдеров.

**Alternatives considered**:
- *Всегда слать PKCE* — Яндекс ответит ошибкой обмена (`provider_error`) —
  фактически блокирует провайдера.

## 19. Транспорт, таймауты, observability — переиспользование бюджетов

**Decision**: userinfo-вызов идёт через тот же транспорт с пер-вызовными лимитами
(connect 1 с / read 2 с) в рамках общего 5-секундного deadline callback'а
(budget: token ≤2 с + userinfo ≤2 с); метрика `sso_idp_call_duration{kind=userinfo}`;
отказы (транспорт/не-2xx/отсутствие subject-клейма) маппятся на существующие
`provider_error` / 502-изоляцию per-provider без новых кодов. Внешний access-токен
потребляется на месте и не сохраняется (Assumptions — без изменений).

**Rationale**: SC-005 (≤5 с, изоляция) и FR-010 формулируются протокол-агностично;
новый лег сопоставим с существующими (token+jwks → token+userinfo).

**Alternatives considered**: отдельных бюджет не вводится — YAGNI.

## 20. Yandex: конфигурация целевого провайдера

**Decision** (нормативные значения для dev-стенда): `protocol: oauth2-userinfo`,
endpoints `https://oauth.yandex.ru/authorize` / `https://oauth.yandex.ru/token` /
`https://login.yandex.ru/info`, `subject-claim: psuid` (стабильный ID сквозь
смену login; `uid` числовой легаси — не использовать), `email-claim:
default_email`, `email-verified-mode: provider-guaranteed`, `pkce: false`,
scopes — права задаются в приложении на oauth.yandex.ru (доступ «Адрес
электронной почты» обязателен); redirect URI = общий `SSO_CALLBACK_URL`
(`https://dev.webchat.lkshr.ru/api/v1/auth/sso/callback`). `trusted-for-email-linking: true`
сохраняется (Яндекс подтверждает владельца default_email).

**Rationale**: `psuid` — документированный постоянный идентификатор пользователя
(логин меняется); `default_email` — подтверждённый основной адрес.

**Alternatives considered**: `login` как subject — отвергнут (меняется);
`uid` — отвергнут (legacy, не выдаётся новым приложениям).

## 21. Тестирование: oauth2-режим MockIdP

**Decision**: MockIdP (§12) расширяется режимом «oauth2-эндпоинт» для
test-провайдера: token-ответ без `id_token` + `GET userinfo` (JSON c
конфигурируемыми subject/email-клеймами, имитация psuid/default_email). Новый
`SsoOauth2FlowIT`: полный вход (JIT), email-гейт (`provider-guaranteed` и `claim`
режимы), отказы (userinfo недоступен / не-2xx / нет subject-клейма / неверный
secret) → `provider_error`, изоляция от параллельного OIDC-провайдера, PKCE
вкл/выкл.

**Rationale**: та же Testcontainers-инфраструктура; соответствие конституции VI.

**Alternatives considered**: внешний реальный Яндекс в CI — отвергнут (секреты,
нестабильность, rate).

## Сводка дополнения

| Вопрос | Решение |
|---|---|
| Поддержка не-OIDC провайдеров | §16: `protocol: oauth2-userinfo` + конфигурируемый маппинг клеймов |
| Доверие без подписи/nonce | §17: state single-use + confidential code exchange + TLS backchannel |
| PKCE у Яндекса | §18: `pkce: false` per-provider |
| Бюджеты/изоляция/метрики | §19: переиспользуются, `kind=userinfo` |
| Нормативные значения Яндекса | §20 |
