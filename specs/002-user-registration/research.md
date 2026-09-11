# Research: Регистрация, вход и управление сессиями

**Feature**: 002-user-registration | **Date**: 2026-09-11

Разрешение всех открытых вопросов Technical Context фичи 002. Контекст: стек зафиксирован
фичей 001 (Kotlin 2.2 + Spring Boot 3.5 / Gradle KTS, React 19 + TS strict, OpenAPI 3.1
конвейер, LGTM-observability, stateless-сервисы в K8s). До сих пор в проекте **нет ни одного
внешнего хранилища** — эта фича вводит первое (и второе).

---

## 1. СУБД: PostgreSQL 17 + Flyway (SQL) + Spring JDBC

**Решение**:
- **PostgreSQL 17** (`postgres:17`), драйвер `org.postgresql:postgresql` (версия из BOM Spring Boot).
- Миграции: **Flyway** (`flyway-core` + `flyway-database-postgresql`, версии из BOM), plain SQL
  в `backend/src/main/resources/db/migration` (`V1__*.sql`, …).
- Доступ к данным: **Spring JDBC (`spring-boot-starter-jdbc`, `JdbcTemplate`)** + Kotlin data classes
  и явные row-mappers. Без JPA/jOOQ/MyBatis.

**Обоснование**: идентификационные данные требуют ACID-транзакций для атомарной ротации
refresh-токенов (условный `UPDATE ... WHERE token_hash = ? AND status = 'active'`) и
transactional outbox. Регистронезависимая уникальность email/username — уникальные
выражения-индексы на `lower(...)`: явно, переносимо, сохраняет оригинальный регистр для
отображения. JdbcTemplate даёт полный контроль над CAS-обновлениями ротации при 6 таблицах —
это ядро корректности фичи; ленивые состояния JPA здесь только добавили бы трения с Kotlin.

**Альтернативы отклонены**: MySQL 8/9 — нет преимуществ, слабее выражения-индексы/JSONB;
**citext** — неявное сворачивание регистра, ловушки в маппингах; недетерминированная ICU-коллация —
ломает LIKE; **JPA/Hibernate** — lifecycle-трение с Kotlin (all-open, final), слабая пригодность
для условных UPDATE-ротаций; **jOOQ** — вес codegen ради 6 таблиц; `ddl-auto` — нет истории
миграций; **Liquibase** — сопоставим, но XML/YAML-чейнджлоги добавляют индирекцию поверх SQL.

## 2. Хранилище лимитов: Redis 7 + Bucket4j (Lettuce)

**Решение**:
- **Redis 7** (`redis:7`) — эфемерное общее хранилище: счётчики rate limiting, счётчики неудачных
  входов, denylist отозванных сессий (см. §4).
- **Bucket4j 8.x** (`bucket4j_jdk17-core` + `bucket4j_jdk17-redis-lettuce`) поверх Lettuce
  (`spring-boot-starter-data-redis`, клиент по умолчанию в Boot): `LettuceBasedProxyManager`,
  ключи-семейства `rl:ip:<route>:<ip>`, `rl:email:<route>:<sha256(email)}`.
- Покрытие — **все 8 публичных маршрутов** (FR-009): email-ключ только там, где email есть в
  запросе (register/resend/password-reset; login — по identifier). Токен-consuming endpoints
  (№3 confirm, №4 password, №9 reset/confirm) и refresh (№6) — только IP-ключ со щадящим
  лимитом: цель — предел флуда/нагрузки на PG, а не подбор (токены 256-bit single-use,
  подбор вычислительно безнадёжен).

**Обоснование**: FR-009 требует состояния лимитов **вне памяти процесса** (консистентность при
горизонтальном масштабировании). Bucket4j даёт честный token bucket (плавные всплески) с
атомарным CAS сериализованного состояния бакета в Redis — Lua/CAS-обвязка входит в библиотеку.
Redis-ключи эфемерны и TTL-ированы: потеря инстанса лишь кратко сбрасывает лимиты — допустимо.
PostgreSQL для лимитов отклонён: каждый запрос авторизации превращался бы в `UPDATE` счётчика —
WAL/vacuum write amplification и давление на пул соединений ровно под тем трафиком
(brute-force), который лимиты обязаны поглощать.

**Альтернативы отклонены**: Resilience4j RateLimiter — строго in-process, нарушает FR-009;
Spring Cloud Gateway RequestRateLimiter — реактивный шлюз ради одного фильтра; собственный
Redis INCR/EXPIRE — гонки INCR+EXPIRE NX, отсутствие терпимости к всплескам, own-bugs.
Kafka/Redis Streams как замена outbox — брокера в проекте нет; вводить ради одного
producer/consumer — операционный вес без потребности (см. §6).

## 3. Хеширование паролей: Argon2id

**Решение**: `Argon2PasswordEncoder` из `spring-security-crypto`, обёрнут в
`DelegatingPasswordEncoder` (префикс `{argon2}` в хеше — миграция алгоритма без lock-in).
Параметры (OWASP Password Storage Cheat Sheet, конфигурируемые):
m = 19456 KiB (19 MiB), t = 2, p = 1, salt 16 байт, hash 32 байта. Индивидуальная соль
на пользователя встроена в формат.

**Обоснование**: OWASP ставит Argon2id первым; гибридный режим устойчив к GPU-атакам и
side-channel. Логин — низкий QPS, ~20 MiB на попытку приемлемы. Политика паролей (FR-004):
8–128 символов, запрет пустых/пробельных, тривиально слабых (топ-лист), совпадения с
username/email. Открытый пароль нигде не логируется (SC-005).

**Альтернативы отклонены**: bcrypt — приемлем, но обрезает ввод 72 байтами и лёгок по памяти
(GPU-crackable); scrypt — слабее поддержка в Spring; PBKDF2 — прямо не рекомендуется OWASP
(кроме FIPS-требований).

## 4. Токены: JWT ES256 (access) + opaque refresh с ротацией + Redis denylist

**Решение**:
- **Access-токен**: JWS **ES256** (EC P-256), TTL **5 мин** (конфиг.). Claims: `sub` (user UUID),
  `sid` (session UUID), `jti`, `iat`, `exp`, `typ:"access"`. Ключи — P-256 PEM в K8s Secret/env,
  заголовок `kid`; ротация ключей = добавление нового `kid` при валидности старых для проверки
  (конституция V: «ротация ключей»).
- **Мгновенный отзыв** (logout / смена пароля / компрометация): **Redis denylist по `sid`**,
  TTL записи = остаток жизни access-токена. Обычный путь — отсутствие записи (GET ~0.1 мс),
  поэтому per-request overhead минимален; состояние — во внешнем хранилище (конституция II).
- **Refresh-токен**: opaque 256-bit (`SecureRandom`, base64url, ~43 симв.), в БД хранится
  **только SHA-256-хеш**. Ротация атомарным условным UPDATE:
  `UPDATE refresh_tokens SET status='rotated', replaced_by=:new WHERE id=:id AND token_hash=:hash AND status='active'`;
  rowcount = 0 → **повторное использование** → session → `compromised`, отзыв всей цепочки +
  denylist `sid`. TTL **3 дня** (sliding — продлевается при каждой ротации).
- **Хранение на клиенте** (SPA): access — только память процесса (~5 мин окна), refresh —
  `localStorage` (переживает перезагрузку; трейдофф и компенсации — в обосновании ниже).

**Обоснование**: stateless-проверка подписи масштабируется без БД-обращений; ES256 — 256-bit
безопасность, компактные подписи, у подов нет симметричного секрета, которым можно чеканить
токены. Opaque refresh не несёт claims (нечего утекать при краже), хеш-at-rest делает дамп БД
бесполезным, CAS-ротация детектирует кражу по определению (edge spec: «украден и использован
раньше владельца»). Refresh в `localStorage` — осознанный трейдофф (XSS даёт доступ к refresh):
httpOnly-cookie отклонена — вводит второй механизм аутентификации (cookie) рядом с Bearer из
контракта (принцип IV: единый API для SPA и встраиваемого виджета), требует CSRF-контура и
SameSite/CORS-настройки против кросс-доменного встраивания. Компенсации: access не пишется в
персистентное хранилище (XSS-окно ≤ TTL 5 мин); ротация при каждом использовании детектирует
кражу refresh (повторное применение отзывает цепочку, FR-006); смена пароля отзывает все сессии
(FR-010); logout отзывает пару (FR-011).

**Альтернативы отклонены**: чисто stateless без denylist — окно не-отзыва до TTL, ломает
«logout = logout» и смену пароля (US2-5, US4-2); token-version/epoch — Redis-чтение на КАЖДЫЙ
запрос без выигрыша против denylist; opaque access + session store в Redis — каждый запрос в
Redis, связывает доступность чата с Redis; RS256 — хуже размером; JWT-refresh — дешёвая
single-use ротация без per-token состояния невозможна.

## 5. Spring Security: resource-server + кастомный JwtDecoder

**Решение**: `spring-boot-starter-security` + `spring-boot-starter-oauth2-resource-server`
(nimbus-jose-jwt), кастомный `JwtDecoder` с разрешением ключей по `kid`.
`SecurityFilterChain`: `sessionManagement(STATELESS)`, csrf off, route-level правила —
permitAll только для зафиксированного списка публичных путей (`/api/v1/auth/register/**`,
`/api/v1/auth/login`, `/api/v1/auth/refresh`, `/api/v1/auth/password-reset/**`,
`/actuator/health`), `anyRequest().authenticated()`. Единый 401: `authenticationEntryPoint` →
`application/problem+json` `{"title":"Unauthorized","status":401,"detail":"Not authenticated"}`
— без различения «нет токена / истёк / отозван» (US3-1/2).

**Обоснование**: resource-server-цепочка — проверенная реализация Bearer-парсинга, clock skew,
ошибок; самописный `OncePerRequestFilter` повторяет её с большим bug surface. Route-level
конфиг в одном бине — аудит «что публично» одним взглядом (US3-4).

**Альтернативы отклонены**: самописный filter; method-level `@PreAuthorize` — рассеивает политику.

## 6. Email: заменяемый шлюз (порт/адаптер) + SMTP + PostgreSQL outbox

**Решение**:
- Порт в домене: `EmailGateway` (интерфейс), **один адаптер сейчас**: SMTP через
  `spring-boot-starter-mail` (Jakarta Mail). Dev — контейнер **Mailpit**; прод — SMTP любого
  провайдера (SES/Mailgun SMTP endpoint) через env/K8s Secret. HTTP-API адаптеры (SendGrid/SES-v2)
  — отложены до реальной потребности (DIP: добавляются новыми классами, ядро не меняется).
- **Transactional outbox** в PostgreSQL: строка `email_outbox` (тип, получатель, payload,
  status, attempts, next_attempt_at, last_error) вставляется **в той же транзакции**, что и
  пользователь/токен; `@Scheduled`-поллер (fixed-delay 5 c) забирает строки
  `SELECT ... FOR UPDATE SKIP LOCKED` (безопасно при N реплик), шлёт через шлюз, экспоненциальный
  backoff (1м → 5м → 15м, потолок 1ч, максимум 10 попыток → `failed_permanent`).
- Observability (FR-008, SC-007): счётчик Micrometer `email_delivery_total{type,outcome}` +
  gauge `email_outbox_pending` + структурные логи (`outbox_id`, `email_type`, `recipient_hash`,
  `attempt`, `provider_error` — без сырого PII).

**Обоснование**: публичный endpoint принимает запрос и возвращается сразу; сбой доставки не
роняет его (SC-007: нет 5xx), ретраи переживают рестарты (долговечность в PG), повторная
отправка работает после восстановления сервиса. Mailpit даёт наблюдаемый ящик в dev без
внешних аккаунтов.

**Альтернативы отклонены**: `@Async` прямая отправка — потеря in-flight писем при деплое,
нет долговечных ретраев (ломает SC-007); брокер сообщений — в проекте отсутствует, один
producer/consumer не оправдывает Kafka/Redis Streams; HTTP-API провайдера сейчас — YAGNI.

## 7. Идентификация источника запроса и нормализация идентификаторов

**Решение**:
- `server.forward-headers-strategy=framework`; клиентский IP — крайний левый `X-Forwarded-For`
  (единый доверенный ingress-хоп), fallback `remoteAddr` при прямом доступе в dev. IPv6
  нормализуется до /64-префикса против ротации адресов.
- Email: хранится как введён, уникальность и поиск входа — по `lower(email)`. Username: то же
  (`lower(username)`), вход регистронезависим обоими идентификаторами.
- Вход по несуществующему идентификатору: тот же код-путь и то же сообщение, пароль
  проверяется против фиктивного Argon2-хеша — выравнивание таймингов против перечисления
  аккаунтов (US2-3, edge «перечисление аккаунтов»).

**Альтернативы отклонены**: `remoteAddr` без XFF (весь трафик = IP ingress → глобальные ложные
блокировки); правый XFF (даёт ingress, бесполезен без цепочки доверия — вернуться при CDN);
citext (см. §1).

## 8. Прогрессивное замедление и блокировка подбора (SC-004)

**Решение**: Redis-счётчик `login:fail:<sha256(identifier)>` (INCR + EXPIRE NX 15 мин,
сброс только при успешном входе). n ≤ 3 — без задержки; далее серверная задержка
`min(250ms × 2^(n−3), 15s)`; n ≥ 10 — временная блокировка: 429 + `Retry-After: <TTL ключа>`
**до** проверки пароля. По истечении TTL корректный пароль работает сразу (US5-2), инструментов
разблокировки не нужно.

**Альтернативы отклонены**: жёсткая бессрочная блокировка — DoS одного аккаунта третьим лицом,
нужен unlock-тулинг; блокировка только по IP — обходится ротацией адресов.

## 9. Тестирование (конституция VI)

**Решение**:
- Backend IT: **Testcontainers** (BOM) — PostgreSQL + Redis модули + `spring-boot-testcontainers`
  + `@ServiceConnection`, общий static-контейнер в базовом классе (один контейнер на прогон
  благодаря кэшированию контекста). Проверки: полный путь регистрации, ротация refresh +
  reuse-detection, отзыв по logout/смене пароля, 401-единообразие, лимиты (429 + Retry-After),
  прогрессирующая задержка, идемпотентность подтверждения/сброса, outbox-ретраи при сбойном
  шлюзе (fake `EmailGateway`), отсутствие пароля/токенов в логах.
- Контракт: конвейер 001 без изменений (vacuum, drift, oasdiff — только additive-изменения).
- Frontend: Vitest + Testing Library — потоки страниц (register → confirm → set-password,
  login, refresh-интерцептор, единый 401-обработчик).
- Нагрузочный smoke (SC-008, конституция VI): **k6** — сценарий `load/k6/auth.smoke.js`,
  профиль из §13 (burst login + burst register с одного источника). PASS = контролируемая
  деградация: 429 + `Retry-After` при превышении лимитов, 0×5xx, `/actuator/health` — 200
  на протяжении прогона, валидные запросы под лимитом обслуживаются. Прогон локально против
  docker-compose ([quickstart §4.7](./quickstart.md)); в CI-гейт не входит (VII: нагрузочный
  стенд в CI — за пределами потребности этой фичи, критерии возврата в §13).

**Альтернативы отклонены**: H2/embedded — диалектное расхождение (выражения-индексы, JSONB)
делает тесты лживыми; Ryuk-reuse контейнеров — nice-to-have.

## 10. Dev-инфраструктура

**Решение**: в dev-overlay Kustomize — PostgreSQL (Deployment replicas:1 + strategy Recreate +
PVC 5–10Gi RWO + Secret), Redis (Deployment Recreate + PVC 1Gi; `--maxmemory 256mb
--maxmemory-policy volatile-ttl`; appendonly off — состояние эфемерно), Mailpit (+Service,
доступ через port-forward). Локальная разработка без кластера — `deploy/local/docker-compose.yml`
(postgres:17 + redis:7 + mailpit). Операторы БД (CloudNativePG и т.п.) — не вводятся (YAGNI);
prod — managed Postgres/Redis вне scope фичи.

**Обоснование**: `Recreate` обязателен при RWO PVC (RollingUpdate deadlock на повторном
attach); volatile-ttl безвреден — все ключи TTL-ированы, evicted = ближайшие к истечению.

## 11. Параметры по умолчанию (все — конфигурируемые)

| Параметр | Значение | Спека |
|---|---|---|
| access-token TTL | 5 мин | FR-006 «короткоживущий» |
| refresh-token TTL (sliding) | 3 дня | FR-006 |
| ссылка подтверждения email | 24 ч, single-use | FR-002 |
| password-setup токен (ответ confirm; письмо resend при `awaiting_password`) | 1 ч, single-use (15 мин недостаточно для ссылки в письме) | FR-003 |
| ссылка восстановления пароля | 1 ч, single-use | FR-010 |
| cooldown повторной отправки письма | 60 с | US1-6 |
| register: лимиты | 5/час на IP; 3/час на email | FR-009 |
| resend: лимиты | 3/час на IP; cooldown 60 с на email | FR-009 |
| login: лимиты | 10/мин на IP; 10/мин на аккаунт; замедление с 4-й неудачи; блокировка с 10-й на 15 мин | FR-009, SC-004 |
| password-reset: лимиты | 5/час на IP; 3/час на email | FR-009 |
| confirm / password / reset-confirm: лимиты | 30/мин на IP (только IP-ключ; single-use 256-bit токены — лимит от флуда, не от подбора) | FR-009 |
| refresh: лимиты | 30/мин на IP (без аккаунт-ключа — идентификатора в запросе нет; щедро для NAT/мультидевайс: легитимный refresh ≤1/5 мин на устройство) | FR-009 |
| outbox: поллинг / backoff / макс. попыток | 5 с / 1→5→15 мин (потолок 1 ч) / 10 | FR-008 |

**Resend: два механизма защиты (не дублирование)**. (1) Бакет `rl:email:resend` — abuse-контроль
по источнику/email-hash, эфемерное состояние Redis, срабатывает ПЕРВЫМ в `RateLimitFilter`
(429 + `Retry-After`). (2) Cooldown 60 с в outbox-репозитории — бизнес-гарантия US1-6 «не чаще
интервала на аккаунт»: атомарен в TX, переживает потерю Redis, ловит ротацию IP; срабатывает
вторым (429, `Retry-After` = 60 − elapsed). Оба ответа — единый 429 problem+json, клиенту
неразличимы. Cooldown-429 возникает только для существующих аккаунтов — микросигнал перечисления
сглажен бакетом, применяемым к любому email из запроса одинаково.

## 12. Готовность к SSO (фича 003)

**Решение**: граница абстракции — «проверка credentials → verified userId → выдача сессии».
Сервис сессий/токенов не знает, как доказана личность. `users.password_hash` — NULLable
(требуется уже сейчас для состояния «email подтверждён, пароль не задан»). Отдельную таблицу
`user_identities` НЕ создаём (VII: абстракция при появлении второй реализации — фича 003).

## 13. Расчёт нагрузки (конституция II)

Худший режим в бюджетах конституции: 1M пользователей, burst логинов ~1M/час ≈ 280 rps.
Верификация ES256 — микросекунды CPU на под; Argon2id ~50–100 мс на логин — приемлемо при
280 rps (лимитируется числом подов). Запись в PG — только на login/refresh (сотни rps — штатно
для PG 17); Redis: O(1) CAS на публичный запрос + O(1) GET denylist на аутентифицированный
запрос — при 100k msg/s это ~100k GET/s, в пределах одного Redis-инстанса (~1M ops/s), при
росте — Redis Cluster/реплики чтения. HikariCP max 10 соединений на под: сотни подов до
пределов PG; PgBouncer — отложен (YAGNI), критерий возврата: pod_count × pool ≈ 100.
Stateless-поды масштабируются горизонтально — все состояния (PG, Redis) внешние.

**Профиль нагрузочного smoke (SC-008)**: burst `login` ≈280 rps (бюджет выше) с вращающимися
identifier'ами + burst `register` 50 rps с одного источника. При активных лимитах (§11)
ожидаемо большинство запросов отклоняется 429 — это и есть контролируемая деградация SC-008;
несущая способность подтверждается расчётом выше и smoke-прогоном на отсутствие 5xx/отказов.
Критерий возврата к полноценному нагрузочному тестированию в CI: профиль фичи приближается
к продуктивному трафику сообщений (100k msg/s) — пока неприменимо.
