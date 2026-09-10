# webchat — документация

- Быстрый старт (проверка каркаса end-to-end с чистого клона, сценарии 1–8):
  [specs/001-project-foundation/quickstart.md](../specs/001-project-foundation/quickstart.md)
- Структура репозитория и план каркаса:
  [specs/001-project-foundation/plan.md](../specs/001-project-foundation/plan.md) (раздел Project Structure)
- Обоснование технических решений:
  [specs/001-project-foundation/research.md](../specs/001-project-foundation/research.md) (сводка — в конце файла)

## Соглашения

### Container registry — параметризация

- `REGISTRY` и `IMAGE_PREFIX` — CI variables; `REGISTRY_USERNAME`, `REGISTRY_PASSWORD` — CI secrets.
- Имя образа: `${REGISTRY}/${IMAGE_PREFIX}/<app>:${SHA}` (приложения `backend`, `frontend`).
- Теги — только полный immutable commit SHA; `latest`/`main` никогда не попадают в кластер.
- Подстановка при деплое (deploy/k8s/overlays/dev):

  ```bash
  kustomize edit set image \
    backend=${REGISTRY}/${IMAGE_PREFIX}/backend:${SHA} \
    frontend=${REGISTRY}/${IMAGE_PREFIX}/frontend:${SHA}
  ```

- Смена registry (GHCR → Harbor/Artifactory/ECR) = правка 2 variables + 2 secrets,
  без изменений джобов и манифестов.
- Registry-креды кластера — K8s Secret `regcred` (создаётся вне git один раз),
  Deployment ссылается через `imagePullSecrets`. Секретов в манифестах и репозитории нет.

### API-контракт

- Источник истины — `contracts/openapi.yaml` (OpenAPI 3.1, один файл в корне монорепо).
- TypeScript-типы генерируются: `pnpm --dir frontend generate:api`
  → `frontend/src/api/schema.d.ts`; вывод коммитится (видимость изменений типов в PR-ревью).
- Валидация/линт: `vacuum lint -e contracts/openapi.yaml`.
- Drift-детекция: CI регенерирует `schema.d.ts` и выполняет `git diff --exit-code` —
  любое расхождение падает. После изменения контракта всегда регенерируйте типы.
- Breaking-детекция: `oasdiff breaking <base> contracts/openapi.yaml`, где base —
  контракт из `origin/main`.

### BREAKING.md

- `contracts/BREAKING.md` — коммит-файл-маркер осознанного breaking-change.
- Его наличие — единственный способ пропустить breaking-проверку в CI.
- Файл содержит описание решения: что ломается, почему, план миграции потребителей.
- Удаляется после мержа PR; в «спокойном» main его не существует.

### Observability (OTLP / LGTM)

- Приложения экспортируют телеметрию по OTLP: backend — Micrometer Tracing +
  OTLP exporter; frontend — OTel web SDK с OTLP-http exporter (W3C `traceparent`
  в каждом fetch).
- В dev приём — OTel Collector (gateway, in-cluster, OTLP gRPC+HTTP через Ingress)
  → Grafana LGTM-стек: Tempo (traces), Loki (logs, JSON из stdout),
  Prometheus (metrics, scrape `/actuator/prometheus`), Grafana (UI).
- LGTM-стек — инфраструктура dev-кластера; в образы приложений не входит.
- Логи — структурированный JSON (`@timestamp`, `level`, `message`, `traceId` из MDC).
- Корреляция сигналов: один traceId в логах, трейсах (Tempo) и метриках
  `http_server_requests_seconds_*` (Prometheus).
- Имя сервиса: backend — `spring.application.name`, frontend — явный `service.name`
  в Resource; одинаково во всех сигналах.

### Stateless

- Все сервисы stateless: без HTTP-сессий и in-memory состояния
  (session policy STATELESS, spring-session вне classpath).
- Единственное состояние — конфигурация и переменные окружения.
- Перезапуск/удаление пода восстанавливает обслуживание автоматически;
  масштабирование — горизонтальное, без изменений кода.
