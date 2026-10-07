/**
 * Модальная оболочка «Aethergram» (feature 008, T012; FR-026, data-model
 * 1.6/3.3, ui-behavior §3): единая оболочка приложения для форм-обитателей
 * (contacts / add-contact / create-group / group-edit / group-members /
 * profile / confirm). DOM и классы — дословно из прототипа
 * specs/008-chat-window-styling/design/chats.html: `.modal-back`
 * (подложка z-47, показ классом .show) > `.modal panel` > `.modal-title`
 * + тело-форма; стили — modal-shell.css на токенах --z-modal-back /
 * --font-heading (FR-002). Поведение: фокус-ловушка (Tab/Shift+Tab по
 * кругу), возврат фокуса на инициатора при закрытии, Esc, закрытие по
 * фону только «press+release на самом фоне» — нажатие И отпускание
 * левой кнопкой на подложке, перетаскивание из окна/в окно не закрывает.
 * Переключение formId внутри одной оболочки — без второй подложки
 * (stackPolicy: одна на приложение, data-model 1.6).
 */
import { useEffect, useId, useRef, type ReactNode } from 'react'
import './modal-shell.css'

/** Формы-обитатели оболочки (data-model 1.6). */
export type ModalFormId =
  | 'contacts'
  | 'add-contact'
  | 'create-group'
  | 'group-edit'
  | 'group-members'
  | 'profile'
  | 'confirm'

export interface ModalShellProps {
  /** Открытая форма; null — оболочка закрыта (одна на приложение). */
  readonly formId: ModalFormId | null
  /** Заголовок оболочки (подставляется под форму-обитателя). */
  readonly title: string
  /** Закрытие: Esc / фон press+release / «Отмена» / успех submit (data-model 3.3). */
  readonly onClose: () => void
  /** Тело-форма: жители рендерятся только в открытом состоянии. */
  readonly children: ReactNode
}

/**
 * Фокусируемые элементы ловушки (порядок документа = порядок Tab).
 * Экспортирован для ловушки drawer (T070, FR-035) — единый словарь
 * фокусируемости обоих слоёв-«клеток» машины.
 */
export const FOCUSABLE_SELECTOR = [
  'a[href]',
  'button:not([disabled])',
  'input:not([disabled])',
  'select:not([disabled])',
  'textarea:not([disabled])',
  '[tabindex]:not([tabindex="-1"])',
].join(',')

export function ModalShell({ formId, title, onClose, children }: ModalShellProps) {
  const open = formId !== null
  const dialogRef = useRef<HTMLDialogElement>(null)
  /** Подложка: нативные mousedown/mouseup-слушатели закрытия по фону. */
  const backRef = useRef<HTMLDivElement>(null)
  /** Инициатор открытия: сюда возвращается фокус при закрытии (ui-behavior §1). */
  const initiatorRef = useRef<HTMLElement | null>(null)
  /** Левая кнопка нажата на самой подложке (press-фаза закрытия по фону). */
  const pressOnBackRef = useRef(false)
  /** Свежий onClose для document-слушателя клавиатуры (ловушка живёт всё open). */
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  const titleId = useId()

  // Ловушка + Esc + инициатор. Объявлена РАНЬШЕ фокус-эффекта: при открытии
  // успевает захватить document.activeElement (инициатора) до переноса
  // фокуса в форму; при formId-переключении не пересоздаётся — инициатор
  // сохраняется, слушатель живёт всё время open.
  useEffect(() => {
    if (!open) {
      return
    }
    initiatorRef.current =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        onCloseRef.current()
        return
      }
      if (event.key !== 'Tab') {
        return
      }
      const root = dialogRef.current
      if (root === null) {
        return
      }
      const focusables = Array.from(root.querySelectorAll<HTMLElement>(FOCUSABLE_SELECTOR))
      const [first] = focusables
      const last = focusables.at(-1)
      if (first === undefined || last === undefined) {
        event.preventDefault()
        root.focus()
        return
      }
      const active = document.activeElement
      const inside = root.contains(active)
      if (event.shiftKey && (active === first || !inside)) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && (active === last || !inside)) {
        event.preventDefault()
        first.focus()
      }
    }
    document.addEventListener('keydown', onKeyDown)
    return () => {
      document.removeEventListener('keydown', onKeyDown)
      initiatorRef.current?.focus()
      initiatorRef.current = null
    }
  }, [open])

  // Вход фокуса в форму: при открытии и при каждом переключении formId —
  // первый фокусируемый элемент новой формы (как openXxx() прототипа).
  useEffect(() => {
    if (!open) {
      return
    }
    const root = dialogRef.current
    if (root === null) {
      return
    }
    const first = root.querySelector<HTMLElement>(FOCUSABLE_SELECTOR)
    ;(first ?? root).focus()
  }, [open, formId])

  // Закрытие по фону «press+release» (ui-behavior §3): нативные слушатели
  // на самой подложке (JSX-хендлеры на статичном div — S6848). Учитывается
  // только левая кнопка, нажатая И отпущенная на самой подложке —
  // перетаскивание из окна/в окно не закрывает.
  useEffect(() => {
    const back = backRef.current
    if (back === null) {
      return
    }
    const onMouseDown = (event: MouseEvent): void => {
      pressOnBackRef.current = event.button === 0 && event.target === event.currentTarget
    }
    const onMouseUp = (event: MouseEvent): void => {
      const releasedOnBack = event.button === 0 && event.target === event.currentTarget
      if (pressOnBackRef.current && releasedOnBack) {
        onCloseRef.current()
      }
      pressOnBackRef.current = false
    }
    back.addEventListener('mousedown', onMouseDown)
    back.addEventListener('mouseup', onMouseUp)
    return () => {
      back.removeEventListener('mousedown', onMouseDown)
      back.removeEventListener('mouseup', onMouseUp)
    }
  }, [])

  return (
    <div
      ref={backRef}
      className={open ? 'modal-back show' : 'modal-back'}
      aria-hidden={open ? undefined : true}
    >
      <dialog
        open
        className="modal panel"
        aria-modal="true"
        aria-labelledby={titleId}
        tabIndex={-1}
        ref={dialogRef}
      >
        {open && (
          <>
            <div className="modal-title" id={titleId}>
              {title}
            </div>
            {children}
          </>
        )}
      </dialog>
    </div>
  )
}
