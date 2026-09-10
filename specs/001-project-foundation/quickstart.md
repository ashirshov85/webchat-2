# Quickstart: Каркас проекта, CI/CD и базовая инфраструктура

**Feature**: 001-project-foundation

Руководство по проверке фичи end-to-end с чистого клона репозитория (SC-001).
Сценарии соответствуют acceptance-сценариям [spec.md](./spec.md); контракты
интерфейсов — [contracts/api-contract.md](./contracts/api-contract.md) и
[contracts/technical-endpoints.md](./contracts/technical-endpoints.md).

## Предусловия

| Инструмент | Версия | Проверка |
|---|---|---|
| JDK | 21 | `java -version` |
| Node.js | 24 (см. `frontend/.nvmrc`) | `node -v` |
| pnpm | закреплён в `frontend/package.json` (`packageManager`) | `pnpm -v` |
| Docker | любая актуальная | `docker version` |
| kubectl + доступ к dev-кластеру | только для сценария 6 | `kubectl version` |

## Сценарий 1 — Сборка, линт, тесты backend (US1)

```bash
cd backend
./gradlew check        # ktlint + detekt + unit-тесты
./gradlew bootRun      # запуск локально
```

Ожидания:
- `check` завершается exit 0; smoke-тесты (context-load, health) зелёные.
- Нарушение линта (например, лишняя пустая строка) → `ktlintCheck`/`detekt` падают с указанием файла и строки (US1-3).
- Запуск: логи — валидный JSON в одну строку на каждую запись (поля `@timestamp`, `level`, `message`); порт 8080.

## Сценарий 2 — Health-check backend (US1-2)

```bash
curl -s localhost:8080/actuator/health/liveness   # → {"status":"UP"}
curl -s localhost:8080/actuator/health/readiness  # → {"status":"UP"}
curl -s localhost:8080/actuator/prometheus | grep http_server_requests
```

Ожидания: оба зонда отвечают `200`/`UP` в течение 60 секунд после старта; в выгрузке
Prometheus присутствуют `http_server_requests_seconds_bucket/_count/_sum` (FR-002, FR-013).

## Сценарий 3 — Сборка, линт, тесты frontend (US1)

```bash
cd frontend
pnpm install
pnpm lint          # eslint + prettier --check
pnpm typecheck     # tsc --noEmit (strict)
pnpm test          # vitest run — smoke-рендер App
pnpm build         # vite build → dist/
```

Ожидания: все команды exit 0; `dist/` содержит `index.html` + хешированные assets (FR-002).

## Сценарий 4 — Контрактный пайплайн (US4)

```bash
# валидация
vacuum lint -e contracts/openapi.yaml

# генерация типов и drift-проверка
pnpm --dir frontend generate:api
git status --porcelain frontend/src/api/schema.d.ts   # пусто = нет расхождения

# breaking-проверка относительно main
git fetch origin main
git show origin/main:contracts/openapi.yaml > /tmp/base.yaml
oasdiff breaking /tmp/base.yaml contracts/openapi.yaml
```

Ожидания:
- Валидация — exit 0.
- После регенерации `git status` по `frontend/src/api/schema.d.ts` пуст (детерминированность, US4-4). Демонстрация drift: изменить контракт так, чтобы изменение отражалось в генерируемых типах (например, добавить путь `/ping`), и не закоммитить регенерированный `schema.d.ts` → CI-джоб `contract` падает (US4-2). Примечание: правки только `info.*` (например, `info.title`) не попадают в вывод openapi-typescript 7.x и drift не дают.
- Демонстрация breaking: удалить/переименовать существующий путь контракта → `oasdiff breaking` exit ≠ 0 без `contracts/BREAKING.md` (US4-3).

## Сценарий 5 — Docker-образы локально (US3-2)

```bash
docker build -t webchat-backend:local backend/
docker run --rm -p 8080:8080 webchat-backend:local &   # health — как в сценарии 2
docker build -t webchat-frontend:local frontend/
docker run --rm -p 8081:8080 webchat-frontend:local &  # curl localhost:8081/healthz → ok
```

Ожидания: оба образа собираются multi-stage; контейнеры запускаются non-root и отвечают на свои health-endpoints (см. technical-endpoints.md).

## Сценарий 6 — Деплой в dev (US3, требуется кластер)

```bash
cd deploy/k8s/overlays/dev
kustomize edit set image \
  backend=${REGISTRY}/${IMAGE_PREFIX}/backend:$(git rev-parse HEAD) \
  frontend=${REGISTRY}/${IMAGE_PREFIX}/frontend:$(git rev-parse HEAD)
kubectl apply -k .
kubectl rollout status deploy/backend --timeout=180s
kubectl rollout status deploy/frontend --timeout=180s
```

Ожидания: оба rollout — exit 0; поды становятся Ready (пробы из technical-endpoints.md);
сервисы доступны через ingress; `kubectl delete pod <pod>` → обслуживание
восстанавливается автоматически без ручных действий (US3-3, SC-004).

## Сценарий 7 — Observability-базлайн (US5, dev-окружение с LGTM-стеком)

1. Через ingress frontend выполнить запрос, проходящий в backend (в этой фиче — любой
   вызов, создающий span; при отсутствии бизнес-endpoints — проверить корреляцию на
   запросе к `/actuator/health` с заголовком `traceparent`, либо через тестовый
   запрос из SPA).
2. В логах обоих сервисов (`kubectl logs`) найти записи с одинаковым `traceId`.
3. В Grafana (Tempo) найти трейс по этому `traceId`: видны span backend (и frontend — при браузерном запросе).
4. В Grafana (Prometheus) построить latency/throughput/error rate по `http_server_requests_seconds_*`.

Ожидания: один traceId во всех сигналах; метрики latency/throughput/errors доступны
для backend в любой момент его работы (SC-005, SC-006).

## Сценарий 8 — CI-конвейер (US2)

1. Открыть PR с заведомо падающим тестом → джоб красный, required check блокирует merge, причина видна в логе джоба (US2-1).
2. Открыть PR с зелёными проверками → merge разрешён без ручных действий (US2-2).
3. Merge в main → образы с SHA-тегами опубликованы, dev обновлён автоматически (US3-1, SC-003, SC-008).

## Соответствие артефактам

- Проверяемые интерфейсы: [contracts/api-contract.md](./contracts/api-contract.md), [contracts/technical-endpoints.md](./contracts/technical-endpoints.md).
- Инварианты артефактов (контракт, образы, релизы, телеметрия): [data-model.md](./data-model.md).
- Обоснование выборов стека: [research.md](./research.md).
