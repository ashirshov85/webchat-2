# Data Model: Каркас проекта, CI/CD и базовая инфраструктура

**Feature**: 001-project-foundation | **Date**: 2026-09-06

В фиче **нет персистентного хранилища** (FR-015, YAGNI): все сервисы stateless (FR-007).
«Данными» здесь являются **артефакты конвейера** и **записи телеметрии**. Модель описывает
их поля, инварианты, связи и переходы состояний; хранение — файлы репозитория,
container registry, Kubernetes и observability-бэкенд dev-кластера.

---

## Сущность 1: Публичный API-контракт (PublicApiContract)

Единственный источник истины публичного API (FR-008).

| Поле | Тип | Описание / валидация |
|---|---|---|
| `path` | фиксирован | `contracts/openapi.yaml`, один файл в корне монорепо |
| `openapi` | const | `3.1.x` |
| `info.title` | string | `WebChat Public API` |
| `info.version` | semver | Начало — `0.1.0`. Правила переходов — ниже |
| `paths` | object | В этой фиче — пустой объект `{}` (бизнес-endpoints запрещены, FR-015) |
| `components` | object | Отсутствует (появится с первым бизнес-endpoint) |

**Валидация** (CI, джоб `contract`):
- Синтаксически валидный OpenAPI 3.1 + прохождение линта `vacuum lint -e` (ошибки → падение).
- Изменение только через pull request (trunk-based, US2).

**Переходы версий** (state transitions `info.version`):

| Переход | Условие | Проверка CI |
|---|---|---|
| patch `0.1.0 → 0.1.1` | Обратимо-совместимое изменение | oasdiff: breaking-изменений нет → pass |
| minor `0.1.x → 0.2.0` | Добавление (новые paths/fields) | oasdiff: breaking-изменений нет → pass |
| major / breaking | Обратно несовместимое изменение | oasdiff находит breaking → **fail**, если отсутствует маркер `contracts/BREAKING.md` (явное решение, FR-010); маркер удаляется после мержа |

---

## Сущность 2: Сгенерированные клиентские типы (GeneratedClientTypes)

Производный артефакт контракта (FR-009).

| Поле | Тип | Описание / валидация |
|---|---|---|
| `path` | фиксирован | `frontend/src/api/schema.d.ts` |
| `generator` | const | `openapi-typescript` (types-only) |
| `source` | ссылка | 1:1 на `PublicApiContract` |
| `content` | TypeScript | Детерминированный вывод: повторная генерация без изменения контракта даёт byte-идентичный файл (US4-4) |

**Инварианты**:
- `schema.d.ts == generate(contracts/openapi.yaml)` в каждом коммите — CI drift-check (regen + `git diff --exit-code`) падает при расхождении (FR-009, edge «контракт изменён, типы — нет»).
- Ручное редактирование запрещено; единственный путь изменения — через контракт (US4-1: diff типов виден в ревью).
- Файл валиден для `tsc --noEmit` при пустом `paths` (компиляция фронтенда не ломается).

---

## Сущность 3: Контейнерный образ (ContainerImage)

Неизменяемый артефакт сборки приложения (FR-004).

| Поле | Тип | Описание / валидация |
|---|---|---|
| `registry` | env (CI variable `REGISTRY`) | Адрес registry (по умолчанию GHCR); НЕ хардкодится |
| `name` | string | `${REGISTRY}/${IMAGE_PREFIX}/backend` / `.../frontend` |
| `tag_sha` | string | Полный commit SHA — **immutable**, однозначная связь с изменением (FR-004) |
| `tag_main` | mutable | `main` — указатель на последний успешный build из main |
| `digest` | string | Content-address идентификатор (неизменяемость) |

**Инварианты / валидация**:
- Один коммит → не более одного образа на приложение с данным SHA-тегом (edge «одновременные слияния»: каждый деплой идентифицируется своей версией).
- Образ: non-root пользователь, порт 8080, проходит локальный health-check перед push.
- `latest`-тег не используется (неоднозначность запрещена).
- Секретов в образе нет (gitleaks + отсутствие секретов в build-аргументах, FR-014).

**Связи**: `ContainerImage 1—1 GitCommit` (на приложение); `ContainerImage 1—n DevRelease` (переиспользуется при повторном деплое того же SHA).

---

## Сущность 4: Релиз dev-окружения (DevRelease)

Состояние развёрнутых сервисов в dev (FR-005, FR-006).

| Поле | Тип | Описание |
|---|---|---|
| `images` | map | `{backend: tag_sha, frontend: tag_sha}` — результат `kustomize edit set image` |
| `manifests` | фикс. | `deploy/k8s/base` + `deploy/k8s/overlays/dev` (deployments, services, ingress, probes) |
| `status` | enum | Переходы — ниже |

**Переходы состояний** (state machine деплоя):

```text
Pending ──apply──► Progressing ──rollout status OK (все реплики Ready, probes зелёные)──► Healthy
                        │
                        └──rollout timeout / probes fail──► Failed
Failed: новая версия НЕ считается успешной; предыдущий Healthy-релиз остаётся доступным
        (replicas старого ReplicaSet), откат = redeploy предыдущего SHA (US3-4, edge cases)
```

**Инварианты**:
- Деплой успешен ⟺ `kubectl rollout status` завершился 0 для обоих deployments (readiness-пробы зелёные).
- Повторный запуск деплоя идемпотентен: `kubectl apply -k` сходится к манифесту, SHA-теги immutable (edge «registry/кластер недоступны»).
- Все сервисы stateless: перезапуск пода → автоматическое восстановление обслуживания (FR-007, SC-004), никакое состояние конфигурации не теряется (конфигурация — в манифестах/env, не в процессе).
- Итоговое состояние кластера = последний успешный деплой (edge «одновременные слияния»).

---

## Сущность 5: Запись структурированного лога (LogRecord)

| Поле | Тип | Валидация |
|---|---|---|
| `@timestamp` | ISO-8601 | Обязательно в 100% записей |
| `level` | enum | `TRACE..ERROR` |
| `service` | string | `backend` (`spring.application.name`) / `frontend` |
| `message` | string | — |
| `traceId` | hex(32) | Обязательно для записей в контексте обработки запроса (FR-011); заполняется из MDC (Micrometer Tracing) автоматически |
| `spanId` | hex(16) | Аналогично `traceId` |
| прочие MDC-поля | — | Сериализуются как top-level JSON-поля (`LogstashEncoder`) |

**Инварианты**: 100% записей — валидный JSON одной строкой (FR-011, SC-005); запрос, прошедший frontend→backend, оставляет записи с **одним и тем же** `traceId` в логах обоих сервисов (US5-1).

---

## Сущность 6: Трейс / Span (Trace, Span)

| Поле | Описание |
|---|---|
| `traceId` | 128-bit hex, формат W3C Trace Context; генерируется браузерным OTel SDK |
| `span` (frontend) | Fetch-вызов к API, `service.name=frontend`; заголовок `traceparent: 00-{traceId}-{spanId}-01` инжектится в каждый запрос |
| `span` (backend) | HTTP server span, `service.name=backend`; создаётся продолжением входящего `traceparent` (Micrometer Tracing + OTel bridge) |
| `resource.service.name` | Единый идентификатор сервиса в трейсах/метриках/логах |

**Инвариант**: `Trace 1 — n Spans` across services; экспорт через OTLP в OTel Collector (endpoint — env `OTEL_EXPORTER_OTLP_ENDPOINT`), хранение — Tempo (dev-инфраструктура).

---

## Сущность 7: Метрическая серия (MetricSeries)

| Поле | Описание |
|---|---|
| `name` | `http_server_requests_seconds_{bucket,count,sum}` (backend, Micrometer); frontend-трейсы дают спаны, метрики HTTP-клиента — не обязательны в этой фиче |
| `tags` | `method`, `uri`, `status`, `outcome`, `exception`, `application` (= `spring.application.name`) |
| `samples` | Гистограмма (`percentiles-histogram` включён) |

**Производные сигналы** (SC-006, FR-013):

| Сигнал | Вычисление |
|---|---|
| Latency | `histogram_quantile(..., http_server_requests_seconds_bucket)` |
| Throughput | `rate(http_server_requests_seconds_count[...])` |
| Error rate | `rate(...{status=~"5.."}[...]) / rate(...[...])` |

**Инвариант**: серия доступна для каждого backend-сервиса в любой момент его работы (scrape Prometheus → `/actuator/prometheus`).

---

## Связи между сущностями

```text
PublicApiContract 1 ──generate──► 1 GeneratedClientTypes   (производная, коммитится)
GitCommit 1 ──build──► 2 ContainerImage (backend, frontend; SHA-тег)
ContainerImage n ──deploy──► DevRelease (deployments/services/ingress dev)
LogRecord n ──traceId──► Trace 1 ──n──► Span (frontend span → backend span, W3C)
MetricSeries n ──application tag──► сервис (backend/frontend)
```

## Проверка требований спека

| Требование | Покрытие моделью |
|---|---|
| FR-008 | Сущность 1: контракт в репо, валидация в CI |
| FR-009 | Сущность 2: инвариант детерминированности + drift-check |
| FR-010 | Сущность 1: переходы версий + BREAKING.md |
| FR-004 | Сущность 3: immutable SHA-тег ↔ коммит |
| FR-005–007 | Сущность 4: state machine деплоя, идемпотентность, stateless |
| FR-011 | Сущность 5: 100% JSON + traceId |
| FR-012 | Сущность 6: W3C propagation frontend→backend |
| FR-013 | Сущность 7: latency/throughput/errors |
| FR-014 | Сущности 3–4: секреты только в CI secrets / imagePullSecrets; gitleaks |
