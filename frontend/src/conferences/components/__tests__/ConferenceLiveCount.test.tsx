import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import ConferenceLiveCount from '../ConferenceLiveCount'

/**
 * 009, US1, T080 — живой счётчик подключённых участников шапки
 * joined-состояния: «Участники: N / maxParticipants» — проекция
 * useParticipants ([local, ...remote], тот же источник истины, что
 * панель «Участники (N)» T041 — конституция III, без дублирования
 * состояния), maxParticipants — пропс из уже загруженного №45-вида.
 * Примитив @livekit/components-react подменён фейком (паттерн
 * ParticipantsPanel.test): предмет теста — живая проекция числа
 * (приход/уход участников меняет цифру), семантика числа совпадает с
 * activeParticipants №45 — подключения медиа-плоскости, квота
 * per-connection (две вкладки одного пользователя = 2 слота, edge
 * spec.md).
 */

const lkc = vi.hoisted(() => ({
  useParticipants: vi.fn(),
}))

vi.mock('@livekit/components-react', () => ({
  useParticipants: lkc.useParticipants,
}))

/** Минимальный контракт участника LiveKit, используемый счётчиком. */
interface FakeParticipant {
  readonly identity: string
  readonly isLocal: boolean
}

function participant(identity: string, isLocal = false): FakeParticipant {
  return { identity, isLocal }
}

describe('ConferenceLiveCount (009 US1 T080)', () => {
  beforeEach(() => {
    lkc.useParticipants.mockReset()
  })

  afterEach(() => {
    cleanup()
    vi.clearAllMocks()
  })

  it('N / maxParticipants: локальный + 2 удалённых → «Участники: 3 / 16»', () => {
    lkc.useParticipants.mockReturnValue([
      participant('u:1:c1', true),
      participant('u:2:c2'),
      participant('u:3:c3'),
    ])

    render(<ConferenceLiveCount maxParticipants={16} />)

    expect(screen.getByText('Участники: 3 / 16')).toBeInTheDocument()
  })

  it('живая проекция: приход/уход участников меняет цифру на ререндере', () => {
    lkc.useParticipants.mockReturnValue([participant('u:1:c1', true)])
    const { rerender } = render(<ConferenceLiveCount maxParticipants={16} />)
    expect(screen.getByText('Участники: 1 / 16')).toBeInTheDocument()

    // приход двух удалённых участников (ParticipantConnected → хук)
    lkc.useParticipants.mockReturnValue([
      participant('u:1:c1', true),
      participant('u:2:c2'),
      participant('u:3:c3'),
    ])
    rerender(<ConferenceLiveCount maxParticipants={16} />)
    expect(screen.getByText('Участники: 3 / 16')).toBeInTheDocument()

    // уход одного удалённого (ParticipantDisconnected → хук)
    lkc.useParticipants.mockReturnValue([participant('u:1:c1', true), participant('u:2:c2')])
    rerender(<ConferenceLiveCount maxParticipants={16} />)
    expect(screen.getByText('Участники: 2 / 16')).toBeInTheDocument()
  })

  it('maxParticipants — из пропса №45-вида: новый запрос не вводится', () => {
    lkc.useParticipants.mockReturnValue([participant('u:1:c1', true)])

    render(<ConferenceLiveCount maxParticipants={8} />)

    expect(screen.getByText('Участники: 1 / 8')).toBeInTheDocument()
  })
})
