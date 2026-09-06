# Implementation Plan: Каркас проекта, CI/CD и базовая инфраструктура

**Branch**: `001-project-foundation` | **Date**: 2026-09-06 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-project-foundation/spec.md`

## Summary

Первая фича проекта: технический каркас веб-чата без бизнес-логики. Монорепозиторий с backend (Kotlin 2.x + Spring Boot 3.x, Gradle Kotlin DSL) и frontend (React 19 + TypeScript strict, Vite, pnpm). CI/CD на GitHub Actions с первой фичи: на PR — сборка, линт, unit-тесты обоих приложений + проверки OpenAPI-контракта (обязательные status checks блокируют слияние); на merge в main — сборка Docker-образов с SHA-тегами, публикация в registry и декларативный деплой в dev-окружение Kubernetes (Kustomize, health-пробы, rollout status). Пайплайн публичного OpenAPI 3.1-контракта: vacuum-валидация, генерация TypeScript-типов (openapi-typescript), drift-детекция (regen + git diff), breaking-детекция (oasdiff + маркер BREAKING.md). Observability-базлайн: структурированные JSON-логи с traceId (logstash-logback-encoder), распределённый трейсинг frontend→backend (Micrometer Tracing + OTel bridge; OTel web SDK в браузере, W3C traceparent), метрики latency/throughput/errors (Micrometer + /actuator/prometheus). Все сервисы stateless. Детальные решения с обоснованием — [research.md](./research.md).

## Technical Context

**Language/Version**: Kotlin 2.2.x (backend), TypeScript 5.x strict mode (frontend); JDK 21 (LTS), Node 24 (LTS).

**Primary Dependencies**:
- Backend: Spring Boot 3.5.x — starters: web, actuator; `micrometer-registry-prometheus`, `micrometer-tracing-bridge-otel`, `opentelemetry-exporter-otlp`, `logstash-logback-encoder`; линт: ktlint + detekt (Gradle-плагины).
- Frontend: React 19, Vite, ESLint (flat config) + typescript-eslint + Prettier, Vitest + Testing Library; OpenTelemetry web SDK (`sdk-trace-web`, `instrumentation-fetch`, `context-zone`, OTLP-http exporter); `openapi-typescript` (codegen).
- Инфраструктура: GitHub Actions, Docker (multi-stage, BuildKit), Kustomize; инструменты контракта: vacuum (валидация), oasdiff (breaking-детекция), gitleaks (секрет-сканирование).

**Storage**: N/A — внешние хранилища в этой фиче не вводятся (YAGNI); сервисы stateless, состояние — только конфигурация/переменные окружения.

**Testing**: Backend — JUnit 5 (spring-boot-starter-test): context-load smoke + health-endpoint тест. Frontend — Vitest + @testing-library/react: smoke-рендер App. Контракт — vacuum lint + drift-check (regen + `git diff --exit-code`) + oasdiff breaking-check в CI.

**Target Platform**: Linux-контейнеры в dev-кластере Kubernetes (backend: eclipse-temurin 21 JRE; frontend: nginx-unprivileged, порт 8080); браузеры evergreen для SPA.

**Project Type**: web-service (монорепозиторий: backend web-сервис + frontend SPA).

**Performance Goals**: N/A для этой фичи (нет бизнес-нагрузки). Бюджеты конституции (1M users, 100k msg/s) не затрагиваются; каркас не должен им препятствовать: stateless, горизонтальное масштабирование по построению. Observability-накладные расходы — на уровне дефолтов библиотек.

**Constraints**: все сервисы stateless (FR-007); CI/CD обязателен с первой фичи; API-First (FR-008–010); секреты только в CI secrets / K8s Secrets (FR-014); деплой только в dev; ≤ 15 минут до dev (SC-003); onboarding с чистого клона ≤ 30 минут (SC-001); ноль ручных подтверждений на пути merge→dev (SC-008).

**Scale/Scope**: 2 приложения-скелета, 1 CI-workflow (6 джобов), Kustomize base + dev-overlay, 1 файл контракта, 2 Dockerfile, LGTM-стек как внешняя dev-инфраструктура. Без бизнес-функций (FR-015).

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| # | Принцип | Статус | Комментарий |
|---|---------|--------|-------------|
| I | Spec-Driven (NON-NEGOTIABLE) | ✅ PASS | Спека утверждена; план — этот документ; `tasks.md` — следующий шаг (`/speckit.tasks`). Код не пишется вне задач. |
| II | Масштабируемость / stateless (NON-NEGOTIABLE) | ✅ PASS | Все сервисы stateless: без HTTP-сессий и in-memory состояния (research §23); K8s Deployments с probes, горизонтальное масштабирование по построению. Бюджеты нагрузки к этой фиче не применимы (нет бизнес-логики) — решение, им препятствующее, не вводится. |
| III | Доставка без дублей (NON-NEGOTIABLE) | ✅ N/A | Пути доставки сообщений в этой фиче нет; принцип не нарушается и не реализуется (кроме идемпотентности деплоя: повторный `kubectl apply -k` безопасен). |
| IV | API-First | ✅ PASS | Публичный OpenAPI-контракт — источник истины в репо; TS-типы генерируются из него; breaking-детекция с явным решением через версионирование. Ядро этой фичи. |
| V | Безопасность и приватность | ✅ PASS | Аутентификация не вводится (нет публичных бизнес-endpoints, FR-015). Секреты: только CI secrets и K8s Secrets (imagePullSecrets), gitleaks-сканирование, ноль секретов в манифестах/репо/логах. |
| VI | Test-First (NON-NEGOTIABLE) | ✅ PASS | Smoke-тесты обоих приложений входят в фичу и обязательны в CI с первого дня; задачи без тестов не принимаются (Definition of Done). |
| VII | Простота (YAGNI) | ✅ PASS | Минимальные выборы: types-only codegen, Kustomize вместо Helm, single-file контракт, без ArgoCD/multi-repo/монорепо-тулинга. Все middleware-выборы зафиксированы в research.md с обоснованием. |

**Нарушений нет** — Complexity Tracking не требуется.

**Re-check после Phase 1 (дизайн)**: сущности (data-model.md) не вводят персистентного состояния; контракты (contracts/) не содержат бизнес-endpoints; quickstart.md проверяем без ручных правок файлов. Выводы таблицы не меняются — **все гейты PASS**.

## Project Structure

### Documentation (this feature)

```text
specs/001-project-foundation/
├── plan.md              # This file (/speckit.plan command output)
├── research.md          # Phase 0 output (/speckit.plan command)
├── data-model.md        # Phase 1 output (/speckit.plan command)
├── quickstart.md        # Phase 1 output (/speckit.plan command)
├── contracts/           # Phase 1 output (/speckit.plan command)
│   ├── api-contract.md
│   └── technical-endpoints.md
└── tasks.md             # Phase 2 output (/speckit.tasks command - NOT created by /speckit.plan)
```

### Source Code (repository root)

```text
backend/                                  # Kotlin 2.x + Spring Boot 3.x (Gradle Kotlin DSL)
├── build.gradle.kts
├── settings.gradle.kts
├── gradle/libs.versions.toml             # version catalog
├── gradlew / gradle/wrapper/…
├── src/main/kotlin/webchat/backend/
│   └── BackendApplication.kt             # минимальный вход (без бизнес-кода)
├── src/main/resources/
│   ├── application.yml                   # actuator, probes, prometheus, OTLP endpoint
│   └── logback-spring.xml                # LogstashEncoder → JSON-логи
├── src/test/kotlin/webchat/backend/
│   ├── BackendApplicationTests.kt        # context-load smoke
│   └── HealthEndpointTests.kt            # health-пробы отвечают 200
└── Dockerfile                            # multi-stage: gradle → temurin 21 JRE, USER 1000, :8080

frontend/                                 # React 19 + TypeScript strict (Vite, pnpm)
├── package.json                          # packageManager: pnpm; engines: node 24
├── pnpm-lock.yaml
├── .nvmrc / .npmrc
├── tsconfig.json
├── eslint.config.js                      # ESLint flat + typescript-eslint
├── .prettierrc.json
├── vite.config.ts                        # + vitest config
├── index.html
├── nginx.conf                            # SPA try_files, gzip, security headers, /healthz
├── Dockerfile                            # multi-stage: node:24-alpine → nginx-unprivileged, :8080
└── src/
    ├── main.tsx
    ├── App.tsx
    ├── App.test.tsx                      # smoke-тест
    ├── api/
    │   ├── schema.d.ts                   # GENERATED (openapi-typescript) — коммитится
    │   └── client.ts                     # тонкая обёртка типов (без рантайм-клиента)
    └── telemetry/
        └── tracing.ts                    # OTel web: fetch-instrumentation, W3C, OTLP exporter

contracts/
├── openapi.yaml                          # ПУБЛИЧНЫЙ КОНТРАКТ (источник истины), OpenAPI 3.1
└── BREAKING.md                           # маркер осознанного breaking-change (создаётся точечно)

deploy/k8s/
├── base/
│   ├── kustomization.yaml
│   ├── backend-deployment.yaml           # probes: liveness/readiness, imagePullSecrets
│   ├── backend-service.yaml
│   ├── frontend-deployment.yaml          # probes: httpGet / :8080
│   ├── frontend-service.yaml
│   └── ingress.yaml
└── overlays/dev/
    ├── kustomization.yaml                # namespace, host, подстановка SHA-тегов образов
    └── patch-*.

.github/workflows/
├── ci.yml                                # PR: backend | frontend | contract (+ gitleaks)
└── deploy.yml                            # main: те же + образы → registry → deploy-dev

docs/                                     # (опц.) ссылка на quickstart и соглашения
```

**Structure Decision**: Монорепозиторий верхнего уровня `backend/` + `frontend/` + общий `contracts/openapi.yaml` в корне (единый источник истины, видимый обоим приложениям без ссылок через package). Деплой-манифесты в `deploy/k8s/` (Kustomize). CI в `.github/workflows/`. Выбран вариант «Web application» из шаблона; структура сознательно плоская — без `apps/`/`packages/` слоёв (YAGNI, введём при появлении общего пакета SPA+widget по конституции IV).

## Complexity Tracking

> **Fill ONLY if Constitution Check has violations that must be justified**

Нарушений конституции нет — таблица пуста.
