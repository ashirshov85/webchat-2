/**
 * Client presence store (feature 007, T018; research.md §C2):
 * `Map<userId, {status, rev}>` with the single client-side merge rule —
 * apply only a STRICTLY greater `rev` per-user. Everything else
 * (duplicate №18 frame, delayed frame, stale №36 snapshot) is a no-op:
 * the at-most-once channel may drop or duplicate frames freely, and
 * the monotone per-user revision makes every convergence path
 * deterministic (FR-003/FR-006, constitution III).
 *
 * The neutral «unknown» default is NOT «offline»: a fresh surface (a
 * chat-list row, a contact, an opened 1:1 dialog) shows no presence
 * signal at all until the first №36 snapshot arrives — a false
 * «офлайн» before data is a spec violation (clarify 2026-10-01).
 *
 * Producers: №36 snapshot items (`applySnapshot`, presenceApi) and
 * `presence.updated` №18 frames (`applyEvent`, usePresence). Consumers:
 * `getStatus`/`getEntry` + `subscribe` for re-renders. This module is
 * pure in-memory state; fetching and realtime wiring belong to
 * `presenceApi`/`usePresence` (T019/T020).
 */

import type { components } from '../api/schema'

/** SSE-событие №18 presence.updated (contracts/presence-events.md §2). */
export type PresenceUpdatedEvent = components['schemas']['PresenceUpdatedEvent']

/** Элемент №36 снапшота: статус одной цели (contracts/presence-api.md §1). */
export type PresenceStatusItem = components['schemas']['PresenceStatusItem']

/** online | offline | unknown — единая семантика «нет доступа» (FR-007). */
export type PresenceStatus = PresenceStatusItem['status']

/** Применённая запись статуса пользователя в store. */
export interface PresenceEntry {
  readonly status: PresenceStatus
  readonly rev: number
}

export type PresenceStoreListener = () => void

export interface PresenceStore {
  /** Опубликованный статус; «unknown» до первого снимка/события. */
  getStatus(userId: string): PresenceStatus
  /** Текущая запись или undefined, пока пользователь не известен. */
  getEntry(userId: string): PresenceEntry | undefined
  /**
   * Сливает кадр №18 `presence.updated` по правилу строго большего
   * rev per-user (research.md §C2). Returns true when applied;
   * дубль/устаревший кадр — false, состояние не тронуто (FR-003).
   */
  applyEvent(event: PresenceUpdatedEvent): boolean
  /**
   * Сливает элементы ответа №36 тем же правилом strictly-greater rev;
   * устаревший снимок никогда не затирает свежий кадр (FR-006).
   * Returns the number of applied items.
   */
  applySnapshot(items: readonly PresenceStatusItem[]): number
  /** Уведомляет только об применённых изменениях; возвращает отписку. */
  subscribe(listener: PresenceStoreListener): () => void
}

/** Внутреннее состояние + сброс (шов тестов общего инстанса). */
interface ResettablePresenceStore extends PresenceStore {
  reset(): void
}

function createPresenceStoreCore(): ResettablePresenceStore {
  const entries = new Map<string, PresenceEntry>()
  const listeners = new Set<PresenceStoreListener>()

  /** Единственное правило слияния: строго большее rev per-user. */
  function apply(userId: string, status: PresenceStatus, rev: number): boolean {
    const current = entries.get(userId)
    if (current !== undefined && rev <= current.rev) {
      return false
    }
    entries.set(userId, { status, rev })
    return true
  }

  function notify(): void {
    for (const listener of listeners) {
      try {
        listener()
      } catch {
        // a broken listener must never break a merge
      }
    }
  }

  return {
    getStatus(userId) {
      return entries.get(userId)?.status ?? 'unknown'
    },
    getEntry(userId) {
      return entries.get(userId)
    },
    applyEvent(event) {
      if (!apply(event.userId, event.status, event.rev)) {
        return false
      }
      notify()
      return true
    },
    applySnapshot(items) {
      let applied = 0
      for (const item of items) {
        if (apply(item.userId, item.status, item.rev)) {
          applied += 1
        }
      }
      if (applied > 0) {
        notify()
      }
      return applied
    },
    subscribe(listener) {
      listeners.add(listener)
      return () => {
        listeners.delete(listener)
      }
    },
    reset() {
      entries.clear()
      listeners.clear()
    },
  }
}

/** Фабрика изолированных store (тесты T012 и локальные инстансы виджета). */
export function createPresenceStore(): PresenceStore {
  return createPresenceStoreCore()
}

const sharedStore = createPresenceStoreCore()

/** Общий инстанс SPA; identity стабилен между reset'ами. */
export const presenceStore: PresenceStore = sharedStore

/**
 * Сбрасывает общий инстанс: записи и подписки очищаются (шов тестов,
 * смена аккаунта); ссылки на `presenceStore` остаются валидными.
 */
export function resetPresenceStore(): void {
  sharedStore.reset()
}
