/**
 * Контекстное меню «Aethergram» (feature 008, T013; FR-027, FR-035, data-model
 * 1.5, ui-behavior §1): единый примитив якорных меню — главное меню (FR-007),
 * контактное «⋯» (FR-013), меню чата «шестерёнка» (FR-023). DOM и классы —
 * дословно из прототипа specs/008-chat-window-styling/design/chats.html:
 * `.ctx-menu.show` (fixed, z-55) с кнопками `.ctx-item[.danger]` и
 * разделителями `.ctx-sep`; стили — ctx-menu.css на токенах --z-ctx-menu /
 * --r-menu / --font-body (FR-002). Открытое меню рендерится ПОРТАЛОМ в
 * document.body (T081, прецедент members-tip ChatHeader): .panel с
 * backdrop-filter (machine.css) создаёт containing block для fixed-потомков
 * — внутри сайдбара координаты вьюпорта ложатся на панель и переполняют
 * её (сайдбар растягивался, горизонтальный скролл); как #ctxMenu
 * прототипа — ребёнок body, fixed считается от вьюпорта. Позиционирование — формулы прототипа
 * (openCtxMenu/openMainMenu/openChatMenu): у якоря (низ + 6) и clamp в
 * пределы экрана с полями 8px; align 'start' — у левого края якоря, 'end' —
 * прижато к правому краю (меню «шестерёнки»). Закрытие: клик-вне, Esc,
 * выбор пункта — как `closeXxx(); act()` прототипа. Клавиатура (FR-035):
 * вход фокуса на первый пункт, ArrowUp/Down по кругу, Enter/Space — активация,
 * после закрытия фокус возвращается на инициатора. Esc слушается в capture:
 * меню — верхний слой (data-model 3.1), Esc не проваливается в модаль/drawer.
 */
import {
  Fragment,
  useEffect,
  useLayoutEffect,
  useRef,
  type KeyboardEvent as ReactKeyboardEvent,
} from 'react'
import { createPortal } from 'react-dom'
import './ctx-menu.css'

/** Пункт меню (data-model 1.5): действие якорного меню. */
export interface MenuItem {
  readonly label: string
  readonly onSelect: () => void
  /** Опасное действие — визуально выделено цветом --err (прототип .danger). */
  readonly danger?: boolean
  /** Разделитель .ctx-sep перед пунктом (группы действий). */
  readonly sepBefore?: boolean
}

/** Горизонтальное выравнивание у якоря — формулы прототипа. */
export type ContextMenuAlign = 'start' | 'end'

export interface ContextMenuProps {
  /** Меню показано; false/null-якорь — в DOM ничего нет. */
  readonly open: boolean
  /** Якорь — rect кнопки меню на момент открытия (data-model 1.5). */
  readonly anchor: DOMRect | null
  readonly items: readonly MenuItem[]
  /** start — у левого края якоря (главное/контактное); end — к правому («шестерёнка»). */
  readonly align?: ContextMenuAlign
  readonly onClose: () => void
}

/** Поле между меню и краями экрана в clamp-формулах прототипа. */
const VIEWPORT_MARGIN = 8
/** Отступ меню от низа якоря (прототип: r.bottom + 6). */
const ANCHOR_GAP = 6

export function ContextMenu({ open, anchor, items, align = 'start', onClose }: ContextMenuProps) {
  const menuRef = useRef<HTMLDivElement>(null)
  /** Свежий onClose для document-слушателей (жизнь эффектов ≠ жизнь рендеров). */
  const onCloseRef = useRef(onClose)
  useEffect(() => {
    onCloseRef.current = onClose
  })

  // Позиционирование по прототипу: показать → измерить (offsetWidth/Height) →
  // поставить left/top с clamp. useLayoutEffect — до отрисовки, без прыжка.
  useLayoutEffect(() => {
    const menu = menuRef.current
    if (!open || anchor === null || menu === null) {
      return
    }
    const rawLeft = align === 'end' ? anchor.right - menu.offsetWidth : anchor.left
    const left = Math.max(
      VIEWPORT_MARGIN,
      Math.min(rawLeft, window.innerWidth - menu.offsetWidth - VIEWPORT_MARGIN),
    )
    const top = Math.min(
      anchor.bottom + ANCHOR_GAP,
      window.innerHeight - menu.offsetHeight - VIEWPORT_MARGIN,
    )
    menu.style.left = `${left}px`
    menu.style.top = `${top}px`
  }, [open, anchor, align, items])

  // Инициатор + клик-вне/Esc + вход фокуса. Слушатель клика вешается со
  // защитой от «открывающего» клика: в реальном Chromium React сбрасывает
  // пассивные эффекты ДИСКРЕТНОГО доверенного события ещё внутри его
  // доставки (до всплытия до document), поэтому без защиты слушатель
  // успевает услышать сам клик, открывший меню, и закрыть его мгновенно
  // (jsdom/fireEvent эффекты откладывает — юнит-тесты этого не видят,
  // T039 ловит в настоящем браузере). Метка времени клика строго меньше
  // момента монтирования слушателя ровно у открывающего клика: его
  // доставка началась до эффекта; каждый следующий клик — новая задача
  // с новой меткой. Esc — capture: контекстное меню верхний слой
  // (data-model 3.1), stopPropagation не пускает Esc в модаль и drawer ниже.
  useEffect(() => {
    if (!open) {
      return
    }
    const menu = menuRef.current
    const initiator = document.activeElement instanceof HTMLElement ? document.activeElement : null
    const listenerAttachedAt = performance.now()

    const onDocKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        onCloseRef.current()
      }
    }
    const onDocClick = (event: MouseEvent): void => {
      if (event.timeStamp < listenerAttachedAt) {
        return // клик, открывший меню, — не «клик-вне»
      }
      const target = event.target
      if (menu !== null && target instanceof Node && menu.contains(target)) {
        return
      }
      onCloseRef.current()
    }
    document.addEventListener('keydown', onDocKeyDown, true)
    document.addEventListener('click', onDocClick)

    // preventScroll: Chromium's focus-reveal would scroll every scrollable
    // ANCESTOR of the menu to its static position — the gear menu (T060)
    // proved it in the real browser: the fixed .ctx-menu's static box sits
    // right of the chat column, so section.chat (overflow:hidden) got
    // scrolled 380px sideways and the whole window shifted under the open
    // menu. jsdom has no layout — the unit suite never saw it. The item
    // still receives focus (FR-035); only the side-effect scroll dies.
    menu?.querySelector<HTMLButtonElement>('.ctx-item')?.focus({ preventScroll: true })

    return () => {
      document.removeEventListener('keydown', onDocKeyDown, true)
      document.removeEventListener('click', onDocClick)
      // Возврат фокуса на инициатора (FR-035) — пока фокус ещё в меню или ни
      // на чём: клик мимо уже увёл фокус на свою цель, её не перебиваем.
      const active = document.activeElement
      const focusInside = menu !== null && active instanceof Node && menu.contains(active)
      if (focusInside || active === null || active === document.body) {
        initiator?.focus()
      }
    }
  }, [open])

  /** Клавиатура меню (FR-035): стрелки — фокус по пунктам (по кругу),
   *  Enter/Space — активация сфокусированного пункта. */
  const handleKeyDown = (event: ReactKeyboardEvent<HTMLDivElement>): void => {
    if (
      event.key !== 'ArrowDown' &&
      event.key !== 'ArrowUp' &&
      event.key !== 'Enter' &&
      event.key !== ' '
    ) {
      return
    }
    const menu = menuRef.current
    if (menu === null) {
      return
    }
    if (event.key === 'Enter' || event.key === ' ') {
      const active = document.activeElement
      if (active instanceof HTMLButtonElement && menu.contains(active)) {
        event.preventDefault() // нативная активация кнопки не дублирует click
        active.click()
      }
      return
    }
    event.preventDefault()
    const buttons = Array.from(menu.querySelectorAll<HTMLButtonElement>('.ctx-item'))
    if (buttons.length === 0) {
      return
    }
    const active = document.activeElement
    const index = active instanceof HTMLButtonElement ? buttons.indexOf(active) : -1
    const step = event.key === 'ArrowDown' ? 1 : -1
    let nextIndex: number
    if (index === -1) {
      nextIndex = step === 1 ? 0 : buttons.length - 1
    } else {
      nextIndex = (index + step + buttons.length) % buttons.length
    }
    const next = buttons.at(nextIndex)
    if (next !== undefined) {
      next.focus()
    }
  }

  if (!open || anchor === null) {
    return null
  }

  // Портал в body (T081): fixed-координаты считаются от вьюпорта, а не от
  // .panel--containing block потребителя; слой z-55 выше modal-back (47) —
  // меню из открытой модали остаётся над подложкой (порядок слоёв §7).
  return createPortal(
    <div
      className="ctx-menu show"
      role="menu"
      tabIndex={-1}
      ref={menuRef}
      onKeyDown={handleKeyDown}
    >
      {items.map((item) => (
        <Fragment key={item.label}>
          {item.sepBefore === true && <hr className="ctx-sep" />}
          <button
            type="button"
            role="menuitem"
            className={item.danger === true ? 'ctx-item danger' : 'ctx-item'}
            onClick={() => {
              onClose()
              item.onSelect()
            }}
          >
            {item.label}
          </button>
        </Fragment>
      ))}
    </div>,
    document.body,
  )
}
