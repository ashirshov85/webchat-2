import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it } from 'vitest'
import { addOutboxRecord, outboxStorageKey, OUTBOX_LIMIT } from '../../outbox'
import type { OutboxRecord } from '../../outbox'
import { QueueOverflowBanner } from '../QueueOverflowBanner'

/**
 * Queue-overflow notification banner (feature 005, T028; FR-005,
 * quickstart §3.3.4 «баннер показан»): when the offline queue passes
 * its fixed storage limit, `outbox.ts` (T026) evicts the oldest
 * `sending` records to terminal `failed`/`queue_overflow` and fires
 * `onOutboxOverflow` — the banner is the explicit user notification.
 *
 * Evictions are triggered through the REAL outbox module (a full queue
 * + `addOutboxRecord`), so the tests cover the whole T026 → T028 path
 * instead of a synthetic event. Written test-first (constitution VI):
 * must fail until the banner lands.
 */

const USER = 'user-banner'
const OTHER = 'user-other'

function sendingRecord(clientMessageId: string): OutboxRecord {
  return {
    clientMessageId,
    chatId: 'chat-1',
    text: `text-${clientMessageId}`,
    state: 'sending',
  }
}

/** Seeds `count` active `sending` records directly into localStorage. */
function seedSending(userId: string, count: number): void {
  const records = Array.from({ length: count }, (_unused, index) =>
    sendingRecord(`m-${String(index + 1).padStart(4, '0')}`),
  )
  window.localStorage.setItem(outboxStorageKey(userId), JSON.stringify(records))
}

function enqueueOverLimit(userId: string, clientMessageId: string): void {
  addOutboxRecord(userId, sendingRecord(clientMessageId))
}

beforeEach(() => {
  window.localStorage.clear()
})

afterEach(cleanup)

describe('QueueOverflowBanner (T028, FR-005)', () => {
  it('renders nothing while the queue never overflows', () => {
    const { container } = render(<QueueOverflowBanner userId={USER} />)

    act(() => {
      enqueueOverLimit(OTHER, 'x-1')
    })

    expect(container.firstChild).toBeNull()
  })

  it('renders nothing while no user is signed in', () => {
    const { container } = render(<QueueOverflowBanner userId={null} />)

    act(() => {
      enqueueOverLimit(USER, 'x-1')
    })

    expect(container.firstChild).toBeNull()
  })

  it('announces evictions of the signed-in account as a live region', () => {
    seedSending(USER, OUTBOX_LIMIT)
    render(<QueueOverflowBanner userId={USER} />)

    act(() => {
      enqueueOverLimit(USER, 'x-1')
    })

    expect(screen.getByRole('status')).toHaveTextContent(/Оффлайн-очередь переполнена/)
    expect(screen.getByRole('status')).toHaveTextContent(/переполнение очереди/)
  })

  it('ignores evictions of another account (per-user outbox storage)', () => {
    seedSending(OTHER, OUTBOX_LIMIT)
    render(<QueueOverflowBanner userId={USER} />)

    act(() => {
      enqueueOverLimit(OTHER, 'x-1')
    })

    expect(screen.queryByRole('status')).toBeNull()
  })

  it('accumulates evictions across repeated overflow bursts until dismissed', () => {
    seedSending(USER, OUTBOX_LIMIT)
    render(<QueueOverflowBanner userId={USER} />)

    act(() => {
      enqueueOverLimit(USER, 'x-1')
    })
    act(() => {
      enqueueOverLimit(USER, 'x-2')
    })

    expect(screen.getByRole('status')).toHaveTextContent(/2/)

    fireEvent.click(screen.getByRole('button', { name: 'Закрыть уведомление' }))
    expect(screen.queryByRole('status')).toBeNull()

    // After a dismiss the banner re-arms: a fresh eviction notifies again.
    act(() => {
      enqueueOverLimit(USER, 'x-3')
    })
    expect(screen.getByRole('status')).toBeVisible()
  })
})
