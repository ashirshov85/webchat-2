/**
 * One «Чаты» row (feature 004, T058; FR-014, FR-020): renders the peer
 * login, the last visible message preview, the numeric unread badge and
 * — for the blocker — the «заблокирован» mark. Everything the row needs
 * already comes from the №12 aggregate (`ChatListItem`): the panel never
 * issues per-chat requests.
 *
 * Unified list (feature 006, T028; FR-014): №12 now also carries group
 * elements, and the row discriminates by `type` — a group renders the
 * group avatar and `title` while `peer`/`blockedByMe` are null
 * (api-contract.md §3); blocks never apply to groups. A direct row keeps
 * rendering the peer login exactly as in 004 (the `type` field may be
 * absent — backward-friendly).
 *
 * Preview: the server sends the FULL last message text and the contract
 * (№12) makes truncation a client render decision — the DOM text is
 * clamped to 64 code points with an ellipsis, the CSS clamps further,
 * and the FULL text rides the `title` tooltip (spec edge-case «полный
 * текст — во всплывающей подсказке»). `null` lastMessage (an empty or
 * fully deleted-for-me dialog) renders the muted «Нет сообщений» line.
 * The «Вы: » prefix stays the 004 rule for every OUTGOING message; an
 * incoming GROUP message renders bare: №12's `lastMessage` carries
 * `senderId` only, the roster with usernames lives in №28 of the OPEN
 * chat alone, SC-003 forbids contract changes and the panel issues no
 * per-chat requests — the «Имя: »-prefixed preview stays the feed's
 * privilege (T022, MessageList's `members` prop).
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
 * «Aethergram» reskin (feature 008, US1, T020; FR-001, FR-008,
 * data-model 2.1, research §C): the row rides the PROTOTYPE markup of
 * design/chats.html §5 — `.contact` button > Avatar + `.c-main`
 * (`.c-top`: `.c-name` + `.c-time` / `.c-prev`) + `.c-badge`; the 004
 * hooks (`chat-item`, `chat-item-badge`, `chat-item-blocked`,
 * `chat-item-preview`, `c-prev`/`c-badge` of T019) stay as wrappers
 * (FR-034). The avatar derives from `peer.username` (circle) or the
 * group `title` (octagon, FR-024) and recalculates itself on renames;
 * the `.c-time` node carries the ЧЧ:ММ (ui/time) of
 * `lastMessage.createdAt` and renders EMPTY for a messageless chat
 * (`${last?last.time:''}` of the prototype); long names/previews
 * truncate via CSS with the full text in `title` tooltips. The №12
 * `memberCount` LEAVES the row (the prototype carries no counter) and
 * returns as the «N участников» header status of US3 (T043).
 *
 * Presence dot (feature 008, US3, T042; FR-024, design-tokens §4,
 * контракт T040): DIRECT rows only — the status comes from
 * presenceStore 007 via `usePresenceStatus(peer.id)` (per-row surface
 * registration + №36 backfill, семантика 007) and rides the Avatar's
 * `presenceDot`: online — зелёная мерцающая, offline — тусклая,
 * unknown — нейтральная (БЕЗ ложного «офлайн» до первого №36);
 * a GROUP row carries no presence UI (006/007) — `null`.
 * React.memo + the row's `content-visibility`
 * (chat-list-panel.css) keep a 200+ list at 60 fps (SC-011, §G).
 */
import { memo } from 'react'
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import { usePresenceStatus } from '../../presence/usePresence'
import { Avatar } from '../../ui/Avatar'
import { formatTime } from '../../ui/time'

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

export const ChatListItem = memo(function ChatListItem({
  item,
  currentUserId = null,
  active = false,
  onSelect,
}: ChatListItemProps) {
  const last = item.lastMessage
  const isGroup = item.type === 'group'
  const title = isGroup ? item.title : item.peer?.username
  const peerId = !isGroup && item.peer !== null ? item.peer.id : null
  // Состояние — ТОЛЬКО из presenceStore 007 (T040): null-ключ (группа/
  // защитный direct без peer) ничего не региструет и точку не даёт.
  const presence = usePresenceStatus(peerId)
  const outgoing = last !== null && last.senderId === currentUserId
  const unreadLabel = item.unreadCount > UNREAD_CAP ? `${UNREAD_CAP}+` : String(item.unreadCount)

  return (
    <li>
      <button
        type="button"
        className={active ? 'chat-item contact active' : 'chat-item contact'}
        aria-current={active ? 'true' : undefined}
        onClick={() => {
          onSelect?.(item.chatId)
        }}
      >
        <Avatar
          source={title ?? ''}
          shape={isGroup ? 'octagon' : 'circle'}
          presenceDot={peerId !== null ? presence : null}
        />
        <span className="c-main">
          <span className="c-top">
            <span className="c-name" title={title ?? ''}>
              {title ?? ''}
            </span>
            {item.blockedByMe === true && <span className="chat-item-blocked">заблокирован</span>}
            <span className="c-time">{last !== null ? formatTime(last.createdAt) : ''}</span>
          </span>
          {last === null ? (
            <span className="chat-item-preview chat-item-preview-empty c-prev">Нет сообщений</span>
          ) : (
            <span
              className="chat-item-preview c-prev"
              title={`${outgoing ? 'Вы: ' : ''}${last.text}`}
            >
              {outgoing ? 'Вы: ' : ''}
              {previewText(last.text)}
            </span>
          )}
        </span>
        {item.unreadCount > 0 && <span className="chat-item-badge c-badge">{unreadLabel}</span>}
      </button>
    </li>
  )
})
