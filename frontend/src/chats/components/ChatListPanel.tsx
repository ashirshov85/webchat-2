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
 * «Чаты» states: loading, error with a retry (the list refetches via
 * `onReload` — the same path SSE reconnects use), empty (a first
 * incoming from a stranger lands here automatically, FR-019) and the
 * list itself. Rows (ChatListItem) render the preview, the unread
 * badge («99+») and the «заблокирован» mark (FR-014/020); clicking a
 * row opens the pair dialog via `onSelectChat`, the open one is
 * highlighted by `activeChatId`.
 */
import { useState } from 'react'
import type { ReactNode } from 'react'
import type { ChatListItem as ChatListItemData } from '../../api/chats'
import type { ChatListStatus } from '../hooks/useChatList'
import { ErrorBanner } from './ErrorBanner'
import { ChatListItem } from './ChatListItem'

export type ChatListPanelMode = 'chats' | 'contacts'

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
        {chats.length > 0 && (
          <ul className="chat-list" aria-label="Список чатов">
            {chats.map((item) => (
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
