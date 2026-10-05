/**
 * Left panel of the messenger (feature 004, T058; FR-013/014): the
 * «Чаты» list itself. The data comes from useChatList (T057) — the
 * server-owned №12 ordering (last visible message first, messageless
 * chats below) plus the live event updates; the panel is render-only
 * and adds no requests.
 *
 * Unified list + search (feature 006, T028; FR-014): direct dialogs and
 * groups render in ONE «Чаты» list — a group row shows its `title`, a
 * direct row the peer login (ChatListItem discriminates by `type`). The
 * search field filters the RENDERED rows live, case-insensitively, by
 * the group title or the peer login: the server keeps owning the
 * ordering, the panel only filters what it already renders (no extra
 * requests); clearing the query restores the full list.
 *
 * «Чаты» states: loading, error with a retry (the list refetches via
 * `onReload` — the same path SSE reconnects use), empty (a first
 * incoming from a stranger lands here automatically, FR-019), the
 * list itself and the «Ничего не найдено» hint of an over-narrow
 * query. Rows (ChatListItem) render the preview, the unread badge
 * («99+», exact internal count — the optimistic cache of the
 * server-authoritative counter that useChatList converges over №12,
 * №26 sync deltas and realtime frames, feature 005 T035) and the
 * «заблокирован» mark (FR-014/020); clicking a row opens the pair
 * dialog via `onSelectChat`, the open one is highlighted by
 * `activeChatId`.
 *
 * «Aethergram» reskin (feature 008, US1, T019; FR-001): the search
 * field rides the PROTOTYPE search row — `.search-row >
 * .search-wrap` with the engraved magnifier glyph and the golden
 * frame of `#search` (design/chats.html §5, chat-list-panel.css) —
 * while the 004 test hook `chat-panel-search` stays as the input's
 * class (research §C, FR-034).
 *
 * Feature 008 (US2, T029; FR-006, research §D, ui-behavior §2): the
 * «Чаты»/«Контакты» mode tabs are GONE — the chat list renders right
 * away and the search filters it by the chat NAME (FR-010: group
 * title / peer login — never by message content, that is feature
 * 013). Contacts are no longer a sidebar section: they live in the
 * ContactsModal (T031/T032) opened through the MainMenuButton of the
 * search row (T030), so the panel carries no contacts content prop
 * anymore. The 004 reachability of every contact operation is
 * preserved — only the entry point moves (FR-034, SC-004).
 */
import { useState } from 'react'
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import type { ChatListStatus } from '../hooks/useChatList'
import { ErrorBanner } from './ErrorBanner'
import { ChatListItem } from './ChatListItem'
import { MainMenuButton } from './MainMenuButton'
import './chat-list-panel.css'
import './states.css'

/**
 * FR-014 row matching: a group answers by its `title`, a direct dialog
 * by the peer login — both case-insensitively. A group whose №12
 * aggregate has not landed yet (a bare realtime row) matches nothing:
 * the aggregate converges on the next №12 refetch.
 */
function chatMatchesQuery(item: ChatListItemData, normalizedQuery: string): boolean {
  if (normalizedQuery === '') {
    return true
  }
  const label = item.type === 'group' ? item.title : item.peer?.username
  return label !== undefined && label.toLowerCase().includes(normalizedQuery)
}

export interface ChatListPanelProps {
  /** №12 aggregate rows as maintained by useChatList (already ordered). */
  readonly chats: readonly ChatListItemData[]
  /** List fetch state from useChatList; defaults to `ready`. */
  readonly status?: ChatListStatus
  /** The error behind the `error` status (problemMessage renders it). */
  readonly error?: unknown
  /** Refetches the list (error retry; the hook's `reload`). */
  readonly onReload?: () => void
  /** The open dialog: its row renders highlighted. */
  readonly activeChatId?: string | null
  /** Opens the pair dialog when a row is clicked. */
  readonly onSelectChat?: (chatId: string) => void
  /** Current user id: passed through to rows for the «Вы:» preview prefix. */
  readonly currentUserId?: string | null
  /**
   * US2 (T030/T034): the MainMenuButton of the search row opens the
   * single ModalShell forms hosted by MessengerPage — «Мой профиль»
   * (profile), «Контакты» (contacts), «Создать групповой чат»
   * (create-group). The panel is only the entry point (passed through
   * to MainMenuButton).
   */
  readonly onOpenProfile?: () => void
  readonly onOpenContacts?: () => void
  readonly onCreateGroup?: () => void
}

export function ChatListPanel({
  chats,
  status = 'ready',
  error = null,
  onReload,
  activeChatId = null,
  onSelectChat,
  currentUserId = null,
  onOpenProfile,
  onOpenContacts,
  onCreateGroup,
}: ChatListPanelProps) {
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim().toLowerCase()
  const visibleChats =
    normalizedQuery === '' ? chats : chats.filter((item) => chatMatchesQuery(item, normalizedQuery))

  return (
    <div className="chat-panel">
      <div className="search-row">
        <MainMenuButton
          onOpenProfile={onOpenProfile}
          onOpenContacts={onOpenContacts}
          onCreateGroup={onCreateGroup}
        />
        <div className="search-wrap">
          <svg viewBox="0 0 24 24" aria-hidden="true" focusable="false">
            <circle cx="10.5" cy="10.5" r="6.5" />
            <path d="M15.5 15.5 21 21" />
          </svg>
          <input
            type="search"
            className="chat-panel-search"
            aria-label="Поиск чатов"
            placeholder="Поиск…"
            autoComplete="off"
            value={query}
            onChange={(event) => {
              setQuery(event.target.value)
            }}
          />
        </div>
      </div>
      {status === 'loading' && <p className="messenger-empty">Загрузка чатов…</p>}
      {status === 'error' && (
        <div className="chat-panel-error">
          <ErrorBanner error={error} />
          {onReload !== undefined && (
            <button
              type="button"
              className="chat-panel-retry"
              onClick={() => {
                onReload()
              }}
            >
              Повторить
            </button>
          )}
        </div>
      )}
      {status === 'ready' && chats.length === 0 && (
        <p className="messenger-empty">Диалогов пока нет</p>
      )}
      {status === 'ready' && chats.length > 0 && visibleChats.length === 0 && (
        <p className="messenger-empty">Ничего не найдено</p>
      )}
      {visibleChats.length > 0 && (
        <ul className="chat-list" aria-label="Список чатов">
          {visibleChats.map((item) => (
            <ChatListItem
              key={item.chatId}
              item={item}
              currentUserId={currentUserId}
              active={item.chatId === activeChatId}
              onSelect={onSelectChat}
            />
          ))}
        </ul>
      )}
    </div>
  )
}
