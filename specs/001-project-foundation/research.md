# Research: Каркас проекта, CI/CD и базовая инфраструктура

**Feature**: 001-project-foundation | **Date**: 2026-09-06

Все `NEEDS CLARIFICATION` из Technical Context плана исследованы и разрешены ниже.

---

## 1. Backend: система сборки

- **Decision**: Gradle с Kotlin DSL (обёртка `gradlew`, version catalog `gradle/libs.versions.toml`).
- **Rationale**: Kotlin Gradle Plugin и Spring Boot plugin — первоклассный поддерживаемый путь; build cache и configuration cache ускоряют CI; скрипты сборки на языке проекта.
- **Alternatives**: Maven — отклонён: многословный XML, нет сопоставимого кэша, `kotlin-maven-plugin` второсортен относительно KGP.

## 2. Backend: версии стека

- **Decision**: Spring Boot 3.5.x (последняя поддерживаемая линия 3.x), Kotlin 2.2.x, JDK 21 (LTS), Gradle 8.x. Плагины: `org.springframework.boot`, `org.jetbrains.kotlin.jvm`, `org.jetbrains.kotlin.plugin.spring`.
- **Rationale**: 3.5.x — актуальная линия 3.x до выхода 4.x; JDK 21 — LTS со зрелыми виртуальными потоками; `plugin.spring` открывает final-классы Kotlin для проксирования Spring.
- **Alternatives**: JDK 17 (минимум, но меньше возможностей), JDK 25 (новее, но тестирование 3.x центрировано на 17–24), Spring Boot 4.x (вне ограничения конституции «3.x»).

## 3. Backend: линт и форматирование

- **Decision**: ktlint (форматирование, таски `ktlintCheck`/`ktlintFormat`) + detekt (статический анализ), наборы правил близкие к дефолтным.
- **Rationale**: Ортогональные проблемы — стиль и сложность кода; оба падают в CI при нарушениях (FR-003, сценарий 3 US1).
- **Alternatives**: Только один из них — остаётся пробел; Spotless — лишняя абстракция поверх ktlint; SonarQube — требует сервер (YAGNI).

## 4. Backend: тесты

- **Decision**: `spring-boot-starter-test` (JUnit 5 + AssertJ) — достаточно для smoke-уровня: тест загрузки контекста + запрос к health-endpoint.
- **Rationale**: Один вендорский стек, нулевая доп. конфигурация; Kotest/Testcontainers — к бизнес-фичам, не сейчас (YAGNI).
- **Alternatives**: Kotest (красивее DSL, лишняя зависимость).

## 5. Frontend: сборка и версии

- **Decision**: Vite (актуальный мажор) + `@vitejs/plugin-react`, React 19, TypeScript 5.x strict (`strict`, `noUncheckedIndexedAccess`, `noImplicitOverride`, `noFallthroughCasesInSwitch`, `noUnusedLocals/Parameters`, `verbatimModuleSyntax`, `isolatedModules`, `moduleResolution: "bundler"`). Node 24 (Active LTS), закреплён в `.nvmrc` + `engines`. Менеджер пакетов — pnpm (закреплён через `packageManager`).
- **Rationale**: Vite — де-факто стандарт для React SPA; строгий tsconfig — требование конституции; pnpm — быстрый content-addressed store и строгие `node_modules` (важно при потреблении сгенерированных типов).
- **Alternatives**: Rspack/Turbopack (меньше экосистема), npm (медленнее, слабее изоляция), TS 7 native (инструментарий typescript-eslint ещё не готов).

## 6. Frontend: линт и форматирование

- **Decision**: ESLint (flat config) + typescript-eslint, `eslint-plugin-react-hooks`, `eslint-plugin-react-refresh`; форматирование — Prettier + `eslint-config-prettier`. CI: `eslint . && prettier --check .` — обе проверки падают при нарушениях.
- **Rationale**: ESLint больше не владеет правилами форматирования — Prettier остаётся стандартом; type-aware правила ловят реальные ошибки.
- **Alternatives**: Biome (быстр, но нет type-aware правил и слабее React/TS плагины).

## 7. Frontend: тесты

- **Decision**: Vitest + `@testing-library/react` + `@testing-library/jest-dom` + jsdom. Smoke: рендер `<App />`, проверка заголовка.
- **Rationale**: Vitest разделяет пайплайн трансформации Vite — нулевая доп. конфигурация; Jest требует babel/ts-jest обвязки.
- **Alternatives**: Jest (избыточная конфигурация в Vite-проекте).

## 8. CI-платформа

- **Decision**: GitHub Actions. На PR — три параллельных обязательных джоба: `backend`, `frontend`, `contract`. На merge в `main` — те же три + `backend-image`, `frontend-image`, `deploy-dev`.
- **Rationale**: Required status checks нативно реализуют блокировку слияния (FR-003); `GITHUB_TOKEN` с `permissions: packages: write` пушит в GHCR без PAT; встроенный кэш BuildKit (`type=gha`) без инфраструктуры.
- **Alternatives**: GitLab CI (только если организация стандартизирована на GitLab — потребует зеркалирования); Jenkins/CircleCI (больше ops-накладных расходов).

## 9. Структура CI-конвейера (монорепо)

- **Decision**: Отдельные джобы per-app + общий джоб `contract`; все запускаются на каждый PR (без path-фильтров на старте); `concurrency` отменяет устаревшие runs.
- **Rationale**: Разные тулчейны (JDK/Gradle vs Node) не дают выиграть от matrix; изоляция сбоев и 1:1 маппинг на required checks; path-фильтры для 2 приложений — экономия минут ценой «зависших» required checks (сложность > пользы, YAGNI).
- **Alternatives**: Один мега-джоб (медленно, маскирует сигнал); path-фильтры (footgun с required checks).

## 10. Docker-образы

- **Decision**: Multi-stage BuildKit.
  - Backend: сборка в `gradle:8-jdk21`, рантайм `eclipse-temurin:21-jre-jammy`, распаковка слоёв Spring Boot jar (слои зависимостей раньше кода), `USER 1000`, порт 8080.
  - Frontend: сборка в `node:24-alpine`, рантайм `nginxinc/nginx-unprivileged:1-alpine` (non-root, порт 8080), SPA `try_files`, gzip, security-заголовки, `location = /healthz`.
  - Кэш: `cache-from/cache-to: type=gha`. Теги: полный commit SHA (immutable, ссылается в K8s) + mutable `main`.
- **Rationale**: Порядок слоёв даёт кэш-хиты при изменениях кода; non-root и unprivileged-порт — безопасность и совместимость с `runAsNonRoot: true`; SHA-тег удовлетворяет FR-004.
- **Alternatives**: distroless (меньше, но без shell для отладки dev); Jib (скрывает сборку в плагине); `latest`-тег (неоднозначен — отклонён).

## 11. Container registry

- **Decision**: Полная параметризация: `REGISTRY` и `IMAGE_PREFIX` — CI variables; `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` — CI secrets. Образ: `${REGISTRY}/${IMAGE_PREFIX}/<app>:${SHA}`.
- **Rationale**: Замена GHCR на Harbor/Artifactory/ECR = изменение 2 variables + 2 secrets, без правок джобов и манифестов.
- **Alternatives**: Хардкод `ghcr.io` (быстрее, но lock-in).

## 12. Kubernetes-деплой

- **Decision**: Kustomize: `deploy/k8s/base/` (2 Deployment, 2 Service, 1 Ingress, probes) + `deploy/k8s/overlays/dev/`. Деплой: `kustomize edit set image` → `kubectl apply -k` (retry ×3) → `kubectl rollout status` ×2 (`--timeout=180s`).
- **Rationale**: Встроен в `kubectl`, декларативно, reviewable, готов к GitOps; нативный image-transformer для подстановки SHA-тегов; `rollout status` = готовность всех реплик = зелёные readiness-пробы; повторный запуск безопасен (идемпотентность apply + immutable SHA-теги) — edge case из спека.
- **Alternatives**: Raw-манифесты (дублирование и sed для тегов); Helm (лишний слой абстракции для одного окружения); ArgoCD/Flux (следующий шаг, не сейчас — YAGNI).

## 13. Секреты

- **Decision**: Ноль секретов в манифестах и репозитории. Registry-креды: `kubectl create secret docker-registry regcred` (создаётся вне git один раз), Deployment ссылается `imagePullSecrets`. CI: `KUBE_CONFIG` (base64, сервис-аккаунт с RBAC только на dev-namespace). Секрет-сканирование (gitleaks) — обязательный шаг конвейера.
- **Rationale**: FR-014 + edge case «секрет случайно добавлен в код» требует автоматической проверки — gitleaks закрывает этот сценарий; ротация кредов не затрагивает git.
- **Alternatives**: Секреты в манифестах (недопустимо).

## 14. OpenAPI-контракт: формат и размещение

- **Decision**: OpenAPI **3.1**, один файл `contracts/openapi.yaml` в корне монорепо. Скелет: `info` (title, semver-версия 0.1.0) + пустой `paths: {}`.
- **Rationale**: Тулчейн 2026 (openapi-typescript, vacuum, oasdiff) полностью поддерживает 3.1; JSON Schema 2020-12 семантика; при почти пустом `paths` разбиение на файлы — лишняя связность (YAGNI).
- **Alternatives**: 3.0.3 (только при legacy-потребителе); split-spec (после роста контракта).

## 15. Генерация TypeScript-типов

- **Decision**: `openapi-typescript` (types-only, без рантайм-клиента). Вывод коммитится: `frontend/src/api/schema.d.ts`; npm-скрипт `generate:api`; потребление через тонкую обёртку `frontend/src/api/client.ts` (`import type`).
- **Rationale**: Детерминированный вывод без таймстампов — критично для regen+diff; корректно обрабатывает пустой `paths`; коммит файла даёт видимость изменений типов в PR-ревью (требование US4).
- **Alternatives**: `openapi-generator-cli` (JVM, недетерминирован по умолчанию, overkill для types-only); orval (генерирует hooks/axios — YAGNI); `openapi-fetch` (добавим при появлении endpoints).

## 16. Валидация и drift-детекция контракта

- **Decision**: Валидация/линт — `vacuum lint -e`. Drift-детекция — CI регенерирует `schema.d.ts` и выполняет `git diff --exit-code` по нему.
- **Rationale**: vacuum — один Go-бинар, first-class 3.1, exit-code для CI; regen+diff — простейший детерминированный механизм расхождения (FR-009, US4-2).
- **Alternatives**: Spectral (инкумбент, медленнее); build-time-only генерация (невидимость типов в ревью — отклонена).

## 17. Breaking-change детекция

- **Decision**: `oasdiff breaking base.yaml head.yaml` (exit ≠ 0 при breaking). Base = контракт из `origin/main`. Override: коммит-файл-маркер `contracts/BREAKING.md` с описанием решения — джоб пропускает проверку только при его наличии; удаляется после мержа.
- **Rationale**: Purpose-built инструмент; маркер-файл делает «явное решение» видимым артефактом ревью (FR-010, US4-3) и остаётся в истории git.
- **Alternatives**: `openapi-diff` (JVM, медленнее); override через лейблы GitHub (менее явный след).

## 18. Observability: backend

- **Decision**: Micrometer Tracing (`micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`); метрики — Micrometer + `micrometer-registry-prometheus`, экспорт `/actuator/prometheus`; JSON-логи — `logstash-logback-encoder` (`LogstashEncoder`), traceId/spanId попадают в JSON из MDC автоматически.
- **Rationale**: Нативная интеграция с Observation API Spring Boot 3 — spans и метрики из одного хука; версионирование через Boot BOM; без `-javaagent`.
- **Alternatives**: OTel Java agent (шире, но ops-фрикция с флагами, дубль спанов, другие MDC-ключи); встроенный structured logging Boot (менее гибкий).

## 19. Observability: метрики latency/throughput/errors

- **Decision**: Таймер `http.server.requests` (Prometheus: `http_server_requests_seconds_bucket/_count/_sum`, теги method/uri/status/outcome/exception) + `management.metrics.distribution.percentiles-histogram.http.server.requests=true`.
- **Rationale**: Один встроенный таймер покрывает все три сигнала: latency (histogram quantiles), throughput (`rate(_count)`), error rate (`rate(status=~"5..")/rate(_count)`) — FR-013.
- **Alternatives**: OTel HTTP-метрики (дубль поверх Micrometer).

## 20. Observability: frontend-трейсинг и propagation

- **Decision**: В этой фиче (US5 требует сквозной трейс): `@opentelemetry/sdk-trace-web` + `instrumentation-fetch` + `context-zone` + OTLP-http exporter; W3C-пропагатор инжектит `traceparent` в каждый fetch; backend извлекает и продолжает трейс.
- **Rationale**: Единственный требование-совместимый вариант — спека требует трейс через frontend и backend уже в этой фиче; готовый propagator вместо самописного заголовка. CORS должен разрешать заголовок `traceparent`.
- **Alternatives**: Отложить (нарушает US5); самописный заголовок (переизобретение propagator).

## 21. Observability: dev-инфраструктура

- **Decision**: OTel Collector (gateway, in-cluster, приём OTLP gRPC+HTTP через Ingress) → Grafana LGTM-стек: Tempo (traces), Loki (logs, JSON из stdout), Prometheus (metrics, scrape `/actuator/prometheus`), Grafana (UI). Развёртывается как инфраструктура dev-кластера, не входит в образы приложений.
- **Rationale**: Один UI с корреляцией TraceID ↔ Loki ↔ Prometheus; браузер не может писать напрямую в кластерный Tempo — collector даёт единый стабильный OTLP endpoint; смена бэкенда = конфиг collector.
- **Alternatives**: Jaeger + Prometheus (нет логов, второй UI); прямая запись в бэкенды (невозможно для браузера).

## 22. Именование сервисов (корреляция)

- **Decision**: Backend: `spring.application.name` — единый источник (маппится в `service.name` спанов и тег метрик `application`). Frontend: явный `Resource` с `service.name` в `WebTracerProvider`.
- **Rationale**: Одинаковая строка в трейсах, метриках и логах = фильтрация per-service (US5, SC-006).
- **Alternatives**: `OTEL_RESOURCE_ATTRIBUTES` (второй источник истины).

## 23. Stateless-гарантии

- **Decision**: Отсутствие HTTP-сессий (session policy STATELESS, spring-session вне classpath); MDC-контекст — thread-local, безопасен; состояние только в конфигурации/переменных окружения.
- **Rationale**: FR-007: перезапуск экземпляра восстанавливает обслуживание — нет in-memory состояния для потери.
- **Alternatives**: spring-session + Redis (появится при реальной потребности — не сейчас).

---

## Сводка разрешённых неизвестных

| Unknown (из Technical Context) | Решение |
|---|---|
| Система сборки backend | Gradle (Kotlin DSL), Spring Boot 3.5.x, Kotlin 2.2.x, JDK 21 |
| Линт backend | ktlint + detekt |
| CI-платформа | GitHub Actions (3 PR-джоба + 3 deploy-джоба) |
| Container registry | Параметризован variables/secrets; по умолчанию GHCR |
| Манифесты K8s | Kustomize base + overlays/dev |
| Codegen контракта | openapi-typescript → коммит `frontend/src/api/schema.d.ts` |
| Валидация контракта | vacuum |
| Breaking-детекция | oasdiff + маркер `contracts/BREAKING.md` |
| Tracing-стек | Micrometer Tracing + OTel bridge; frontend OTel web SDK |
| Metrics-стек | Micrometer + Prometheus endpoint; scrape Prometheus |
| Логирование | logback + logstash-logback-encoder (JSON, traceId из MDC) |
| Tracing-бэкенд (dev) | OTel Collector + Grafana LGTM (Tempo/Loki/Prometheus/Grafana) |
| Frontend-сборка | Vite + React 19 + TS 5 strict; pnpm; Node 24 |
| Frontend-линт/тесты | ESLint + Prettier; Vitest + Testing Library |
| Секрет-сканирование | gitleaks в CI (edge case из спека) |
