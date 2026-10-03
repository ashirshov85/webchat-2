/**
 * Left panel of the messenger (feature 004, T058; FR-013/014): the
 * «Чаты»/«Контакты» mode switch and the «Чаты» list itself. The data
 * comes from useChatList (T057) — the server-owned №12 ordering
 * (last visible message first, messageless chats below) plus the live
 * event updates; the panel is render-only and adds no requests.
 *
 * FR-013 «состояние списков сохраняется»: both mode sections stay
 * mounted and the inactive one is toggled with the `hidden` attribute,
 * so switching modes never unmounts a list — scroll positions and any
 * state inside the «Контакты» content (T059 sort toggle, search field)
 * survive the round trip. The default mode is «Чаты» (FR-013).
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
 * class (research §C, FR-034). The tabs stay until US2 removes them
 * (T029) and are only restyled into the machine vocabulary
 * (Cormorant SC uppercase plates); the MainMenuButton that joins the
 * search row per the prototype is T030.
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import type { ChatListStatus } from '../hooks/useChatList'
import { ErrorBanner } from './ErrorBanner'
import { ChatListItem } from './ChatListItem'
import './chat-list-panel.css'

export type ChatListPanelMode = 'chats' | 'contacts'

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
  /** Content of the «Контакты» mode (T059: search box + contact list). */
  readonly contacts?: ReactNode
  /** Initial mode; FR-013 default is «Чаты». */
  readonly defaultMode?: ChatListPanelMode
}

export function ChatListPanel({
  chats,
  status = 'ready',
  error = null,
  onReload,
  activeChatId = null,
  onSelectChat,
  currentUserId = null,
  contacts,
  defaultMode = 'chats',
}: ChatListPanelProps) {
  const [mode, setMode] = useState<ChatListPanelMode>(defaultMode)
  const [query, setQuery] = useState('')
  const normalizedQuery = query.trim().toLowerCase()
  const visibleChats =
    normalizedQuery === '' ? chats : chats.filter((item) => chatMatchesQuery(item, normalizedQuery))

  return (
    <div className="chat-panel">
      <div className="chat-panel-tabs" role="tablist" aria-label="Режимы панели">
        <button
          type="button"
          role="tab"
          id="chat-panel-tab-chats"
          aria-selected={mode === 'chats'}
          aria-controls="chat-panel-section-chats"
          className={mode === 'chats' ? 'chat-panel-tab chat-panel-tab-active' : 'chat-panel-tab'}
          onClick={() => {
            setMode('chats')
          }}
        >
          Чаты
        </button>
        <button
          type="button"
          role="tab"
          id="chat-panel-tab-contacts"
          aria-selected={mode === 'contacts'}
          aria-controls="chat-panel-section-contacts"
          className={
            mode === 'contacts' ? 'chat-panel-tab chat-panel-tab-active' : 'chat-panel-tab'
          }
          onClick={() => {
            setMode('contacts')
          }}
        >
          Контакты
        </button>
      </div>

      <div
        role="tabpanel"
        id="chat-panel-section-chats"
        aria-labelledby="chat-panel-tab-chats"
        className="chat-panel-section"
        hidden={mode !== 'chats'}
      >
        <div className="search-row">
          {/* .menu-btn (главное меню, US2/T030) встраивается сюда же —
              слева от поля, как в прототипе §5. */}
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

      <div
        role="tabpanel"
        id="chat-panel-section-contacts"
        aria-labelledby="chat-panel-tab-contacts"
        className="chat-panel-section"
        hidden={mode !== 'contacts'}
      >
        {contacts === undefined ? (
          <p className="messenger-empty">Контакты появятся здесь</p>
        ) : (
          contacts
        )}
      </div>
    </div>
  )
}
