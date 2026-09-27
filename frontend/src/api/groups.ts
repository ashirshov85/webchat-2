import { toApiProblem } from './auth'
import { apiFetch } from './client'
import type { components, operations } from './schema'

export type CreateGroupRequest = components['schemas']['CreateGroupRequest']

export type UpdateGroupRequest = components['schemas']['UpdateGroupRequest']

export type AddMembersRequest = components['schemas']['AddMembersRequest']

export type GroupMember = components['schemas']['GroupMember']

export type GroupView = components['schemas']['GroupView']

export type AddMembersResponse =
  operations['addGroupMembers']['responses'][200]['content']['application/json']

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
