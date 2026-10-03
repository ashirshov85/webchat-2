import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { useState, type ReactNode } from 'react'
import { ConfirmDialog, type ConfirmVariant } from '../ConfirmDialog'
import { ModalShell, type ModalFormId } from '../ModalShell'

/**
 * Подтверждение «Aethergram» (feature 008, T014; FR-014, SC-007, ui-behavior
 * §3): форма-обитатель 'confirm' единой оболочки ModalShell (T012). DOM и
 * классы — дословно из прототипа design/chats.html (confirmForm):
 * `.confirm-text` (текст с `<b>`-выделением имени, подпись `.confirm-sub`) +
 * `.modal-btns` с кнопками `.m-btn` (Отмена) и `.m-btn.primary[.danger]`
 * (действие; 100% деструктивных операций — через danger-кнопку, SC-007).
 * Заголовок рендерит оболочка. Проверки — через пробу-потребителя в духе
 * T034: ОДНА ModalShell на приложение, переключение forms contacts →
 * confirm без второй подложки; отмена — кнопка «Отмена»/Esc/фон (onClose
 * оболочки), подтверждение — активация submit-кнопки формы.
 */

/** Запрос подтверждения — как askConfirm(opt, cb) прототипа. */
interface ConfirmRequest {
  readonly title: string
  readonly text: ReactNode
  readonly confirmLabel?: string
  readonly variant?: ConfirmVariant
}

interface HarnessProps {
  readonly request: ConfirmRequest
  readonly initialForm?: ModalFormId | null
  readonly onConfirm?: () => void
  readonly onCancel?: () => void
}

/**
 * Проба-потребитель в духе T034 (MessengerPage): одна оболочка на
 * приложение, formId-переключение contacts → confirm поверх открытой
 * формы; Esc и фон оболочки сводятся к отмене (действия не выполняется).
 */
function ConfirmHarness({ request, initialForm = null, onConfirm, onCancel }: HarnessProps) {
  const [formId, setFormId] = useState<ModalFormId | null>(initialForm)
  const confirm = (): void => {
    onConfirm?.()
    setFormId(null)
  }
  const cancel = (): void => {
    onCancel?.()
    setFormId(null)
  }
  return (
    <>
      <button type="button" onClick={() => setFormId('contacts')}>
        контакты
      </button>
      <button type="button" onClick={() => setFormId('confirm')}>
        спросить
      </button>
      <ModalShell
        formId={formId}
        title={formId === 'confirm' ? request.title : 'Контакты'}
        onClose={cancel}
      >
        {formId === 'confirm' ? (
          <ConfirmDialog
            text={request.text}
            confirmLabel={request.confirmLabel}
            variant={request.variant}
            onConfirm={confirm}
            onCancel={cancel}
          />
        ) : (
          <input placeholder="Поиск контакта" />
        )}
      </ModalShell>
    </>
  )
}

/** Опасное подтверждение прототипа — askDeleteContact. */
const DELETE_CONTACT: ConfirmRequest = {
  title: 'Удалить контакт',
  text: (
    <>
      Контакт <b>Мария Лопес</b> будет удалён. Чат и история сохранятся.
    </>
  ),
  confirmLabel: 'Удалить',
  variant: 'danger',
}

/** Основное подтверждение прототипа — addForm → askConfirm (имя + подпись). */
const ADD_CONTACT: ConfirmRequest = {
  title: 'Добавить контакт',
  text: (
    <>
      <b>Alex Carter</b>
      <span className="confirm-sub">acarter · alex.carter@aethergram.io</span>
    </>
  ),
  confirmLabel: 'Добавить',
}

function backdrop(): HTMLElement {
  const el = document.querySelector<HTMLElement>('.modal-back')
  expect(el).not.toBeNull()
  return el as HTMLElement
}

/** Открытая единая оболочка: show и ровно одна подложка (data-model 1.6). */
function expectOpenShell(): void {
  expect(document.querySelectorAll('.modal-back')).toHaveLength(1)
  expect(backdrop().className).toBe('modal-back show')
}

function cancelButton() {
  return screen.getByRole('button', { name: 'Отмена' })
}

afterEach(() => {
  cleanup()
})

describe('ConfirmDialog — DOM формы-обитателя confirm (ui-behavior §3)', () => {
  it('структура confirmForm прототипа: .confirm-text + .modal-btns (Отмена + действие submit)', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    const form = document.querySelector('.confirm-form')
    expect(form).toBeInTheDocument()
    expect(form?.tagName).toBe('FORM')
    expect(form?.querySelector('.confirm-text')).toBeInTheDocument()
    const btns = document.querySelector('.modal-btns')
    expect(btns).toBeInTheDocument()
    const cancel = screen.getByRole('button', { name: 'Отмена' })
    const ok = screen.getByRole('button', { name: 'Удалить' })
    expect(btns).toContainElement(cancel)
    expect(btns).toContainElement(ok)
    expect(cancel).toHaveAttribute('type', 'button')
    expect(ok).toHaveAttribute('type', 'submit')
  })

  it('заголовок подтверждения рендерит единая оболочка (.modal-title, имя диалога)', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    expect(document.querySelector('.modal-title')?.textContent).toBe('Удалить контакт')
    expect(screen.getByRole('dialog')).toHaveAccessibleName('Удалить контакт')
  })

  it('текст с <b>-выделением имени (ui-behavior §3)', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    const text = document.querySelector('.confirm-text')
    const name = text?.querySelector('b')
    expect(name).toBeInTheDocument()
    expect(name).toHaveTextContent('Мария Лопес')
    expect(text).toHaveTextContent('Контакт Мария Лопес будет удалён. Чат и история сохранятся.')
  })

  it('подпись .confirm-sub (username · email) — часть текста добавления контакта', () => {
    render(<ConfirmHarness request={ADD_CONTACT} initialForm="confirm" />)
    const sub = document.querySelector('.confirm-text .confirm-sub')
    expect(sub).toBeInTheDocument()
    expect(sub).toHaveTextContent('acarter · alex.carter@aethergram.io')
  })

  it('действие по умолчанию — основная кнопка (.m-btn primary) с надписью «Подтвердить»', () => {
    render(
      <ConfirmHarness
        request={{
          title: 'Создать чат',
          text: (
            <>
              Создать чат с <b>Мария Лопес</b>?
            </>
          ),
        }}
        initialForm="confirm"
      />,
    )
    expect(screen.getByRole('button', { name: 'Подтвердить' }).className).toBe('m-btn primary')
    expect(cancelButton().className).toBe('m-btn')
  })

  it('свойственная надпись действия — как opt.ok прототипа («Создать»)', () => {
    render(
      <ConfirmHarness
        request={{
          title: 'Создать чат',
          text: (
            <>
              Создать чат с <b>Мария Лопес</b>?
            </>
          ),
          confirmLabel: 'Создать',
        }}
        initialForm="confirm"
      />,
    )
    expect(screen.getByRole('button', { name: 'Создать' }).className).toBe('m-btn primary')
  })

  it('опасное действие — danger-кнопка (.m-btn primary danger)', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    expect(screen.getByRole('button', { name: 'Удалить' }).className).toBe('m-btn primary danger')
  })

  it('вариант default — обычная кнопка (.m-btn)', () => {
    render(
      <ConfirmHarness
        request={{
          title: 'Разблокировать контакт',
          text: (
            <>
              <b>Мария Лопес</b> снова сможет отправлять вам сообщения.
            </>
          ),
          confirmLabel: 'Разблокировать',
          variant: 'default',
        }}
        initialForm="confirm"
      />,
    )
    expect(screen.getByRole('button', { name: 'Разблокировать' }).className).toBe('m-btn')
  })
})

describe('ConfirmDialog — подтверждение и отмена (FR-014, SC-007)', () => {
  it('само открытие ничего не выполняет: ни onConfirm, ни onCancel до явного выбора', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmHarness
        request={DELETE_CONTACT}
        initialForm="confirm"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    expect(onConfirm).not.toHaveBeenCalled()
    expect(onCancel).not.toHaveBeenCalled()
  })

  it('клик по кнопке действия подтверждает: onConfirm 1 раз, onCancel — ни разу, форма закрыта', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmHarness
        request={DELETE_CONTACT}
        initialForm="confirm"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
    expect(onCancel).not.toHaveBeenCalled()
    expect(document.querySelectorAll('.modal-back')).toHaveLength(1)
    expect(backdrop().className).toBe('modal-back')
  })

  it('клик по «Отмена» отменяет: onCancel 1 раз, onConfirm — ни разу, форма закрыта', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmHarness
        request={DELETE_CONTACT}
        initialForm="confirm"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    fireEvent.click(cancelButton())
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
    expect(backdrop().className).toBe('modal-back')
  })

  it('Esc закрывает оболочку путём отмены (onClose ModalShell = отмена, не действие)', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmHarness
        request={DELETE_CONTACT}
        initialForm="confirm"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    const dialog = document.querySelector('.modal') as HTMLElement
    fireEvent.keyDown(dialog, { key: 'Escape' })
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
    expect(backdrop().className).toBe('modal-back')
  })

  it('фон press+release — отмена, не действие', () => {
    const onConfirm = vi.fn()
    const onCancel = vi.fn()
    render(
      <ConfirmHarness
        request={DELETE_CONTACT}
        initialForm="confirm"
        onConfirm={onConfirm}
        onCancel={onCancel}
      />,
    )
    const back = backdrop()
    fireEvent.mouseDown(back)
    fireEvent.mouseUp(back)
    expect(onCancel).toHaveBeenCalledTimes(1)
    expect(onConfirm).not.toHaveBeenCalled()
  })
})

describe('ConfirmDialog — единая оболочка и клавиатура (FR-026, FR-035)', () => {
  it('переключение contacts → confirm — без второй подложки, контент и заголовок меняются', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} />)
    fireEvent.click(screen.getByRole('button', { name: 'контакты' }))
    expectOpenShell()
    fireEvent.click(screen.getByRole('button', { name: 'спросить' }))
    expectOpenShell()
    expect(document.querySelector('.modal-title')?.textContent).toBe('Удалить контакт')
    expect(screen.queryByPlaceholderText('Поиск контакта')).not.toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Удалить' })).toBeInTheDocument()
  })

  it('вход фокуса — «Отмена» (первый фокусируемый формы; безопасный дефолт опасных действий)', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    expect(document.activeElement).toBe(cancelButton())
  })

  it('Tab остаётся в форме: цикл Отмена ↔ действие', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} initialForm="confirm" />)
    const ok = screen.getByRole('button', { name: 'Удалить' })
    fireEvent.keyDown(ok, { key: 'Tab' })
    expect(document.activeElement).toBe(cancelButton())
    fireEvent.keyDown(cancelButton(), { key: 'Tab', shiftKey: true })
    expect(document.activeElement).toBe(ok)
  })

  it('после подтверждения фокус возвращается на инициатора', () => {
    render(<ConfirmHarness request={DELETE_CONTACT} />)
    const opener = screen.getByRole('button', { name: 'спросить' })
    opener.focus()
    fireEvent.click(opener)
    expect(document.activeElement).toBe(cancelButton())
    fireEvent.click(screen.getByRole('button', { name: 'Удалить' }))
    expect(document.activeElement).toBe(opener)
  })
})

describe('ConfirmDialog — текст без innerHTML (FR-033)', () => {
  it('разметка в строке текста не интерпретируется — dangerouslySetInnerHTML нет', () => {
    render(
      <ConfirmHarness
        request={{ title: 'Удалить чат', text: '<b>инъекция</b>' }}
        initialForm="confirm"
      />,
    )
    const text = document.querySelector('.confirm-text')
    expect(text?.querySelector('b')).toBeNull()
    expect(text).toHaveTextContent('<b>инъекция</b>')
  })
})
