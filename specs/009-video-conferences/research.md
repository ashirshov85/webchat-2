# Research: Видеоконференции в чатах (речь, видео, демонстрация экрана)

**Feature**: 009-video-conferences | **Date**: 2026-10-15

Исходные неизвестные Technical Context разрешены исследованием кодовой базы 001–008 (домены
`chats/groups/presence/realtime`, паттерн rate limiting 002, membership-check 006, контракт
0.10.0), официальной документации LiveKit (токены/grants, RoomService API, webhooks,
self-hosting) и решений specify/clarify-раундов спеки (Q1–Q23). Формат каждого пункта:
**Решение / Обоснование / Альтернативы**. Источник фактов LiveKit — docs.livekit.io
(access tokens & grants; Room service API; Webhooks & events; Self-hosting: Kubernetes,
ports & firewall), проверено 2026-10-15.

---

## §1. Медиа-плоскость: self-hosted SFU LiveKit

**Решение**: медиа-плоскость — self-hosted **LiveKit** (open-source WebRTC SFU на Go, CNCF-экосистема),
отдельный Deployment; в v1 — distributed mode поверх существующего Redis. Своего медиа-кода нет
(FR-004): ни RTP-форвардинга, ни сигналинга — только admin-интеграция (§3) и webhooks (§7).

**Обоснование**: выбор предписан описанием фичи и конституцией VII («предпочтение проверенным
готовым решениям … SFU для WebRTC»). LiveKit закрывает весь объём v1 без кастома: комнаты
1:1/групповые, simulcast/dynacast (FR-004 адаптация качества), демонстрация экрана
(`screen_share`/`screen_share_audio`), серверные API модерации (`UpdateParticipant`/`MutePublishedTrack`/
`RemoveParticipant`/`DeleteRoom`/`SendData`), webhooks жизненного цикла, лимиты комнаты
(`max_participants`, `empty_timeout`, `departure_timeout`), встроенный TURN (UDP/TLS),
горизонтальное масштабирование нод через Redis (бюджет 1000 конференций, принцип II),
официальные SDK: Kotlin/Java server (`io.livekit:livekit-server`), JS-клиент + React-компоненты.
Self-hosted сохраняет приватность (нет внешнего CPaaS) и нулевую стоимость на(media)-транзакцию.

**Альтернативы**: (а) **Jitsi** (Jitsi Videobridge, JVM) — отклонено:Admin-плоскость (XMPP-оркестрация
или отдельный Jibri/prosody-стек) существенно тяжелее в K8s; модель per-conference token-grants
слабее (нет `canPublishSources`-гранулярности, необходимой FR-008/FR-011); клиентский бандл
(iframe API / lib-jitsi-meet) плохо встраивается в React-SPA и публичную лёгкую гостевую
join-страницу; (б) **CPaaS** (Twilio Video/Vonage/Agora) — отклонено: внешний вендор = данные
медиа вне периметра (принцип V), платная транзакция на минуту, зависимость доступности от
вендора; закрытость не позволяет per-env dev-стенд без счётчика; Twilio Video к тому же
EOL-анонсирован; (в) **свой SFU** (mediasoup/Pion самосборка) — отклонено прямо спекой
(FR-004: «своего медиа-кода не пишется») и конституцией VII.

## §2. Контрольная плоскость: stateless-модуль `conferences/` в монолите

**Решение**: новый домен `backend/src/main/kotlin/webchat/backend/conferences/` по образцу
`groups/` (api/api.dto, domain.model/service/port, repository, scheduler; `ConferenceMetrics.kt`),
stateless-инстансы монолита масштабируются горизонтально; всё состояние — PostgreSQL (V18,
[data-model.md](./data-model.md)) + LiveKit + Redis. Переиспользуются без дублирования:
membership-check 006 — порт `ChatMembershipChecker`, адаптированный над
`GroupMembershipGate.requireActiveMembership/findActiveMembership` (группы) и прямым
`ParticipantRepository.findActive` (личные чаты); realtime — расширение sealed-типов
`chats/domain/port/RealtimeEventPublisher` событиями `conference.started|ended` (компилятор
заставит адаптер `RedisRealtimePublisher` маппить новые типы — существующий прецедент 006/007);
системное сообщение — через порты `chats` (§8); rate limiting — `RateLimitFilter` (§6);
JWT-инфраструктура `JwtService` (ротация ключей, ES256) — для гостевых nonce (§6). Исключение
участника из чата во время конференции (edge 006×009): `GroupService` публикует in-process
`ApplicationEvent MemberRemoved(chatId, userId)` (post-commit), слушатель `conferences`
отключает активные подключения (§5) — тот же процесс, отдельный сервис не нужен.

**Обоснование**: FR-017 предписывает модуль монолита; конвенция доменов даёт SRP/DIP (VIII)
без новых сервисов и сборок (II, VII): весь транспорт/секьюрити/observability достаётся готовым.
In-process событие вместо обращения к RealtimePubSub «наоборот» не создаёт耦合 chats→conferences:
группы остаются независимыми от конференций (событие — просто доменное событие группы).

**Альтернативы**: (а) отдельный микросервис `conference-service` — отклонено (FR-017): дублирование
аутентификации/obs/деплоя ради одного домена, нарушение «как chats/groups/presence»; (б) жить
без события MemberRemoved (поллинг членства) — отклонено: рассинхрон до минут, лишняя нагрузка,
FR-003-требование немедленного разрыва; (в) Webhook-цепочка LiveKit→бэкенд для этой цели —
не подходит: LiveKit ничего не знает о членстве в чате.

## §3. Интеграция с LiveKit: официальный Kotlin SDK поверх Twirp

**Решение**: зависимости — `io.livekit:livekit-server` (Maven Central; доставляет: минт
`AccessToken` HS256, RoomService-клиент поверх **Twirp over HTTP** `POST /twirp/livekit.RoomService/<Method>`,
`WebhookReceiver`). Домен зависит только от порта `LiveKitAdminClient`
(createRoom/deleteRoom/listRooms/listParticipants/removeParticipant/updateParticipant/muteTrack/sendData);
адаптер `livekit/LiveKitServerAdminClient` инкапсулирует SDK. Админ-аутентификация Twirp —
серверный JWT с grants `roomCreate/roomList/roomAdmin`, минтуется тем же SDK из
`LIVEKIT_API_KEY/SECRET` (env). Конфиг LiveKit включает `enable_remote_unmute: true`
(без него `MutePublishedTrack` запрещён — FR-008) и `webhook.api_key/urls` → внутренний endpoint
бэкенда. **Риск-фолбэк** (фиксируется в tasks): если артефакт SDK несовместим со стеком
(okhttp-конфликты и т.п.), адаптер переписывается на прямой Twirp-клиент (WebClient + JSON;
контракт тривиален, [contracts/livekit-integration.md](./contracts/livekit-integration.md)) —
порт домена не меняется.

**Обоснование**: Twirp = обычный HTTP POST JSON на порту 7880 — не тянет gRPC-стек в монолит;
серверные API «полностью распределены: любой инстанс обслуживает любой запрос» (документация
LiveKit) — горизонтальное масштабирование контрольной плоскости не требует sticky-роутинга.
Официальный SDK снимает ручную работу с подписями вебхуков (sha256-клейм) и grants-типизацией.
DIP-порт страхует от vendor-lock и делает домен тестируемым фейками (VI, VIII).

**Альтернативы**: (а) прямой gRPC-клиент по proto LiveKit — отклонено: gRPC/protobuf-стек ради
8 методов при наличии HTTP/Twirp (VII); (б) полностью ручной минт токенов на Nimbus — запасной
вариант (фолбэк), не основной: SDK уже типизирует `canPublishSources` и обновления протокола.

## §4. Токены подключений и identity: короткий TTL, per-connection identity, без PII

**Решение**:
- **Identity per-подключение**: член чата — `u:{userId}:{connId}`, гость — `g:{connId}` (`connId` =
  серверный UUID, минтуется при каждом `join`/`join-by-invite`). Это единственный идентификатор
  в LiveKit (соответствует Q7: «внутренний идентификатор подключения генерируется сервером»);
  имя для отображения передаётся отдельным клеймом `name` (username члена / введённое имя гостя).
- **Room name** — `c:{conferenceId}` (непрозрачный, без PII).
- **Grants члену**: `roomJoin`, `room`, `canSubscribe: true`, `canPublish: true`,
  `canPublishSources` = все источники минус ограниченные модерацией (§5), `canPublishData: false`,
  админ-grants (`roomAdmin|roomCreate|roomList`) — никогда.
  **Гостю**: то же, но c стартовым клиентским состоянием «только микрофон» (soft, Q1=C/Q3=A —
  жёсткий запрет источников не вводится; grants источников не урезаются, иначе гость не сможет
  включить камеру/экран в звонке без перевыпуска токена).
- **TTL токена** — `conferences.token-ttl` (дефолт v1 **15 мин**, per-env). LiveKit сам продлевает
  токены подключённым клиентам (token refresh), поэтому TTL не режет живые звонки.
- **Самоподписная ревокация не используется**: self-hosted LiveKit не аннулирует токен при
  `RemoveParticipant`/`UpdateParticipant` (ревокация — Cloud-only, документация LiveKit) —
  вместо этого: кик ≠ бан по спеке (переподключение разрешено намеренно), немедленный разрыв
  делает `RemoveParticipant`, а права при переподключении заново вычисляются из БД при минте.
- **Атрибуты участника** (participant metadata JSON `{"role":"moderator","guest":true}`) —
  broadcast-канал для UI-состояния (§5).

**Обоснование**: per-connection identity обязателен: LiveKit дедуплицирует подключения по
identity (второе подключение с тем же identity закрывает первое), а спека требует отдельные
слоты/отображения на вкладки/устройства (edge «один участник с нескольких вкладок», Q10).
Короткий TTL — прямой ответ документации LiveKit на отсутствие self-hosted ревокации; 15 мин
покрывает pre-join→подключение и переподключения после кратковременного обрыва (LiveKit
продлевает токен подключённым). `canPublishData: false` — v1 не имеет participant-to-participant
данных; серверные data-пакеты (предупреждение о лимите длительности) идут через admin `SendData`
и не требуют этого grant'а. PII-запрет в identity/room name — рекомендация безопасности LiveKit
(логи/трейсы платформы).

**Альтернативы**: (а) identity = `u:{userId}` (стабильный) — отклонено: вторая вкладка выкидывает
первую (нарушение edge-кейса слотов); (б) долгоживущие токены (часы) — отклонено: кикнутый/
исключённый из чата участник сохранял бы работающий токен; (в) жёсткие grants гостю без
video/screen — отклонено: Q1=C разрешает включение в звонке, а самопродления прав нет; soft-режим
единый с Q3=A; (г) выдача модераторам client-side `roomAdmin` — отклонено: вся модерация серверная
(FR-008, меньше поверхности атаки).

## §5. Модерация: серверная модель и персистентность ограничений (Q4/Q5/Q9/Q21/Q22)

**Решение**: источник истины прав — таблица `conference_participants` (строка на «принципала»:
член чата по `user_id`; гость — по `connId`-строке его подключения, см. §4 и
[data-model.md](./data-model.md)): `role ('moderator'|'participant')`, ограничения
`mic_restricted | camera_restricted | screen_restricted | speech_restricted` (bool, дефолт false).
Применение модерационного действия (API №49/№50/№51, только для `role='moderator'`; модераторы
равноправны, включая действия друг против друга и создателя, Q21=A):
1. В транзакции: проверка роли действующего, обновление строки целика (SET-семантика PUT),
   post-commit публикация события участникам (UI-обновление через SSE/metadata).
2. Немедленное применение в LiveKit: `UpdateParticipant(permission)` — `can_publish_sources`
   минус ограниченные источники, `can_publish=false` при `speech_restricted` (право слова глушит
   все публикации — Q4=A); уже опубликованные треки — `MutePublishedTrack` (мьют микрофона/камеры,
   отключение экрана — Q22=A). Обновление permissions LiveKit транслирует клиенту событием
   `ParticipantPermissionsChanged`, metadata — `ParticipantMetadataChanged` → ограниченный видит
   заблокированные тумблеры с пояснением (FR-007/FR-008).
3. **Персистентность (Q5=A)**: при следующем минте токена (переподключение) grants вычисляются
   из той же строки — ограничение переживает реконнект до конца конференции; роли так же
   персистентны (Q9=A), автопередачи модератора нет.
4. **Кик** (№51) = `RemoveParticipant` (разрыв текущего подключения) **без** записи ограничения:
   переподключение допустимо (спека: кик ≠ бан; радикальная мера для гостя — отзыв ссылки §6).
5. **Завершение** (№48, модератор) = `DeleteRoom` → каскад §7.
6. **Гость**: ограничения применяются к его текущему подключению немедленно (п. 2), но не
   переживают переподключение (новое подключение гостя = новая строка/identity, Q7) —
   зафиксировано как ограничение v1; инструменты модератора для гостя — кик и отзыв ссылки.
7. Исключение из чата (§2) и отзыв гостевой ссылки (§6) отключают подключения тем же
   `RemoveParticipant`.

**Обоснование**: единая точка правды в БД даёт персистентность Q5/Q9 декларативно (grants при
минте), а LiveKit-вызовы — немедленность SC-005 (серверные API без участия клиента). Модель
«PUT полных ограничений» проще PATCH-дельт и идемпотентна. Клиенту не нужны опросы: SSE +
LiveKit broadcast-события закрывают UI (заблокированные тумблеры с пояснением, пометка «гость»,
роль) — данные едут в participant metadata, обновляемом сервером при каждом действии.

**Альтернативы**: (а) enforcement только на клиенте — отклонено: FR-008 требует серверного
применения; (б) хранить ограничения в Redis с TTL — отклонено: «до конца конференции» — это
жизнь строки конференции (БД, восстановимость, аудит-возможность); (в) отдельная таблица
`participant_restrictions(source, restricted)` — отклонено: 4 фиксированных источника v1,
колонки проще (VIII/YAGNI), расширение — миграцией; (г) «владелец» конференции с иммунитетом —
отклонено: Q21=A (все модераторы равноправны).

## §6. Гостевой доступ: подписанный nonce + строка-статус, одна ссылка, rate limiting (FR-009–FR-012)

**Решение**:
- **Nonce** — JWT, минтуемый `JwtService`-инфраструктурой (ES256, ротация ключей `kid` уже в
  проекте): claims `sub = inviteId (uuid)`, `cid = conferenceId`, `typ = "guest-invite"`,
  `iat/exp` (= TTL ссылки, дефолт 24 ч, per-env `conferences.guest-invite-ttl`). Ссылка:
  `{FRONTEND}/join/{nonce}`. Валидация: подпись/срок → статус строки `conference_guest_invites`
  (`status='active'`) → статус конференции (`<> 'ended'`).
- **Одна активная ссылка** (Q17=A): частичный уникальный индекс `WHERE status='active'`;
  генерация новой в той же транзакции помечает старую `revoked`; далее post-commit: всех гостей
  с `metadata.inviteId = <старый>` (из `ListParticipants`) — `RemoveParticipant` (немедленное
  отключение вошедших, Q13=B), новые входы блокируются статусом.
- **Явный отзыв** (№53) — тот же путь. Гостевая ссылка никогда не постится в чат (спека).
- **Публичные endpoints** (единственное исключение «все API аутентифицированы», FR-009):
  `GET /api/v1/conferences/invite-info?inviteToken=` (валидность до ввода имени; минимум
  раскрытия: статус ссылки, live-флаг, без деталей чата) и `POST /api/v1/conferences/join-by-invite`
  `{inviteToken, name}` (валидация имени: trim, 1–50 символов, Q19=A/Q7=A → минт гостевого
  LiveKit-токена `g:{connId}` + `name`). Оба — `permitAll` в SecurityConfig + строки per-IP
  бакетов в `RateLimitFilter` (паттерн 002; флуд → 429 problem+json, SC-004). Подделанный/
  просроченный nonce — единый отказ без различения причин (edge «без раскрытия причин подделки»).
- **Роль точкой входа** (Q14=A): вошедший по ссылке — гость независимо от аккаунта; токен гостя
  не несёт membership, доступ к чату отсутствует (у гостя нет auth-токена вообще).
- **Квота** — общая `maxParticipants` (Q23=A): pre-check `ListParticipants` при минте (дружественная
  ошибка) + жёсткий кап `max_participants` комнаты (§7).

**Обоснование**: stateless-подпись даёт проверку подлинности без обращения к БД по секрету,
а строка-статус — мгновенный отзыв (stateless-токен нельзя «забрать назад» без списка отзыва);
их комбинация — минимум состояния (одна строка на ссылку, не на вход). Индекс `WHERE active`
гарантирует инвариант «одна ссылка» хранилищем (прецедент — owner-индекс 006). Per-IP бакеты —
отработанный паттерн 002 (login/SSO-GET уже так защищены); «мягкая» DDoS-устойчивость Redis
(fail-open) унаследована.

**Альтернативы**: (а) random-opaque nonce в БД (без JWT) — отклонено: ссылка валидируется на
каждом шаге join-страницы — лишние запросы, а подпись даёт offline-проверку формата до тяжёлого
пути; (б) single-use ссылки — отклонено спекой (Q2=A: многоразовые в пределах TTL); (в) HMAC
общим секретом вместо ES256-ключей — приемлемо, но переиспользование готовой инфраструктуры
ротации ключей `JwtService` дешевле и уже протестировано; (г) отдельный лимит гостей — отклонено
(Q23=A); (д) «зал ожидания» (`canSubscribe:false` до апрува) — out of scope спеки (кандидат
на будущее, зафиксирован в Assumptions).

## §7. Жизненный цикл: комнаты, вебхуки, события SSE, компенсации (FR-013/FR-014/FR-015, SC-006/SC-007)

**Решение**: состояние конференции — `conferences.status ('created'|'active'|'ended')` +
`end_reason`. Поток:
- **Создание** (№44, идемпотентно: частичный уникальный индекс «одна не-`ended` на чат»; повторное
  нажатие возвращает существующую — edge спеки): перед транзакцией — лёгкий pre-flight
  доступности медиа-плоскости (`ListRooms`-probe): отказ → `503 conference_media_unavailable`
  без записи в БД (edge «звонок временно недоступен», api-contract №44); гонка «probe успешен,
  комната не создалась» остаётся за sweeper'ом (self-heal, ниже). Транзакция [INSERT строки
  `created` +
  `CreateRoom` вызов **до** коммита не выполняется — см. ниже] + INSERT системного сообщения (§8);
  post-commit — `LiveKitAdminClient.createRoom(c:{id}, empty_timeout = empty-room-timeout (60 c),
  departure_timeout = 60 c, max_participants = maxParticipants)`. Порядок «сначала коммит, потом
  комната» + sweeper-самоисцеление (ниже) не оставляет сирот: если процесс упал между коммитом и
  createRoom, комнату создаёт `RoomConfiguration`-путь — упрощение v1: sweeper при первой же
  проверке `created`-конференции без комнаты досоздаёт её.
- **Начало**: первый вебхук `participant_joined` для конференции в статусе `created` → переход
  `created→active` (conditional UPDATE, идемпотентно) → post-commit SSE `conference.started`
  активным членам чата (снимок членства в транзакции события). `room_started` триггером начала
  НЕ является: комната создаётся eagerly при №44 (post-commit `createRoom`), и вебхук приходит
  в момент создания комнаты — до входа первого участника; обрабатывается как no-op (200).
- **Завершение** (все пути → единый идемпотентный `finalize`): (а) вебхук `room_finished`
  (последний вышел + `departure_timeout`, либо `DeleteRoom`); для `active` без предзаписи —
  `last_left`, для `created` («никто не вошёл», empty-timeout) — `room_lost`; (б) модератор №48:
  предзапись `end_reason='moderator'` → `DeleteRoom` → далее (а) с предзаписанной причиной; (в) **максимум длительности** (Q16=B, дефолт 4 ч): `ConferenceLifecycleScheduler`
  (fixed-delay, как scheduler-прецедент groups) — за `warning-before-end` (5 мин) до лимита
  отправляет предупреждение: SSE участникам-членам + admin `SendData(topic="conference.warning")`
  в комнату (достаёт гостей, у которых нет SSE); по истечении — `DeleteRoom`; (г) **sweeper**
  (SC-007, компенсация потери вебхука): периодическая проверка `active`-конференций — комната
  отсутствует в `ListRooms` дольше grace-окна → finalize c `end_reason='room_lost'`
  (конфиг `conferences.sweep-interval`/`conferences.room-lost-grace`, дефолты 60 с / 120 с); заодно
  завершает `created`-конференции, чья комната исчезла по `empty_timeout` («никто не вошёл»,
  edge спеки) с `end_reason='room_lost'`.
- **Finalize** (conditional UPDATE `status<>'ended'` под блокировкой — защита от дублей вебхуков
  и гонок с API): `status='ended'`, `ended_at`, `end_reason`; **удаление** строк
  `conference_participants` и `conference_guest_invites` (Q18=A: состав после завершения не
  сохраняется и не показывается — приватность, принцип V); post-commit SSE `conference.ended`
  членам чата. История чата не дополняется (Q20=A) — след завершения только realtime + ответ
  «конференция завершена» по старой ссылке/`join`.
- **Вебхук-приёмник**: `POST /api/internal/conferences/livekit/webhook` — `permitAll` +
  `WebhookReceiver.receive(rawBody, Authorization)` (подпись API-ключом LiveKit, sha256-клейм
  тела; Content-Type `application/webhook+json` — приём как `byte[]`) + per-IP бакет; обработка
  идемпотентна по `event.id` + условным переходам статуса; ретраи LiveKit безопасны.
  Внутренний контракт (события/поля) — [contracts/livekit-integration.md](./contracts/livekit-integration.md);
  в публичный OpenAPI не входит.
- **Недоступность медиа-плоскости в середине звонка** (Q15=B): клиентская сторона — события
  `Reconnecting/Reconnected/Disconnected` livekit-client с индикацией и ограниченным
  переподключением; серверная — sweeper (г): пропала комната и не вернулась за grace →
  контролируемое завершение с понятным сообщением (SSE + `SendData` перед завершением, если
  комната ещё жива). При создании звонка (недоступен LiveKit): `join`/№44 отвечает
  `503 conference_media_unavailable` — чат и остальные API не затронуты (edge).

**Обоснование**: LiveKit сам — источник истины о комнате (кто подключён, пустая ли она):
webhooks дают событийность (SC-006 ≤ 2 c), sweeper — гарантию освобождения ресурсов (SC-007
«100 % … не позднее таймаута») без trust в доставку вебхуков («no guarantees around delivery» —
документация LiveKit). `departure_timeout=60 c` — прямое отображение «таймаут пустой комнаты 60 с»
(Q19; `empty_timeout` того же размера покрывает «никто не вошёл»). Удаление participant-строк на
finalize — самое простое соответствие Q18 (нет данных → нечего утечь/показать).

**Альтернативы**: (а) поллинг комнат вместо вебхуков — отклонено: лаг ≥ sweep-интервала для всех
событий, нагрузка ListRooms; вебхуки+sweeper дают и скорость, и гарантию; (б) хранить участников
завершённых конференций с флагом скрытия — отклонено: ненужное персональное состояние (V, YAGNI);
(в) таймеры max-duration в Redis — отклонено: scheduler+БД уже есть, персистентность для
obs/аудита; (г) `empty_timeout`=300 (дефолт LiveKit) — отклонено: Q19 фиксирует 60 с.

## §8. Системное сообщение-приглашение: первый виджет в `messages` (FR-002, FR-013)

**Решение**: миграция V18 эволюционирует `messages`: `sender_id` → **nullable**, новые
`kind varchar(16) NOT NULL DEFAULT 'user'` и `payload jsonb NULL`; CHECK-инвариант:
`kind='user'` ⟺ `sender_id NOT NULL` и `payload IS NULL`; для `kind='system'` — `sender_id IS NULL`,
`payload` обязателен. Первый системный виджет — `kind='system'`, `payload = {"type":"conference_invite",
"conferenceId":…, "state":"created|active"}`; id сообщения — серверный UUID; INSERT — в транзакции
создания конференции с существующим выделением `seq` и post-commit фанаутом `message.created`
(расширенным `kind`/`payload`/nullable `senderId` — additive в MessageView/OpenAPI). Frontend
рендерит карточку-виджет с кнопкой «Подключиться» (маршрут pre-join) по `payload.type`;
неизвестные `payload.type` — нейтральная плейсхолдер-карточка (OCP: новые виджеты без правки ядра).
Завершение конференции **не** трогает историю (Q20=A): тип сообщения остаётся `conference_invite`,
актуальность проверяется при клике (GET №45).

**Обоснование**: сообщение-приглашение обязано жить в истории (FR-002: «на общих основаниях
системных сообщений»), значит нужен тип сообщения, а не хак текста. Nullable-эволюция повторяет
прецедент 006 (`peer` nullable у group-элементов) — additive, без BREAKING. jsonb-`payload` —
расширяемая система виджетов (конституция IV), серверный UUID-id сохраняет дедуп-семантику
существующего пути.

**Альтернативы**: (а) отдельная таблица `chat_system_messages` с union при чтении — отклонено:
два источника истории ломают пагинацию/sync/курсоры 004/005; (б) текстовое сообщение от
системного «бота»-пользователя — отклонено: суррогат-аккаунт, парсинг ссылки из текста, нет
структуры для кнопки/статуса; (в) `payload` varchar с JSON-строкой — отклонено: jsonb даёт
валидацию и индексацию даром.

## §9. Квоты и конфигурация per-env (FR-015, Q19/Q23)

**Решение**: `ConferencesProperties` (`@ConfigurationProperties("conferences")`, конвенция
`GroupsProperties` и др.): `max-participants` (dev-стенд **≤ 16** — SC-008), `token-ttl` (15 мин),
`guest-invite-ttl` (24 ч), `guest-name-max-length` (50), `empty-room-timeout` (60),
`max-duration` (4 ч), `warning-before-end` (5 мин), `sweep-interval` (60 с),
`room-lost-grace` (120 с — порог `room_lost`, ≥ 2× empty-timeout). LiveKit-подключение:
`livekit.url | api-key | api-secret` (env, секреты не в коде — принцип V). Применение квот —
в двух точках (FR-015): создание комнаты (`max_participants` комнаты = `max-participants`) и
каждый минт токена (pre-check `ListParticipants` → дружественный `409 conference_full`).
Квота считается по одновременным подключениям (вкладка/устройство = слот, edge спеки) — это
семантика `Room.num_participants` LiveKit без собственной бухгалтерии.

**Обоснование**: per-env override через `${ENV:default}` в `application.yml` + K8s-оверлеи —
существующий механизм 001–008 (SPRING_APPLICATION_JSON для стресс-проверок — прецедент 006).
Двойное применение (pre-check + жёсткий кап комнаты) закрывает гонку N+1 подключений: кто-то
получит понятную 409, кто-то — отказ LiveKit по капу (клиент показывает ту же ошибку).

**Альтернативы**: счётчик подключений в БД (инкремент на join) — отклонено: рассинхрон с
реальностью медиа-плоскости (обрывы, дубли), лишнее состояние; LiveKit уже считает участников.

## §10. Фронтенд: модуль `conferences/`, pre-join, звонок, гостевая страница

**Решение**: новый модуль `frontend/src/conferences/` (по образцу `groups/`: api/hooks/components/
pages/validation), **лениво загружаемый** (`React.lazy` + динамический импорт `livekit-client`/
`@livekit/components-react` в отдельный чанк — тяжёлая зависимость не раздувает основной бандл).
- **Точки входа**: кнопки «видеозвонок»/«аудиозвонок» в `ChatHeader.tsx` (создание №44 с
  `startDefaults: 'video'|'audio'`; при активной конференции возвращается существующая — edge);
  кнопка «Подключиться» в системной карточке; прямой URL `/conference/:id` (protected) и
  `/join/:inviteToken` (публичный, без auth).
- **Pre-join** (FR-005–FR-007) — единый `PreJoinScreen` для всех точек входа на `livekit-client`
  (`createLocalTracks` для превью, `switchActiveDevice` для выбора устройств): тумблеры
  «микрофон»/«камера» с дефолтами по точке входа (video → оба on; audio/инвайт/гость → только
  микрофон, тумблер камеры доступен — soft, Q3=A), оба тумблера можно выключить («только слушаю»,
  FR-006), блокировка тумблеров по ограничениям (данные из №46 `GET …/me` — роль + ограничения
  до подключения; у гостя ограничений на входе нет), предупреждение при отказе браузера в
  доступе (вход разрешён, Q6=A), «Войти» → `POST join` → `room.connect` с выбранными треками.
  Pre-join не подключается к комнате до «Войти» (слот не занимается — семантика
  `participant_joined` = media connection), закрытие — без следов.
- **Экран конференции** `ConferenceRoom`: `@livekit/components-react` примитивы (`useTracks`,
  `VideoTrack`, `RoomAudioRenderer`), сетка участников (роль/«гость»/ограничения — из participant
  metadata, §5), `MediaControls` (мьют/камера/экран — заблокированы с пояснением при ограничении,
  публикация only-audio у гостя — стартовое состояние), переключение устройств **на лету**
  (`switchActiveDevice`, Q11=B), индикация реконнекта (Q15=B: `Reconnecting/Disconnected` +
  ограниченные ретраи), `ModerationPanel` (для `role='moderator'`: mute/право слова/роль/киик/
  завершение/гостевая ссылка — №48–№53), предупреждение о лимите длительности (SSE + data-топик).
- **Гостевая join-страница** `/join/:inviteToken`: `invite-info` → форма имени → тот же
  `PreJoinScreen` (дефолт «только микрофон») → звонок; без auth, без доступа к чату (API-клиент
  гостя знает только join-by-invite).
- **SSE-индикатор**: расширение демультиплексора `useRealtime` событиями
  `conference.started|ended` (по `chatId`) → бейдж «звонок идёт» в списке чатов и в заголовке
  (вход voluntary, Q12=A — рингтона/accept-decline нет).
- **Одна конференция на вкладку** (Q10=A): при `join` второй конференции hook корректно
  `room.disconnect()` текущей (слот освобождается, остальные видят выход) до подключения к новой.
- Доступность — стандарты 008: `aria-pressed` тумблеров, `focus-visible`, dialog-семантика
  `ModalShell` для модерации, клавиатурное управление, reduced-motion.

**Обоснование**: переиспользование компонентов LiveKit (VideoTrack/RoomAudioRenderer/
ConnectionQualityIndicator) вместо ручного WebRTC-рендера — конституция VII; кастомный
`PreJoinScreen` (вместо готового `<PreJoin/>`) — требования FR-005 различают дефолты по точке
входа, блокировку по ограничениям и a11y-стандарты 008. Ленивый чанк — изоляция ~200 КБ
медиа-кода от остальных пользователей чата. Хуки вместо стора — конвенция фронтенда 001–008.

**Альтернативы**: (а) готовый `<PreJoin/>`/`VideoConference` целиком — отклонено: нет различия
дефолтов/ограничений/a11y-токенов 008, тяжёлый cowboy-стиль; (б) отдельное SPA для гостя —
отклонено: второй билд/деплой ради одной формы (YAGNI); маршрут в том же SPA дешевле; (в)
polling статуса конференции — отклонено: SSE уже мультиплексирует всё.

## §11. Развёртывание LiveKit: local compose + K8s

**Решение**:
- **Local dev**: `deploy/local/docker-compose.yml` + сервис `livekit` (образ
  `livekit/livekit-server`, флаг `--dev` для одного инстанса без Redis), порт 7880 (+7881/tcp,
  7882/udp), `LIVEKIT_API_KEY/SECRET`, `webhook.urls: http://backend:8080/api/internal/conferences/livekit/webhook`,
  `enable_remote_unmute: true`. Бэкенд-профиль уже подхватывает `LIVEKIT_URL`.
- **K8s**: `deploy/k8s/base/livekit.yaml` (+overlay dev): Deployment с **hostNetwork: true**
  (требование LiveKit: RTC-порты на ноде; один pod LiveKit на ноду — документация), Service/
  Ingress на 7880 (TLS терминируется ingress — конвенция кластера), NodePort/hostPort для
  7881 (ICE/TCP) и UDP range `rtc.port_range_start/end` (или mux `udp_port` 7882 — один UDP-порт
  проще для dev-стенда), встроенный TURN/TLS 5349 для сетей с блокировкой WebRTC (prod;
  сертификат — Secret по прецеденту кластерных issuer'ов), distributed mode → Redis (prod —
  отдельный logical DB/инстанс; ключи LiveKit префиксированы `lk:`), `terminationGracePeriodSeconds`
  ~5ч для drain активных звонков (рекомендация helm-чарта LiveKit; свой манифест вместо helm —
  кластер на голом kustomize, конвенция deploy/). Клиенты получают `livekit.url` (wss) из
  конфигурации фронта.
- **Порядок**: миграции/контракт/бэкенд/фронт — конвейер 001; LiveKit-Deployment не зависит от
  релизов бэкенда (версия фиксируется overlay'ом).

**Обоснование**: helm-чарт LiveKit рассчитан на GKE/EKS/DOKS + ALB/nginx-контроллеры; наш кластер —
kustomize (deploy/k8s/base+overlays), поэтому минимальный собственный манифест по образцу
документированной topology (hostNetwork + ingress 7880 + UDP range) дешевле внесения helm в CD.
Mux UDP (7882) на dev-стенде упрощает firewall; полный range для prod-масштаба. Graceful drain
— требование качества апгрейдов медиа-нод.

**Альтернативы**: (а) helm-чарт как есть — отклонено: ломает единообразие kustomize-конвейера;
(б) без TURN — отклонено: корпоративные NAT/VPN не пройдут ICE/TCP/UDP → часть пользователей
без звука (документация LiveKit относит TURN/TLS к обязательным для продакшена); (в) shared
Redis-инстанс без разделения — допустимо на dev; prod-изоляция logical DB, т.к. LiveKit пишет
служебные ключи в общий keyspace.

## §12. Нагрузочный расчёт (принцип II, SC-008)

**Решение** (расчёт фиксируется, валидация — прогонами):
- **Контрольная плоскость** (бюджет 1000 конференций): созданий/мин — единицы; минтов токенов —
  ~1000 конф × ~8 входов/реконнектов за средний звонок ~1 ч ≈ 2–3 rps пиково (плюс `ListParticipants`
  на каждый минт — дешёвый Twirp-вызов); SSE-фанаут — 2 события жизненного цикла × ~10 членов чата
  × 1000 конф/час ≈ 6 публикаций/с — доли процента бюджета 100 000 msg/s; публичный гостевой
  endpoint — флуд-лимит 002 держит нагрузку (SC-004). Вывод: контрольная плоскость не является
  bottleneck; масштабирование — горизонтальное (stateless, II).
- **Медиа-плоскость**: 1000 конференций × ~6 подключений ≈ 6000 участников; при ~50 %
  публикующих видео/аудио ≈ 3000 publisher-потоков + подписки ~6000×4 ≈ 24 000 streams; входе
  simulcast (3 слоя) расчётный поток-эквивалент ~36–40k. Ориентир производительности LiveKit —
  порядка сотен потоков на ядро (benchmark-страница LiveKit: тысячи потоков на инстанс);
  при ноде 8 vCPU ≈ 3–4k streams → **~10–14 медиа-нод** на полный бюджет (с N+1). Точная цифра
  зависит от битрейта/слоёв — фиксируется прогоном: (а) dev-стенд: maxParticipants ≤ 16, smoke
  `load/k6/conferences.smoke.js` (контрольная плоскость: создание/join/гость/rate-limit), (б)
  медиа-прогон Load-test-инструментом LiveKit (`lk load-test`, ранее `livekit/lt`) на dev-стенде с
  экстраполяцией по формуле выше; полноразмерные прогоны — платформенная фича 016 (прецедент 004/005).

**Обоснование**: конституция II требует расчёта или отклонения; расчёт показывает: budgets
контрольной плоскости — мизерные относительно существующих, медиа — линейно масштабируется
нодами LiveKit (архитектура выбрана именно поэтому), единственная реальная проверяемая точка —
ёмкость медиа-ноды, закрываемая нагрузочным прогоном dev-стенда.

**Альтернативы**: (а) полный нагрузочный кластер в этой фиче — отклонено: вне dev-стенда
нет среды; фича 016 — платформенная; (б) без расчёта — отклонено (II NON-NEGOTIABLE).

## §13. Наблюдаемость и тестирование (FR-018, принцип VI)

**Решение**:
- **Метрики** `ConferenceMetrics` (`webchat_conference_*`, конвенция `GroupMetrics`): gauges
  `conferences_active`, `participants_connected` (снимок sweeper'ом); counters `conferences_started_total{end_reason}`,
  `conferences_ended_total{end_reason}`, `joins_total{kind=member|guest}`, `joins_denied_total{reason=membership|quota|invite|rate_limit}`,
  `tokens_minted_total{kind}`, `moderation_actions_total{action}`, `webhooks_processed_total{event,result}`,
  `livekit_api_seconds{method}` (Timer). Теги — только операции/причины, без userId (приватность,
  конвенция). SC-001–SC-009 проверяются по этим метрикам + `http.server.requests`.
- **Тесты**: unit (сервисы на фейках портов: grants-вычисление из ограничений, identity-парсинг,
  nonce-подпись/отзыв, идемпотентный finalize); IT `AbstractIntegrationTest` + GenericContainer
  `livekit/livekit-server --dev` (профиль test): membership-отказ 403 (SC-002), guest-флоу
  (валидный/истёкший/отозванный nonce — SC-003), квота (заниженный max-participants), rate-limit
  429 публичного endpoint (SC-004), вебхуки (первый `participant_joined` → started,
  `room_finished` → finalize + SSE-события, дубли вебхуков), системное сообщение в истории, исключение из чата → разрыв; фронтенд — Vitest
  (pre-join дефолты/блокировки, join-страница, виджет-карточка, SSE-индикатор) + Playwright e2e
  (звонок двух вкладок против локального LiveKit: вход/мьют/экран/выход-завершение, SC-001
  вручную); нагрузка — §12. E2EE-чаты (010) — негативный IT: создание конференции из E2EE-чата
  невозможно (граница v1).

**Обоснование**: повторяет проверенные конвейеры 001–008 (Testcontainers-стек уже поднимает
PG+Redis; LiveKit-контейнер в `--dev` — single binary без конфига, добавляется одной строкой
контейнера); метрики напрямую мапятся на SC-критерии приёмки.

**Альтернативы**: мок LiveKit вместо контейнера в IT — отклонено: проверяется интеграция
(подпись вебхука, grants, таймауты комнаты) — это и есть предмет контракта
[livekit-integration.md](./contracts/livekit-integration.md).

---

## Сводка разрешённых неизвестных Technical Context

| Неизвестное | Решение | Пункт |
|---|---|---|
| Медиа-плоскость | self-hosted LiveKit, distributed mode | §1 |
| Контрольная плоскость | модуль `conferences/` монолита, порты/адаптеры | §2 |
| Интеграция (SDK/транспорт) | `io.livekit:livekit-server` (Twirp), порт+фолбэк | §3 |
| Токены/identity | HS256 LiveKit JWT, TTL 15 мин, `u:{userId}:{connId}`/`g:{connId}` | §4 |
| Модерация/персистентность | БД-строка принципала + UpdateParticipant/MutePublishedTrack | §5 |
| Гостевой nonce/отзыв | ES256 JWT (JwtService) + строка-статус; одна ссылка | §6 |
| Жизненный цикл/SC-007 | вебхуки + scheduler/sweeper + идемпотентный finalize | §7 |
| Системное сообщение | `messages.kind/payload` (V18), виджет conference_invite | §8 |
| Квоты per-env | `conferences.*` Properties; комната-кап + pre-check | §9 |
| Фронтенд | модуль `conferences/`, ленивый чанк, кастомный pre-join | §10 |
| Развёртывание | compose `--dev` + kustomize-манифест (hostNetwork, TURN) | §11 |
| Нагрузка (II) | расчёт: контрольная — доли бюджетов; медиа — формула нод + прогон | §12 |
| Наблюдаемость/тесты | `webchat_conference_*`; IT с LiveKit-контейнером, Playwright, k6 | §13 |
