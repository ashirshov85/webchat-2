import { toApiProblem } from './auth'
import { apiFetch } from './client'
import type { components, operations } from './schema'

export type CreateGroupRequest = components['schemas']['CreateGroupRequest']

export type UpdateGroupRequest = components['schemas']['UpdateGroupRequest']

export type AddMembersRequest = components['schemas']['AddMembersRequest']

export type SetMemberRoleRequest = components['schemas']['SetMemberRoleRequest']

export type TransferOwnershipRequest = components['schemas']['TransferOwnershipRequest']

export type GroupMember = components['schemas']['GroupMember']

export type GroupView = components['schemas']['GroupView']

export type AddMembersResponse =
  operations['addGroupMembers']['responses'][200]['content']['application/json']

export type GroupUpdatedEvent = components['schemas']['GroupUpdatedEvent']

export type GroupMemberAddedEvent = components['schemas']['GroupMemberAddedEvent']

export type GroupMemberRemovedEvent = components['schemas']['GroupMemberRemovedEvent']

export type GroupRoleChangedEvent = components['schemas']['GroupRoleChangedEvent']

export type GroupDeletedEvent = components['schemas']['GroupDeletedEvent']

export type GroupYouRemovedEvent = components['schemas']['GroupYouRemovedEvent']

/**
 * A №18 group frame as the client dispatches it (feature 006,
 * realtime-group-events.md §3): the SSE `event:` type tagged onto the
 * payload — the payload itself carries no type field.
 */
export type GroupRealtimeEvent =
  | ({ type: 'group.updated' } & GroupUpdatedEvent)
  | ({ type: 'group.member.added' } & GroupMemberAddedEvent)
  | ({ type: 'group.member.removed' } & GroupMemberRemovedEvent)
  | ({ type: 'group.role.changed' } & GroupRoleChangedEvent)
  | ({ type: 'group.deleted' } & GroupDeletedEvent)
  | ({ type: 'group.you_removed' } & GroupYouRemovedEvent)

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

/**
 * №27 `POST /groups`: creates the group (FR-001) with the caller as
 * owner and the initial members from the creator's contacts (FR-002);
 * `201` GroupView — an invalid/non-contact batch rejects the whole
 * request atomically, no group is created.
 */
export async function createGroup(body: CreateGroupRequest): Promise<GroupView> {
  const response = await authedRequest('/groups', 'POST', body)
  return (await response.json()) as GroupView
}

/**
 * №28 `GET /groups/{chatId}`: metadata + active members with roles
 * (≤200). Authorized by active membership on every request (FR-008):
 * non-members, former members and direct-chat ids get the unified
 * `404 group_not_found` (FR-009).
 */
export async function getGroup(chatId: string): Promise<GroupView> {
  const response = await authedRequest(`/groups/${encodeURIComponent(chatId)}`, 'GET')
  return (await response.json()) as GroupView
}

/**
 * №29 `PATCH /groups/{chatId}`: changes group metadata (owner/admin,
 * FR-005) — at least one field or the server rejects with
 * `400 empty_patch`; applied atomically, `200` GroupView and a
 * `group.updated` broadcast to members.
 */
export async function updateGroup(chatId: string, body: UpdateGroupRequest): Promise<GroupView> {
  const response = await authedRequest(`/groups/${encodeURIComponent(chatId)}`, 'PATCH', body)
  return (await response.json()) as GroupView
}

/**
 * №31 `POST /groups/{chatId}/members`: batch add of members from the
 * ADDER's contacts (owner/admin, FR-002/FR-004). The batch is atomic
 * (any rejected item rejects it all), idempotent for already-active
 * users; returns the current active roster after the operation.
 */
export async function addMembers(chatId: string, body: AddMembersRequest): Promise<GroupMember[]> {
  const response = await authedRequest(
    `/groups/${encodeURIComponent(chatId)}/members`,
    'POST',
    body,
  )
  const parsed = (await response.json()) as AddMembersResponse
  return parsed.members
}

/**
 * №32 `DELETE /groups/{chatId}/members/{userId}`: kicks a member
 * (owner/admin, FR-004 hierarchy — admins cannot kick admins/owner).
 * The kicked user's messages stay in history; kicking yourself is
 * `400 self_forbidden` — leaving is №33, not this call.
 */
export async function kickMember(chatId: string, userId: string): Promise<void> {
  await authedRequest(
    `/groups/${encodeURIComponent(chatId)}/members/${encodeURIComponent(userId)}`,
    'DELETE',
  )
}

/**
 * №34 `PUT /groups/{chatId}/members/{userId}/role`: grants/revokes the
 * admin role — owner-only (FR-003); `200` GroupMember with the new
 * role; everyone gets `group.role.changed`.
 */
export async function setMemberRole(
  chatId: string,
  userId: string,
  role: SetMemberRoleRequest['role'],
): Promise<GroupMember> {
  const response = await authedRequest(
    `/groups/${encodeURIComponent(chatId)}/members/${encodeURIComponent(userId)}/role`,
    'PUT',
    { role },
  )
  return (await response.json()) as GroupMember
}

/**
 * №35 `POST /groups/{chatId}/owner`: transfers ownership (owner-only,
 * FR-003) — exactly one owner remains, the old owner becomes admin;
 * `200` GroupView of the group under the new owner.
 */
export async function transferOwnership(
  chatId: string,
  userId: TransferOwnershipRequest['userId'],
): Promise<GroupView> {
  const response = await authedRequest(`/groups/${encodeURIComponent(chatId)}/owner`, 'POST', {
    userId,
  })
  return (await response.json()) as GroupView
}
