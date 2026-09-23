# Quickstart: Устойчивость доставки при разрывах и пиковой нагрузке

**Feature**: 005-delivery-resilience | **Date**: 2026-09-23

Руководство по end-to-end проверке фичи вручную и автотестами. Ссылки на детали:
[протокол синхронизации](./contracts/sync-protocol.md), [изменения API](./contracts/api-contract.md),
[модель данных](./data-model.md), [решения](./research.md). База — сценарии 004
([quickstart 004](../../004-direct-messaging-core/quickstart.md)); здесь проверяются
дополнительно разрывы, оффлайн-режим и деградация под нагрузкой.

## 1. Предпосылки

- JDK 21, Node 24 + pnpm, Docker (Testcontainers и локальная инфраструктура).
- Клон репозитория, ветка `005-delivery-resilience`.
- Три завершённых аккаунта (фича 002): `alice`, `bob`, `carol` (см.
  [quickstart 002/004](../../004-direct-messaging-core/quickstart.md)).

## 2. Локальная инфраструктура и запуск

```bash
docker compose -f deploy/local/docker-compose.yml up -d   # PostgreSQL 17 :5432, Redis 7 :6379, Mailpit :8025
./gradlew bootRun                                          # workdir backend/ — Flyway применяет V12
pnpm --dir frontend dev                                    # SPA :5173, /api → :8080
```

Новые настройки: sync/ack — значения контракта (`message-page-size=50`, `chat-page-size=20`,
`ack-batch-limit=100`); backpressure — [research.md §6](./research.md): `enabled=true`,
`min-limit=4`, `max-limit=64`, `latency-baseline=50ms` (для стресс-ручной проверки —
заниженный `max-limit`). Прочее — как в 004.

Далее: два браузера/приватных окна, мессенджер на `/`. DevTools → Network → **Offline** —
основной инструмент разрывов; HTTP-примеры — с `Authorization: Bearer <accessToken>`.

## 3. Сценарии проверки

### 3.1 Синхронизация пропущенных при переподключении (US1) — SC-001, SC-008

1. Bob в диалоге, получает realtime-сообщения Alice. Перевести Bob в Offline; Alice отправляет
   N=10 сообщений; Bob обратно онлайн → появились ровно 10, по одному разу, по порядку
   (проверить seq), индикатор дозагрузки мелькнул и погас.
2. Разрыв «на середине»: Offline Bob → Alice пишет 5 → Bob online (синхронизация началась) →
   сразу Offline → снова online → итог: все 10+5 сообщений по одному разу (курсор = последняя
   ack'нутая позиция; `SyncIT` покрывает автоматикой).
3. Дубль доставки: повторно выполнить `POST /users/me/sync` с теми же курсорами → те же
   сообщения; в UI ничего не задвоилось (дедуп по `message.id`, US1-3).
4. 200+ сообщений в нескольких чатах (третья учётная запись Carol пишет Bob; Alice пишет Bob):
   разрыв на несколько минут → восстановление → дозагрузка порциями, первыми — чаты с новыми
   сообщениями; время дозагрузки 200 сообщений ≈ секунды (бюджет ≤ 10 с p95 — k6 §4.11);
   счётчики растут до фактических.
5. Curl-проверка контракта:

```bash
curl -s -X POST http://localhost:8080/api/v1/users/me/sync \
  -H "Authorization: Bearer $BOB" -H "Content-Type: application/json" \
  -d '{"cursors":[{"chatId":"<chatId>","upToSeq":0}]}'
# → SyncResponse: дельты ascending, hasMore/moreChats корректны; затем
curl -s -X POST http://localhost:8080/api/v1/users/me/delivery-ack \
  -H "Authorization: Bearer $BOB" -H "Content-Type: application/json" \
  -d '{"acks":[{"chatId":"<chatId>","upToSeq":<последний seq страницы>}]}' # → 204; повтор → 204
```

### 3.2 Обрезка и границы (US1-5, edge) — FR-003

1. Bob удаляет чат у себя (`DELETE /chats/{id}`, 004) → Alice пишет новое → у Bob чат
   возвращается: синхронизация даёт только сообщения после точки удаления; дельта несла
   `truncatedUpToSeq`; старая история не восстановилась; непрочитанные = только новые.
2. Курсор «из будущего» (ручная имитация backup-ремонта): sync с `upToSeq: 999999` → ответ не
   ошибка: `desynced: true` + `serverUpToSeq` по чату, клиент отматывает и продолжает.

### 3.3 Оффлайн-очередь исходящих (US2) — SC-002

1. Bob Offline: отправить 3 сообщения → видны со статусом «отправляется», сохранены в
   localStorage (`webchat.chats.outbox.<userId>`). Закрыть вкладку/браузер, открыть снова
   (сеть всё ещё выкл.) → очередь на месте. Включить сеть → все 3 доставлены Alice ровно по
   одному разу, в порядке отправки, статусы → ✓.
2. Потеря подтверждения: Offline Bob → отправка 1 сообщения → сеть на миг (запрос ушёл,
   ответ не получен) → снова Offline → online → повтор тем же ID: у Alice один экземпляр
   (дедуп 004 + ретрай тем же `clientMessageId`).
3. Постоянный отказ: Alice блокирует Bob → Bob online повторяет из очереди → сообщение
   переходит в «не отправлено» (ошибка `you_are_blocked`), остальные записи очереди
   обрабатываются дальше (FR-006).
4. Лимит очереди (автотест `useOutbox`): при 1000 записей новые вытесняют старейшие
   `sending` → «не отправлено» с причиной `queue_overflow`, баннер показан; ручной повтор
   вытеснённого тем же ID работает.
5. Превышение 30/мин в онлайне: 31-е сообщение прозрачно встаёт в очередь («отправляется»,
   отсчёт) и уходит при открытии окна — без ошибки пользователю (US2-7; 004 §3.8 расширено).

### 3.4 Непрочитанные и прочтения (US3) — SC-003

1. Realtime: сообщение в закрытый чат → бейдж +1; sync-дозагруженное → бейдж растёт до
   фактического; открытие чата → сброс, у Alice ✓✓.
2. Частичное чтение постраничной дозагрузки: счётчик уменьшается на прочитанное, не в ноль
   (watermark растёт по мере прокрутки, US3-6).
3. Оффлайн-прочтения: Bob Offline читает ранее синхронизированное → `webchat.sync.pendingReads`
   populated → перезапуск приложения → сеть on → флаш через №17: серверный счётчик сходится,
   у Alice ✓✓ (US3-7).
4. Статусы за оффлайн: Alice Offline, пока Bob прочитал её сообщения; Alice online →
   догоняющая синхронизация приносит `peerReadUpToSeq` → ✓✓ без обновления страницы (US3-8).
5. Блокировка: бейдж у блокирующего «заморожен» (004 §3.7 сохраняется).

### 3.5 Backpressure и контролируемая деградация (US4) — SC-005/006/007

1. Ручная проверка сигнала: в dev-профиле занижить `delivery.backpressure.max-limit` (например,
   2) и `min-limit` 1 → параллельные отправки №16: часть ответов `503` + `Retry-After`,
   problem+json `server_busy`; уже принятые (201) повтором тем же ID возвращаются `200`
   (никогда 503); клиент UI ошибок не показывает — очередь «отправляется» и уходит по
   Retry-After.
2. Автотест `BackpressureIT`: порядок дедуп → admission → флуд; AIMD-восстановление лимита
   после спада (gauge `webchat_backpressure_limit` возвращается к базе).
3. 429 при перегрузке не «съедает» флуд-токены: после 503 и Retry-After свежая отправка,
   дошедшая до INSERT, платит токен; обхода лимита повторами нет (FR-010).

### 3.6 Контрактный клиент и конвейер (конституция IV)

1. Скрипт по контракту: №26 → рендер → №25 (ack) → №15 `after` до исчерпания → повтор №26 →
   пусто (`moreChats: false`); все схемы совпадают с `contracts/openapi.yaml`.
2. `after` + `before` вместе → `400 mixed_cursors`; `nextAfter` отсутствует на исчерпании.
3. Конвейер: `vacuum lint` → `pnpm --dir frontend generate:api` без drift → `oasdiff` без
   breaking (additive 0.5.0).

## 4. Автотесты и наблюдаемость

- Backend: `./gradlew check` (workdir `backend/`) — `DeliveryAckIT`, `SyncIT`,
  `AscendingHistoryIT`, `UnreadConvergenceIT`, `BackpressureIT` ([research.md §11](./research.md)).
- Frontend: `pnpm --dir frontend test` — cursors/single-flight/ack-батчер/pendingReads/
  outbox-вытеснение/индикаторы.
- Метрики (FR-014) на `http://localhost:8080/actuator/prometheus`: в сценариях 3.1–3.5 растут
  `webchat_sync_request_seconds`, `webchat_sync_messages_delivered_total`,
  `webchat_sync_truncated_total`, `webchat_sync_desynced_total`,
  `webchat_delivery_ack_seconds`, `webchat_backpressure_rejections_total`; gauges
  `webchat_backpressure_limit`/`webchat_backpressure_inflight` показывают деградацию и рекавери;
  warn-логи shed/обрезок — в JSON-выводе (без текста сообщений).
- Нагрузочные (сначала `docker build -t webchat-k6 load/k6`):
  - **Разрывы/SC-001/SC-008**: `docker run --rm -i --network host webchat-k6 run - <
    load/k6/delivery-resilience.js` — принудительные обрывы SSE в разных точках пути,
    аудит «0 потерь / 0 дублей / порядок монотонен» по seq; дозагрузка 200 сообщений
    p95 ≤ 10 с; env `K6_BASE_URL`, `K6_MAILPIT_URL`, `K6_DISCONNECT_PROFILE`.
  - **Насыщение/SC-005/006/007**: тот же скрипт, `-e K6_TARGET_RPS=<выше пропускной
    способности стенда>`: пороги — 0 connection-errors/timeouts без предшествующего
    503/429 («молчаливых» отказов нет), все отказы с `Retry-After` ≥ 1, принятые
    доставляются без потерь/дублей, рекавери латентности ≤ 5 мин после ramp-down.
  - **Бюджетный профиль 100k msg/s × 10 мин** (SC-004/FR-012): тот же скрипт,
    `-e K6_TARGET_RPS=100000 -e K6_DURATION=10m` — исполняется распределённым k6 на
    масштабированной инфраструктуре (лестница 004 research §12); параметры и пороги готовы,
    прогон закреплён за платформенной фичей 014 (ROADMAP) — обоснование в
    [plan.md](./plan.md) Complexity Tracking.
