/**
 * Модальная форма «Участники» группового чата (feature 008, US4; план T055;
 * FR-023, ui-behavior §3, research §D): житель ЕДИНОЙ оболочки ModalShell
 * (MessengerPage, formId 'members'), проекция #membersForm нормативного
 * прототипа specs/008-chat-window-styling/design/chats.html — ростер №28
 * минус собственная строка («Вы» не выводится, как renderMembersList
 * прототипа), метки ролей 006 и ростер-действия №32/№34/№35 строго по
 * иерархии myRole (наследие MemberList, миграция T049), «Добавить в
 * контакты» для участника вне адресной книги вызывающего.
 *
 * T049 (контракт-заглушка по паттерну T028): интерфейс закреплён тестами
 * GroupMembersModal.test.tsx (TDD-красные до реализации T055) — рендер
 * появляется в T055.
 */
import type { GroupMember } from '../../api/groups'

export interface GroupMembersModalProps {
  /** №28 active roster (server-owned order, ≤200 — FR-013). */
  readonly members: readonly GroupMember[]
  /** The viewer's role in the group — gates the row actions (FR-004). */
  readonly myRole: GroupMember['role']
  /** The viewer's user id — the own row is NOT rendered (ui-behavior §3). */
  readonly currentUserId: string
  /** №32 kick (owner: admins+members; admin: members only). */
  readonly onKick: (userId: string) => void
  /** №34 grant/revoke admin (owner-only). */
  readonly onSetRole: (userId: string, role: 'admin' | 'member') => void
  /** №35 transfer ownership (owner-only, never to self). */
  readonly onTransferOwnership: (userId: string) => void
  /** The member with an in-flight roster action (useGroupMembers mutex view). */
  readonly pendingUserId?: string | null
  /** The last roster-action problem (useGroupMembers `error`). */
  readonly rosterError?: unknown
  /** User ids already in the adder's address book (№20) — hides the offer. */
  readonly contactUserIds?: ReadonlySet<string>
  /** «Добавить в контакты» — the parent owns №21 and its toast (T055/T057). */
  readonly onAddContact?: (userId: string) => void
}

export const GroupMembersModal: (props: GroupMembersModalProps) => null = () => null
