/**
 * Кнопка-«шестерёнка» меню чата «Aethergram» (feature 008, US4; план T054;
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
 * T049 (контракт-заглушка по паттерну T028): интерфейс закреплён тестами
 * ChatGearMenu.test.tsx (TDD-красные до реализации T054) — рендер
 * появляется в T054.
 */
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
  /** «Удалить чат» (подтверждение) — №14 страницы. */
  readonly onDeleteChat?: () => void
  /** группа: «Участники» → форма members ModalShell (T055). */
  readonly onOpenMembers?: () => void
  /** группа owner/admin: «Редактировать чат» → форма group-edit (T056). */
  readonly onOpenEdit?: () => void
  /** группа member: «Выйти из чата» (danger, подтверждение) — №33 страницы. */
  readonly onLeaveChat?: () => void
}

export const ChatGearMenu: (props: ChatGearMenuProps) => null = () => null
