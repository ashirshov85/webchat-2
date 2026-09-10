---

description: "Task list: Каркас проекта, CI/CD и базовая инфраструктура"
---

# Tasks: Каркас проекта, CI/CD и базовая инфраструктура

**Input**: Design documents from `/specs/001-project-foundation/`

**Prerequisites**: plan.md (required), spec.md (required), research.md, data-model.md, contracts/

**Tests**: Тесты включены — Test-First обязателен (конституция VI, NON-NEGOTIABLE); smoke-тесты входят в US1 (spec.md, acceptance-сценарий 4, план → Testing); каждая story завершается верификационной задачей по quickstart.md.

**Organization**: Задачи сгруппированы по user stories (US1–US5) для независимой реализации, тестирования и доставки каждого story как самостоятельного инкремента.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Можно выполнять параллельно (разные файлы, нет зависимостей от незавершённых задач)
- **[Story]**: Принадлежность user story (US1–US5); Setup/Foundational/Polish — без метки
- Каждое описание содержит точные пути файлов и критерий приёмки (конституция → Development Workflow)

## Path Conventions

- **Монорепозиторий web app**: `backend/` (Kotlin 2.2.x + Spring Boot 3.5.x, Gradle Kotlin DSL, JDK 21), `frontend/` (React 19 + TS strict, Vite, pnpm, Node 24), `contracts/`, `deploy/k8s/` (Kustomize), `.github/workflows/`
- Структура и версии — plan.md → Project Structure / Technical Context; обоснования — research.md (§-ссылки в задачах)

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Инициализация репозитория и структуры монорепо

- [X] T001 Инициализировать git-репозиторий (ветка `main`, рабочая ветка `001-project-foundation`), создать структуру каталогов монорепо — `backend/`, `frontend/`, `contracts/`, `deploy/k8s/base/`, `deploy/k8s/overlays/dev/`, `.github/workflows/`, `docs/` — и корневой `.gitignore` (Gradle: `.gradle/`, `build/`; frontend: `node_modules/`, `dist/`; секреты: `.env`, `.env.*`) согласно plan.md → Project Structure

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Инициализация собирающихся проектов backend и frontend — инфраструктура, на которой строятся ВСЕ user stories

**⚠️ CRITICAL**: Ни одна user story не может начаться до завершения этой фазы

- [X] T002 [P] Инициализировать backend-проект Gradle (Kotlin DSL) в `backend/`: `settings.gradle.kts`, `build.gradle.kts` (плагины: Spring Boot 3.5.x, Kotlin JVM 2.2.x + `kotlin.plugin.spring`, ktlint, detekt), version catalog `backend/gradle/libs.versions.toml`, Gradle wrapper 8.x (`backend/gradlew`, `backend/gradle/wrapper/*`), toolchain JDK 21; зависимости: `spring-boot-starter-web`, `spring-boot-starter-actuator`, `micrometer-registry-prometheus`, test: `spring-boot-starter-test`; минимальный вход `backend/src/main/kotlin/webchat/backend/BackendApplication.kt`; критерий: `./gradlew check` exit 0 в `backend/` (research.md §1–§4)
- [X] T003 [P] Инициализировать frontend-проект в `frontend/`: `package.json` (packageManager: pnpm, engines: node 24; скрипты `dev`/`build`/`lint`/`typecheck`/`test`), `.nvmrc`, `.npmrc`, `pnpm-lock.yaml` (pnpm install), `tsconfig.json` strict (флаги: `strict`, `noUncheckedIndexedAccess`, `noImplicitOverride`, `noFallthroughCasesInSwitch`, `noUnusedLocals`/`noUnusedParameters`, `verbatimModuleSyntax`, `isolatedModules`, `moduleResolution: bundler`), `vite.config.ts` + конфигурация vitest (jsdom), `eslint.config.js` (flat: typescript-eslint, react-hooks, react-refresh, prettier), `.prettierrc.json`, `index.html`, `frontend/src/main.tsx`, `frontend/src/App.tsx`; критерий: `pnpm install && pnpm lint && pnpm typecheck && pnpm build` exit 0 (research.md §5–§7)

**Checkpoint**: Оба проекта собираются и линтятся — реализация user stories может начинаться параллельно

---

## Phase 3: User Story 1 - Единый каркас монорепозитория (Priority: P1) 🎯 MVP

**Goal**: Оба приложения собираются, линтятся и тестируются одной командой; стартуют локально и сообщают о готовности через health-check; smoke-тесты и линт/форматирование настроены (FR-001, FR-002).

**Independent Test**: Полностью проверяется клонированием репозитория на чистой машине: команды сборки/линта/тестов для backend и frontend завершаются успешно, оба приложения запускаются и отвечают на health-check (quickstart.md сценарии 1–3).

### Tests for User Story 1 (Test-First: тесты T004 падают до конфигурации T006)

- [X] T004 [P] [US1] Написать backend smoke-тесты: `backend/src/test/kotlin/webchat/backend/BackendApplicationTests.kt` (context-load: `@SpringBootTest` + `contextLoads()`) и `backend/src/test/kotlin/webchat/backend/HealthEndpointTests.kt` (GET `/actuator/health/liveness` и `/actuator/health/readiness` → 200 + `{"status":"UP"}`; `/actuator/prometheus` содержит `http_server_requests_seconds`); критерий: тесты написаны и FAIL до включения проб (spec.md US1-4, contracts/technical-endpoints.md §1)
- [X] T005 [P] [US1] Добавить dev-зависимости Vitest-стека (`@testing-library/react`, `@testing-library/jest-dom`, `jsdom`) в `frontend/package.json` и написать smoke-тест `frontend/src/App.test.tsx` (рендер `<App />`, проверка заголовка через Testing Library + jest-dom); критерий: `pnpm test` exit 0 (research.md §7, spec.md US1-4)

### Implementation for User Story 1

- [X] T006 [US1] Создать `backend/src/main/resources/application.yml`: `spring.application.name: backend`, `server.port: 8080`, stateless-политика сессий (SessionCreationPolicy.STATELESS, FR-007), `management.endpoints.web.exposure.include: health,prometheus`, `management.endpoint.health.probes.enabled: true`, `management.prometheus.metrics.export.enabled: true` (contracts/technical-endpoints.md §1); критерий: `./gradlew check` зелёный (тесты из T004 проходят), `./gradlew bootRun` — оба зонда отвечают `UP` в течение 60 секунд, `/actuator/prometheus` отдаёт метрики (quickstart.md сценарии 1–2)
- [X] T007 [US1] Верифицировать US1 end-to-end с чистого клона (SC-001): backend — `./gradlew check`, `./gradlew bootRun` (готовность ≤ 60 секунд); frontend — `pnpm install`, `pnpm lint`, `pnpm typecheck`, `pnpm test`, `pnpm build`, `pnpm dev` отдаёт приложение; демонстрация линта: внести нарушение стиля → `ktlintCheck`/`detekt` и `eslint`/`prettier --check` падают с указанием файла и строки; критерий: все команды exit 0, acceptance-сценарии US1-1..US1-4 (quickstart.md сценарии 1–3)

**Checkpoint**: US1 полностью функционален и проверяем независимо — MVP

---

## Phase 4: User Story 2 - Конвейер качества для каждого изменения (Priority: P1)

**Goal**: Каждый pull request автоматически проходит конвейер: сборка, линт, unit-тесты backend и frontend; падение любой стадии блокирует merge (FR-003).

**Independent Test**: Открывается PR с заведомо падающим тестом — пайплайн красный, merge заблокирован; PR с зелёными проверками — merge разрешён (quickstart.md сценарий 8).

### Implementation for User Story 2

- [X] T008 [US2] Создать `.github/workflows/ci.yml`: триггеры `pull_request` (в `main`) и `push` в `main`; `concurrency` (cancel-in-progress); джобы `backend` (setup-java temurin 21 + Gradle cache → `./gradlew check`), `frontend` (pnpm + Node 24 + pnpm-store cache → `pnpm install --frozen-lockfile && pnpm lint && pnpm typecheck && pnpm test && pnpm build`), `gitleaks` (gitleaks-action — FR-014, edge case «секрет в коде»); минимальные `permissions`; критерий: все три джоба запускаются на PR и зелёные на текущем коде (research.md §8–§9, contracts/technical-endpoints.md §5)
- [X] T009 [US2] Настроить branch protection на `main` (gh CLI / настройки репозитория): запрет прямых push, обязательные PR, required status checks `backend`, `frontend`, `gitleaks`; критерий: merge блокируется при красном любом из required checks, причина видна в результатах проверки (spec.md US2-1, FR-003)
- [X] T010 [US2] Верифицировать US2: открыть тест-PR с заведомо падающим unit-тестом → required check красный, merge заблокирован, причина видна в логе джоба; добавить фикс → проверки зелёные, merge разрешён без ручных действий; параллельные PR получают независимые runs (spec.md US2 acceptance 1–3, quickstart.md сценарий 8)

**Checkpoint**: US1 и US2 работают независимо; путь merge защищён с первой фичи (SC-002)

---

## Phase 5: User Story 3 - Автоматический деплой в dev-окружение (Priority: P2)

**Goal**: После слияния в main автоматически собираются контейнерные образы (SHA-тег ↔ коммит), публикуются в registry и декларативно деплоятся в dev-Kubernetes с health-пробами; сервисы stateless (FR-004, FR-005, FR-006, FR-007).

**Independent Test**: Слияние зелёного изменения в основную ветку — через несколько минут новая версия обоих приложений доступна в dev-окружении; перезапуск подов не требует ручного вмешательства (quickstart.md сценарии 5–6).

### Implementation for User Story 3

- [X] T011 [P] [US3] Создать `backend/Dockerfile`: multi-stage BuildKit (сборка `gradle:8-jdk21` → рантайм `eclipse-temurin:21-jre-jammy`), распаковка слоёв Spring Boot jar (зависимости раньше кода), `USER 1000`, `EXPOSE 8080`; критерий: `docker build` успешен, контейнер non-root отвечает на `/actuator/health/liveness` (research.md §10, quickstart.md сценарий 5)
- [X] T012 [P] [US3] Создать `frontend/nginx.conf` (SPA `try_files`, gzip для text/js/json, security-заголовки `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`, `Referrer-Policy`, `location = /healthz` → 200 `ok` с `access_log off`, `/assets/*` → `Cache-Control: immutable`) и `frontend/Dockerfile` (multi-stage: `node:24-alpine` pnpm build → `nginxinc/nginx-unprivileged:1-alpine`, порт 8080, non-root); критерий: `docker build` успешен, `curl localhost:8081/healthz` → ok (contracts/technical-endpoints.md §2, research.md §10)
- [X] T013 [P] [US3] Создать манифесты Kustomize base для backend: `deploy/k8s/base/backend-deployment.yaml` (liveness `httpGet /actuator/health/liveness :8080`, readiness `httpGet /actuator/health/readiness :8080`, `imagePullSecrets: [regcred]`, `runAsNonRoot: true`, `progressDeadlineSeconds`) и `deploy/k8s/base/backend-service.yaml` (ClusterIP :8080) (contracts/technical-endpoints.md §3, FR-006)
- [X] T014 [P] [US3] Создать манифесты Kustomize base для frontend: `deploy/k8s/base/frontend-deployment.yaml` (liveness и readiness `httpGet /healthz :8080`, `imagePullSecrets: [regcred]`, `runAsNonRoot: true`) и `deploy/k8s/base/frontend-service.yaml` (ClusterIP :8080) (contracts/technical-endpoints.md §3, FR-006)
- [X] T015 [P] [US3] Создать `deploy/k8s/base/kustomization.yaml` (resources: оба deployment + оба service + ingress) и `deploy/k8s/base/ingress.yaml` (маршрутизация на frontend- и backend-service; хост задаётся overlay); критерий: `kubectl kustomize deploy/k8s/base` рендерится без ошибок (FR-006)
- [X] T016 [US3] Создать overlay dev: `deploy/k8s/overlays/dev/kustomization.yaml` (+ patch-файлы: namespace dev, хост ingress, подстановка образов `${REGISTRY}/${IMAGE_PREFIX}/<app>:${SHA}` через `kustomize edit set image`); критерий: `kubectl kustomize deploy/k8s/overlays/dev` рендерится, образы подставляются по SHA-тегу, `latest`/`main` в кластере не используются (research.md §12, data-model.md Сущность 4)
- [X] T017 [US3] Создать `.github/workflows/deploy.yml`: триггер push в `main`; джобы `backend-image` и `frontend-image` (docker/build-push-action, BuildKit, теги `${REGISTRY}/${IMAGE_PREFIX}/<app>:${GITHUB_SHA}` + `main`, cache `type=gha`, variables `REGISTRY`/`IMAGE_PREFIX`, secrets `REGISTRY_USERNAME`/`REGISTRY_PASSWORD`) и `deploy-dev` (needs: оба image; kubeconfig из секрета `KUBE_CONFIG`; `kustomize edit set image` → `kubectl apply -k` с retry ×3 → `kubectl rollout status deploy/backend` и `deploy/frontend` с `--timeout=180s`); критерий: деплой успешен ⟺ rollout status exit 0 у обоих deployments, ноль ручных стадий (SC-008, research.md §10–§12, contracts/technical-endpoints.md §5)
- [X] T018 [P] [US3] Настроить входы деплоя: GitHub variables `REGISTRY`, `IMAGE_PREFIX` и secrets `REGISTRY_USERNAME`, `REGISTRY_PASSWORD`, `KUBE_CONFIG` (base64, сервис-аккаунт с RBAC только на dev-namespace); создать pull-secret `regcred` в dev-namespace (`kubectl create secret docker-registry`, один раз, вне git); критерий: ноль секретов в репозитории и манифестах (FR-014, research.md §13)
- [X] T019 [US3] Верифицировать US3: merge зелёного PR в `main` → образы с SHA-тегами в registry → деплой в dev → оба rollout status exit 0, поды Ready, сервисы доступны через ingress ≤ 15 минут (SC-003); `kubectl delete pod` → обслуживание восстанавливается автоматически без ручных действий (SC-004); повторный деплой идемпотентен (spec.md US3 acceptance 1–4, quickstart.md сценарий 6)

**Checkpoint**: Путь «коммит → dev» работает автоматически; Docker/K8s-часть проверяема локально независимо от других stories

---

## Phase 6: User Story 4 - Пайплайн публичного OpenAPI-контракта (Priority: P2)

**Goal**: Контракт в репозитории — единственный источник истины; TypeScript-типы генерируются автоматически; drift и breaking-изменения детектируются в PR и требуют явного решения (FR-008, FR-009, FR-010).

**Independent Test**: В PR изменяется контракт — конвейер регенерирует типы и/или детектирует расхождение; забытая регенерация фейлит проверку (quickstart.md сценарий 4).

### Implementation for User Story 4

- [X] T020 [P] [US4] Создать `contracts/openapi.yaml` — скелет публичного контракта OpenAPI 3.1: `info.title: WebChat Public API`, `info.version: 0.1.0`, description, `paths: {}` (бизнес-endpoints запрещены, FR-015); точное содержимое — contracts/api-contract.md §1 (FR-008, data-model.md Сущность 1)
- [X] T021 [US4] Добавить `openapi-typescript` в devDependencies и скрипт `generate:api` в `frontend/package.json` (`openapi-typescript ../contracts/openapi.yaml -o src/api/schema.d.ts`); сгенерировать и закоммитить `frontend/src/api/schema.d.ts`; критерий: повторная `pnpm generate:api` не меняет файл (`git status` чист — детерминированность, US4-4), `pnpm typecheck` проходит при пустом `paths` (FR-009, research.md §15, data-model.md Сущность 2)
- [X] T022 [P] [US4] Создать `frontend/src/api/client.ts` — тонкую обёртку типов (`import type { paths, components } from './schema'`) без рантайм-клиента (contracts/api-contract.md §4, contracts/technical-endpoints.md §2)
- [X] T023 [P] [US4] Добавить джоб `contract` в `.github/workflows/ci.yml`: `vacuum lint -e contracts/openapi.yaml` → `pnpm --dir frontend generate:api` → drift-проверка `git diff --exit-code -- frontend/src/api/schema.d.ts` → `oasdiff breaking <base> contracts/openapi.yaml` (base = `origin/main`; шаг пропускается только при наличии маркера `contracts/BREAKING.md`); добавить `contract` в required status checks (обновить правило из T009); критерий: джоб зелёный на текущем контракте (FR-009, FR-010, contracts/api-contract.md §3)
- [X] T024 [US4] Верифицировать US4: `vacuum lint` exit 0; регенерация детерминирована (US4-4); демонстрация drift — изменить `info.title` в `contracts/openapi.yaml` без регенерации типов → джоб `contract` падает, merge заблокирован (US4-2); демонстрация breaking — во временном PR удалить/переименовать элемент контракта → `oasdiff breaking` exit ≠ 0 без `contracts/BREAKING.md` (US4-3) (quickstart.md сценарий 4)

**Checkpoint**: Контрактный пайплайн работает; US4 не зависит от US1/US3 (только от Foundational и файла ci.yml из US2)

---

## Phase 7: User Story 5 - Observability-базлайн (Priority: P3)

**Goal**: Структурированные JSON-логи с traceId, распределённый трейсинг frontend→backend, метрики latency/throughput/errors; ошибка запроса наблюдаема во всех трёх сигналах (FR-011, FR-012, FR-013).

**Independent Test**: Тестовый запрос через frontend в backend — во всех логах обоих сервисов один и тот же traceId, запрос виден в трейсе, метрики latency/throughput/errors обновляются (quickstart.md сценарий 7).

### Implementation for User Story 5

- [X] T025 [P] [US5] Добавить observability-зависимости backend в `backend/gradle/libs.versions.toml` и `backend/build.gradle.kts`: `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `logstash-logback-encoder`; создать `backend/src/main/resources/logback-spring.xml` (`LogstashEncoder`, однострочный JSON в stdout, traceId/spanId из MDC); критерий: `./gradlew bootRun` — 100% записей логов валидный JSON одной строкой (`@timestamp`, `level`, `message`) (FR-011, research.md §18, data-model.md Сущность 5)
- [X] T026 [US5] Расширить `backend/src/main/resources/application.yml`: `management.otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}`, `management.metrics.distribution.percentiles-histogram.http.server.requests: true` (contracts/technical-endpoints.md §1); критерий: `./gradlew check` зелёный; HTTP server span продолжает входящий `traceparent` (W3C) и экспортируется по OTLP; `http_server_requests_seconds_{bucket,count,sum}` с тегами method/uri/status/outcome/exception (FR-012, FR-013, research.md §19)
- [X] T027 [P] [US5] Создать `frontend/src/telemetry/tracing.ts` — инициализация OTel web SDK (`@opentelemetry/sdk-trace-web`, `instrumentation-fetch`, `context-zone`, OTLP-http exporter, W3C-propagator, `Resource` с `service.name: frontend`): fetch-инструментация инжектит заголовок `traceparent` в каждый запрос; подключить в `frontend/src/main.tsx`; добавить зависимости в `frontend/package.json`; критерий: `pnpm typecheck && pnpm build && pnpm test` зелёные (FR-012, research.md §20, §22, data-model.md Сущность 6)
- [X] T028 [US5] Прокинуть OTLP-конфигурацию в dev-деплой: env `OTEL_EXPORTER_OTLP_ENDPOINT` для backend (patch в `deploy/k8s/overlays/dev/`), endpoint браузерного exporter'а frontend через переменную сборки (vite `define`/`VITE_*`); критерий: манифесты применяются, оба сервиса шлют телеметрию в OTel Collector dev-кластера (research.md §21, contracts/technical-endpoints.md §4)
- [X] T029 [US5] Верифицировать US5 end-to-end в dev (LGTM-стек — внешняя инфраструктура): запрос из frontend в backend → записи логов обоих сервисов (`kubectl logs`) содержат одинаковый `traceId`; трейс по этому `traceId` найден в Tempo (span frontend → span backend); метрики latency/throughput/error rate по `http_server_requests_seconds_*` доступны в Prometheus; 100% записей логов — машиночитаемый JSON (spec.md US5 acceptance 1–4, quickstart.md сценарий 7, SC-005, SC-006)

**Checkpoint**: Все user stories независимо функциональны

---

## Phase 8: Polish & Cross-Cutting Concerns

**Purpose**: Сквозные улучшения поверх всех user stories

- [X] T030 [P] Создать `docs/README.md`: ссылка на `specs/001-project-foundation/quickstart.md` и соглашения — параметризация registry (`REGISTRY`/`IMAGE_PREFIX`), правила контракта и `BREAKING.md`, OTLP/LGTM dev-инфраструктура, требование stateless (plan.md → Project Structure, research.md → сводка)
- [ ] T031 [P] Финальный security-проход: локальный запуск gitleaks по всей истории репозитория; проверить отсутствие секретов в `deploy/k8s/`-манифестах, образах и логах; `.gitignore` покрывает `.env` (FR-014, spec.md edge case «секрет в коде»)
- [ ] T032 Прогнать полную валидацию `specs/001-project-foundation/quickstart.md` (сценарии 1–8, где доступна инфраструктура) с чистого клона: все команды exit 0, onboarding ≤ 30 минут (SC-001); сверить покрытие acceptance-сценариев и edge cases из spec.md

---

## Dependencies & Execution Order

### Phase Dependencies

- **Setup (Phase 1)**: Нет зависимостей — начинать сразу
- **Foundational (Phase 2)**: Зависит от Setup — БЛОКИРУЕТ все user stories
- **User Stories (Phases 3–7)**: Все зависят от Foundational
  - **US1 (P1)**: После Foundational; MVP
  - **US2 (P1)**: После Foundational; независимо от US1 (демо-PR с падающим тестом не требует завершённого US1)
  - **US3 (P2)**: После Foundational; верификация контейнера backend (T011) требует health-проб из US1 (T006); полный путь merge→dev (T019) требует branch protection из US2 (T009)
  - **US4 (P2)**: После Foundational; T023 изменяет `.github/workflows/ci.yml`, созданный в US2 — при параллельной работе конфликт файлов, рекомендованный порядок US2 → US4
  - **US5 (P3)**: После US1 (расширяет `backend/src/main/resources/application.yml`) и US3 (патчит dev-манифесты в T028); полная верификация T029 требует деплоя в dev
- **Polish (Phase 8)**: После всех реализованных user stories

### User Story Dependencies

- **US1 (P1)**: Foundational → готово; не зависит от других stories
- **US2 (P1)**: Foundational → готово; независимо от US1/US3/US4/US5
- **US3 (P2)**: Foundational + US1 (health-пробы для проверок контейнера) + US2 (protection для end-to-end merge→dev); Docker/K8s-задачи (T011–T018) проверяемы локально независимо
- **US4 (P2)**: Foundational; файловая зависимость от US2 (`.github/workflows/ci.yml`); не зависит от US1/US3/US5
- **US5 (P3)**: US1 (конфигурация приложения) + US3 (dev-деплой для сквозной проверки); backend-часть (T025–T026) проверяема локально после US1

### Within Each User Story

- Тесты пишутся до/вместе с реализацией и FAIL до неё, где применимо (конституция VI)
- Сначала генерация/конфигурация/манифесты, затем верификация
- Верификационная задача каждой story (T007, T010, T019, T024, T029) закрывает её Independent Test
- Story завершена только при зелёных тестах, линте и пройденной верификации (Definition of Done)

### Parallel Opportunities

- Phase 2: T002 ∥ T003 (разные каталоги)
- US1: T004 ∥ T005; после T004 → T006 → T007
- US3: T011 ∥ T012; T013 ∥ T014 ∥ T015; T018 параллельна T011–T017 (настройки репозитория/кластера, не файлы кода)
- US4: T022 ∥ T023 (после T021; разные файлы)
- US5: T025 ∥ T027 (backend и frontend — разные файлы)
- Polish: T030 ∥ T031
- После Foundational: US1 и US2 могут выполняться параллельно разными исполнителями (разные файлы); US4 параллельна US1/US3, но T023 конфликтует с ci.yml US2 — координировать

---

## Parallel Example: User Story 1

```bash
# Запустить тесты US1 параллельно (разные каталоги):
Task: "T004 backend smoke-тесты в backend/src/test/kotlin/webchat/backend/"
Task: "T005 frontend smoke-тест в frontend/src/App.test.tsx"

# Затем конфигурация и верификация последовательно:
Task: "T006 application.yml (пробы health/prometheus)"
Task: "T007 верификация US1 с чистого клона"
```

## Parallel Example: User Story 3

```bash
# Независимые образы и манифесты (разные файлы):
Task: "T011 backend/Dockerfile"
Task: "T012 frontend/nginx.conf + frontend/Dockerfile"
Task: "T013 deploy/k8s/base/backend-{deployment,service}.yaml"
Task: "T014 deploy/k8s/base/frontend-{deployment,service}.yaml"
Task: "T015 deploy/k8s/base/{kustomization,ingress}.yaml"

# Затем последовательно: T016 overlay → T017 deploy.yml → T019 верификация (T018 параллельно)
```

---

## Implementation Strategy

### MVP First (Setup + Foundational + US1)

1. Complete Phase 1: Setup (T001)
2. Complete Phase 2: Foundational (T002, T003) — CRITICAL, блокирует все stories
3. Complete Phase 3: User Story 1 (T004–T007)
4. **STOP and VALIDATE**: чистый клон → сборка/линт/тесты/health-check (quickstart сценарии 1–3)
5. Дополнительно закрыть P1-пару: US2 (T008–T010) — путь merge защищён (SC-002)

### Incremental Delivery

1. Setup + Foundational → оба проекта собираются
2. + US1 → работающий каркас с health-check и smoke-тестами (MVP)
3. + US2 → защищённый конвейер качества на каждый PR
4. + US3 → автоматический путь «коммит → dev-окружение» (SC-003, SC-008)
5. + US4 → контрактный пайплайн (API-First с первой фичи)
6. + US5 → observability-базлайн (SC-005, SC-006)
7. Polish → финальная валидация quickstart (SC-001)
8. Каждый story добавляет ценность, не ломая предыдущие (все checkpoints независимо проверяемы)

### Parallel Team Strategy

1. Команда вместе завершает Setup + Foundational
2. После Foundational (при наличии нескольких исполнителей):
   - Разработчик A: US1 → затем US3 (зависит от health-проб)
   - Разработчик B: US2 → затем US4 (обе владеют CI-файлами)
   - US5 — после US1 и US3
3. Конфликтные файлы: `.github/workflows/ci.yml` (US2, US4), `backend/src/main/resources/application.yml` (US1, US5), `deploy/k8s/overlays/dev/` (US3, US5) — координировать или выполнять последовательно

---

## Notes

- [P] = разные файлы, нет зависимостей от незавершённых задач
- Метка [Story] обеспечивает трассируемость задач к user stories из spec.md
- Каждый story независимо завершаем и проверяем; останавливайтесь на checkpoint'ах для валидации
- Тесты FAIL до реализации, где применимо (T004 до T006); задача не выполнена без зелёных тестов и линта (конституция VI, Definition of Done)
- Коммит после каждой задачи или логической группы; статусы задач отмечаются в этом файле
- Секреты — только CI secrets / K8s Secrets вне git (FR-014); gitleaks обязателен на каждый PR
- Избегать: расплывчатых задач, конфликтов одного файла, меж-story зависимостей, ломающих независимость
- Внешние зависимости вне scope фичи: dev-кластер Kubernetes, container registry, LGTM-стек (OTel Collector + Tempo/Loki/Prometheus/Grafana) — считаются доступными (spec.md → Assumptions)