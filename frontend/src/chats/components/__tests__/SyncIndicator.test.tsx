import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { SyncIndicator } from '../SyncIndicator'

/**
 * Catch-up progress indicator (feature 005, T020; quickstart §3.1.1):
 * active while the §3.1 cycles A/B run («индикатор дозагрузки
 * мелькнул и погас»). Purely driven by the single-flight `syncing`
 * signal of useSync (T019) — one steady indication for the whole
 * cycle, no per-request blinking.
 *
 * 009 (T110): строка переведена в семью тихих подписей — лексика
 * «Загрузка чатов…» и подсказок сайдбара (`.messenger-empty`,
 * states.css, FR-032), не латунная плашка полос доставки: класс
 * `.sync-indicator` и точка-«лампа» `.sync-indicator-dot` демонтированы,
 * а замки a11y (role=status + дословный текст live-region) — на месте.
 */

afterEach(cleanup)

describe('SyncIndicator (T020)', () => {
  it('renders nothing while no catch-up cycle is running', () => {
    const { container } = render(<SyncIndicator syncing={false} />)

    expect(container.firstChild).toBeNull()
  })

  it('announces the running catch-up as a polite live region', () => {
    render(<SyncIndicator syncing={true} />)

    expect(screen.getByRole('status')).toHaveTextContent('Синхронизация сообщений…')
  })

  it('T110: rides the quiet `.messenger-empty` family — no brass strip, no dot', () => {
    const { container } = render(<SyncIndicator syncing={true} />)
    const status = screen.getByRole('status')

    expect(status).toHaveClass('messenger-empty')
    expect(status).not.toHaveClass('sync-indicator')
    expect(container.querySelector('.sync-indicator-dot')).toBeNull()
  })

  it('lights up for the cycle and goes dark on completion', () => {
    const { rerender, container } = render(<SyncIndicator syncing={true} />)
    expect(screen.getByRole('status')).toBeVisible()

    rerender(<SyncIndicator syncing={false} />)
    expect(container.firstChild).toBeNull()
    expect(screen.queryByRole('status')).toBeNull()
  })
})
