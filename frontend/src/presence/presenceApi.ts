/**
 * Presence API client (feature 007, T019; contracts/presence-api.md §1/§2,
 * research.md §E2): the №36 batch snapshot, the №37 heartbeat and the
 * heartbeat scheduler driven by the `connected` №18 frame.
 *
 * The scheduler's only client-side duty is keeping ITS OWN registration
 * alive (research.md §E2): every `connected` frame hands it a fresh
 * connectionId, and it beats №37 every 30 s against the 90 s TTL — a
 * 3× margin, so a single lost beat (429 `flood_limit`, network hiccup)
 * is absorbed by the next interval WITHOUT touching the SSE connection;
 * only 404 `presence_connection_not_found` means the registration (and
 * most likely the channel) is dead → immediate SSE reconnect, and the
 * reconnect's new `connected` frame restarts the loop with a new id.
 *
 * transport-keepalive `:ka` (15 s comment frame) is a №18 channel
 * concern and unrelated to №37 (presence-api.md §2) — this module never
 * touches it. №38 settings clients (T034) live below; snapshot chunking
 * (>200) and surface bookkeeping belong to usePresence (T020).
 */
import { toApiProblem } from '../api/auth'
import { apiFetch } from '../api/client'
import type { components } from '../api/schema'
import type { PresenceStatusItem } from './presenceStore'

export type PresenceHeartbeatRequest = components['schemas']['PresenceHeartbeatRequest']

export type PresenceSnapshotResponse = components['schemas']['PresenceSnapshotResponse']

export type PresenceSettingsResponse = components['schemas']['PresenceSettingsResponse']

export type PresenceSettingsUpdateRequest = components['schemas']['PresenceSettingsUpdateRequest']

const SNAPSHOT_PATH = '/users/me/presence'
const HEARTBEAT_PATH = '/users/me/presence/heartbeat'
const SETTINGS_PATH = '/users/me/presence/settings'

/** №37 contract interval: 30 s beats against the 90 s TTL = 3× margin (research.md §G). */
export const HEARTBEAT_INTERVAL_MS = 30_000

/**
 * №36 `GET /users/me/presence?userIds=`: one displayed-surface batch
 * (≤200 after dedup — chunking is the caller's, usePresence T020).
 * Non-OK answers reject with the RFC 9457 problem (`ApiProblem`,
 * incl. `retryAfterSec` on 429 `flood_limit`).
 */
export async function fetchPresenceSnapshot(
  userIds: readonly string[],
): Promise<PresenceStatusItem[]> {
  const unique = [...new Set(userIds)]
  if (unique.length === 0) {
    return []
  }
  const query = unique.map(encodeURIComponent).join(',')
  const response = await apiFetch(`${SNAPSHOT_PATH}?userIds=${query}`, {
    headers: { Accept: 'application/json, application/problem+json' },
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  const parsed = (await response.json()) as PresenceSnapshotResponse
  return parsed.items
}

/**
 * №38 `GET /users/me/presence/settings`: the persisted «incognito» mode
 * (users.presence_hidden, PG V15) — per-user, survives relogin
 * (presence-api.md §3). Non-OK answers reject with the ApiProblem.
 */
export async function fetchPresenceSettings(): Promise<PresenceSettingsResponse> {
  const response = await apiFetch(SETTINGS_PATH, {
    headers: { Accept: 'application/json, application/problem+json' },
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return (await response.json()) as PresenceSettingsResponse
}

/**
 * №38 `PUT /users/me/presence/settings`: idempotent mode flip; returns
 * the persisted value (a repeat of the same value is a server-side
 * no-op without events). Non-OK answers (400 `malformed_request`,
 * 429 `flood_limit` + retryAfterSec) reject with the ApiProblem.
 */
export async function updatePresenceSettings(
  incognito: boolean,
): Promise<PresenceSettingsResponse> {
  const response = await apiFetch(SETTINGS_PATH, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json, application/problem+json',
    },
    body: JSON.stringify({ incognito } satisfies PresenceSettingsUpdateRequest),
  })
  if (!response.ok) {
    const problem: unknown = await toApiProblem(response)
    throw problem
  }
  return (await response.json()) as PresenceSettingsResponse
}

/** Classified №37 outcome — the caller never needs the raw response. */
/**
 * - `'connection_lost'` — 404 `presence_connection_not_found`: reconnect
 *   SSE immediately;
 * - `'flood_limited'` — 429: keep the SSE connection, retry next interval;
 * - `'error'` — transient (network/5xx/final 401): retry next interval.
 */
export type HeartbeatOutcome = 'renewed' | 'connection_lost' | 'flood_limited' | 'error'

/**
 * №37 `POST /users/me/presence/heartbeat`: atomically renews the
 * registration horizons to now + 90 s. Classifies per
 * presence-api.md §2; network failures resolve to `'error'` — a beat
 * must never surface as an unhandled rejection.
 */
export async function sendPresenceHeartbeat(connectionId: string): Promise<HeartbeatOutcome> {
  let response: Response
  try {
    response = await apiFetch(HEARTBEAT_PATH, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'application/json, application/problem+json',
      },
      body: JSON.stringify({ connectionId } satisfies PresenceHeartbeatRequest),
    })
  } catch {
    return 'error'
  }
  await discardBody(response)
  if (response.ok) {
    return 'renewed'
  }
  if (response.status === 404) {
    return 'connection_lost'
  }
  if (response.status === 429) {
    return 'flood_limited'
  }
  return 'error'
}

export interface PresenceHeartbeatOptions {
  /** Beat interval; defaults to the contract 30 s (HEARTBEAT_INTERVAL_MS). */
  readonly intervalMs?: number
  /**
   * 404 `presence_connection_not_found` → the consumer must reconnect
   * the SSE channel IMMEDIATELY; the new `connected` frame restarts
   * this heartbeat via `updateConnectionId` (presence-api.md §2).
   */
  readonly onReconnectRequired?: () => void
}

export interface PresenceHeartbeat {
  /** A `connected` №18 frame arrived (reconnects issue a NEW id). */
  updateConnectionId(connectionId: string): void
  /** The stream is gone (close/logout) — stop beating, forget the id. */
  stop(): void
}

export function createPresenceHeartbeat(options: PresenceHeartbeatOptions = {}): PresenceHeartbeat {
  const intervalMs = options.intervalMs ?? HEARTBEAT_INTERVAL_MS
  let connectionId: string | null = null
  let timer: ReturnType<typeof setInterval> | null = null
  let beating = false

  function clearTimer(): void {
    if (timer !== null) {
      clearInterval(timer)
      timer = null
    }
  }

  async function beat(): Promise<void> {
    const id = connectionId
    if (id === null || beating) {
      return
    }
    beating = true
    try {
      const outcome = await sendPresenceHeartbeat(id)
      if (outcome === 'connection_lost' && connectionId === id) {
        connectionId = null
        clearTimer()
        try {
          options.onReconnectRequired?.()
        } catch {
          // a broken callback must not kill the beat loop
        }
      }
      // 'renewed' | 'flood_limited' | 'error': keep the SSE connection
      // and retry in the next interval — the 90 s TTL is a 3× margin.
    } finally {
      beating = false
    }
  }

  return {
    updateConnectionId(next: string): void {
      connectionId = next
      clearTimer()
      timer = setInterval(() => {
        void beat()
      }, intervalMs)
    },
    stop(): void {
      connectionId = null
      clearTimer()
    },
  }
}

async function discardBody(response: Response): Promise<void> {
  try {
    await response.body?.cancel()
  } catch {
    // body disposal is best-effort
  }
}
