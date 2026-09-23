/**
 * One «Чаты» row (feature 004, T058; FR-014, FR-020): renders the peer
 * login, the last visible message preview, the numeric unread badge and
 * — for the blocker — the «заблокирован» mark. Everything the row needs
 * already comes from the №12 aggregate (`ChatListItem`): the panel never
 * issues per-chat requests.
 *
 * Preview: the server sends the FULL last message text and the contract
 * (№12) makes truncation a client render decision — the row clamps it
 * to 64 code points with an ellipsis. `null` lastMessage (an empty or
 * fully deleted-for-me dialog) renders the muted «Нет сообщений» line.
 *
 * Badge (FR-014): numeric counter, «99+» above ninety-nine — it is a
 * render decision over the exact server count and never affects the
 * ordering (positions are owned by useChatList sorting).
 *
 * Blocked mark (FR-020): only the blocker sees «заблокирован» —
 * `blockedByMe` is the single block projection the API exposes, so
 * the blocked side's row renders without any mark by construction.
 */
import type { ChatListItem as ChatListItemData } from '../../api/chats'

/** Превью последнего сообщения: обрезка ≤64 симв. — клиентский рендер (контракт №12). */
const PREVIEW_MAX_LENGTH = 64

/** Бейдж непрочитанных сворачивается в «99+» (FR-014). */
const UNREAD_CAP = 99

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
          <span className="chat-item-title">{item.peer.username}</span>
          {item.blockedByMe && <span className="chat-item-blocked">заблокирован</span>}
          {item.unreadCount > 0 && <span className="chat-item-badge">{unreadLabel}</span>}
        </span>
        {last === null ? (
          <span className="chat-item-preview chat-item-preview-empty">Нет сообщений</span>
        ) : (
          <span className="chat-item-preview">
            {outgoing ? 'Вы: ' : ''}
            {previewText(last.text)}
          </span>
        )}
      </button>
    </li>
  )
}
