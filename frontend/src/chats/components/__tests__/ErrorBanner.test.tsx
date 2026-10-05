import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ErrorBanner } from '../ErrorBanner'

/**
 * Dialog error banner (feature 004, T026; 008 adaptation US4, T048,
 * FR-030/FR-034): API problem details surface inside the chat window
 * as a readable `role="alert"` line (problemMessage, feature 002
 * conventions) and never block the surfaces around them. The suite is
 * deliberately role/text-based — no class hooks — so the T052 token
 * restyle keeps it green while the 004 behavior stays pinned: nothing
 * renders without an error, the dismiss control appears only when a
 * handler is given and fires exactly once.
 */

afterEach(cleanup)

describe('ErrorBanner (004 T026; 008 T048 adaptation)', () => {
  it('renders nothing while there is no error', () => {
    const { container } = render(<ErrorBanner error={null} />)

    expect(container.firstChild).toBeNull()
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('renders nothing for an undefined error', () => {
    const { container } = render(<ErrorBanner error={undefined} />)

    expect(container.firstChild).toBeNull()
  })

  it('announces validation problem details as an alert', () => {
    render(
      <ErrorBanner
        error={{ title: 'Problem', status: 400, errors: { text: ['text_too_long'] } }}
      />,
    )

    expect(screen.getByRole('alert')).toHaveTextContent('text: text_too_long')
  })

  it('falls back to the problem title when no field details are given', () => {
    render(<ErrorBanner error={{ title: 'Chat not found', status: 404 }} />)

    expect(screen.getByRole('alert')).toHaveTextContent('Chat not found')
  })

  it('shows no dismiss control without a handler', () => {
    render(<ErrorBanner error={{ title: 'Problem', status: 500 }} />)

    expect(screen.queryByRole('button', { name: 'Закрыть ошибку' })).toBeNull()
  })

  it('dismisses through the close control exactly once', () => {
    const onDismiss = vi.fn()
    render(<ErrorBanner error={{ title: 'Problem', status: 500 }} onDismiss={onDismiss} />)

    fireEvent.click(screen.getByRole('button', { name: 'Закрыть ошибку' }))

    expect(onDismiss).toHaveBeenCalledTimes(1)
  })
})
