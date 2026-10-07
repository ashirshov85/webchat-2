import { act, cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { PresenceIndicator } from '../PresenceIndicator'
import { presenceStore, resetPresenceStore } from '../presenceStore'

/**
 * Accessible presence dot (feature 007, T012 → T021; research.md §E1,
 * clarify a11y 2026-10-01): one shared indicator for all three surfaces
 * («Чаты» direct rows, the 1:1 dialog header, «Контакты») — the dot
 * always carries an aria-label of the state («онлайн» / «офлайн» /
 * «неизвестно»), a visible TEXT label is rendered only in the 1:1
 * dialog header (`showLabel`, T022). Before the first №36 snapshot and
 * for №36 `unknown` («нет доступа», FR-006/FR-007) the indicator stays
 * NEUTRAL — a false «офлайн» before data is forbidden.
 */

const api = vi.hoisted(() => ({
  fetchPresenceSnapshot: vi.fn(),
  createPresenceHeartbeat: vi.fn(() => ({ updateConnectionId: vi.fn(), stop: vi.fn() })),
}))

vi.mock('../presenceApi', () => api)

const realtime = vi.hoisted(() => ({
  onOpen: () => () => {},
  onPresenceUpdated: () => () => {},
}))

vi.mock('../../chats/hooks/useRealtime', () => ({ useRealtime: () => realtime }))

const ALICE = '11111111-1111-4111-8111-111111111111'

beforeEach(() => {
  resetPresenceStore()
  api.fetchPresenceSnapshot.mockReset()
  api.fetchPresenceSnapshot.mockResolvedValue([])
})

afterEach(cleanup)

describe('PresenceIndicator (T012/T021)', () => {
  it('is neutral "неизвестно" before the first snapshot — never a false "офлайн"', () => {
    render(<PresenceIndicator userId={ALICE} />)

    const dot = screen.getByRole('img', { name: 'неизвестно' })
    expect(dot).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'офлайн' })).toBeNull()
    expect(dot.closest('.presence-indicator')).toHaveClass('presence-unknown')
  })

  it('announces «онлайн» for an online peer', () => {
    presenceStore.applyEvent({ userId: ALICE, status: 'online', rev: 1 })

    render(<PresenceIndicator userId={ALICE} />)

    const dot = screen.getByRole('img', { name: 'онлайн' })
    expect(dot).toBeInTheDocument()
    expect(dot.closest('.presence-indicator')).toHaveClass('presence-online')
  })

  it('announces «офлайн» for an offline peer', () => {
    presenceStore.applySnapshot([{ userId: ALICE, status: 'offline', rev: 2 }])

    render(<PresenceIndicator userId={ALICE} />)

    const dot = screen.getByRole('img', { name: 'офлайн' })
    expect(dot).toBeInTheDocument()
    expect(dot.closest('.presence-indicator')).toHaveClass('presence-offline')
  })

  it('stays neutral for №36 unknown — «нет доступа» is indistinguishable from «no data yet» (FR-006)', () => {
    presenceStore.applySnapshot([{ userId: ALICE, status: 'unknown', rev: 3 }])

    render(<PresenceIndicator userId={ALICE} />)

    expect(screen.getByRole('img', { name: 'неизвестно' })).toBeInTheDocument()
    expect(screen.queryByRole('img', { name: 'офлайн' })).toBeNull()
    expect(screen.queryByRole('img', { name: 'онлайн' })).toBeNull()
  })

  it('re-renders when the store changes under it', () => {
    render(<PresenceIndicator userId={ALICE} />)
    expect(screen.getByRole('img', { name: 'неизвестно' })).toBeInTheDocument()

    act(() => {
      presenceStore.applyEvent({ userId: ALICE, status: 'online', rev: 1 })
    })
    expect(screen.getByRole('img', { name: 'онлайн' })).toBeInTheDocument()

    act(() => {
      presenceStore.applyEvent({ userId: ALICE, status: 'offline', rev: 2 })
    })
    expect(screen.getByRole('img', { name: 'офлайн' })).toBeInTheDocument()
  })

  it('renders no visible text by default — the aria-label is the only label (list surfaces, T022)', () => {
    presenceStore.applyEvent({ userId: ALICE, status: 'online', rev: 1 })

    render(<PresenceIndicator userId={ALICE} />)

    expect(screen.getByRole('img', { name: 'онлайн' })).toBeInTheDocument()
    expect(screen.queryByText('онлайн')).toBeNull()
  })

  it('renders the visible text label only in the 1:1 dialog header mode (showLabel)', () => {
    presenceStore.applyEvent({ userId: ALICE, status: 'online', rev: 1 })

    render(<PresenceIndicator userId={ALICE} showLabel={true} />)

    expect(screen.getByRole('img', { name: 'онлайн' })).toBeInTheDocument()
    expect(screen.getByText('онлайн')).toBeVisible()
  })

  it('labels the neutral state in the header mode too', () => {
    render(<PresenceIndicator userId={ALICE} showLabel={true} />)

    expect(screen.getByRole('img', { name: 'неизвестно' })).toBeInTheDocument()
    expect(screen.getByText('неизвестно')).toBeVisible()
  })
})
