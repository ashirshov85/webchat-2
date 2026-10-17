/**
 * Типизированный API-клиент тега `conferences` (009, №44–№51): создание
 * конференции из чата, статус, собственные права, JoinTicket и серверная
 * модерация (ограничения/роль/кик/завершение). Типы — из
 * генерации `pnpm generate:api` (`contracts/openapi.yaml` — единственный
 * источник истины); ошибки — RFC 9457 Problem: каждый вызов бросает
 * типизированный ApiProblem, коды контракта едут в `errors: {field:
 * [code]}` (конвенция 002) — ветвление UX по коду, а не по HTTP-статусу.
 */
import { toApiProblem } from '../../api/auth'
import type { ApiProblem } from '../../api/auth'
import { apiFetch } from '../../api/client'
import type { components } from '../../api/schema'

export type CreateConferenceRequest = components['schemas']['CreateConferenceRequest']

/** №44 `mode`: стартовые дефолты pre-join (video — камера+микрофон, audio — только микрофон). */
export type ConferenceMode = CreateConferenceRequest['mode']

export type ConferenceView = components['schemas']['ConferenceView']

/** Жизненный цикл: created → active → ended (переходы только вперёд). */
export type ConferenceStatus = ConferenceView['status']

export type ConferenceEndReason = NonNullable<ConferenceView['endReason']>

/** №46 MeView: роль и 4 ограничения принципала — блокировка тумблеров (FR-007). */
export type MeView = components['schemas']['MeView']

/** №47/№55 JoinTicket: короткоживущий LiveKit-токен + wsUrl + identity. */
export type JoinTicket = components['schemas']['JoinTicket']

/** №49/№50 ParticipantView: применённое состояние участника (роль + 4 ограничения + online). */
export type ParticipantView = components['schemas']['ParticipantView']

/** №49 тело запроса: ПОЛНОЕ SET-состояние четырёх ограничений (сброс — false). */
export type UpdateParticipantRestrictionsRequest =
  components['schemas']['UpdateParticipantRestrictionsRequest']

/** №50 тело запроса: назначение/снятие модератора (равноправие — Q21=A). */
export type UpdateParticipantRoleRequest = components['schemas']['UpdateParticipantRoleRequest']

/**
 * Коды ошибок контракта №44–№47: «конференция завершена» (`conference_ended`
 * — ответ по старой ссылке), «нет доступа» (`conference_forbidden` — не-член
 * чата), «заполнена» (`conference_full` — общая квота с гостями, Q23=A),
 * E2EE-граница v1 и недоступность медиа-плоскости.
 */
export type ConferenceErrorCode =
  | 'invalid_uuid'
  | 'invalid_mode'
  | 'malformed_request'
  | 'chat_not_found'
  | 'conference_forbidden'
  | 'conference_not_found'
  | 'conference_e2ee_unsupported'
  | 'conference_ended'
  | 'conference_full'
  | 'conference_media_unavailable'

/**
 * Коды ошибок контракта модерации №48–№51: правовой гейт `moderator_required`
 * (403, включая снятие роли самому себе в полёте), цель ушла из конференции
 * (`participant_not_found` — строка LiveKit уже отсутствует), терминальное
 * `conference_ended` и единый не-раскрывающий `conference_not_found`.
 */
export type ModerationErrorCode =
  | 'invalid_uuid'
  | 'malformed_request'
  | 'conference_not_found'
  | 'conference_ended'
  | 'moderator_required'
  | 'participant_not_found'

async function authedRequest(path: string, method: string, body?: unknown): Promise<Response> {
  const response = await apiFetch(path, {
    method,
    headers: {
      ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}),
      Accept: 'application/json, application/problem+json',
    },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return response
}

/** Type guard брошенного значения: Problem из этого клиента (или transport-ошибка). */
export function isConferenceProblem(value: unknown): value is ApiProblem {
  return (
    typeof value === 'object' &&
    value !== null &&
    typeof (value as Partial<ApiProblem>).status === 'number'
  )
}

/**
 * Единственный код ошибки из Problem (`errors: {field: [code]}`); null —
 * если брошено не Problem (сеть/транспорт). Ожидается только код из
 * ConferenceErrorCode, но значение не сужается на runtime-валидации.
 */
export function conferenceErrorCode(value: unknown): ConferenceErrorCode | null {
  if (!isConferenceProblem(value)) return null
  const code = Object.values(value.errors ?? {}).flat()[0]
  return (code as ConferenceErrorCode | undefined) ?? null
}

/**
 * Единственный код ошибки из Problem модерации №48–№51
 * (`errors: {field: [code]}`); null — не Problem (сеть/транспорт).
 */
export function moderationErrorCode(value: unknown): ModerationErrorCode | null {
  if (!isConferenceProblem(value)) return null
  const code = Object.values(value.errors ?? {}).flat()[0]
  return (code as ModerationErrorCode | undefined) ?? null
}

/**
 * №44 `POST /chats/{chatId}/conferences` (FR-001/FR-002): старт конференции
 * из чата — `201` новой или `200` уже активной (идемпотентность «одна
 * активная на чат», сообщение-приглашение не дублируется); обе ветки
 * возвращают ConferenceView — переход по view.id без различения. mode задаёт
 * стартовые дефолты pre-join. Ошибки: `chat_not_found`,
 * `conference_forbidden` (не-член), `conference_e2ee_unsupported` (граница
 * v1), `conference_media_unavailable` (ничего не создаётся).
 */
export async function createConference(
  chatId: string,
  mode: ConferenceMode,
): Promise<ConferenceView> {
  const body: CreateConferenceRequest = { mode }
  const response = await authedRequest(
    `/chats/${encodeURIComponent(chatId)}/conferences`,
    'POST',
    body,
  )
  return (await response.json()) as ConferenceView
}

/**
 * №45 `GET /conferences/{conferenceId}`: ConferenceView, включая завершённые
 * (status='ended' — клик «Подключиться» по старой ссылке показывает
 * «конференция завершена»); REST-фолбэк индикатора «звонок идёт» (источник
 * истины — status). Не-члену и для несуществующей — единый
 * `conference_not_found` (существование не раскрывается).
 */
export async function getConference(conferenceId: string): Promise<ConferenceView> {
  const response = await authedRequest(`/conferences/${encodeURIComponent(conferenceId)}`, 'GET')
  return (await response.json()) as ConferenceView
}

/**
 * №46 `GET /conferences/{conferenceId}/me`: MeView — роль и 4 ограничения
 * принципала для блокировки тумблеров pre-join/звонка (FR-007);
 * ограничения персистентны до конца конференции (Q5/Q22=A). Ошибка:
 * `conference_not_found`.
 */
export async function getConferenceMe(conferenceId: string): Promise<MeView> {
  const response = await authedRequest(`/conferences/${encodeURIComponent(conferenceId)}/me`, 'GET')
  return (await response.json()) as MeView
}

/**
 * №47 `POST /conferences/{conferenceId}/join`: короткоживущий JoinTicket
 * (LiveKit JWT + wsUrl + identity u:{userId}:{connId} — connId свежий на
 * каждый минт, истёкший токен обновляется повторным вызовом в окне
 * реконнекта). Membership-check на каждой выдаче (FR-003). Ошибки:
 * `conference_forbidden`, `conference_not_found`, `conference_ended`,
 * `conference_full`, `conference_media_unavailable`.
 */
export async function joinConference(conferenceId: string): Promise<JoinTicket> {
  const response = await authedRequest(
    `/conferences/${encodeURIComponent(conferenceId)}/join`,
    'POST',
  )
  return (await response.json()) as JoinTicket
}

/**
 * №48 `POST /conferences/{conferenceId}/end`: завершение модератором —
 * медиа-комната удаляется (все отключаются немедленно), SSE
 * `conference.ended` членам чата (финализация — вебхук US5/T055).
 * Модераторы равноправны (Q21=A). Ошибки: `moderator_required`,
 * `conference_not_found`, `conference_ended` (повтор по завершённой).
 */
export async function endConference(conferenceId: string): Promise<void> {
  await authedRequest(`/conferences/${encodeURIComponent(conferenceId)}/end`, 'POST')
}

/**
 * №49 `PUT /conferences/{conferenceId}/participants/{identity}/restrictions`:
 * полное SET-состояние четырёх ограничений (идемпотентно, передаются все
 * поля — текущее состояние панели выводит из grants LiveKit); применяется
 * немедленно ≤ 2 c (SC-005: UpdateParticipant + MutePublishedTrack) и
 * персистентно до конца конференции (Q5/Q22=A). Ошибки:
 * `moderator_required`, `conference_not_found`, `participant_not_found`.
 */
export async function updateParticipantRestrictions(
  conferenceId: string,
  identity: string,
  restrictions: UpdateParticipantRestrictionsRequest,
): Promise<ParticipantView> {
  const response = await authedRequest(
    `/conferences/${encodeURIComponent(conferenceId)}/participants/${encodeURIComponent(identity)}/restrictions`,
    'PUT',
    restrictions,
  )
  return (await response.json()) as ParticipantView
}

/**
 * №50 `PUT /conferences/{conferenceId}/participants/{identity}/role`:
 * назначение/снятие модератора — роль персистентна, включая
 * переподключение (Q9=A), применяется немедленно broadcast'ом metadata
 * (SC-005). Действия применимы к любому участнику (Q21=A). Ошибки:
 * `moderator_required`, `conference_not_found`, `participant_not_found`.
 */
export async function updateParticipantRole(
  conferenceId: string,
  identity: string,
  role: UpdateParticipantRoleRequest['role'],
): Promise<ParticipantView> {
  const response = await authedRequest(
    `/conferences/${encodeURIComponent(conferenceId)}/participants/${encodeURIComponent(identity)}/role`,
    'PUT',
    { role },
  )
  return (await response.json()) as ParticipantView
}

/**
 * №51 `DELETE /conferences/{conferenceId}/participants/{identity}`: кик —
 * немедленный разрыв подключения (≤ 2 c, SC-005); кик ≠ бан —
 * переподключение по действующей ссылке допустимо (edge spec.md).
 * Ошибки: `moderator_required`, `conference_not_found`,
 * `participant_not_found`.
 */
export async function kickParticipant(conferenceId: string, identity: string): Promise<void> {
  await authedRequest(
    `/conferences/${encodeURIComponent(conferenceId)}/participants/${encodeURIComponent(identity)}`,
    'DELETE',
  )
}
