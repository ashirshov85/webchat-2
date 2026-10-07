/**
 * The open dialog header (feature 008, US1 T021 → US3 T043; FR-016,
 * FR-017, data-model 2.3): the `.chat-head` block of the prototype
 * specs/008-chat-window-styling/design/chats.html §7 — `.head-av`
 * avatar + `.chat-title` (`.chat-name` + `.status-row`) + the
 * right-side controls, styled by chat-header.css (FR-001: a verbatim
 * projection; the prototype wins on conflicts). The name keeps the
 * 004 hook `dialog-title` on its h2 (research §C, FR-034).
 *
 * The status row is the FULL US3 projection (T043):
 *  * direct — the prototype presence-лампа `.lamp` + `.status-txt`
 *    label «В сети»/«офлайн» driven by the 007 presenceStore through
 *    `usePresenceStatus(peerId)` (the T040 contract: `.off` ONLY for a
 *    true store offline — a missing №36 snapshot and №36 `unknown`
 *    never yield a false «офлайн»); bug 12 (T090): `unknown` renders
 *    NO indicator at all — the empty `.status-row` keeps the height
 *    (chat-header.css min-height) until №36 converges; the blocker-side
 *    «заблокирован» mark follows (FR-020);
 *  * group — «N участников» with the prototype pluralRu; hover/focus
 *    of the status row opens the `.members-tip` roster (FR-017):
 *    «Вы» first (mark «администратор» when `myRole` is owner), then
 *    the live №28 members minus me with the mark for owner/admin
 *    roles. The tip is portaled to document.body — the prototype's
 *    body-level `#membersTip` (fixed, z-56, pointer-events: none) —
 *    positioned by the prototype clamp formulas against the status
 *    row rect and closed by mouseleave/blur/Esc; Esc is captured as
 *    the top layer of data-model 3.1 so it never falls through to a
 *    modal/drawer below. While №28 is not live (the №12 basis only,
 *    `members === null`) the row is no tip source — not focusable,
 *    hover is a no-op. The prototype's own-row presence dot
 *    (mePresenceDot) is not carried: data-model 2.3 derives the tip
 *    from members + myRole only.
 *
 * US4 (T054): the ONLY right-side control is the «шестерёнка»
 * ChatGearMenu (ui-behavior §4) — direct: №21 add / №23-24 block
 * toggle / №14 delete (подтверждения — у страницы через форму confirm
 * оболочки T034); group: the №28 myRole matrix of 006 («Участники» /
 * «Редактировать чат» / «Удалить чат» vs «Выйти из чата»). While the
 * role is unknown (no live №28 and no basis) the group header carries
 * no gear — the menu matrix needs the role. The prototype search/sound
 * buttons never join the header (FR-016: 013/008a are out of scope).
 *
 * The avatar derives from the peer username (circle) or the group
 * title (octagon, FR-024) via ui/Avatar and recalculates on renames;
 * the title itself is resolved by the page — the live №28 title
 * while the view is open (the optimistic `group.updated` half), the
 * №12/№27 answer until then.
 */
import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from 'react'
import { createPortal } from 'react-dom'
import type { GroupMember } from '../../api/groups'
import { Avatar } from '../../ui/Avatar'
import { pluralRu } from '../../ui/time'
import { usePresenceStatus } from '../../presence/usePresence'
import type { PresenceStatus } from '../../presence/presenceStore'
import { ChatGearMenu } from './ChatGearMenu'
import type { GearMenuChat } from './ChatGearMenu'
import './chat-header.css'

/** Header avatar size — design-tokens §4 (заголовок 46px). */
const HEADER_AVATAR_SIZE = 46

/** Members-tip avatar size — prototype `.mt-row .avatar` (24px). */
const TIP_AVATAR_SIZE = 24

/** Поле clamp-формул прототипа (showMembersTip: 8px). */
const TIP_VIEWPORT_MARGIN = 8

/** Отступ подсказки от низа якоря (прототип: r.bottom + 6). */
const TIP_ANCHOR_GAP = 6

/** The direct dialog of the header (the 004 pair dialog surface). */
export interface DirectChatHeaderData {
  /** The peer of the 1:1 dialog — the presence surface userId (007). */
  readonly peerId: string
  readonly username: string
  /** The blocker-side mark «заблокирован» (№12 projection, FR-020). */
  readonly blockedByMe: boolean
  /** №20 membership of the peer — the gear's «Добавить в контакты» visibility (T054). */
  readonly peerInContacts: boolean
}

/** The group window of the header (feature 006). */
export interface GroupChatHeaderData {
  /** Resolved by the page: the live №28 title or the №12/№27 answer. */
  readonly title: string
  /** Live №28 roster length or the №12/№13/№27 memberCount; null — unknown. */
  readonly memberCount: number | null
  /**
   * The live №28 roster — the members-tip source (US3 T043, FR-017);
   * null while №28 is not live (the №12 basis carries no names) —
   * the status row is no tip source until then.
   */
  readonly members: readonly GroupMember[] | null
  /**
   * The viewer's role — the live №28 myRole while the view is open,
   * the №12/№13/№27 basis until then (T054): the «администратор» mark
   * of the «Вы» tip row (only rendered when №28 is live) AND the gear
   * menu matrix of 006 (ui-behavior §4); null — no basis at all, the
   * group header carries no gear.
   */
  readonly myRole: GroupMember['role'] | null
  /** The viewer's user id — filters the own №28 row out of the tip. */
  readonly meUserId: string | null
  /** The viewer's username — the «Вы» avatar derivation source. */
  readonly meUsername: string | null
}

/** One header, two variants — the page discriminates by `kind`. */
export type ChatHeaderChat =
  | ({ readonly kind: 'direct' } & DirectChatHeaderData)
  | ({ readonly kind: 'group' } & GroupChatHeaderData)

export interface ChatHeaderProps {
  /** The open dialog of either kind — drives avatar shape and status. */
  readonly chat: ChatHeaderChat
  /** direct: «Добавить в контакты» — №21 belongs to the page (T054). */
  readonly onAddContact?: () => void
  /** direct: «Заблокировать/Разблокировать контакт» — №23/№24 of the page. */
  readonly onToggleBlock?: () => void
  /** «Удалить чат» (подтверждение): direct №14 / group №30 — the page's. */
  readonly onDeleteChat?: () => void
  /** group: «Участники» → the members form of the shell (T055/T057). */
  readonly onOpenMembers?: () => void
  /** group owner/admin: «Редактировать чат» → group-edit of the shell (T056/T057). */
  readonly onOpenEdit?: () => void
  /** group member: «Выйти из чата» (danger, подтверждение) — №33 of the page. */
  readonly onLeaveChat?: () => void
}

/** «N участников» — the prototype membersCount (§7 chatView, ui/time pluralRu). */
function membersStatus(count: number): string {
  return `${count} ${pluralRu(count, 'участник', 'участника', 'участников')}`
}

/** `.status-txt` of the direct row (T040 «В сети»/«офлайн»; bug 12/T090:
 *  «неизвестно» не выводится — unknown рендерит пустую строку статуса). */
const DIRECT_STATUS_TEXT: Readonly<Record<'online' | 'offline', string>> = {
  online: 'В сети',
  offline: 'офлайн',
}

/** The tip rows (FR-017): the live №28 members minus me ([] until №28 is live). */
function tipMembersOf(chat: ChatHeaderChat): readonly GroupMember[] {
  if (chat.kind !== 'group' || chat.members === null) {
    return []
  }
  return chat.members.filter((member) => member.user.id !== chat.meUserId)
}

interface DirectStatusRowProps {
  /** The merged 007 status of the peer (T040). */
  readonly presence: PresenceStatus
  /** The blocker-side mark «заблокирован» (№12 projection, FR-020). */
  readonly blockedByMe: boolean
}

/**
 * The direct `.status-row` — the prototype presence lamp + label (US3
 * T043). Bug 12 (T090): the neutral «unknown» is NOT displayed — no
 * lamp, no `.status-txt` — only the empty row stays (its min-height in
 * chat-header.css holds the header height) so the header does not jump
 * when №36 converges; the «заблокирован» mark (FR-020) is not a
 * presence indicator and stays.
 */
function DirectStatusRow({ presence, blockedByMe }: DirectStatusRowProps) {
  if (presence === 'unknown') {
    return (
      <div className="status-row">
        {blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
      </div>
    )
  }
  return (
    <div className="status-row">
      <span className={presence === 'offline' ? 'lamp off' : 'lamp'} aria-hidden="true" />
      <span className="status-txt">{DIRECT_STATUS_TEXT[presence]}</span>
      {blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
    </div>
  )
}

interface GroupStatusRowProps {
  readonly chat: GroupChatHeaderData
  /** №28 live — the row is a focusable tip source; the №12 basis is inert. */
  readonly rosterLive: boolean
  readonly statusRowRef: RefObject<HTMLButtonElement | null>
  readonly openTip: () => void
  readonly closeTip: () => void
}

/**
 * The group `.status-row` — «N участников», the members-tip anchor
 * (US3 T043, FR-017): while №28 is live the row is a native `<button>`
 * — the focusable tip source (FR-035); until then a plain div — no
 * tip source, not focusable, hover a no-op. null — no memberCount yet.
 */
function GroupStatusRow({
  chat,
  rosterLive,
  statusRowRef,
  openTip,
  closeTip,
}: GroupStatusRowProps) {
  if (chat.memberCount === null) {
    return null
  }
  if (!rosterLive) {
    return (
      <div className="status-row">
        <span className="status-txt">{membersStatus(chat.memberCount)}</span>
      </div>
    )
  }
  return (
    <button
      type="button"
      className="status-row tip-source"
      ref={statusRowRef}
      tabIndex={0}
      onMouseEnter={openTip}
      onMouseLeave={closeTip}
      onFocus={openTip}
      onBlur={closeTip}
    >
      <span className="status-txt">{membersStatus(chat.memberCount)}</span>
    </button>
  )
}

interface MembersTipProps {
  /** The «Вы» avatar derivation source. */
  readonly meUsername: string | null
  /** The viewer's №28 role — the «администратор» mark of the «Вы» row. */
  readonly myRole: GroupMember['role'] | null
  /** The live №28 members minus me (FR-017). */
  readonly members: readonly GroupMember[]
  readonly tipRef: RefObject<HTMLDivElement | null>
}

/** The `.members-tip` roster body (FR-017): «Вы» first, then №28 minus me. */
function MembersTip({ meUsername, myRole, members, tipRef }: MembersTipProps) {
  return (
    <div className="members-tip show" role="tooltip" ref={tipRef}>
      <div className="mt-title">Участники</div>
      <div className="mt-row">
        <Avatar source={meUsername ?? ''} size={TIP_AVATAR_SIZE} />
        <span className="mt-name">Вы</span>
        {myRole === 'owner' && <span className="mt-me">администратор</span>}
      </div>
      {members.map((member) => (
        <div className="mt-row" key={member.user.id}>
          <Avatar source={member.user.username} size={TIP_AVATAR_SIZE} />
          <span className="mt-name">{member.user.username}</span>
          {(member.role === 'owner' || member.role === 'admin') && (
            <span className="mt-me">администратор</span>
          )}
        </div>
      ))}
    </div>
  )
}

/**
 * The gear menu data of the header chat (T054): direct — the №20
 * membership of the peer + the block mark; group — the resolved role
 * (live №28, else the №12/№13/№27 basis); null — no basis yet, no
 * gear in the header.
 */
function gearChatOf(chat: ChatHeaderChat): GearMenuChat | null {
  if (chat.kind !== 'group') {
    return {
      kind: 'direct',
      peerInContacts: chat.peerInContacts,
      blockedByMe: chat.blockedByMe,
    }
  }
  return chat.myRole !== null ? { kind: 'group', myRole: chat.myRole } : null
}

export function ChatHeader({
  chat,
  onAddContact,
  onToggleBlock,
  onDeleteChat,
  onOpenMembers,
  onOpenEdit,
  onLeaveChat,
}: ChatHeaderProps) {
  const isGroup = chat.kind === 'group'
  const name = isGroup ? chat.title : chat.username
  const gearChat = gearChatOf(chat)

  // The 007 surface of the open 1:1 dialog (T040): the status comes
  // ONLY from the presenceStore — a group header registers nothing.
  const presence = usePresenceStatus(!isGroup ? chat.peerId : null)

  // The members-tip anchor (the ContextMenu pattern): the status row
  // rect captured at open — null keeps the tip out of the DOM.
  const [tipAnchor, setTipAnchor] = useState<DOMRect | null>(null)
  const statusRowRef = useRef<HTMLButtonElement>(null)
  const tipRef = useRef<HTMLDivElement>(null)
  const closeTip = (): void => {
    setTipAnchor(null)
  }

  const rosterLive = isGroup && chat.members !== null
  const tipOpen = rosterLive && tipAnchor !== null

  const openTip = (): void => {
    if (!rosterLive) {
      return
    }
    const rect = statusRowRef.current?.getBoundingClientRect()
    if (rect !== undefined) {
      setTipAnchor(rect)
    }
  }

  // The tip rows (FR-017): «Вы» first, then the №28 members minus me.
  const tipMembers = tipMembersOf(chat)

  // Prototype clamp formulas (showMembersTip): left — no further than
  // the viewport edge less the measured width, top — under the anchor.
  // Re-clamped when the roster lands/grows under an open hover so the
  // growth never pushes the tip past the bottom edge.
  useLayoutEffect(() => {
    const tip = tipRef.current
    if (tipAnchor === null || tip === null) {
      return
    }
    const left = Math.max(
      TIP_VIEWPORT_MARGIN,
      Math.min(tipAnchor.left, window.innerWidth - tip.offsetWidth - TIP_VIEWPORT_MARGIN),
    )
    const top = Math.min(
      tipAnchor.bottom + TIP_ANCHOR_GAP,
      window.innerHeight - tip.offsetHeight - TIP_VIEWPORT_MARGIN,
    )
    tip.style.left = `${left}px`
    tip.style.top = `${top}px`
  }, [tipAnchor, tipMembers])

  // Esc closes the tip as the TOP layer (data-model 3.1: ctx-menu/
  // members-tip → modal → drawer) — capture + stopPropagation keep it
  // from falling through to a modal or the drawer below.
  useEffect(() => {
    if (!tipOpen) {
      return
    }
    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.stopPropagation()
        closeTip()
      }
    }
    document.addEventListener('keydown', onKeyDown, true)
    return () => {
      document.removeEventListener('keydown', onKeyDown, true)
    }
  }, [tipOpen])

  return (
    <header className="chat-head">
      <div className="head-av">
        <Avatar source={name} shape={isGroup ? 'octagon' : 'circle'} size={HEADER_AVATAR_SIZE} />
      </div>
      <div className="chat-title">
        <h2 className="chat-name dialog-title" title={name}>
          {name}
        </h2>
        {isGroup ? (
          <GroupStatusRow
            chat={chat}
            rosterLive={rosterLive}
            statusRowRef={statusRowRef}
            openTip={openTip}
            closeTip={closeTip}
          />
        ) : (
          <DirectStatusRow presence={presence} blockedByMe={chat.blockedByMe} />
        )}
      </div>
      {/* Единственная кнопка заголовка — «шестерёнка» (FR-016, T054):
           direct — всегда; group — с известной ролью (живой №28 или
           базис №12/№13/№27) — без роли матрица пунктов 006 не определена. */}
      {gearChat !== null && (
        <div className="ch-btns">
          <ChatGearMenu
            chat={gearChat}
            onAddContact={onAddContact}
            onToggleBlock={onToggleBlock}
            onDeleteChat={onDeleteChat}
            onOpenMembers={onOpenMembers}
            onOpenEdit={onOpenEdit}
            onLeaveChat={onLeaveChat}
          />
        </div>
      )}
      {/* Подсказка состава (FR-017) — портал в body, как #membersTip
          прототипа: fixed-позиционирование относительно вьюпорта,
          поверх машины (z-56), pointer-events: none — чистый ховер. */}
      {tipOpen &&
        isGroup &&
        chat.members !== null &&
        createPortal(
          <MembersTip
            meUsername={chat.meUsername}
            myRole={chat.myRole}
            members={tipMembers}
            tipRef={tipRef}
          />,
          document.body,
        )}
    </header>
  )
}
