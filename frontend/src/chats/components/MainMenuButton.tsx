/**
 * Кнопка «три полоски» главного меню «Aethergram» (feature 008, US2, T030;
 * FR-007, ui-behavior §2, data-model 1.5): стоит в строке поиска сайдбара
 * СЛЕВА от поля (design/chats.html §5, `.search-row > .menu-btn` #menuBtn)
 * и открывает ContextMenu с пунктами toggleMainMenu прототипа — «Мой
 * профиль», «Контакты», «Создать групповой чат». Каждый пункт открывает
 * свою форму ЕДИНОЙ модальной оболочки MessengerPage (T034) через
 * колбэки; кнопка и панель — только вход, логики форм здесь нет.
 *
 * Закрытие (FR-007): повторный клик по кнопке, клик-вне и Esc — документные
 * слушатели ContextMenu (клик по якорю — тоже «вне» меню, как
 * `!e.target.closest('#menuBtn')` прототипа); якорь — rect кнопки на
 * момент клика, выравнивание start (формулы openMainMenu: r.left /
 * r.bottom + 6, clamp 8px). Клавиатура (FR-035): Tab до кнопки,
 * Enter/Space — нативная активация, стрелки/Enter/Space и возврат фокуса
 * на инициатора — внутри ContextMenu.
 */
import { useState, type MouseEvent as ReactMouseEvent } from 'react'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'

export interface MainMenuButtonProps {
  /** «Мой профиль» → форма profile ModalShell (T033/T034). */
  readonly onOpenProfile?: () => void
  /** «Контакты» → форма contacts/add-contact (T031/T032). */
  readonly onOpenContacts?: () => void
  /** «Создать групповой чат» → форма create-group (T035). */
  readonly onCreateGroup?: () => void
}

export function MainMenuButton({
  onOpenProfile,
  onOpenContacts,
  onCreateGroup,
}: MainMenuButtonProps) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const [open, setOpen] = useState(false)

  const items: readonly MenuItem[] = [
    {
      label: 'Мой профиль',
      onSelect: () => {
        onOpenProfile?.()
      },
    },
    {
      label: 'Контакты',
      onSelect: () => {
        onOpenContacts?.()
      },
    },
    {
      label: 'Создать групповой чат',
      onSelect: () => {
        onCreateGroup?.()
      },
    },
  ]

  /** toggleMainMenu прототипа: якорь перевычисляется на каждый клик;
   *  повторный клик по кнопке при открытом меню закрывает его клик-вне
   *  слушатель ContextMenu (кнопка — не часть меню). */
  const handleMenuButtonClick = (event: ReactMouseEvent<HTMLButtonElement>): void => {
    setAnchor(event.currentTarget.getBoundingClientRect())
    setOpen(true)
  }

  return (
    <>
      <button
        type="button"
        className="menu-btn"
        title="Меню"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={handleMenuButtonClick}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path d="M4 6h16M4 12h16M4 18h16" />
        </svg>
      </button>
      <ContextMenu
        open={open}
        anchor={open ? anchor : null}
        items={items}
        onClose={() => {
          setOpen(false)
        }}
      />
    </>
  )
}
