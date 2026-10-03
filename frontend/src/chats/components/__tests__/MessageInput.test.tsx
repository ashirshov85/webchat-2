import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { MessageInput } from '../MessageInput'
import { MESSAGE_MAX_LENGTH } from '../../validation'

/**
 * The composer rebuilt per the prototype (feature 008, US1, T024;
 * FR-021, ui-behavior §4 «Композер»): the `.chat-input` block of
 * design/chats.html §7 — the golden `.input-frame` around the field
 * (`.msg-input`, Anonymous Pro over `--input-bg`) + the «ОТПРАВИТЬ»
 * plate (`.send-btn`, `--gold-gradient`). The 004 hooks stay: the
 * form keeps `message-input`, the field — id `message-composer`, the
 * local validation error — `message-input-error` role=alert
 * (research §C, FR-034).
 *
 * Behavior (US1-AS4, FR-021): Enter sends the trimmed text; the send
 * button never steals the field focus (mousedown preventDefault —
 * focus stays in the field on every send path); the 004 length rule
 * (`validateMessageText`: trim, non-empty, ≤ MESSAGE_MAX_LENGTH)
 * keeps firing the local error instead of `onSend`. The prototype
 * «steam» (3 `.puff`s above the composer on send, design-tokens §5)
 * rides the `.steam` container of the block.
 */

function renderInput(overrides: Partial<{ disabled: boolean }> = {}) {
  const onSend = vi.fn()
  const view = render(<MessageInput onSend={onSend} disabled={overrides.disabled ?? false} />)
  const field = view.container.querySelector('#message-composer') as HTMLTextAreaElement
  return { ...view, onSend, field }
}

afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

describe('MessageInput prototype block (T024, FR-021, ui-behavior §4)', () => {
  it('renders the .chat-input block: golden .input-frame field and «ОТПРАВИТЬ» .send-btn', () => {
    const { container, field } = renderInput()

    const form = container.querySelector('form.message-input.chat-input') as HTMLElement
    expect(form).not.toBeNull()

    // The field rides the golden frame; the 004 id hook stays (FR-034).
    expect(form.querySelector('.input-frame #message-composer.msg-input')).not.toBeNull()
    expect(form.querySelector('.steam')).not.toBeNull()

    // The visually-hidden label keeps the accessible name of the field.
    expect(screen.getByLabelText('Текст сообщения')).toBe(field)

    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toHaveClass('send-btn')
  })
})

describe('MessageInput sending (FR-021, US1-AS4)', () => {
  it('sends the trimmed text on Enter and clears the field', () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: '  Готов к отправке  ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(onSend).toHaveBeenCalledWith('Готов к отправке')
    expect(field.value).toBe('')
  })

  it('keeps the field focused after an Enter send', () => {
    const { field } = renderInput()

    field.focus()
    fireEvent.change(field, { target: { value: 'Готов к отправке' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(document.activeElement).toBe(field)
  })

  it('keeps Shift+Enter as a newline — no send (004 multiline input preserved)', () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: 'строка один' } })
    fireEvent.keyDown(field, { key: 'Enter', shiftKey: true })

    expect(onSend).not.toHaveBeenCalled()
    fireEvent.change(field, { target: { value: 'строка один\nстрока два' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('строка один\nстрока два')
  })

  it('sends by the «ОТПРАВИТЬ» button', () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: 'Готов к отправке' } })
    fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))

    expect(onSend).toHaveBeenCalledWith('Готов к отправке')
    expect(field.value).toBe('')
  })

  it('prevents the send button mousedown — the button never steals the field focus', () => {
    const { field } = renderInput()

    // The contract is the prevented default: a native mousedown on the
    // button would move the focus out of the field before the click.
    field.focus()
    expect(fireEvent.mouseDown(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))).toBe(false)
    expect(document.activeElement).toBe(field)
  })
})

describe('MessageInput 004 pre-validation kept (FR-021, FR-034, SC-002)', () => {
  it('rejects a whitespace-only draft with the local error — onSend never fires', () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveClass('message-input-error')
    expect(screen.getByRole('alert').textContent).toContain('Сообщение не может быть пустым')
    expect(field.value).toBe('   ')
  })

  it(`rejects a draft over ${MESSAGE_MAX_LENGTH} characters (004 length rule)`, () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: 'а'.repeat(MESSAGE_MAX_LENGTH + 1) } })
    fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))

    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByRole('alert').textContent).toContain('слишком длинное')
  })

  it(`sends a draft of exactly ${MESSAGE_MAX_LENGTH} characters`, () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: 'а'.repeat(MESSAGE_MAX_LENGTH) } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(onSend).toHaveBeenCalledTimes(1)
    expect(onSend).toHaveBeenCalledWith('а'.repeat(MESSAGE_MAX_LENGTH))
  })

  it('clears the error as soon as the draft changes again', () => {
    const { field } = renderInput()

    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(screen.getByRole('alert')).toBeInTheDocument()

    fireEvent.change(field, { target: { value: 'Готов к отправке' } })
    expect(screen.queryByRole('alert')).toBeNull()
  })
})

describe('MessageInput disabled state (004 wiring preserved)', () => {
  it('disables both the field and the «ОТПРАВИТЬ» button', () => {
    renderInput({ disabled: true })

    expect(screen.getByLabelText('Текст сообщения')).toBeDisabled()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeDisabled()
  })
})

describe("MessageInput steam puffs (design-tokens §5: 3 puff's above the composer on send)", () => {
  it('spawns exactly three puffs into .steam on a successful send and drops them after the flight', () => {
    vi.useFakeTimers()
    const { field } = renderInput()
    const steam = document.querySelector('.chat-input .steam') as HTMLElement
    expect(steam.querySelectorAll('.puff')).toHaveLength(0)

    fireEvent.change(field, { target: { value: 'Готов к отправке' } })
    fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))

    expect(steam.querySelectorAll('.puff')).toHaveLength(3)

    vi.advanceTimersByTime(1500)
    expect(steam.querySelectorAll('.puff')).toHaveLength(0)
  })

  it('spawns no steam on a rejected draft', () => {
    vi.useFakeTimers()
    const { field } = renderInput()

    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    const steam = document.querySelector('.chat-input .steam') as HTMLElement
    expect(steam.querySelectorAll('.puff')).toHaveLength(0)
  })
})
