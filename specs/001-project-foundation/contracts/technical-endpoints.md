# Contract: Технические endpoints и зонды

**Feature**: 001-project-foundation

Технические интерфейсы приложений. Не являются частью публичного API-контракта
(конституция IV ограничивает публичный API бизнес-интерфейсами; health/metrics — инфраструктурные интерфейсы, FR-002).

## 1. Backend (Spring Boot Actuator)

| Endpoint | Метод | Успешный ответ | Назначение |
|---|---|---|---|
| `/actuator/health/liveness` | GET | `200` + `{"status":"UP"}` | Liveness-проба K8s: процесс жив |
| `/actuator/health/readiness` | GET | `200` + `{"status":"UP"}` | Readiness-проба K8s: готов принимать трафик |
| `/actuator/prometheus` | GET | `200`, `text/plain` (формат Prometheus) | Экспорт метрик (scrape Prometheus) |

Конфигурация (application.yml):

```yaml
management:
  endpoints.web.exposure.include: health,prometheus
  endpoint.health.probes.enabled: true
  prometheus.metrics.export.enabled: true
  metrics.distribution.percentiles-histogram.http.server.requests: true
  otlp.tracing.endpoint: ${OTEL_EXPORTER_OTLP_ENDPOINT}
```

Метрики (FR-013): `http_server_requests_seconds_{bucket,count,sum}` с тегами
`method, uri, status, outcome, exception, application` → latency / throughput / error rate.

Логи: stdout, 100% JSON (`LogstashEncoder`), поля `traceId`/`spanId` из MDC.

## 2. Frontend (nginx, статический артефакт)

| Endpoint | Метод | Успешный ответ | Назначение |
|---|---|---|---|
| `/` (и любые SPA-маршруты) | GET | `200`, `index.html` (`try_files … /index.html`) | Раздача приложения |
| `/healthz` | GET | `200`, тело `ok`, `access_log off` | Проба готовности статической раздачи |
| `/assets/*` | GET | `200`, `Cache-Control: immutable` | Хешированные бандлы |
| прочие файлы | GET | `200`/`404` | — |

Заголовки (все ответы): `X-Content-Type-Options: nosniff`, `X-Frame-Options: DENY`,
`Referrer-Policy`; gzip для text/js/json. Порт `8080`, non-root.

Рантайм-запросов frontend НЕ выполняет в этой фиче (нет бизнес-API); код обёртки
`client.ts` и OTel-инициализация присутствуют, но не активируют нагрузку.

## 3. Kubernetes-пробы (deploy/k8s/base)

| Сервис | Liveness | Readiness |
|---|---|---|
| backend | `httpGet /actuator/health/liveness :8080` | `httpGet /actuator/health/readiness :8080` |
| frontend | `httpGet /healthz :8080` | `httpGet /healthz :8080` |

Общие параметры: `initialDelaySeconds` минимальный, `periodSeconds` ~10,
`failureThreshold` ~3; Deployment: `progressDeadlineSeconds`, `runAsNonRoot: true`,
`imagePullSecrets: [regcred]`, образ — SHA-тег (никогда `latest`/`main` в кластере).

## 4. Контракт трейсинга (frontend → backend)

| Аспект | Значение |
|---|---|
| Формат контекста | W3C Trace Context: заголовок `traceparent: 00-{traceId}-{parentId}-{flags}` |
| Инициатор | Браузерный OTel SDK (`sdk-trace-web` + `instrumentation-fetch`, propagator W3C) |
| Продолжатель | Backend: Micrometer Tracing (OTel bridge) извлекает `traceparent` и продолжает трейс; `traceId` попадает в MDC → в JSON-логи |
| Экспорт | Оба сервиса → OTel Collector (OTLP, endpoint из `OTEL_EXPORTER_OTLP_ENDPOINT`) → Tempo |
| Именование | `service.name`: `backend` / `frontend` — единая строка в трейсах, метриках, логах |
| Требование | Один запрос = один traceId в логах/трейсах всех затронутых сервисов (FR-011, FR-012, US5-1) |

Примечание: при появлении реальных CORS-запросов бэкенд должен разрешать заголовок
`traceparent` (preflight); в этой фиче браузерных запросов к API нет.

## 5. CI/CD-интерфейс конвейера (соглашение)

| Триггер | Джобы | Обязательность |
|---|---|---|
| pull_request | `backend` (lint+build+test), `frontend` (lint+typecheck+test), `contract` (validate+drift+breaking), gitleaks | Все — required status checks; падение любого блокирует merge (FR-003) |
| push в main | те же + `backend-image`, `frontend-image` (build+push SHA-тега), `deploy-dev` (apply+rollout status) | Деплой успешен ⟺ rollout status 0 у обоих deployment (FR-005) |

Входы деплоя: `REGISTRY`, `IMAGE_PREFIX` (variables), `REGISTRY_USERNAME`,
`REGISTRY_PASSWORD`, `KUBE_CONFIG` (secrets). Никаких секретов в манифестах (FR-014).
