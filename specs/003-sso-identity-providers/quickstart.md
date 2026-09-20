# Quickstart: SSO — вход через внешние identity-провайдеры (OIDC/OAuth2)

**Feature**: `003-sso-identity-providers` | **Date**: 2026-09-16

Руководство по ручной end-to-end проверке фичи. Контракты — [contracts/sso-api.md](contracts/sso-api.md),
модель данных — [data-model.md](data-model.md). Интеграционные автотесты (MockIdP)
покрывают те же сценарии автоматически — ручная проверка вторична, но обязательна
для UI-флоу (экран входа, настройки безопасности).

## Предусловия

- Docker + docker compose; pnpm ≥12, Node ≥24, JDK 21 (как в фиче 002).
- Локальная инфраструктура 002: `deploy/local/docker-compose.yml`
  (postgres:17 `:5432`, redis:7 `:6379`, mailpit `:8025`).
- **Локальный IdP**: dex-контейнер (`deploy/local/dex/`, добавляется реализацией;
  конфиг со статическими пользователями `alice@example.com` (S1), `bob@example.com`
  (S2 — JIT), `carol@example.com` (S3 — автосвязывание), `dave@example.com`
  (S3 шаг 4 — email_conflict) и redirect-uri
  `http://localhost:8080/api/v1/auth/sso/callback`).

## Настройка и запуск

```bash
# 1. Инфраструктура (включая локальный dex IdP на :5556)
docker compose -f deploy/local/docker-compose.yml --profile sso up -d

# 2. Backend: провайдер dex с явными endpoints, opt-in через env, секрет из env
export SSO_DEX_ENABLED=true
export SSO_DEX_CLIENT_SECRET=dev-secret
./gradlew bootRun   # workdir backend/; application.yml: sso.providers.dex.*

# 3. Frontend
pnpm --dir frontend dev
```

Схема конфигурации провайдера и TTL — [data-model.md §4](data-model.md);
client secret передаётся только env (K8s Secrets в dev-кластере), в репозитории
секретов нет — gitleaks в CI это проверяет (SC-004; единственное исключение —
dev-only dummy-секрет staticClient dex в `deploy/local/dex/`, см. Assumptions spec.md).

## Сценарии проверки

### S1. Вход через провайдера с единой сессией (US1, P1)

1. Открыть SPA `/login` → видна кнопка `dex`.
2. Нажать → редирект на IdP → аутентификация `alice@example.com`.
3. Возврат в SPA вошедшим (`/sso/callback` → чат).
4. **Ожидаемо**: пара токенов того же формата, что при парольном входе;
   `GET /api/v1/users/me` по токену сессии → 200; продление
   `POST /api/v1/auth/refresh` ротирует refresh; `POST /api/v1/auth/logout`
   отзывает оба (повторное использование → 401).
5. **Ожидаемо**: флоу уложился в 3 взаимодействия (SC-001): выбор провайдера →
   аутентификация → возврат.

### S2. Первый вход: JIT-аккаунт (US2, P1)

1. На IdP войти новым пользователем `bob@example.com` (verified email).
2. **Ожидаемо**: автоматический вход; создан active-аккаунт с email
   `bob@example.com`, системным username, без пароля; письма в mailpit нет.
3. `GET /api/v1/users/me/identities` → одна привязка dex.
4. Парольный вход `bob@example.com` → 401 (FR-013); после password-reset из
   письма (mailpit) парольный вход работает.

### S3. Первый вход: автосвязывание по allowlist (US2, P1)

1. Зарегистрировать паролем (фича 002) аккаунт `carol@example.com`; провайдер
   dex сконфигурирован с `trusted-for-email-linking: true`.
2. Войти через IdP пользователем с verified `carol@example.com`.
3. **Ожидаемо**: вход в существующий аккаунт (новый не создан), привязка
   добавлена; пароль не запрашивался.
4. Зарегистрировать паролем аккаунт `dave@example.com` (фича 002), переключить
   dex на `trusted-for-email-linking: false` (рестарт backend) и войти через IdP
   пользователем `dave@example.com`: вход завершается `sso_error=email_conflict`,
   предложение войти по паролю.

### S4. Ручные привязки (US3, P2)

1. Войти паролем → `/settings/security` → список привязок (после S3 — с dex).
2. Привязать ещё одного провайдера (второй dex-клиент или повторный вход другим
   пользователем IdP) → появляется в списке, вход через него работает.
3. Отвязать провайдера при наличии пароля → 204; повторный вход через него — по
   правилам первого входа: `trusted-for-email-linking: false` →
   `sso_error=email_conflict`, `: true` с совпадающим verified email → повторная
   автопривязка и вход; активные сессии живут до logout.
4. JIT-аккаунт без пароля: попытка отвязать единственную привязку → 409
   `last_login_method` с предложением задать пароль.

### S5. Несколько провайдеров и секреты (US4, P2)

1. Добавить второго провайдера: в `deploy/local/dex/` — второй staticClient
   (`webchat2`, тот же dummy-секрет), в `backend/src/main/resources/application.yml` —
   блок `sso.providers.dex2.*` (тот же issuer/endpoints dex, другой `client-id`,
   `enabled: ${SSO_DEX2_ENABLED:false}`, секрет — плейсхолдер `${SSO_DEX2_CLIENT_SECRET:}`);
   `export SSO_DEX2_ENABLED=true SSO_DEX2_CLIENT_SECRET=dev-secret`, рестарт backend →
   экран входа перечисляет обоих (`dex`, `dex2`), вход работает через каждого.
2. Выключить одного (`enabled: false`, рестарт) → исчез с экрана; прямой
   `POST /api/v1/auth/sso/authorize {providerId}` → 404; привязки и остальные
   способы входа не затронуты.
3. `rg -i "client.?secret" --hidden -g '!node_modules' -g '!deploy/local/dex/**'`
   по репозиторию → только плейсхолдеры `${SSO_*_CLIENT_SECRET}` (и dummy
   `dev-secret` в `deploy/local/dex/` — исключение из Assumptions); в логах
   backend секретов нет.

### S6. Защита флоу (US5, P2)

1. Повторно открыть URL callback (скопированный из истории) → `sso_error=invalid_state`,
   сессия не создаётся.
2. `for i in $(seq 1 40); do curl -s -o /dev/null -w "%{http_code}\n" \
   http://localhost:8080/api/v1/auth/sso/providers; done` → после 30 запросов в
   минуту — 429 с `Retry-After` (значения лимитов — research §9).
3. Остановить dex-контейнер → вход через dex завершается `sso_error=provider_error`
   ≤5 c; парольный вход и `/login` работают; в `auth_events` — `sso_flow_error`,
   метрика `sso_flow_total{outcome="provider_error"}` растёт (`/actuator/prometheus`).

### S7. Yandex на dev-стенде — oauth2-userinfo (US6, P1; дополнение 2026-09-19)

Проверка реального OAuth2-провайдера без OIDC на dev-стенде
(`https://dev.webchat.lkshr.ru`); локально — только через MockIdP
(автотесты, research §21).

**Предусловия**:

1. Приложение на [oauth.yandex.ru](https://oauth.yandex.ru): платформа
   «Веб-сервисы»; Redirect URI = `SSO_CALLBACK_URL`
   (`https://dev.webchat.lkshr.ru/api/v1/auth/sso/callback`); доступ
   «Адрес электронной почты» **обязателен** — без него
   `GET login.yandex.ru/info` не возвращает `default_email`, вход
   завершается `email_not_verified` / `provider_error` (research §20).
   ClientID/ClientSecret приложения — на следующем шаге.
2. Secret `sso-credentials` вне репозитория (SC-004), ключи Яндекса:
   `SSO_YANDEX_CLIENT_ID` / `SSO_YANDEX_CLIENT_SECRET` (команда — в шапке
   `deploy/k8s/overlays/dev/backend-auth-env.yaml`); overlay уже выставляет
   `SSO_YANDEX_ENABLED=true` и `SSO_CALLBACK_URL`. Деплой overlay:
   `kubectl kustomize deploy/k8s/overlays/dev | kubectl apply -f -`.
3. Конфигурация провайдера — `application.yml` `sso.providers.yandex`
   (research §20): `protocol: oauth2-userinfo`, явные endpoints
   `oauth.yandex.ru/authorize|token` + `login.yandex.ru/info`,
   `subject-claim: psuid`, `email-claim: default_email`,
   `email-verified-mode: provider-guaranteed`, `pkce: false`; issuer удалён
   (T059) — OIDC-discovery не используется.

**Ручная проверка**:

1. `/login` → кнопка «Yandex ID» → вход Яндекс-аккаунтом → возврат в SPA
   с той же парой токенов, что при парольном входе (`GET /api/v1/users/me`
   → 200; флоу — как S1, но профиль получен backchannel-запросом
   `login.yandex.ru/info`, `id_token` не выдаётся).
2. JIT: первый вход новым Яндекс-аккаунтом → создан active-аккаунт с
   email = `default_email` (считается подтверждённым), системным username,
   без пароля; `GET /api/v1/users/me/identities` → привязка `yandex`.
3. Автосвязывание: существующий парольный аккаунт с тем же email, что
   `default_email` Яндекс-аккаунта (`trusted-for-email-linking: true`) →
   вход в существующий аккаунт, привязка добавлена (аналог S3-1/2).
4. Привязки `/settings/security`: привязать/отвязать Yandex — правила как
   в S4 (отвязка единственной привязки JIT-аккаунта без пароля → 409
   `last_login_method`).

**Диагностика 502** (`sso_error=provider_error`):

- Изоляция per-provider: Яндекс недоступен/ошибается → только его флоу
  завершается ошибкой ≤5 c; Google, dex и парольный вход работают
  (SC-005, FR-010); в `auth_events` — `sso_flow_error`, растёт
  `sso_flow_total{outcome="provider_error"}`; длительность userinfo-лага —
  `sso_idp_call_duration{kind=userinfo}` (`/actuator/prometheus`).
- Discovery больше не используется: у `yandex` нет issuer — не проверять
  `.well-known/openid-configuration`, а проверить прямую достижимость
  `https://oauth.yandex.ru/token` и `https://login.yandex.ru/info`
   (например, `curl -s -o /dev/null -w '%{http_code}\n'` из пода backend) и
  валидность client_id/secret в Secret `sso-credentials`.
- `email_not_verified` на первом входе → в приложении oauth.yandex.ru не
  включён доступ «Адрес электронной почты» (см. предусловие 1).

**Откат**: `SSO_YANDEX_ENABLED=false` (изменить env в overlay, рестарт
деплоя) → «Yandex ID» исчезает с экрана входа, прямой
`POST /api/v1/auth/sso/authorize {providerId:"yandex"}` → 404; существующие
привязки `yandex` и остальные способы входа не затронуты.

**Приёмка**: раздел прошёл ручную проверку на dev-стенде после деплоя;
результат фиксируется в PR.

### S8. VK ID на dev-стенде — oauth2-userinfo с device_id (P1; дополнение 2026-09-19)

Третий реальный провайдер: VK ID (`id.vk.com`) — тот же `oauth2-userinfo`
режим, что Яндекс, но с особенностями VK: профиль вложен в объект `user`
(dot-path клеймы `user.user_id` / `user.email` / `user.email_verified`),
креды клиента уходят в POST-body token-запроса (`client-auth: post`), а
authorize-выдаанный `device_id` возвращается в обмен кода
(`token-device-id: true` — callback пробрасывает параметр автоматически).

**Предусловия**:

1. Приложение на [dev.vk.com](https://dev.vk.com) (тип «Веб-сайт» /
   «Веб»): Redirect URI = `SSO_CALLBACK_URL`
   (`https://dev.webchat.lkshr.ru/api/v1/auth/sso/callback`); «Доступ к
   e-mail» включён — без него `GET id.vk.com/oauth/user_info` не вернёт
   `user.email`, вход завершится без адреса (no JIT / no auto-link).
   ClientID («ID приложения»)/ClientSecret («Защищённый ключ») — на
   следующем шаге.
2. Secret `sso-credentials` вне репозитория (SC-004), ключи VK:
   `SSO_VK_CLIENT_ID` / `SSO_VK_CLIENT_SECRET` (команда — в шапке
   `deploy/k8s/overlays/dev/backend-auth-env.yaml`); overlay выставляет
   `SSO_VK_ENABLED=true`.
3. Конфигурация провайдера — `application.yml` `sso.providers.vk`: явные
   endpoints `id.vk.com/authorize` + `id.vk.com/oauth/token` +
   `id.vk.com/oauth/user_info`, `scopes: [email]`, PKCE S256 включён
   (VK поддерживает RFC 7636 — в отличие от Яндекса).

**Ручная проверка**: как S7 (вход → единая сессия, JIT по
`user.email` при `user.email_verified=true`, автосвязывание, привязки),
кнопка «VK ID».

**Диагностика 502** (`sso_error=provider_error`): как S7, но прямая
достижимость `https://id.vk.com/oauth/token` и
`https://id.vk.com/oauth/user_info`; `device_id`-лег проверяется
автотестами (`SsoOauth2FlowIT`), на стенде его нарушения не бывает.

**Откат**: `SSO_VK_ENABLED=false` → «VK ID» исчезает с экрана входа,
привязки `vk` не затронуты.

**Приёмка**: раздел прошёл ручную проверку на dev-стенде после деплоя;
результат фиксируется в PR.

### S9. Экраны входа и регистрации — визуальный прототип (US7+US8, P2; дополнение 2026-09-20)

Презентационный редизайн `/login` по дизайн-прототипу (research §22.1–22.4):
«латунная» карточка на полноэкранном фоне, шрифты Cinzel/EB Garamond
(self-host `@fontsource`), шестерёнка-бейдж, заклёпки, поля с иконками, глаз
показа пароля, SSO-кнопки «Продолжить с …» с брендовыми иконками. Контракт и
поведение входа не меняются (FR-018): стили scoped в `auth-theme.css`,
`index.css` не тронут. Экран регистрации `/register` (US8) применяет тот же
визуальный язык без отдельного прототипа (research §22.6): общий
`auth-theme.css`, та же карточка/палитра; шаги регистрации — ниже (шаги 9–14).

**Предусловия**:

1. `pnpm --dir frontend install` (зависимости `@fontsource/*`), фон
   `frontend/public/bg2.png` в репозитории.
2. Для SSO-части — как S1 (локально: `deploy/local` + dex, или dev-стенд);
   без провайдеров сценарий проверяется на парольной форме (шаг 2).

**Ручная проверка** (`pnpm --dir frontend dev`):

1. Фон/карточка: `/login` — фон `bg2.png` на весь экран (center/cover) с
   радиальным затемнением; карточка по центру (380px, max 90vw), рамка
   2px золото-тёмная, тройная тень + blur; заклёпки ×4 по углам (10px,
   радиальный градиент).
2. Шрифты/бренд: STEAMCHAT (Cinzel 800, letter-spacing) + подпись
   CONNECT·CHAT·EXPLORE; тексты полей/кнопок — EB Garamond, кириллица
   («Email или имя пользователя», «Пароль», «ВОЙТИ») без fallback на
   системный шрифт.
3. Функциональный паритет входа: неверные креды → ошибка в карточке
   (role=alert, problem-текст без изменений); верные → welcome; «ВОЙТИ»
   блокируется на отправке (disabled = submitting); autoComplete полей
   сохранён.
4. Глаз в поле пароля: кнопка переключает type password ↔ text и
   aria-label «Показать пароль» ↔ «Скрыть пароль».
5. SSO-кнопки (при включённых провайдерах): разделитель «ИЛИ», кнопки
   «Продолжить с {displayName}» с иконками (google/github/yandex/vk —
   брендовые, dex и прочие — generic) в порядке конфигурации; клик →
   переход на authorize (как S1); при пустом перечне блок и разделитель
   скрыты.
6. h1 скрыт: глобальный заголовок «WebChat» отсутствует на `/login` и на
   `/register` (US8); на прочих маршрутах (например, `/forgot-password`) —
   присутствует.
7. `prefers-reduced-motion: reduce` (DevTools → Rendering → emulate CSS
   media feature) — шестерёнка-бейдж статична; без флага — вращается
   (18s linear infinite).
8. Регресс окружения: страницы вне scope (confirm-registration,
   set-password, forgot/reset, sso/callback) — без визуальных изменений;
   узкий вьюпорт (mobile) — карточка ≤90vw, при малой высоте прокручивается,
   бейдж не обрезается.

Регистрация (US8, research §22.6):

9. Карточка того же стиля: `/register` — тот же фон `bg2.png` с затемнением,
   карточка `.auth-card` с заклёпками ×4, шестерёнка-бейдж, бренд-блок
   STEAMCHAT + CONNECT·CHAT·EXPLORE; заголовок формы «Создание аккаунта»
   (EB Garamond, золотой, по центру).
10. Поля и кнопка: «Имя пользователя» (иконка юзера) и «Email» (иконка
    конверта) — то же оформление, что в `/login` (sr-only label + placeholder,
    кириллица без fallback), `autoComplete="username"/"email"` сохранён; глаза
    нет — пароль задаётся по ссылке из письма (фича 002); кнопка
    «ЗАРЕГИСТРИРОВАТЬСЯ» — тот же золотой градиент, что «ВОЙТИ», блокируется
    на отправке (disabled = submitting). Divider «ИЛИ» и SSO-кнопки
    отсутствуют: первый вход через провайдера создаёт аккаунт сам (US2/S2).
11. Навигация `/login` ↔ `/register`: футер `/login` «Нет аккаунта?
    Зарегистрироваться» → `/register`; футер `/register` «Уже есть аккаунт?
    Войти» → `/login` (шаги 1–2 применимы к обеим страницам).
12. Поведение формы: незанятые данные → HTTP 202 → success-сообщение в
    карточке (role=status): «Проверьте свою почту ({email}): мы отправили
    ссылку для подтверждения, она действует 24 часа»; невалидные данные
    (короткое имя/некорректный email) → 400, занятые username/email → 409 —
    ошибка в карточке (role=alert, тексты `problemMessage` без изменений).
13. h1 скрыт на `/register` (как на `/login`, шаг 6); на прочих маршрутах —
    присутствует.
14. Регресс US8: экраны confirm-registration/set-password (переход по ссылке
    из письма) — вне scope, старый стиль; мобильный вьюпорт — карточка
    регистрации ≤90vw, прокручивается при малой высоте.

**Откат**: revert Phase 10–11 (tasks.md T063–T069, T070–T073) — поведение
входа и регистрации не зависело от редизайна.

**Приёмка**: раздел прошёл ручную проверку локально (`pnpm --dir frontend
dev`); фиксация в PR. DOM-контракт (русские label/кнопки, глаз, SSO-блок)
автоматизирован в `LoginPage.test.tsx` / `LoginPageSso.test.tsx` (T064);
регистрация — в `RegisterPages.test.tsx` (T070); финальные прогоны — T069
(US7) и T073 (US8).

## Автоматизированные проверки

```bash
# workdir backend/ — интеграционные тесты SSO (Testcontainers: PG17, Redis7, MockIdP)
./gradlew test --tests 'webchat.backend.sso.*'

# Полный прогон с линтами
./gradlew check && pnpm --dir frontend test && pnpm --dir frontend lint

# Контракт
pnpm --dir frontend generate:api && git diff --exit-code -- frontend/src/api/schema.d.ts
vacuum lint -e contracts/openapi.yaml
oasdiff breaking origin/main:contracts/openapi.yaml contracts/openapi.yaml

# Нагрузочный smoke (вкл. SSO-сценарии: providers/authorize/token-error)
docker run --rm --network host -v "$PWD/load/k6:/k6" grafana/k6 run /k6/auth.smoke.js
```

**Ожидаемые итоги**: все suite'ы зелёные; oasdiff без breaking-изменений;
в smoke — 0×5xx, SSO-лимиты отвечают 429 + `Retry-After ≥ 1`.

## Ссылки

- Контракты: [contracts/sso-api.md](contracts/sso-api.md) (после реализации —
  `contracts/openapi.yaml` 0.3.0).
- Решения проектирования: [research.md](research.md); модель данных:
  [data-model.md](data-model.md).
