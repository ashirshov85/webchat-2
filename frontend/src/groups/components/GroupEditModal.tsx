/**
 * Модальная форма «Редактировать групповой чат» (feature 008, US4; план T056;
 * FR-023, ui-behavior §3, research §D): житель ЕДИНОЙ оболочки ModalShell
 * (MessengerPage, formId 'group-edit'), проекция #grpEditForm нормативного
 * прототипа specs/008-chat-window-styling/design/chats.html — название,
 * описание, состав: черновик ростера №28 (минус собственная строка — уход
 * это «Выйти из чата» шестерёнки) с удалением «✕» и добавлением из №20
 * «+» (активные не предлагаются), валидация 006 (≥1 участник, title 1–64,
 * description ≤256; порядок прототипа — участники, затем название).
 * Submit — №29 `updateGroup` (обрезанный title, описание только непустое)
 * + №31 `addMembers` (батч добавленных) + №32 `kickMember` (по каждому
 * удалённому); успех — ровно один тост «Групповой чат обновлён — {title}»
 * (T045: тост выдаёт владелец операции-формы), №20 грузится на монтировании
 * (сбой — .modal-err + «Повторить», ожидание 004).
 *
 * T049 (контракт-заглушка по паттерну T028): интерфейс закреплён тестами
 * GroupEditModal.test.tsx (TDD-красные до реализации T056) — рендер
 * появляется в T056.
 */
import type { GroupMember, GroupView } from '../../api/groups'

export interface GroupEditModalProps {
  /** The group chatId — the №29/№31/№32 target. */
  readonly chatId: string
  /** №28 `title` — the form opens prefilled with it. */
  readonly title: string
  /** №28 `description` — the form opens prefilled with it ('' when null). */
  readonly description: string | null
  /** №28 active roster — the composition draft base (minus the own row). */
  readonly members: readonly GroupMember[]
  /** №29 success: the updated GroupView — the parent converges №28/closes. */
  readonly onUpdated?: (group: GroupView) => void
  /** №31 success: the returned members — the parent converges №28 (№31-response). */
  readonly onMembersAdded?: (members: readonly GroupMember[]) => void
  /** №32 success — the parent converges its №28 state (void response). */
  readonly onMemberRemoved?: () => void
  /** Optional close control for the parent-managed shell lifetime. */
  readonly onCancel?: () => void
}

export const GroupEditModal: (props: GroupEditModalProps) => null = () => null
