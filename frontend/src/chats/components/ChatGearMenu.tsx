/**
 * Кнопка-«шестерёнка» меню чата «Aethergram» (feature 008, US4, T054;
 * FR-016/FR-023, ui-behavior §4, data-model 1.5): единственная кнопка
 * заголовка окна чата (прототип #btnGear, title «Настройки чата»),
 * открывает ContextMenu (align 'end' — прижато к правому краю кнопки,
 * формулы openChatMenu прототипа) с пунктами по типу/роли чата:
 * direct — «Добавить в контакты» (если собеседник вне адресной книги),
 * «Заблокировать/Разблокировать контакт» (подтверждение — владелец
 * страница), «Удалить чат» (danger, подтверждение); группа owner/admin —
 * «Участники», «Редактировать чат», «Удалить чат» (danger); member —
 * «Участники», «Выйти из чата» (danger). Закрытие: повторный клик,
 * клик-вне, Esc, выбор пункта — слушатели ContextMenu.
 *
 * Меню — только вход (T049): №21/№23/№24/№14/№33/№30 и их подтверждения
 * (ConfirmDialog поверх оболочки, T034) принадлежат странице; каждая
 * ветка §4 закреплена своим колбэком. Клавиатура (FR-035) — стрелки/
 * Enter/Space и возврат фокуса на инициатора внутри ContextMenu.
 */
import { useState, type MouseEvent as ReactMouseEvent } from 'react'
import { ContextMenu, type MenuItem } from '../../ui/ContextMenu'
import type { GroupMember } from '../../api/groups'

/** Меню «шестерёнки» одного чата — варианты по типу (ui-behavior §4). */
export type GearMenuChat =
  | {
      readonly kind: 'direct'
      /** Собеседник уже в адресной книге (№20) — прячет «Добавить в контакты». */
      readonly peerInContacts: boolean
      /** Текущее состояние блокировки — выбор метки «Заблокировать/Разблокировать». */
      readonly blockedByMe: boolean
    }
  | {
      readonly kind: 'group'
      /** №28 myRole — матрица пунктов 006 (owner/admin: редактирование+удаление). */
      readonly myRole: GroupMember['role']
    }

export interface ChatGearMenuProps {
  /** Открытый чат заголовка — управляет набором пунктов (ui-behavior §4). */
  readonly chat: GearMenuChat
  /** direct: «Добавить в контакты» — №21 принадлежит странице. */
  readonly onAddContact?: () => void
  /** direct: «Заблокировать/Разблокировать контакт» — №23/№24 страницы. */
  readonly onToggleBlock?: () => void
  /** «Удалить чат» (подтверждение) — direct №14 / группа №30 страницы. */
  readonly onDeleteChat?: () => void
  /** группа: «Участники» → форма members ModalShell (T055). */
  readonly onOpenMembers?: () => void
  /** группа owner/admin: «Редактировать чат» → форма group-edit (T056). */
  readonly onOpenEdit?: () => void
  /** группа member: «Выйти из чата» (danger, подтверждение) — №33 страницы. */
  readonly onLeaveChat?: () => void
}

/** Пункты #chatMenu прототипа (openChatMenu) — набор по типу/роли чата. */
function gearItemsOf(
  chat: GearMenuChat,
  props: Omit<ChatGearMenuProps, 'chat'>,
): readonly MenuItem[] {
  if (chat.kind === 'direct') {
    return [
      // Участник вне книги — предлагаем добавить (по username-снимку peer).
      ...(chat.peerInContacts
        ? []
        : [
            {
              label: 'Добавить в контакты',
              onSelect: () => {
                props.onAddContact?.()
              },
            },
          ]),
      {
        label: chat.blockedByMe ? 'Разблокировать контакт' : 'Заблокировать контакт',
        onSelect: () => {
          props.onToggleBlock?.()
        },
      },
      { label: 'Удалить чат', danger: true, onSelect: () => props.onDeleteChat?.() },
    ]
  }
  const manageItems: readonly MenuItem[] = [
    {
      label: 'Участники',
      onSelect: () => {
        props.onOpenMembers?.()
      },
    },
    ...(chat.myRole === 'member'
      ? [
          {
            label: 'Выйти из чата',
            danger: true,
            onSelect: () => {
              props.onLeaveChat?.()
            },
          },
        ]
      : [
          {
            label: 'Редактировать чат',
            onSelect: () => {
              props.onOpenEdit?.()
            },
          },
          {
            label: 'Удалить чат',
            danger: true,
            onSelect: () => {
              props.onDeleteChat?.()
            },
          },
        ]),
  ]
  return manageItems
}

export function ChatGearMenu({
  chat,
  onAddContact,
  onToggleBlock,
  onDeleteChat,
  onOpenMembers,
  onOpenEdit,
  onLeaveChat,
}: ChatGearMenuProps) {
  const [anchor, setAnchor] = useState<DOMRect | null>(null)
  const [open, setOpen] = useState(false)

  const items = gearItemsOf(chat, {
    onAddContact,
    onToggleBlock,
    onDeleteChat,
    onOpenMembers,
    onOpenEdit,
    onLeaveChat,
  })

  /** openChatMenu прототипа: якорь перевычисляется на каждый клик;
   *  повторный клик по кнопке при открытом меню закрывает его клик-вне
   *  слушатель ContextMenu (кнопка — не часть меню). */
  const handleGearClick = (event: ReactMouseEvent<HTMLButtonElement>): void => {
    setAnchor(event.currentTarget.getBoundingClientRect())
    setOpen(true)
  }

  return (
    <>
      <button
        type="button"
        className="ch-btn"
        title="Настройки чата"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={handleGearClick}
      >
        <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
          <path
            strokeLinejoin="round"
            d="M19 10.4 21.4 10.3A9.5 9.5 0 0 1 21.4 13.7L19 13.6A7.2 7.2 0 0 1 18.1 15.8L19.8 17.4A9.5 9.5 0 0 1 17.4 19.8L15.8 18.1A7.2 7.2 0 0 1 13.6 19L13.7 21.4A9.5 9.5 0 0 1 10.3 21.4L10.4 19A7.2 7.2 0 0 1 8.2 18.1L6.6 19.8A9.5 9.5 0 0 1 4.2 17.4L5.9 15.8A7.2 7.2 0 0 1 5 13.6L2.6 13.7A9.5 9.5 0 0 1 2.6 10.3L5 10.4A7.2 7.2 0 0 1 5.9 8.2L4.2 6.6A9.5 9.5 0 0 1 6.6 4.2L8.2 5.9A7.2 7.2 0 0 1 10.4 5L10.3 2.6A9.5 9.5 0 0 1 13.7 2.6L13.6 5A7.2 7.2 0 0 1 15.8 5.9L17.4 4.2A9.5 9.5 0 0 1 19.8 6.6L18.1 8.2A7.2 7.2 0 0 1 19 10.4Z"
          />
          <circle cx="12" cy="12" r="3.2" />
        </svg>
      </button>
      <ContextMenu
        open={open}
        anchor={open ? anchor : null}
        items={items}
        align="end"
        onClose={() => {
          setOpen(false)
        }}
      />
    </>
  )
}
