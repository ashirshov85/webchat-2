# Contract: Изменения публичного API — групповые чаты (OpenAPI)

**Feature**: 006-group-chats | **Источник истины**: `contracts/openapi.yaml`

Изменения к контракту 005: 9 новых endpoints (№27–№35, tag `groups`), group-поля у существующих
операций (№12/№13/№26), 6 новых `event:`-типов канала №18. Изменение — **additive** (minor
`0.5.0 → 0.6.0`, без BREAKING.md; nullable-переход `peer` у group-элементов обоснован в
[research.md §5](../research.md)); конвейер 001 (vacuum → openapi-typescript → drift-check →
oasdiff) применяется без изменений. TS-типы фронтенда — из генерации. Нормативная семантика
событий — [realtime-group-events.md](./realtime-group-events.md).

Все операции требуют `Authorization: Bearer <accessToken>`; авторизация — active-членство в группе
на каждом запросе (FR-008) + роль для управления (FR-003/FR-004). Ошибки — RFC 9457 `Problem`
(`errors: map<string, string[]>`, конвенция 002). Фиксированные константы фичи (дублируются
в контракте): **лимит участников 200** (включая владельца); **название 1–64** (после trim);
**описание ≤ 256** (опционально); страница истории/дельты — 50, флуд-лимит 30/мин (004/005,
действуют в группах без изменений); окно прекращения доставки событий группы после
исключения/выхода/удаления — ≤ 5 с (FR-010).

## 1. Приватность (все операции)

Не-участник (включая бывшего — removed) и несуществующая группа отвечают одинаково: `404
group_not_found` — существование группы не раскрывается (FR-008/FR-009). Различение появляется
только после прохождения membership-гейта: участник без достаточной роли получает `403`
(коды ниже). Публичного каталога/поиска групп нет; метаданные/состав/события — только участникам.

## 2. Операции tag `groups` (новые)

| # | Метод и путь | Тело (request) | Успех | Ошибки |
|---|---|---|---|---|
| 27 | `POST /groups` | `{title: string 1–64 (trim), description?: string ≤256, memberUserIds?: uuid[] ≤199, элементы уникальны}` — начальный состав из контактов создателя | `201` `GroupView` — создатель owner, добавленные member; всем активным (включая создателя) приходит `group.member.added` | `400` `invalid_title` \| `invalid_description` \| `invalid_member_ids` (не-uuid/дубли) \| `self_forbidden`; `422` `not_in_contacts` (кто-то из списка не в контактах создателя — весь запрос отклонён); `401` |
| 28 | `GET /groups/{chatId}` | — | `200` `GroupView` (состав с ролями ≤200) | `400` `invalid_uuid`; `401`; `404` `group_not_found` (в т.ч. для direct-чатов и чужих) |
| 29 | `PATCH /groups/{chatId}` | `{title?, description?}` (валидация как №27; хотя бы одно поле) | `200` `GroupView`; участникам — `group.updated` | `400` валидация \| `empty_patch`; `401`; `404` `group_not_found`; `403` `forbidden_role` (member не управляет метаданными) |
| 30 | `DELETE /groups/{chatId}` | — | `204` — hard-delete: история/членства/водяные знаки/счётчики стёрты; бывшим участникам — `group.deleted`; в журнале — факт удаления без содержимого | `400` `invalid_uuid`; `401`; `404`; `403` `not_group_owner` |
| 31 | `POST /groups/{chatId}/members` | `{userIds: uuid[] 1–199, уникальны}` — из контактов ДОБАВЛЯЮЩЕГО | `200` `{members: [GroupMember]}` — текущий активный состав после операции; идемпотентно для уже активных (дубля не создаёт); добавленным — группа сразу видна в «Чатах» (событие) | `400` `invalid_user_ids` \| `self_forbidden`; `401`; `404`; `403` `forbidden_role`; `422` `not_in_contacts` (атомарно: весь batch) \| `group_full` (активных + batch > 200) |
| 32 | `DELETE /groups/{chatId}/members/{userId}` | — (исключение) | `204`; исключённому — `group.you_removed` (финальное событие группы), остальным — `group.member.removed`; сообщения исключённого остаются в истории | `400` `invalid_uuid` \| `self_forbidden` (userId = вызывающий — выход только через №33; проверяется до иерархии ролей); `401`; `404`; `403` `forbidden_role` (member) \| `role_hierarchy_violation` (admin исключает admin/owner); `409` `target_not_member` (не активный участник) |
| 33 | `DELETE /groups/{chatId}/membership` | — (самостоятельный выход) | `204`; устройствам вышедшего — `group.you_removed {reason:'left'}`, остальным — `group.member.removed`; сообщения остаются; водяные знаки сохранены (повторное добавление FR-002) | `400` `invalid_uuid`; `401`; `404`; `403` `owner_must_transfer` (owner сначала передаёт владение или удаляет группу); повторный выход → `404` (членства нет) |
| 34 | `PUT /groups/{chatId}/members/{userId}/role` | `{role: 'admin'\|'member'}` (назначение/снятие admin) | `200` `GroupMember`; всем — `group.role.changed` | `400` `invalid_role` \| `self_forbidden` (owner себе); `401`; `404`; `403` `not_group_owner`; `409` `target_not_member` |
| 35 | `POST /groups/{chatId}/owner` | `{userId: uuid}` (передача владения) | `200` `GroupView` — новый owner, прежний admin (ровно один owner); всем — `group.role.changed` ×2 (парой кадров) | `400` `invalid_uuid` \| `self_forbidden`; `401`; `404`; `403` `not_group_owner`; `409` `target_not_member` |

Идемпотентность/повторы: №31 для уже активных — 200 без изменений; №30/№32/№33 повторно — `404`
(объект исчез — штатная сходимость, edge «исключение×выход»: одна операция применяется, вторая
видит `409 target_not_member`/`404`).

## 3. Расширения существующих операций (additive)

| # | Операция | Изменение |
|---|---|---|
| 12 | `GET /chats` | Элементы получают `type: 'direct'\|'group'` (у direct отсутствует/равно 'direct' — backward-friendly). Group-элемент: `title: string`, `memberCount: int 1–200`, `myRole: 'owner'\|'admin'\|'member'`, `peer: null`, `blockedByMe: null` (поля nullable — [research.md §5]); `lastMessage`/`unreadCount`/сортировка — 004 без изменений (единый список, FR-014) |
| 13 | `GET /chats/{chatId}` | Group-вариант ответа: `type:'group', title, description, myRole, myReadUpToSeq, othersReadUpToSeq` (MIN водяных знаков остальных активных; 0 при одиночной группе — ✓✓ не выставляется), `memberCount`; direct-ответ — без изменений |
| 15/16/17 | история/отправка/прочтение | Контракт без изменений: `chatId` группы валиден; membership-гейт сервера проверяет active-строку (не-участнику `404 chat_not_found`/`403 not_participant` — семантика 004); флуд/admission/дедуп — 004/005; блокировки 004 на группы не действуют |
| 18 | `GET /users/me/events` | +6 `event:`-типов: `group.updated`, `group.member.added`, `group.member.removed`, `group.role.changed`, `group.you_removed`, `group.deleted` — схемы и семантика в [realtime-group-events.md](./realtime-group-events.md); неизвестные типы игнорируются клиентом (прямая совместимость 004) |
| 25/26 | ack/sync | Контракт без изменений; server: №26 отбирает только active-членства (исключённому дельты не приходят), group-дельта несёт `type:'group'`, `title`, `memberCount`, `othersReadUpToSeq` (вместо `peer`/`peerReadUpToSeq`/`blockedByMe` = null); `startAfterSeq`/`truncatedUpToSeq`/`unreadCount` — 005 без изменений |

## 4. Схемы (components.schemas)

Новые:
- `CreateGroupRequest` (`title: string 1–64`, `description?: string ≤256`, `memberUserIds?:
  uuid[] unique ≤199`), `UpdateGroupRequest` (`title?`, `description?`), `AddMembersRequest`
  (`userIds: uuid[] 1–199 unique`), `SetMemberRoleRequest` (`role: enum admin|member`),
  `TransferOwnershipRequest` (`userId: uuid`).
- `GroupMember` (`user: PublicUser, role: enum owner|admin|member, joinedAt: timestamp`).
- `GroupView` (`chatId: uuid, title: string, description: string|null, myRole: enum, members:
  GroupMember[] ≤200`).
- События: `GroupUpdatedEvent`, `GroupMemberAddedEvent`, `GroupMemberRemovedEvent`,
  `GroupRoleChangedEvent`, `GroupYouRemovedEvent`, `GroupDeletedEvent` (поля —
  [realtime-group-events.md](./realtime-group-events.md) §3).

Изменённые (additive-optional/nullable):
- `ChatListItem`: + `type?: enum direct|group`, `title?`, `memberCount?`, `myRole?`; `peer`, 
  `blockedByMe` → nullable (group-элементы).
- `ChatView`: + group-поля (`type?`, `title?`, `description?`, `myRole?`, `othersReadUpToSeq?`,
  `memberCount?`); `peer`/`blockedByMe`/`peerReadUpToSeq` → nullable.
- `SyncChatDelta`: + `type?`, `title?`, `memberCount?`, `othersReadUpToSeq?`; `peer`,
  `blockedByMe`, `peerReadUpToSeq` → nullable.

Переиспользуются: `PublicUser`, `Message`, `Problem`, `bearerAuth`, константы 004/005.

Инвариант состава: все новые пути — аутентифицированные; авторизация per-resource (active-членство)
на каждом запросе; `memberUserIds`/`userIds` проверяются по контактам ДОБАВЛЯЮЩЕГО (фича 004,
FR-002); публичный перечень 002–003 не расширяется.
