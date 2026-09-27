# Quickstart: Групповые чаты с ролями и member-авторизацией

**Feature**: 006-group-chats | **Date**: 2026-09-25

Руководство по end-to-end проверке фичи вручную и автотестами. Ссылки на детали:
[операции API](./contracts/api-contract.md), [события группы](./contracts/realtime-group-events.md),
[модель данных](./data-model.md), [решения](./research.md). База — сценарии 004/005
([quickstart 004](../../004-direct-messaging-core/quickstart.md)); здесь проверяются группы,
роли, приватность и жизненный цикл. Все гарантии доставки 004/005 проверяются в группах теми же
приёмами (разрывы/оффлайн/флуд) — ниже только группоспецифичные сценарии.

## 1. Предпосылки

- JDK 21, Node 24 + pnpm, Docker (Testcontainers и локальная инфраструктура).
- Клон репозитория, ветка `006-group-chats`.
- Четыре завершённых аккаунта (фича 002): `alice`, `bob`, `carol`, `dave` (см.
  [quickstart 002/004](../../004-direct-messaging-core/quickstart.md)).

## 2. Локальная инфраструктура и запуск

```bash
docker compose -f deploy/local/docker-compose.yml up -d postgres redis mailpit
./gradlew bootRun                                          # workdir backend/ — Flyway применяет V14
pnpm --dir frontend dev                                    # SPA :5173, /api → :8080
```

Обязательные env backend — как в 005 (`AUTH_IP_HASH_PEPPER`, `AUTH_JWT_KEYS`,
`AUTH_JWT_ACTIVE_KID`; dev-ключ одной командой — [quickstart 005 §2](../../005-delivery-resilience/quickstart.md)).
Новые настройки: `groups.max-members=200`, `groups.title-max-length=64`,
`groups.description-max-length=256` — значения контракта; для стресс-ручной проверки лимита
участников — заниженный `groups.max-members`, например
`SPRING_APPLICATION_JSON='{"groups":{"max-members":3}}'`.

Подготовка контактов (фича 004): alice добавляет в контакты bob, carol, dave; bob — carol
(`POST /contacts`, см. quickstart 004). Далее — два браузера/приватных окна; HTTP-примеры — с
`Authorization: Bearer <accessToken>`; SSE-поток — DevTools → Network → EventStream.

## 3. Сценарии проверки

### 3.1 Создание группы и участники из контактов (US1) — SC-005

1. Alice: кнопка «Создать группу» → название «Проект WebChat» → выбрать bob и carol из контактов →
   создать. Группа сразу в «Чатах» у всех троих: у Alice роль owner, у bob/carol — member; состав
   и роли видны в инфо-панели (US1-1/US1-2).
2. Curl-эквивалент:

```bash
curl -s -X POST http://localhost:8080/api/v1/groups \
  -H "Authorization: Bearer $ALICE" -H "Content-Type: application/json" \
  -d '{"title":"Проект WebChat","memberUserIds":["<bobId>","<carolId>"]}'
# → 201 GroupView: myRole=owner, members с ролями; bob получает SSE group.member.added
```

3. Валидация: пустое/пробельное название, 65 символов, описание 257 → `400 invalid_title` /
   `invalid_description` (US1-4). Участник не из контактов (dave, не добавленный alice) → `422
   not_in_contacts`, группа НЕ создана (US1-3). Себя в memberUserIds → `400 self_forbidden`.
4. Лимит: при `groups.max-members=3` добавить dave (состав 3) → `409 group_full`; повторные
   попытки не создают записей (US1-5/edge).

### 3.2 Переписка с гарантиями 004/005 (US2) — SC-001/SC-002

1. Alice пишет в группу → у bob/carol сообщение в реальном времени, у Alice ✓ после подтверждения
   (US2-1). Повтор POST того же `clientMessageId` → у каждого ровно один экземпляр (US2-2).
2. Bob Offline → 10 сообщений от carol → online: все по одному разу, по порядку (seq), счётчик
   непрочитанных группы сходится (US2-3); история грузится постранично (50, US2-4).
3. Bob прочитал всё → у carol ✓✓ (все активные, кроме отправителя, просмотрели; US2-5). Группа из
   одного владельца: сообщение отправляется, ✓✓ не выставляется (edge).
4. Флуд: 31-е сообщение в минуту — прозрачная очередь (US2-6, поведение 004).

### 3.3 Роли и управление составом (US3) — SC-004

1. Alice назначает bob admin (№34) → у всех участников роль обновляется в составе без перезагрузки
   (событие `group.role.changed`, US3-1). Снятие: alice возвращает bob роль member (№34,
   `role:'member'`) → событие `group.role.changed`, журнал `admin_revoked`; повторное назначение —
   `admin_granted`.
2. Bob (admin) добавляет dave из СВОИХ контактов (bob–dave контакт) — успех; исключает dave —
   успех. Попытки bob исключить carol-admin/саму alice, изменить роли → `403
   role_hierarchy_violation` / `not_group_owner` (US3-2). Carol (member) добавляет/исключает/
   меняет роли → `403 forbidden_role` (US3-3).
3. Alice передаёт владение bob (№35) → bob owner, alice admin (ровно один owner — проверить
   `ux_chat_participants_owner`: повторная попытка второго owner на группу невозможна); bob
   назначает alice admin обратно и передаёт ей владение — прежний владелец каждый раз становится
   admin (US3-4).
4. Изменения состава/ролей видимы онлайн-участникам ≤ 2 с (SC-004; EventStream в DevTools).

### 3.4 Метаданные (US4)

1. Admin переименовывает группу и меняет описание → у всех участников название в «Чатах» и
   заголовке меняется без перезагрузки (`group.updated`, US4-1); журнал — `title_changed`/
   `description_changed`.
2. Carol (member) переименовывает → `403 forbidden_role` (US4-2). Некорректное название/описание
   → `400` (US4-3). Два admin переименовывают одновременно → применяется последняя
   подтверждённая операция целиком, «смешивания» нет (edge).

### 3.5 Приватность: авторизация на каждом запросе (US5) — SC-003/SC-006

1. Dave (не участник, аутентифицирован): №28/№29/№31/№32/№34/№35 по groupId, №15/№16/№17 с
   chatId группы, sync №26 с курсором группы → везде отказ; для операций группы — единый `404
   group_not_found` (существование не раскрывается, US5-1). Поиск пользователей/контактов группу
   не находит (US5-4).
2. Bob исключает dave (повторно добавленного из §3.3): dave получает `group.you_removed
   {reason:'kicked'}` на активном SSE — группа исчезает из его «Чатов» без перезагрузки/поллинга;
   все его прежние запросы отклоняются как в п.1 (US5-2); после события новых событий группы не
   приходит (≤5 с, US5-3/SC-006; гонка порядка — [realtime-group-events.md §1]).
3. Автотест `GroupPrivacyIT` прогоняет матрицу «каждая операция × не-участник/исключённый» — 100%
   отказов (SC-003).

### 3.6 Выход, повторное добавление, водяные знаки (US6, edge)

1. Carol покидает группу (№33): исчезает из состава, теряет доступ; её сообщения остаются в
   истории с атрибуцией (US6-1). Alice (owner) пытается выйти → `403 owner_must_transfer`
   с подсказкой (US6-2); после передачи владения — выходит штатно (US6-3).
2. Непрочитанное и выход: dave не читал сообщение carol → carol видит ✓✓ после выхода dave
   (вышедшие исключаются из условия, clarify).
3. Повторное добавление carol (контакт alice сохранился): доступна ВСЯ история; непрочитаны
   сообщения после её последнего прочтения (включая период отсутствия) — счётчик группы у carol
   это отражает (edge/FR-002).
4. Первое добавление dave в группу с историей: бейдж непрочитанных = 0, вся прежняя история
   доступна постранично (edge/FR-013).

### 3.7 Удаление группы (US6-4)

1. Owner удаляет группу (№30): у всех участников чат исчезает из «Чатов» (`group.deleted`), окна
   корректно закрываются без «зависших» состояний (edge).
2. Проверка hard-delete (SQL): `chats`/`messages`/`chat_participants` группы пусты; в
   `group_admin_log` — `group_deleted` (кто, когда), без содержимого (FR-006).

### 3.8 Журнал и метрики (FR-017, SC-008)

1. `group_admin_log` по группе (все 10 действий, data-model.md §Сущность 4): `group_created`,
   `member_added`×N, `admin_granted`, `admin_revoked`, `ownership_transferred`,
   `member_removed`, `member_left`, `title_changed`, `description_changed`, `group_deleted` —
   хронология соответствует действиям §3.1–§3.7.
2. `http://localhost:8080/actuator/prometheus`: в сценариях растут
   `webchat_group_authz_denials_total` (§3.5), `webchat_group_message_fanout_total` /
   `_seconds` (§3.2), `webchat_group_size` отражает состав.

### 3.9 Контрактный конвейер (конституция IV)

1. `vacuum lint -e contracts/openapi.yaml` → 0 ошибок.
2. `pnpm --dir frontend generate:api` → `git diff --exit-code frontend/src/api/schema.d.ts` —
   drift отсутствует (типы Group* сгенерированы).
3. `oasdiff breaking <base> contracts/openapi.yaml` — без breaking (additive 0.6.0).

## 4. Автотесты и нагрузочные

- Backend: `./gradlew check` (workdir `backend/`) — `GroupLifecycleIT`, `GroupPrivacyIT`,
  `GroupReadStatusIT`, `GroupRealtimeIT`, `GroupLimitsIT`, `GroupAdminLogIT`, эволюция
  ChatList/Sync-тестов (group-элементы).
- Frontend: `pnpm --dir frontend test` — useGroup/useGroupMembers/useGroupRealtime (идемпотентные
  события, group.you_removed/group.deleted), единый список (type-дискриминация, поиск по названию), ✓✓ по
  othersReadUpToSeq; `pnpm --dir frontend lint && pnpm --dir frontend typecheck`.
- Нагрузочные (сначала `docker build -t webchat-k6 load/k6`):

```bash
docker run --rm -i --network host webchat-k6 run - < load/k6/group-chats.js
# env: K6_BASE_URL, K6_MAILPIT_URL, K6_GROUP_SIZE (default 10), K6_MESSAGE_RPS, K6_VUS (default 10)
# Сценарии: A — K6_VUS VU параллельно создают группу + K6_GROUP_SIZE участников; p90 полного
# цикла на VU ≤ 60 с при K6_VUS=10, K6_GROUP_SIZE=10 (SC-005); B — переписка, «отправлено→доставлено» p95 ≤ 2 с
# (SC-001); C — изменения состава/ролей/метаданных ≤ 2 с (SC-004); D — authz-отказы (SC-003);
# E — smoke группы 200 участников (K6_GROUP_SIZE=200): 0 дублей, порядок seq, you_removed ≤ 5 с
# (SC-006/SC-007). Полнообъёмные пиковые бюджеты — платформенная фича 016 (ROADMAP).
```
