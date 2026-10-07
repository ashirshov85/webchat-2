import { act, cleanup, fireEvent, render, screen } from '@testing-library/react'
import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ToastProvider } from '../../../ui/Toast'
import { MessageInput } from '../MessageInput'
import { MESSAGE_MAX_LENGTH } from '../../validation'

/**
 * The composer rebuilt per the prototype (feature 008, US1, T024;
 * FR-021, ui-behavior §4 «Композер»): the `.chat-input` block of
 * design/chats.html §7 — the golden `.input-frame` around the field
 * (`.msg-input`, Anonymous Pro over `--input-bg`) + the «ОТПРАВИТЬ»
 * plate (`.send-btn`, `--gold-gradient`). The 004 hooks stay: the
 * form keeps `message-input`, the field — id `message-composer`
 * (research §C, FR-034).
 *
 * The overflow hint rides the TOAST slot (Bug 4, T082; FR-025,
 * ui-behavior §5): a draft over MESSAGE_MAX_LENGTH shows «Сообщение
 * слишком длинное: …» via useToast/ToastProvider (z-99, ~3 s, single
 * notification) — NOT an inline role=alert; the other local composer
 * error (the blank draft «Сообщение не может быть пустым») stays the
 * inline `message-input-error` role=alert, and the flood line/block
 * hint surfaces are untouched.
 *
 * Behavior (US1-AS4, FR-021): Enter sends the trimmed text; the send
 * button never steals the field focus (mousedown preventDefault —
 * focus stays in the field on every send path); the 004 length rule
 * (`validateMessageText`: trim, non-empty, ≤ MESSAGE_MAX_LENGTH)
 * keeps firing the local error instead of `onSend`. The prototype
 * «steam» (3 `.puff`s above the composer on send, design-tokens §5)
 * rides the `.steam` container of the block.
 *
 * The blocked-contact state (US2, T036; FR-022, US2-AS5) is the
 * prototype §7 `updateInputState()`: the field AND the «ОТПРАВИТЬ»
 * button go disabled while the placeholder carries the block hint —
 * «Контакт заблокирован — разблокируйте, чтобы писать сообщения».
 * It is the ONLY input lock of the composer (flood-limit/overflow
 * never disable input, FR-030); unblocking restores the activity and
 * the normal «Сообщение…» placeholder.
 */

/** Слот тоста ToastProvider — единственный .toast узел документа. */
function toastSlot(): HTMLElement {
  const slot = document.querySelector<HTMLElement>('.toast')
  if (slot === null) {
    throw new Error('тост-слот ToastProvider не смонтирован')
  }
  return slot
}

function renderInput(
  overrides: Partial<{ disabled: boolean; blocked: boolean; floodRetryAt: number | null }> = {},
) {
  const onSend = vi.fn()
  const view = render(
    <ToastProvider>
      <MessageInput
        onSend={onSend}
        disabled={overrides.disabled ?? false}
        blocked={overrides.blocked ?? false}
        floodRetryAt={overrides.floodRetryAt}
      />
    </ToastProvider>,
  )
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
  it('rejects a blank draft with the local INLINE error — onSend never fires', () => {
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: '   ' } })
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(onSend).not.toHaveBeenCalled()
    expect(screen.getByRole('alert')).toHaveClass('message-input-error')
    expect(screen.getByRole('alert').textContent).toContain('Сообщение не может быть пустым')
    expect(field.value).toBe('   ')
  })

  it(`rejects a draft over ${MESSAGE_MAX_LENGTH} characters with the TOAST — no inline alert (T082, Bug 4)`, () => {
    vi.useFakeTimers()
    const { onSend, field } = renderInput()

    fireEvent.change(field, { target: { value: 'а'.repeat(MESSAGE_MAX_LENGTH + 1) } })
    fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))

    expect(onSend).not.toHaveBeenCalled()
    // Подсказка переполнения — тост (FR-025): слот z-99 ToastProvider,
    // НЕ инлайн role=alert у композера.
    expect(screen.queryByRole('alert')).toBeNull()
    expect(toastSlot()).toHaveClass('show')
    expect(toastSlot()).toHaveTextContent(
      `Сообщение слишком длинное: ${MESSAGE_MAX_LENGTH + 1} из ${MESSAGE_MAX_LENGTH} допустимых символов`,
    )
    // Одиночный слот (data-model 1.4) и нетронутый черновик.
    expect(document.querySelectorAll('.toast')).toHaveLength(1)
    expect(field.value).toBe('а'.repeat(MESSAGE_MAX_LENGTH + 1))

    // Лексика T045/FR-025: авто-скрытие ~3 с.
    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(toastSlot()).not.toHaveClass('show')
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

describe('MessageInput blocked-contact state (T036, FR-022, US2-AS5)', () => {
  it('disables the field and the «ОТПРАВИТЬ» button and swaps the placeholder into the block hint', () => {
    const { field } = renderInput({ blocked: true })

    // Прототип §7 updateInputState(): inp.disabled / sendBtn.disabled /
    // placeholder = 'Контакт заблокирован — …' (FR-001: дословно).
    expect(field).toBeDisabled()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeDisabled()
    expect(field).toHaveAttribute(
      'placeholder',
      'Контакт заблокирован — разблокируйте, чтобы писать сообщения',
    )
  })

  it('never fires onSend while blocked — both submit paths are dead', () => {
    const { onSend, field } = renderInput({ blocked: true })

    fireEvent.keyDown(field, { key: 'Enter' })
    fireEvent.click(screen.getByRole('button', { name: 'ОТПРАВИТЬ' }))

    expect(onSend).not.toHaveBeenCalled()
  })

  it('unblocking restores the activity and the normal placeholder (US2-AS5)', () => {
    const onSend = vi.fn()
    const view = render(
      <ToastProvider>
        <MessageInput onSend={onSend} blocked />
      </ToastProvider>,
    )
    const field = view.container.querySelector('#message-composer') as HTMLTextAreaElement
    expect(field).toBeDisabled()

    view.rerender(
      <ToastProvider>
        <MessageInput onSend={onSend} blocked={false} />
      </ToastProvider>,
    )

    expect(field).toBeEnabled()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeEnabled()
    expect(field).toHaveAttribute('placeholder', 'Сообщение…')

    // Композер снова полноценно отправляет.
    fireEvent.change(field, { target: { value: '  Снова на связи  ' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('Снова на связи')
  })

  it('keeps the block lock independent of the 004 disabled wiring', () => {
    // 004-провод `disabled` остаётся своей веткой: без пользователя и
    // без блокировки ввод активен; блокировка глушит его сама по себе.
    const { field } = renderInput({ disabled: false, blocked: true })
    expect(field).toBeDisabled()
  })
})

describe('MessageInput flood-limit retry line (T051, FR-030, Clarification)', () => {
  /**
   * A 429/503 deferral of the open chat is a PATIENCE state, not an
   * input lock: the head `retryAt` record (outbox.ts headFloodRetryAt,
   * wired by MessengerPage over the open chat's records) drives a
   * single status line «Повтор через N с» above the field — ONE
   * interval ticks the ONE element (ui-behavior §4 «Композер»), the
   * waiting bubble itself stays static «отправляется» (MessageList),
   * and the field/button stay active: a new message transparently
   * enqueues as another optimistic row (005 semantics, SC-002).
   */
  it('renders no line while no deferral is pending — the composer stays plain', () => {
    const { container } = renderInput()

    expect(container.querySelector('.message-input-retry')).toBeNull()
  })

  it('shows «Повтор через N с» above the field — N is the ceil of the remainder', () => {
    vi.useFakeTimers()
    const { container } = renderInput({ floodRetryAt: Date.now() + 5000 })

    const line = container.querySelector('.message-input-retry') as HTMLElement
    expect(line).toBeInstanceOf(HTMLOutputElement)
    expect(line.textContent).toBe('Повтор через 5 с')
    // Над полем: строка стоит раньше золотой рамы в порядке формы.
    const frame = container.querySelector('.input-frame') as HTMLElement
    expect(line.compareDocumentPosition(frame) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy()
  })

  it('ticks ONE element — a single line decrements each second, no second surface appears', () => {
    vi.useFakeTimers()
    const { container } = renderInput({ floodRetryAt: Date.now() + 5000 })

    act(() => {
      vi.advanceTimersByTime(1000)
    })
    expect(container.querySelectorAll('.message-input-retry')).toHaveLength(1)
    expect(container.querySelector('.message-input-retry')?.textContent).toBe('Повтор через 4 с')

    act(() => {
      vi.advanceTimersByTime(2500)
    })
    expect(container.querySelector('.message-input-retry')?.textContent).toBe('Повтор через 2 с')

    // Ровно один живой элемент отсчёта на всю форму — не по записям.
    const form = container.querySelector('form.message-input') as HTMLElement
    expect(form.querySelectorAll('output')).toHaveLength(1)
  })

  it('hides the line at the deadline — the 005 engine retries by itself', () => {
    vi.useFakeTimers()
    const { container } = renderInput({ floodRetryAt: Date.now() + 3000 })

    act(() => {
      vi.advanceTimersByTime(3000)
    })
    expect(container.querySelector('.message-input-retry')).toBeNull()
  })

  it('keeps the input ACTIVE while the countdown shows — typing and sending still work', () => {
    vi.useFakeTimers()
    const { onSend, field } = renderInput({ floodRetryAt: Date.now() + 60_000 })

    expect(field).toBeEnabled()
    expect(screen.getByRole('button', { name: 'ОТПРАВИТЬ' })).toBeEnabled()

    fireEvent.change(field, { target: { value: '  В очереди  ' } })
    fireEvent.keyDown(field, { key: 'Enter' })
    expect(onSend).toHaveBeenCalledWith('В очереди')

    // Отсчёт — не блокировка: строка живёт своей жизнью поверх ввода.
    expect(document.querySelector('.message-input-retry')?.textContent).toBe('Повтор через 60 с')
  })

  it('re-targets on a new deferral and hides when the head record is gone', () => {
    vi.useFakeTimers()
    const onSend = vi.fn()
    const view = render(
      <ToastProvider>
        <MessageInput onSend={onSend} floodRetryAt={Date.now() + 5000} />
      </ToastProvider>,
    )
    expect(view.container.querySelector('.message-input-retry')?.textContent).toBe(
      'Повтор через 5 с',
    )

    act(() => {
      vi.advanceTimersByTime(2000)
    })
    expect(view.container.querySelector('.message-input-retry')?.textContent).toBe(
      'Повтор через 3 с',
    )

    // Новая отсрочка (ещё один 429) — головная запись уехала дальше.
    view.rerender(
      <ToastProvider>
        <MessageInput onSend={onSend} floodRetryAt={Date.now() + 8000} />
      </ToastProvider>,
    )
    expect(view.container.querySelector('.message-input-retry')?.textContent).toBe(
      'Повтор через 8 с',
    )

    // Сервер подтвердил головную запись — головы больше нет.
    view.rerender(
      <ToastProvider>
        <MessageInput onSend={onSend} floodRetryAt={null} />
      </ToastProvider>,
    )
    expect(view.container.querySelector('.message-input-retry')).toBeNull()
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

describe('MessageInput auto-growing field (Bug 6, T084)', () => {
  /**
   * Многострочный черновик (Shift+Enter, 004) растит золотую раму:
   * высота поля = содержимому — авторост читает scrollHeight (+ 2px
   * рамок: box-sizing: border-box, у поля border 1px сверху/снизу) и
   * сажает высоту без внутренней прокрутки, пока max-height из
   * message-input.css не остановит рост — свыше максимума поле
   * прокручивается само. Отправка сжимает поле обратно к однострочной
   * высоте ВМЕСТЕ с черновиком; кнопка «ОТПРАВИТЬ» не растёт и прижата
   * к верхней границе строки композера (align-items: flex-start).
   *
   * jsdom не лейаутит (scrollHeight всегда 0), поэтому поведение пинится
   * моком scrollHeight, а пределы роста и посадка кнопки — статическим
   * пином стилей у их источника (паттерн T083/ChatHeader, FR-001:
   * stylesheet — норматив).
   */

  /** Рамки поля из message-input.css: border 1px × 2 (border-box). */
  const FIELD_BORDERS_PX = 2

  /** Мок scrollHeight поля; возвращает сеттер следующих значений. */
  function mockScrollHeight(field: HTMLTextAreaElement, initial: number) {
    let current = initial
    Object.defineProperty(field, 'scrollHeight', {
      configurable: true,
      get: () => current,
    })
    return (next: number) => {
      current = next
    }
  }

  it('grows with a multiline draft — the height tracks scrollHeight of the content', () => {
    const { field } = renderInput()
    const setScrollHeight = mockScrollHeight(field, 40)

    // Пересадка (resize-путь) — однострочная высота поля.
    fireEvent(window, new Event('resize'))
    expect(field.style.height).toBe(`${40 + FIELD_BORDERS_PX}px`)

    // Многострочный черновик (Shift+Enter) поднимает высоту поля.
    setScrollHeight(130)
    fireEvent.change(field, { target: { value: 'строка один\nстрока два\nстрока три' } })
    expect(field.style.height).toBe(`${130 + FIELD_BORDERS_PX}px`)
  })

  it('shrinks back to the single-line height together with the cleared draft after send', () => {
    const { onSend, field } = renderInput()
    const setScrollHeight = mockScrollHeight(field, 130)
    fireEvent.change(field, { target: { value: 'строка один\nстрока два' } })
    expect(field.style.height).toBe(`${130 + FIELD_BORDERS_PX}px`)

    setScrollHeight(40)
    fireEvent.keyDown(field, { key: 'Enter' })

    expect(onSend).toHaveBeenCalledWith('строка один\nстрока два')
    expect(field.value).toBe('')
    expect(field.style.height).toBe(`${40 + FIELD_BORDERS_PX}px`)
  })

  it('keeps the grown height on a rejected draft — the draft stays in the field', () => {
    const { field } = renderInput()
    mockScrollHeight(field, 130)
    fireEvent.change(field, { target: { value: '   ' } })
    expect(field.style.height).toBe(`${130 + FIELD_BORDERS_PX}px`)

    fireEvent.keyDown(field, { key: 'Enter' })

    // Пустой черновик отклонён инлайн-ошибкой, поле не чищено — высота
    // не схлопывается: отклонение валидации рост поля не трогает.
    expect(field.value).toBe('   ')
    expect(field.style.height).toBe(`${130 + FIELD_BORDERS_PX}px`)
  })

  it('refits on window resize — the wraps change with the composer width', () => {
    const { field } = renderInput()
    const setScrollHeight = mockScrollHeight(field, 40)
    fireEvent.change(field, { target: { value: 'длинный черновик' } })
    expect(field.style.height).toBe(`${40 + FIELD_BORDERS_PX}px`)

    // Окно сузилось — те же строки переносятся шире, высота пересажена.
    setScrollHeight(96)
    fireEvent(window, new Event('resize'))
    expect(field.style.height).toBe(`${96 + FIELD_BORDERS_PX}px`)
  })

  it('CSS owns the growth limits and the button pinning (static pin)', () => {
    const css = readFileSync(join(import.meta.dirname, '../message-input.css'), 'utf8')
    // Комментарии правила содержат фигурные скобки (form{} auth-наследия)
    // и обрывают простой [^}]* — разбираем тело правил без комментариев.
    const bareCss = css.replace(/\/\*[\s\S]*?\*\//g, '')

    /** Extracts the declarations block of an exact selector from css text. */
    function ruleBody(selector: string): string {
      const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
      const match = bareCss.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
      if (!match) {
        throw new Error(`rule not found: ${selector}`)
      }
      return match[1] ?? ''
    }

    // Потолок роста + внутренняя прокрутка сверх максимума.
    const fieldRule = ruleBody('.message-input .msg-input')
    expect(fieldRule).toContain('max-height:')
    expect(fieldRule).toContain('overflow-y: auto')

    // Кнопка прижата к верхней границе строки композера и не растёт.
    expect(ruleBody('.message-input.chat-input')).toContain('align-items: flex-start')
    expect(ruleBody('.message-input .send-btn')).toContain('flex: none')
  })
})
