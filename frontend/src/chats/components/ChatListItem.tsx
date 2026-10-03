/**
 * One «Чаты» row (feature 004, T058; FR-014, FR-020): renders the peer
 * login, the last visible message preview, the numeric unread badge and
 * — for the blocker — the «заблокирован» mark. Everything the row needs
 * already comes from the №12 aggregate (`ChatListItem`): the panel never
 * issues per-chat requests.
 *
 * Unified list (feature 006, T028; FR-014): №12 now also carries group
 * elements, and the row discriminates by `type` — a group renders the
 * group avatar glyph, `title` and the `memberCount` counter (Russian
 * plurals) while `peer`/`blockedByMe` are null (api-contract.md §3);
 * blocks never apply to groups. A direct row keeps rendering the peer
 * login exactly as in 004 (the `type` field may be absent —
 * backward-friendly) and never carries a member counter.
 *
 * Preview: the server sends the FULL last message text and the contract
 * (№12) makes truncation a client render decision — the row clamps it
 * to 64 code points with an ellipsis. `null` lastMessage (an empty or
 * fully deleted-for-me dialog) renders the muted «Нет сообщений» line.
 *
 * Badge (FR-014; feature 005 T035, FR-007/FR-008): the counter is
 * server-authoritative and delivery-bounded — useChatList maintains it
 * as an optimistic cache over №12, №26 sync deltas and realtime frames,
 * converging to the server value. «99+» above ninety-nine is a RENDER
 * decision over the exact count: the internal `unreadCount` stays
 * exact (US3-4) and never affects the ordering (positions are owned by
 * useChatList sorting). While the chat is blocked the displayed value
 * is whatever useChatList froze at the block moment (US3-5). Group
 * rows badge exactly like direct ones (FR-013/FR-014).
 *
 * Blocked mark (FR-020): only the blocker sees «заблокирован» —
 * `blockedByMe` is the single block projection the API exposes, so
 * the blocked side's row renders without any mark by construction.
 *
 * Presence dot (feature 007, T022; FR-006, clarify a11y): DIRECT rows
 * only — a group carries no presence UI (YAGNI; №12 group elements
 * have `peer = null` by contract). The dot's aria-label carries the
 * state; no visible text on this surface (the text label belongs to
 * the 1:1 dialog header alone).
 *
 * «Aethergram» reskin (feature 008, US1; research §C, FR-034): the
 * badge and preview nodes additionally carry the PROTOTYPE row hooks
 * `.c-badge`/`.c-prev` beside the 004 hooks — the T019 share of the
 * T017 adaptation (ChatListPanel tests); the full row rebuild — the
 * Avatar, the c-top/c-main structure, the last-message time — is T020.
 */
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import { PresenceIndicator } from '../../presence/PresenceIndicator'

/** Превью последнего сообщения: обрезка ≤64 симв. — клиентский рендер (контракт №12). */
const PREVIEW_MAX_LENGTH = 64

/** Бейдж непрочитанных сворачивается в «99+» (FR-014). */
const UNREAD_CAP = 99

/**
 * Русская плюрализация счётчика участников (№12 `memberCount` 1–200):
 * 1/21 участник, 3 участника, 5/11 участников.
 */
function membersLabel(count: number): string {
  const mod10 = count % 10
  const mod100 = count % 100
  if (mod10 === 1 && mod100 !== 11) {
    return 'участник'
  }
  if (mod10 >= 2 && mod10 <= 4 && (mod100 < 12 || mod100 > 14)) {
    return 'участника'
  }
  return 'участников'
}

export interface ChatListItemProps {
  /** №12 aggregate row: peer, lastMessage, unreadCount, blockedByMe. */
  readonly item: ChatListItemData
  /** Current user id: outgoing last messages get the «Вы:» preview prefix. */
  readonly currentUserId?: string | null
  /** The open dialog renders highlighted (aria-current). */
  readonly active?: boolean
  /** Opens the pair dialog. */
  readonly onSelect?: (chatId: string) => void
}

function previewText(text: string): string {
  if (text.length <= PREVIEW_MAX_LENGTH) {
    return text
  }
  return `${Array.from(text).slice(0, PREVIEW_MAX_LENGTH).join('')}…`
}

export function ChatListItem({
  item,
  currentUserId = null,
  active = false,
  onSelect,
}: ChatListItemProps) {
  const last = item.lastMessage
  const isGroup = item.type === 'group'
  const title = isGroup ? item.title : item.peer?.username
  const outgoing = last !== null && last.senderId === currentUserId
  const unreadLabel = item.unreadCount > UNREAD_CAP ? `${UNREAD_CAP}+` : String(item.unreadCount)

  return (
    <li>
      <button
        type="button"
        className={active ? 'chat-item chat-item-active' : 'chat-item'}
        aria-current={active ? 'true' : undefined}
        onClick={() => {
          onSelect?.(item.chatId)
        }}
      >
        <span className="chat-item-head">
          {isGroup && (
            <span className="chat-item-avatar" aria-hidden="true">
              #
            </span>
          )}
          <span className="chat-item-title">{title ?? ''}</span>
          {!isGroup && item.peer !== null && <PresenceIndicator userId={item.peer.id} />}
          {isGroup && item.memberCount !== undefined && (
            <span className="chat-item-members">
              {item.memberCount} {membersLabel(item.memberCount)}
            </span>
          )}
          {item.blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
          {item.unreadCount > 0 && <span className="chat-item-badge c-badge">{unreadLabel}</span>}
        </span>
        {last === null ? (
          <span className="chat-item-preview chat-item-preview-empty c-prev">Нет сообщений</span>
        ) : (
          <span className="chat-item-preview c-prev">
            {outgoing ? 'Вы: ' : ''}
            {previewText(last.text)}
          </span>
        )}
      </button>
    </li>
  )
}
