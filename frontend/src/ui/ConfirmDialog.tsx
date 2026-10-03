/**
 * Подтверждение «Aethergram» (feature 008, T014; FR-014, SC-007, ui-behavior
 * §3, data-model 1.6): форма-обитатель 'confirm' ЕДИНОЙ модальной оболочки
 * ModalShell (T012) — проекция confirmForm прототипа
 * specs/008-chat-window-styling/design/chats.html: `.confirm-text` (текст с
 * `<b>`-выделением имени) + `.modal-btns` с кнопками `.m-btn` («Отмена») и
 * `.m-btn.primary[.danger]` (действие). Заголовок рендерит сама оболочка
 * (ModalShell title) — поэтому одна подложка на приложение и при askConfirm
 * поверх открытой формы (переключением formId, без второй подложки).
 * 100% деструктивных операций проходят через это подтверждение (SC-007):
 * опасное действие — danger-кнопка. Текст — ReactNode, без
 * dangerouslySetInnerHTML (FR-033): выделение имени — JSX `<b>`, подпись
 * «username · email» — `<span className="confirm-sub">` прототипа. Отмена —
 * кнопкой «Отмена» и путями оболочки (Esc, фон press+release — onClose
 * ModalShell, потребитель сводит к отмене); подтверждение — submit формы
 * (кнопка type=submit, как confirmForm прототипа).
 */
import { type FormEvent, type ReactNode } from 'react'
import './confirm-dialog.css'

/** Вариант кнопки действия (ui-behavior §3): обычная / основная / опасная. */
export type ConfirmVariant = 'default' | 'primary' | 'danger'

export interface ConfirmDialogProps {
  /** Текст подтверждения: `<b>` выделяет имя (ReactNode — без innerHTML, FR-033). */
  readonly text: ReactNode
  /** Надпись кнопки действия; по умолчанию «Подтвердить» (opt.ok прототипа). */
  readonly confirmLabel?: string
  /** Вариант кнопки действия; по умолчанию основная (золотая .m-btn.primary). */
  readonly variant?: ConfirmVariant
  /** Подтверждённое действие — submit формы (cb askConfirm прототипа). */
  readonly onConfirm: () => void
  /** Отмена: кнопка «Отмена»/Esc/фон — действие НЕ выполняется (SC-007). */
  readonly onCancel: () => void
}

/** Класс кнопки действия по варианту (прототип: .m-btn.primary + .danger). */
const CONFIRM_BUTTON_CLASS: Record<ConfirmVariant, string> = {
  default: 'm-btn',
  primary: 'm-btn primary',
  danger: 'm-btn primary danger',
}

export function ConfirmDialog({
  text,
  confirmLabel,
  variant = 'primary',
  onConfirm,
  onCancel,
}: ConfirmDialogProps) {
  const handleSubmit = (event: FormEvent<HTMLFormElement>): void => {
    event.preventDefault()
    onConfirm()
  }

  return (
    <form className="confirm-form" onSubmit={handleSubmit}>
      <div className="confirm-text">{text}</div>
      <div className="modal-btns">
        <button type="button" className="m-btn" onClick={onCancel}>
          Отмена
        </button>
        <button type="submit" className={CONFIRM_BUTTON_CLASS[variant]}>
          {confirmLabel ?? 'Подтвердить'}
        </button>
      </div>
    </form>
  )
}
