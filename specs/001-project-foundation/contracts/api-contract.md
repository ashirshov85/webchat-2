# Contract: Публичный API-контракт (OpenAPI)

**Feature**: 001-project-foundation | **Источник истины**: `contracts/openapi.yaml`

## 1. Скелет контракта (полное содержимое на момент этой фичи)

```yaml
openapi: 3.1.0
info:
  title: WebChat Public API
  version: 0.1.0
  description: >
    Публичный API веб-чата. Единственный источник истины публичного API;
    TypeScript-типы фронтенда генерируются из этого файла автоматически.
paths: {}
```

Бизнес-endpoints запрещены в этой фиче (FR-015): `paths` пуст. Первый бизнес-endpoint
появится в следующей фиче — только через изменение этого файла.

## 2. Правила

| Правило | Значение |
|---|---|
| Формат | OpenAPI **3.1**, один файл `contracts/openapi.yaml` |
| Изменение | Только через pull request; дифф контракта виден ревьюерам (US4) |
| Версионирование | `info.version` — semver; breaking-change допускается только с явным решением |
| Breaking-маркер | Файл `contracts/BREAKING.md` с обоснованием; удаляется после мержа |

## 3. Конвейер проверок (CI, джоб `contract`)

| Шаг | Команда | Ожидание |
|---|---|---|
| Валидация/линт | `vacuum lint -e contracts/openapi.yaml` | exit 0 (валидный OpenAPI 3.1, нет линт-ошибок) |
| Генерация типов | `pnpm --dir frontend generate:api` (→ `openapi-typescript contracts/openapi.yaml -o frontend/src/api/schema.d.ts`) | файл создан/обновлён |
| Drift-детекция | `git diff --exit-code -- frontend/src/api/schema.d.ts` после регенерации | exit 0 = коммит-версия совпадает с генерацией (FR-009) |
| Breaking-детекция | `oasdiff breaking <base:contracts/openapi.yaml> contracts/openapi.yaml` (base = `origin/main`); шаг пропускается только при наличии `contracts/BREAKING.md` | exit 0 = breaking-изменений нет (FR-010) |

**Детерминированность** (US4-4): повторная генерация без изменения контракта не меняет
файл (types-only вывод без таймстампов) — ложных срабатываний drift-check нет.

## 4. Маппинг генерации типов

| Контракт | Сгенерированный TypeScript (`frontend/src/api/schema.d.ts`) |
|---|---|
| `paths` | интерфейс `paths` (пустой в этой фиче) |
| `components.schemas.*` | `components['schemas'][...]` (отсутствуют в этой фиче) |
| `operations` | интерфейс `operations` (пустой в этой фиче) |

Потребление — `import type { paths, components } from './api/schema'` из
`frontend/src/api/client.ts`. Файл компилируется `tsc --noEmit` при пустом контракте.

## 5. Совместимость (для следующих фич)

- Публичный API обратно совместим по умолчанию (конституция IV).
- Добавление endpoint/поля — minor; удаление/переименование/сужение типа — breaking
  (major + `BREAKING.md` + явное решение в `plan.md` соответствующей фичи).
- Серверная реализация (Spring) сверяется с контрактом; технические endpoints
  (health/metrics) — **не** часть публичного контракта (см. [technical-endpoints.md](./technical-endpoints.md)).
