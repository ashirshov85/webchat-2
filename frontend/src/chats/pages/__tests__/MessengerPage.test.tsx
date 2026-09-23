import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it } from 'vitest'
import { MessengerPage } from '../MessengerPage'

afterEach(() => {
  cleanup()
})

describe('MessengerPage', () => {
  it('renders the chats/contacts panel on the left and the dialog window', () => {
    render(<MessengerPage />)

    expect(screen.getByRole('complementary', { name: 'Чаты и контакты' })).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Окно диалога' })).toBeInTheDocument()
  })

  it('renders the panel before the dialog window in layout order', () => {
    render(<MessengerPage />)

    const panel = screen.getByRole('complementary', { name: 'Чаты и контакты' })
    const dialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(panel.compareDocumentPosition(dialog) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('shows the empty state in the dialog window while no chat is open', () => {
    render(<MessengerPage />)

    const dialog = screen.getByRole('region', { name: 'Окно диалога' })
    expect(dialog).toHaveTextContent('Выберите чат, чтобы начать общение')
  })
})
