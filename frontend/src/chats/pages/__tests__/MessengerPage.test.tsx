import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { MessengerPage } from '../MessengerPage'

/**
 * Application shell (feature 008, US1, T017 → T018): the page renders
 * the prototype «machine» corpus — the chats/contacts sidebar and the
 * dialog window are its `.panel` surfaces carrying the prototype
 * classes `machine`/`sidebar`/`chat`/`panel` (design-tokens §3, FR-004)
 * — while the 004 structural expectations stay: the labeled
 * `complementary` panel comes before the labeled dialog window in
 * layout order, and the window without an open chat renders the US1
 * empty state «Чат не выбран» (FR-032; the prototype is normative,
 * FR-001 — replaces the 004 «Выберите чат…» line).
 */

afterEach(() => {
  cleanup()
})

describe('MessengerPage', () => {
  it('renders the machine shell with the panel surfaces: sidebar and chat window', () => {
    const { container } = render(<MessengerPage />)

    const machine = container.querySelector('.machine')
    expect(machine).not.toBeNull()

    const panel = screen.getByRole('complementary', { name: 'Чаты и контакты' })
    const dialog = screen.getByRole('region', { name: 'Окно диалога' })
    // Prototype hooks (FR-001): both surfaces of the machine carry the
    // prototype classes — `.sidebar` / `.chat` ride together with `.panel`.
    expect(panel).toHaveClass('sidebar', 'panel')
    expect(dialog).toHaveClass('chat', 'panel')
    expect(machine?.contains(panel)).toBe(true)
    expect(machine?.contains(dialog)).toBe(true)
  })

  it('renders the panel before the dialog window in layout order', () => {
    render(<MessengerPage />)

    const panel = screen.getByRole('complementary', { name: 'Чаты и контакты' })
    const dialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(panel.compareDocumentPosition(dialog) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows the «Чат не выбран» empty state in the dialog window while no chat is open', () => {
    render(<MessengerPage />)

    const dialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(dialog).toHaveTextContent('Чат не выбран')
  })
})
