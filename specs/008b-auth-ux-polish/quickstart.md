# Quickstart: 008b-auth-ux-polish

**Дата**: 2026-10-11 | Руководство по сквозной проверке фичи. Контракт:
[contracts/auth-ux-polish-api.md](./contracts/auth-ux-polish-api.md); модель данных:
[data-model.md](./data-model.md); решения: [research.md](./research.md).

## Предусловия

- Зависимости: `docker compose -f deploy/local/docker-compose.yml up -d` (PostgreSQL 17,
  Redis 7, Mailpit — приём писем сброса на `localhost:8025`)
- Backend: `./gradlew check` (из `backend/`, JDK 21) — включает миграцию V17 и
  интеграционные тесты фичи (`PasswordChangeIT`, `AdminSeedIT`, `StarterContactIT`)
- Фронтенд: `pnpm --dir frontend install` → после правки `contracts/openapi.yaml`
  `pnpm --dir frontend generate:api` (регенерация `schema.d.ts`) → `pnpm --dir frontend
  test` + `lint` + `typecheck`
- Контрактные гейты: `scripts/validate-contracts.sh` (vacuum + drift + oasdiff breaking =
  0; версия 0.10.0)
- Запуск: backend (`./gradlew bootRun`) + `pnpm --dir frontend dev`; два браузерных
  профиля для мультидевайс-сценариев US2
- Seed: переменные окружения не заданы ⇒ admin создаётся беспарольным
  (`admin@webchat.local`); пароль ему задаётся письмом сброса (Mailpit) — это же проверка
  беспарольной формы seed

## Сценарии проверки (по User Stories спеки)

### US1 — Редирект после парольного входа с returnTo (P1)

1. **Вход → чат**: выйти, открыть `/login`, ввести корректные credentials → после успеха
   браузер на `/chat`; на странице входа не остаётся (SC-001).
2. **returnTo**: в разлогиненном состоянии открыть `/settings/security` → выброс на
   `/login?returnTo=/settings/security` (существующий ProtectedRoute) → войти по паролю →
   возврат на `/settings/security`.
3. **Небезопасный returnTo**: `/login?returnTo=https://evil.example`, `?returnTo=//evil.example`,
   `?returnTo=/\evil.com`, `?returnTo=/a/../b` и `?returnTo=/login`
   → после входа `/chat` (open redirect исключён, петля на /login исключена, US1 AC3, FR-002 (б)–(д)).
4. **Регресс SSO**: вход через провайдера 003 → возврат по `returnTo`/`/chat` как раньше
   (US1 AC5); `SsoCallbackPage.test.tsx` зелёный.

### US2 — Смена пароля в «Мой профиль» (P1)

1. **Успех**: войти на устройстве A → «Мой профиль» → смена пароля (корректный текущий +
   валидный новый + совпадающее подтверждение) → toast успеха; выйти → вход по новому
   паролю успешен, по старому — 401 «Invalid credentials» (US2 AC2).
2. **Неверный текущий**: форма отвечает понятной ошибкой (400 `currentPassword: invalid`),
   пароль не меняется (AC3).
3. **Клиентская валидация**: несовпадение подтверждения, длина вне 8–128, пробельный,
   совпадающий с username/email → ошибки до отправки, `changePassword` не вызывается
   (AC4); слабый из топ-листа (`password_change` не сработал) → серверное 400
   `too_common` с понятным сообщением.
4. **Rate limit**: 6-я смена в минуту → 429 + подсказка повтора (`Retry-After`), сервис
   жив; через минуту смена проходит (AC5, SC-004).
5. **Аудит**: в `auth_events` есть `password_change_completed` / `password_change_failed`
   без паролей и токенов; хранилище: `users.password_hash` — Argon2id, открытого пароля
   нигде нет (AC6–7, SC-004).
6. **Мультидевайс (FR-008)**: устройство B в системе → смена на A → B при первом действии
   требует вход (сессия/refresh отозваны, `session_revoked` c `password_change`); A
   остаётся вошедшим, его refresh ротирован (новая пара применена без разрыва);
   совпадающий с прежним новый пароль — отзыв прочих сессий всё равно выполняется (AC8,
   clarify).
7. **JIT-аккаунт**: SSO-пользователь без пароля → в профиле формы нет, подсказка со
   ссылкой на `/forgot-password` (FR-004); `GET /users/me` → `hasPassword: false`.

### US3 — Ссылка сброса на экране входа (P2)

1. `/login` → видна «Забыли пароль?» в футере → открывает `/forgot-password`.
2. Запрос сброса по email → письмо в Mailpit → задание нового пароля по ссылке → вход с
   новым паролем; семантика 002 не изменилась (единый ответ, отзыв всех сессий) —
   регрессионный прогон `PasswordResetIT` зелёный.

### US4 — Стартовый контакт admin (P2)

1. **Seed**: после старта backend в `users` есть ровно один `admin` (active); повторный
   рестарт/второй инстанс — без дублей и перезаписи (FR-012, SC-сценарий 1).
2. **Новая регистрация**: завершить регистрацию 002 (email-подтверждение + пароль) →
   первый вход → «Контакты»: `Administrator` ровно один раз (AC2); SSO JIT-регистрация —
   тот же результат (edge spec.md:104).
3. **Идемпотентность**: повторная доставка события (перезапуск/ретрай не создаёт дублей);
   симуляция потери события (тест `StarterContactIT`: событие не доставлено) → первый
   `GET /contacts` добавляет admin ровно один раз (AC3–4, SC-003).
4. **Удаление**: удалить admin из контактов → повторные входы/запросы контактов не
   возвращают его (AC5).
5. **Обычный контакт**: диалог с admin — стандартный личный чат 004 (AC6).
6. **Бэкфилла нет**: пользователь, зарегистрированный до деплоя фичи, admin в контактах
   не получает (edge spec.md:109; `starter_contact_added = TRUE` проставлен миграцией).

## Автоматизированная проверка

- Backend: `./gradlew test --tests 'webchat.backend.auth.PasswordChangeIT' --tests
  'webchat.backend.onboarding.*'` + существующие `SessionIT`, `PasswordResetIT`,
  `RateLimitIT`, `SsoFlowIT`, `ContactsIT` (регресс SC-005)
- Frontend: `pnpm --dir frontend test` (returnTo-util, LoginPage redirect/link,
  ProfileModal форма/JIT-гейтинг/токены)
- Контракт: `scripts/validate-contracts.sh` + CI job `contract` (drift + oasdiff)

## Критерий готовности проверки

Все сценарии US1–US4 пройдены; регрессионный прогон 002/003/004 зелёный; oasdiff
breaking = 0; в PG/логах 0 открытых паролей и токенов.
