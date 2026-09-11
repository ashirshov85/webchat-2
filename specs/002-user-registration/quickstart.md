# Quickstart: Регистрация, вход и управление сессиями

**Feature**: 002-user-registration | **Date**: 2026-09-11

Руководство по end-to-end проверке фичи вручную и автотестами. Ссылки на детали:
[контракт API](./contracts/api-contract.md), [модель данных](./data-model.md),
[решения](./research.md).

## 1. Предпосылки

- JDK 21, Node 24 + pnpm, Docker (Testcontainers и локальная инфраструктура).
- Клон репозитория, ветка `002-user-registration`.

## 2. Локальная инфраструктура

```bash
docker compose -f deploy/local/docker-compose.yml up -d
# PostgreSQL 17 :5432, Redis 7 :6379, Mailpit :8025 (UI) / :1025 (SMTP)
```

Переменные окружения backend (dev-значения; прод — K8s Secret, [research.md §6](./research.md)):
`SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME/PASSWORD`, `SPRING_DATA_REDIS_HOST`,
`SPRING_MAIL_HOST=localhost`, `SPRING_MAIL_PORT=1025`, `AUTH_JWT_KEYS` (kid → P-256 PEM),
`AUTH_JWT_ACTIVE_KID`, `APP_PUBLIC_BASE_URL=http://localhost:5173`.

## 3. Запуск

```bash
./gradlew bootRun        # workdir backend/ — Flyway применяет V-миграции при старте
pnpm --dir frontend dev  # SPA на :5173
```

## 4. Сценарии проверки (ручные, через Mailpit UI http://localhost:8025)

Каждый сценарий соответствует acceptance-сценариям спека; ожидания — по контрактy.

### 4.1 Полная регистрация (US1) — SC-001

1. `POST /api/v1/auth/register` `{username:"alice", email:"alice@example.com"}` (или форма SPA
   `/register`) → **202**; в БД аккаунт `pending_email_confirmation`, пароля нет.
2. Письмо в Mailpit содержит одноразовую ссылку `/confirm-registration?token=...` (TTL 24 ч).
3. Переход по ссылке (SPA вызывает `POST /auth/register/confirm`) → **200** +
   `setupToken` (TTL 1 ч).
4. `POST /auth/register/password` `{setupToken, password, confirmPassword}` → **204**;
   аккаунт `active`. Повторный вызов с тем же setupToken → **400** (идемпотентность поглощения).
5. Вход `POST /auth/login` → **200** с парой токенов.

### 4.2 Конфликты и незавершённость (US1)

- Регистрация занятых активным аккаунтом username/email → **409** с указанием поля; письмо не
  ставится в очередь.
- Полное совпадение пары на незавершённом аккаунте → **202**, дубля нет, новое письмо следующего шага.
- Вход до завершения (нет пароля) → **403** «завершите регистрацию».
- Повторная отправка чаще 60 с → **429** + `Retry-After`.
- Истёкший setupToken → resend для подтверждённого email шлёт письмо `password_setup` со ссылкой
  `/set-password?token=...`; задание пароля по новой ссылке → аккаунт `active`.

### 4.3 Вход, ротация, отзыв (US2)

1. `POST /auth/login` с `identifier` = username, затем = email → оба **200** (равноправие).
2. Неверный пароль / несуществующий identifier → одинаковый **401** `Invalid credentials`
   (сравнить тайминги).
3. `GET /api/v1/users/me` c `Bearer <accessToken>` → **200**; без токеня, с мусором, с
   отозванным → единый **401** `Not authenticated`.
4. `POST /auth/refresh` со refresh → **200** новая пара; повторный refresh старым токеном →
   **401**, и вся цепочка сессии отозвана (me по старому access → 401 после истечения ≤5 мин,
   сразу — из-за denylist).
5. `POST /auth/logout` → **204**; оба токена недействительны.

### 4.4 Восстановление пароля (US4)

1. `POST /auth/password-reset` на существующий email → письмо со ссылкой (TTL 1 ч);
   на несуществующий → тот же **202** (не раскрывает).
2. `POST /auth/password-reset/confirm` `{token, newPassword}` → **204**; вход старым паролем →
   **401**, новым → **200**; прежние refresh/access отозваны (denylist).
3. Повторное применение ссылки → **400** с предложением запросить новую.

### 4.5 Rate limiting и защита от подбора (US5) — SC-004

1. 6-й `POST /auth/register` за час с одного IP → **429** + `Retry-After`.
2. 11 неудачных `POST /auth/login` подряд по одному аккаунту в течение минуты: видны
   возрастающие задержки ответов, затем **429** с `Retry-After` ≈ остаток 15 мин; после
   истечения блокировки корректный пароль входит сразу.
3. Лимиты считаются в Redis (общие для всех реплик): перезапуск backend не сбрасывает
   заблокированный счётчик.
4. 31-я попытка `POST /auth/refresh` или `POST /auth/register/confirm` в минуту с одного IP →
   **429** + `Retry-After` (FR-009: лимиты действуют на всех публичных endpoints).

### 4.6 Отказ email-сервиса (US6) — SC-007

1. `docker stop <mailpit>`; `POST /auth/register` → всё равно **202** (нет 5xx).
2. `docker compose ... up -d` снова → поллер outbox доносит письмо (backoff), метрика
   `email_delivery_total{outcome=...}` и лог `outbox_id/last_error` фиксируют сбой и доставку;
   gauge `email_outbox_pending` виден на `/actuator/prometheus`.

### 4.7 Нагрузочный smoke (SC-008)

1. При поднятом стеке (§2) выполнить из корня репо:
   `docker run --rm --network host -v "$PWD/load/k6:/k6" grafana/k6 run /k6/auth.smoke.js`
2. Ожидание: burst register/login сверх лимитов → **429** + `Retry-After`; **0×5xx**;
   `/actuator/health` — **200** на протяжении прогона; валидные запросы под лимитом —
   200/202/204, их `http_req_duration` **p95 ≤ 500 мс** (SC-003). Критерии зашиты как
   k6 thresholds — ненулевой exit code при нарушении
   (профиль — [research.md §13](./research.md)).

## 5. Автопроверка

```bash
./gradlew test                                  # workdir backend/: unit + Testcontainers IT (Docker)
pnpm --dir frontend test                        # Vitest: страницы и auth-интерцептор
pnpm --dir frontend lint && ./gradlew ktlintCheck detekt
vacuum lint -e contracts/openapi.yaml           # контракт валиден
oasdiff breaking origin/main:contracts/openapi.yaml contracts/openapi.yaml  # только additive
```

Интеграционные тесты покрывают сценарии §4 программно: полный путь регистрации, ротация +
reuse-detection, отзыв по logout/смене пароля, единый 401, 429/Retry-After, прогрессирующая
замедляемость входа, идемпотентность ссылок, outbox-ретраи на сбойном шлюзе (fake
`EmailGateway`), отсутствие пароля/открытых токенов в логах (SC-005).

## 6. Ожидаемый итог

Все acceptance-сценарии US1–US6 проходят; `paths` контракта содержат ровно endpoints 1–10
[контракта](./contracts/api-contract.md); `git status` чист после регенерации TS-типов.
