import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useState } from 'react'
import { ModalShell, type ModalFormId } from '../ModalShell'

/**
 * Модальная оболочка «Aethergram» (feature 008, T012; FR-026, data-model
 * 1.6/3.3, ui-behavior §3): единая оболочка с формами-обитателями.
 * DOM и классы — дословно из прототипа design/chats.html (`.modal-back`
 * + `.modal panel` + `.modal-title`); фокус-ловушка, возврат фокуса на
 * инициатора, закрытие по Esc и по фону «press+release» (нажатие И
 * отпускание на самом фоне — перетаскивание не закрывает); переключение
 * formId внутри одной оболочки — без второй подложки.
 */

/** Форма-обитатель «Контакты»: поле поиска (первый фокусируемый) + кнопка. */
function ContactsForm({ onClose }: { readonly onClose: () => void }) {
  return (
    <div>
      <input placeholder="Поиск контакта" />
      <button type="button" onClick={onClose}>
        Закрыть форму
      </button>
    </div>
  )
}

/** Форма-обитатель «Подтверждение»: две кнопки, первый фокусируемый — Отмена. */
function ConfirmForm({ onConfirm }: { readonly onConfirm: () => void }) {
  return (
    <div>
      <button type="button">Отмена</button>
      <button type="button" onClick={onConfirm}>
        Подтвердить
      </button>
    </div>
  )
}

interface HarnessProps {
  readonly initialForm: ModalFormId | null
}

/**
 * Проба-потребитель в духе T034 (MessengerPage): держит formId в состоянии,
 * открывает оболочку кнопкой-инициатором, закрывает колбэком/Esc/фоном,
 * переключает contacts → confirm поверх открытой оболочки.
 */
function ModalHarness({ initialForm }: HarnessProps) {
  const [formId, setFormId] = useState<ModalFormId | null>(initialForm)
  const close = () => setFormId(null)
  return (
    <>
      <button type="button" onClick={() => setFormId('contacts')}>
        контакты
      </button>
      <button type="button" onClick={() => setFormId('confirm')}>
        переключить на подтверждение
      </button>
      <button type="button" onClick={close}>
        закрыть
      </button>
      <ModalShell
        formId={formId}
        title={formId === 'confirm' ? 'Подтверждение' : 'Контакты'}
        onClose={close}
      >
        {formId === 'confirm' ? (
          <ConfirmForm onConfirm={close} />
        ) : (
          <ContactsForm onClose={close} />
        )}
      </ModalShell>
    </>
  )
}

function renderHarness(initialForm: ModalFormId | null = null) {
  return render(<ModalHarness initialForm={initialForm} />)
}

function backdrop(): HTMLElement {
  const el = document.querySelector<HTMLElement>('.modal-back')
  expect(el).not.toBeNull()
  return el as HTMLElement
}

function dialog(): HTMLElement {
  const el = document.querySelector<HTMLElement>('.modal')
  expect(el).not.toBeNull()
  return el as HTMLElement
}

/** Единая подложка на приложение (data-model 1.6 stackPolicy). */
function expectSingleBackdrop(): void {
  expect(document.querySelectorAll('.modal-back')).toHaveLength(1)
}

afterEach(() => {
  cleanup()
})

describe('ModalShell — единая оболочка: DOM и formId (FR-026, data-model 1.6)', () => {
  it('в закрытом состоянии подложка одна, без show, без контента и скрыта от AT', () => {
    renderHarness()
    expectSingleBackdrop()
    const back = backdrop()
    expect(back.className).toBe('modal-back')
    expect(back).toHaveAttribute('aria-hidden', 'true')
    expect(back.textContent).toBe('')
  })

  it('открытая оболочка: show, role=dialog + aria-modal, заголовок и контент форм', () => {
    renderHarness('contacts')
    expectSingleBackdrop()
    expect(backdrop().className).toBe('modal-back show')
    const dlg = dialog()
    expect(dlg).toHaveAttribute('role', 'dialog')
    expect(dlg).toHaveAttribute('aria-modal', 'true')
    expect(dlg).toHaveAccessibleName('Контакты')
    expect(document.querySelector('.modal-title')?.textContent).toBe('Контакты')
    expect(screen.getByPlaceholderText('Поиск контакта')).toBeInTheDocument()
  })

  it('переключение formIdcontacts → confirm — без второй подложки, контент и заголовок меняются', () => {
    renderHarness('contacts')
    expect(backdrop().className).toBe('modal-back show')

    fireEvent.click(screen.getByRole('button', { name: 'переключить на подтверждение' }))
    expectSingleBackdrop()
    expect(backdrop().className).toBe('modal-back show')
    expect(document.querySelector('.modal-title')?.textContent).toBe('Подтверждение')
    expect(screen.queryByPlaceholderText('Поиск контакта')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Подтвердить' })).toBeInTheDocument()
  })

  it('после закрытия снова закрытое состояние — подложка всё ещё одна', () => {
    renderHarness('contacts')
    fireEvent.click(screen.getByRole('button', { name: 'Закрыть форму' }))
    expectSingleBackdrop()
    expect(backdrop().className).toBe('modal-back')
    expect(backdrop().textContent).toBe('')
  })
})

describe('ModalShell — фокус-ловушка (FR-026, ui-behavior §1)', () => {
  it('при открытии фокус переносится на первый фокусируемый элемент формы', () => {
    renderHarness()
    fireEvent.click(screen.getByRole('button', { name: 'контакты' }))
    expect(document.activeElement).toBe(screen.getByPlaceholderText('Поиск контакта'))
  })

  it('Tab на последнем элементе заворачивает на первый', () => {
    renderHarness('contacts')
    fireEvent.click(screen.getByRole('button', { name: 'контакты' }))
    const input = screen.getByPlaceholderText('Поиск контакта')
    const last = screen.getByRole('button', { name: 'Закрыть форму' })
    last.focus()
    fireEvent.keyDown(last, { key: 'Tab' })
    expect(document.activeElement).toBe(input)
  })

  it('Shift+Tab на первом элементе заворачивает на последний', () => {
    renderHarness('confirm')
    const first = screen.getByRole('button', { name: 'Отмена' })
    expect(document.activeElement).toBe(first)
    fireEvent.keyDown(first, { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Подтвердить' }))
  })

  it('фокус не покидает оболочку при многократном Tab', () => {
    renderHarness('confirm')
    for (let i = 0; i < 6; i += 1) {
      fireEvent.keyDown(document.activeElement as HTMLElement, { key: 'Tab' })
    }
    expect(dialog().contains(document.activeElement)).toBe(true)
  })

  it('при переключении formId фокус заново ловится в новой форме', () => {
    renderHarness('contacts')
    fireEvent.click(screen.getByRole('button', { name: 'переключить на подтверждение' }))
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Отмена' }))
  })
})

describe('ModalShell — возврат фокуса на инициатора (ui-behavior §1)', () => {
  it('после закрытия фокус возвращается на кнопку-инициатор', () => {
    renderHarness()
    const opener = screen.getByRole('button', { name: 'контакты' })
    opener.focus()
    fireEvent.click(opener)
    expect(dialog().contains(document.activeElement)).toBe(true)

    fireEvent.click(screen.getByRole('button', { name: 'Закрыть форму' }))
    expect(document.activeElement).toBe(opener)
  })

  it('после переключения formId инициатором остаётся исходная кнопка, а не элемент формы', () => {
    renderHarness()
    const opener = screen.getByRole('button', { name: 'контакты' })
    opener.focus()
    fireEvent.click(opener)

    fireEvent.click(screen.getByRole('button', { name: 'переключить на подтверждение' }))
    fireEvent.click(screen.getByRole('button', { name: 'Подтвердить' }))
    expect(document.activeElement).toBe(opener)
  })
})

describe('ModalShell — Esc (FR-026, data-model 3.3)', () => {
  it('Esc закрывает открытую оболочку (один вызов onClose)', () => {
    const onClose = vi.fn()
    render(
      <ModalShell formId="profile" title="Мой профиль" onClose={onClose}>
        <input placeholder="username" />
      </ModalShell>,
    )
    fireEvent.keyDown(dialog(), { key: 'Escape' })
    expect(onClose).toHaveBeenCalledTimes(1)
  })

  it('в закрытом состоянии Esc не вызывает onClose', () => {
    const onClose = vi.fn()
    render(
      <ModalShell formId={null} title="Мой профиль" onClose={onClose}>
        <input placeholder="username" />
      </ModalShell>,
    )
    fireEvent.keyDown(document, { key: 'Escape' })
    expect(onClose).not.toHaveBeenCalled()
  })
})

describe('ModalShell — закрытие по фону press+release (FR-026, ui-behavior §3)', () => {
  it('нажатие и отпускание на самом фоне закрывают оболочку', () => {
    renderHarness('contacts')
    const back = backdrop()
    fireEvent.mouseDown(back)
    fireEvent.mouseUp(back)
    expect(backdrop().className).toBe('modal-back')
  })

  it('перетаскивание из окна на фон не закрывает: press в форме, release на фоне', () => {
    renderHarness('contacts')
    const back = backdrop()
    fireEvent.mouseDown(screen.getByPlaceholderText('Поиск контакта'))
    fireEvent.mouseUp(back)
    expect(backdrop().className).toBe('modal-back show')
  })

  it('перетаскивание с фона в окно не закрывает: press на фоне, release в форме', () => {
    renderHarness('contacts')
    const back = backdrop()
    fireEvent.mouseDown(back)
    fireEvent.mouseUp(screen.getByPlaceholderText('Поиск контакта'))
    expect(backdrop().className).toBe('modal-back show')
  })

  it('release на самом окне (внутри .modal) не закрывает', () => {
    renderHarness('contacts')
    fireEvent.mouseDown(dialog())
    fireEvent.mouseUp(dialog())
    expect(backdrop().className).toBe('modal-back show')
  })

  it('не-левая кнопка на фоне не закрывает', () => {
    renderHarness('contacts')
    const back = backdrop()
    fireEvent.mouseDown(back, { button: 2 })
    fireEvent.mouseUp(back, { button: 2 })
    expect(backdrop().className).toBe('modal-back show')
  })
})
