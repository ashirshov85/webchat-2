# API-контракт: 008a-social-signals (REST)

**Дата**: 2026-10-08 | Контракт-источник: `contracts/openapi.yaml` **0.8.0 → 0.9.0** (строго аддитивно, FR-016/SC-007)

Изменения: 4 новые операции №39–№42; опциональные поля в существующих схемах. Общие конвенции сохраняются: Bearer-авторизация, ошибки RFC 9457 problem+json (`code` snake_case, `errors:{field:[code]}`), флуд-отказ — **429 `flood_limit` + заголовок `Retry-After`** (секунды, ceil), идемпотентность операций.

---

## 1. Новые операции

### №39 — обновление профиля (displayName)

```yaml
PUT /api/v1/users/me/profile
operationId: updateMyProfile        # summary: "Обновить мой профиль (№39, Bearer)"
body (application/json): ProfileUpdateRequest
  displayName?: string | null   # null/отсутствие = сброс (имя не задано); ""/пробельная строка = 400 (сброс — только явный null)
responses:
  200 -> PublicUser              # обновлённое представление (с displayName, если задано)
  400 invalid_display_name       # пусто/пробельно после trim, или > 64 символов
  401 unauthorized
  429 flood_limit + Retry-After
```

- **Флуд-лимит**: `rl:user:profile:{userId}`, 30/мин (паритет №38).
- **Идемпотентность**: повтор того же значения — 200, без побочных событий (realtime-события смены имени нет — рефетч-семантика).
- **Валидация**: серверный trim; допустимая длина 1–64 после trim; Unicode без ограничений (кириллица/латиница/эмодзи, US1 edge).

### №40 — персональный alias контакта

```yaml
PUT /api/v1/contacts/{userId}/alias
operationId: setContactAlias      # summary: "Задать/сбросить персональный alias контакта (№40, Bearer)"
body: AliasUpdateRequest
  alias?: string | null          # null/отсутствие = сброс → отображение по displayName/username; ""/пробельная строка = 400 (сброс — только явный null)
responses:
  200 -> ContactView             # user (PublicUser с displayName), alias?, createdAt, blockedByMe
  400 invalid_alias              # пусто/пробельно после trim, или > 64 символов; invalid_uuid (path)
  404 contact_not_found          # {userId} не является контактом запросчика
  401 unauthorized
  429 flood_limit + Retry-After
```

- **Флуд-лимит**: `rl:user:alias:{userId}`, 30/мин.
- **Идемпотентность**: последний write wins.
- **Жизненный цикль**: alias удаляется вместе с контактом (№22); переживает удаление чата; виден только запросчику во всех его представлениях.

### №41 — сигнал набора текста (эфемерный)

```yaml
POST /api/v1/chats/{chatId}/typing
operationId: sendTypingSignal    # summary: "Сигнал набора текста в чате (№41, Bearer)"
body: TypingRequest
  action: enum start | stop      # обязательное
responses:
  204 No Content                 # сигнал принят (идемпотентно: повтор start = продление)
  400 invalid_action | invalid_uuid
  401 unauthorized
  403/404 — коды доступа чата, те же, что у №16 (chat_not_found / not-chat-member семантика 004/006)
  429 flood_limit + Retry-After  # избыточные сигналы отклонены, НЕ публикуются
```

- **Флуд-лимит**: `rl:user:typing:{userId}`, **60 сигналов/мин** (все чаты пользователя).
- **Константы** (нормативные, см. [realtime-events.md](./realtime-events.md)): окно повтора клиента **3 с**; окно молчания клиента 5 с; серверный таймаут состояния **8 с**.
- **Публикация**: `start` → `typing.started {chatId, userId}` участникам чата **кроме отправителя**; `stop` → `typing.stopped`. DIRECT-блок-пара (обе стороны, 004 FR-020): сигналы принимаются `204` (если членство валидно), но **не публикуются** — наблюдатели дребезга не видят (AC7/AC8).
- **Не существует** операции чтения состояния набора (эфемерность: подключившиеся не восстанавливают, AC5).

### №42 — настройка звука чата (per-user-per-chat)

```yaml
PUT /api/v1/chats/{chatId}/sound
operationId: setChatSound        # summary: "Переключить звуковые оповещения чата (№42, Bearer)"
body: ChatSoundRequest
  enabled: boolean               # обязательное
responses:
  200 -> ChatSoundResponse { soundEnabled: boolean }
  400 invalid_body | invalid_uuid
  401 unauthorized
  403/404 — коды доступа чата (как №13/№16)
  429 flood_limit + Retry-After
```

- **Флуд-лимит**: `rl:user:chat-sound:{userId}`, 30/мин.
- **Идемпотентность**: повтор того же значения — 200 **без** события; смена значения — событие `chat.sound.updated` только в собственный канал №18.
- **Приватность**: значение и факт настройки не раскрываются другим участникам (ни в представлениях, ни в событиях).
- Без сети операция не выполняется (ошибка сети) — офлайн-очереди переключений нет.

---

## 2. Изменения существующих схем (все поля опциональные → non-breaking)

| Схема / место | Поле | Тип | Семантика |
|---|---|---|---|
| `PublicUser` (№10, №19, №20/21, peer №11/№12/№13/№26, №28 members) | `displayName` | `string`, opt | Профильное имя; отсутствует/null → отображение `username`. Экранирование — клиентский инвариант 008 FR-033 |
| `ContactView` (№20/№21, №40-ответ) | `alias` | `string`, opt | Персональный alias **запросчика**; отсутствует = не задан |
| peer-фрагмент `ChatView`/`ChatListItem` (№11/№12/№13) | `displayName`, `alias` | `string`, opt | Профильное имя peer + alias запросчика к peer |
| `GroupMember.user` (№28) | `displayName`, `alias` | `string`, opt | Для каждого участника; alias — запросчика |
| `ChatView`, `ChatListItem` (№11/№12/№13) | `soundEnabled` | `boolean`, opt | Персональная настройка звука чата запросчика; сервер выставляет всегда; отсутствие (старый сервер) трактуется клиентом как `true` |
| `PresenceStatusItem` (№36) | `lastSeenAt` | `string` format date-time, opt | Только при `status=offline`, в аудитории видимости 007, не инкогнито, данные есть; **отсутствие поля нейтрально** (нет данных ≡ скрыто ≡ посторонний ≡ блок-пара) |
| `PresenceUpdatedEvent` (№18) | `lastSeenAt` | `string` date-time, opt | Те же правила; при offline-переходе в рамках аудитории |

- Лимит длины имён: **1–64 символа после trim** для `displayName` и `alias` (запись; чтение возвращает сохранённое).
- Серверные сортировки и поиск №19 (точное совпадение username/email) не меняются; серверный поиск по displayName вне объёма.

## 3. Обратная совместимость

- Все новые поля — optional; все новые операции/события — дополнительные. Клиенты 008 работают без изменений: неизвестные JSON-поля игнорируются, неизвестные `event:` канала №18 игнорируются (контракт 004).
- Гейт: `oasdiff breaking --fail-on ERR` = 0 ошибок; `info.description` дополняется параграфом «Фича 008a» (конвенция 001); TS-типы регенерируются `pnpm --dir frontend generate:api` (drift-check в CI).

## 4. Валидационные коды (новые problem-codes)

| code | HTTP | Где |
|---|---|---|
| `invalid_display_name` | 400 | №39: пусто/пробельно/длинно |
| `invalid_alias` | 400 | №40: пусто/пробельно/длинно |
| `contact_not_found` | 404 | №40: `{userId}` не контакт |
| `invalid_action` | 400 | №41: не `start`/`stop` |
| `flood_limit` | 429 | №39–№42 (+Retry-After) — существующий код |
