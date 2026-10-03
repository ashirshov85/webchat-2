/**
 * The open dialog header (feature 008, US1, T021; FR-016 base part,
 * data-model 2.3): the `.chat-head` block of the prototype
 * specs/008-chat-window-styling/design/chats.html §7 — `.head-av`
 * avatar + `.chat-title` (`.chat-name` + `.status-row`) + the
 * right-side controls, styled by chat-header.css (FR-001: a verbatim
 * projection; the prototype wins on conflicts). The name keeps the
 * 004 hook `dialog-title` on its h2 (research §C, FR-034).
 *
 * The BASIC status rides EXISTING data only (the T021 scope):
 *  * direct — the 007 PresenceIndicator with its visible label
 *    (feature 007 T022 semantics: neutral «неизвестно» before the
 *    first №36 snapshot and for №36 `unknown` — never a false
 *    «офлайн») plus the blocker-side «заблокирован» mark (FR-020);
 *  * group — «N участников» with the prototype pluralRu over the
 *    live №28 roster length or the №12/№13/№27 memberCount; the
 *    status row stays empty while the count is unknown (the
 *    prototype `hide` on an empty status).
 * The presence-лампа and the members-tip of the status row are US3
 * (T043), the «шестерёнка» ChatGearMenu is US4 (T054) — until then
 * the 004/006 controls keep their behavior: the direct «Действия»
 * menu (№14 delete + №23/№24 block toggle) and the group
 * «Информация о группе» card toggle. The prototype search/sound
 * buttons never join the header (FR-016: 013/008a are out of scope).
 *
 * The avatar derives from the peer username (circle) or the group
 * title (octagon, FR-024) via ui/Avatar and recalculates on renames;
 * the title itself is resolved by the page — the live №28 title
 * while the view is open (the optimistic `group.updated` half), the
 * №12/№27 answer until then.
 */
import { Avatar } from '../../ui/Avatar'
import { pluralRu } from '../../ui/time'
import { PresenceIndicator } from '../../presence/PresenceIndicator'
import './chat-header.css'

/** Header avatar size — design-tokens §4 (заголовок 46px). */
const HEADER_AVATAR_SIZE = 46

/** The direct dialog of the header (the 004 pair dialog surface). */
export interface DirectChatHeaderData {
  /** The peer of the 1:1 dialog — the presence surface userId (007). */
  readonly peerId: string
  readonly username: string
  /** The blocker-side mark «заблокирован» (№12 projection, FR-020). */
  readonly blockedByMe: boolean
}

/** The group window of the header (feature 006). */
export interface GroupChatHeaderData {
  /** Resolved by the page: the live №28 title or the №12/№27 answer. */
  readonly title: string
  /** Live №28 roster length or the №12/№13/№27 memberCount; null — unknown. */
  readonly memberCount: number | null
}

/** One header, two variants — the page discriminates by `kind`. */
export type ChatHeaderChat =
  | ({ readonly kind: 'direct' } & DirectChatHeaderData)
  | ({ readonly kind: 'group' } & GroupChatHeaderData)

export interface ChatHeaderProps {
  /** The open dialog of either kind — drives avatar shape and status. */
  readonly chat: ChatHeaderChat
  /** The direct «Действия» menu state + its №14/№23-24 actions (T060 wiring). */
  readonly menuOpen: boolean
  readonly onToggleMenu: () => void
  readonly onDeleteChat: () => void
  readonly onToggleBlock: () => void
  /** The group card toggle state (US3 T046a wiring). */
  readonly groupInfoOpen: boolean
  readonly onToggleGroupInfo: () => void
}

/** «N участников» — the prototype membersCount (§7 chatView, ui/time pluralRu). */
function membersStatus(count: number): string {
  return `${count} ${pluralRu(count, 'участник', 'участника', 'участников')}`
}

export function ChatHeader({
  chat,
  menuOpen,
  onToggleMenu,
  onDeleteChat,
  onToggleBlock,
  groupInfoOpen,
  onToggleGroupInfo,
}: ChatHeaderProps) {
  const isGroup = chat.kind === 'group'
  const name = isGroup ? chat.title : chat.username

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
          chat.memberCount !== null && (
            <div className="status-row">
              <span className="status-txt">{membersStatus(chat.memberCount)}</span>
            </div>
          )
        ) : (
          <div className="status-row">
            <PresenceIndicator userId={chat.peerId} showLabel={true} />
            {chat.blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
          </div>
        )}
      </div>
      <div className="ch-btns">
        {isGroup ? (
          <button
            type="button"
            className="dialog-group-info-toggle"
            aria-expanded={groupInfoOpen}
            aria-controls="dialog-group-card"
            onClick={onToggleGroupInfo}
          >
            Информация о группе
          </button>
        ) : (
          <div className="dialog-menu">
            <button
              type="button"
              className="dialog-menu-toggle"
              aria-haspopup="menu"
              aria-expanded={menuOpen}
              onClick={onToggleMenu}
            >
              Действия
            </button>
            {menuOpen && (
              <div className="dialog-menu-items" role="menu" aria-label="Действия с чатом">
                <button
                  type="button"
                  role="menuitem"
                  className="dialog-menu-item"
                  onClick={onDeleteChat}
                >
                  Удалить чат
                </button>
                <button
                  type="button"
                  role="menuitem"
                  className="dialog-menu-item"
                  onClick={onToggleBlock}
                >
                  {chat.blockedByMe ? 'Разблокировать пользователя' : 'Заблокировать пользователя'}
                </button>
              </div>
            )}
          </div>
        )}
      </div>
    </header>
  )
}
